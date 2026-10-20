// The keeper under Node, against the build: what Bun forgives (a socket path longer than sun_path) fails here.
import { test } from "node:test"
import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { once } from "node:events"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { fileURLToPath } from "node:url"
import { createCore, detachedKeeperHooks, supermux } from "@supermux/core"

// Unix sockets and signals.
const skip = process.platform === "win32" && "the keeper listens on a unix socket"
const fixture = fileURLToPath(new URL("../tests/fixtures/claude-agent.mjs", import.meta.url))
const text = value => [{ type: "text", text: value }]
const alive = pid => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

async function dead(pid) {
  const deadline = Date.now() + 5_000
  while (alive(pid) && Date.now() < deadline) await sleep(20)
  return !alive(pid)
}

test("a state directory too deep for a unix socket path: a session opens, detaches and re-attaches", { skip, timeout: 60_000 }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  // The agents work beside the state directory: the core refuses a cwd that holds it.
  const work = join(dir, "work")
  mkdirSync(work)
  // Like the kanban example's state inside a repository nested deep in a home directory.
  const state = join(dir, "a-repository-nested-deep-in-a-home-directory".repeat(2), ".supermux-kanban")
  const open = () => supermux({ stateDirectory: state, agents: { claude: { command: process.execPath, args: [fixture], env: { EXPECT_DEFAULT_DENY: "0" } } } })
  const status = () => JSON.parse(readFileSync(join(state, "keepers", "deep", "status.json"), "utf8"))
  let core = open()
  let started
  try {
    const session = await core.sessions.create({ id: "deep", agent: "claude", cwd: work })
    assert.equal((await (await session.send({ content: text("hello"), whenBusy: "queue" })).completed).status, "completed")
    started = status()
    await core.close({ agents: "detach" })
    assert.ok(alive(started.keeperPid), "the keeper ended with the core it was detached from")

    core = open()
    const resumed = await core.sessions.resume("deep")
    assert.equal(status().keeperPid, started.keeperPid, "the session got a new keeper instead of re-attaching")
    assert.equal((await (await resumed.send({ content: text("again"), whenBusy: "queue" })).completed).status, "completed")
    await core.close({ agents: "shutdown" })
    assert.ok(await dead(started.keeperPid), "the keeper outlived the shutdown")
    assert.ok(await dead(started.agentPid), "the agent outlived the shutdown")
  } finally {
    await core.close({ agents: "shutdown" }).catch(() => {})
    for (const pid of [started?.keeperPid, started?.agentPid]) if (pid && alive(pid)) process.kill(pid, "SIGKILL")
    rmSync(dir, { recursive: true, force: true })
  }
})

test("a detached session closed with shutdown by a new core: its keeper and agent stop, nothing is relaunched", { skip, timeout: 60_000 }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  // The agents work beside the state directory: the core refuses a cwd that holds it.
  const work = join(dir, "work")
  mkdirSync(work)
  const state = join(dir, "a-repository-nested-deep-in-a-home-directory".repeat(2), ".supermux-kanban")
  const trace = join(dir, "trace")
  const open = () => supermux({ stateDirectory: state, agents: { claude: { command: process.execPath, args: [fixture], env: { EXPECT_DEFAULT_DENY: "0", TRACE: trace } } } })
  const launches = () => readFileSync(trace, "utf8").split("\n").filter(line => line.startsWith('{"argv"')).length
  let core = open()
  let started
  try {
    await core.sessions.create({ id: "deep", agent: "claude", cwd: work })
    started = JSON.parse(readFileSync(join(state, "keepers", "deep", "status.json"), "utf8"))
    await core.close({ agents: "detach" })

    core = open()
    await core.sessions.close("deep", { mode: "shutdown" })
    assert.ok(!alive(started.keeperPid), "the keeper still runs")
    assert.ok(await dead(started.agentPid), "the agent still runs")
    assert.equal(launches(), 1)
    assert.equal((await core.sessions.get("deep"))?.id, "deep")
  } finally {
    await core.close({ agents: "shutdown" }).catch(() => {})
    for (const pid of [started?.keeperPid, started?.agentPid]) if (pid && alive(pid)) process.kill(pid, "SIGKILL")
    rmSync(dir, { recursive: true, force: true })
  }
})

test("forget is session_busy while a detached agent runs, and deletes the keeper's files once it is shut down", { skip, timeout: 60_000 }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  // The agents work beside the state directory: the core refuses a cwd that holds it.
  const work = join(dir, "work")
  mkdirSync(work)
  const state = join(dir, "a-repository-nested-deep-in-a-home-directory".repeat(2), ".supermux-kanban")
  const open = () => supermux({ stateDirectory: state, agents: { claude: { command: process.execPath, args: [fixture], env: { EXPECT_DEFAULT_DENY: "0" } } } })
  const keeper = join(state, "keepers", "deep")
  let core = open()
  let started
  try {
    await core.sessions.create({ id: "deep", agent: "claude", cwd: work })
    started = JSON.parse(readFileSync(join(keeper, "status.json"), "utf8"))
    await core.close({ agents: "detach" })

    core = open()
    await assert.rejects(core.sessions.forget("deep"), { code: "session_busy" })
    assert.ok(alive(started.keeperPid), "forget stopped the keeper")
    assert.ok(existsSync(join(keeper, "status.json")), "forget deleted the files of a running keeper")
    await core.sessions.close("deep", { mode: "shutdown" })
    await core.sessions.forget("deep")
    assert.ok(!existsSync(keeper), "the keeper directory is still there")
    assert.ok(!existsSync(started.socket), "the keeper socket is still there")
    assert.equal(await core.sessions.get("deep"), undefined)
  } finally {
    await core.close({ agents: "shutdown" }).catch(() => {})
    for (const pid of [started?.keeperPid, started?.agentPid]) if (pid && alive(pid)) process.kill(pid, "SIGKILL")
    rmSync(dir, { recursive: true, force: true })
  }
})

