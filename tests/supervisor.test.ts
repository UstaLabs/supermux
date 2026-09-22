import { test, expect, describe, afterAll, beforeEach, afterEach, mock } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { createSupervisor } from "../src/core/session-manager/supervisor"
import { AgentKind } from "../src/shared/agents"
import { setSessionBackendForTests } from "../src/core/runtime"
import type { SessionBackend } from "../src/core/runtime/session-backend"
import { fakeCodexHost } from "./helpers/fake-codex-host"

// Codex PA spawns go through the real spawnPA path; its collaborators are
// swapped via bun module mocks (there are no injection seams). mock.module is
// process-global: capture the real modules and restore them in afterAll.
const realCodexCoreHost = { ...(await import("../src/core/agents/codex/core-host-provider")) }

let fake = fakeCodexHost()

mock.module("../src/core/agents/codex/core-host-provider", () => ({
  ...realCodexCoreHost,
  getCodexCoreHost: () => fake.host,
}))

afterAll(() => {
  mock.module("../src/core/agents/codex/core-host-provider", () => realCodexCoreHost)
})

let tmpDir: string, db: ReturnType<typeof openDb>
beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "amux-sup-"))
  db = openDb(join(tmpDir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  fake = fakeCodexHost()
})
afterEach(async () => {
  await fake.close()
  setSessionBackendForTests()
  try { db.close() } catch {}
  rmSync(tmpDir, { recursive: true, force: true })
})

test("createSupervisor exposes ensurePersonalAssistants", () => {
  const registry = new Registry(db)
  const sup = createSupervisor({ registry, bindSocket: async () => {} })
  expect(typeof sup.ensurePersonalAssistants).toBe("function")
})

test("ensurePersonalAssistants keeps a fresh install at zero PAs", async () => {
  const registry = new Registry(db)
  const sup = createSupervisor({
    registry,
    bindSocket: async () => {},
    paWorkdir: "/tmp/amux-test-pa",
  })
  await sup.ensurePersonalAssistants()
  expect(registry.listPAs().length).toBe(0)
})

test("bootstrapPA supports codex agent and stores it in registry", async () => {
  const registry = new Registry(db)
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
    sessionManager: { registerSpawnedAdapter: () => {} },
  })

  await supervisor.bootstrapPA("coder", { agent: AgentKind.Codex })

  const pa = registry.resolveName("coder")
  expect(pa?.agent).toBe("codex")
  expect(pa?.role).toBe("personal_assistant")
})

test("a sessionManager-equipped supervisor registers the adapter of a spawned non-Claude PA (half-filled-bag regression)", async () => {
  const registry = new Registry(db)
  const registered: Array<{ name: string }> = []
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
    // Adapter registration must DERIVE from sessionManager.
    sessionManager: {
      registerSpawnedAdapter: (name: string) => { registered.push({ name }) },
    },
  })

  await supervisor.bootstrapPA("coder-reg", { agent: AgentKind.Codex })
  supervisor.stop()

  expect(registered.length).toBe(1)
  expect(registered[0]?.name).toBe("coder-reg")
})

test("ensurePersonalAssistants respawns dead non-Claude PA", async () => {
  const registry = new Registry(db)
  const registered = registry.registerPA({
    name: "codex-pa",
    agent: AgentKind.Codex,
    workdir: "/tmp/codex-pa-test",
    pid: 999999, // dead PID
    is_default: true,
  })
  const paId = registered.id
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
    sessionManager: { registerSpawnedAdapter: () => {} },
  })
  await expect(supervisor.ensurePersonalAssistants()).resolves.toBeUndefined()
  const pa = registry.get(paId)
  expect(pa?.status).toBe("active")
  expect(pa?.pid).toBe(0)
})

test("bootstrapPA forwards model and reasoningLevel to registry", async () => {
  const { createClaudeCoreHost } = await import("../src/core/agents/claude/core-host")
  const dir = mkdtempSync(join(tmpdir(), "mux-claude-sup-"))
  const host = createClaudeCoreHost({
    stateDirectory: dir,
    driverFactory: () => ({
      id: "claude",
      async open(ctx) {
        return {
          agentSessionId: ctx.resumeId ?? "n1",
          capabilities: { resume: true, steer: false, fork: false, detach: true, configure: false, history: false },
          async prompt() { return { stopReason: "end_turn" } },
          async interrupt() {},
          async close() {},
        }
      },
    }),
  })
  const registry = new Registry(db)
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
    sessionManager: { registerSpawnedAdapter: () => {} },
    claudeHost: host,
  })

  let captured: any
  const originalRegisterPA = registry.registerPA.bind(registry)
  registry.registerPA = (input: any) => {
    captured = input
    return originalRegisterPA(input)
  }

  await supervisor.bootstrapPA("test-pa", {
    agent: AgentKind.Claude,
    model: "claude-opus-4",
    reasoningLevel: "high",
  })
  supervisor.stop()
  await host.close({ agents: "shutdown" }).catch(() => {})
  rmSync(dir, { recursive: true, force: true })

  expect(captured.model).toBe("claude-opus-4")
  expect(captured.reasoningLevel).toBe("high")
  expect(captured.core).toBe(true)
  expect(captured.pid).toBe(0)
})

test("bootstrapPA creates Claude as a Core session", async () => {
  const { createClaudeCoreHost } = await import("../src/core/agents/claude/core-host")
  const dir = mkdtempSync(join(tmpdir(), "mux-claude-sup2-"))
  const host = createClaudeCoreHost({
    stateDirectory: dir,
    driverFactory: () => ({
      id: "claude",
      async open(ctx) {
        return {
          agentSessionId: ctx.resumeId ?? "n1",
          capabilities: { resume: true, steer: false, fork: false, detach: true, configure: false, history: false },
          async prompt() { return { stopReason: "end_turn" } },
          async interrupt() {},
          async close() {},
        }
      },
    }),
  })
  const registry = new Registry(db)
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
    sessionManager: { registerSpawnedAdapter: () => {} },
    claudeHost: host,
  })

  await supervisor.bootstrapPA("native-pa", { agent: AgentKind.Claude })
  supervisor.stop()
  await host.close({ agents: "shutdown" }).catch(() => {})
  rmSync(dir, { recursive: true, force: true })

  const pa = registry.resolveName("native-pa")
  expect(pa?.core).toBe(true)
  expect(pa?.pid).toBe(0)
  expect(pa?.tmux_window_id).toBeUndefined()
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
