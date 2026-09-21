import type {
  AuthMethod, ContentBlock, RequestPermissionRequest, RequestPermissionResponse,
  SessionNotification,
} from "@agentclientprotocol/sdk"

export type { AuthMethod, ContentBlock }

export type SessionState = "idle" | "running" | "interrupting" | "closing" | "closed" | "failed"
export type Capabilities = {
  resume: boolean
  steer: boolean
  fork: boolean
  detach: boolean
  configure?: boolean
  history?: boolean
}

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
  | { type: "session.failed"; sessionId: string; error: Error }
  | { type: "message.accepted"; sessionId: string; messageId: string }
  | { type: "message.started"; sessionId: string; messageId: string }
  | { type: "message.completed"; sessionId: string; messageId: string; result: Completion }

export type Observer = (event: CoreEvent) => void | Promise<void>
export type PermissionHandler = (
  request: RequestPermissionRequest & { coreSessionId: string },
  signal: AbortSignal,
) => Promise<RequestPermissionResponse>

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
  onUpdate(update: AgentUpdate): void
  onExit(error: Error): void
  requestPermission: PermissionHandler
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
  onPermission?: PermissionHandler
  onObserverError?: (error: Error) => void
  limits: CoreLimits
}

export type CreateOptions = { agent: string; cwd: string; id: string; authProfile?: string; configuration?: SessionConfiguration }

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
