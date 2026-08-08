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

// Codex PA spawns go through the real spawnPA path; its collaborators are
// swapped via bun module mocks (there are no injection seams). mock.module is
// process-global: capture the real modules and restore them in afterAll.
const realCodexAuth = { ...(await import("../src/core/agents/codex/auth")) }
const realCodexSpawn = { ...(await import("../src/core/agents/codex/spawn")) }
const realCodexAdapter = { ...(await import("../src/core/agents/codex/adapter")) }

mock.module("../src/core/agents/codex/auth", () => ({
  ...realCodexAuth,
  resolveCodexAuth: async () => ({ mode: "oauth_copy" as const, env: { OPENAI_API_KEY: "test" } }),
}))
mock.module("../src/core/agents/codex/spawn", () => ({
  ...realCodexSpawn,
  spawnCodexAppServer: () => ({
    pid: 123,
    client: { request: async () => ({}) } as any,
    child: null as any,
    kill: () => {},
    onExit: () => {},
  }),
}))
mock.module("../src/core/agents/codex/adapter", () => ({
  ...realCodexAdapter,
  CodexAdapter: class {
    constructor(_opts: any) {}
    async start() {}
  },
}))

afterAll(() => {
  mock.module("../src/core/agents/codex/auth", () => realCodexAuth)
  mock.module("../src/core/agents/codex/spawn", () => realCodexSpawn)
  mock.module("../src/core/agents/codex/adapter", () => realCodexAdapter)
})

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
  expect(pa?.pid).toBe(123)
})

test("bootstrapPA forwards model and reasoningLevel to registry", async () => {
  const registry = new Registry(db)
  setSessionBackendForTests({
    create: async (opts: Parameters<SessionBackend["create"]>[0]) => ({ id: "w1", name: opts.name, pid: 123, alive: true }),
    capture: async () => "Listening for channel messages",
  } as unknown as SessionBackend)
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
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

  expect(captured.model).toBe("claude-opus-4")
  expect(captured.reasoningLevel).toBe("high")
})

test("bootstrapPA creates Claude through the session backend", async () => {
  const registry = new Registry(db)
  let createOpts: Parameters<SessionBackend["create"]>[0] | undefined
  setSessionBackendForTests({
    create: async (opts: Parameters<SessionBackend["create"]>[0]) => {
      createOpts = opts
      return { id: "opaque-target", name: opts.name, pid: 31337, alive: true }
    },
    capture: async () => "Listening for channel messages",
  } as unknown as SessionBackend)
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
  })

  await supervisor.bootstrapPA("native-pa", { agent: AgentKind.Claude })
  supervisor.stop()

  expect(createOpts?.argv[0]).toBe("claude")
  expect(createOpts?.env.MUX_DISPLAY_NAME).toBe("native-pa")
  expect(registry.resolveName("native-pa")?.tmux_window_id).toBe("opaque-target")
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
