# supermux kanban

A board for the coding agent CLIs on your machine (Claude Code, Codex, Grok, OpenCode, Cursor), built on `@supermux/core` alone: each card is a task for one agent, which works on a branch and in a git worktree of its own.
Two files: `server.ts` (an HTTP server, about 500 lines) and `index.html` (the page, no framework, no build step): copy them and make it yours.

## Run it

In this repository (Node 22.18 or later runs `server.ts` as it is):

```sh
bun install
bun run build:core
node packages/supermux-core/examples/kanban/server.ts --repo /path/to/repo
# listening on http://127.0.0.1:4322/?token=3f9c…  ← open it
```

On its own:

```sh
npx degit UstaLabs/supermux/packages/supermux-core/examples/kanban my-kanban
cd my-kanban && npm install && node server.ts --repo /path/to/repo
```

Each agent is its own CLI, signed in as you are in a terminal. Without `--agents` the server offers every one it finds on `PATH`.

Open the URL the server prints, token included: it signs your browser in with a cookie (`HttpOnly`, `SameSite=Strict`) and takes the token out of the address bar. Every request needs that token, the page's, the API's and the event stream's, because any process on this machine can reach 127.0.0.1, another user's or a command an agent runs, and none of them may start a card, answer a permission prompt or discard work. Each start prints a new token, so a page left open across a restart says to open the new URL (or start with `--token`). A script sends it as `Authorization: Bearer <token>`.

A card starts in the backlog. Start gives it a branch, `agent/<id>`, and a worktree made from the commit the repository is on, and sends its prompt to its agent. From then on the card moves by itself: to Needs you while the agent waits on a permission prompt or a question (you answer it on the card), and to Review when the agent is done, with the diff and a box for a follow-up that sends it back to work. Done stops the agent and commits what it changed on its branch (agents leave their work uncommitted), as one commit titled after the card: `git merge agent/<id>`, which the card copies, merges the work. Discard stops the agent and deletes the worktree and the branch. An agent that fails while at work sends its card back to the backlog, saying why: Start again goes on in the same worktree and conversation.

Stopping the server (Ctrl-C, or SIGTERM) detaches it from the agents instead of stopping them: the next start re-attaches to every card at work.

## Flags

| Flag | Default | |
| --- | --- | --- |
| `--repo <dir>` | `.` | Any directory of the git work tree the cards branch from. Keep it outside `/tmp` (see Safety). |
| `--port <n>` | `4322` | It listens on 127.0.0.1 only, answers its own page only (the Host and Origin are checked) and requests with its token, and no other site may frame the page. |
| `--state <dir>` | `~/.supermux-kanban/<repo>-<hash>` | The board (`board.json`), the core's sessions and keepers (`core/`) and the cards' worktrees (`worktrees/`), side by side: the core refuses an agent folder inside its own directory. One server per directory. Keep it outside `/tmp` too (see Safety). A directory inside the repository is added to its `.git/info/exclude`. |
| `--agents <a,b>` | the CLIs on `PATH` | Any of `claude`, `codex`, `grok`, `opencode`, `cursor`. |
| `--model <agent>=<model>` | the CLI's own | Once per agent, e.g. `--model claude=opus --model codex=gpt-5`. |
| `--token <t>` | a new one at each start | The token requests carry, the same at every start: 16 or more letters, digits, `.`, `_`, `~` or `-`. Other users of the machine can read a command's arguments (`ps`): on a shared machine, keep the printed one. |

## API

Every route needs the token, as the page's cookie or an `Authorization: Bearer <token>` header (401 without), and takes and returns JSON, but for the diff, which is text. An error is `{ error, code }` with a 4xx or 5xx status: 404 for a card that does not exist, 409 while another action on the same card is in flight (or the card is not where the action needs it).

