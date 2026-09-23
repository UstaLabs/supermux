import type {
  AuthMethod, ContentBlock, RequestPermissionRequest, RequestPermissionResponse,
  SessionNotification,
} from "@agentclientprotocol/sdk"
import type { EventEnvelope, NormalizedBody } from "./events/normalized.js"

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
  | { protocol: "acp"; value: SessionNotification["update"]; replay?: boolean }
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

export type Observer = (event: CoreEvent) => void | Promise<void>

/** Driver-facing permission callback. Hosts answer via Session.requests, not this type. */
export type PermissionRequest = RequestPermissionRequest & {
  coreSessionId: string
  detail?: { command?: string; cwd?: string; blockedPath?: string }
}

export type PermissionResponse = RequestPermissionResponse & { message?: string }

export type PermissionHandler = (
  request: PermissionRequest,
  signal: AbortSignal,
) => Promise<PermissionResponse>

export type QuestionRequest = {
  toolCallId?: string
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

export type DriverContext = {
  sessionId: string
  cwd: string
  profile?: AuthProfile
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
export type AgentRuntime = {
  readonly agentSessionId: string
  readonly capabilities: Capabilities
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
  onObserverError?: (error: Error) => void
  limits: CoreLimits
}

export type CreateOptions = { agent: string; cwd: string; id: string; authProfile?: string; configuration?: SessionConfiguration; permissions?: PermissionsSpec }

/** Optional resume overrides. `configuration: undefined` is omitted (no-options resume). `{}` is an explicit no-op patch. */
export type ResumeOptions = { configuration?: SessionConfiguration }

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
