# Signatures

What to import and what each call takes and returns. The rules behind them are in ../SKILL.md and, complete, in the package's API.md. `?` marks what may be left out.

## Making a core

```ts
import { supermux, defaults, createCore } from "@supermux/core"
import type { Core, SupermuxOptions, SupermuxAgents, CoreOptions } from "@supermux/core"

supermux(options: SupermuxOptions): Core
// { stateDirectory: string; agents: SupermuxAgents; limits?; context?; contextPolicy?; mcpServers?; accounts?; profiles?; onObserverError?; refuseWorkdirsContainingState? }
// SupermuxAgents: { claude?, codex?, grok?, opencode?, cursor?, antigravity? }; {} takes every default, antigravity needs { home }
defaults // deeply frozen: every value supermux() fills in, by agent (and core.limits, core.refuseWorkdirsContainingState)
createCore(options: CoreOptions): Core // the strict form: driver instances, every limit given
```

## Core

```ts
import type {
  Observer, CoreEvent, CoreCloseOptions, AgentCapabilities, CreateOptions, ResumeOptions, Session, SessionRecord,
  CloseOptions, ContextPatch, UpdateContextOptions, UpdateContextResult,
} from "@supermux/core"
import type { HostMcpServer } from "@supermux/core/mcp"

core.subscribe(observer: Observer): () => void // Observer: (event: CoreEvent) => void | Promise<void>; returns unsubscribe
core.close(options: CoreCloseOptions): Promise<void> // { agents: "shutdown" | "detach" }
core.capabilities(agent: string): AgentCapabilities // { context, contextUpdate, hostToolChanges }

core.sessions.create(options: CreateOptions): Promise<Session>
// { id; agent; cwd; context?; contextPolicy?; permissions?; configuration?; account? }
core.sessions.resume(id: string, options?: ResumeOptions): Promise<Session>
// { context?; permissions?; configuration?; account?; adoptInstructions? }
core.sessions.live(id: string): Session | undefined
core.sessions.get(id: string): Promise<SessionRecord | undefined>
core.sessions.list(filter?: { agent?: string }): Promise<SessionRecord[]>
core.sessions.close(id: string, options: CloseOptions): Promise<void> // { mode: "shutdown" | "detach" }
core.sessions.forget(id: string): Promise<void> // a closed session's record
core.sessions.updateContext(id: string, patch: ContextPatch, options?: UpdateContextOptions): Promise<UpdateContextResult>
core.sessions.reopening(id: string): boolean // the core is relaunching the session's agent itself

core.mcp.register(server: HostMcpServer): void
core.mcp.unregister(name: string): boolean
core.mcp.get(name: string): HostMcpServer | undefined
core.mcp.list(): string[]
```

## Session

