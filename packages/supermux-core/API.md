# supermux-core API

Node.js **>= 22** ESM. Package is **private** (`version` `0.0.0`). Import compiled `dist/` after `tsc`.

This document describes **exported source contracts**. The in-tree broker is not this contract. Driver capabilities and unsupported operations are listed under Drivers.

### TypeScript (NodeNext)

Use `module` / `moduleResolution` `NodeNext`. Helpers such as `cursorConfigRoot` / `cursorHistoryStorePath` are typed with `NodeJS.ProcessEnv`. Add `@types/node` **22** as a **devDependency** and set `"types": ["node"]` (or `--types node`). Do **not** enable blanket `skipLibCheck` for this package. With TypeScript 6.x and those Node types, `skipLibCheck: false` typechecks the public surface. No source API change is required.

## Package exports

| Subpath | Factory / types |
| --- | --- |
| `supermux-core` | `createCore`, `Core`, `Session`, `CoreError`, `UnsupportedOperation`, public types |
| `supermux-core/acp` | `acp` |
| `supermux-core/claude` | `claude` |
| `supermux-core/codex` | `codex` |
| `supermux-core/cursor` | `cursor`, `cursorConfigRoot`, `cursorHistoryStorePath` |
| `supermux-core/agents` | `grok`, `opencode` |
| `supermux-core/auth` | `copiedCredentials`, `withAuth` |

Root public types include `ActivityNotice`, `ActivityPhase`, `CreateOptions`, `AdoptOptions`, `ResumeOptions`, `SessionConfiguration`, `DriverContext`, `CoreEvent`, `Observer`, `AgentDriver`, `AgentRuntime`.

## Core

```ts
createCore(options: CoreOptions): Core
```

`CoreOptions`: `stateDirectory` (required), `agents` (unique nonempty **ids**; the array may be empty), **`limits` (required, no defaults)**: `{ interruptTimeoutMs, maxPending, outstandingActivity }` each a **positive safe integer**. Missing `limits` or an invalid field → `TypeError` naming it. `profiles?`, `onObserverError?`. Permission answers are `session.requests.respond`, not a Core callback.

### Configuration

Only `model` and `reasoningEffort` (nonempty strings). Unknown keys / non-object → `invalid_input`. Core does not enumerate vendor effort strings; drivers may still reject (Grok: `low|medium|high` or a `TypeError` on open/configure).

Create/resume-open pass cloned requested state into `driver.open` when nonempty. On create/adopt, `{}` / omitted / undefined values → omit on disk and omit on `DriverContext`. Resume `configuration: {}` is an **explicit patch** (see table). **Adopt never opens**: nonempty adopt config is persisted only; `configure` capability is enforced on later resume-open, not at adopt.

| Call | omitted / `undefined` | `configuration: {}` | `undefined` values on keys |
| --- | --- | --- | --- |
| create | defaults | defaults (normalized away) | omitted |
| adopt | defaults | defaults | omitted |
| resume | no-options; dedupes in-flight restore | explicit patch; no join with a different restore (`session_busy`) | clears key |
| live idle resume + patch | n/a | `Session.configure` | same merge |

Nonempty create/resume-open config requires the opened runtime to advertise `configure` (**after** `driver.open`). Failure then leftover-cleans the runtime; if cleanup fails, retry `sessions.close(id)`. Live patched resume without `configure` → `unsupported_operation` (Claude/ACP/Cursor/OpenCode).

`Session.configuration()` is requested persisted state (`{}` = defaults). `runtime.configuration()` is **optional** and driver-defined (live native **or** last requested overrides). A runtime may omit the getter.

### `core.sessions`

- `create({ agent, cwd, id, authProfile?, configuration? })` → `Session`. `id` is required (`TypeError` if missing). `cwd` must be an existing absolute directory. Session id `^[a-zA-Z0-9_-]{1,128}$`.
- `adopt({ id, agent, agentSessionId, cwd, createdAt?, authProfile?, configuration? })` → `SessionRecord`. No spawn. Resume later must keep that native id.
- `get(id)`, `list({ agent? })`.
- `resume(id, { configuration? }?)` — exact native id. See table.
- `forget(id)` — metadata only; session must already be closed. Leftover ownership is `session_busy` until confirmed close.
- `close(id, { mode: "shutdown" | "detach" })` — `mode` is required (no default). No spawn/resume. Validates id (`invalid_session_id`). Joins in-flight same-id close **before** the shutdown gate (joining an already-running close still works after `core.close({ agents })` starts). A **new** close after shutdown → `core_closed`; `core.close({ agents })` finishes remaining leftovers. Waits already-started create/adopt/**fork**/resume (`opening` / restore; ignores setup rejection), then live `Session.close({ mode })` or leftover cleanup. Does **not** abort a pending custom-driver `open`. Do not `await sessions.close(id, { mode })` from inside that same id’s `driver.open`. Fire-and-forget from `open` is fine. Unknown valid id is idempotent. Failed close keeps leftover; retry `sessions.close(id, { mode })` or `core.close({ agents })`. Different ids are independent. `detach` is supported only by keeper-backed runtimes (Codex today); other drivers reject `unsupported_operation` (`detach`) before any side effect. A detached session’s record stays on disk with its native id so a later resume re-attaches.

