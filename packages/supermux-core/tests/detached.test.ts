import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { spawn, type ChildProcess } from "node:child_process"
import { once } from "node:events"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { createServer } from "node:net"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { CoreError, createCore, createHost, supermux, type AgentDriver, type Core, type Host } from "../src/index.js"
import * as root from "../src/index.js"
import { KeeperUnconfirmed } from "../src/keeper/client.js"
import { TEST_LIMITS, WORKDIR } from "./helpers.js"

setDefaultTimeout(30_000)

// Sessions left running behind their keepers with no Session open on them: what a core that
// detached (or a host that restarted without resuming them) leaves behind.

const fixture = fileURLToPath(new URL("./fixtures/claude-agent.mjs", import.meta.url))
const AGENTS = ["claude", "codex", "grok", "opencode", "cursor", "antigravity"] as const
const dirs: string[] = []
const pids: number[] = []
const cores: Core[] = []
const hosts: Host[] = []

function alive(pid: number) {
  if (!Number.isInteger(pid) || pid < 1) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(hosts.splice(0).map(host => host.close({ agents: "shutdown" }).catch(() => {})))
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
  await first.sessions.create({ id, agent: "claude", cwd: WORKDIR })
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
    await core.sessions.adopt({ id: agent, agent, agentSessionId: `native-${agent}`, cwd: WORKDIR })
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
  await core.sessions.adopt({ id: "stale", agent: "claude", agentSessionId: "native-stale", cwd: WORKDIR })
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
  await core.sessions.create({ id: "f1", agent: "claude", cwd: WORKDIR })
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
    await core.sessions.adopt({ id: agent, agent, agentSessionId: `native-${agent}`, cwd: WORKDIR })
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
  await core.sessions.adopt({ id: "crashed", agent: "claude", agentSessionId: "native-crashed", cwd: WORKDIR })
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
  await core.sessions.adopt({ id: "odd", agent: "claude", agentSessionId: "native-odd", cwd: WORKDIR })
  const keeperDir = join(state, "keepers", "odd")
  mkdirSync(keeperDir, { recursive: true })
  const notes = join(dir, "notes.txt")
  writeFileSync(notes, "keep me")
  writeKeeperStatus(keeperDir, 0, notes)
  await core.sessions.forget("odd")
  expect(readFileSync(notes, "utf8")).toBe("keep me")
  expect(existsSync(keeperDir)).toBe(false)
})

async function until(check: () => boolean, what: string, ms = 5000) {
  const start = Date.now()
  while (!check()) {
    if (Date.now() - start > ms) throw new Error(`timed out waiting for ${what}`)
    await new Promise(resolve => setTimeout(resolve, 20))
  }
}

/** A sacrificial process: what a pid recorded by a keeper that died may belong to now. */
function otherProcess() {
  const child = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], { stdio: "ignore" })
  pids.push(child.pid!)
  return child.pid!
}

test("a detached agent in the middle of a turn: sessions.close(id, shutdown) stops it, relaunching nothing", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const trace = join(dir, "trace")
  const first = claudeCore(state, trace)
  const session = await first.sessions.create({ id: "busy", agent: "claude", cwd: WORKDIR })
  const turn = await session.send({ content: [{ type: "text", text: "hang" }], whenBusy: "queue" })
  void turn.completed.catch(() => {})
  // The fixture never ends a "hang" turn: once it has the message, the turn is running.
  await until(() => readFileSync(trace, "utf8").includes('"hang"'), "the agent to get the turn")
  const status = keeperStatus(state, "busy")
  pids.push(status.keeperPid, status.agentPid)
  await first.close({ agents: "detach" })
  expect(alive(status.agentPid)).toBe(true)

  const core = claudeCore(state, trace)
  await core.sessions.close("busy", { mode: "shutdown" })
  expect(alive(status.keeperPid)).toBe(false)
  expect(alive(status.agentPid)).toBe(false)
  expect(launches(trace)).toBe(1)
})

test("a recorded keeper socket outside a private directory of this user is neither connected to nor its pid signalled", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = everyAgentCore(state, dir)
  await core.sessions.adopt({ id: "foreign", agent: "claude", agentSessionId: "native-foreign", cwd: WORKDIR })
  const keeperDir = join(state, "keepers", "foreign")
  mkdirSync(keeperDir, { recursive: true })
  // A shared directory: whoever made it may be listening there.
  const shared = join(dir, "shared")
  mkdirSync(shared, { mode: 0o755 })
  chmodSync(shared, 0o755)
  const socket = join(shared, "k.sock")
  let connections = 0
  const fake = createServer(sock => { connections += 1; sock.destroy() })
  await new Promise<void>(resolve => fake.listen(socket, resolve))
  const other = otherProcess()
  writeKeeperStatus(keeperDir, other, socket)
  try {
    await core.sessions.close("foreign", { mode: "shutdown" })
    expect(alive(other)).toBe(true)
    expect(connections).toBe(0)
    // No keeper of this user's runs there: forget goes ahead.
    await core.sessions.forget("foreign")
    expect(existsSync(keeperDir)).toBe(false)
    expect(connections).toBe(0)
    expect(alive(other)).toBe(true)
  } finally {
    await new Promise<void>(resolve => fake.close(() => resolve()))
  }
})

