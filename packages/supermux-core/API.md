# @supermux/core API

Node.js **>= 22** ESM. Package `@supermux/core`; the unscoped `supermux-core` is an alias that re-exports it. Import compiled `dist/` after `tsc`.

This document describes **exported source contracts**. The in-tree broker is not this contract. Driver capabilities and unsupported operations are listed under Drivers.

### TypeScript (NodeNext)

Use `module` / `moduleResolution` `NodeNext`. Add `@types/node` **22** as a **devDependency** and set `"types": ["node"]` (or `--types node`). Do **not** enable blanket `skipLibCheck` for this package. With TypeScript 6.x and those Node types, `skipLibCheck: false` typechecks the public surface. No source API change is required.

## Package exports

| Subpath | Factory / types |
| --- | --- |
| `@supermux/core` | `createCore`, `supermux`, `defaults`, `Core`, `Session`, `CoreError`, `UnsupportedOperation`, `createHost`, `createHostProvider`, `fileVault`, `memoryVault`, `connectKeeper`, `detachedKeeperHooks`, `CORE_SUBCOMMANDS`, `requireCloseMode`, `requireAgentsCloseMode`, public types |
| `@supermux/core/acp` | `acp` |
| `@supermux/core/claude` | `claude` |
| `@supermux/core/antigravity` | `antigravity` |
| `@supermux/core/codex` | `codex` |
| `@supermux/core/cursor` | `cursor` (re-export of `@supermux/core/agents`) |
| `@supermux/core/agents` | `grok`, `opencode`, `cursor` |
| `@supermux/core/auth` | `copiedCredentials`, `withAuth` (**deprecated**, see Accounts) |
| `@supermux/core/accounts` | `fileVault`, `memoryVault`, `AccountRegistry`, `ensureHome`, `claudeLayout`, `codexLayout`, adapters, `pickAccount`, `score`, usage parsers, `RefreshCoordinator`, `refreshCodexToken`, `UsageStore`, login parsers + `defaultLoginRunner` |
| `@supermux/core/environment` | `prepareGrokEnvironment`, `prepareCodexEnvironment`, `prepareOpenCodeEnvironment`, `prepareCursorEnvironment`, `prepareAntigravityEnvironment`, credential helpers |
| `@supermux/core/mcp` | `mcpServer`, `tool`, `HostMcpServer`, `isHostMcpServer`, `toCallToolResult`, `bridgeEntry`, `socketPaths`, `BRIDGE_ENV` (see "Host MCP servers") |
| `@supermux/core/mcp-bridge` | the bridge script itself (`dist/mcp/bridge.js`, no dependencies); the core runs it for you |

Root public types include `ActivityNotice`, `ActivityPhase`, `CreateOptions`, `AdoptOptions`, `ResumeOptions`, `SessionContextUpdate`, `SessionConfiguration`, `DriverContext`, `CoreEvent`, `Observer`, `AgentDriver`, `AgentRuntime`, `SubagentSnapshot`, `SubagentDelivery`, `SubagentMessageDelivery`, `Host`, `HostHandle`, `HostRegistration`, `StopKeeperLimits`, and what `connectKeeper` takes and returns (`ConnectKeeperOptions`, `KeeperSpec`, `FrameShape`, `KeeperLimits`, `KeeperConnection`, `KeeperFrameEvent`, `KeeperWelcome`). Those keeper types, and `connectKeeper` with them, are `@experimental` (marked so in their JSDoc): they may change in any release before 1.0, a patch release included.

Three root exports for hosts that embed the core and for custom drivers:

- `CORE_SUBCOMMANDS` is `{ keeper: "__core-keeper", bridge: "__core-bridge" }`, for a host that ships as one `bun build --compile` binary. Such a binary has no `keeper.js` or `bridge.js` on disk, so the core starts those two helper processes as `<binary> <subcommand>`; the binary's entry point must run the keeper or the bridge module when its first argument is one of these.
- `requireCloseMode(options)` returns `options.mode` when it is `"shutdown"` or `"detach"` and throws a `TypeError` otherwise (`close mode is required`). It is the check every built-in runtime's `close` makes, for a custom driver's `close(options)` to make too.
- `requireAgentsCloseMode(options)` does the same for `options.agents` (`core close agents mode is required`): the check `core.close`, `host.close` and `HostProvider.close` make, for code that wraps one of them.

## `supermux()` and `defaults`

`supermux(options)` is `createCore()` with every missing field filled in from `defaults`, an exported, deeply frozen object. `defaults` holds every default except Antigravity's `userHome`, which falls back to `os.homedir()` when it is not given. `createCore()` and the agent functions (`claude()`, `codex()` and the rest) stay strict: their required options have no defaults, and a missing one fails with an error naming it. None of the permission defaults is an auto-approve setting. Claude, Codex, Grok, OpenCode and Cursor use their CLI's own asking mode, so a request they need answered reaches you as a `permission-request` event. Antigravity has no prompt channel: in its `default` mode agy itself denies the actions its mode does not allow (a shell command, for example), and the core emits a `warning` event naming them when the turn ends.

```ts
import { homedir } from "node:os"
import { join } from "node:path"
import { supermux, defaults } from "@supermux/core"

const core = supermux({
  stateDirectory: join(homedir(), ".my-app"), // required; also holds each session's keeper
  agents: { claude: {}, codex: { sandbox: "read-only" } },
  limits: { interruptTimeoutMs: 5_000 },  // optional, merged over defaults.core.limits
})
console.log(defaults.codex)               // every value supermux() fills in for Codex
```

Keep `stateDirectory` outside every folder an agent can write (its working directory, `/tmp`, `$TMPDIR`): see REFERENCE.md, "Where the state directory lives". A session `cwd` that is the state directory or is inside it is always refused (`invalid_workdir`); `supermux()` also refuses one that contains it (`refuseWorkdirsContainingState`, `true` in `defaults.core`; pass `false` to allow it).

| Agent key | Permission input (default) | Required input |
| --- | --- | --- |
| `claude` | `permissionMode` (`default`), `permissionPrompts` (`host`) | none |
| `codex` | `sandbox` (`workspace-write`), `approvalPolicy` (`on-request`) | none |
| `grok` | `policy` (`ask`), `nativeMode` (`null`), `askKinds` (unset) | none |
| `opencode` | `policy` (`ask`), `nativeMode` (`null`), `askKinds` (unset) | none |
| `cursor` | `policy` (`ask`), `mode` (`agent`), `askKinds` (unset) | none |
| `antigravity` | `mode` (`default`) | `home`: a shadow HOME prepared with `prepareAntigravityEnvironment`; `userHome` defaults to `os.homedir()` |

Agents are named by key; a key left out is not available, and `{}` takes every default. A value you pass replaces the default, arrays included: Codex's `args` replaces `["app-server"]`, so keep `"app-server"` first for the real CLI. An option the agent's input does not list is refused (`supermux: agents.<name>: unknown option <key>`), typos included, and so is an unknown name in `limits`. `id`, `permissions` and Cursor's `nativeMode` are refused with a pointer to the input to use instead. `keeper` takes `{ parkedDeadlineMs, journalMaxBytes, connectTimeoutMs }` and merges one level over the agent's defaults; the keeper itself lives in the state directory, at `<stateDirectory>/keepers/<sessionId>` (its socket too, unless that path is longer than a unix socket path may be, 107 bytes on Linux and 103 on macOS: then the socket is a short path in a private per-user 0700 directory under the OS temp dir (else `/tmp`, else `$XDG_RUNTIME_DIR`, last because it is deleted at logout without lingering), recorded in the keeper's `status.json`; a recorded socket is only used when it is a socket of this user's in such a directory). `undefined` means not given, so the default applies; `null` is a value and is passed on to the driver. `refuseWorkdirsContainingState` defaults to `defaults.core.refuseWorkdirsContainingState` (`true`). Every other `CoreOptions` field (`profiles`, `accounts`, `onObserverError`, `context`, `contextPolicy`, `mcpServers`) passes through to `createCore()`. A relative `stateDirectory`, `home` or `userHome` is resolved against the process's working directory. For a driver of your own, or an option `supermux()` does not accept (such as Codex's `onRuntimeRequest`), call `createCore()` with driver instances. The option types `SupermuxOptions`, `SupermuxAgents`, `ClaudeInput`, `CodexInput`, `GrokInput`, `OpenCodeInput`, `CursorInput` and `AntigravityInput` are exported from the package root.

## Core

```ts
createCore(options: CoreOptions): Core
```

`CoreOptions`: `stateDirectory` (required), `agents` (unique nonempty **ids**; the array may be empty), **`limits` (required, no defaults)**: `{ interruptTimeoutMs, maxPending, outstandingActivity }` each a **positive safe integer**. Missing `limits` or an invalid field → `TypeError` naming it. `profiles?`, `accounts?` (see Accounts), `onObserverError?`. `refuseWorkdirsContainingState?` (a boolean; absent: not checked; `supermux()` turns it on): also refuse a session `cwd` that contains the state directory (`invalid_workdir`), as one that is it or is inside it always is; leave it off only when agents must work in a folder above the state directory (a home directory, say), knowing they can write there. Permission answers are `session.requests.respond`, not a Core callback.

### Configuration

Only `model`, `reasoningEffort` and `serviceTier` (nonempty strings). `serviceTier` is Codex's Fast tier id: Grok and Antigravity accept it and ignore it, and a driver without `configure` (Claude, ACP, Cursor, OpenCode) refuses it like any nonempty configuration (see below). Unknown keys / non-object → `invalid_input`. Core does not enumerate vendor effort strings; drivers may still reject (Grok: `low|medium|high` or a `TypeError` on open/configure).

Create/resume-open pass cloned requested state into `driver.open` when nonempty. On create/adopt, `{}` / omitted / undefined values → omit on disk and omit on `DriverContext`. Resume `configuration: {}` is an **explicit patch** (see table). **Adopt never opens**: nonempty adopt config is persisted only; `configure` capability is enforced on later resume-open, not at adopt.

| Call | omitted / `undefined` | `configuration: {}` | `undefined` values on keys |
| --- | --- | --- | --- |
| create | defaults | defaults (normalized away) | omitted |
| adopt | defaults | defaults | omitted |
| resume | no-options; dedupes in-flight restore | explicit patch; no join with a different restore (`session_busy`) | clears key |
| live idle resume + patch | n/a | `Session.configure` | same merge |

Nonempty create/resume-open config requires the opened runtime to advertise `configure` (**after** `driver.open`). Failure then leftover-cleans the runtime; if cleanup fails, retry `sessions.close(id, { mode: "shutdown" })`. Live patched resume without `configure` → `unsupported_operation` (Claude/ACP/Cursor/OpenCode).

`Session.configuration()` is requested persisted state (`{}` = defaults). `runtime.configuration()` is **optional** and driver-defined (live native **or** last requested overrides). A runtime may omit the getter.

### `core.sessions`