```ts
import type {
  SessionState, Capabilities, SendOptions, Receipt, Completion, ContentBlock, InterruptResult, PendingRequest,
  RequestAnswer, PermissionsSpec, PermissionsApplied, SessionConfiguration, HistoryOptions, HistoryPage, ForkOptions,
  SubagentMessageOptions,
} from "@supermux/core"

session.id: string
session.snapshot(): SessionRecord & { state: SessionState; pending: number; paused: boolean; pendingRequests: number }
session.capabilities(): Capabilities // { resume; steer; fork; detach; configure?; history?; permissions? }
session.send(options: SendOptions): Promise<Receipt>
// SendOptions: { content: ContentBlock[]; whenBusy: "queue" | "reject"; idempotencyKey? }
// Receipt: { messageId: string; completed: Promise<Completion> }
// Completion: { status: "completed"; stopReason } | { status: "cancelled" } | { status: "failed"; error }
session.pending.list(): { messageId: string }[]
session.pending.cancel(messageId: string): boolean
session.pending.clear(): void
session.pending.continue(): void // runs the queue again after an interrupt
session.requests.list(): PendingRequest[]
session.requests.respond(requestId: string, answer: RequestAnswer): Promise<void>
session.interrupt(options: { pending: "keep" | "discard" }): Promise<InterruptResult>
// InterruptResult: { status: "stopped" | "already_idle" | "unconfirmed" }
session.close(options: CloseOptions): Promise<void>
session.setPermissions(spec: PermissionsSpec): Promise<{ applied: PermissionsApplied }>
session.updateContext(patch: ContextPatch, options?: UpdateContextOptions): Promise<UpdateContextResult>
session.steer(input: { content: ContentBlock[] }): Promise<void> // when capabilities().steer (Codex)
session.configure(patch: SessionConfiguration): Promise<void> // when capabilities().configure (Codex, Grok, Antigravity)
session.configuration(): SessionConfiguration // { model?; reasoningEffort?; serviceTier? }
session.history(options?: HistoryOptions): Promise<HistoryPage> // when capabilities().history (Codex)
session.fork(options: ForkOptions): Promise<Session> // when capabilities().fork (Codex); { id; at?; context? }
session.messageSubagent(subagentId: string, content: ContentBlock[], options?: SubagentMessageOptions):
  Promise<{ via: "direct" | "relay"; receipt?: Receipt; delivery: Promise<{ status: "delivered" | "refused" | "unconfirmed"; reason?: string }> }>
  // receipt: "relay" only, the main-thread message that asks the parent agent to pass it on
session.stopSubagent(subagentId: string): Promise<void>
```

## Requests

```ts
import type { PermissionAnswer, QuestionAnswer } from "@supermux/core"

type PendingRequest = { requestId: string; kind: "permission" | "question"; createdAt: string; body: object }
// body: the request's permission-request or user-question event body
type PermissionAnswer = { optionId: string; message?: string } // the id of an option found by its kind
type QuestionAnswer = { answers: Record<string, string | string[]> } | { decline: true } // by question id: option id(s) or free text
type RequestAnswer = PermissionAnswer | QuestionAnswer
```

## Events

```ts
import type { CoreEvent, EventEnvelope, NormalizedBody } from "@supermux/core"

// An observer gets each CoreEvent; a "session.event" one is { type; sessionId; event: EventEnvelope & NormalizedBody }.
// Every kind and its fields: events.md
```

## Host tools

```ts
import { mcpServer, tool, HostMcpServer } from "@supermux/core/mcp"
import type { McpServerOptions, ToolDefinition, ToolContext, HostTool, CallToolResult } from "@supermux/core/mcp"

mcpServer(options: McpServerOptions): HostMcpServer
// { name; tools: Record<string, HostTool>; instructions?; version?; toolChanges?: "reload" | "live-only" }
// or { name; create: (ctx) => McpServer; … }: an SDK server of your own, built once per connection
tool(definition: ToolDefinition): HostTool
// { description; input?: zod schema; output?: zod schema; title?; annotations?; run(args, ctx: ToolContext) }
// run returns a string, a CallToolResult or any value (sent as JSON); a throw is an error result
// ToolContext: { sessionId; agent; account?; server; signal: AbortSignal }
server.name: string
server.tools: string[]
server.add(name: string, tool: HostTool): HostMcpServer
server.remove(name: string): boolean
```

## Antigravity's home

```ts
import { prepareAntigravityEnvironment } from "@supermux/core/environment"
import type { AntigravityEnvironmentSpec, PreparedEnvironment } from "@supermux/core/environment"

prepareAntigravityEnvironment(spec: AntigravityEnvironmentSpec): Promise<PreparedEnvironment>
// { home: the shadow HOME to build; userHome: the real HOME, read only; platform: NodeJS.Platform }
// then supermux({ stateDirectory, agents: { antigravity: { home } } })
```

## Errors

```ts
import { CoreError, UnsupportedOperation } from "@supermux/core"

error instanceof CoreError // error.code: "invalid_input", "session_busy", "request_not_found", … (API.md, "Errors")
error instanceof UnsupportedOperation // code "unsupported_operation": this agent lacks the operation
```