test("forget deletes Antigravity's own files of the session too", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = everyAgentCore(state, dir)
  await core.sessions.adopt({ id: "agy", agent: "antigravity", agentSessionId: "native-agy", cwd: WORKDIR })
  const own = join(state, "antigravity")
  mkdirSync(join(own, "agy", "attachments"), { recursive: true })
  writeFileSync(join(own, "agy.json"), "{}")
  writeFileSync(join(own, "agy", "attachments", "image.png"), "png")
  writeFileSync(join(own, "other.json"), "{}")
  await core.sessions.forget("agy")
  expect(existsSync(join(own, "agy.json"))).toBe(false)
  expect(existsSync(join(own, "agy"))).toBe(false)
  expect(existsSync(join(own, "other.json"))).toBe(true)
})

test("a saved session that cannot be read: close still stops its keeper, and forget keeps it while that runs", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const core = everyAgentCore(state, dir)
  expect(await core.sessions.list()).toEqual([])
  writeFileSync(join(state, "sessions", "broken.json"), "{")
  const keeper = await standInKeeper(state, "broken")
  await expect(core.sessions.forget("broken")).rejects.toMatchObject({ code: "session_busy" })
  expect(existsSync(join(state, "sessions", "broken.json"))).toBe(true)
  const exited = once(keeper, "exit")
  await core.sessions.close("broken", { mode: "shutdown" })
  await exited
  await core.sessions.forget("broken")
  expect(existsSync(join(state, "keepers", "broken"))).toBe(false)
  expect(existsSync(join(state, "sessions", "broken.json"))).toBe(false)
})

test("a saved session of an agent this core does not register: close and forget still reach its keeper", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const first = everyAgentCore(state, dir)
  await first.sessions.adopt({ id: "gone", agent: "codex", agentSessionId: "native-gone", cwd: WORKDIR })
  await first.close({ agents: "shutdown" })
  const core = supermux({ stateDirectory: state, agents: { claude: {} } })
  cores.push(core)
  const keeper = await standInKeeper(state, "gone")
  await expect(core.sessions.forget("gone")).rejects.toMatchObject({ code: "session_busy" })
  const exited = once(keeper, "exit")
  await core.sessions.close("gone", { mode: "shutdown" })
  await exited
  await core.sessions.forget("gone")
  expect(existsSync(join(state, "keepers", "gone"))).toBe(false)
  expect(await core.sessions.get("gone")).toBeUndefined()
})

test("a core whose state directory another core holds: sessions.close(id, shutdown) resolves as before and stops nothing", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const owner = everyAgentCore(state, dir)
  await owner.sessions.adopt({ id: "held", agent: "claude", agentSessionId: "native-held", cwd: WORKDIR })
  const keeper = await standInKeeper(state, "held")
  const other = everyAgentCore(state, dir)
  await other.sessions.close("unknown", { mode: "shutdown" })
  await other.sessions.close("held", { mode: "shutdown" })
  expect(alive(keeper.pid!)).toBe(true)
  await expect(other.sessions.list()).rejects.toMatchObject({ code: "state_locked" })
})

test("createHost forwards the keeper hooks: close(id, shutdown) stops a detached session's keeper, forget waits for it", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const host = createHost({ stateDirectory: state, agent: "claude", limits: TEST_LIMITS, driver: () => { throw new Error("nothing opens in this test") } })
  hosts.push(host)
  await host.core.sessions.adopt({ id: "h1", agent: "claude", agentSessionId: "native-h1", cwd: WORKDIR })
  const keeper = await standInKeeper(state, "h1")
  await expect(host.core.sessions.forget("h1")).rejects.toMatchObject({ code: "session_busy" })
  const exited = once(keeper, "exit")
  await host.core.sessions.close("h1", { mode: "shutdown" })
  await exited
  await host.core.sessions.forget("h1")
  expect(existsSync(join(state, "keepers", "h1"))).toBe(false)
})

test("createHost takes detached hooks of its own (HostOptions.detached)", async () => {
  const dir = scratch()
  const calls: string[] = []
  const host = createHost({
    stateDirectory: join(dir, "state"), agent: "claude", limits: TEST_LIMITS, driver: () => { throw new Error("nothing opens in this test") },
    detached: {
      shutdownDetached: async id => { calls.push(`shutdown ${id}`) },
      forgetDetached: async id => { calls.push(`forget ${id}`); return true },
    },
  } as Parameters<typeof createHost>[0])
  hosts.push(host)
  await host.core.sessions.adopt({ id: "h2", agent: "claude", agentSessionId: "native-h2", cwd: WORKDIR })
  await host.core.sessions.close("h2", { mode: "shutdown" })
  await host.core.sessions.forget("h2")
  expect(calls).toEqual(["shutdown h2", "forget h2"])
})

