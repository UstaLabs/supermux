# supermux-core

Private, unpublished TypeScript library for owning agent session lifecycle. It starts no HTTP server and does not import broker code. Build target is **Node.js >= 22** ESM; the same compiled package also runs under Bun.

This package is still developing. Treat the capability table below as the advertised surface, not a claim that every vendor path is finished. The in-tree broker adapter is not the library contract.

## Imports

After `bun run build` (or consuming `dist/`):

```ts
import { createCore, Core, Session, CoreError, UnsupportedOperation } from "supermux-core"
import { acp } from "supermux-core/acp"
import { claude } from "supermux-core/claude"
import { codex } from "supermux-core/codex"
import { cursor } from "supermux-core/cursor"
import { grok, opencode } from "supermux-core/agents"
import { copiedCredentials, withAuth } from "supermux-core/auth"
```

Root also exports types including `ActivityNotice`, `ActivityPhase`, `CreateOptions`, `AdoptOptions`, `ResumeOptions`, `SessionConfiguration`, `DriverContext`, `CoreEvent`, `Observer`. There is no root barrel that re-exports drivers.

## TypeScript consumers

Node 22 ESM with `module` / `moduleResolution` `NodeNext`. Public `.d.ts` uses the `NodeJS` namespace (`cursorConfigRoot` / `cursorHistoryStorePath`). A consumer that typechecks this package should:

- add `@types/node` **22** as a **devDependency**
- set `"types": ["node"]` in `compilerOptions` (or pass `--types node`)

Do **not** turn on blanket `skipLibCheck` to hide that. With TypeScript 6.x (global `bunx tsc` is 6.0.3, not 5.6) plus `@types/node` 22 and explicit `types: ["node"]`, strict checking with `skipLibCheck: false` succeeds. This package’s `devDependencies` already include `@types/node` `^22.0.0`. No source API change is required.

## Host vs library

| Owner | Responsibility |
| --- | --- |
| Host | Workspaces (`cwd`), credentials, env, UI, permission decisions, deployment |
| Core | Scheduling, in-memory queues/receipts, session metadata, events |
| Driver | Native/ACP I/O, capability truth, native conversation identity |

`createCore({ stateDirectory, agents, limits, profiles?, ... })` has **no defaults**. `limits` is required: `{ interruptTimeoutMs, maxPending, outstandingActivity }` — each a **positive safe integer**. Missing `limits` or an invalid field is a `TypeError` that names the field. Permission answers go through `session.requests`, not a host callback.

## Configuration (create / adopt / resume)

Core keys are only nonempty string `model` and `reasoningEffort`. Unknown keys or a non-object → `invalid_input`. Core does **not** check vendor enums; Codex/Claude/Grok may still reject values (Grok effort other than `low|medium|high` throws a driver `TypeError` on open/configure).

Pass requested state **before** native open on create/resume. Do not rely on a later `Session.configure` if the host needs the child born with that model.

On **create** and **adopt**, empty/`{}`/omitted/undefined values are omitted on disk and omitted on `DriverContext` (factory defaults). **Resume** is different: omitting `configuration` joins an in-flight restore; `configuration: {}` is an **explicit patch** (see table).

**Adopt never opens.** Nonempty adopt `configuration` is stored only. Runtime `configure` capability is checked on a later **resume** that actually opens, not at adopt.

| Call | omitted / `undefined` | `configuration: {}` | `undefined` **values** on keys |
| --- | --- | --- | --- |
| create | defaults; omit on disk/open | same (normalized away) | key omitted |
| adopt | defaults; omit on disk | same | key omitted |
| resume | no-options; **dedupes** in-flight restore | **explicit patch**; does not join a differently configured or no-option restore (`session_busy`) | merge **clears** that key (same as `Session.configure`) |
| live idle resume + explicit patch | n/a | `Session.configure` on the live handle | same merge |

Live resume with a patch therefore requires `capabilities.configure`. Claude/ACP/Cursor/OpenCode advertise no configure: a live patched resume fails `unsupported_operation`. Create/resume **open** with nonempty config also requires the opened runtime to advertise `configure` (checked **after** `driver.open`; leftover cleanup on failure). Adopt does not.

`Session.configuration()` is **requested persisted** state (`{}` = defaults). `AgentRuntime.configuration()` is optional and driver-defined: it may be a live native view or the requested overrides the driver last applied. Do not assume it is always a live CLI snapshot.

## Lifecycle (actual)

