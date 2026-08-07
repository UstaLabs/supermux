import { test, expect, afterAll, beforeEach, afterEach, mock } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { spawnPA } from "../src/core/session-manager/spawn-helper"
import { setSessionBackendForTests } from "../src/core/runtime"
import type { SessionBackend } from "../src/core/runtime/session-backend"

// Non-claude collaborators are swapped via bun module mocks (spawnPA has no
// injection seams). mock.module is process-global: capture the real modules
// first, restore them in afterAll so later test files see the real thing.
const realCodexAuth = { ...(await import("../src/core/agents/codex/auth")) }
const realCodexSpawn = { ...(await import("../src/core/agents/codex/spawn")) }
const realCodexAdapter = { ...(await import("../src/core/agents/codex/adapter")) }
const realCursorAuth = { ...(await import("../src/core/agents/cursor/auth")) }
const realCursorSmoke = { ...(await import("../src/core/agents/cursor/smoke")) }
const realCursorRunner = { ...(await import("../src/core/agents/cursor/runner")) }
const realCursorAdapter = { ...(await import("../src/core/agents/cursor/adapter")) }

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
    constructor(private opts: any) {}
    async start() { await this.opts.persistThreadId("codex-thread-id") }
  },
}))
mock.module("../src/core/agents/cursor/auth", () => ({
  ...realCursorAuth,
  resolveCursorAuth: async () => ({ mode: "api_key", env: { CURSOR_API_KEY: "test" } }),
}))
mock.module("../src/core/agents/cursor/smoke", () => ({
  ...realCursorSmoke,
  smokeCursorAgent: async () => {},
}))
mock.module("../src/core/agents/cursor/runner", () => ({
  ...realCursorRunner,
  makeRealCursorRunner: () => async () => {},
}))
mock.module("../src/core/agents/cursor/adapter", () => ({
  ...realCursorAdapter,
  CursorAdapter: class {
    constructor(private opts: any) {}
    async start() { await this.opts.persistSessionId("cursor-session-id") }
  },
}))

afterAll(() => {
  mock.module("../src/core/agents/codex/auth", () => realCodexAuth)
  mock.module("../src/core/agents/codex/spawn", () => realCodexSpawn)
  mock.module("../src/core/agents/codex/adapter", () => realCodexAdapter)
  mock.module("../src/core/agents/cursor/auth", () => realCursorAuth)
  mock.module("../src/core/agents/cursor/smoke", () => realCursorSmoke)
  mock.module("../src/core/agents/cursor/runner", () => realCursorRunner)
  mock.module("../src/core/agents/cursor/adapter", () => realCursorAdapter)
})

let tmpDir: string

function makeRegistry(): Registry {
  const db = openDb(join(tmpDir, `test-${Math.random()}.sqlite3`))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  return new Registry(db)
}

beforeEach(() => { tmpDir = mkdtempSync(join(tmpdir(), "spawn-pa-")) })
afterEach(() => {
  setSessionBackendForTests()
  rmSync(tmpDir, { recursive: true, force: true })
})

function claudeBackend(id: string): SessionBackend {
  return {
    create: async (opts: Parameters<SessionBackend["create"]>[0]) => ({ id, name: opts.name, pid: 123, alive: true }),
    capture: async () => "Listening for channel messages",
  } as unknown as SessionBackend
}

test("spawns a Claude PA and registers it as personal_assistant", async () => {
  const registry = makeRegistry()
  setSessionBackendForTests(claudeBackend("w1"))
  const result = await spawnPA({
    registry,
    name: "assistant",
    agent: "claude" as const,
    workdir: join(tmpDir, "pa-workdir"),
    bind: async () => {},
    tmuxSession: "mux",
  })

  expect(result.name).toBe("assistant")
  expect(result.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)

  const pa = registry.resolveName("assistant")
  expect(pa?.role).toBe("personal_assistant")
  expect(pa?.is_default).toBe(true)
  expect(pa?.agent).toBe("claude")
})

