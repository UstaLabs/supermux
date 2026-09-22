export { createCore, Core } from "./core.js"
export { Session } from "./session.js"
export type {
  NormalizedBody, EventEnvelope, NativeRef, EventOrigin, TurnCompleteReason,
} from "./events/normalized.js"
export { CoreError, UnsupportedOperation } from "./errors.js"
export { connectKeeper } from "./keeper/index.js"
export type {
  AgentDriver, AgentRuntime, DriverContext, AgentUpdate, AuthContext, AuthProfile,
  AuthMethod, ActivityNotice, ActivityPhase, Capabilities, ContentBlock, CoreEvent, CoreLimits, CoreOptions, CreateOptions,
  ResumeOptions, AdoptOptions, Completion, InterruptResult, Observer, PermissionHandler, PermissionRequest, PermissionResponse,
  PermissionOptionKind, RequestAnswer, PermissionAnswer, QuestionAnswer, PendingRequest,
  QuestionRequest, QuestionResponse, AnswersHandler, UserQuestionSpec, UserQuestionOption,
  Receipt, SendOptions,
  SessionRecord, SessionState, ForkOptions, ForkSource,
  SessionConfiguration, HistoryOptions, HistoryPage,
  CloseMode, CloseOptions, CoreCloseOptions,
} from "./types.js"
export { requireCloseMode, requireAgentsCloseMode } from "./types.js"
export { createHost } from "./host/index.js"
export type { Host, HostHandle, HostOptions, HostRegistration, HostStartOptions } from "./host/index.js"
export { createHostProvider } from "./host/provider.js"
export type { HostProvider, HostFactory } from "./host/provider.js"