Duplicate saved id → `session_exists`. Core closing → `core_closed` on **new** operations. Failed-open leftover blocks create/adopt/resume/forget of the same id until confirmed close.

### `core.auth`

`methods` / `login` only if the driver implements `auth`. ACP/Grok expose ACP auth discovery.

### `core.subscribe` / `core.close`

Events are live microtask notifications, not a durable log. ACP history load updates may set `replay: true`. Initialize metadata is **not** replay.

`CoreEvent` variants: `session.created` | `session.resumed` | `session.stateChanged` | `session.update` | `session.event` | `session.failed` | `message.accepted` | `message.started` | `message.completed`.

## Normalized events

`session.event` is emitted beside each `session.update` (the raw update is unchanged). Envelope: `{ sessionId, agent, seq, ts, turnId?, replay, origin: "live"|"replay", native: { protocol, method?, payload } }`. `seq` is per-session monotonic, minted by Session. `origin` follows the update `replay` flag. Turn boundaries are Session state (`running` → `turn-start`, `idle` → flush then `turn-complete`); vendor `turn_completed` is ignored.

Body kinds: `turn-start`, `turn-complete`, `assistant-delta` / `assistant-message`, `reasoning-delta` / `reasoning` (`redacted: true` when the agent provided no text), `tool-call`, `command-output`, `file-diff`, `web-search`, `mcp-tool`, `plan`, `task`, `user-question`, `permission-request`, `request-resolved`, `commands-update`, `mode-update`, `session-info`, `usage`, `compaction`, `warning`, `error`. Unknown native frames produce no `session.event`. Blocking `user-question` and `permission-request` are answerable via `session.requests`. Codex `agentMessage.questions` is non-blocking (`blocking: false`): it is not pending; answer with `session.send`.

Mappers are pure (`createCodexNormalizer`, `createAcpNormalizer({ vendor?: "grok" })`) with their own buffers; `AgentRuntime.normalize` / `flush` are optional. ACP `agent_message_chunk` / `agent_thought_chunk` flush to final messages on turn-complete.

`message.started` `{ sessionId, messageId }` fires once when an owned queued send becomes the active drain entry, **synchronously before** `runtime.prompt`, **after** `session.stateChanged` to `running`. Drain is blocked while outstanding native activity (`activity.size`); sequence is then `accepted` → wait activity complete → `started` → `completed`. Not emitted for cancelled queued work, autonomous `onActivity`, or an idempotent resend of the same key. Observer delivery is `queueMicrotask`; prompt may already be running.

`close({ agents: "shutdown" | "detach" })` is required (`agents` has no default). Aborts the core lifetime, waits operations, closes runtimes with that mode, then releases the store/lock. `{ agents: "detach" }` must not kill keeper-backed agents. Failed runtime shutdown: retry `close({ agents })`. Stale `.core.lock` is **manual** recovery.

## Session

## Requests

`session.requests.list(): PendingRequest[]`. Pending items are `kind: "permission"` (`permission-request` body) or `kind: "question"` (`user-question` body, `blocking: true`). `session.requests.respond(requestId, answer)`: permissions take `{ optionId, message? }` and resolve `{ outcome: { outcome: "selected", optionId }, message? }`; questions take `{ answers: Record<questionId, optionId | optionId[] | free text> }` (option ids are mapped to labels for the driver) or `{ decline: true }`. Wrong answer kind or unknown question id → `invalid_input`. Unknown/already-resolved id → `request_not_found`. A Codex non-blocking question id (`blocking: false`) is never listed; `respond` on it is `request_not_found` with message `non-blocking question: answer with session.send`. Driver AbortSignal / interrupt / close → cancelled + `request-resolved` `cancelled`. Cap is `limits.maxPending`; overflow is cancelled immediately plus a warning. `snapshot().pendingRequests` is the pending count.

- `id`, `snapshot()`, `capabilities()` (`detach` is true only for keeper-backed runtimes).
- `send({ content, whenBusy, idempotencyKey? })` → `Receipt`. Failures settle `completed` as `{ status: "failed", error }` rather than rejecting the receipt (invalid send still throws).
- `pending.list|cancel|clear|continue`. `continue` after `interrupt({ pending: "keep" })`.
- `interrupt({ pending })` → `stopped` | `already_idle` | `unconfirmed`. `pending: "discard" | "keep"` is required (`TypeError` if missing). No defaults.
- `close({ mode: "shutdown" | "detach" })` — `mode` is required. `shutdown` stops the native process (today’s previous close). `detach` drops this process’s connection and keeps the agent running (Codex/keeper only). Missing `mode` is a `TypeError`. `steer`, `configure`, `configuration()`, `history`, `fork({ id, at? })` (`id` required). Session.`detach()` remains unsupported as a separate method; use `close({ mode: "detach" })`.

