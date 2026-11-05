# New session with a worktree: from Send to the first reply (card #75)

Step 1 is a static map of the path, with no behaviour changes. Step 2 is the timing trace (one trace id per
start, broker + app, printed by one command) and the measurements. Line numbers are on this branch
(`mux/worktree-session-startup`), which adds the trace marks; the code paths are the same as `dev`.

Prior work: a one-off hand profile on 2026-10-21 (session "Profile Session Startup Latency", summarised in
`~/.mux/domains/claudemux.md`) found ~35 s before Claude had the message. This card adds a permanent trace,
so the same breakdown can be printed for any start, and re-measures. Since 10-21: the fixed 2.5 s inject
grace is gone (the first message goes straight to the agent), and the rest of that list still stands.

## The path at a glance

```mermaid
sequenceDiagram
    autonumber
    participant L as Launcher (app)
    participant H as HostStore / BrokerApi (app)
    participant W as Broker web channel
    participant M as main.ts spawn path
    participant G as git (repo)
    participant C as supermux-core host
    participant K as keeper process
    participant A as agent CLI (claude / codex)

    Note over L: while open (before Send): GET /repos/info gates Send, /accounts, /agents/status, /commands/preview, /projects
    L->>H: Send → doSubmit → createSessionWithFirstMessageOrThrow
    H->>W: POST /paths/validate (always, one round trip)
    H->>W: POST /upload … (each staged file, one after another)
    H->>W: POST /sessions {workdir, agent, model, worktree:true, baseBranch, firstMessage}
    W->>M: spawnSession (create path)
    M->>M: assertAgentInstalled · Auto account (may wait ≤3 s for usage)
    M->>G: getRepoInfo: rev-parse, nested-repo scan (depth 2), branch, for-each-ref ×2
    M->>G: for-each-ref heads · git worktree add -b mux/<slug> <dir> <base>
    M->>G: .worktreeinclude copies · .mux/worktree-setup.sh (if any)
    M->>C: agent spawn: register row (2 sync git execs), adapter.start()
    C->>C: prepare (plugins, instructions, env) · session context folder
    C->>K: spawn keeper (bun keeper.ts), wait for socket + status, hello/welcome
    K->>A: spawn the agent CLI (claude --print … --await-initialize)
    C->>A: initialize (Claude: reply lists the slash commands; Codex: + `codex debug models` before launch, thread/start after)
    A-->>C: initialize reply → commands-update
    M->>W: session_added (thin) · Telegram menu refresh (awaited, fails)
    M->>W: workspace_added + session_added (full) · Telegram menu refresh again (awaited, fails)
    W-->>H: frames (projects_changed, workspace_added, session_added)
    W-->>H: 200 {id, workdir, repo_root, session_branch}
    H->>L: navigate to the new chat (on the response)
    M->>A: first message (not awaited): log + deliverInbound → adapter.send → write prompt
    W-->>H: message_append (inbound), agent_state working
    A-->>C: system/init (MCP servers incl. claude.ai connectors) · stream events · assistant
    C-->>M: activity cards · assistant-message (whole text, not streamed)
    M-->>W: message_append (outbound reply)
    W-->>H: reply frame → drawn in the chat
```

Everything in the diagram happens **one after another**. The only overlaps: the keeper starts the agent CLI as
soon as it is up, so the CLI boots while the broker is still waiting for the keeper's socket; the broker's
frames go out while the HTTP request is still open; and the first message is handed over after the response
without being awaited.

## Step by step

"Per start" means paid by every new session; "per launcher" means paid once while the launcher is open, before
Send; "per process" means paid once per broker process.

