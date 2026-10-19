import type {
  AuthMethod, ContentBlock, RequestPermissionRequest, RequestPermissionResponse,
  SessionNotification,
} from "@agentclientprotocol/sdk"
import type {
  EventEnvelope, NativeProtocol, NormalizedBody, SubagentActionsSource, SubagentDelivery, SubagentEndedBy,
  SubagentMessaging,
} from "./events/normalized.js"
import type { AccountsOptions } from "./accounts/types.js"
import type { ContextApplied, ContextDrop, ContextPolicy, DriverContextSupport, LaunchContext, RuntimeContextControl, SessionContext, SessionContextUpdate, ToolChangeApplied } from "./context/types.js"
import type { HostMcpServer } from "./mcp/server.js"

export type PermissionOptionKind = "allow_once" | "allow_always" | "reject_once" | "reject_always"

export type PermissionAnswer = { optionId: string; message?: string }
export type QuestionAnswer =
  | { answers: Record<string, string | string[]> }
  | { decline: true }
export type RequestAnswer = PermissionAnswer | QuestionAnswer

export type UserQuestionOption = { id?: string; label: string; description?: string }
export type UserQuestionSpec = {
  id?: string
  header?: string
  prompt?: string
  question?: string
  multiSelect?: boolean
  allowFreeText?: boolean
  options?: UserQuestionOption[]
}

export type PendingRequest =
  | {
      requestId: string
      kind: "permission"
      createdAt: string
      body: Extract<NormalizedBody, { kind: "permission-request" }>
    }
  | {
      requestId: string
      kind: "question"
      createdAt: string
      body: Extract<NormalizedBody, { kind: "user-question" }>
    }

export type { AuthMethod, ContentBlock }

export type SessionState = "idle" | "running" | "interrupting" | "closing" | "closed" | "failed"
export type Capabilities = {
  resume: boolean
  steer: boolean
  fork: boolean
  detach: boolean
  configure?: boolean
  history?: boolean
  permissions?: boolean
}

export type ToolKind = "read" | "edit" | "delete" | "move" | "search" | "execute" | "fetch" | "other"

export type PermissionsSpec =
  | {
      kind: "claude"
      permissionMode: "bypassPermissions" | "acceptEdits" | "default" | "plan" | "auto" | "dontAsk"
    }
  | {
      kind: "codex"
      approvalPolicy: "never" | "on-request" | "untrusted"
      sandbox: "read-only" | "workspace-write" | "danger-full-access"
    }
  | {
      kind: "acp"
      policy: "auto-approve" | "ask" | "read-only"
      nativeMode: string | null
      askKinds?: ToolKind[]
    }
  | { kind: "antigravity"; mode: "bypass" | "acceptEdits" | "plan" | "default" }

export type PermissionsApplied = "now" | "next-turn"

export type SessionConfiguration = {
  model?: string
  reasoningEffort?: string
  /** Codex `serviceTier` (its Fast tier id); other drivers ignore it. */
  serviceTier?: string
}

export type HistoryOptions = {
  cursor?: string
  limit?: number
}

export type HistoryPage = {
  protocol: "native" | "acp"
  items: unknown[]
  cursor?: string
}

/** Secrets remain in caller configuration, never in a SessionRecord. */
export type AuthProfile = {
  agent: string
  env?: Record<string, string>
  methodId?: string
  /** Removed from the launch environment after merging (inherited, driver and profile env). */
  unsetEnv?: string[]
  /** Appended to the agent CLI args. */
  args?: string[]
}

/** `context` replaces the parent's own skills/plugins/MCP servers for the fork; omitted, the fork inherits them. The fork always keeps the parent's instructions (`createdInstructions`). */
export type ForkOptions = { id: string; at?: { nativeTurnId: string }; context?: SessionContextUpdate }
export type ForkSource = { agentSessionId: string; at?: { nativeTurnId: string } }

export type SessionRecord = {
  version: 1
  id: string
  agent: string
  agentSessionId: string
  cwd: string
  createdAt: string
  authProfile?: string
  /** Account id (see core.accounts). Absent: the agent's system account (or `authProfile`). */
  account?: string
  lineage?: { parentSessionId: string; nativeTurnId?: string }
  configuration?: SessionConfiguration
  permissions?: PermissionsSpec
  /** The session's own context (the core default is not stored; it is merged in at each launch). */
  context?: SessionContext
  contextPolicy?: ContextPolicy
  /**
   * The merged instructions (core default + session) snapshotted at creation (forks inherit them).
   * Every later launch uses this text; it is never re-merged, so a changed core default only
   * reaches new sessions.
   */
  createdInstructions?: string
}

