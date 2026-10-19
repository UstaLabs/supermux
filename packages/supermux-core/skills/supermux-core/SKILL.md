---
name: supermux-core
description: Use when building an app, script, bot or service in TypeScript that drives Claude Code, Codex, Cursor, OpenCode, Grok Build or Antigravity through the @supermux/core library.
---

# Building with @supermux/core

`@supermux/core` runs coding-agent CLIs as sessions behind one TypeScript API: you send messages, read one normalized event stream, answer the agents' permission prompts and questions, and give them tools that are functions in your process. Each agent runs behind a small keeper process, so it can outlive your process and be re-attached.

- Exact signatures: [references/api.md](references/api.md).
- Every event kind with its fields: [references/events.md](references/events.md).

## Install

```sh
npm i @supermux/core
npm i zod     # only to write host tools (zod 4)
```

- Node.js 22 or later, or Bun; ESM only. Node 22.18+ runs a `.ts` file as it is when its TypeScript is erasable (no `enum`, no `namespace`, `import type` for types).
- The CLI of each agent you use, installed and logged in: `claude`, `codex`, `cursor-agent`, `opencode`, `grok`, `agy`. The library runs them with the user's own login and never logs in for you.
- TypeScript: `"module"` and `"moduleResolution"` set to `"NodeNext"`, `@types/node` 22.

## A first program

One turn on Claude Code and one on Codex: it prints the replies, allows each permission once and declines each question.

```ts run
import { randomUUID } from "node:crypto"
import { homedir } from "node:os"
import { join } from "node:path"
import { supermux } from "@supermux/core"

// The agents by name, each with its defaults: its CLI's own asking mode, so its prompts come here.
// The state directory holds the session records and the keepers: a folder of its own, outside the folders agents work in.
const core = supermux({ stateDirectory: join(homedir(), ".supermux-demo"), agents: { claude: {}, codex: {} } })

// One observer sees the events of every session. Nothing is stored: keep what you need.
core.subscribe(event => {
  if (event.type !== "session.event") return
  const body = event.event
  if (body.kind === "assistant-message") console.log(`${body.agent}: ${body.text}`)
  // A request waits until it is answered: never leave one pending.
  const session = core.sessions.live(event.sessionId)
  if (body.kind === "permission-request") {
    const allow = body.options.find(option => option.kind === "allow_once")
    if (allow) session?.requests.respond(body.requestId, { optionId: allow.id }).catch(console.error)
    else session?.interrupt({ pending: "discard" }).catch(console.error)
  }
  if (body.kind === "user-question" && body.blocking) {
    session?.requests.respond(body.requestId, { decline: true }).catch(console.error)
  }
})

for (const agent of ["claude", "codex"]) {
  const session = await core.sessions.create({ id: randomUUID(), agent, cwd: process.cwd() })
  const receipt = await session.send({ content: [{ type: "text", text: "List the files here." }], whenBusy: "queue" })
  const completion = await receipt.completed // status "completed", "cancelled" or "failed" (with error)
  console.log(`${agent}: ${completion.status}`)
}

// Stops every agent: without it they keep running behind their keepers after this process ends.
await core.close({ agents: "shutdown" })
// expect-stdout: ^claude: completed$
// expect-stdout: ^codex: completed$
```

## Sessions

