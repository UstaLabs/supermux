import { test, expect, describe, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { createSupervisor } from "../src/core/session-manager/supervisor"
import { AgentKind } from "../src/shared/agents"
import type { SessionBackend } from "../src/core/runtime/session-backend"

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
    sessionBackend: {
      create: async (opts: Parameters<SessionBackend["create"]>[0]) => ({ id: "w1", name: opts.name, pid: 123, alive: true }),
      capture: async () => "Listening for channel messages",
    } as unknown as SessionBackend,
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
  const sessionBackend = {
    create: async (opts: Parameters<SessionBackend["create"]>[0]) => {
      createOpts = opts
      return { id: "opaque-target", name: opts.name, pid: 31337, alive: true }
    },
    capture: async () => "Listening for channel messages",
  } as unknown as SessionBackend
  const supervisor = createSupervisor({
    registry,
    bindSocket: async () => {},
    spawnTmux: async () => { throw new Error("must not spawn via tmux") },
    sessionBackend,
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
    spawnTmux: async () => {},
    reapInternalWorkers: async () => { reapCalls++ },
  })
  await sup.reconcile()
  expect(reapCalls).toBe(1)
})