export type Completion =
  | { status: "completed"; stopReason: string }
  | { status: "cancelled" }
  | { status: "failed"; error: Error }

export type Receipt = { messageId: string; completed: Promise<Completion> }
export type SendOptions = {
  content: ContentBlock[]
  whenBusy: "queue" | "reject"
  idempotencyKey?: string
}

/** ACP payloads stay intact; native integrations may use a separate namespace. */
export type AgentUpdate =
  /** `sessionId` is the ACP session the update belongs to (the main session or a subagent's child session). */
  | { protocol: "acp"; value: SessionNotification["update"]; replay?: boolean; sessionId?: string }
  | { protocol: "native"; value: unknown; replay?: boolean }

export type CoreEvent =
  | { type: "session.created"; sessionId: string; record: SessionRecord }
  | { type: "session.resumed"; sessionId: string; record: SessionRecord }
  | { type: "session.stateChanged"; sessionId: string; state: SessionState }
  | { type: "session.update"; sessionId: string; update: AgentUpdate }
  | { type: "session.event"; sessionId: string; event: EventEnvelope & NormalizedBody }
  | { type: "session.failed"; sessionId: string; error: Error }
  | { type: "message.accepted"; sessionId: string; messageId: string }
  | { type: "message.started"; sessionId: string; messageId: string }
  | { type: "message.completed"; sessionId: string; messageId: string; result: Completion }
  | { type: "account.switched"; sessionId: string; from: string; to: string; reason: "manual" | "limit" | "unavailable" }
  /** An idle token-account session was reopened (same account, same native id) to pick up a refreshed access token. */
  | { type: "account.refreshed"; sessionId: string; account: string }
  /** A rate limit was hit, or the account was refused, and no other account of the agent is available. */
  | { type: "account.exhausted"; sessionId: string; agent: string; account: string; reason: "limit" | "unavailable" }
  /** The agent's API refused `account` (401/403) on this session's turn; it is now marked unavailable. */
  | { type: "account.unavailable"; sessionId: string; agent: string; account: string; reason: string }
  /** A turn completed on an account marked unavailable: the mark is cleared. */
  | { type: "account.available"; sessionId: string; agent: string; account: string }
  /** The session launched (policy "warn") without these context items. */
  | { type: "context.degraded"; sessionId: string; dropped: ContextDrop[] }
  /** `updateContext` finished, or a host server's tools changed: what each change did (see ContextApplied, ToolChangeApplied). */
  | { type: "context.updated"; sessionId: string; applied: Array<ContextApplied | ToolChangeApplied> }
  /** A host MCP server tool call started (from the agent or one of its subagents). Never carries arguments. */
  | { type: "tool.called"; sessionId: string; server: string; tool: string; callId: string }
  /** It ended: `ok` false for an error, an isError result, a cancel or a lost connection. Never carries the result. */
  | { type: "tool.finished"; sessionId: string; server: string; tool: string; callId: string; ok: boolean; durationMs: number }

export type Observer = (event: CoreEvent) => void | Promise<void>

/** Driver-facing permission callback. Hosts answer via Session.requests, not this type. */
export type PermissionRequest = RequestPermissionRequest & {
  coreSessionId: string
  /** Set when the request was raised by a subagent (its tool call). */
  subagentId?: string
  detail?: { command?: string; cwd?: string; blockedPath?: string }
}

export type PermissionResponse = RequestPermissionResponse & { message?: string }

export type PermissionHandler = (
  request: PermissionRequest,
  signal: AbortSignal,
) => Promise<PermissionResponse>

export type QuestionRequest = {
  toolCallId?: string
  /** Set when the question was raised by a subagent. */
  subagentId?: string
  questions: UserQuestionSpec[]
}

export type QuestionResponse =
  | { outcome: "answered"; answers: Record<string, string | string[]> }
  | { outcome: "declined" }
  | { outcome: "cancelled" }

export type AnswersHandler = (
  request: QuestionRequest,
  signal: AbortSignal,
) => Promise<QuestionResponse>

/** Driver-minted opaque turn/work identity. Core does not parse native payloads. */
export type ActivityPhase = "started" | "completed"

export type ActivityNotice = {
  id: string
  phase: ActivityPhase
}

/**
 * What Core remembers about one subagent across a resume (the driver's own registry is in-memory).
 * Folded from the `subagent` bodies the session emitted; persisted next to the session record;
 * handed back to the driver as `DriverContext.subagents` when the session is resumed so a
 * subagent stays addressable (Grok/OpenCode child sessions, Claude task ids and spawn calls).
 */
