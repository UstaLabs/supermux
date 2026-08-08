import { describe, expect, test } from "bun:test"
import { join } from "path"
import { openDb, runMigrations, type Db } from "../storage/db"
import { Registry } from "./registry"
import { SessionManager, type SessionManagerPorts } from "./manager"
import type { CodexAdapter } from "../agents/codex/adapter"
import type { CodexSpawnHandle } from "../agents/codex/spawn"
import type { ClaudeCodeAdapter } from "../agents/claude"
import { CursorAdapter, type CursorRunner } from "../agents/cursor/adapter"
import { OpenCodeAdapter, type OpenCodeClientLike } from "../agents/opencode/adapter"
import type { OpenCodeSpawnHandle } from "../agents/opencode/spawn"
import { GrokAdapter } from "../agents/grok/adapter"
import type { GrokRunner } from "../agents/grok/runner"
import type { AgentPhase } from "./agent-state-store"
import type { FileStore } from "../files/store"

/** Seams for the applyConfig frame tests; everything else stays inert. */
type PortSeams = {
  /** agent-state phase reported for every session (queue-when-busy path). */
  phase?: AgentPhase
  /** model cache lookup (reasoning-level validation). */
  lookupModels?: SessionManagerPorts["config"]["lookupModels"]
  /** collects notifyAgentError calls as "type:message". */
  agentErrors?: string[]
  /** collects webChannel broadcast frames. */
  frames?: object[]
}