- `create({ agent, cwd, id, authProfile?, account?, configuration?, permissions?, context?, contextPolicy? })` → `Session`. `permissions` (a `PermissionsSpec`) is the session's own: validated before anything launches (`invalid_input` with the validator's message), given to the driver ahead of the factory's spec and stored in the record. `context` / `contextPolicy`: see Session context. `account` xor `authProfile`. `id` is required (`TypeError` if missing). `cwd` must be an existing absolute directory that is not the state directory and is not inside it, nor, with `refuseWorkdirsContainingState` (on under `supermux()`), contains it, real paths compared (`invalid_workdir`; adopt, resume and fork check it too). Session id `^[a-zA-Z0-9_-]{1,128}$`.
- `adopt({ id, agent, agentSessionId, cwd, createdAt?, authProfile?, configuration? })` → `SessionRecord`. No spawn. Resume later must keep that native id.
- `get(id)`, `list({ agent? })`.
- `live(id)` → the open `Session` for `id` right now, or `undefined` (synchronous, no lifecycle work). After `account.switched` / `account.refreshed` / a context or host-tool reload it is the reopened Session, so a host holding the old object can pick up the new one.
- `reopening(id)` → `true` while the core itself is replacing the session's agent process (a context or host-tool reload, an account limit switch, a token refresh): the old `Session` is closing or closed and `live(id)` will return the new one. Lets a host tell a reload from an agent that died.
- `resume(id, { configuration?, account?, context?, adoptInstructions?, permissions? }?)` — exact native id. See table. `permissions` replaces the session's spec from this launch on: validated like `create`'s (`invalid_input`), given to the driver ahead of the record's spec and stored in the record, so a setting only a launch can apply (Codex's process-wide sandbox/approval policy) is right from the start; on a live session it is `session.setPermissions` instead. An agent still running behind a keeper keeps the spec it has (its live spec wins at re-attach): call `session.setPermissions` after the resume when it must change. `account` switches accounts (see Accounts). `context` (no `instructions`: fixed at creation) replaces the session's own skills, plugins and MCP servers (see Session context). `adoptInstructions` (string or strings): only for a record created before instructions were snapshotted (no `createdInstructions` and no own `context`, e.g. sessions a host made before it passed contexts): they become the session's instructions on this launch, snapshotted exactly as if given at creation, and are fixed from then on; ignored for every other record and when the driver cannot apply instructions.
- `updateContext(id, patch, options?)` → `{ applied, effective }` — see "Changing context in flight". On a session that is not open it only updates the record (the next launch applies it); on an open one it is `session.updateContext`.
- `forget(id)` — metadata only (not native erase): the record, and through `AgentDriver.forgetDetached(id)` what the driver keeps for the session (every built-in driver: its keeper directory; Antigravity also its `<state>/antigravity/<id>.json` and attachments; every registered driver when the record cannot be read or names an agent this core does not register). The session must already be closed: `session_busy` while it is open, and while its agent still runs behind its keeper (detached; `close(id, { mode: "shutdown" })` first), deleting nothing. Leftover ownership is `session_busy` until confirmed close.
- `close(id, { mode: "shutdown" | "detach" })` — `mode` is required (no default). No spawn/resume. Validates id (`invalid_session_id`). Joins in-flight same-id close **before** the shutdown gate (joining an already-running close still works after `core.close({ agents })` starts). A **new** close after shutdown → `core_closed`; `core.close({ agents })` finishes remaining leftovers. Waits already-started create/adopt/**fork**/resume (`opening` / restore; ignores setup rejection), then live `Session.close({ mode })` or leftover cleanup. With `shutdown` it then stops an agent still running for the session with no Session open on it (a detached session never resumed, e.g. after a host restart) through `AgentDriver.shutdownDetached(id)`: every built-in driver stops the session's keeper and its agent, launching nothing (no-op when none runs). Only a keeper whose socket takes a connection is signalled (its recorded pid may belong to another process by now): one whose socket does not answer fails the close with `keeper_unconfirmed`, also after the close stopped an open Session's agent, whose error then says that the agent was stopped but its keeper could not be confirmed gone, and to retry the close (or stop that keeper process); its `cause` is the keeper's own refusal. A record that cannot be read, or names an agent this core does not register, is handled by every registered driver; a core that cannot open its state directory (another core holds it) stops nothing, as before. `detach` on a session that is not open is a no-op. Does **not** abort a pending custom-driver `open`. Do not `await sessions.close(id, { mode })` from inside that same id’s `driver.open`. Fire-and-forget from `open` is fine. Unknown valid id is idempotent. Failed close keeps leftover; retry `sessions.close(id, { mode })` or `core.close({ agents })`. Different ids are independent. `detach` needs a runtime that advertises it: every built-in driver does, through its keeper, and a custom driver that does not rejects `unsupported_operation` (`detach`) before any side effect. A detached session’s record stays on disk with its native id so a later resume re-attaches.

Duplicate saved id → `session_exists`. Core closing → `core_closed` on **new** operations. Failed-open leftover blocks create/adopt/resume/forget of the same id until confirmed close.

### `core.auth`

`methods` / `login` only if the driver implements `auth`. ACP/Grok expose ACP auth discovery.

### `core.subscribe` / `core.close`

Events are live microtask notifications, not a durable log. ACP history load updates may set `replay: true`. Initialize metadata is **not** replay.

`CoreEvent` variants: `session.created` | `session.resumed` | `session.stateChanged` | `session.update` | `session.event` | `session.failed` | `message.accepted` | `message.started` | `message.completed` | `account.switched` | `account.exhausted` | `account.refreshed` | `account.unavailable` | `account.available` | `context.degraded` | `context.updated` | `tool.called` | `tool.finished` (host MCP server calls, see "Host MCP servers").

## Normalized events

`session.event` is emitted beside each `session.update` (the raw update is unchanged). Envelope: `{ sessionId, agent, seq, ts, turnId?, replay, origin: "live"|"replay", native: { protocol, method?, payload } }`. `seq` is per-session monotonic, minted by Session. `origin` follows the update `replay` flag. `native.protocol` names the producing runtime (`AgentRuntime.nativeProtocol`): `claude-stream-json` (Claude), `codex-app-server` (Codex), `acp` (Grok/OpenCode/Cursor, including their vendor `_x.ai/…` frames), `antigravity-stream-json` (Antigravity), `core` for driver-synthesized `supermux/…` frames and Core's own bodies. Turn boundaries are Session state (`running` → `turn-start`, `idle` → flush then `turn-complete`); vendor `turn_completed` is ignored.

Body kinds: `turn-start`, `turn-complete`, `assistant-delta` / `assistant-message`, `reasoning-delta` / `reasoning` (`redacted: true` when the agent provided no text), `tool-call`, `command-output`, `file-diff`, `web-search`, `mcp-tool`, `plan`, `task`, `subagent`, `user-question`, `permission-request`, `request-resolved`, `permission-auto` (`{ toolCall, optionId }`: the session's ACP policy answered a request itself, so no `permission-request` was raised), `permissions-update` (`{ spec, applied }`: `session.setPermissions` changed the permissions, `applied` is `now` or `next-turn`), `commands-update`, `mode-update`, `session-info`, `usage`, `compaction`, `warning`, `error`. Unknown native frames produce no `session.event`. A `user-question` the driver asked the host and a `permission-request` are answerable via `session.requests`: Claude `AskUserQuestion`, Grok `_x.ai/ask_user_question` and Codex's inline `agentMessage.questions`, whose answer `respond()` steers into the running turn. A Codex `item/tool/requestUserInput` request is refused by the driver, so its `user-question` event is never pending.

`tool-call` carries card-ready fields (optional, additive): `input` (tool arguments: command/cwd/path/query/file changes/MCP args), `output` (aggregated command output, tool/MCP result text), `exitCode`, `description` (agent “why” when provided), `category` (`execute|edit|read|search|fetch|mcp|web-search|other|…`). Claude Code's Edit and Write, and the Codex and ACP mappers, also emit `file-diff` `{ callId, path, diff, changeKind? }` per changed file (a unified diff; a new file from Claude Code's Write, `changeKind: "add"`, is its content) and `command-output` deltas keyed by `callId`. Activity cards for Core agents are built from these fields only (not `native`).