test("second PA gets is_default false", async () => {
  const registry = makeRegistry()

  setSessionBackendForTests(claudeBackend("w1"))
  await spawnPA({
    registry,
    name: "assistant",
    agent: "claude" as const,
    workdir: join(tmpDir, "pa-1"),
    bind: async () => {},
    tmuxSession: "mux",
  })

  setSessionBackendForTests(claudeBackend("w2"))
  const result = await spawnPA({
    registry,
    name: "helper",
    agent: "claude" as const,
    workdir: join(tmpDir, "pa-2"),
    bind: async () => {},
    tmuxSession: "mux",
  })

  expect(result.name).toBe("helper")

  const pa = registry.resolveName("helper")
  expect(pa?.role).toBe("personal_assistant")
  expect(pa?.is_default).toBe(false)
})

test("spawns a Codex PA and registers it as personal_assistant", async () => {
  const registry = makeRegistry()
  let codexSessionIdCalled = false
  let receivedBrokerId = ""
  let receivedSessionId = ""
  const result = await spawnPA({
    registry,
    name: "coder",
    agent: "codex" as const,
    workdir: join(tmpDir, "codex-pa"),
    bind: async () => {},
    tmuxSession: "mux",
    registerAdapter: () => {},
    onCodexSessionId: (brokerSessionId, sessionId) => {
      receivedBrokerId = brokerSessionId
      receivedSessionId = sessionId
      codexSessionIdCalled = true
    },
  })

  expect(result.name).toBe("coder")
  expect(result.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)

  const pa = registry.resolveName("coder")
  expect(pa?.role).toBe("personal_assistant")
  expect(pa?.agent).toBe("codex")
  expect(pa?.agent_home).toBeTruthy()
  expect(codexSessionIdCalled).toBe(true)
  expect(receivedBrokerId).toBe(result.id)
  expect(receivedSessionId).toBe("codex-thread-id")
})

test("spawns a Cursor PA and registers it as personal_assistant", async () => {
  const registry = makeRegistry()
  let cursorSessionIdCalled = false
  const result = await spawnPA({
    registry,
    name: "cursor-pa",
    agent: "cursor" as const,
    workdir: join(tmpDir, "cursor-pa"),
    bind: async () => {},
    tmuxSession: "mux",
    registerAdapter: () => {},
    onCursorSessionId: (name, sessionId) => {
      expect(name).toBe("cursor-pa")
      expect(sessionId).toBe("cursor-session-id")
      cursorSessionIdCalled = true
    },
  })

  expect(result.name).toBe("cursor-pa")
  expect(result.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)

  const pa = registry.resolveName("cursor-pa")
  expect(pa?.role).toBe("personal_assistant")
  expect(pa?.agent).toBe("cursor")
  expect(pa?.agent_home).toBeTruthy()
  expect(cursorSessionIdCalled).toBe(true)
})

test("spawns an OpenCode PA and registers it as personal_assistant", async () => {
  const registry = makeRegistry()
  let opencodeSessionIdCalled = false
  const result = await spawnPA({
    registry,
    name: "opencode-pa",
    agent: "opencode" as const,
    workdir: join(tmpDir, "opencode-pa"),
    bind: async () => {},
    tmuxSession: "mux",
    opencodeSpawnServer: async () => ({
      pid: 123,
      baseUrl: "http://localhost:1234",
      client: {
        session: {
          create: async () => ({ data: { id: "opencode-sid" } }),
        },
        event: {
          subscribe: async () => ({ stream: [] as any }),
        },
        listCommands: async () => [],
      } as any,
      child: null as any,
      kill: () => {},
      onExit: () => {},
    }),
    opencodeAdapterFactory: (opts) => ({
      start: async () => {
        await opts.persistSessionId("opencode-sid")
      },
    } as any),
    registerAdapter: () => {},
    onOpenCodeSessionId: (name, sessionId) => {
      expect(name).toBe("opencode-pa")
      expect(sessionId).toBe("opencode-sid")
      opencodeSessionIdCalled = true
    },
  })

  expect(result.name).toBe("opencode-pa")
  expect(result.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)

  const pa = registry.resolveName("opencode-pa")
  expect(pa?.role).toBe("personal_assistant")
  expect(pa?.agent).toBe("opencode")
  expect(pa?.agent_home).toBeTruthy()
  expect(opencodeSessionIdCalled).toBe(true)
})
