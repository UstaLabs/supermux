import { test, expect, describe, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { createSupervisor } from "../src/core/session-manager/supervisor"
import { AgentKind } from "../src/shared/agents"

let tmpDir: string, db: ReturnType<typeof openDb>
beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "amux-sup-"))
  db = openDb(join(tmpDir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
})
afterEach(() => { try { db.close() } catch {}; rmSync(tmpDir, { recursive: true, force: true }) })

test("createSupervisor exposes ensurePersonalAssistants", () => {
  const registry = new Registry(db)
  const sup = createSupervisor({ registry, bindSocket: async () => {}, spawnTmux: async () => {} })
  expect(typeof sup.ensurePersonalAssistants).toBe("function")
})

test("ensurePersonalAssistants keeps a fresh install at zero PAs", async () => {
  const registry = new Registry(db)
  const spawns: any[] = []
  const sup = createSupervisor({
    registry,
    bindSocket: async () => {},
    spawnTmux: async (o) => { spawns.push(o) },
    paWorkdir: "/tmp/amux-test-pa",
  })
  await sup.ensurePersonalAssistants()
  expect(spawns.length).toBe(0)
  expect(registry.listPAs().length).toBe(0)
})

test("bootstrapPA supports codex agent and stores it in registry", async () => {
  const registry = new Registry(db)
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
    spawnTmux: async () => ({ windowId: "w1" }),
    codexResolveAuth: async () => ({ mode: "oauth_copy" as const, env: { OPENAI_API_KEY: "test" } }),
    codexSpawnAppServer: () => ({
      pid: 123,
      client: { request: async () => ({}) } as any,
      child: null as any,
      kill: () => {},
      onExit: () => {},
    }),
    codexAdapterFactory: () => ({
      start: async () => {},
    } as any),
    registerAdapter: () => {},
  })

  await supervisor.bootstrapPA("coder", { agent: AgentKind.Codex })

  const pa = registry.resolveName("coder")
  expect(pa?.agent).toBe("codex")
  expect(pa?.role).toBe("personal_assistant")
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
    spawnTmux: async () => ({ windowId: "w1" }),
    codexResolveAuth: async () => ({ mode: "oauth_copy" as const, env: { OPENAI_API_KEY: "test" } }),
    codexSpawnAppServer: () => ({
      pid: 123,
      client: { request: async () => ({}) } as any,
      child: null as any,
      kill: () => {},
      onExit: () => {},
    }),
    codexAdapterFactory: () => ({
      start: async () => {},
    } as any),
    registerAdapter: () => {},
  })
  await expect(supervisor.ensurePersonalAssistants()).resolves.toBeUndefined()
  const pa = registry.get(paId)
  expect(pa?.status).toBe("active")
  expect(pa?.pid).toBe(123)
})

test("bootstrapPA forwards model and reasoningLevel to registry", async () => {
  const registry = new Registry(db)
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
    spawnTmux: async () => ({ windowId: "w1" }),
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

test("reconcile invokes the internal-worker reaper each tick", async () => {
  let reapCalls = 0
  const registry = new Registry(db)
  const sup = createSupervisor({
    registry,
    bindSocket: async () => {},
    spawnTmux: async () => {},
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
    spawnTmux: async () => {},
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