| Route | |
| --- | --- |
| `GET /api/events` | Server-sent events: `{ type: "board", repo, cards }`, the whole board, first when the stream opens and then after every change; and `{ type: "activity", cardId, text }`, what a card's agent does, in a line. |
| `GET /api/agents` | The agents offered. |
| `GET /api/board` | `{ repo, cards }`. `repo` is the repository the cards branch from, `{ name, path }`: its name, and the full path of its top directory. A card is `{ id, title, prompt, agent, column }`, `column` being `backlog`, `running`, `needs-you`, `review` or `done`; once started it also has `branch`, `worktree` and `base` (the commit it starts from), and as it goes `diffStat` (`git diff --shortstat`, taken at each review and at Done), `error`, `activity`, `request` (the `permission-request` or `user-question` its agent waits on), and once done `commits`: how many commits `agent/<id>` has over its base (0: nothing to merge). |
| `POST /api/cards` | `{ title, prompt, agent }` → `{ id }`. The card goes to the backlog. |
| `POST /api/cards/:id/start` | Makes the branch and the worktree, and sends the prompt to the agent. |
| `POST /api/cards/:id/requests/:requestId` | Answers a permission prompt, `{ optionId }`, or a question, `{ answers: { [questionId]: optionId, or optionId[] when it takes several, or text } }` or `{ decline: true }`. |
| `POST /api/cards/:id/interrupt` | Stop: ends the agent's turn, and cancels the messages queued behind it. |
| `GET /api/cards/:id/diff` | What the agent changed since the card's base, new files included, as a unified diff. 413 over 5 MB: open the worktree to read it. |
| `POST /api/cards/:id/followup` | `{ text }`: a message for the agent, which takes the card back to work. 409 while a Stop is still ending. |
| `POST /api/cards/:id/done` | Stops the agent, then commits what it changed on `agent/<id>`, with the first line of the card's title as the message, unsigned: 409 when the agent moved its worktree to another branch or detached it. Repositories the agent made inside its worktree are left out. `{ ok, commit, skipped }`: the commit (`null` when nothing changed, and nothing is committed), and the repositories left out. The branch and the worktree stay; a commit refused or failed leaves the card in review, its agent stopped. |
| `POST /api/cards/:id/discard` | Stops the agent, and deletes its worktree, its branch and the card. |

## Safety

- **Keep the repository and the state directory outside `/tmp`.** The board drives git in the repository, so it must trust the repository's `.git`: one inside the agents' writable roots (a `/tmp` checkout, say) is refused, as an agent could write `.git/hooks` or `.git/config` there for git to run. A state directory there is refused too, as the next start trusts its `board.json`. The temporary directories are `/tmp`, `/var/tmp` and `$TMPDIR`, compared by their real paths (on macOS `/tmp` is `/private/tmp`).
- **`board.json` names only what the board makes.** At start each card is checked: an id that is not 8 hex digits drops it; a worktree that is not `<state>/worktrees/<id>` or a branch that is not `agent/<id>` is put back; a base that is not a commit id, or a git directory outside the repository's own, is left out. The log says what was wrong. So the paths git takes and Discard deletes are always the card's own.
- **The repository's hooks do not run for the board's worktrees**, nor for the commit Done makes. Every git command the board runs has `core.hooksPath` and `core.fsmonitor` off, and goes through the git directories the board recorded when it made each worktree, never the `.git` file in the worktree (which an agent can replace). Submodules stay out of the diff and the commit, so no filter an agent configures in one runs, and so do the repositories an agent makes in its worktree. The commit has the repository's identity (`user.name`, `user.email`), or the board's where it has none. Nothing an agent writes runs as a git command of the board's.
- **Board commits are unsigned**: Done turns `commit.gpgSign` off for them, so it never waits on a signing prompt with no terminal to show it. If your project signs its commits, sign when you merge.
- **Agents run in their CLI's own asking mode**, as `supermux()` launches them: what an agent would ask you in a terminal comes to the board, and its card waits in Needs you until you answer. A worktree is not a sandbox: a command you allow runs as you.
- The page shows every value from the server (titles, prompts, what the agents say, diffs) as text, never as HTML.
