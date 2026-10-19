# Events

`core.subscribe(observer)` gets every `CoreEvent`. What an agent does comes as `session.event`, whose `event` is an envelope and a body: `if (event.type === "session.event") switch (event.event.kind) { … }`.

**Envelope:** `sessionId`, `agent`, `seq` (rises by one per session), `ts` (ISO time), `turnId?`, `replay`, `origin` (`live`, or `replay` for history the agent replays on load), `native` (`{ protocol, method?, payload }`: the agent's own frame, for debugging). Any body may also carry `subagentId`: a subagent produced it.

## Body kinds

Fields with `?` may be absent.

- `turn-start` `{}`: a turn began.
- `turn-complete` `{ reason }`: the turn ended; `reason` is `ok`, `interrupt`, `error` or `stall`.
- `assistant-delta` `{ messageId, text }`: a piece of the reply, to stream.
- `assistant-message` `{ messageId, text }`: a reply message's whole text (sent also when deltas came).
- `reasoning-delta` `{ reasoningId, text }`: a piece of the agent's reasoning.
- `reasoning` `{ reasoningId, text?, redacted, summary? }`: whole reasoning; `redacted: true` when the agent gave no text.
- `tool-call` `{ callId, tool, title?, phase, category?, locations?, input?, output?, exitCode?, description? }`: a tool call, `phase` `started`, `updated`, `completed` or `failed`; `category` is `read`, `edit`, `delete`, `move`, `search`, `execute`, `fetch`, `mcp`, `web-search`, `think` or `other`.
- `command-output` `{ callId, stream, delta }`: output of a running command (`stdout`, `stderr` or `merged`).
- `file-diff` `{ callId?, path, diff, changeKind? }`: a unified diff of one file a tool call changed.
- `web-search` `{ callId, phase, query?, results? }`: a web search, `started`, `completed` or `failed`.
- `mcp-tool` `{ callId, server, tool, phase, arguments?, result? }`: an MCP tool call, `started`, `progress`, `completed` or `failed`.
- `plan` `{ entries, explanation? }`: the agent's plan; each entry `{ content, status, priority? }`, `status` `pending`, `in_progress` or `completed`.
- `task` `{ taskId, taskKind, phase, label?, parentCallId? }`: background work (a shell, workflow or monitor), `phase` `started`, `interacted`, `interrupted`, `completed`, `failed` or `wake`.
- `subagent` `{ subagentId, phase, name?, description?, prompt?, activity?, stats?, result?, messaging?, endedBy?, canMessage?, canStop?, cannotMessageReason?, cannotStopReason?, … }`: a subagent's life, `phase` `started`, `progress`, `completed`, `failed`, `cancelled` or `resumed`; `canMessage` / `canStop` say what `session.messageSubagent` / `stopSubagent` can do now.
- `user-question` `{ requestId, blocking, questions }`: the agent asks the user; each question `{ id, prompt, header?, multiSelect, allowFreeText, options: [{ id, label, description? }] }`. Pending (answer it) when `blocking`.
- `permission-request` `{ requestId, toolCall, options, detail? }`: approve a tool call; `toolCall` `{ callId, tool, title, input?, category? }`, each option `{ id, kind, label }` with `kind` `allow_once`, `allow_always`, `reject_once` or `reject_always`; `detail` `{ command?, cwd?, blockedPath? }`.
- `request-resolved` `{ requestId, outcome, answer? }`: a request ended, `answered` or `cancelled` (the type also lists `expired`; nothing emits it today); `answer` is what was chosen.
- `permission-auto` `{ toolCall, optionId }`: a request the session's ACP policy answered itself (`auto-approve`, `read-only`, or `ask` with `askKinds` for the kinds not listed), so no `permission-request` was raised.
- `permissions-update` `{ spec, applied }`: the permissions changed, applied `now` or from the `next-turn`.
- `commands-update` `{ commands }`: the agent's slash commands, each `{ name, description? }`.
- `mode-update` `{ modeId?, model? }`: the agent's mode or model changed.
- `session-info` `{ title?, cwd?, model? }`: what the agent reports about its session.
- `usage` `{ context?, tokens?, cost?, rateLimits? }`: `context` `{ used, size }`, `tokens` `{ input, output, total }`, `cost` `{ amount, currency }`.
- `compaction` `{ compactionId, status, summary?, error? }`: the agent compacting its context, `in_progress`, `completed`, `failed` or `cancelled`.
- `warning` `{ message, source? }`: something to show; the turn goes on.
- `error` `{ message, errorType?, recoverable? }`: an error the agent reported.

## Core events

- `session.event` `{ sessionId, event }`: the above.
- `session.update` `{ sessionId, update }`: the agent's raw frame, beside each `session.event` and for frames that have no body.
- `session.created`, `session.resumed` `{ sessionId, record }`: a session opened.
- `session.stateChanged` `{ sessionId, state }`: `idle`, `running`, `interrupting`, `closing`, `closed` or `failed`.
- `session.failed` `{ sessionId, error }`: the agent died; `core.sessions.resume(id)` launches it again.
- `message.accepted`, `message.started` `{ sessionId, messageId }`, then `message.completed` `{ sessionId, messageId, result }`: a message you sent, queued, running, done (`result` as the receipt's `completed`).
- `tool.called` `{ sessionId, server, tool, callId }`, `tool.finished` `{ …, ok, durationMs }`: a call of a host tool, without arguments or result.
- `context.degraded` `{ sessionId, dropped }`: launched without the context items the agent cannot apply (`contextPolicy: "warn"`).
- `context.updated` `{ sessionId, applied }`: what `updateContext`, or a host server's tool change, did.
- `account.switched`, `account.refreshed`, `account.exhausted`, `account.unavailable`, `account.available`: accounts (API.md, "Accounts").