| # | Step | Where | Runs | Cost |
|---|---|---|---|---|
| 0 | Launcher open: repo info for the picked folder gates Send (`canSend` needs `repoInfoFor == workdir`); also `/accounts`, `/agents/status`, `/commands/preview`, `/projects`. `git fetch` happens only when the worktree picker opens (`/repos/info?fetch=1`, once per repo). | `apps/ui/.../session/SessionLauncherScreen.kt:502-535`, `:866` | before Send | per launcher |
| 1 | Send → `doSubmit` (trace begins here) → `onSubmit` → `createSessionWithFirstMessage` | `SessionLauncherScreen.kt:893`, `:913`, `:920`; `apps/ui/.../shell/SupermuxApp.kt:651-668`; `apps/ui/.../session/SessionActions.kt:257` | sequential | per start |
| 2 | `POST /paths/validate`, always one extra round trip; then staged files upload one after another | `apps/shared/.../state/HostStore.kt:2655`, `:2668` | sequential | per start |
| 3 | `POST /sessions`; **the client gives up after 15 s** on desktop and Android (see finding 1) | `HostStore.kt:2673`; `apps/shared/.../net/BrokerApi.kt:2295`, `:1767`; `HostStore.kt:199`, `:208`; `CioHttpFactory.kt:20` | sequential | per start |
| 4 | Broker route: body, workdir check, attachments, then `spawnSession` | `src/channels/web/index.ts:3956-4041` | sequential | per start |
| 5 | Create path: spawn, then announce, then Telegram | `src/main.ts:2839-2894`, `src/core/session-manager/create-session.ts:108-135` | sequential | per start |
| 6 | `assertAgentInstalled` | `src/main.ts:3720` | sequential | per start |
| 7 | **Auto account**: with ≥2 accounts it refreshes stale usage first and waits up to `AUTO_USAGE_WAIT_MS` = 3 s (his box: 3 Claude accounts + the system login) | `src/main.ts:3726`; `src/core/accounts/broker-accounts.ts:408-430`, `:50` | sequential | per start (only when usage is older than 5 min) |
| 8 | Repo probe: `realpath`, `rev-parse --show-toplevel`, nested-repo scan to depth 2, `branch --show-current`, `for-each-ref` remotes and heads | `src/main.ts:3745`; `src/core/git/repo-info.ts:21-58` | sequential, 5 git processes | per start |
| 9 | Branch name: `for-each-ref refs/heads` again | `src/core/worktree/manager.ts:55` | sequential | per start |
| 10 | **`git worktree add -b mux/<slug> <dir> <base>`**: a full checkout (supermux: 5,915 files) | `manager.ts:62` | sequential | per start |
| 11 | `.worktreeinclude` copies (supermux: `docs/`, 42 MB / 524 files, and `verify.sh`). #29 (`mux/platform-layer`, not on `dev` yet) swaps `fs.cp` for `files.copyTree`; the mark wraps the call, so it times either | `manager.ts:94-110` | sequential | per start |
| 12 | `.mux/worktree-setup.sh` (dependency install, if a repo has one). **None of his repos has one today**, so no install runs | `manager.ts:112-123` | sequential | per start |
| 13 | Agent spawn: name, `host.register`, socket bind, adapter; the row is registered with **two synchronous `git` execs on the event loop** (`captureBaseCommits`) | `src/core/agents/claude/session.ts:94-171`, `:162`; `src/core/session-manager/spawn-helper.ts:27-39` | sequential | per start |
| 14 | `adapter.start()` → host admission → **prepare** (plugins, instructions, `~/.claude.json` shim entries, Claude environment) | `src/core/agents/core-bridge/core-adapter.ts:647`, `:968`; `packages/supermux-core/src/host/index.ts:371-395`; `src/core/agents/claude/core-host.ts:205-260` | sequential | per start |
| 15 | Core create → session context folder (instructions file, plugin folder of links, MCP config) | `packages/supermux-core/src/core.ts:197`, `:884` | sequential | per start |
| 16 | **Keeper**: `bun keeper.ts` from TypeScript source, detached; the broker polls every 20 ms for its socket, then its status, then hello/welcome | `packages/supermux-core/src/keeper/client.ts:411`, `:463`, `:491`, `:592` | sequential (the agent CLI starts inside it) | per start |
| 17 | **Agent CLI boot until it answers `initialize`**; Claude's reply carries the slash-command list (card #13: pushed, no probe) | `packages/supermux-core/src/claude/index.ts:110`, `:451`, `:599`, `:537` | sequential | per start |
| 17b | Codex only: `codex debug models` (a whole extra Codex process) **before** the app-server launch, then `initialize`, then `thread/start` | `packages/supermux-core/src/codex/index.ts:763`; `codex/catalog.ts:66` | sequential | per start |
| 18 | Thin `session_added`, then **Telegram menu refresh, awaited** | `src/main.ts:3851` | sequential | per start |
| 19 | Workspace created or joined, `projects_changed`, `workspace_added`, full `session_added` | `create-session.ts:84-106` | sequential | per start |
| 20 | **Telegram menu refresh again, awaited** | `create-session.ts:127` | sequential | per start |
| 21 | HTTP 200 → the app navigates to the chat (on the response, not on `session_added`); the chat fetches `/messages` + `/chat-extras` | `HostStore.kt:2690-2697`; `apps/ui/.../shell/ShellUiState.kt:256`; `SupermuxApp.kt:468`; `HostStore.kt:1848` | sequential | per start |
| 22 | First message: written to the chat log and handed to the agent, **not awaited** (so it overlaps the response) | `src/main.ts:2887`, `:3674`, `:3605`; `core-adapter.ts:763`; `claude/index.ts:717` | parallel with 21 | per start |
| 23 | First turn: Claude's `system/init` (MCP servers connect: mux-shim, mux-channel, his claude.ai connectors), the model's first stream event, activity cards, the whole reply | `claude/index.ts:465-475`; `src/core/agents/core-bridge/normalized-bridge.ts:136-142` | sequential | per start |
| 24 | Reply: `assistant-message` → transcript + `message_append`; **text is not streamed to the apps**, it arrives whole | `src/main.ts:1793`, `:1362` | sequential | per start |