**Subagents.** Every body may carry `subagentId`: set on bodies produced by a subagent (its tool calls, command output, text, reasoning, requests); main-thread bodies leave it unset and main-thread messages never contain subagent text. The `subagent` body `{ subagentId, phase, parentCallId?, name?, description?, prompt?, background?, activity?, stats?, result?, model?, messaging?, nativeId? }` has exactly one `started`, `progress` when activity/stats change, one terminal (`completed` | `failed` | `cancelled`) per run, and `resumed` (same id) when a finished subagent runs again. Ids: Claude `task_id`; Codex child `threadId`; Grok child session id; Cursor `subagentSessionId`/agent id (a Task resume's `<id>.2` session is `nativeId`); OpenCode the `task` call id (child session in `nativeId`, known once the task ends). `messaging`: `relay` (Claude SendMessage, Cursor Task resume), `direct` (Codex v1 child thread, Grok/OpenCode child session), `none` (Codex `multi_agent_v2`, which refuses direct input with -32600). `task` stays for shell/workflow/monitor/background-bash only. OpenCode shows no live child activity over ACP (only the finished task).

**Truthful subagent actions.** Every `subagent` body also carries what a client may do right now: `canMessage`, `canStop`, `actionsSource` (`native`: the agent reports it; `derived`: computed from that agent's own lifecycle signals by its own rules — never a generic state machine), and when an action is off a short user-facing `cannotMessageReason` / `cannotStopReason`. A `progress` body is emitted whenever the flags change outside a lifecycle body. Terminal bodies carry `endedBy`: `self` (finished on its own), `parent` (its parent model closed/killed it: Claude `TaskStop`, Codex `close_agent`/`interrupt_agent`, Grok `kill_command_or_subagent`) or `client` (`stopSubagent`). `name` is the real name when the agent has one (Codex `agentNickname` from `thread/read`; others `subagent_type`/Cursor name). A relayed message's outcome arrives as a body with `delivery: { status: "delivered" | "refused", reason? }` (a `SubagentDelivery`; Claude's SendMessage result; a refusal also turns `canMessage` off with Claude's reason). The `delivery` that `messageSubagent` settles is a `SubagentMessageDelivery`: a `SubagentDelivery` or `unconfirmed`. Per agent (truth table 2026-10-03):

<!-- subagent-truth-table:start (generated from scripts/subagent-truth/matrix.ts; do not edit by hand) -->
| | running | finished | stopped by client | ended by parent | after resume |
|---|---|---|---|---|---|
| Claude (derived) | Message (relay, queued for its next tool round) + Stop (`stop_task`) | Message (resumes) | **no Message** ("Stopped by you — Claude can't resume it"; Claude refuses, even after a restart) | Message (resumes) | as before the restart (registry) |
| Codex (native) | Message (`turn/steer`) + Stop (child turn active) | Message (`turn/start`) | Message | Message (`thread/resume` first: close_agent unloads it) | Message (`thread/resume`) |
| Codex v2 child | no Message ("Codex doesn't accept messages for this subagent", `canAcceptDirectInput: false`) | — | — | — | — |
| Grok ≥ 1.0.46 (derived) | **no Message** ("Grok can message it once it finishes": loading a running child breaks it) + Stop (`_x.ai/subagent/cancel`) | Message (`session/load` + `session/prompt`) | Message | Message | Message (registry keeps the child session) |
| OpenCode (derived) | Message + Stop (HTTP abort) once the child session is known | Message | Message | — (no parent kill tool) | Message (registry) |
| Cursor | Message (relay, derived; queued until the parent's Task returns) | Message | — | — | Message; Stop never ("Cursor can't stop subagents": `subagent_spawned.capabilities` is `{}`) |
<!-- subagent-truth-table:end -->

Antigravity has no row: its mapper emits no `subagent` bodies.

**Feature detection.** The undocumented controls behind some cells are checked when a session opens, so a CLI update that removes one turns the flag off with a reason instead of failing at click time: **Grok** — `_x.ai/subagent/cancel` is probed with an id no subagent has (1.0.46 answers `outcome.kind: "not_found"`; it advertises nothing in `initialize`); -32601 or an unknown reply shape turns Stop off for background children ("This Grok version can't stop subagents"; a child the client prompted itself is still stopped with `session/cancel`), and a -32601 at click time does the same and re-sends the flags. **Codex** — the catalog rewrite runs only when `codex debug models` still prints `{ models: [...] }` with `multi_agent_version` "v1"/"v2"; otherwise it is skipped with a `warning` body on the first turn, and children report their real protocol through `thread/read` `canAcceptDirectInput` (v2: "Codex doesn't accept messages for this subagent"). **OpenCode** — `GET /permission` is probed when the side channel starts; a route that answers OpenCode's web app (`200 text/html`), malformed JSON, or a 404 that is not OpenCode's `NotFoundError` (startup or any later call, including Stop) turns the side channel off for good: no more polling, Stop off ("This OpenCode version can't stop subagents"; "supermux can't reach this OpenCode session's server" when it has none), one `warning` that child permission requests are no longer relayed. **Cursor** — if `initialize` does not echo `sessionCapabilities.subagents` after the `_meta.subagents` opt-in, the Task call is the subagent (no live child stream) with `messaging: "none"`: no Message ("This Cursor version doesn't report its subagents": a relay needs the agent id) and no Stop. `bun run truth:subagents` (REFERENCE.md) re-checks the whole table live.

While a client's own direct message runs on a Grok/OpenCode child, Message is off ("It is still answering your last message"). `Session.subagents()` returns the folded registry, one plain object per subagent, as `SubagentSnapshot[]` (root export): `{ subagentId: string; status: "running" | "completed" | "failed" | "cancelled"; updatedAt: number /* epoch ms */; endedBy?: "self" | "parent" | "client"; name?: string; description?: string; background?: boolean; model?: string; messaging?: "direct" | "relay" | "none"; canMessage?: boolean; canStop?: boolean; actionsSource?: "native" | "derived"; cannotMessageReason?: string; cannotStopReason?: string; nativeId?: string; spawnCallId?: string; parentCallId?: string }`, the optional fields when known. It is saved next to the session record (`<stateDirectory>/subagents/<id>.json`) and handed back to the driver on resume (`DriverContext.subagents`; the driver returns its view as `AgentRuntime.restoredSubagents` — a fresh process reports what the old one ran as `cancelled`, a keeper re-attach keeps it running), so subagents stay addressable across restarts.

A driver maps its native updates through `AgentRuntime.normalize(update)`, which returns the normalized bodies of one update (none for a frame it does not know), and `AgentRuntime.flush()`, which returns the final messages of deltas still buffered; both are optional. Each built-in driver keeps a mapper of its own, with its own buffers. ACP `agent_message_chunk` / `agent_thought_chunk` flush to final messages on turn-complete.

`message.started` `{ sessionId, messageId }` fires once when an owned queued send becomes the active drain entry, **synchronously before** `runtime.prompt`, **after** `session.stateChanged` to `running`. Drain is blocked while outstanding native activity (`activity.size`); sequence is then `accepted` → wait activity complete → `started` → `completed`. Not emitted for cancelled queued work, autonomous `onActivity`, or an idempotent resend of the same key. Observer delivery is `queueMicrotask`; prompt may already be running.

`close({ agents: "shutdown" | "detach" })` is required (`agents` has no default). Aborts the core lifetime, waits operations, closes runtimes with that mode, then releases the store/lock. `{ agents: "detach" }` must not kill keeper-backed agents. Failed runtime shutdown: retry `close({ agents })`. A `.core.lock` left by a crashed core is reclaimed by the next core when the process it records is gone or it was written before the last boot. A lock whose owner may still run, or that cannot be read, fails the core's first operation with `state_locked`; remove it by hand only after proving its owner has exited.

## Session

## Requests

`session.requests.list(): PendingRequest[]`. Pending items are `kind: "permission"` (`permission-request` body) or `kind: "question"` (`user-question` body, `blocking: true`). `session.requests.respond(requestId, answer)`: permissions take `{ optionId, message? }` and resolve `{ outcome: { outcome: "selected", optionId }, message? }`; questions take `{ answers: Record<questionId, optionId | optionId[] | free text> }` (option ids are mapped to labels for the driver) or `{ decline: true }`. Wrong answer kind or unknown question id → `invalid_input`. Unknown/already-resolved id → `request_not_found`. The `user-question` event of a Codex `item/tool/requestUserInput` request (the driver refuses that request) is never listed: `respond` on its id is `request_not_found`, with message `non-blocking question: answer with session.send` when the event says `blocking: false`. A Codex inline question is listed like any other, and `respond` steers the answer into its running turn (`{ decline: true }` steers an explicit decline); if the turn ends first it resolves `cancelled` and nothing is steered. Driver AbortSignal / interrupt / close → cancelled + `request-resolved` `cancelled`. Cap is `limits.maxPending`; overflow is cancelled immediately plus a warning. `snapshot().pendingRequests` is the pending count.

- `id`, `snapshot()`, `capabilities()` (`detach` is true only for keeper-backed runtimes). `snapshot().turnId` is the running turn's id (the `turnId` on its events) while the state is `running` or `interrupting`, and absent otherwise. It is unique within this Session object; a reopened Session counts from `turn:1` again, so compare it together with the Session. It changes in step with `state`, before observers get the turn's events: a host can tell whether the turn it saw is still the one running.
- `send({ content, whenBusy, idempotencyKey? })` → `Receipt`. Failures settle `completed` as `{ status: "failed", error }` rather than rejecting the receipt (invalid send still throws).
- `pending.list|cancel|clear|continue`. `continue` after `interrupt({ pending: "keep" })`.
- `interrupt({ pending })` → `stopped` | `already_idle` | `unconfirmed`. `pending: "discard" | "keep"` is required (`TypeError` if missing). No defaults.
- `close({ mode: "shutdown" | "detach" })` — `mode` is required. `shutdown` stops the native process (today’s previous close). `detach` drops this process’s connection and keeps the agent running (every built-in driver, through its keeper). Missing `mode` is a `TypeError`. `steer`, `configure`, `configuration()`, `history`, `fork({ id, at? })` (`id` required). Session.`detach()` remains unsupported as a separate method; use `close({ mode: "detach" })`.

Configure/fork require idle + empty queue + no outstanding activity. Configure merge: nonempty string `model` / `reasoningEffort` / `serviceTier` only; `undefined` values clear that key. Empty requested config means defaults.

`messageSubagent(subagentId, content, { whenBusy? })` → `{ via: "direct", delivery }` (delivered to the child now: Codex `turn/steer` while its turn runs, else `turn/start`; Grok/OpenCode `session/load` once then `session/prompt` on the child session, which streams back as that subagent and never drives the parent turn) or `{ via: "relay", receipt, delivery }` (Claude/Cursor: a main-thread message asking the parent model to forward it verbatim, queued through `send`). `delivery` settles (a `SubagentMessageDelivery`) `{ status: "delivered" }`, `{ status: "refused", reason }` (the agent's own refusal) or `{ status: "unconfirmed", reason }` (the relay turn ended without the parent forwarding it). `stopSubagent(subagentId)`: Claude `stop_task`, Codex `turn/interrupt` on the child's turn, Grok `_x.ai/subagent/cancel` (a background child; `session/cancel` on a child we prompted), OpenCode HTTP abort. Both throw `UnsupportedOperation` (`unsupported_operation`) when the runtime lacks them, `subagent_unavailable` (message = the agent's reason) when the subagent's current `canMessage` / `canStop` is false, `subagent_not_found` for an id the runtime does not know, and `runtime_closed` after the runtime closed. `subagents()` returns the folded registry described after the subagent truth table.

`updateContext(patch, options?)` changes the session's own context in flight (see "Changing context in flight"); after a reload the session continues in a new `Session` (`core.sessions.live(id)`).

Steer without an active owned prompt → `session_not_running`. Close/fail/interrupt on a closed or failed handle → `session_closed` / `session_failed`.

## Types (selected)

`SessionRecord` version `1`. Secrets never stored. `AuthProfile` is `{ agent, env?, methodId?, unsetEnv?, args? }` in **caller** configuration. `SessionRecord.account?` (id only; never together with `authProfile`).

`AgentUpdate`: `{ protocol: "acp" | "native", value, replay? }`.

`ActivityNotice`: `{ id: string; phase: "started" | "completed" }` (`ActivityPhase`). Core copies each notice, so changing it afterwards has no effect. Empty id / bad phase silently dropped. Duplicate `started` same id: no-op. Unknown `completed`: no-op. At most `limits.outstandingActivity` distinct outstanding ids (open buffer and live map): the caller sets it in `createCore()`, and `supermux()` defaults it to 256. One more → session `fail` with `activity_overflow`. The ACP-based drivers (Grok, OpenCode, Cursor) also count their own outstanding activity against their `maxOutstandingActivity` option (256 under `supermux()`) and fail the session the same way. Native activity is independent of owned receipts and does **not** emit `message.started`. Not a `CoreEvent`; hosts observe busy via `snapshot().state` / interrupt / send rejection.

Drivers still receive `DriverContext.requestPermission` and `DriverContext.requestAnswers` (Session implements both). Hosts must not pass `onPermission` on `createCore`; they subscribe to `permission-request` / `user-question` events and call `session.requests.respond`. No library always-approve default.

## Errors

Every `CoreError` has a `code`. These are all the codes a caller can see, from a rejected call, a receipt that settles `failed`, or a `session.failed` event.

**Input and options**

- `invalid_options`: an option of `createCore()`, `createHost()`, the accounts, a vault or an auth helper is invalid, or a path the core needs for a socket or the MCP secret is unusable. The message names it.
- `invalid_input`: an argument is wrong (an unknown configuration key, an answer of the wrong kind, an `optionId` the request does not have, a bad account or login option, and so on). The message says which.
- `invalid_session_id`: a session id is not `^[a-zA-Z0-9_-]{1,128}$`.
- `invalid_workdir`: `cwd` is not an existing absolute directory, is the state directory or inside it, or, under `supermux()` (`refuseWorkdirsContainingState`), contains it.
- `invalid_auth_profile`: the profile is not one of the core's `profiles`, or belongs to another agent.
- `unknown_agent`: no agent with that id is registered.

**Sessions**

- `session_exists`: a session with that id is already saved. A record stays after `close`; only `forget` removes it.
- `session_busy`: the session is occupied. Another lifecycle operation on its id is running, or its failed-open leftover is not closed yet; or it is not idle (`configure`, `fork`, a `whenBusy: "reject"` send, a context change); or `forget` finds it open or its agent still running behind its keeper.
- `keeper_unconfirmed`: a session's keeper did not answer on its socket either way, so the core could not tell whether it still runs and signalled nothing (its recorded pid may belong to another process by now). `sessions.close(id, { mode: "shutdown" })` rejects with it; when that close had already stopped an open session's agent, the message says so (the agent was stopped, but its keeper could not be confirmed gone) and to retry the close, and `cause` is the keeper's own refusal. A launch that has to replace a running agent started for another context (`resume` with a changed context) fails with it too. `forget` is `session_busy` instead.
- `session_not_found`: no saved session has that id.
- `session_closed`: the `Session` (or a host handle) is closed.
- `session_failed`: the `Session`'s runtime failed; close it and resume it.
- `session_not_running`: `steer` with no active turn to steer.
- `queue_full`: the session's input queue already holds `limits.maxPending` messages.
- `idempotency_conflict`: an `idempotencyKey` was used again with different input.
- `request_not_found`: no pending request has that id.
- `core_closed`: a new operation after the core began closing.
- `resume_identity_changed`: a resume came back on another native conversation than the saved one.
- `session_identity`: a driver re-attached to, or resumed, an agent whose native session is not the one the record names (ACP, Grok, Antigravity).
- `fork_identity_unchanged`: the agent reused the source conversation instead of forking.
- `activity_overflow`: more distinct outstanding native activities than the limit (see `ActivityNotice`); the session fails.
- `unsupported_operation`: the agent or the platform does not support the operation; the message says which. `UnsupportedOperation` extends `CoreError` with this code.

**State directory**

- `state_locked`: the state directory's `.core.lock` is held by a process that may still run (another core), or cannot be read. A lock left by a process that is gone is reclaimed (see `core.close`).
- `state_unsafe`: a symbolic link, or a file or folder of another user, where the core or a keeper reads or writes in the state directory: a session record or the subagent lists' folder, a session's generated context, a keeper's directory, journal, status, token or fingerprint, Antigravity's files of a session, the `.core.lock`, the MCP host's secret or socket. Nothing there is followed through a link (`accounts/` aside: see REFERENCE.md "Where the state directory lives"); the message names the path.
- `invalid_session_record`: a saved session record cannot be read or is not valid. It is never treated as empty.
- `store_closed`: the session store was used after the core released it.

**Accounts**

- `unknown_account`: no account has that id.
- `account_exists`: an account with that id exists, or the login is a second copy of a rotating login that another account or the system login already holds (refresh tokens are single-use).
- `account_expired`: a Codex token account's login expired and cannot be refreshed (it has no refresh token, or the server refused it).
- `account_secret_missing`: the account's secret is not in the vault.
- `account_home_conflict`: an account home, or an entry in it that must become a link, already exists and is not empty.
- `account_refresh_failed`: refreshing a Codex token failed for another reason than an expired login (a request or HTTP error, a reply without an access token), or another process holds the refresh lock.
- `account_identity_mismatch`: a guided login signed in as another email than the one asked for.
- `login_failed`: a guided login produced no usable login: the CLI exited with an error (the message has the exit code and the end of its output), wrote no credential file, or its identity cannot be read.
- `login_timeout`: a guided login took longer than `login.timeoutMs`.
- `login_cancelled`: a guided login was cancelled, with `cancel()` or by `core.close`.
- `invalid_account_id`: an account id is not `^[a-zA-Z0-9_-]{1,128}$`.
- `invalid_account_record`: the saved accounts file cannot be read.

**Context and host tools**

- `invalid_context`: a malformed context or patch, a path that is not an existing absolute directory, a bad or duplicate MCP server name, two different host server objects with one name, or a patch that removes what the session does not own or adds what it has.
- `context_unsupported`: policy `"error"` and the agent cannot apply an item; the message lists each one.
- `missing_mcp_servers`: a launch names a host MCP server that is not registered with this core; it is never dropped silently.
- `mcp_socket_in_use`: another live core owns this state directory's MCP socket.

**Subagents**

- `subagent_not_found`: unknown subagent id.
- `subagent_unavailable`: the agent says the action cannot work now; the message is its short reason.

**Drivers**

- `setup_timeout`: an ACP agent (also Grok, OpenCode, Cursor) did not finish starting within `setupTimeoutMs`; the open fails.
- `protocol_version`: an ACP agent answered `initialize` with another protocol version than the one the library speaks; the open fails.
- `connection_closed`: the connection to an ACP agent closed: the open fails, or the session fails if it was ready.
- `aborted`: an ACP or Grok operation was cut short because the core or the runtime was closing (a `core.auth` call in flight when the core closes, say). A session open that a close interrupts rejects `core_closed` instead.
- `auth_method`: the `methodId` of a profile or of `core.auth.login` is not an authentication method the ACP agent advertises.
- `conversation_lost`: Antigravity no longer has the conversation a resume names (its history was deleted, or it belongs to another account): a failed resume, or a failed session when it is found at a later launch.
- `runtime_closed`: a driver runtime was used after it closed.
- `busy`: an ACP-based driver (Grok's wrapper included) was given a prompt while one is already running.

**Host**

- `already_live`: `register` for an id that is already live.
- `host_closing`: the host is closing or closed.

**Auth helper only**

These four apply when using `copiedCredentials`, not the session surface.

- `auth_home_locked`: the agent home is already leased; verify its owner before recovering a stale lock.
- `auth_source_locked`: the credential source is busy; retry the auth cleanup.
- `auth_missing`: the credential file is unavailable.
- `auth_invalid`: the credential file does not hold a nonempty JSON object.

Errors a driver throws that are not `CoreError`s (Grok's `TypeError` on an effort it does not know, say) are passed on as they are, not rewritten into these codes.

## Drivers

**ACP** `acp({ id, command, args, inheritEnv, mcpServers, keeper, setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, maxOutstandingActivity, cancelRetryIntervalMs, cancelRetryTimeoutMs, captureStderr, permissions, env?, classifyActivity?, vendor?, normalizer?, sessionConfig?, contextAdapter?, openCodePollIntervalMs? })`. **There are no defaults** for required fields; `acp()` throws `TypeError` naming a missing field. `captureStderr` forwards the agent's stderr lines as native updates `{ method: "stderr", params: { line } }` so a normalizer can map logged errors. Optional (absence means the field is not used): `env`; `classifyActivity`, which maps a forwarded update to a native activity notice; `vendor`, which switches on one agent's own handling: `"grok"` (its `_x.ai/ask_user_question` requests and `_x.ai` subagent notifications), `"cursor"` (it advertises the ACP subagents extension, `clientCapabilities._meta.subagents`, and reads `cursor/task`) or `"opencode"` (its `task` tool is the subagent; this needs `openCodePollIntervalMs`); `sessionConfig`, a `Record<string, string>` of select options (OpenCode's `model`, say) that `acp()` sets with `session/set_config_option` right after `session/new`, `session/resume` or `session/load`; `contextAdapter`, how the agent applies a session context (absent: MCP servers only, unverified; `grok()`, `opencode()` and `cursor()` pass their own); `openCodePollIntervalMs`, required with `vendor: "opencode"` and ignored by other vendors: how often the HTTP side channel is polled for subagent asks while work is live; and `normalizer`, an ACP normalizer to use instead of the private one `acp()` makes (the Grok wrapper keeps one across respawns; the package exports no way to build one). `permissions` is `{ kind: "acp", policy: "auto-approve" | "ask" | "read-only", nativeMode: string | null, askKinds?: ToolKind[] }`. Live `session.setPermissions` is allowed while a turn is running (`applied: "now"`). `auto-approve` answers the first `allow_*` option and emits `permission-auto` instead of `permission-request`; `read-only` rejects edit/execute/delete/move. `nativeMode` non-null uses `session/set_mode` when advertised, else `session/set_config_option { configId: "mode" }`. `keeper` is `{ stateDirectory, limits: { parkedDeadlineMs, journalMaxBytes, connectTimeoutMs } }`. The driver talks to the agent only through that keeper (same process-death / re-attach model as Codex). Client capabilities `{}` (no FS/terminal claims; `vendor: "cursor"` adds `_meta: { subagents: true }`). Steer/fork/configure/history unsupported at this wrapper; `detach: true`. Cancel retries are best-effort; core may still return `unconfirmed`.

**Grok** `grok({ id, command, commandArgs, permissions, noLeader, inheritEnv, mcpServers, keeper, setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, maxOutstandingActivity, cancelRetryIntervalMs, cancelRetryTimeoutMs, env?, model?, reasoningEffort?, authPath? })`. Required fields have **no defaults**; `grok()` throws `TypeError` naming a missing field. Optional (absent = flag/field not sent): `model`, `reasoningEffort`, `authPath`, `env`. Grok never passes `--always-approve`; policy is the ACP `permissions` spec. Configure: `close({ mode: 'shutdown' })` then reopen with **exact** `agentSessionId`. Steer/history/fork unsupported; detach is passed through to the child.

**OpenCode** `opencode({ id, command, inheritEnv, mcpServers, keeper, setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, maxOutstandingActivity, cancelRetryIntervalMs, cancelRetryTimeoutMs, openCodePollIntervalMs, permissions, env?, model? })` → `opencode acp`. Required fields have no defaults; `openCodePollIntervalMs` is how often the HTTP side channel is polled for subagent state. Optional: `env`, `model` (ACP `model` config option after session open). Steer/fork/configure/history unsupported at this wrapper; detach follows ACP. Provider failures arrive as normalized `error` events from stderr.

**Claude** `claude({ id, command, args, keeper, inheritEnv, tools, permissionPrompts, permissions, partialMessages, setupTimeoutMs, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes, env?, model?, effort?, fastMode?, allowedTools?, disallowedTools? })`. **There are no defaults** for those required fields; `claude()` throws `TypeError` naming a missing field. `permissions` is `{ kind: "claude", permissionMode: "bypassPermissions" | "acceptEdits" | "default" | "plan" | "auto" | "dontAsk" }` and is sent at open as `--permission-mode` (omitted when `default`). Live change uses `control_request` `{ subtype: "set_permission_mode", mode }` (`applied: "now"`). `--permission-prompt-tool stdio` stays on whenever `permissionPrompts` is `'host'`. The driver always passes `--allow-dangerously-skip-permissions`: without it a runtime `set_permission_mode` to `bypassPermissions` fails with `bypass_not_launched` (verified on Claude 2.1.291). It only makes bypass switchable at runtime; it does not enable it. Optional (absent = do not pass the CLI flag): `model`, `effort`, `fastMode`, `allowedTools`, `disallowedTools`, `env`. `fastMode: true` passes `--settings {"fastMode":true}`, which turns on Claude's Fast mode: a `--print` session counts as the Agent SDK, which needs this opt-in through the flag settings. `supermux()` accepts `fastMode` too. The launch permission mode comes only from `permissions` (there is no separate top-level `permissionMode`). `keeper` is `{ stateDirectory, limits: { parkedDeadlineMs, journalMaxBytes, connectTimeoutMs } }`. Frames are `claude-control`. Steer/fork/configure/history unsupported; `detach: true`.

**Codex** `codex({ id, command, args, keeper, sandbox, approvalPolicy, permissions, inheritEnv, setupTimeoutMs, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes, env?, model?, reasoningEffort?, onRuntimeRequest? })`. **There are no defaults** for those required fields; `codex()` throws `TypeError` naming a missing field. `permissions` is `{ kind: "codex", approvalPolicy: "never" | "on-request" | "untrusted", sandbox: "read-only" | "workspace-write" | "danger-full-access" }`. The top-level `sandbox` and `approvalPolicy` must equal `permissions.sandbox` and `permissions.approvalPolicy`; `codex()` throws `TypeError` when they differ. Live `setPermissions` is stored and sent on every following `turn/start` and `thread/resume` (`applied: "next-turn"`). The session's policy is also passed per process as `app-server -c sandbox_mode=… -c approval_policy=…` (appended after `args` and the profile's args): child threads spawned by the collab tools take their policy from the process config, and nothing is written into `CODEX_HOME` (an account can share it between sessions). After a live `setPermissions`, children spawned by the running process keep the launch policy until the next launch. There is no `permissionPrompts` option: command, file-change and MCP tool approvals go to the host's `requestPermission` (declined instead when they do not belong to the running turn). Exceptions: under `approvalPolicy: "never"` an MCP tool approval is accepted without asking the host; a free-form MCP elicitation is always declined; `item/permissions/requestApproval` is always answered with an empty grant. Optional (absent = not sent): `model`, `reasoningEffort`, `env`, `onRuntimeRequest`. Supports resume, steer, fork, configure, history, and `close({ mode: "detach" })`.

**Cursor** `cursor({ id, command, commandArgs, permissions, inheritEnv, mcpServers, keeper, setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, maxOutstandingActivity, cancelRetryIntervalMs, cancelRetryTimeoutMs, env?, model?, mode? })` → `cursor-agent acp`. Required fields have **no defaults**. `permissions` is an ACP `PermissionsSpec` (never `--force` / `--auto-review`). Optional `mode` (`agent` \| `plan` \| `ask`) is sent via ACP `session/set_config_option` after session/new or session/load; a `permissions.nativeMode` that differs (the factory's or a session's own) is applied after it, so the mode the permissions report is the one that runs. `model` is matched against the picker's values, then its names (`"auto"` → Cursor's `default[]`; a free plan accepts only Auto). Resume via `session/load`. Steer/fork/configure/history unsupported at this wrapper; `detach: true`. `captureStderr` is always on.

**Antigravity** `antigravity({ id, command, args, inheritEnv, permissions, home, setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, interruptGraceMs, keeper, env?, model? })` → one long-lived `agy --input-format stream-json --output-format stream-json` process per session, behind the keeper, for many turns. Required fields have **no defaults**; `antigravity()` throws `TypeError` naming a missing field, and `setupTimeoutMs`, `shutdownTimeoutMs`, `maxFrameBytes` and `interruptGraceMs` must be positive safe integers. `args` come before agy's own flags. `keeper` is `{ stateDirectory, limits: { parkedDeadlineMs, journalMaxBytes, connectTimeoutMs } }`; the driver talks to agy only through it, and also keeps `<keeper.stateDirectory>/antigravity/<sessionId>.json` (the conversation id and whether the instructions were sent). `home` is `{ shadow, user }` (`supermux()` takes them as the strings `home` and `userHome`): `shadow` is a shadow HOME prepared with `prepareAntigravityEnvironment` from `@supermux/core/environment` (see Environment), `user` is the real HOME it mirrors (the library only reads it, but agy itself writes its conversations into the real `~/.gemini/antigravity-cli` through the shadow's `antigravity-cli` link); agy runs with `HOME` set to `shadow`. `interruptGraceMs` is how long an interrupted agy may take to end its turn before it is shut down: an interrupt is SIGINT through the keeper, agy ends the turn and exits, and the next prompt starts it again with `--conversation <id>`. `permissions` is `{ kind: "antigravity", mode: "bypass" | "acceptEdits" | "plan" | "default" }`, sent at launch as `--dangerously-skip-permissions`, `--mode accept-edits`, `--mode plan` and no flag. A live `setPermissions` or `configure` restarts an idle process (after the running turn, when there is one), so `setPermissions` reports `applied: "next-turn"`; `configure` takes `model` only (`reasoningEffort` is ignored: agy's effort is part of the model id). There is no prompt channel, so no `permission-request` is ever emitted: agy itself denies the actions its mode does not allow, and the core emits a `warning` body (`source: "antigravity"`) naming the denied actions when the turn ends. A resume names the exact conversation (`--conversation <id>`); when agy no longer has it (its history was deleted, or it belongs to another account) that is `conversation_lost`: a failed resume, or a failed session when it is found at a later launch. A prompt takes text and images (each image is written under `<keeper.stateDirectory>/antigravity/<sessionId>/attachments` and referenced as `[Attached image: <path>]`); any other block kind, or an empty prompt, is `invalid_input`. Session instructions are sent once, as a leading `<session-instructions>` text block of the first prompt of a new conversation. Optional (absent = not sent): `model`, `env`. Frames are `antigravity-stream-json`. Steer/fork/history unsupported; `detach: true`.

Env precedence: inherit process (unless `inheritEnv: false`) → factory `env` → `profile.env`.

## Host

Process-level owner of **one** `Core` for a single agent id. Consumers register per-session env/command/args (cloned; **never** written into session records), then `start` / `resume` / `stop`. No library defaults: `limits` and every close `mode` / `agents` are required.

```ts
createHost({
  stateDirectory,
  limits,          // CoreLimits, required
  agent,           // driver id
  driver: (registered, context) => AgentDriver | Promise<AgentDriver>,
  prepare?: (registration) => Promise<void | { env?, args?, context? }>,   // after admission, before open; may return env/args the driver gets and the session context
  accounts?,       // AccountsOptions passed to the Core, e.g. one shared { registry, usage } for every agent's host
  context?,        // DriverContextSupport: what the drivers this host builds apply (as AgentDriver.context; a built-in driver carries its own: claude({ … }).context)
  contextPolicy?,  // the Core's default context policy ("error" | "warn")
  mcpServers?,     // HostMcpServer[] registered with the Core up front (createCore({ mcpServers }))
  detached?,       // { shutdownDetached?, forgetDetached? } for sessions.close(id, { mode: "shutdown" }) / forget; default: the built-in drivers' (keepers, Antigravity's files) in stateDirectory
}): Host
```

**Session context through a host (C3).** `HostOptions.context` declares the host driver's context support exactly like a createCore driver's `AgentDriver.context` (absent: none, and the host behaves as before). A registration's `context` (or the one `prepare` returns, which replaces it for this and later opens) is the session's: on a new session it is the `create` context (instructions snapshotted); on an existing record its skills / plugins / MCP servers replace the session's own (`resume(id, { context })`), and its instructions are passed as `adoptInstructions`, so a record from before instructions were snapshotted takes them once and every other record keeps its creation snapshot. An adopted native session (`nativeSessionId`, no record yet) gets the same. The driver factory never sees the context: the core applies it through `DriverContext.sessionContext`. A registration without a context launches exactly as before. The ready handle follows every core-side reopen (`session.resumed`, `account.switched`, `account.refreshed`). `HostOptions.mcpServers` registers host MCP servers with the host's Core, so a context may name them as `{ kind: "host", name }` and a stored record that names one resumes after a host restart (a host created without it fails such a resume with `missing_mcp_servers`).

**Detached sessions through a host.** The host's driver forwards `HostOptions.detached` as its `shutdownDetached` / `forgetDetached`: the host cannot ask the drivers its factory builds (one per open, from a registration). By default it does what the built-in drivers do in the host's own `stateDirectory`, where their keepers live when the factory passes that directory as `keeper.stateDirectory`: it stops the session's keeper, and forget deletes its keeper directory and Antigravity's own files of the session. Keepers elsewhere: `detached: detachedKeeperHooks(theirDirectory, limits)`.

`HostRegistration`: `{ id, env, command?, args?, extra?, account?, context? }`. `account` on a new session creates it on that account (absent, or the agent's system account: no account in the record, today's behaviour). On an existing record a different account switches it on this open (`account.switched`, reason `manual`); absent keeps the record's account. `prepare` receives the **effective** account in `registration.account` (the record's when the registration has none), so it can skip credential copies; the driver factory sees it per open in `DriverContext.account` (also after Core-internal switches). A Core-internal reopen (limit switch, token refresh) updates the ready handle's `session`. `register` throws `host_closing` or `already_live` / `session_busy` (id already `admission` | `starting` | `ready` | `recovering`). A `failed-cleanup` id (start failed and the leftover process refused to die) may be re-registered: the new handle's `start` retries that cleanup first.

`HostHandle.start({ cwd, configuration?, permissions?, nativeSessionId?, onOpened? })` / `resume({ configuration?, permissions? })` / `stop({ mode })`. `permissions` is passed to `create({ permissions })` for a new session and to `resume(id, { permissions })` for an existing or adopted one (absent: a new session launches under the spec the driver factory builds). `onOpened(session)` runs inside the start (e.g. persist the native id): its rejection is a failed start and the session is closed. Lifetime fence: a stale handle cannot start/resume after replacement; concurrent `start` calls on one handle join the same in-flight open; `stop` is idempotent after confirmed release and a **no-op on a handle that never owned the id** (it never closes a replacement's session); a failed stop leaves the id `failed-cleanup` (retry `stop` on the same handle, or re-register and `start`, which retries the cleanup first); replacement is allowed only after confirmed stop. `handle.session` is the Core `Session` once started. `host.core` is for `subscribe`.

Admission fence is built in. `failed-cleanup` is retried **before** `prepare` (credential/config/home writes). Concurrent same-id starts from different handles: exactly one wins, the other rejects `session_busy` ("already awaiting failed-start cleanup" / "already starting") without closing anything. Recovering-token semantics match the former broker session fence.

`host.close({ agents })` fences every handle, closes Core, then stops handles. Failed Core close leaves the registry for retry.

```ts
createHostProvider({ create: () => Host })
```

`get()` is lazy and once. `close({ agents })` keeps the handle on failed close for retry and forbids reopen after shutdown. `setFactoryForTests` refuses to replace a live host. `resetForTests` is test-only.

## Session context

Extra **instructions**, **skills**, **plugins** and **MCP servers** for a session, applied at launch through channels that are private to the session: nothing is written into the workdir or into the user's agent homes.

```ts
type SessionContext = {
  instructions?: string | string[]   // appended to the agent's own system prompt; entries joined with a blank line
  skills?: string[]                  // absolute folders holding <name>/SKILL.md
  plugins?: string[]                 // absolute plugin folders
  mcpServers?: (ExternalMcpServer | HostMcpServer | HostMcpServerRef)[]
  // external: { name, command, args?, env? } stdio; name ^[A-Za-z0-9_-]+$
  // host: a mcpServer() object (tools are TS functions in this process), or { kind: "host", name } for a registered one
}
createCore({ …, context?, contextPolicy?: "error" | "warn" })   // default for every session
core.sessions.create({ …, context?, contextPolicy? })
core.sessions.resume(id, { context? })   // context: Omit<SessionContext, "instructions">; replaces the session's own skills/plugins/MCP servers
session.fork({ id, at?, context? })      // inherits the parent's own context, policy and instructions; `context` (no instructions) replaces the rest
core.capabilities(agent).context         // { instructions, skills, plugins, mcpServers }: { support, note }
```

- **Merge.** Core default first, then the session's own: instructions (at creation only, see below) and lists are concatenated in that order. Duplicate MCP server names → `invalid_context`. Duplicate paths are applied once. Skills folders and plugins must be existing absolute directories (checked at every launch).
- **Persistence.** The record keeps the session's **own** context (`record.context`), its `contextPolicy` when set, and `createdInstructions`. The core default's skills, plugins and MCP servers are not stored: they are merged in again at each launch. Records without these fields load unchanged.
- **Honesty.** Before anything launches, the merged context is checked against the driver (`AgentDriver.context`). With `contextPolicy: "error"` (default) any item the agent cannot apply → `context_unsupported` and nothing launches (no process, no record, no folder). With `"warn"` the session launches without those items and the core emits `{ type: "context.degraded", sessionId, dropped: [{ kind, item, reason }] }` right after `session.created` / `session.resumed`. `"unverified"` items count as supported; `core.capabilities(agent)` lists them so a host can decide.
- **Instructions are fixed at creation, for every agent.** At create the merged instructions (core default + session) are snapshotted into `record.createdInstructions` (only when applied). Every later launch (resume, reload, keeper relaunch, OpenCode config regeneration) and every fork uses that snapshot and never re-merges, so a changed core default reaches only new sessions and never makes an existing session's resume fail. `resume(id, { context })`, `fork({ context })` and `updateContext` take no `instructions` (`invalid_context` "instructions are fixed when the session is created"). Several agents could not change them anyway: Claude stores its appended prompt with the session (`--resume` ignores a new one, verified on 2.1.289), Codex fixes `developerInstructions` at `thread/start`, Grok `_meta.rules` at `session/new`.
- **Live sessions.** `resume(id, { context })` with a context that differs from the stored one relaunches an idle session (same native id); during a turn it is `session_busy`. The same context returns the live session.
- **Files.** Generated files live in `<stateDirectory>/context/<id>/` (0700), rebuilt before each launch and deleted by `sessions.forget(id)`. No context at all (no default, none on the session) → no folder and a launch that is byte-for-byte what it was before (tested per driver). Antigravity is the exception: its `mcp_config.json` and `supermux-*` plugins go into its shadow HOME and are rewritten at every launch, also without a context (which clears what an earlier launch generated).
- **Drivers** get the merged context as `DriverContext.sessionContext: { instructions?, skills, plugins, mcpServers, directory, launch: "create" | "resume" | "fork", dropped, fingerprint }` and must skip `dropped` items. Custom drivers declare support with `AgentDriver.context: { capabilities, drops?(context), update? }`; a driver without it supports nothing. `createHost` drivers declare it with `HostOptions.context` (see Host).
- **Detached sessions.** The built-in drivers hand `fingerprint` (a digest of what the launch applies; `"none"` without a context) to the keeper (the `fingerprint` field of the `spec` that `connectKeeper` takes). A detached agent process whose recorded fingerprint differs is shut down and a new one started, never re-attached: `resume(id, { context })` after a host restart really relaunches. A keeper with no recorded fingerprint (started before C1b) is re-attached as before.

| Agent | Instructions | Skills | Plugins | MCP servers |
|---|---|---|---|---|
| Claude | `--append-system-prompt-file <ctx>/instructions.md`; a host's own `--append-system-prompt[-file]` is copied in first (Claude keeps only the last one) | one generated plugin per folder, inside the plugin folder | one `--plugin-dir <ctx>/plugins` holding a symlink per plugin | `--mcp-config <ctx>/mcp.json` (adds to a host's; no `--strict-mcp-config` added) |
| Codex | `thread/start.developerInstructions` (create only) | `skills/extraRoots/set` before `thread/start` / `thread/resume` | **mapped**: `skills/` → extraRoots, `.mcp.json` stdio servers → MCP; hooks, commands, agents and non-stdio servers are dropped | `app-server -c mcp_servers.<n>.{command,args,env}` + `default_tools_approval_mode="approve"`: per process, nothing written to `CODEX_HOME`; the session's approval policy is unchanged for every other tool |
| Grok | ACP `session/new` `_meta.rules` (create only) | one generated plugin per folder, `grok agent --plugin-dir` | `grok agent --plugin-dir <plugin>` each | ACP `mcpServers` (after the driver's own) |
| OpenCode | `OPENCODE_CONFIG=<ctx>/opencode.json` → `instructions` | same file → `skills.paths` (the global config's paths are repeated: this key replaces, it does not merge) | **mapped**: `skills/` → `skills.paths`, `.opencode/plugins/*` → `plugin`, `.mcp.json` stdio servers → ACP `mcpServers`; hooks, commands, agents dropped | ACP `mcpServers` |
| Cursor | a separate embedded-resource block in front of the first prompt's blocks (`supermux://instructions`, `text/markdown`, `annotations.audience: ["assistant"]`; sent once while the conversation has no turn: create, a `session/load` that replayed no user message, a keeper re-attach with it pending). Its echo is stripped from every `user_message_chunk` (Cursor flattens it into the replayed first message). Cursor's ACP server has no system-prompt channel (`--system-prompt` is ignored under `acp`); subagents do not get it | **unsupported** (`cursor-agent acp` reads skills only from the workspace and `$HOME/.cursor/skills` / `$HOME/.agents/skills`, which belong to the host) | **unsupported** (`cursor-agent acp` ignores `--plugin-dir`) | ACP `mcpServers` (subagents get them) |
| Antigravity | a leading `<session-instructions>` text block on the first prompt of a new conversation (agy has no system-prompt flag, and no `GEMINI.md` is written); `--conversation` keeps it | one generated `supermux-skills-N` plugin per folder, in the shadow HOME's `~/.gemini/config/plugins` | **mapped**: a generated `supermux-<name>` plugin in the same folder with the plugin's `skills/` and its `.mcp.json` stdio servers; hooks, commands and agents are dropped, and so is a plugin with neither `skills/` nor a stdio server | the shadow HOME's `~/.gemini/config/mcp_config.json`, under `supermux-<name>` (the user's own servers are kept; a name clash renames ours) |
| ACP (generic) | unsupported | unsupported | unsupported | unverified: ACP `mcpServers` |

A host's own `OPENCODE_CONFIG` is carried into the session file. A context MCP server whose name a driver's own `mcpServers` already uses is a drop. In a plugin's `.mcp.json`, `${CLAUDE_PLUGIN_ROOT}` is expanded for the mapping agents. Claude always gets the `--plugin-dir <ctx>/plugins` folder once a session has any context (empty is fine), so skills and plugins can be added live later. Live check: `bun scripts/context-live.ts [claude] [codex] [cursor] [grok] [opencode]` (scratch in `~/.cache/context-c1/`, or `CONTEXT_LIVE_DIR`; Cursor runs model Auto, `CONTEXT_LIVE_CURSOR_MODEL` overrides). Cursor proven 2026-10-05 (C0 cells: `docs/core-design/session-context.md` "Cursor cells as built").

### Changing context in flight

```ts
type ContextPatch = {                                            // no instructions: fixed at creation
  skills?: { add?: string[]; remove?: string[] }
  plugins?: { add?: string[]; remove?: string[] }
  mcpServers?: { add?: ExternalMcpServer[]; remove?: string[] }  // remove by name
}
type UpdateContextOptions = {
  reload?: "allow" | "never"       // default "allow"; "never": items that need a relaunch are unsupported
  holdOnCacheImpact?: boolean      // Claude: reload_plugins refuses a reload that would change the tool list under a cached prompt
}
session.updateContext(patch, options?) → Promise<{
  applied: Array<{ kind: "skills" | "plugins" | "mcpServers", op: "add" | "remove", item, how: "live" | "reload" | "unsupported", reason? }>
  effective: "now" | "next_turn"
}>
core.sessions.updateContext(id, patch, options?)   // same; a session that is not open only gets its record updated
core.capabilities(agent).contextUpdate             // { skills|plugins|mcpServers: { add, remove, note } }
// event: { type: "context.updated", sessionId, applied }
```

- **Validation** (`invalid_context`, nothing stored): the patch shape; an added path must exist and be new (also not in the core default); a removed path or server must be in the session's **own** context (the core default cannot be removed per session); nothing added and removed at once; MCP names unique. An `instructions` key → `invalid_context` ("instructions are fixed when the session is created"). An empty patch returns `{ applied: [], effective: "now" }`.
- **Per item:** `live` when the driver declares it and the running process confirms it (`AgentRuntime.context.live(change)`), otherwise `reload` (if `reload` is not `"never"`), otherwise `unsupported`. Under `contextPolicy` `"error"` (the session's, else the core's) anything unsupported → `context_unsupported` and **nothing** is applied or stored. Under `"warn"` the rest is applied and the unsupported items are reported (and not stored). A plugin part the agent cannot map (Codex hooks) is reported as its own unsupported row, like `context.degraded`.
- **Order:** the new own context is persisted to the record **before** anything reaches the agent (a crash never leaves the record older than what is live). A change the agent then refuses without effect (Claude `held: true`) becomes `unsupported` and is taken out of the record again.
- **Never mid-turn:** the core holds the queue (no queued input starts), waits until the session is idle, then applies. A running turn is never cancelled. `live` items are sent at that idle point (`effective: "now"` only when the call arrived while idle and every applied item was live). Any `reload` item relaunches the agent on the same conversation (the account-switch path: shutdown, then resume with the record's context); the other items of that update ride the relaunch and are reported `reload` too. Queued input moves to the relaunched session and runs after it, in order; the old `Session` object is closed, `core.sessions.live(id)` returns the new one. A live change that throws falls back to a reload (with the error in `reason`), or rethrows under `reload: "never"`. The promise settles once everything is applied.
- **Closed session:** `core.sessions.updateContext(id, …)` updates the record only; applicable items are `reload` (the next launch applies them), `effective: "next_turn"`; `reload: "never"` does not apply.
- **Concurrency:** updates of one session run one after another; during a resume/close/forget of the id → `session_busy`.

| Agent | Skills add / remove | Plugins add / remove | MCP add / remove |
|---|---|---|---|
| Claude | **live**: a `supermux-skills-N` wrapper added to / removed from `<ctx>/plugins`, then `reload_plugins` (`reload_skills` does not load a new plugin) | **live**: a symlink added to / removed from `<ctx>/plugins`, then `reload_plugins` (`hold_on_cache_impact` with `holdOnCacheImpact`) | **live**: `mcp_set_servers` with the full dynamic set; removing a server that came with the launch (`--mcp-config`) is a **reload** |
| Codex | **live**: `skills/extraRoots/set` with the full new list | **live** when the plugin maps to skills only; with `.mcp.json` servers: **reload** | **reload** (new app-server with the new `-c` args + `thread/resume`) |
| Grok | reload | reload | reload |
| OpenCode | reload | reload | reload |
| Cursor | unsupported | unsupported | **reload** (new process + `session/load`) |
| Antigravity | reload | reload | reload |
| ACP (generic) | unsupported | unsupported | reload (unverified) |

Antigravity reads its plugins and `mcp_config.json` when it starts (it has no MCP flag and no live reload), so each of its changes relaunches agy with `--conversation <id>` between turns. Live mechanisms are confirmed per process: a Claude process launched without the plugin folder (a session that had no context) reloads instead; a re-attached Claude process recorded which servers it got at launch in keeper meta. Live check: `bun scripts/context-live-update.ts [claude] [codex] [cursor] [grok] [opencode]` (scratch in `~/.cache/context-c1b/`).

## Host MCP servers

MCP servers whose tools are TypeScript functions in the host process. The core ships **no default server**; a host builds as many as it wants and attaches them like any MCP server. All MCP logic runs on the official SDK `@modelcontextprotocol/server` 2.3.0 (zod 4): one SDK `McpServer` per (session, server) connection.

```ts
import { mcpServer, tool } from "@supermux/core/mcp"
import { z } from "zod"                                  // zod 4 (the SDK's peer)

const orders = mcpServer({
  name: "orders",                                        // ^[A-Za-z0-9_-]+$, unique per session
  instructions: "Order lookup",                          // optional, MCP server instructions
  toolChanges: "reload",                                 // default; or "live-only" (see below)
  tools: {
    lookup: tool({
      description: "Look up an order",
      input: z.object({ id: z.string() }),               // validated before run; omitted: no arguments
      output: z.object({ total: z.number() }),           // optional: the result also goes out as structuredContent
      async run({ id }, ctx) { return db.orders.get(id) },
    }),
  },
})
// or any SDK server, built once per (session, connection): an SDK server serves one connection
const custom = mcpServer({ name: "custom", create: ctx => buildMyMcpServer(ctx) })
// a create server without zod: "@supermux/core/mcp" re-exports the SDK's McpServer and fromJsonSchema
//   sdk.registerTool("say", { description, inputSchema: fromJsonSchema({ type: "object", … }) }, handler)

createCore({ …, mcpServers: [orders], context: { mcpServers: [orders] } })   // registry + a default for every session
core.sessions.create({ …, context: { mcpServers: [custom, { name: "github", command: "gh-mcp" }] } })
orders.add("cancel", tool({ … }))                         // live in every attached session (below)
orders.remove("lookup")
core.mcp.register(server) / core.mcp.unregister(name) / core.mcp.get(name) / core.mcp.list() / core.mcp.socket()
core.capabilities(agent).hostToolChanges                 // "live" | "reload"
```

- **`ctx`** = `{ sessionId, agent, account?, server, signal }`. `sessionId` is the core session whose agent (or one of its subagents: they share the agent's MCP connections) made the call. For a tool, `signal` aborts when the agent cancels the call (`notifications/cancelled`) or the session is interrupted (`session.interrupt`); for `create(ctx)` it aborts when that connection closes.
- **Results:** a `string` → one text block; an MCP `CallToolResult` (`{ content: [...], isError? }`) as is; any other value → its JSON as text (plus `structuredContent` when the tool has `output`). A throw → `{ isError: true, content: [{ type: "text", text: message }] }`. Invalid arguments → an `isError` result "Input validation error: …" (the SDK's).
- **Registry and records.** `SessionContext.mcpServers` takes host server objects on create, resume, fork and `updateContext` (`mcpServers: { add: [server], remove: ["name"] }`), exactly like external ones. An object in a context is registered automatically; a second, different object with a registered name → `invalid_context` (`core.mcp.register` → `invalid_input`, `createCore({ mcpServers })` → `invalid_options`). The record stores `{ kind: "host", name }` only. Every launch (create, resume, reload, keeper relaunch) resolves the names through the registry; an unregistered one → `missing_mcp_servers` and nothing launches. After a host restart, pass the servers again (`createCore({ mcpServers })` or `core.mcp.register`) before resuming. `unregister` closes the server's open connections (their bridges answer "host unavailable" and keep retrying).
- **How it reaches the agent.** Each attached host server is one ordinary stdio MCP server under its own name (`mcp__orders__lookup` in Claude), using the C1 channels (Claude `--mcp-config`, Codex `-c mcp_servers.*` with the per-server approve, ACP `mcpServers`, Antigravity the shadow HOME's `mcp_config.json`). Its command is the bridge: `process.execPath` + [`dist/mcp/bridge.js` (or `src/mcp/bridge.ts` when the host runs the sources under Bun), `--server`, name], env `SUPERMUX_MCP_SOCKET`, `SUPERMUX_MCP_SESSION`, `SUPERMUX_MCP_TOKEN`. A host on Node gets `node …/dist/mcp/bridge.js`; on Bun, `bun …/bridge.(js|ts)`. The bridge has no dependencies and no MCP code: it pipes newline-delimited JSON-RPC between stdio and the socket after a one-line hello `{ protocol: "supermux-mcp-bridge/1", token, sessionId, server }`.
- **Socket.** One Unix socket per core, `<stateDirectory>/mcp/mcp.sock` (0600, directory 0700), opened at the first launch that has a host server. When that path exceeds sun_path (107 bytes on Linux, 103 on macOS) it is, as for a keeper, a short path in a private per-user 0700 directory, `<base>/supermux-mcp-<uid>/<sha256(stateDirectory)[:16]>.sock`, under the OS temp dir (else `/tmp`, else `$XDG_RUNTIME_DIR`, last because it is deleted at logout without lingering): the first whose directory this user can make private (its own, not a link; `invalid_options` when none can be, or every path is too long). It depends only on the state directory and the environment, so a restarted host listens where the bridges of detached sessions reconnect. `socketPaths(stateDirectory)` (from `@supermux/core/mcp`) lists those candidates, best first, and creates or changes nothing: the host listens on the first whose directory it can make private, which is the first unless that one is unusable (`invalid_options` when every one is too long), and `core.mcp.socket()` says which is in use. A stale socket file is removed on start; a live one (another core) → `mcp_socket_in_use`.
- **Token.** `HMAC-SHA256(secret, sessionId + "\0" + server)`, the secret generated once into `<stateDirectory>/mcp/secret` (0600). It is the same on every launch and after a host restart, so a detached session's bridge still authenticates. The hello is refused for a bad token (the bridge exits), an unknown server or session, or a server the session does not have (those keep retrying). It never enters the record or the keeper fingerprint.
- **Detached sessions.** The keeper fingerprint holds a host server's **name** only (plus its sorted tool names on an agent that ignores `list_changed`, see below), never the token, socket or runtime path, so a restart with the same servers re-attaches the running agent. When the host goes away the bridge keeps running and reconnects with backoff (100 ms doubling to 5 s; `SUPERMUX_MCP_RETRY_MAX_MS`). While it is disconnected every JSON-RPC **request** from the agent gets `{ code: -32000, message: "host unavailable" }` at once; notifications are dropped; calls are never queued. On reconnect the bridge replays the agent's original `initialize` (its answer is swallowed) and `notifications/initialized` before anything else, so the new host-side server is initialized.
- **Tool changes.** `server.add(name, tool)` / `server.remove(name)` (tools servers only; a `create` server changes its own SDK servers) register / remove the tool on every live connection; the SDK sends `notifications/tools/list_changed`. Claude and OpenCode re-list (`hostToolChanges: "live"`): `context.updated` with `{ kind: "tools", op, item: "<server>/<tool>", how: "live" }`. Codex, Grok, Cursor and Antigravity do not (`"reload"`): the core relaunches each affected open session at idle (the updateContext rules: never mid-turn, queue held, one relaunch for all changes made meanwhile) and emits `how: "reload"`. With `toolChanges: "live-only"` there is no relaunch (`how: "unsupported"`; the agent sees the change at its next launch).
- **Events** on the session stream: `{ type: "tool.called", sessionId, server, tool, callId }` and `{ type: "tool.finished", sessionId, server, tool, callId, ok, durationMs }` (`ok` false for an error, an `isError` result, a cancel or a lost connection). Never arguments or results: they may be secret; log them in `run()` if needed. The agent's own MCP tool events (`mcp-tool` / `tool-call` bodies) show the same calls.
- **Protocol notes.** The SDK negotiates 2025-06-18 (Codex) and 2025-11-25 (the others). Claude sends `server/discover` first; the SDK answers -32601 and Claude falls back to `initialize` (not special-cased).

Live check: `bun scripts/context-live-mcp.ts [claude] [codex] [cursor] [grok] [opencode]` (scratch in `~/.cache/context-c2/`): per agent two host servers plus one external server, a mid-session tool add, a throwing tool, a subagent's call, and a detached restart (same agent pid, a call through the reconnected bridge). Results in `docs/core-design/session-context.md` "C2 as built".

## Environment

`@supermux/core/environment` owns **mechanism**: session-private home, config.toml, credential copy/canonical path. The caller owns **content** (MCP server command/args/env, skill paths). It writes no instruction files: agent instructions travel only through the session context (`createCore({ context })` / `sessions.create({ context })`, see "Session context"). No defaults: every field on `GrokEnvironmentSpec` / `CodexEnvironmentSpec` / `OpenCodeEnvironmentSpec` / `CursorEnvironmentSpec` / `ClaudeEnvironmentSpec` / `AntigravityEnvironmentSpec` is required (`requireSpec` throws `TypeError('<field> is required')` for a missing field, including explicit-null fields `provider`, `sharedRuntime`, and `credentials.apiKey`). `sessionId` / `sessionName` are not environment-spec fields — the broker puts them into the mux-shim server env itself. `skillsPaths: []` omits the grok `[skills]` table / OpenCode `skills` object. MCP `name` must match `/^[A-Za-z0-9_-]+$/`.

```ts
prepareGrokEnvironment(spec: GrokEnvironmentSpec): Promise<PreparedEnvironment>
prepareCodexEnvironment(spec: CodexEnvironmentSpec): Promise<PreparedEnvironment>
prepareOpenCodeEnvironment(spec: OpenCodeEnvironmentSpec): Promise<PreparedEnvironment>
prepareCursorEnvironment(spec: CursorEnvironmentSpec): Promise<PreparedEnvironment>
prepareClaudeEnvironment(spec: ClaudeEnvironmentSpec): Promise<PreparedEnvironment & { args: string[] }>
prepareAntigravityEnvironment(spec: AntigravityEnvironmentSpec): Promise<PreparedEnvironment>
```

Grok writes `<home>/.grok/config.toml`, points `GROK_AUTH_PATH` at the canonical auth file (legacy private copy is promoted when newer). Codex writes `<home>/config.toml` (0600), sets `CODEX_HOME`, copies `auth.json` unless `apiKey` is set (then `OPENAI_API_KEY`). OpenCode writes `<configHome>/opencode/opencode.json` (0600), sets `XDG_CONFIG_HOME` only; credentials stay under the user's XDG_DATA_HOME (`credentials: "none"`). `provider: null` and `pluginPaths: []` omit those keys. Cursor writes `<home>/.cursor/mcp.json` (0600), copies `cli-config.json` / `agent-cli-state.json` and `auth.json` unless `apiKey` is set (then `CURSOR_API_KEY`), and links `.local/share/cursor-agent` to `sharedRuntime.source` when that field is non-null. Claude does **not** isolate HOME (auth lives in the user's `~/.claude`). `prepareClaudeEnvironment` writes session-private `<home>/mcp.json` (0600) and returns CLI `args`: `--append-system-prompt-file` for each of `systemPromptFiles`, then `--plugin-dir` / `--add-dir`, then `--strict-mcp-config` (when `strictMcp`) and `--mcp-config`. `nativeMemory: false` sets `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`. `credentials: "none"`. `PreparedEnvironment.args` is Claude-only.

Antigravity: `AntigravityEnvironmentSpec` is `{ home, userHome, platform }` (`home` is the shadow HOME to build, `userHome` the user's real HOME, which the library only reads). agy finds its config only through `$HOME`, and its shell tool inherits it, so each session runs in a shadow HOME: every top-level entry of the real HOME is symlinked in, except `.gemini`, which is a real directory. In it, `antigravity-cli` links to the user's agy cli dir (the driver points it at an account's own cli dir when the session runs on an account), `config/` is real, with a generated `mcp_config.json` (the user's own config; the driver adds the session's servers at each launch) and a `plugins/` that links the user's plugins and holds the generated `supermux-*` ones, and the other entries link to the user's. `prepareAntigravityEnvironment` returns `env: { HOME: <shadow> }` (plus `USERPROFILE` on Windows), `files: [<shadow>/.gemini/config/mcp_config.json]` and `credentials: "canonical"` when `<userHome>/.gemini/antigravity-cli/antigravity-oauth-token` exists, else `"none"`. The library itself never changes anything under the real HOME (a shadow HOME that is a symlink, the real HOME, an ancestor of it or inside the real `~/.gemini` is refused with a `TypeError`), but agy writes its conversations into the real `~/.gemini/antigravity-cli` through the shadow's `antigravity-cli` link. The spec carries no MCP or skill content: the driver writes those from the session context at every launch (see Session context).

**Account mode** (`credentials.account: true`, Codex and Cursor): the session's credential comes from an account at launch, so no `auth.json` is copied; a copy left by an earlier launch is healed back to the canonical file (`promoteIfNewer`) and removed, and `credentials` is `"account"`. Cursor still copies `cli-config.json` / `agent-cli-state.json`. `releaseSessionCredential({ sessionCopy, canonical, freshness })` (heal, then remove; a copy whose promotion failed is kept) and `refreshSessionCredential(...)` (heal, then copy the canonical file again) do the same for a host that switches accounts between launches.

Credential helpers (`promoteCredential`, `promoteIfNewer`, `jwtExpiryMs`, `readCredentialJson`, `cursorCredentialFreshness`) are exported for copy-transport agents.

## Accounts

`@supermux/core/accounts` (root re-exports `fileVault`, `memoryVault` and the account types). Several logins per agent, chosen per session. **Credentials travel by environment; homes hold history.**

`CoreOptions.accounts?`: `{ registry?, usage?, vault?, homes?: { claudeRoot?, codexRoot?, grokRoot?, cursorRoot?, antigravityRoot? }, autoSwitch?, continueAfterSwitch?, continuePrompt?, fetch?, login?: { runner?, commands?, timeoutMs? } }`. `vault` defaults to `fileVault(<stateDirectory>/accounts/vault)` (dir 0700, one 0600 file per secret, atomic write+rename, ids `^[a-zA-Z0-9_-]{1,128}$`, no encryption). `memoryVault()` is for tests. History roots default to `$CLAUDE_CONFIG_DIR` or `~/.claude`, and `$CODEX_HOME` or `~/.codex`. `grokRoot` (`$GROK_HOME` or `~/.grok`), `cursorRoot` (`$XDG_CONFIG_HOME` or `~/.config`) and `antigravityRoot` (`~/.gemini/antigravity-cli`) only locate the system logins for their identity. `autoSwitch` defaults to off, `continueAfterSwitch` to on. `fetch` (default `globalThis.fetch`) is the token-refresh transport. `autoSwitch` may be a function, read each time a limit is seen (a host toggles it at runtime; a throwing function counts as off). **Shared registry:** a process running one Core per agent passes the same `registry` (`new AccountRegistry(dir, agents, { vault?, homes?, fetch? })`, metadata under `<dir>/accounts`) and `usage` (`new UsageStore(<registry.directory>/usage.json)`) to each, so there is one source of truth and one writer per file. The registry must know every agent of the Core (`invalid_options`); its own `vault`/`homes`/`fetch` apply. `core.accounts.list()` without an agent then lists every agent's accounts; `list(agent)`, `pick`, `login` and sessions still accept only the Core's own agents.

`Account`: `{ id, agent, method: "system" | "api_key" | "token" | "subscription", label?, identity?: { email?, org?, accountId? }, isolated?, provider?, createdAt }`. Metadata lives in `<stateDirectory>/accounts/accounts.json` (atomic, 0600). Secrets live only in the vault. `SessionRecord.account` holds the id only.

`core.accounts`:
- `list(agent?)`, `get(id)`. One built-in **system** account per configured agent (`"<agent>:system"`): the CLI's own login in the real home. supermux never moves, copies or overrides it, and the CLI refreshes it in place. Its identity is read lazily (Claude `oauthAccount` from `.claude.json`, which is `~/.claude.json` for the real `~/.claude`; Codex `auth.json` `tokens.account_id` plus the `id_token` email; Grok `auth.json` entry `email`/`user_id`/`team_id`; Cursor `cursor/auth.json` access-token JWT `sub`; Antigravity `antigravity-oauth-token` `id_token` JWT `email` and `sub`).
- `add({ id?, agent, method, secret?, label?, isolated?, identity?, provider? })`. `api_key`/`token` need `secret`. `subscription` refuses one (its login stays in the account home). `id` defaults to `<agent>-<8 hex>`. Methods per agent: claude all, codex all, cursor/grok/antigravity `api_key` + `subscription`, opencode `api_key` (needs `provider`). An agent id with no adapter has only its system account. A **rotating** login (system, subscription, or a Codex token whose secret has a `refresh_token`) whose identity matches another rotating login of that agent → `account_exists`, because refresh tokens are single-use and two copies log each other out. Non-rotating tokens/keys may share an identity.
- `remove(id, { deleteHome? })`: removes the metadata, secret and persisted usage. A subscription home stays on disk because it holds that login, unless `deleteHome: true`: its top-level symlinks (the shared entries) are unlinked first, never followed, then the private files are removed. The history root is never touched. System accounts cannot be removed (`invalid_input`).
- `login(options)` → `LoginHandle` (see Guided login).
- `system(agent)`, `home(id)`: `home` creates and returns a subscription account's home, so the host can run the CLI's login in it (e.g. `CLAUDE_CONFIG_DIR=<home> claude auth login`, `CODEX_HOME=<home> codex login`).
- `pick(agent, exclude?)`, `usage(id)` (see policy).

What each method does at launch (adapter `materialize` → `profile.env` / `profile.unsetEnv` / `profile.args`):

| Agent | api_key | token | subscription |
| --- | --- | --- | --- |
| claude | `ANTHROPIC_API_KEY`, unset `CLAUDE_CODE_OAUTH_TOKEN` | `CLAUDE_CODE_OAUTH_TOKEN` (setup-token), unset `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` | `CLAUDE_CONFIG_DIR=<home>`, unset all three |
| codex | `CODEX_API_KEY` + `OPENAI_API_KEY` | vault JSON `{access_token, account_id, refresh_token?, expires_at?}` → `SUPERMUX_CODEX_ACCESS_TOKEN` + `-c model_provider=supermux_chatgpt …` (ChatGPT backend, `chatgpt-account-id` header). With a `refresh_token`, the vault refreshes it (see Token refresh); without one, expired → `account_expired` | `CODEX_HOME=<home>` |
| cursor | `CURSOR_API_KEY` | n/a | `XDG_CONFIG_HOME=<home>/xdg` |
| grok | `XAI_API_KEY` | n/a | `GROK_AUTH_PATH=<home>/auth.json` |
| opencode | `OPENCODE_AUTH_CONTENT`: the v2 `account.json` shape **plus** a legacy `{ <provider>: { type: "api", key } }` entry. opencode 1.16.2 reads both: v2 alone leaves the provider without a credential, and legacy alone is migrated into a written `account.json` | n/a | n/a |
| antigravity | `GEMINI_API_KEY`, plus `SUPERMUX_ANTIGRAVITY_CLI_DIR=<home>` (an empty private agy cli dir, so agy cannot fall back to the user's own token) | n/a | `SUPERMUX_ANTIGRAVITY_CLI_DIR=<home>`, unset `GEMINI_API_KEY` |

**Account homes** (`<stateDirectory>/accounts/homes/<agent>/<id>/`, 0700). Shared entries are symlinked to the history root (a missing root entry is created first: a dir, an empty file, or `{}` for `settings.json`). Claude shares `projects/ skills/ commands/ agents/ plugins/ history.jsonl settings.json CLAUDE.md`. `.credentials.json`, `.claude.json`, `sessions/` and `ide/` stay private. Codex shares `sessions/ archived_sessions/ thread-writer-locks/ session_index.jsonl history.jsonl`. `auth.json`, `models_cache.json` and the sqlite state stay private (verified live: app-server `thread/resume` works across homes without sharing sqlite). Antigravity has no layout here: its home is the account's agy cli dir (`antigravity-oauth-token`; an `api_key` account gets an empty one), which the driver links into the shadow HOME as `~/.gemini/antigravity-cli`, and at each launch it links the real cli dir's `conversations/` and `brain/` into it (when they exist, and only where the account's entry is missing or an empty directory), so history stays shared. An existing non-link entry is replaced only when empty, otherwise `account_home_conflict`. `isolated` accounts get no links and are never switched to. Windows: directory junctions for dirs; shared files are `unsupported_operation`, so use isolated accounts there.

**Sessions.** `create({ …, account })`: `account` and `authProfile` are mutually exclusive (`invalid_input`). With neither, the session runs on the system account exactly as before: no profile, and no `account` in the record. `resume(id, { account })` switches the account. The account must belong to the session's agent (`invalid_input`; unknown → `unknown_account`). A session with an `authProfile` cannot switch (`invalid_input`). A live session is shut down (`close({ mode: "shutdown" })`, which cancels an in-flight turn and queued input) and reopened with the **same native id** under the new account. The record is updated and `account.switched { sessionId, from, to, reason: "manual" }` is emitted. Same account → no reopen. **Detached keeper sessions:** a resume re-attaches to the parked process, which keeps its old environment. To switch, first reopen it and `close({ mode: "shutdown" })`, then resume with the account. `DriverContext.account` carries the id. `AuthProfile.unsetEnv` keys are removed after the env merge, and `AuthProfile.args` are appended to the CLI args (Codex: before the multi_agent v1 catalog step, which keeps `app-server` first). All four driver families (claude, codex, acp/grok/cursor/opencode, antigravity) honour both.

**Usage and policy** (`accounts/policy.ts`). `usage` bodies' `rateLimits` are normalised per adapter into `UsageWindow { name, usedPercent, resetsAt? }`. Claude: `rate_limit_info.unifiedWindows[*].utilization` ×100, and `status: "rejected"` marks `rateLimitType` full. Codex: `primary`/`secondary.usedPercent`, named `<windowDurationMins>m`, and `rateLimitReachedType` marks a full window. Antigravity reports no windows (its `usage` bodies carry token counts only and its adapter has no usage parser), so `pick` has no data for its accounts and no limit switch is queued for it. Unknown shapes are skipped. `pick` (Ghostex "Auto"): per account, the min over windows of `(100 − used) / max(hoursUntilReset, 1/60)`. A window without `resetsAt` counts as 1 h, and a window past its reset counts as unused. Highest wins. Limited accounts are skipped. Accounts without data rank after those with data, in list order (system first). Isolated and Cursor accounts are never picked. With `autoSwitch`, a window ≥ 100% on a session queues a switch. When the current turn has ended (state idle/failed, never mid-turn), Core resumes the session on the picked account and emits `account.switched` with `reason: "limit"`, or `account.exhausted { sessionId, agent, account }` when nothing is available. Input queued behind the limited turn runs first and may fail. Sessions on isolated or Cursor accounts are not auto-switched. After a limit switch, if the turn before it ended because of the limit (its `message.completed` was `failed`, or its stop reason names a limit or quota, or a window ≥ 100% was seen during it), Core sends **one** continuation prompt on the new account: `continuePrompt` (default `"Continue from where you stopped. Your previous turn hit a usage limit and you are now on another account."`). It is skipped when the host had input queued at switch time or the new session is busy, and never sent after a manual switch. `continueAfterSwitch: false` turns it off.

**Unavailable accounts.** A driver that sees the agent's API refuse the account itself sets `errorType: "account_unavailable"` (`ACCOUNT_UNAVAILABLE_ERROR`) on its `error` body. Claude does so for a `result` with `api_error_status` 401 or 403, e.g. `oauth_not_allowed_for_organization` after a cancelled subscription. Antigravity does so for a failed turn whose error message names an eligibility check, a missing sign-in, `unauthenticated`, `PERMISSION_DENIED` or HTTP 401 or 403. Core then marks the account unavailable (`core.accounts.unavailable(id)` → `{ reason, since }`, persisted in `usage.json`) and emits `account.unavailable { sessionId, agent, account, reason }` the first time. With `autoSwitch`, the session switches like on a limit (after the turn, `account.switched` with `reason: "unavailable"`, or `account.exhausted` with `reason: "unavailable"`), and the refused turn continues once with its own default prompt (a host's `continuePrompt` serves both). `pick` and the auto-switch skip unavailable accounts. The mark never blocks a manual choice: the next turn that completes on the account clears it (`account.available`), and so does `remove`. Nothing probes an account in the background.

Usage is persisted to `<stateDirectory>/accounts/usage.json` (0600, atomic, writes debounced by 1 s and flushed on `core.close`), so `pick` has data after a restart. Only windows with a `resetsAt` in the future are kept; a window without one could never be known to have reset. An unreadable file is ignored (a cache).

**Guided login.** `core.accounts.login({ agent, id?, label?, email?, isolated?, as?: "subscription" | "token" })` returns a `LoginHandle` synchronously: `state()` → `{ phase: "starting" | "awaiting_user" | "verifying" | "done" | "failed" | "cancelled", url?, code?, needsCode?, deadline?, error?, errorCode? }`, `on(listener)` (returns an unsubscribe), `submitCode(text)`, `cancel()`, `done: Promise<Account>`. `needsCode` is set when the CLI waits for a code passed to `submitCode` (Claude, Antigravity); `deadline` (epoch ms) is set while awaiting the user when the CLI has its own limit (Antigravity: 60 s after the URL appears). Invalid options throw at once (`unknown_agent`, `invalid_input`, `invalid_account_id`; OpenCode and agents without an adapter → `unsupported_operation`; `as: "token"` is Codex only; `email` is Claude only). The agent's own login runs in a throwaway `<stateDirectory>/accounts/pending/<uuid>/` (0700) as its working directory, in its own process group, with `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `CLAUDE_CODE_OAUTH_TOKEN`, `OPENAI_API_KEY`, `CODEX_API_KEY`, `CURSOR_API_KEY`, `XAI_API_KEY` and `GEMINI_API_KEY` removed from its env:

| Agent | Isolation | Command | User step |
| --- | --- | --- | --- |
| claude | `CLAUDE_CONFIG_DIR=<tmp>` | `claude auth login --claudeai [--email E]` in a PTY (`script`; util-linux and BSD forms) | open `url`, then `submitCode(code)`: the code and Enter are typed as two writes |
| codex | `CODEX_HOME=<tmp>` | `codex -c cli_auth_credentials_store="file" login --device-auth` | open `url`, enter `code` |
| grok | `GROK_AUTH_PATH=<tmp>/auth.json`, `GROK_HOME=HOME=<tmp>` | `grok login --device-auth` | open `url`, enter `code` |
| cursor | `XDG_CONFIG_HOME=<tmp>/xdg`, `NO_OPEN_BROWSER=1` | `cursor-agent login` (or `agent`) | open `url` |
| antigravity | `HOME=<tmp>/.login-home`, whose `.gemini/antigravity-cli` links to `<tmp>` (the token lands in the login dir and the real `~/.gemini` is never written); a no-op `xdg-open` / `open` first on `PATH` | `agy -p="Reply with the single word OK."` in a PTY (`script`) | open `url` (Google sign-in), then `submitCode(code)` before `state().deadline` |

Success means exit 0 (Antigravity also accepts a nonzero exit once its credential file exists: an account that still needs Google's browser eligibility check exits 1 after saving its token), the credential file exists (`.credentials.json`, `auth.json`, `auth.json`, `xdg/cursor/auth.json`, `antigravity-oauth-token`) and the identity is readable (Claude `.claude.json` `oauthAccount`; with `email`, a different signed-in email → `account_identity_mismatch`; Antigravity the token's `id_token` JWT). Then the rotating-login duplicate rule applies: the same identity as the system login or another account → `account_exists` naming it, and the temp dir is deleted. `as: "subscription"` (default) renames the temp dir into the account home (same filesystem) and creates the shared links (Antigravity has none: its driver shares history at launch). Anything the login run left under a shared name (e.g. a default `settings.json`) is dropped first, since it is a CLI default and not user data. For Antigravity, its `.login-home` and what its one turn left behind (`conversations`, `brain`, `annotations`, `implicit`, `conversation_summaries.db*`) are removed after verification instead, never through symlinks. An existing home dir of that id → `account_home_conflict`. `as: "token"` (Codex) moves `auth.json` `tokens` into the vault as `{ access_token, refresh_token, id_token, account_id, expires_at }` (`expires_at` from the access-token JWT `exp`) and deletes the temp dir, so the vault is the only holder of that login. The whole login times out after `login.timeoutMs` (default 30 min) → `login_timeout`. `cancel()` → `login_cancelled` (no effect once `verifying`). Other failures → `login_failed` with the exit code and the end of the CLI output. Temp dirs are always removed on failure, and `core.close` cancels running logins. `login.runner` replaces process spawning (`LoginSpawn { command, args, env, cwd, pty }` → `LoginProcess { onOutput, onExit, write, kill }`); `login.commands` overrides the CLI per agent kind. Windows has no `script`, so the Claude and Antigravity logins are `unsupported_operation` there. On macOS, Claude and Cursor may keep the login in the Keychain instead of the file this checks. Live: `bun scripts/accounts-login-live.ts <agent> [--as token] [--timeout-min N] [--cancel-at-url]`.

**Token refresh (vault-owned, Codex `token` accounts with a `refresh_token`).** `materialize` refreshes when the access token expires within 5 minutes (JWT `exp`, or `expires_at`). Session opens ask for 30 minutes, so a new session is never due right away. The refresh is a `refresh_token` grant to `https://auth.openai.com/oauth/token` with the Codex CLI client id. It runs once per account at a time: concurrent callers share one in-process refresh, and processes share `<stateDirectory>/accounts/locks/<id>.lock` (O_EXCL; stale when its pid is gone or after 2 minutes; waits up to 30 s). After taking the lock it re-reads the vault, in case another process already refreshed. The rotated secret is written to the vault **before** the new access token is used. The old refresh token is kept when the reply omits one. Failures: `refresh_token_expired` / `refresh_token_reused` / `refresh_token_invalidated` / `invalid_grant` → `account_expired`, anything else → `account_refresh_failed`. The message carries the server's error code, never a token. A live token session holds its token in the agent process, so Core schedules a reopen 30 minutes before expiry. When the session is idle with nothing queued (never mid-turn), it is resumed on the same account and native id with a fresh token, and `account.refreshed { sessionId, account }` is emitted (not `account.switched`). Never use this with a refresh token copied from `~/.codex/auth.json`: it is single-use, and refreshing it logs out the system login.

## Auth helper (deprecated)

**Deprecated:** per-session credential copies race on single-use refresh tokens. Use accounts (env injection, or one shared account home per login). The code stays for existing callers.

`copiedCredentials({ source, homesDirectory, filename, homeVariable })` + `withAuth(driver, provider)`. Fork disabled. Failed open + failed lease release: `provider.close()`. Native expiry stays on the driver; Core does not inspect tokens. This is **opt-in**; examples that talk to real CLIs should use caller env/home instead of silent copies.

## Custom drivers

Implement `AgentDriver.open(DriverContext): AgentRuntime`. Honor `signal`, `onExit`, serialize protocol internally, settle `close` after releasing resources. Core owns records and the send queue.

A driver whose agents can outlive the process (detached) may implement `shutdownDetached(sessionId): Promise<void>`: stop what still runs for that session without launching anything, resolving once it is gone and at once when nothing runs. `sessions.close(id, { mode: "shutdown" })` calls it after closing any open Session. Likewise `forgetDetached(sessionId): Promise<boolean>` deletes what the driver keeps on disk for the session, or resolves `false`, deleting nothing, while its agent still runs; `sessions.forget(id)` calls it and is then `session_busy`. A driver that runs its agent through `connectKeeper` gets both from `detachedKeeperHooks(keeperStateDirectory, limits)` (root export; `limits` is a `StopKeeperLimits`, `{ shutdownTimeoutMs, connectTimeoutMs }`).

If you advertise `configure`, `configure(requested)` must apply the **full** requested object (missing keys are factory defaults). If you implement `configuration()`, return a **copy** of applied state — Core may clone it, and callers must not share a mutable open-time snapshot.

Core always passes `onActivity`. Call it only if the native runtime has autonomous work **outside** an owned `prompt`: `context.onActivity?.({ id, phase })`. Core clones notices; mutating the object after return has no effect. Omit calls to keep owned-prompt lifecycle only. Overflow (one more distinct outstanding id than `limits.outstandingActivity`, 256 under `supermux()`'s default) fails open/session with `activity_overflow`.

## Examples

Packed tarball includes the single-file examples (`examples/*.mjs`) and this file; an app with a folder of its own (`examples/chat`) is in the repository, to be copied out. Run from a project that depends on the installed package, or from the repository after build. Do not use `NODE_PATH` for ESM. `examples/real-agents.mjs` does **not** copy credentials via `copiedCredentials`; vendor CLIs may still refresh credentials or write history under HOME. Default live prompt: `Reply with the single word pong. Do not use tools.` Do not treat these snippets as live-model tests.

Pre-open config (configure-capable driver; `{}` is omitted). Codex/Grok both advertise `configure`; do not execute against a live CLI from CI:

```js
const session = await core.sessions.create({
  id: "codex-tuned", // required: yours, unique in the state directory
  agent: "codex", // or "grok"
  cwd,
  configuration: { model: "gpt-5", reasoningEffort: "low" },
})
await core.sessions.create({ id: "codex-defaults", agent: "codex", cwd, configuration: {} }) // same as omitted
```

Failed-start leftover: nonempty create config on a runtime **without** `configure` throws `unsupported_operation` **after** `driver.open`. If leftover `close` fails, the create/resume rejects with an **`AggregateError`** (no `code`; `errors[0]` is the open failure, the last entry is the cleanup failure), and create/resume of that id is `session_busy` until `sessions.close(id, { mode: "shutdown" })` (or `core.close({ agents: "shutdown" })`) confirms cleanup. See `examples/custom-driver.mjs` (no network; CI Node 22 smoke).

Subscribe owned-prompt order (started is not native activity):

```js
core.subscribe(event => {
  if (event.type === "message.accepted" || event.type === "message.started" || event.type === "message.completed") {
    console.log(event.type, event.messageId)
  }
})
```