- **create** — optional `configuration` is cloned/validated, then `driver.open` (config on context only if nonempty). Persists `SessionRecord`. **Required** `CreateOptions.id` (same `^[a-zA-Z0-9_-]{1,128}$` validation). Missing id is a `TypeError`. Receipts still mint `messageId`.
- **adopt** — metadata-only. Validates and `store.put`s an existing native id. Does **not** spawn. Native identity is checked later by **resume** with **no new-conversation fallback**.
- **get/list** — metadata only.
- **resume** — see table. Live handle if open (idle patch via `configure`); else restore **exact** saved `agentSessionId`.
- **send** — `{ messageId, completed }`. `whenBusy: "queue" | "reject"`. Optional `idempotencyKey`.
- **interrupt** — `{ pending: "discard" | "keep" }` is required (no default). Missing `pending` is a `TypeError`. Timeout → `{ status: "unconfirmed" }`. Queues are **not** durable across process crash.
- **close** (session) — `session.close({ mode: "shutdown" | "detach" })` is required. `shutdown` stops the native process; `detach` (Codex/keeper only) drops this connection and keeps the agent. Cancels local work, **keeps** metadata.
- **sessions.close(id, { mode })** — `mode` required. No spawn. Joins an in-flight **same-id** close (that join happens **before** the shutdown gate, so an already-running close can still be awaited after `core.close({ agents })` starts). New close after shutdown → `core_closed`; leftover teardown then belongs to `core.close({ agents })`. Waits already-started create/adopt/**fork**/resume (`opening` / restore), ignoring setup rejection, then live `Session.close({ mode })` or leftover cleanup. Does **not** abort a pending `driver.open`. Do not `await sessions.close(id, { mode })` from inside that same id’s `open`. Unknown **valid** id is idempotent. Invalid id → `invalid_session_id`. Failed cleanup stays owned; create/adopt/resume/forget of that id are `session_busy` until confirmed close.
- **forget** — deletes core metadata of a **closed** session. Not native erase. Leftover ownership is `session_busy`.
- **fork** — if `capabilities.fork`; idle, empty queue, no outstanding native activity. Required `{ id }` (no minted fork id). Copied-home auth **disables fork**.
- **detach** — `close({ mode: "detach" })` on keeper-backed runtimes (Codex). Other drivers throw `unsupported_operation` before side effects. The old `Session.detach()` method remains unsupported.
- **configure / history** — only if advertised. Configure/fork require idle + empty queue + no outstanding activity.

`core.close({ agents: "shutdown" | "detach" })` waits in-flight operations (including adopt writes), then remaining leftovers with that mode, then releases the store/lock. `{ agents: "detach" }` does not kill keeper-backed agents.

## Events

`core.subscribe` delivers live **microtask** notifications (`queueMicrotask`), not a durable log.

Owned send: `message.accepted` → (drain may wait outstanding native activity) → `message.started` → `message.completed`. `message.started` is emitted in drain **synchronously before** `runtime.prompt`, after `session.stateChanged` to `running`. Observers run on a microtask, so prompt **may already be running**. Not emitted for cancelled queued work, idempotent resend of the same key, or native `onActivity`. Native activity is independent of receipts.

## Native activity (`ActivityNotice`)

`{ id: string; phase: "started" | "completed" }`. Empty id / bad phase **silently dropped**. Duplicate `started` for the same id: no-op. Unknown `completed`: no-op. Max **256** distinct outstanding ids; overflow fails the session with `activity_overflow`. Not a `CoreEvent`. While outstanding: `whenBusy: "reject"` send is `session_busy`; configure/fork are `session_busy`; drain does not start owned prompts; interrupt waits activity gates; idle can become `running` from activity alone. `DriverContext.onActivity` is optional; core always passes a callback. Drivers that never call `context.onActivity?.(...)` keep owned-prompt lifecycle only.

Call `onActivity` only for work the native runtime started **outside** an owned `prompt`. Emitting started/completed inside `prompt` after drain has already passed the activity gate does not demonstrate independent native work.

## Auth, env, secrets

Env merge (ACP/Claude/Codex/Cursor/Grok): process env unless `inheritEnv: false`, then driver `env`, then **profile `env` last**.

Records store the **profile name**, never env or tokens. Reopening a core requires the host to pass profiles again.

`copiedCredentials` + `withAuth` copies a credential file into a per-session home. Copied homes **cannot fork** (`fork: false`). `withAuth` forwards configure/history. Native token expiry is **agent-owned**: the library copies/promotes nonempty JSON objects and propagates driver errors; it does not inspect TTL or open a login UI. Real-agent examples in this package do **not** copy credentials.

## Requests

Permission prompts are first-class events. There is no `onPermission` host callback.

When an agent needs approval, Session mints a `requestId`, stores a pending request, and emits `session.event` with `kind: "permission-request"` (`toolCall`, `options` with `allow_once` / `allow_always` / `reject_once` / `reject_always`, optional `detail`). Answer with:

```ts
await session.requests.respond(requestId, { optionId: "allow_once", message?: string })
```

Blocking questions (Claude `AskUserQuestion`, Grok `_x.ai/ask_user_question`) emit `kind: "user-question"` and sit in the same pending store (`kind: "question"`). Answer with `{ answers: { [questionId]: optionId | optionId[] | freeText } }` or `{ decline: true }`. Option ids are mapped to labels before the driver sees them.

