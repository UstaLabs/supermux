import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { spawn } from "node:child_process"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from "node:fs"
import { createServer } from "node:net"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { supermux } from "../src/index.js"
import { connectKeeper } from "../src/keeper/index.js"
import { keeperSocketPaths } from "../src/keeper/socket.js"
import { SUN_PATH_MAX } from "../src/unix-socket.js"

setDefaultTimeout(30_000)

const fixture = fileURLToPath(new URL("./fixtures/claude-agent.mjs", import.meta.url))
const echo = fileURLToPath(new URL("./fixtures/echo-agent.mjs", import.meta.url))
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

test("keeperSocketPaths: <dir>/keeper.sock when it fits sun_path, else one short path per directory under the temp dir, /tmp, then the runtime dir", () => {
  const base = mkdtempSync(join(tmpdir(), "socket-paths-"))
  dirs.push(base)
  const short = join(base, "state", "keepers", "a")
  expect(keeperSocketPaths(short)).toEqual([join(short, "keeper.sock")])
  const deep = join(base, "x".repeat(120), "keepers")
  const runtime = join(base, "run")
  mkdirSync(runtime)
  const tmp = join(base, "tmp")
  const paths = keeperSocketPaths(join(deep, "a"), { XDG_RUNTIME_DIR: runtime }, tmp)
  // The runtime dir last: it is deleted at logout (without lingering), under a detached keeper.
  expect(paths.map(path => dirname(dirname(path)))).toEqual([tmp, "/tmp", runtime])
  for (const path of paths) expect(Buffer.byteLength(path)).toBeLessThanOrEqual(SUN_PATH_MAX)
  // One name per keeper directory, the same each time.
  expect(keeperSocketPaths(join(deep, "a"), { XDG_RUNTIME_DIR: runtime }, tmp)).toEqual(paths)
  const other = keeperSocketPaths(join(deep, "b"), { XDG_RUNTIME_DIR: runtime }, tmp)
  expect(other[0]).not.toBe(paths[0])
  expect(dirname(other[0]!)).toBe(dirname(paths[0]!))
  // A runtime dir that is not there, or not absolute, is skipped; so is a temp dir too long to hold the path.
  expect(keeperSocketPaths(join(deep, "a"), { XDG_RUNTIME_DIR: join(base, "missing") }, tmp).map(path => dirname(dirname(path)))).toEqual([tmp, "/tmp"])
  expect(keeperSocketPaths(join(deep, "a"), { XDG_RUNTIME_DIR: "run" }, tmp).map(path => dirname(dirname(path)))).toEqual([tmp, "/tmp"])
  expect(keeperSocketPaths(join(deep, "a"), {}, join(base, "t".repeat(120))).map(path => dirname(dirname(path)))).toEqual(["/tmp"])
  expect(keeperSocketPaths(join(deep, "a"), { XDG_RUNTIME_DIR: runtime }, join(base, "t".repeat(120))).map(path => dirname(dirname(path)))).toEqual(["/tmp", runtime])
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

/** A sacrificial process: what a pid recorded by a keeper that died may belong to now. */
function otherProcess() {
  const child = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], { stdio: "ignore" })
  pids.push(child.pid!)
  return child.pid!
}

const SOCKET_DIRECTORIES: Array<[string, (path: string) => void]> = [
  ["a directory others may enter (0755)", path => { mkdirSync(path, { mode: 0o755 }); chmodSync(path, 0o755) }],
  ["a symlink to a private directory", path => { mkdirSync(`${path}-real`, { mode: 0o700 }); symlinkSync(`${path}-real`, path) }],
]

for (const [what, make] of SOCKET_DIRECTORIES) {
  test(`a recorded keeper socket in ${what} is never sent the session's token: a new keeper starts`, async () => {
    const base = mkdtempSync(join(tmpdir(), "keeper-trust-"))
    dirs.push(base)
    const state = join(base, "state")
    const keeperDir = join(state, "keepers", "s1")
    mkdirSync(keeperDir, { recursive: true })
    writeFileSync(join(keeperDir, "token"), "the-session-token\n")
    // Someone else listening where a keeper of this session once did, its pid reused by another process.
    const socketDir = join(base, "sockets")
    make(socketDir)
    const socket = join(socketDir, "k.sock")
    const heard: string[] = []
    const fake = createServer(sock => sock.on("data", chunk => heard.push(String(chunk))))
    await new Promise<void>(resolve => fake.listen(socket, resolve))
    const other = otherProcess()
    writeFileSync(join(keeperDir, "status.json"), JSON.stringify({
      keeperPid: other, agentPid: 0, startedAt: 1, lastSeq: 0, firstSeq: 1, ackedSeq: 0, updatedAt: 1, meta: {}, socket,
    }))
    try {
      const conn = await connectKeeper({
        stateDirectory: state, sessionId: "s1",
        spec: { command: process.execPath, args: [echo], cwd: process.cwd(), env: { PATH: process.env.PATH ?? "" }, frameShape: "jsonrpc", captureStderr: false },
        // Also how long the new keeper may take to start: generous, for a loaded machine.
        limits: { maxFrameBytes: 4096, shutdownTimeoutMs: 200, parkedDeadlineMs: 5000, journalMaxBytes: 64_000, connectTimeoutMs: 4000 },
        cursor: 0,
      })
      const started = keeperStatus(state, "s1")
      pids.push(started.keeperPid, started.agentPid)
      await conn.shutdown()
      expect(started.keeperPid).not.toBe(other)
      expect(started.socket).not.toBe(socket)
      expect(heard).toEqual([])
      expect(alive(other)).toBe(true)
    } finally {
      await new Promise<void>(resolve => fake.close(() => resolve()))
    }
  })
}