test("a keeper whose socket does not answer is never signalled: close(id, shutdown) fails and forget is session_busy", { skip, timeout: 60_000 }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  // The agents work beside the state directory: the core refuses a cwd that holds it.
  const work = join(dir, "work")
  mkdirSync(work)
  const state = join(dir, "state")
  const core = supermux({ stateDirectory: state, agents: { claude: {} } })
  let standIn
  try {
    await core.sessions.adopt({ id: "mute", agent: "claude", agentSessionId: "native-mute", cwd: work })
    const keeperDir = join(state, "keepers", "mute")
    mkdirSync(keeperDir, { recursive: true })
    const socket = join(keeperDir, "keeper.sock")
    // Stands in for the keeper; its socket then refuses this user (a connect fails with EACCES: neither refused nor missing).
    standIn = spawn(process.execPath, ["-e", `require("node:net").createServer().listen(${JSON.stringify(socket)}, () => console.log("up"))`], { stdio: ["ignore", "pipe", "inherit"] })
    await once(standIn.stdout, "data")
    chmodSync(socket, 0o000)
    writeFileSync(join(keeperDir, "status.json"), JSON.stringify({
      keeperPid: standIn.pid, agentPid: 0, startedAt: 1, lastSeq: 0, firstSeq: 1, ackedSeq: 0, updatedAt: 1, meta: {}, socket,
    }))
    await assert.rejects(core.sessions.close("mute", { mode: "shutdown" }), /does not answer/)
    assert.ok(alive(standIn.pid), "the unverified pid was signalled")
    await assert.rejects(core.sessions.forget("mute"), { code: "session_busy" })
    assert.ok(existsSync(join(keeperDir, "status.json")))
  } finally {
    standIn?.kill("SIGKILL")
    await core.close({ agents: "shutdown" }).catch(() => {})
    rmSync(dir, { recursive: true, force: true })
  }
})

test("a close that stopped the agent, then finds its keeper not answering, fails saying the agent was stopped", { skip, timeout: 60_000 }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  const work = join(dir, "work")
  mkdirSync(work)
  const state = join(dir, "state")
  let closes = 0
  // A driver whose agents run behind keepers in `state` (the built-in drivers' hooks); its agent stops on close.
  const driver = {
    id: "test",
    ...detachedKeeperHooks(state, { shutdownTimeoutMs: 1_000, connectTimeoutMs: 1_000 }),
    async open() {
      return {
        agentSessionId: "native-stopped",
        capabilities: { resume: true, steer: false, fork: false, detach: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => { closes += 1 },
      }
    },
  }
  const core = createCore({ stateDirectory: state, agents: [driver], limits: { interruptTimeoutMs: 1_000, maxPending: 8, outstandingActivity: 8 } })
  let standIn
  try {
    await core.sessions.create({ id: "stopped", agent: "test", cwd: work })
    const keeperDir = join(state, "keepers", "stopped")
    mkdirSync(keeperDir, { recursive: true })
    const socket = join(keeperDir, "keeper.sock")
    // A keeper the status names that does not answer (its socket refuses this user with EACCES: neither refused nor missing).
    standIn = spawn(process.execPath, ["-e", `require("node:net").createServer().listen(${JSON.stringify(socket)}, () => console.log("up"))`], { stdio: ["ignore", "pipe", "inherit"] })
    await once(standIn.stdout, "data")
    chmodSync(socket, 0o000)
    writeFileSync(join(keeperDir, "status.json"), JSON.stringify({
      keeperPid: standIn.pid, agentPid: 0, startedAt: 1, lastSeq: 0, firstSeq: 1, ackedSeq: 0, updatedAt: 1, meta: {}, socket,
    }))
    await assert.rejects(core.sessions.close("stopped", { mode: "shutdown" }),
      new RegExp(`Session stopped's agent was stopped, but its keeper could not be confirmed gone: process ${standIn.pid} does not answer on .*keeper\\.sock`))
    assert.equal(closes, 1)
    assert.ok(alive(standIn.pid), "the unverified pid was signalled")
    // Nothing is open any more: a retry is the keeper's own check, and says nothing was stopped.
    await assert.rejects(core.sessions.close("stopped", { mode: "shutdown" }), /does not answer on .*: it was not stopped/)
  } finally {
    standIn?.kill("SIGKILL")
    await core.close({ agents: "shutdown" }).catch(() => {})
    rmSync(dir, { recursive: true, force: true })
  }
})