export type SubagentSnapshot = {
  subagentId: string
  status: "running" | "completed" | "failed" | "cancelled"
  endedBy?: SubagentEndedBy
  /** First spawning tool call (Claude: child frames of a resumed task still carry it). */
  spawnCallId?: string
  /** Latest spawning/resuming call. */
  parentCallId?: string
  nativeId?: string
  name?: string
  description?: string
  background?: boolean
  model?: string
  messaging?: SubagentMessaging
  canMessage?: boolean
  canStop?: boolean
  actionsSource?: SubagentActionsSource
  cannotMessageReason?: string
  cannotStopReason?: string
  /** Epoch ms. */
  updatedAt: number
}

export type DriverContext = {
  sessionId: string
  cwd: string
  profile?: AuthProfile
  /** The session's account id (no secrets; its env/args arrive in `profile`). */
  account?: string
  signal: AbortSignal
  resumeId?: string
  forkFrom?: ForkSource
  configuration?: SessionConfiguration
  permissions?: PermissionsSpec
  onUpdate(update: AgentUpdate): void
  onExit(error: Error): void
  requestPermission: PermissionHandler
  requestAnswers: AnswersHandler
  /** Optional. Drivers that omit this retain owned-prompt lifecycle only. */
  onActivity?(notice: ActivityNotice): void
  /** Resume only: the subagents this session had (see SubagentSnapshot). */
  subagents?: SubagentSnapshot[]
  /** The session's merged context for this launch; absent when there is none (the launch is then unchanged). */
  sessionContext?: LaunchContext
}

export type CloseMode = "shutdown" | "detach"
export type CloseOptions = { mode: CloseMode }
export type CoreCloseOptions = { agents: CloseMode }

export function requireCloseMode(options: { mode?: unknown } | undefined): CloseMode {
  if (!options || (options.mode !== "shutdown" && options.mode !== "detach")) {
    throw new TypeError("close mode is required")
  }
  return options.mode
}

export function requireAgentsCloseMode(options: { agents?: unknown } | undefined): CloseMode {
  if (!options || (options.agents !== "shutdown" && options.agents !== "detach")) {
    throw new TypeError("core close agents mode is required")
  }
  return options.agents
}

/**
 * What a runtime did with a message for a subagent. `direct`: already delivered to the child.
 * `relay`: the runtime cannot address the child; `relay` holds the main-thread user message
 * that asks the parent model to forward it, and Session sends it through the normal queue.
 */
export type SubagentMessageResult = { via: "direct" } | { via: "relay"; relay: ContentBlock[] }

/**
 * What became of a message to a subagent. Direct messages are `delivered` once the runtime
 * accepted them. A relayed one settles when the parent's forwarding tool reports back:
 * `delivered`, `refused` (with the agent's own reason, e.g. Claude's "was stopped by the user"),
 * or `unconfirmed` when the relay turn ended without the parent ever calling the tool.
 */
export type SubagentMessageDelivery = SubagentDelivery | { status: "unconfirmed"; reason?: string }

export type SubagentMessageOptions = {
  /** Only for relayed messages (they are ordinary main-thread input). Default "queue". */
  whenBusy?: "queue" | "reject"
}

/** Runtime methods own their I/O. close must settle any in-flight prompt. */
export type AgentRuntime = {
  readonly agentSessionId: string
  readonly capabilities: Capabilities
  /** How this runtime's `native` updates are labelled on normalized events. */
  readonly nativeProtocol?: NativeProtocol
  /**
   * Resume only: the driver's view of `DriverContext.subagents` after restoring them (a fresh
   * process cannot still be running what the old one ran). Core seeds its registry from this.
   */
  readonly restoredSubagents?: SubagentSnapshot[]
  prompt(content: ContentBlock[], signal: AbortSignal): Promise<{ stopReason: string }>
  interrupt(): Promise<void>
  close(options: CloseOptions): Promise<void>
  steer?(content: ContentBlock[]): Promise<void>
  /** Full requested state (missing keys are driver/factory defaults). Core clones before invoke; do not mutate the persisted request via this argument. */
  configure?(configuration: SessionConfiguration): Promise<void>
  /** Live native view. Session.configuration() exposes requested persisted state instead; `{}` means defaults. */
  configuration?(): SessionConfiguration
  history?(options: HistoryOptions): Promise<HistoryPage>
  /** Pure mapper: native/ACP update → normalized bodies. Unknown frames yield []. */
  normalize?(update: AgentUpdate): NormalizedBody[]
  /** Flush buffered assistant/reasoning deltas as final messages. */
  flush?(): NormalizedBody[]
  setPermissions?(spec: PermissionsSpec): Promise<{ applied: PermissionsApplied }>
  /** Deliver input to a subagent (direct), or say how the parent must relay it. */
  messageSubagent?(subagentId: string, content: ContentBlock[]): Promise<SubagentMessageResult>
  /** Stop a running subagent without interrupting the main turn. */
  stopSubagent?(subagentId: string): Promise<void>
  /** Session context changes this process can take without a relaunch (session.updateContext). */
  context?: RuntimeContextControl
}