Configure/fork require idle + empty queue + no outstanding activity. Configure merge: nonempty string `model` / `reasoningEffort` only; `undefined` values clear that key. Empty requested config means defaults.

Steer without an active owned prompt → `session_not_running`. Close/fail/interrupt on a closed or failed handle → `session_closed` / `session_failed`.

## Types (selected)

`SessionRecord` version `1`. Secrets never stored. `AuthProfile` is `{ agent, env?, methodId? }` in **caller** configuration.

`AgentUpdate`: `{ protocol: "acp" | "native", value, replay? }`.

`ActivityNotice`: `{ id: string; phase: "started" | "completed" }` (`ActivityPhase`). Core clones via `copyActivityNotice`. Empty id / bad phase silently dropped. Duplicate `started` same id: no-op. Unknown `completed`: no-op. Max **256** distinct outstanding ids (open buffer and live map). Overflow → session `fail` with `activity_overflow`. Native activity is independent of owned receipts and does **not** emit `message.started`. Not a `CoreEvent`; hosts observe busy via `snapshot().state` / interrupt / send rejection.

Drivers still receive `DriverContext.requestPermission` and `DriverContext.requestAnswers` (Session implements both). Hosts must not pass `onPermission` on `createCore`; they subscribe to `permission-request` / `user-question` events and call `session.requests.respond`. No library always-approve default.

## Errors

`CoreError` with `code`: `invalid_options`, `invalid_input`, `invalid_session_id`, `invalid_workdir`, `invalid_auth_profile`, `unknown_agent`, `session_exists`, `session_busy`, `session_not_found`, `session_closed`, `session_failed`, `session_not_running`, `queue_full`, `idempotency_conflict`, `request_not_found`, `core_closed`, `resume_identity_changed`, `fork_identity_unchanged`, `activity_overflow`, `unsupported_operation`.

`UnsupportedOperation` extends `CoreError` (`unsupported_operation`). Driver-thrown values (e.g. Grok `TypeError` on effort) are not rewritten into these codes.

Auth-helper-only codes (`auth_home_locked`, `auth_source_locked`, `auth_missing`, `auth_invalid`) apply when using `copiedCredentials`, not the session surface.

## Drivers

**ACP** `acp({ id, command, args, inheritEnv, mcpServers, keeper, setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, maxOutstandingActivity, cancelRetryIntervalMs, cancelRetryTimeoutMs, env?, classifyActivity? })`. **There are no defaults** for required fields; `acp()` throws `TypeError` naming a missing field. Optional: `env`, `classifyActivity` (absence means the field is not used). `keeper` is `{ stateDirectory, limits: { parkedDeadlineMs, journalMaxBytes, connectTimeoutMs } }`. The driver talks to the agent only through that keeper (same process-death / re-attach model as Codex). Client capabilities `{}` (no FS/terminal claims). Steer/fork/configure/history unsupported at this wrapper; `detach: true`. Cancel retries are best-effort; core may still return `unconfirmed`.

**Grok** `grok({ id, command, commandArgs, alwaysApprove, noLeader, inheritEnv, mcpServers, keeper, setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, maxOutstandingActivity, cancelRetryIntervalMs, cancelRetryTimeoutMs, env?, model?, reasoningEffort?, authPath? })`. Required fields have **no defaults**; `grok()` throws `TypeError` naming a missing field. Optional (absent = flag/field not sent): `model`, `reasoningEffort`, `authPath`, `env`. Configure: `close({ mode: 'shutdown' })` then reopen with **exact** `agentSessionId`. Steer/history/fork unsupported; detach is passed through to the child.

**OpenCode** `opencode({ id, command, inheritEnv, mcpServers, keeper, setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, maxOutstandingActivity, cancelRetryIntervalMs, cancelRetryTimeoutMs, env? })` → `opencode acp`. Required fields have no defaults. Optional: `env`. Steer/fork/configure/history unsupported at this wrapper; detach follows ACP.

