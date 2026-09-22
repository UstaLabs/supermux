import { test, expect, afterAll, beforeEach, afterEach, mock } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { spawnPA } from "../src/core/session-manager/spawn-helper"
import { setSessionBackendForTests } from "../src/core/runtime"
import type { SessionBackend } from "../src/core/runtime/session-backend"
import { fakeCodexHost } from "./helpers/fake-codex-host"
import { fakeOpenCodeHost } from "./helpers/fake-opencode-host"
import { fakeCursorHost } from "./helpers/fake-cursor-host"

// Non-claude collaborators are swapped via bun module mocks (spawnPA has no
// injection seams). mock.module is process-global: capture the real modules
// first, restore them in afterAll so later test files see the real thing.
const realCodexCoreHost = { ...(await import("../src/core/agents/codex/core-host-provider")) }
const realCursorHost = { ...(await import("../src/core/agents/cursor/core-host-provider")) }
const realOpenCodeHost = { ...(await import("../src/core/agents/opencode/core-host-provider")) }

let fake = fakeCodexHost()
let fakeOc = fakeOpenCodeHost()
let fakeCur = fakeCursorHost("cursor-session-id")

mock.module("../src/core/agents/codex/core-host-provider", () => ({
  ...realCodexCoreHost,
  getCodexCoreHost: () => fake.host,
}))
mock.module("../src/core/agents/cursor/core-host-provider", () => ({
  ...realCursorHost,
  getCursorCoreHost: () => fakeCur.host,
}))
mock.module("../src/core/agents/opencode/core-host-provider", () => ({
  ...realOpenCodeHost,
  getOpenCodeCoreHost: () => fakeOc.host,
}))

afterAll(() => {
  mock.module("../src/core/agents/codex/core-host-provider", () => realCodexCoreHost)
  mock.module("../src/core/agents/cursor/core-host-provider", () => realCursorHost)
  mock.module("../src/core/agents/opencode/core-host-provider", () => realOpenCodeHost)
})

let tmpDir: string

function makeRegistry(): Registry {
  const db = openDb(join(tmpDir, `test-${Math.random()}.sqlite3`))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  return new Registry(db)
}

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "spawn-pa-"))
  fake = fakeCodexHost()
  fakeOc = fakeOpenCodeHost()
  fakeCur = fakeCursorHost("cursor-session-id")
  process.env.CURSOR_API_KEY = process.env.CURSOR_API_KEY ?? "test-key"
})
afterEach(async () => {
  await fake.close()
  await fakeOc.close()
  await fakeCur.close()
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
