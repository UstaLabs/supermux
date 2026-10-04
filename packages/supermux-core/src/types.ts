import type {
  AuthMethod, ContentBlock, RequestPermissionRequest, RequestPermissionResponse,
  SessionNotification,
} from "@agentclientprotocol/sdk"
import type {
  EventEnvelope, NativeProtocol, NormalizedBody, SubagentActionsSource, SubagentDelivery, SubagentEndedBy,
  SubagentMessaging,
} from "./events/normalized.js"
import type { AccountsOptions } from "./accounts/types.js"

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

export type PermissionsApplied = "now" | "next-turn"

export type SessionConfiguration = {
  model?: string
  reasoningEffort?: string
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

export type ForkOptions = { id: string; at?: { nativeTurnId: string } }
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
  | { type: "account.switched"; sessionId: string; from: string; to: string; reason: "manual" | "limit" }
  /** An idle token-account session was reopened (same account, same native id) to pick up a refreshed access token. */
  | { type: "account.refreshed"; sessionId: string; account: string }
  /** A rate limit was hit and no other account of the agent is available. */
  | { type: "account.exhausted"; sessionId: string; agent: string; account: string }

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

/** Runtime methods own their I/O. close must settle any in-flight prompt. */
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
}

export type AuthContext = {
  profile?: AuthProfile
  signal: AbortSignal
}

export type AgentDriver = {
  readonly id: string
  open(context: DriverContext): Promise<AgentRuntime>
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
}

/** `account` and `authProfile` are mutually exclusive. Neither: the agent's system account. */
export type CreateOptions = { agent: string; cwd: string; id: string; authProfile?: string; account?: string; configuration?: SessionConfiguration; permissions?: PermissionsSpec }

/** Optional resume overrides. `configuration: undefined` is omitted (no-options resume). `{}` is an explicit no-op patch. */
/** `account` switches the session to another account of the same agent (a live session is shut down and reopened). */
export type ResumeOptions = { configuration?: SessionConfiguration; account?: string }

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