- **Create.** `core.sessions.create({ id, agent, cwd })` launches the agent and resolves the `Session`. `id` is yours: `^[a-zA-Z0-9_-]{1,128}$`, unique within the state directory (`session_exists`). `cwd` must be an existing absolute directory, and neither the state directory, nor a folder that holds it, nor one inside it (`invalid_workdir`). Also: `context` (below), `permissions`, `account`, `configuration`.
- **Send.** `session.send({ content, whenBusy })` resolves a receipt `{ messageId, completed }` once the message is accepted. `completed` resolves when its turn ends, to `{ status: "completed", stopReason }`, `{ status: "cancelled" }` or `{ status: "failed", error }`; it never rejects. `content` is a list of blocks: `{ type: "text", text }`, or `{ type: "image", mimeType, data }` with base64 `data`.
- **Queue.** `whenBusy: "queue"` runs the message after the running turn and the messages queued before it; `"reject"` throws `session_busy` instead. `session.pending.list()`, `cancel(messageId)` and `clear()` act on the queue.
- **Interrupt.** `session.interrupt({ pending: "keep" | "discard" })` (`pending` is required) ends the running turn, which completes `cancelled`, and resolves `{ status: "stopped" | "already_idle" | "unconfirmed" }`. `"discard"` also cancels the queued messages. After any interrupt the queue is paused, for messages sent later too: call `session.pending.continue()` to run them. `continue()` throws `session_busy` while `snapshot().state` is still `"interrupting"` (after `unconfirmed`, until the turn really ends).
- **State.** `session.snapshot()` has `state` (`idle`, `running`, `interrupting`, `closing`, `closed`, `failed`), `pending`, `paused` and `pendingRequests`. `session.capabilities()` says what this agent can do (`steer`, `fork`, `configure`, `history`; Codex has them all).
- **Resume.** `core.sessions.resume(id)` gets you a running session: the open one as it is; else it re-attaches to the agent still running behind its keeper (after a `detach`, or after your process died), a turn in progress included; else it relaunches the agent on the same conversation (a closed or failed session). `core.sessions.live(id)` only returns the session open in this process right now, or `undefined`, and starts nothing.
- **Close.** `session.close({ mode })` or `core.sessions.close(id, { mode })`, `mode` required: `"shutdown"` stops the agent (`core.sessions.close(id, { mode: "shutdown" })` also stops one a detach left running), `"detach"` leaves it running behind its keeper for a later `resume`. The record stays (`core.sessions.list()`); `core.sessions.forget(id)` deletes a closed one, with its keeper's files (`session_busy` while a detached agent still runs).
- **Shut down.** `await core.close({ agents: "shutdown" | "detach" })` before your process exits: every open session closes with that mode. One core owns a state directory at a time (`state_locked`).
- **Failure.** When an agent dies its session fails: `session.failed`, state `failed`, its turn and queue complete `failed`. `resume(id)` launches it again.

## Events

`core.subscribe(event => …)` returns an unsubscribe function. Every observer gets every session's events, in order, on a microtask. Switch on `event.type`:

- `session.event`: what the agent does, in `event.event`: an envelope (`sessionId`, `agent`, `seq`, `ts`, `turnId`, `origin`, `native`) and a body with a `kind`. Handle at least:
  - `turn-start`, `turn-complete` (`reason`: `ok`, `interrupt`, `error`, `stall`).
  - `assistant-delta` (stream it) and `assistant-message` (the final text, whether or not deltas came).
  - `reasoning-delta`, `reasoning`.
  - `tool-call` (`callId`, `tool`, `title`, `phase`, `input`, `output`, `exitCode`), with the `command-output` and `file-diff` bodies of the same `callId`.
  - `plan` (`entries`, each with a `status`).
  - `subagent` (`subagentId`, `phase`, `canMessage`, `canStop`). A body with a `subagentId` comes from that subagent.
  - `permission-request`, `user-question` and `request-resolved` (next section).
  - `usage` (`context`, `tokens`, `cost`), `warning`, `error`.
- `message.accepted`, then `message.started`, then `message.completed` (with `result`): the receipts of your messages, by `messageId`.
- `session.stateChanged` (`state`), `session.failed` (`error`), `session.created`, `session.resumed`.
- `session.update`: the agent's raw frame, also sent for frames that have no normalized body.

Events are not stored: persist what you need (the chat example keeps a transcript). `origin: "replay"` marks history an agent replays when a session is loaded: do not show it twice. A receipt's `completed` can resolve before the last events of its turn arrive (an `assistant-message` built from deltas comes with `turn-complete`): wait for `turn-complete` to act on the whole turn.

## Permission requests and questions

