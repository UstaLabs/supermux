import { describe, expect, test } from "bun:test"
import { join } from "path"
import { openDb, runMigrations, type Db } from "../storage/db"
import { Registry } from "./registry"
import { SessionManager, type SessionManagerPorts } from "./manager"
import type { CodexAdapter } from "../agents/codex/adapter"
import type { CodexSpawnHandle } from "../agents/codex/spawn"
import type { ClaudeCodeAdapter } from "../agents/claude"
import type { FileStore } from "../files/store"

/** Minimal inert ports: the runtime-store tests never cross into a port. */
function fakePorts(db: Db): SessionManagerPorts {
  return {
    getWebChannel: () => undefined,
    getAgentRpc: () => ({ settle: () => {}, fail: () => {} }),
    socket: { sendInbound: async () => {} },
    backend: { runtimeTargetIdOf: async () => null, kill: async () => {} },
    cleanup: {
      terminals: { killAllForSession: async () => {} },
      fsWatcher: { killSession: () => {} },
      stopClaudeTailer: () => {},
      releaseDraftAttachments: () => {},
      recentInbound: { clear: () => {} },
      pendingReapply: { clear: () => {} },
      syncGitStatus: () => {},
    },
    displays: {
      killAllForSession: async () => {},
      start: async () => { throw new Error("unused in tests") },
      get: () => undefined,
      stop: async () => {},
    },
    agentState: { applyEvent: () => {}, clear: () => {} },
    bgTasks: { clear: () => {} },
    commands: { remove: () => {}, refresh: async () => {} },
    register: {
      interruptClaudePane: async () => {},
      notifyAgentError: async () => {},
      ensureClaudeTailer: () => {},
      maybeAutoSendSoulSetup: async () => {},
    },
    outbound: {
      onAssistantMessage: async () => {},
      getChannel: () => undefined,
      telegramApi: undefined,
    },
    orchestration: {
      spawnSession: async () => { throw new Error("unused in tests") },
      refreshTelegramMenu: async () => {},
      wsDto: () => undefined,
      exposedProxyLinksBaseUrl: () => undefined,
      proxyWsPayload: () => ({}),
      proxyLiveness: { getStatus: () => "unknown", refresh: async () => {} },
    },
    stores: {
      fileStore: {} as unknown as FileStore,
      messageLog: { get: () => [], update: () => false, addReaction: () => false },
      searchStore: { searchKnowledge: () => [], searchSessions: () => [] },
      db,
    },
  }
}

function manager(): SessionManager {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new SessionManager(new Registry(db), fakePorts(db))
}

describe("SessionManager runtime store", () => {
  test("registerClaudeRuntime stores the runtime; adapterFor returns its adapter", () => {
    const m = manager()
    const adapter = { fake: true } as unknown as ClaudeCodeAdapter
    m.registerClaudeRuntime("s1", adapter)
    expect(m.adapterFor("s1")).toBe(adapter)
    expect(m.runtimes.get("s1")?.kind).toBe("claude")
  })

  test("deleteRuntime removes it; double delete is a no-op", () => {
    const m = manager()
    m.registerClaudeRuntime("s1", { fake: true } as unknown as ClaudeCodeAdapter)
    m.deleteRuntime("s1")
    expect(m.adapterFor("s1")).toBeUndefined()
    m.deleteRuntime("s1") // must not throw
  })

  test("a codex handle exit unregisters the runtime", () => {
    const m = manager()
    let onExit: ((code: number | null) => void) | undefined
    const handle = {
      pid: 1,
      kill: () => {},
      onExit: (cb: (code: number | null) => void) => { onExit = cb },
    } as unknown as CodexSpawnHandle
    m.registerCodexRuntime("s2", "n", { fake: true } as unknown as CodexAdapter, handle)
    expect(m.adapterFor("s2")).toBeDefined()
    onExit?.(0)
    expect(m.adapterFor("s2")).toBeUndefined()
  })
})
