import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { createSupervisor, reconcileOnStartup } from "../src/core/session-manager/supervisor"
import { AgentKind } from "../src/shared/agents"
import { setSessionBackendForTests } from "../src/core/runtime"

let tmpDir: string, db: ReturnType<typeof openDb>
beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "amux-sup-"))
  db = openDb(join(tmpDir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
})
afterEach(() => {
  setSessionBackendForTests()
  try { db.close() } catch {}
  rmSync(tmpDir, { recursive: true, force: true })
})

type FakeAdapter = { alive: boolean; resumed: number; isAlive(): boolean; resume(): Promise<void> }
const fakeAdapter = (alive: boolean): FakeAdapter => ({ alive, resumed: 0, isAlive() { return this.alive }, async resume() { this.resumed++; this.alive = true } })

function agentRow(registry: Registry, name: string, agent: AgentKind, is_default = false) {
  return registry.registerPA({ name, agent, workdir: `/tmp/${name}`, pid: 0, is_default, core: true })
}

test("a live Agent is left alone, whatever its provider (no double start for pid 0)", async () => {
  const registry = new Registry(db)
  const rows = [agentRow(registry, "c", AgentKind.Claude, true), agentRow(registry, "x", AgentKind.Codex), agentRow(registry, "g", AgentKind.Grok)]
  const adapters = new Map(rows.map(r => [r.id, fakeAdapter(true)]))
  const resumeSuspended: string[] = [], fresh: string[] = []
  const sup = createSupervisor({
    registry, bindSocket: async () => {},
    sessionManager: { registerSpawnedAdapter: () => {}, adapterFor: (id) => adapters.get(id), resumeSuspended: async (s) => { resumeSuspended.push(s.name); return true } },
    startAgentFresh: async (s) => { fresh.push(s.name) },
  })
  await sup.superviseAgents()
  sup.stop()
  expect([...adapters.values()].map(a => a.resumed)).toEqual([0, 0, 0])
  expect(resumeSuspended).toEqual([])
  expect(fresh).toEqual([])
})

test("a crashed Agent is revived through its own adapter, for every provider", async () => {
  const registry = new Registry(db)
  const rows = [agentRow(registry, "c", AgentKind.Claude, true), agentRow(registry, "o", AgentKind.OpenCode), agentRow(registry, "a", AgentKind.Antigravity)]
  const adapters = new Map(rows.map(r => [r.id, fakeAdapter(false)]))
  const sup = createSupervisor({ registry, bindSocket: async () => {}, sessionManager: { registerSpawnedAdapter: () => {}, adapterFor: (id) => adapters.get(id), resumeSuspended: async () => true } })
  await sup.reconcile() // the 30 s tick
  sup.stop()
  expect([...adapters.values()].map(a => a.resumed)).toEqual([1, 1, 1])
})

test("an Agent with no adapter is resumed through the normal resume path; one that cannot resume starts fresh with the same id", async () => {
  const registry = new Registry(db)
  const resumable = agentRow(registry, "r", AgentKind.Codex, true)
  const neverTalked = agentRow(registry, "n", AgentKind.Codex)
  const calls: string[] = []
  const sup = createSupervisor({
    registry, bindSocket: async () => {},
    sessionManager: { registerSpawnedAdapter: () => {}, adapterFor: () => undefined, resumeSuspended: async (s) => { calls.push(`resume:${s.name}`); return s.id === resumable.id } },
    startAgentFresh: async (s) => { calls.push(`fresh:${s.name}:${s.id === neverTalked.id}`) },
  })
  await sup.superviseAgents()
  sup.stop()
  expect(calls).toEqual(["resume:r", "resume:n", "fresh:n:true"])
})

test("zero Agents stay zero, and a missing default is repaired", async () => {
  const registry = new Registry(db)
  const sup = createSupervisor({ registry, bindSocket: async () => {}, sessionManager: { registerSpawnedAdapter: () => {}, adapterFor: () => undefined, resumeSuspended: async () => true } })
  await sup.superviseAgents()
  expect(registry.listPAs()).toEqual([])
  const a = agentRow(registry, "a", AgentKind.Claude, false)
  await sup.superviseAgents()
  sup.stop()
  expect(registry.get(a.id)?.is_default).toBe(true)
})