**Claude** `claude({ id, command, args, keeper, inheritEnv, tools, permissionPrompts, partialMessages, setupTimeoutMs, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes, env?, model?, effort?, allowedTools?, disallowedTools?, permissionMode? })`. **There are no defaults** for those required fields; `claude()` throws `TypeError` naming a missing field. `partialMessages: true` adds `--include-partial-messages`; `false` omits it. Optional (absent = do not pass the CLI flag): `permissionMode`, `model`, `effort`, `allowedTools`, `disallowedTools`, `env`. `keeper` is `{ stateDirectory, limits: { parkedDeadlineMs, journalMaxBytes, connectTimeoutMs } }`. The driver talks to the Claude CLI only through that keeper (same process-death / re-attach model as Codex). Frames are `claude-control` (`control_request` / `control_response`), not JSON-RPC. Pass `tools: []` for no tools (`'default'` leaves the CLI tool list alone). `permissionPrompts` is `'none'` or `'host'`. Do not default `bypassPermissions`. Steer/fork/configure/history unsupported; `detach: true`.

**Codex** `codex({ id, command, args, keeper, sandbox, approvalPolicy, permissionPrompts, inheritEnv, setupTimeoutMs, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes, env?, model?, reasoningEffort?, onRuntimeRequest? })`. **There are no defaults** for those required fields; `codex()` throws `TypeError` naming a missing field. Optional (absent = not sent): `model`, `reasoningEffort`, `env`, `onRuntimeRequest`. `keeper` is `{ stateDirectory, limits: { parkedDeadlineMs, journalMaxBytes, connectTimeoutMs } }`. The driver talks to `codex app-server` only through that keeper: a process that dies can re-open the same session id, replay from `ackedSeq`, and skip a second handshake. Supports resume, steer, fork, configure, history, and `close({ mode: "detach" })`. Unknown native reset can reject. `onRuntimeRequest` is invoked once the thread id is known with a `request(method, params)` that refuses `turn/` and `thread/` methods and rejects after close so hosts can run read-only RPC such as `skills/list` without owning turns.

**Cursor** `cursor({ id, command, args, inheritEnv, sandbox, trust, force, approveMcps, setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, env?, model?, mode? })`. Required fields have **no defaults**; `cursor()` throws `TypeError` naming a missing field. Optional (absent = not sent): `mode`, `model`, `env`. Resume is intended to use a cwd-hashed `store.db`. Steer/fork/configure/history unsupported. **Durable native resume has not been verified** on a live `create-chat` path; do not assume it works.

Env precedence: inherit process (unless `inheritEnv: false`) → factory `env` → `profile.env`.

## Auth helper

`copiedCredentials({ source, homesDirectory, filename, homeVariable })` + `withAuth(driver, provider)`. Fork disabled. Failed open + failed lease release: `provider.close()`. Native expiry stays on the driver; Core does not inspect tokens. This is **opt-in**; examples that talk to real CLIs should use caller env/home instead of silent copies.

## Custom drivers

Implement `AgentDriver.open(DriverContext): AgentRuntime`. Honor `signal`, `onExit`, serialize protocol internally, settle `close` after releasing resources. Core owns records and the send queue.

If you advertise `configure`, `configure(requested)` must apply the **full** requested object (missing keys are factory defaults). If you implement `configuration()`, return a **copy** of applied state — Core may clone it, and callers must not share a mutable open-time snapshot.

Core always passes `onActivity`. Call it only if the native runtime has autonomous work **outside** an owned `prompt`: `context.onActivity?.({ id, phase })`. Core clones notices; mutating the object after return has no effect. Omit calls to keep owned-prompt lifecycle only. Overflow (257th distinct outstanding id) fails open/session with `activity_overflow`.

## Examples

Packed tarball includes `examples/` and this file. Run from a project that depends on the installed package, or from the repository after build. Do not use `NODE_PATH` for ESM. `examples/real-agents.mjs` does **not** copy credentials via `copiedCredentials`; vendor CLIs may still refresh credentials or write history under HOME. Default live prompt: `Reply with the single word pong. Do not use tools.` Do not treat these snippets as live-model tests.

Pre-open config (configure-capable driver; `{}` is omitted). Codex/Grok both advertise `configure`; do not execute against a live CLI from CI:

```js
const session = await core.sessions.create({
  agent: "codex", // or "grok"
  cwd,
  configuration: { model: "gpt-5", reasoningEffort: "low" },
})
await core.sessions.create({ agent: "codex", cwd, configuration: {} }) // same as omitted
```

Failed-start leftover: nonempty create config on a runtime **without** `configure` throws `unsupported_operation` **after** `driver.open`. If leftover `close` fails, the create/resume rejects with an **`AggregateError`** (no `code`; `errors[0]` is the open failure, the last entry is the cleanup failure), and create/resume of that id is `session_busy` until `sessions.close(id)` (or `core.close()`) confirms cleanup. See `examples/custom-driver.mjs` (no network; CI Node 22 smoke).

Subscribe owned-prompt order (started is not native activity):

```js
core.subscribe(event => {
  if (event.type === "message.accepted" || event.type === "message.started" || event.type === "message.completed") {
    console.log(event.type, event.messageId)
  }
})
```