/** Minimal inert ports: the runtime-store tests never cross into a port. */
function fakePorts(db: Db, seams: PortSeams = {}): SessionManagerPorts {
  return {
    getWebChannel: () => (seams.frames ? { broadcastToAll: (frame: object) => { seams.frames!.push(frame) } } : undefined),
    getAgentRpc: () => ({ settle: () => {}, fail: () => {} }),
    socket: { sendInbound: async () => {} },
    backend: { runtimeTargetIdOf: async () => null, kill: async () => {} },
    cleanup: {
      terminals: { killAllForSession: async () => {} },
      fsWatcher: { killSession: () => {} },
      stopClaudeTailer: () => {},
      releaseDraftAttachments: () => {},
      recentInbound: { clear: () => {} },
      syncGitStatus: () => {},
    },
    displays: {
      killAllForSession: async () => {},
      start: async () => { throw new Error("unused in tests") },
      get: () => undefined,
      stop: async () => {},
    },
    agentState: { applyEvent: () => {}, clear: () => {}, get: () => ({ phase: seams.phase ?? "idle", since: 0 }) },
    bgTasks: { clear: () => {} },
    commands: { remove: () => {}, refresh: async () => {} },
    config: { lookupModels: seams.lookupModels ?? (() => []) },
    register: {
      interruptClaudePane: async () => {},
      notifyAgentError: async (_id, _name, errorType, message) => { seams.agentErrors?.push(`${errorType}:${message}`) },
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
    resume: {
      bind: async () => {},
      ensureSessionWorktree: async () => {},
      sessionEffort: () => undefined,
      resolveAttachment: async () => { throw new Error("unused in tests") },
      wireAdapterEvents: () => {},
      sessionBackend: {
        list: async () => [],
        create: async () => { throw new Error("unused in tests") },
      } as unknown as import("../runtime/session-backend").SessionBackend,
      tmuxSession: "mux-test",
    },
  }
}

function manager(seams: PortSeams = {}): SessionManager {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new SessionManager(new Registry(db), fakePorts(db, seams))
}

describe("SessionManager resume frames", () => {
  test("suspended resume of an unknown agent kind returns false", async () => {
    const m = manager()
    const ok = await m.resumeSuspended({ id: "x", name: "n", agent: "not-a-kind", workdir: "/tmp" })
    expect(ok).toBe(false)
  })

  test("suspended resume of opencode WITHOUT agent_home returns false (arm exists, home missing)", async () => {
    const m = manager()
    const ok = await m.resumeSuspended({ id: "x", name: "n", agent: "opencode", workdir: "/tmp" })
    expect(ok).toBe(false)
  })

  test("archive resume of a non-archived id reports the guard error", async () => {
    const m = manager()
    const r = await m.resumeFromArchive("nope")
    expect(r.ok).toBe(false)
    expect(r.error).toContain("not archived")
  })
})

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

// ── applyConfig: the model/effort entry (frame around the per-kind dialects) ─

function cursorAdapter(model?: string): CursorAdapter {
  return new CursorAdapter({
    sessionName: "t",
    workdir: "/tmp",
    runner: (async () => { throw new Error("no turns in this test") }) as unknown as CursorRunner,
    persistSessionId: async () => {},
    model,
  })
}

function grokAdapter(model?: string): GrokAdapter {
  return new GrokAdapter({
    sessionName: "t",
    workdir: "/tmp",
    runner: (() => { throw new Error("no child in this test") }) as unknown as GrokRunner,
    persistSessionId: async () => {},
    model,
  })
}

describe("SessionManager applyConfig", () => {
  test("unknown session id reports the exact error", async () => {
    const m = manager()
    const r = await m.applyConfig("ghost", { model: "m1" })
    expect(r).toEqual({ ok: false, error: "no such session: ghost" })
  })

  test("cursor model switch: registry write + typed live-adapter update + broadcast, applied", async () => {
    const frames: object[] = []
    const m = manager({ frames })
    m.registry.register({ id: "c1", name: "cur", workdir: "/tmp", pid: 0, agent: "cursor", model: "old-model" })
    const adapter = cursorAdapter("old-model")
    m.registerCursorRuntime("c1", adapter)
    const r = await m.applyConfig("c1", { model: "new-model" })
    expect(r).toEqual({ ok: true, status: "applied" })
    expect(adapter.model).toBe("new-model")
    expect(m.registry.get("c1")?.model).toBe("new-model")
    expect(frames).toContainEqual({ type: "session_state", session: "c1", model: "new-model" })
  })

  test("opencode model switch goes through the typed adapter accessor", async () => {
    const m = manager()
    m.registry.register({ id: "o1", name: "oc", workdir: "/tmp", pid: 0, agent: "opencode", model: "openai/gpt-5" })
    const adapter = new OpenCodeAdapter({
      sessionName: "oc",
      workdir: "/tmp",
      client: {} as unknown as OpenCodeClientLike,
      persistSessionId: async () => {},
      model: "openai/gpt-5",
    })
    const handle = { pid: 1, kill: () => {}, onExit: () => {} } as unknown as OpenCodeSpawnHandle
    m.registerOpenCodeRuntime("o1", "oc", adapter, handle)
    const r = await m.applyConfig("o1", { model: "anthropic/claude-sonnet-5" })
    expect(r).toEqual({ ok: true, status: "applied" })
    expect(adapter.model).toBe("anthropic/claude-sonnet-5")
  })

  test("grok model switch pokes the live adapter and does NOT touch effort", async () => {
    const m = manager()
    m.registry.register({ id: "g1", name: "gk", workdir: "/tmp", pid: 0, agent: "grok", model: "grok-4" })
    const adapter = grokAdapter("grok-4")
    const effortCalls: (string | undefined)[] = []
    adapter.setEffort = async (e) => { effortCalls.push(e) }
    m.registerGrokRuntime("g1", adapter)
    const r = await m.applyConfig("g1", { model: "grok-4-fast" })
    expect(r).toEqual({ ok: true, status: "applied" })
    expect(adapter.model).toBe("grok-4-fast")
    expect(effortCalls).toEqual([])
  })

  test("live-model kinds apply even with no runtime adapter (registry only)", async () => {
    const m = manager()
    m.registry.register({ id: "c2", name: "cur2", workdir: "/tmp", pid: 0, agent: "cursor", model: "old-model" })
    const r = await m.applyConfig("c2", { model: "new-model" })
    expect(r).toEqual({ ok: true, status: "applied" })
    expect(m.registry.get("c2")?.model).toBe("new-model")
  })

  test("cursor effort switch is rejected with the dialect's reason", async () => {
    const m = manager()
    m.registry.register({ id: "c3", name: "cur3", workdir: "/tmp", pid: 0, agent: "cursor" })
    const r = await m.applyConfig("c3", { effort: "high" })
    expect(r).toEqual({ ok: false, error: "cursor sessions use model selection for reasoning depth" })
  })

  test("unsupported reasoning level is rejected before any registry write", async () => {
    const m = manager()
    m.registry.register({ id: "k0", name: "cl0", workdir: "/tmp", pid: 1, agent: "claude", reasoningLevel: "low" })
    const r = await m.applyConfig("k0", { effort: "not-a-level" })
    expect(r).toEqual({ ok: false, error: "unsupported reasoning level: not-a-level" })
    expect(m.registry.get("k0")?.reasoningLevel).toBe("low")
  })

  test("claude switches queue while the session is busy — even with applyNow", async () => {
    const m = manager({ phase: "running" })
    m.registry.register({ id: "k1", name: "cl1", workdir: "/tmp", pid: 1, agent: "claude", model: "old-m" })
    const r = await m.applyConfig("k1", { model: "new-m", applyNow: true })
    expect(r).toEqual({ ok: true, status: "queued" })
    // The desired value is persisted up-front; the live apply is deferred.
    expect(m.registry.get("k1")?.model).toBe("new-m")
  })

  test("the idle drain runs the deferred apply; a failure rolls back and notifies", async () => {
    const agentErrors: string[] = []
    const frames: object[] = []
    const m = manager({ phase: "running", agentErrors, frames })
    m.registry.register({ id: "k2", name: "cl2", workdir: "/tmp", pid: 1, agent: "claude", model: "old-m" })
    const queued = await m.applyConfig("k2", { model: "new-m" })
    expect(queued).toEqual({ ok: true, status: "queued" })

    // Not idle yet → nothing drains.
    await m.drainPendingReapply("k2", "running")
    expect(m.registry.get("k2")?.model).toBe("new-m")

    // Idle → drain runs. No tmux window exists in tests, so the claude apply
    // fails explicitly ("session window not found") → registry rolls back,
    // clients get the corrected state, and the user is notified.
    await m.drainPendingReapply("k2", "idle")
    expect(m.registry.get("k2")?.model).toBe("old-m")
    expect(agentErrors).toEqual(["config:Failed to apply model/effort change: session window not found"])
    expect(frames).toContainEqual({ type: "session_state", session: "k2", model: "old-m", reasoningLevel: undefined })

    // The queue entry was consumed — a second idle transition is a no-op.
    await m.drainPendingReapply("k2", "idle")
    expect(agentErrors).toHaveLength(1)
  })

  test("an idle claude apply fails explicitly without a window and rolls back", async () => {
    const m = manager()
    m.registry.register({ id: "k3", name: "cl3", workdir: "/tmp", pid: 1, agent: "claude", reasoningLevel: "low" })
    const r = await m.applyConfig("k3", { effort: "high" })
    expect(r).toEqual({ ok: false, error: "session window not found" })
    expect(m.registry.get("k3")?.reasoningLevel).toBe("low")
  })

  test("codex reapply without agent_home fails with the exact error and rolls back", async () => {
    const m = manager()
    m.registry.register({ id: "x1", name: "cx", workdir: "/tmp", pid: 1, agent: "codex", model: "old-m" })
    const r = await m.applyConfig("x1", { model: "new-m" })
    expect(r).toEqual({ ok: false, error: "codex session missing agent_home" })
    expect(m.registry.get("x1")?.model).toBe("old-m")
  })

  test("grok effort reapply without a live adapter fails with the exact error and rolls back", async () => {
    const m = manager({
      lookupModels: () => [
        { id: "grok-4", displayName: "Grok 4", agent: "grok", reasoningLevels: [{ id: "low" }, { id: "high" }] },
      ],
    })
    m.registry.register({ id: "g2", name: "gk2", workdir: "/tmp", pid: 0, agent: "grok", model: "grok-4", reasoningLevel: "low" })
    const r = await m.applyConfig("g2", { effort: "high" })
    expect(r).toEqual({ ok: false, error: "grok session has no live adapter" })
    expect(m.registry.get("g2")?.reasoningLevel).toBe("low")
  })
})