test("two overlapping passes revive a crashed Agent once", async () => {
  const registry = new Registry(db)
  const row = agentRow(registry, "c", AgentKind.Claude, true)
  let release!: () => void
  const gate = new Promise<void>((r) => { release = r })
  const adapter = { alive: false, resumed: 0, isAlive() { return this.alive }, async resume() { this.resumed++; await gate; this.alive = true } }
  const sup = createSupervisor({ registry, bindSocket: async () => {}, sessionManager: { registerSpawnedAdapter: () => {}, adapterFor: (id) => id === row.id ? adapter : undefined } })
  const first = sup.superviseAgents()
  const second = sup.reconcile()
  release()
  await Promise.all([first, second])
  sup.stop()
  expect(adapter.resumed).toBe(1)
})

test("with awaitBootPass, the tick leaves Agents to the boot pass until it has run", async () => {
  const registry = new Registry(db)
  agentRow(registry, "x", AgentKind.Codex, true)
  const calls: string[] = []
  const sup = createSupervisor({
    registry, bindSocket: async () => {}, awaitBootPass: true,
    sessionManager: { registerSpawnedAdapter: () => {}, adapterFor: () => undefined, resumeSuspended: async (s) => { calls.push(s.name); return true } },
  })
  await sup.reconcile() // a tick while resumeAtBoot may still be resuming this Agent
  expect(calls).toEqual([])
  await sup.superviseAgents() // main.ts, after resumeAtBoot
  await sup.reconcile()
  sup.stop()
  expect(calls).toEqual(["x", "x"])
})

test("reconcileOnStartup leaves Agents to resumeAtBoot (never restarts them itself)", async () => {
  const registry = new Registry(db)
  agentRow(registry, "c", AgentKind.Claude, true)
  let supervised = 0
  await reconcileOnStartup({ registry, bindSocket: async () => {}, supervisor: { superviseAgents: async () => { supervised++ }, reconcile: async () => {}, stop() {} }, isAlive: () => false, livePanePid: async () => null })
  expect(supervised).toBe(0)
  expect(registry.resolveName("c")?.status).toBe("active")
})

test("reconcile invokes the internal-worker reaper each tick", async () => {
  let reapCalls = 0
  const registry = new Registry(db)
  const sup = createSupervisor({
    registry,
    bindSocket: async () => {},
    reapInternalWorkers: async () => { reapCalls++ },
  })
  await sup.reconcile()
  expect(reapCalls).toBe(1)
})

test("reconcile never suspends a draft (pid 0 reads as dead but the guard skips it)", async () => {
  const registry = new Registry(db)
  // A draft: cached claude row with no process. pid 0 → isProcessAlive returns
  // false, so WITHOUT the isDraftSession guard the live reconcile loop (which
  // suspends dead claude sessions) would suspend it. This pins that guard.
  // Mirror main.ts createDraft: the draft row is written via the store's
  // register (registry.register drops user_status), so a draft is claude + pid 0.
  const draft = registry.sessions.register({
    name: "draft-1",
    agent: AgentKind.Claude,
    workdir: "/tmp",
    pid: 0,
    user_status: "draft",
  })
  const sup = createSupervisor({
    registry,
    bindSocket: async () => {},
  })
  try {
    await sup.reconcile()
  } finally {
    sup.stop()
  }
  const after = registry.get(draft.id)
  expect(after).toBeDefined()
  expect(after?.status).toBe("active")
  expect(after?.user_status).toBe("draft")
})

test("a failed resume of an Agent with a saved native conversation never starts it fresh (any provider)", async () => {
  const registry = new Registry(db)
  const claude = agentRow(registry, "c", AgentKind.Claude, true)
  const codexNoHome = agentRow(registry, "x", AgentKind.Codex) // a thread id without agent_home is still a saved thread
  registry.sessions.setAgentSessionId(claude.id, "claude-conv-1")
  registry.sessions.setAgentSessionId(codexNoHome.id, "thread-1")
  const calls: string[] = []
  const sup = createSupervisor({
    registry, bindSocket: async () => {},
    sessionManager: { registerSpawnedAdapter: () => {}, adapterFor: () => undefined, resumeSuspended: async (s) => { calls.push(`resume:${s.name}`); return false } },
    startAgentFresh: async (s) => { calls.push(`fresh:${s.name}`) },
  })
  await sup.superviseAgents()
  sup.stop()
  expect(calls).toEqual(["resume:c", "resume:x"])
  expect(registry.get(claude.id)?.agent_session_id).toBe("claude-conv-1")
  expect(registry.get(codexNoHome.id)?.agent_session_id).toBe("thread-1")
})
