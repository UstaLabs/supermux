# supermux chat

A chat app for the coding agent CLIs on your machine (Claude Code, Codex, Grok, OpenCode, Cursor), built on `@supermux/core` alone.
Two files: `server.ts` (an HTTP server, under 400 lines) and `index.html` (the page, no framework, no build step): copy them and make it yours.

## Run it

In this repository (Node 22.18 or later runs `server.ts` as it is):

```sh
bun install
bun run build:core
node packages/supermux-core/examples/chat/server.ts
# listening on http://127.0.0.1:4321  ← open it
```

On its own:

```sh
npx degit UstaLabs/supermux/packages/supermux-core/examples/chat my-chat
cd my-chat && npm install && node server.ts
```

Each agent you chat with is its own CLI, signed in as you are in a terminal. Without `--agents` the server offers every one it finds on `PATH`.

## Flags

| Flag | Default | |
| --- | --- | --- |
| `--port <n>` | `4321` | It listens on 127.0.0.1 only, answers its own page only (the Host and Origin are checked), and no other site may frame the page. |
| `--state <dir>` | `.supermux-chat` | Sessions, transcripts and titles. One server per directory. |
| `--agents <a,b>` | the CLIs on `PATH` | Any of `claude`, `codex`, `grok`, `opencode`, `cursor`. |
| `--model <agent>=<model>` | the CLI's own | Once per agent, e.g. `--model claude=opus --model codex=gpt-5`. |

## API

Every route takes and returns JSON; an error is `{ error, code }` with a 4xx or 5xx status.

| Route | |
| --- | --- |
| `GET /api/events` | Server-sent events for every session: `event` (what the agent does, a normalized event), `user`, `user-rejected`, `failed`, `state`, `message` (a message's phases: `accepted`, `started`, `completed`) and `sessions` (the list changed). |
| `GET /api/agents` | The agents offered. |
| `GET /api/sessions` | `[{ id, agent, cwd, title, state, pendingRequests }]`, newest first. |
| `POST /api/sessions` | `{ agent, cwd }` → `{ id }`. `cwd` is an absolute path. |
| `GET /api/sessions/:id/transcript?after=<line>` | The entries a transcript keeps, past `line`. |
| `POST /api/sessions/:id/messages` | `{ text }` → `{ messageId, line }`. While a turn runs it waits in a queue. 409 while a Stop is still ending, 429 when 128 wait. |
| `POST /api/sessions/:id/interrupt` | Stop. |
| `POST /api/sessions/:id/requests/:requestId` | Answers a permission prompt, `{ optionId }`, or a question, `{ answers: { [questionId]: optionId, or optionId[] when it takes several, or text } }` or `{ decline: true }`. |
| `POST /api/sessions/:id/subagents/:subagentId/stop` | Stops a subagent. |
| `POST /api/sessions/:id/subagents/:subagentId/message` | `{ text }` → `{ via }`: `direct`, or `relay` (through the main agent, with the `messageId` of that message). |
| `POST /api/sessions/:id/close` | Stops the agent. The transcript stays, and the next message starts the agent again. |

The entries a transcript keeps (all but text deltas, states and a message's phases before it completes) carry `line`: 1, 2, 3, ... A page loads the transcript, skips the live entries it has already, and after its stream drops asks for `?after=<the last line it has>`. A completed `message` names the `userLine` it answers, so a page loaded later still shows which message was cancelled or failed. The server keeps that link in memory: a message that completes after a restart has none.

## Stop, and restarts

Stop ends the running turn and cancels the messages queued behind it: each completes `cancelled`.

Stopping the server (Ctrl-C, or `kill`: SIGINT, SIGTERM) detaches it from the agents instead of stopping them: each keeps running behind its keeper, and the next start re-attaches to every session left open, a turn in progress included (so its message is not marked cancelled; the messages queued behind it are, as the queue does not outlive the server). Claude and Codex also replay a turn that finished while the server was down; ACP agents (Grok, OpenCode, Cursor) don't yet.