test("the root exports detachedKeeperHooks: the shutdownDetached and forgetDetached of a keeper-backed driver", () => {
  const exported = root as Record<string, unknown>
  expect("detachedKeeper" in exported).toBe(false)
  expect(typeof exported.detachedKeeperHooks).toBe("function")
  const hooks = (exported.detachedKeeperHooks as typeof root.detachedKeeperHooks)(join(tmpdir(), "no-state"), { shutdownTimeoutMs: 1000, connectTimeoutMs: 1000 })
  expect(Object.keys(hooks).sort()).toEqual(["forgetDetached", "shutdownDetached"])
})

test("createHost's default forget also deletes Antigravity's own files of the session", async () => {
  const dir = scratch()
  const state = join(dir, "state")
  const host = createHost({ stateDirectory: state, agent: "antigravity", limits: TEST_LIMITS, driver: () => { throw new Error("nothing opens in this test") } })
  hosts.push(host)
  await host.core.sessions.adopt({ id: "agy", agent: "antigravity", agentSessionId: "native-agy", cwd: WORKDIR })
  const own = join(state, "antigravity")
  mkdirSync(join(own, "agy", "attachments"), { recursive: true })
  writeFileSync(join(own, "agy.json"), "{}")
  writeFileSync(join(own, "agy", "attachments", "image.png"), "png")
  writeFileSync(join(own, "other.json"), "{}")
  await host.core.sessions.forget("agy")
  expect(existsSync(join(own, "agy.json"))).toBe(false)
  expect(existsSync(join(own, "agy"))).toBe(false)
  expect(existsSync(join(own, "other.json"))).toBe(true)
})

test("a shutdown close that stopped the agent, whose keeper then cannot be confirmed gone, says so and what to do", async () => {
  const dir = scratch()
  let answers = false
  let closes = 0
  const driver: AgentDriver = {
    id: "test",
    async open() {
      return {
        agentSessionId: "native-unconfirmed",
        capabilities: { resume: true, steer: false, fork: false, detach: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => { closes += 1 },
      }
    },
    // What stopKeeper reports when the keeper a status names does not answer on its socket either way.
    async shutdownDetached(sessionId) { if (!answers) throw new KeeperUnconfirmed(sessionId, 4242, "/run/k.sock") },
  }
  const core = createCore({ stateDirectory: join(dir, "state"), agents: [driver], limits: TEST_LIMITS })
  cores.push(core)
  const session = await core.sessions.create({ id: "unconfirmed", agent: "test", cwd: WORKDIR })

  const error = await core.sessions.close("unconfirmed", { mode: "shutdown" }).then(() => undefined, (failure: Error) => failure)
  // Still a rejection (the keeper may run), but it does not read as if the agent were left running.
  expect(error?.message).toStartWith("Session unconfirmed's agent was stopped, but its keeper could not be confirmed gone: process 4242 does not answer on /run/k.sock")
  expect(error?.message).toContain('Retry sessions.close("unconfirmed", { mode: "shutdown" })')
  // A host matches it by its code, not by its text; the keeper's own refusal stays its cause.
  expect(error).toBeInstanceOf(CoreError)
  expect(error).toMatchObject({ code: "keeper_unconfirmed" })
  expect(error?.cause).toBeInstanceOf(KeeperUnconfirmed)
  expect(error?.cause).toMatchObject({ code: "keeper_unconfirmed", sessionId: "unconfirmed", keeperPid: 4242, socket: "/run/k.sock" })
  expect(closes).toBe(1)
  expect(session.snapshot().state).toBe("closed")

  // A close that stops nothing itself keeps the keeper's own message, with the same code.
  const retry = await core.sessions.close("unconfirmed", { mode: "shutdown" }).then(() => undefined, (failure: Error) => failure)
  expect(retry?.message).toBe("The keeper of session unconfirmed does not answer on /run/k.sock: it was not stopped")
  expect(retry).toBeInstanceOf(CoreError)
  expect(retry).toMatchObject({ code: "keeper_unconfirmed" })
  answers = true
  await core.sessions.close("unconfirmed", { mode: "shutdown" })
  expect(closes).toBe(1)
})

test("KeeperUnconfirmed is a CoreError named for what it is, with the code keeper_unconfirmed, and keeps the cause it is given", () => {
  const error = new KeeperUnconfirmed("s1", 4242, "/run/k.sock")
  expect(error).toBeInstanceOf(CoreError)
  expect(error).toBeInstanceOf(Error)
  expect(error.name).toBe("KeeperUnconfirmed")
  expect(error.code).toBe("keeper_unconfirmed")
  expect(error.message).toBe("The keeper of session s1 does not answer on /run/k.sock: it was not stopped")
  expect([error.sessionId, error.keeperPid, error.socket]).toEqual(["s1", 4242, "/run/k.sock"])
  const cause = new Error("underneath")
  const said = new KeeperUnconfirmed("s1", 4242, "/run/k.sock", "said another way", { cause })
  expect(said.message).toBe("said another way")
  expect(said.code).toBe("keeper_unconfirmed")
  expect(said.cause).toBe(cause)
})
