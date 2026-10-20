# supermux chat

A chat app for the coding agent CLIs on your machine (Claude Code, Codex, Grok, OpenCode, Cursor), built on `@supermux/core` alone.
Two files: `server.ts` (an HTTP server, under 500 lines) and `index.html` (the page, no framework, no build step): copy them and make it yours.

## Run it

In this repository (Node 22.18 or later runs `server.ts` as it is):

```sh
bun install
bun run build:core
node packages/supermux-core/examples/chat/server.ts
# listening on http://127.0.0.1:4321/?token=3f9c…  ← open it
```

On its own:

```sh
npx degit UstaLabs/supermux/packages/supermux-core/examples/chat my-chat
cd my-chat && npm install && node server.ts
```

Each agent you chat with is its own CLI, signed in as you are in a terminal. Without `--agents` the server offers every one it finds on `PATH`.

Open the URL the server prints, token included: it signs your browser in with a cookie (`HttpOnly`, `SameSite=Strict`) and takes the token out of the address bar. Every request needs that token, the page's, the API's and the event stream's, because any process on this machine can reach 127.0.0.1, another user's or a command an agent runs, and none of them may start an agent in a folder of its choosing or answer a permission prompt. The state directory keeps the token, in `token` (made at the first start, a file only you can read), so a page left open signs in again after a restart; delete the file and the next start makes a new one, which a page then says to open. A script sends it as `Authorization: Bearer <token>`.

## Flags

| Flag | Default | |
| --- | --- | --- |
| `--port <n>` | `4321` | It listens on 127.0.0.1 only, answers its own page only (the Host and Origin are checked) and requests with its token, and no other site may frame the page. |
| `--state <dir>` | `~/.supermux-chat` | Sessions, transcripts, titles and the token. One server per directory. Not inside a temporary directory (`/tmp`, `/var/tmp`, `$TMPDIR`; real paths are compared), which the start refuses. A chat on a folder that holds it is refused (400). |
| `--agents <a,b>` | the CLIs on `PATH` | Any of `claude`, `codex`, `grok`, `opencode`, `cursor`. |
| `--model <agent>=<model>` | the CLI's own | Once per agent, e.g. `--model claude=opus --model codex=gpt-5`. |
| `--token <t>` | the one in `<state>/token` | A token of your own instead, 16 or more letters, digits, `.`, `_`, `~` or `-` (the file stays as it is). Other users of the machine can read a command's arguments (`ps`): on a shared machine, keep the file's. |

The state directory is kept out of your projects because an agent can write the folder it works in, and a symlink it planted in the state directory would make a keeper, which no sandbox confines, write wherever the link points. It is kept out of the temporary directories for the same reason: a sandboxed agent can still write those, and the next start trusts the sessions it finds in the state directory.

## API

Every route takes and returns JSON; an error is `{ error, code }` with a 4xx or 5xx status. Each needs the token, as the page's cookie or an `Authorization: Bearer <token>` header: without it, 401.

| Route | |
| --- | --- |
| `GET /api/events` | Server-sent events for every session: `event` (what the agent does, a normalized event), `user`, `user-rejected`, `failed`, `state`, `message` (a message's phases: `accepted`, `started`, `completed`) and `sessions` (the list changed). |
| `GET /api/agents` | The agents offered. |
| `GET /api/sessions` | `[{ id, agent, cwd, title, state, pendingRequests }]`, newest first. |
| `POST /api/sessions` | `{ agent, cwd }` → `{ id }`. `cwd` is an absolute path; 400 if it holds the state directory (see `--state`). |
| `GET /api/sessions/:id/transcript?after=<line>` | The entries a transcript keeps, past `line`. |
| `POST /api/sessions/:id/messages` | `{ text }` → `{ messageId, line }`. While a turn runs it waits in a queue. 409 while a Stop is still ending, 429 when 128 wait. |
| `POST /api/sessions/:id/interrupt` | Stop. |
| `POST /api/sessions/:id/requests/:requestId` | Answers a permission prompt, `{ optionId }`, or a question, `{ answers: { [questionId]: optionId, or optionId[] when it takes several, or text } }` or `{ decline: true }`. |
| `POST /api/sessions/:id/subagents/:subagentId/stop` | Stops a subagent. |
| `POST /api/sessions/:id/subagents/:subagentId/message` | `{ text }` → `{ via }`: `direct`, or `relay` (through the main agent, with the `messageId` of that message). |
| `POST /api/sessions/:id/close` | Stops the agent and forgets the session: its record, and its keeper's directory with its journal (up to 64 MB), so closed chats do not pile up. The chat leaves the list; its transcript stays, in `<state>/transcripts/<id>.ndjson`. |

The entries a transcript keeps (all but text deltas, states and a message's phases before it completes) carry `line`: 1, 2, 3, ... A page loads the transcript, skips the live entries it has already, and after its stream drops asks for `?after=<the last line it has>`. A completed `message` names the `userLine` it answers, so a page loaded later still shows which message was cancelled or failed. The server keeps that link in memory: a message that completes after a restart has none.

## Stop, and restarts

Stop ends the running turn and cancels the messages queued behind it: each completes `cancelled`.

Stopping the server (Ctrl-C, or `kill`: SIGINT, SIGTERM) detaches it from the agents instead of stopping them: each keeps running behind its keeper, and the next start re-attaches to every session left open, a turn in progress included (so its message is not marked cancelled; the messages queued behind it are, as the queue does not outlive the server). Claude and Codex also replay a turn that finished while the server was down; ACP agents (Grok, OpenCode, Cursor) don't yet. A page left open says it reconnects, over each chat's last known state, and catches up once the server is back on the same port, with the token it keeps; after the token changes it says to open the new URL.
