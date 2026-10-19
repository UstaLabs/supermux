import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { supermux } from "../src/index.js"
import { keeperSocketPath } from "../src/keeper/socket.js"

setDefaultTimeout(30_000)

/** Longest usable sun_path in bytes, without the NUL: 108 on Linux, 104 on macOS. */
const SUN_PATH_MAX = process.platform === "linux" ? 107 : 103
const fixture = fileURLToPath(new URL("./fixtures/claude-agent.mjs", import.meta.url))
const claudeFixture = () => ({ command: process.execPath, args: [fixture], env: { EXPECT_DEFAULT_DENY: "0" } })
const text = (value: string) => [{ type: "text" as const, text: value }]
const dirs: string[] = []
const pids: number[] = []

function alive(pid: number) {
  try { process.kill(pid, 0); return true } catch { return false }
}

async function waitDead(pid: number, ms = 5000) {
  const start = Date.now()
  while (alive(pid) && Date.now() - start < ms) await new Promise(resolve => setTimeout(resolve, 20))
}

afterEach(() => {
  for (const pid of pids.splice(0)) if (alive(pid)) try { process.kill(pid, "SIGKILL") } catch { /* gone */ }
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true })
})

const keeperStatus = (state: string, id: string) => JSON.parse(readFileSync(join(state, "keepers", id, "status.json"), "utf8"))

test("keeperSocketPath: <dir>/keeper.sock when it fits sun_path, else one short path per directory in a per-user temp directory", () => {
  const short = join(tmpdir(), "state", "keepers", "a")
  expect(keeperSocketPath(short)).toBe(join(short, "keeper.sock"))
  const deep = join(tmpdir(), "x".repeat(120), "keepers")
  const path = keeperSocketPath(join(deep, "a"))
  expect(Buffer.byteLength(path)).toBeLessThanOrEqual(SUN_PATH_MAX)
  expect(dirname(dirname(path))).toBe(tmpdir())
  expect(keeperSocketPath(join(deep, "a"))).toBe(path)
  const other = keeperSocketPath(join(deep, "b"))
  expect(other).not.toBe(path)
  expect(dirname(other)).toBe(dirname(path))
})

test("a state directory too deep for sun_path: the keeper listens at a short private path, and a session opens, detaches and re-attaches", async () => {
  const dir = mkdtempSync(join(tmpdir(), "keeper-deep-"))
  dirs.push(dir)
  // Like the kanban example's state inside a repository nested deep in a home directory.
  const state = join(dir, "work", "a-repository-nested-deep-in-a-home-directory".repeat(2), ".supermux-kanban")
  const keeperDir = join(state, "keepers", "deep-1")
  expect(Buffer.byteLength(join(keeperDir, "keeper.sock"))).toBeGreaterThan(SUN_PATH_MAX)
  const open = () => supermux({ stateDirectory: state, agents: { claude: claudeFixture() } })
  let core = open()
  try {
    const session = await core.sessions.create({ id: "deep-1", agent: "claude", cwd: dir })
    expect((await (await session.send({ content: text("hello"), whenBusy: "queue" })).completed).status).toBe("completed")
    const started = keeperStatus(state, "deep-1")
    pids.push(started.keeperPid, started.agentPid)
    // Not inside the keeper directory (Node cannot bind or connect there), but a short path in a private directory.
    expect(existsSync(join(keeperDir, "keeper.sock"))).toBe(false)
    expect(typeof started.socket).toBe("string")
    expect(Buffer.byteLength(started.socket)).toBeLessThanOrEqual(SUN_PATH_MAX)
    expect(statSync(started.socket).isSocket()).toBe(true)
    expect(statSync(dirname(started.socket)).mode & 0o777).toBe(0o700)

    await core.close({ agents: "detach" })
    expect(alive(started.keeperPid)).toBe(true)
    core = open()
    const resumed = await core.sessions.resume("deep-1")
    // Re-attached to the same keeper, not relaunched.
    expect(keeperStatus(state, "deep-1").keeperPid).toBe(started.keeperPid)
    expect((await (await resumed.send({ content: text("again"), whenBusy: "queue" })).completed).status).toBe("completed")

    await core.close({ agents: "shutdown" })
    await waitDead(started.keeperPid)
    expect(alive(started.keeperPid)).toBe(false)
    expect(alive(started.agentPid)).toBe(false)
    expect(existsSync(started.socket)).toBe(false)
  } finally {
    await core.close({ agents: "shutdown" }).catch(() => {})
  }
})