export type AuthContext = {
  profile?: AuthProfile
  signal: AbortSignal
}

export type AgentDriver = {
  readonly id: string
  open(context: DriverContext): Promise<AgentRuntime>
  /**
   * Stops what still runs for session `sessionId` with no runtime open on it in this process (an
   * agent a detach or a host restart left behind its keeper), without launching anything; resolves
   * once it is gone, at once when nothing runs. `sessions.close(id, { mode: "shutdown" })` calls it.
   * Every built-in driver stops the session's keeper.
   */
  shutdownDetached?(sessionId: string): Promise<void>
  /**
   * Deletes what the driver keeps on disk for session `sessionId` (every built-in driver: its
   * keeper's directory) unless an agent still runs for it: resolves true once that is gone (or there
   * was nothing), false, deleting nothing, while one runs. `sessions.forget(id)` calls it, and is
   * `session_busy` on false.
   */
  forgetDetached?(sessionId: string): Promise<boolean>
  /** Which session context items this driver applies, and how. Absent: none. */
  readonly context?: DriverContextSupport
  auth?: {
    methods(context: AuthContext): Promise<AuthMethod[]>
    login(context: AuthContext, methodId: string): Promise<void>
  }
}

export type CoreLimits = {
  interruptTimeoutMs: number
  maxPending: number
  outstandingActivity: number
}

export type CoreOptions = {
  stateDirectory: string
  agents: AgentDriver[]
  profiles?: Record<string, AuthProfile>
  accounts?: AccountsOptions
  onObserverError?: (error: Error) => void
  limits: CoreLimits
  /** Context every session gets, before its own (see API.md "Session context"). */
  context?: SessionContext
  /** Default "error": a context item the agent cannot apply fails the launch. "warn": launch and emit `context.degraded`. */
  contextPolicy?: ContextPolicy
  /** Host MCP servers registered up front (see `core.mcp`), so stored sessions that name them can launch. */
  mcpServers?: HostMcpServer[]
  /**
   * Also refuse a session `cwd` that contains the state directory (`invalid_workdir`), as `supermux()` does. Absent:
   * not checked. A `cwd` that is the state directory or is inside it is always refused. Leave it off only when agents
   * must work in a folder above the state directory (a home directory, say), knowing they can write there.
   */
  refuseWorkdirsContainingState?: boolean
}

/** `account` and `authProfile` are mutually exclusive. Neither: the agent's system account. */
export type CreateOptions = { agent: string; cwd: string; id: string; authProfile?: string; account?: string; configuration?: SessionConfiguration; permissions?: PermissionsSpec; context?: SessionContext; contextPolicy?: ContextPolicy }

/** Optional resume overrides (each field is described on it). */
export type ResumeOptions = {
  /** A patch over the record's configuration. `configuration: undefined` is omitted (no-options resume). `{}` is an explicit no-op patch. */
  configuration?: SessionConfiguration
  /** Switches the session to another account of the same agent (a live session is shut down and reopened). */
  account?: string
  /** Replaces the session's own skills/plugins/MCP servers for this and later launches (a live idle session is relaunched); instructions are fixed at creation. */
  context?: SessionContextUpdate
  /**
   * For a record created before instructions were snapshotted (no `createdInstructions` and no own
   * context, e.g. a host's pre-context sessions), these become the session's instructions on this
   * launch and are fixed from then on, exactly as if they had been given at creation. Ignored for
   * every other record.
   */
  adoptInstructions?: string | string[]
  /**
   * The session's permissions from this launch on: validated like `create`'s (`invalid_input`),
   * given to the driver ahead of the record's spec and stored in the record, so a setting only a
   * launch can apply (Codex's process-wide sandbox/approval policy) is right from the start. A live
   * session gets `setPermissions` instead. An agent that is still running behind a keeper keeps the
   * spec it has (its live spec wins at re-attach): call `session.setPermissions` after the resume
   * when it must change.
   */
  permissions?: PermissionsSpec
}

/** Metadata-only registration of an existing native conversation. Native history is checked later by resume. */
export type AdoptOptions = {
  id: string
  agent: string
  agentSessionId: string
  cwd: string
  createdAt?: string
  authProfile?: string
  configuration?: SessionConfiguration
}
export type InterruptResult = { status: "stopped" | "already_idle" | "unconfirmed" }
