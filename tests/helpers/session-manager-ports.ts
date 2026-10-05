// Inert SessionManager ports for tests (shared by manager.test.ts and the mux-tools host server
// tests). Not used by production code.
import type { Db } from "../../src/core/storage/db"
import type { SessionManagerPorts } from "../../src/core/session-manager/manager"
import type { AgentPhase } from "../../src/core/session-manager/agent-state-store"
import type { FileStore } from "../../src/core/files/store"
import { ReviewStore } from "../../src/core/review/store"
import { WalkthroughStore } from "../../src/core/walkthrough/store"

/** Seams for the applyConfig frame tests; everything else stays inert. */
export type PortSeams = {
  /** agent-state phase reported for every session (queue-when-busy path). */
  phase?: AgentPhase
  /** model cache lookup (reasoning-level validation). */
  lookupModels?: SessionManagerPorts["config"]["lookupModels"]
  /** collects notifyAgentError calls as "type:message". */
  agentErrors?: string[]
  /** collects webChannel broadcast frames. */
  frames?: object[]
  sessionEffort?: SessionManagerPorts["resume"]["sessionEffort"]
}

/** Minimal inert ports: the runtime-store tests never cross into a port. */
export function fakePorts(db: Db, seams: PortSeams = {}): SessionManagerPorts {
  return {
    getWebChannel: () => (seams.frames ? { broadcastToAll: (frame: object) => { seams.frames!.push(frame) } } : undefined),
    getAgentRpc: () => ({ settle: () => {}, fail: () => {} }),
    socket: { sendInbound: async () => {} },
    inbound: {},
    backend: { runtimeTargetIdOf: async () => null, kill: async () => {} },
    cleanup: {
      terminals: { killAllForSession: async () => {} },
      stopClaudeTailer: () => {},
      releaseDraftAttachments: () => {},
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
      onAssistantMessage: async () => ({ ok: true as const, delivered: 1 }),
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
      postBrokerInbound: () => {},
    },
    stores: {
      fileStore: {} as unknown as FileStore,
      messageLog: { get: () => [], update: () => false, addReaction: () => false, findByChannelMessageId: () => undefined },
      searchStore: { searchKnowledge: () => [], searchSessions: () => [] },
      db,
      reviewStore: new ReviewStore(db),
      walkthroughStore: new WalkthroughStore(db),
    },
    resume: {
      bind: async () => {},
      ensureSessionWorktree: async () => {},
      sessionEffort: seams.sessionEffort ?? (() => undefined),
      resolveAttachment: async () => { throw new Error("unused in tests") },
      wireAdapterEvents: () => {},
      sessionBackend: {
        list: async () => [],
        create: async () => { throw new Error("unused in tests") },
      } as unknown as import("../../src/core/runtime/session-backend").SessionBackend,
      tmuxSession: "mux-test",
    },
  }
}