Not on the path: `git fetch` (only the launcher's worktree picker), dependency installs (no setup hook in his
repos), the slash-command probe (gone since card #13; the list arrives with `initialize`).

## What the map already shows

1. **The launcher's create request times out at 15 s on desktop and Android.** `BrokerApi.spawn` has its own
   120 s `withTimeout` (`BrokerApi.kt:1767`), but it runs on the store's shared client, built with no
   request timeout (`HostStore.kt:199`), so Ktor CIO's default 15 s fires first (the comment at
   `HostStore.kt:208` says so). iOS gets 60 s (NSURLSession), web 120 s. The broker carries on, creates the
   session and delivers the first message, while the launcher shows an error. In the live log, **15 of the
   last 47 agent-made starts took more than 15 s** (next section).
2. **The Telegram menu is refreshed twice per start, awaited, and fails every time** with
   `BOT_COMMANDS_TOO_MUCH` (more than 100 sessions). The first message waits behind both calls.
3. **Auto account choice can wait up to 3 s** for a usage refresh before anything else starts, when usage is
   older than 5 minutes (his box has 3 Claude accounts plus the system login, so Auto has a choice).
4. **Nothing reaches the app until the agent is fully up.** The response, `workspace_added` and
   `session_added` all wait for step 17, and the first message only goes out after that.
5. **Replies are not streamed.** The broker collects Claude's text deltas and sends the reply only when the
   message is complete; activity cards (thinking, tools) are the only live signal.
6. **Codex pays for an extra process per start** (`codex debug models`) before its real launch.
7. Small: two synchronous git execs on the broker's event loop per start (`captureBaseCommits`), five git
   processes in the repo probe plus one more `for-each-ref` for the branch name, and one extra round trip
   (`/paths/validate`) before the request.

## Real starts from the live broker's log (no restart, 2026-11-01 → 11-05)

The live broker logs every `spawn_session` / `delegate` call (Tally and other agents starting sessions; same
create path as the launcher, minus the first message). From `broker_call_received` to `broker_call_dispatched`:

- **47 starts: 1.1–26.2 s, median 9.0 s; 15 of 47 took more than 15 s.**
- Split by the session row's `created_at` (written after the worktree, before the agent starts), for the 26
  supermux-repo starts with a worktree: **1.6–16.5 s before the row** (median 6.8 s: Auto account + repo
  probe + worktree) and **2.7–12.7 s after it** (median 7.3 s: agent up + Telegram).
- This session itself: requested 06:53:25.76, Claude's tools attached 06:53:33.72, the two Telegram refreshes
  failed at 33.90 and 34.02, done 34.02: **8.26 s**.

## How to trace a start (step 2)

One trace id per start: the app's `S…` (made at Send, `PerfTrace.beginStart`) travels on `x-mux-trace` with
`POST /sessions`; a start without one (an agent's `spawn_session`, `delegate`, an older app) gets an `S…` id
minted by the broker. Same `perf.jsonl` and switch as card #54:

```
bun scripts/perf-timeline.ts on          # tracing on (the broker notices within 2 s)
# start a session in the app (or let an agent start one)
bun scripts/perf-timeline.ts start       # the newest start; `start <TRACE>` or `--last 5` for more
bun scripts/perf-timeline.ts list        # every launch, chat open and start
bun scripts/perf-timeline.ts off
```

Broker marks (`start.*`, in `src/main.ts`, `create-session.ts`, `worktree/manager.ts`, `claude/core-host.ts`):
`request` · `installed` · `account` · `repo` · `worktree.branches` · `worktree.add` · `worktree.includes` ·
`worktree.hook` · `worktree` · `spawned` · `telegram` (×2) · `announced` · `created` · `message.delivering` ·
`turn.started` · `activity.first` · `reply.first` · `turn.completed`.
Core marks, joined by session id through `packages/supermux-core/src/perf-marks.ts` (a no-op without a sink):
`host.prepared` (+ `host.prepare.laps`) · `core.context` · `keeper.spawned` · `keeper.connected` ·
`claude.transport` · `claude.initialized` · `host.opened` · `claude.prompt_written` · `claude.system_init`
(with each MCP server's status) · `claude.first_stream` · `claude.first_assistant` · Codex: `codex.catalog` ·
`codex.transport` · `codex.initialized` · `codex.thread` · `codex.prompt_written` · `codex.turn_started` ·
`codex.first_notify` · `codex.first_text`.
App marks (`start.*`, shipped while the broker traces): `send` · `validated` · `uploaded` · the `http` line of
`POST /sessions` (the broker's time nested under it) · `response` · `session_added` · `message.echoed` ·
`agent.working` · `activity` · `commands` · `reply.arrived` · `chat.shown` · `reply.drawn` · `failed`.

`scripts/perf-start-probe.ts` starts sessions the way the launcher does and ships the same app-side marks,
for repeatable runs against a throwaway or shadow broker.
