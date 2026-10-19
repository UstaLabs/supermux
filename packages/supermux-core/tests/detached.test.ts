import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { spawn, type ChildProcess } from "node:child_process"
import { once } from "node:events"
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { supermux, type Core } from "../src/index.js"

setDefaultTimeout(30_000)

// Sessions left running behind their keepers with no Session open on them: what a core that
// detached (or a host that restarted without resuming them) leaves behind.

const fixture = fileURLToPath(new URL("./fixtures/claude-agent.mjs", import.meta.url))
const AGENTS = ["claude", "codex", "grok", "opencode", "cursor", "antigravity"] as const
const dirs: string[] = []
const pids: number[] = []
const cores: Core[] = []

function alive(pid: number) {
  if (!Number.isInteger(pid) || pid < 1) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  for (const pid of pids.splice(0)) if (alive(pid)) try { process.kill(pid, "SIGKILL") } catch { /* gone */ }
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true })
})

function scratch() {
  const dir = mkdtempSync(join(tmpdir(), "detached-"))
  dirs.push(dir)
  return dir
}

/** A core over `state` whose Claude is the fixture; each launch of it appends its argv to `trace`. */
function claudeCore(state: string, trace: string) {
  const core = supermux({ stateDirectory: state, agents: { claude: { command: process.execPath, args: [fixture], env: { EXPECT_DEFAULT_DENY: "0", TRACE: trace } } } })
  cores.push(core)
  return core
}

const launches = (trace: string) => readFileSync(trace, "utf8").split("\n").filter(Boolean)
  .map(line => JSON.parse(line)).filter(entry => Array.isArray(entry?.argv)).length
const keeperStatus = (state: string, id: string) => JSON.parse(readFileSync(join(state, "keepers", id, "status.json"), "utf8"))

/** Session `id` created by one core that then detached, and a new core over the same state. */
async function detachedClaude(id: string) {
  const dir = scratch()
  const state = join(dir, "state")
  const trace = join(dir, "trace")
  const first = claudeCore(state, trace)
  await first.sessions.create({ id, agent: "claude", cwd: dir })
  const status = keeperStatus(state, id)
  pids.push(status.keeperPid, status.agentPid)
  await first.close({ agents: "detach" })
  expect(alive(status.keeperPid)).toBe(true)
  expect(alive(status.agentPid)).toBe(true)
  return { dir, state, trace, status, core: claudeCore(state, trace) }
}

/** Every built-in driver over `state` (none is launched: their sessions are adopted). */
function everyAgentCore(state: string, dir: string) {
  const core = supermux({
    stateDirectory: state,
    agents: { claude: {}, codex: {}, grok: {}, opencode: {}, cursor: {}, antigravity: { home: join(dir, "shadow"), userHome: join(dir, "user") } },
  })
  cores.push(core)
  return core
}

/** A process listening on `socket`. SIGTERM ends it, as it makes a keeper stop its agent and exit. */
async function listener(socket: string): Promise<ChildProcess> {
  const script = `require("node:net").createServer().listen(${JSON.stringify(socket)}, () => console.log("up")); process.on("SIGTERM", () => process.exit(0))`
  const child = spawn(process.execPath, ["-e", script], { stdio: ["ignore", "pipe", "inherit"] })
  pids.push(child.pid!)
  await once(child.stdout!, "data")
  return child
}

function writeKeeperStatus(dir: string, keeperPid: number, socket: string) {
  writeFileSync(join(dir, "status.json"), JSON.stringify({
    keeperPid, agentPid: 0, startedAt: Date.now(), lastSeq: 0, firstSeq: 1, ackedSeq: 0, updatedAt: Date.now(), meta: {}, socket,
  }))
}

/** Stands in for a running keeper of session `id`: a process listening on the keeper's socket, with the status a keeper writes. */
async function standInKeeper(state: string, id: string): Promise<ChildProcess> {
  const dir = join(state, "keepers", id)
  mkdirSync(dir, { recursive: true })
  const socket = join(dir, "keeper.sock")
  const child = await listener(socket)
  writeKeeperStatus(dir, child.pid!, socket)
  return child
}

test("sessions.close(id, shutdown) stops the agent a detached session left behind its keeper, without relaunching it; the record stays", async () => {
  const { trace, status, core } = await detachedClaude("d1")
  // detach stays a no-op for a session that is not open.
  await core.sessions.close("d1", { mode: "detach" })
  expect(alive(status.keeperPid)).toBe(true)

  await core.sessions.close("d1", { mode: "shutdown" })
  expect(alive(status.keeperPid)).toBe(false)
  expect(alive(status.agentPid)).toBe(false)
  expect(launches(trace)).toBe(1)
  expect((await core.sessions.get("d1"))?.id).toBe("d1")

  // Nothing runs any more: another shutdown is a no-op, and the session resumes on a new agent.
  await core.sessions.close("d1", { mode: "shutdown" })
  expect(launches(trace)).toBe(1)
  const resumed = await core.sessions.resume("d1")
  expect(launches(trace)).toBe(2)
  await resumed.close({ mode: "shutdown" })
})

