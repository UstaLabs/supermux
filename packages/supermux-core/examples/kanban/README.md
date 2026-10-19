# supermux kanban

A board for the coding agent CLIs on your machine (Claude Code, Codex, Grok, OpenCode, Cursor), built on `@supermux/core` alone: each card is a task for one agent, which works on a branch and in a git worktree of its own.
Two files: `server.ts` (an HTTP server, under 500 lines) and `index.html` (the page, no framework, no build step): copy them and make it yours.

## Run it

In this repository (Node 22.18 or later runs `server.ts` as it is):

```sh
bun install
bun run build:core
node packages/supermux-core/examples/kanban/server.ts --repo /path/to/repo
# listening on http://127.0.0.1:4322  ← open it
```

On its own:

```sh
npx degit UstaLabs/supermux/packages/supermux-core/examples/kanban my-kanban
cd my-kanban && npm install && node server.ts --repo /path/to/repo
```

Each agent is its own CLI, signed in as you are in a terminal. Without `--agents` the server offers every one it finds on `PATH`.

A card starts in the backlog. Start gives it a branch, `agent/<id>`, and a worktree made from the commit the repository is on, and sends its prompt to its agent. From then on the card moves by itself: to Needs you while the agent waits on a permission prompt or a question (you answer it on the card), and to Review when the agent is done, with the diff and a box for a follow-up that sends it back to work. Done stops the agent and keeps the branch for you to merge (the card copies `git merge agent/<id>`); Discard stops the agent and deletes the worktree and the branch. An agent that fails while at work sends its card back to the backlog, saying why: Start again goes on in the same worktree and conversation.

Stopping the server (Ctrl-C, or SIGTERM) detaches it from the agents instead of stopping them: the next start re-attaches to every card at work.

## Flags

| Flag | Default | |
| --- | --- | --- |
| `--repo <dir>` | `.` | Any directory of the git work tree the cards branch from. Keep it outside `/tmp` (see Safety). |
| `--port <n>` | `4322` | It listens on 127.0.0.1 only, answers its own page only (the Host and Origin are checked), and no other site may frame the page. |
| `--state <dir>` | `~/.supermux-kanban/<repo>-<hash>` | The board, the agents' sessions and the cards' worktrees. One server per directory. A directory inside the repository is added to its `.git/info/exclude`. |
| `--agents <a,b>` | the CLIs on `PATH` | Any of `claude`, `codex`, `grok`, `opencode`, `cursor`. |
| `--model <agent>=<model>` | the CLI's own | Once per agent, e.g. `--model claude=opus --model codex=gpt-5`. |

## API

Every route takes and returns JSON, but for the diff, which is text. An error is `{ error, code }` with a 4xx or 5xx status: 404 for a card that does not exist, 409 while another action on the same card is in flight (or the card is not where the action needs it).

| Route | |
| --- | --- |
| `GET /api/events` | Server-sent events: `{ type: "board", cards }`, the whole board, first when the stream opens and then after every change; and `{ type: "activity", cardId, text }`, what a card's agent does, in a line. |
| `GET /api/agents` | The agents offered. |
| `GET /api/board` | `{ cards }`. A card is `{ id, title, prompt, agent, column }`, `column` being `backlog`, `running`, `needs-you`, `review` or `done`; once started it also has `branch`, `worktree` and `base` (the commit it starts from), and as it goes `diffStat` (`git diff --shortstat`, taken at each review), `error`, `activity`, and `request`: the `permission-request` or `user-question` its agent waits on. |
| `POST /api/cards` | `{ title, prompt, agent }` → `{ id }`. The card goes to the backlog. |
| `POST /api/cards/:id/start` | Makes the branch and the worktree, and sends the prompt to the agent. |
| `POST /api/cards/:id/requests/:requestId` | Answers a permission prompt, `{ optionId }`, or a question, `{ answers: { [questionId]: optionId, or optionId[] when it takes several, or text } }` or `{ decline: true }`. |
| `POST /api/cards/:id/interrupt` | Stop: ends the agent's turn, and cancels the messages queued behind it. |
| `GET /api/cards/:id/diff` | What the agent changed since the card's base, new files included, as a unified diff. 413 over 5 MB: open the worktree to read it. |
| `POST /api/cards/:id/followup` | `{ text }`: a message for the agent, which takes the card back to work. 409 while a Stop is still ending. |
| `POST /api/cards/:id/done` | Stops the agent. The branch and the worktree stay. |
| `POST /api/cards/:id/discard` | Stops the agent, and deletes its worktree, its branch and the card. |

## Safety

- **Keep the repository outside `/tmp`.** The board drives git in it, so it must trust the repository's `.git`: one inside the agents' writable roots (a `/tmp` checkout, say) is refused, as an agent could write `.git/hooks` or `.git/config` there for git to run.
- **The repository's hooks do not run for the board's worktrees.** Every git command the board runs has `core.hooksPath` and `core.fsmonitor` off, and goes through the git directories the board recorded when it made each worktree, never the `.git` file in the worktree (which an agent can replace). Submodules stay out of the diff, so no filter an agent configures in one runs. Nothing an agent writes runs as a git command of the board's.
- **Agents run in their CLI's own asking mode**, as `supermux()` launches them: what an agent would ask you in a terminal comes to the board, and its card waits in Needs you until you answer. A worktree is not a sandbox: a command you allow runs as you.
- The page shows every value from the server (titles, prompts, what the agents say, diffs) as text, never as HTML.