`session.requests.list()` returns pending items. Unknown id → `request_not_found`. Bad `optionId` / wrong answer kind / unknown question id → `invalid_input`. Interrupt, close, or the driver's AbortSignal resolves the driver as cancelled and emits `request-resolved` with `outcome: "cancelled"`. Pending request count is capped by `limits.maxPending` (overflow is cancelled immediately plus a warning event). `snapshot().pendingRequests` is that count.

Codex questions on `agentMessage` are non-blocking: the turn already ended. They are not listed; `respond` returns `request_not_found` (`non-blocking question: answer with session.send`). Send the next user message instead.

A request parked by the keeper while detached is redelivered on re-attach, flows through `requestPermission` again, and appears in `list()` as a new event.

Claude/Codex ask only when `permissionPrompts: "host"`. Otherwise they deny. Duplicate JSON-RPC/`request_id` values are answered **once per matching turn+fingerprint**; later/stale reuse is **deny**, not a second grant. Codex `item/permissions/requestApproval` is answered with an empty grant, not a host-invented profile. `allow_always` maps to Codex `acceptWithExecpolicyAmendment` (when offered) and Claude `updatedPermissions` from `permission_suggestions`. ACP option kinds pass through 1:1; `message` is ignored on ACP.

Grok: `noLeader` and `alwaysApprove` are **required**. A broker that wants unattended Grok must pass `noLeader: false` and `alwaysApprove: true` **explicitly**.

## Capabilities (current drivers)

| Driver | resume | steer | fork | configure | history | notes |
| --- | --- | --- | --- | --- | --- | --- |
| ACP generic | if agent supports resume/load | no | no | no | no | no synthetic transcript fork |
| Grok | via ACP | no | no | yes (restart same id) | no | opaque `_x.ai/*` native updates |
| OpenCode | via ACP | no | no | no | no | `opencode acp` |
| Claude | yes | no | no | no | no | `tools` required (`[]` or `'default'`) |
| Codex | yes | yes | yes | yes | yes | `sandbox` / `approvalPolicy` required |
| Cursor | cwd-hashed `store.db` | no | no | no | no | durable native resume is **unverified** on a live `create-chat` |

Detach is always unsupported at the session layer. Steer/fork/configure/history fail `unsupported_operation` unless the opened runtime advertises them.

Nonempty **create/resume-open** `configuration` requires the opened runtime to advertise `configure`. Passing `{ model }` on Claude/ACP/Cursor/OpenCode **create** fails **after spawn**; retry `sessions.close(id)` if leftover cleanup failed (that case rejects with an `AggregateError` whose `errors[0]` is the `unsupported_operation`). Adopt with `{ model }` does not fail until that later resume-open.

Crash leaves `.core.lock` for **manual** recovery after proving the owner is dead. There is no synthetic transcript fork.


## Try it against a real agent

`examples/console.mjs` is an interactive console over the library with no broker code: it creates or re-attaches a session, prints the normalized event stream, and lets you answer permission requests and questions, steer, interrupt, detach or shut down. Every flag is required (no defaults):

```sh
bun run build
bun examples/console.mjs --agent codex --cwd /path/to/project --state /tmp/core-state --session demo
```

Type text to send it. Commands: `/steer <text>`, `/interrupt`, `/requests`, `/allow <n> [always]`, `/reject <n> [message]`, `/answer <n> <optionId|free text>`, `/decline <n>`, `/status`, `/deltas`, `/detach`, `/quit`. Run the same command again (same `--state` and `--session`) after `/detach` or after killing the console: for Codex, Claude and Grok the agent kept running behind the keeper and you re-attach to the live turn. Agents use your own logins (`~/.codex`, `~/.claude`, `~/.grok`). Cursor needs `--model auto` on a free plan.

## Examples

`API.md` and `examples/` ship in the packed package (`files`). Run them from a project that **depends on the installed package**, or from this repository **after** `bun run build`. Do not use `NODE_PATH` for ESM.

Custom driver (no vendor account, no live model). CI runs this under Node 22 after the core build:

```sh
bun run build
node examples/custom-driver.mjs
```

The example asserts pre-open requested configuration, empty `{}` create, a live `Session.configure` apply, owned-prompt `accepted → started → completed`, and leftover recovery via `sessions.close(id)`. It does **not** emit native `onActivity` (owned-prompt only).

Opt-in live agents (caller supplies env/home; this library does **not** copy credentials, but vendor CLIs may still write under HOME). Default prompt is text-only and includes **Do not use tools.**:

```sh
bun examples/real-agents.mjs claude
```

See [API.md](./API.md) for types, errors, driver options, and Codex/Grok create-time config snippets (do not execute those against a live model from this package’s default examples).

```sh
bun run typecheck
bun run build
bun test tests
```