test("every built-in driver stops a detached session's keeper on sessions.close(id, shutdown)", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = everyAgentCore(state, dir)
  for (const agent of AGENTS) {
    await core.sessions.adopt({ id: agent, agent, agentSessionId: `native-${agent}`, cwd: dir })
    const keeper = await standInKeeper(state, agent)
    const exited = once(keeper, "exit")
    await core.sessions.close(agent, { mode: "shutdown" })
    expect({ agent, alive: alive(keeper.pid!) }).toEqual({ agent, alive: false })
    await exited
  }
  // An unknown id, and a session with no keeper, stay no-ops.
  await core.sessions.close("unknown", { mode: "shutdown" })
  await core.sessions.close("claude", { mode: "shutdown" })
})

test("a saved session that cannot be read: sessions.close(id, shutdown) does not fail, and forget still deletes it", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = everyAgentCore(state, dir)
  expect(await core.sessions.list()).toEqual([])
  writeFileSync(join(state, "sessions", "broken.json"), "{")
  await core.sessions.close("broken", { mode: "shutdown" })
  await core.sessions.forget("broken")
  expect(existsSync(join(state, "sessions", "broken.json"))).toBe(false)
})

test("a keeper status whose pid now belongs to another process is never signalled: its socket no longer answers", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = everyAgentCore(state, dir)
  await core.sessions.adopt({ id: "stale", agent: "claude", agentSessionId: "native-stale", cwd: dir })
  const keeperDir = join(state, "keepers", "stale")
  mkdirSync(keeperDir, { recursive: true })
  // A keeper killed outright leaves its socket behind.
  const socket = join(keeperDir, "keeper.sock")
  const killed = await listener(socket)
  killed.kill("SIGKILL")
  await once(killed, "exit")
  const other = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], { stdio: "ignore" })
  pids.push(other.pid!)
  writeKeeperStatus(keeperDir, other.pid!, socket)
  await core.sessions.close("stale", { mode: "shutdown" })
  expect(alive(other.pid!)).toBe(true)
})

test("forget deletes the keeper directory of a session closed with shutdown, with its record", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = claudeCore(state, join(dir, "trace"))
  await core.sessions.create({ id: "f1", agent: "claude", cwd: dir })
  await core.sessions.close("f1", { mode: "shutdown" })
  expect(existsSync(join(state, "keepers", "f1", "status.json"))).toBe(true)
  await core.sessions.forget("f1")
  expect(existsSync(join(state, "keepers", "f1"))).toBe(false)
  expect(await core.sessions.get("f1")).toBeUndefined()
})

test("forget is session_busy while the agent of a detached session still runs: its record and keeper stay", async () => {
  const { state, status, core } = await detachedClaude("f2")
  await expect(core.sessions.forget("f2")).rejects.toMatchObject({ code: "session_busy" })
  expect(alive(status.keeperPid)).toBe(true)
  expect(alive(status.agentPid)).toBe(true)
  expect(existsSync(join(state, "keepers", "f2", "status.json"))).toBe(true)
  expect((await core.sessions.get("f2"))?.id).toBe("f2")

  await core.sessions.close("f2", { mode: "shutdown" })
  await core.sessions.forget("f2")
  expect(existsSync(join(state, "keepers", "f2"))).toBe(false)
  expect(await core.sessions.get("f2")).toBeUndefined()
})

test("every built-in driver: forget is session_busy while the keeper runs, and deletes its directory once it ended", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = everyAgentCore(state, dir)
  for (const agent of AGENTS) {
    await core.sessions.adopt({ id: agent, agent, agentSessionId: `native-${agent}`, cwd: dir })
    const keeper = await standInKeeper(state, agent)
    await expect(core.sessions.forget(agent)).rejects.toMatchObject({ code: "session_busy" })
    keeper.kill("SIGTERM")
    await once(keeper, "exit")
    await core.sessions.forget(agent)
    expect({ agent, kept: existsSync(join(state, "keepers", agent)) }).toEqual({ agent, kept: false })
  }
})

test("forget deletes the socket a keeper killed outright left outside its directory", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = everyAgentCore(state, dir)
  await core.sessions.adopt({ id: "crashed", agent: "claude", agentSessionId: "native-crashed", cwd: dir })
  const keeperDir = join(state, "keepers", "crashed")
  mkdirSync(keeperDir, { recursive: true })
  // Where the keeper of a state directory too deep for sun_path listens.
  const socket = join(dir, "short.sock")
  const killed = await listener(socket)
  killed.kill("SIGKILL")
  await once(killed, "exit")
  writeKeeperStatus(keeperDir, killed.pid!, socket)
  await core.sessions.forget("crashed")
  expect(existsSync(socket)).toBe(false)
  expect(existsSync(keeperDir)).toBe(false)
})

test("forget leaves alone a file that is not a socket, whatever a keeper status names", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = everyAgentCore(state, dir)
  await core.sessions.adopt({ id: "odd", agent: "claude", agentSessionId: "native-odd", cwd: dir })
  const keeperDir = join(state, "keepers", "odd")
  mkdirSync(keeperDir, { recursive: true })
  const notes = join(dir, "notes.txt")
  writeFileSync(notes, "keep me")
  writeKeeperStatus(keeperDir, 0, notes)
  await core.sessions.forget("odd")
  expect(readFileSync(notes, "utf8")).toBe("keep me")
  expect(existsSync(keeperDir)).toBe(false)
})