A `permission-request` (approve a tool call) or a blocking `user-question` (the agent asks the user) stays pending in `session.requests` until you answer it. It never expires and the turn waits for ever, so a program with nobody at the keyboard must answer or refuse each one.

- **Permission.** Find the option by `kind` (`allow_once`, `allow_always`, `reject_once`, `reject_always`), never by a fixed id (ids differ per agent), and call `session.requests.respond(requestId, { optionId: option.id })`. `toolCall` (`tool`, `title`, `input`) and `detail` (`command`, `cwd`) say what is asked. A refusal may add `message`, the reason the agent is told (ACP agents ignore it).
- **Question.** `respond(requestId, { answers: { [question.id]: option.id } })`: an array of option ids when `multiSelect`, your own text when `allowFreeText`. Or `{ decline: true }`.
- A `user-question` with `blocking: false` (Codex's `requestUserInput`) is not pending: answer it with `session.send`.
- Every request ends with `request-resolved` (`outcome`: `answered`, `expired`, `cancelled`). Interrupting or closing the session cancels its pending requests.
- `session.requests.list()` lists the pending ones, e.g. for a page that reconnects. `respond` rejects with `request_not_found` for a request already resolved, `invalid_input` for an option the request does not have.
- Nothing to answer with (no option of the kind you want)? Interrupt the turn.
- To be asked less, give the agent's permission input to `supermux()`: `claude: { permissionMode: "acceptEdits" }`, `codex: { approvalPolicy: "never" }`, `grok`, `opencode` or `cursor: { policy: "auto-approve" }`; on a live session, `session.setPermissions(spec)`.
- Antigravity has no prompt channel: its `mode` decides, and the actions it denied come as a `warning` when the turn ends.

## Host tools

A host tool is a function in your process that the agent calls as an MCP tool, through a small stdio bridge the core starts for it. `mcpServer` and `tool` come from `@supermux/core/mcp`; arguments are described with zod 4.

```ts run
import { randomUUID } from "node:crypto"
import { homedir } from "node:os"
import { join } from "node:path"
import { supermux } from "@supermux/core"
import { mcpServer, tool } from "@supermux/core/mcp"
import { z } from "zod"

const orders = mcpServer({
  name: "orders", // the agent sees an MCP server of this name
  tools: {
    lookup: tool({
      description: "Look up an order by its id",
      input: z.object({ id: z.string() }),
      async run({ id }, ctx) {
        console.log(`${ctx.agent} looked up order ${id}`)
        return { id, status: "shipped" }
      },
    }),
  },
})

// Claude names the tool mcp__orders__lookup; allowedTools lets it call it without asking.
const core = supermux({ stateDirectory: join(homedir(), ".supermux-demo"), agents: { claude: { allowedTools: ["mcp__orders__lookup"] } } })
core.subscribe(event => {
  if (event.type === "session.event" && event.event.kind === "assistant-message") console.log(event.event.text)
})

const session = await core.sessions.create({
  id: randomUUID(), agent: "claude", cwd: process.cwd(),
  context: { mcpServers: [orders] },
})
const prompt = 'Call mcp__orders__lookup with {"id":"42"} and tell me the status of the order.'
const { completed } = await session.send({ content: [{ type: "text", text: prompt }], whenBusy: "queue" })
await completed
await core.close({ agents: "shutdown" })
// expect-stdout: ^claude looked up order 42$
```

- `run(args, ctx)`: `args` are checked against `input` first. `ctx` is `{ sessionId, agent, account, server, signal }`; `signal` aborts when the agent cancels the call or the session is interrupted.
- Return a string (one text block), any other value (sent as JSON, and as `structuredContent` when the tool has an `output` schema) or an MCP `CallToolResult`. A throw becomes an error result with its message.
- Give a server to one session in `context.mcpServers`, or to every session with `supermux({ context: { mcpServers: [orders] } })`. Every agent takes host servers.
- `orders.add(name, tool(…))` and `orders.remove(name)` reach every session that has the server: live on Claude and OpenCode, elsewhere by relaunching the agent between turns.
- The session record keeps a host server by name: after a restart, pass the same servers again (`supermux({ mcpServers: [orders] })`) before resuming such a session, or it fails with `missing_mcp_servers`.
- The `tool.called` and `tool.finished` (`ok`, `durationMs`) events report each call, without its arguments or result.

## Per-session context

A session can get extra instructions, skills, plugins and MCP servers, through channels private to it: nothing is written into the workdir or the user's agent configuration.

```ts
const session = await core.sessions.create({
  id, agent: "claude", cwd,
  context: {
    instructions: "Answer in French.",           // appended to the agent's system prompt; fixed at creation
    skills: ["/abs/path/skills"],                 // folders holding <name>/SKILL.md
    plugins: ["/abs/path/plugin"],
    mcpServers: [{ name: "github", command: "github-mcp-server", args: ["stdio"] }, orders],
  },
})
await session.updateContext({ skills: { add: ["/abs/path/more-skills"] }, mcpServers: { remove: ["github"] } })
```

- An item the agent cannot apply fails the `create` with `context_unsupported` (Cursor takes no skills or plugins); `contextPolicy: "warn"` launches without it and emits `context.degraded`. `core.capabilities(agent).context` says what each agent applies.
- `updateContext` changes skills, plugins and MCP servers, never instructions. It waits for the running turn to end, then applies each change live or by relaunching the agent on the same conversation; after a relaunch, `core.sessions.live(id)` is a new `Session`.
- `supermux({ context })` is merged into every session. Details: API.md, "Session context".

## Common mistakes

- Leaving a `permission-request` or a blocking `user-question` unanswered: it never expires, and the turn waits for ever.
- Matching a permission option by its id: ids differ per agent; match its `kind`.
- `core.sessions.live(id)` where you mean `resume(id)`: `live` starts nothing, and returns `undefined` for a session that is not open in this process.
- A relative `cwd`: `create` takes an existing absolute directory only (`invalid_workdir`).
- A state directory in a folder an agent can write (its `cwd`, `/tmp`, `os.tmpdir()`): the keepers write there unsandboxed, so give it a folder of its own under the home directory. `create` refuses a `cwd` that is, holds or is inside it (`invalid_workdir`); see REFERENCE.md, "Where the state directory lives".
- No `await core.close({ agents })` before exiting: the agents keep running behind their keepers.
- Sending after an interrupt and waiting: the queue stays paused until `session.pending.continue()`.
- `configuration: { model }` on a session: only an agent that advertises `configure` (Codex, Grok, Antigravity) takes it; the others fail with `unsupported_operation`. Set `model` per agent instead: `supermux({ agents: { claude: { model: "opus" } } })`.
- Expecting normalized events for the turn of an ACP agent (Grok, OpenCode, Cursor) that ended while your process was down: on re-attach it arrives as raw `session.update` events only.
- Antigravity without a prepared `home`: it runs in a shadow HOME made by `prepareAntigravityEnvironment` (from `@supermux/core/environment`), given as `supermux({ agents: { antigravity: { home } } })`.
- Native Windows: not supported (the keepers talk over Unix sockets); use WSL.

## Where to look

Paths are relative to the package: `node_modules/@supermux/core/`, or [packages/supermux-core](https://github.com/UstaLabs/supermux/tree/main/packages/supermux-core) in the repository, which also has the example apps (the npm package does not).

- [examples/chat](../../examples/chat/): a chat page for several agents: streamed replies, prompts answered in the page, Stop, detach on Ctrl-C and re-attach on the next start.
- [examples/kanban](../../examples/kanban/): a board of tasks, each worked by one agent in a git worktree of its own; a card moves by itself when its agent asks for something or is done.
- [examples/recipes](../../examples/recipes/): short programs, one task each.
- [API.md](../../API.md): the whole API, with every option, error code and per-agent table.
- [REFERENCE.md](../../REFERENCE.md): lifecycle rules, limits and what each agent can do.
