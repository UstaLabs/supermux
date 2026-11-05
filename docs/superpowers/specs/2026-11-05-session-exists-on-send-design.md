# A session exists on Send: Design (2026-11-05, card #75)

**Status:** implemented on `mux/worktree-session-startup` (Ahmet's go, 2026-11-05). Reviewed by council-17
(Codex, Antigravity, Claude Fable): *approve with changes*, all folded in below. What the build changed
from this text is listed in **As built**, at the end. The open choice went with the recommendation (no new
storage).

## Goal

Today Send on a new session waits for the worktree and the agent before the app hears anything: median ~9 s,
up to ~26 s on a busy box, measured on card #75 (`docs/perf/2026-11-05-worktree-session-start.md`). The app
should answer in a fraction of a second and open the chat; the slow part happens after. The time to the
agent's first reply stays the same; the wait the user stares at goes away.

**No state machine.** No new stored session state, no `starting`/`failed` columns, no transitions to repair
after a restart. Everything the UI shows is computed from facts the broker already has.

## The model

> **A session is a chat with a folder. Send saves the chat (its row, its branch, your message) and the
> session is born asleep. A message to a sleeping session wakes it: the worktree is made if it isn't there,
> the agent starts, and the message is delivered. If waking fails, the chat says why, and your next message
> tries again.**

"Asleep" is not new: it is today's `status = suspended`, the state a dozing session is in. Everything that
already wakes a dozing session (the web path, Telegram, the session inbox, boot) then works for a new one.

## What already exists (verified on this branch)

| Fact | Where |
|---|---|
| A suspended session stays asleep at boot | `resumeAtBoot` skips it, `src/core/session-manager/manager.ts:1757` |
| A web message to it resumes it first | `src/main.ts:4271` |
| A Telegram message does the same | `src/main.ts:4080` |
| The inbox wakes any non-live recipient (restore, resume, revive), one wake at a time | `ensureLive`, `src/core/inbox/delivery.ts:163`; chain `:82`; `pending(id)` `:90` |
| Resume recreates a missing worktree | `manager.ts:1602` → `ensureSessionWorktree` → `ensureWorktreeAt` (`src/core/worktree/manager.ts:79`) |
| Every agent's dialect starts fresh when there is no conversation id | e.g. `src/core/agents/codex/session.ts:273` (`if (initialSessionId) resume() else start()`) |

## Design

### 1. Born suspended

`POST /sessions` does only the instant part, then answers:

1. **Checks:** agent installed, account (Auto picks at once, fix 2 on #75), folder.
2. **Repo probe and branch:** if a worktree is wanted, derive `mux/<slug>` unique against **both** git's
   branches and every session row's `session_branch`. Two quick Sends collide today because only `refs/heads`
   is checked (`worktree/manager.ts:42`). Create the branch now with `git branch mux/<slug> <base>` (~10 ms),
   so the name is taken for good; pick the worktree path (a uuid under the repo's worktrees folder).
3. **The row:** registered with everything a later wake needs: the future worktree path as `workdir`,
   `repo_root`, `base_branch`, `session_branch`, agent, model, effort, permission mode, **account**, and
   **`agent_home` derived as spawn does** (`join(STATE_DIR, "agents", <kind>, homeDirName(name))`). Then
   `sessions.suspend(id)`.
4. **Workspace** made or joined, then `workspace_added` / `session_added` broadcast, so every device sees the
   session at once.
5. **The first message** is logged under the app's own id (§5) and handed to the session's wake chain (§2),
   not awaited.
6. **Answer** `{ id, … }`.

**Drafts take the same path.** A draft's first message wakes the draft's own row. The hard-delete-and-respawn
in `main.ts:4279-4360` goes; its "there is no way to adopt the draft's id" comment is stale, because
`spawnSession` takes `args.id`.

**Other doors keep their answer.** Agents' `spawn_session`, `delegate`, council, the curator and Telegram
`/spawn` create the same way and then wait for the wake, returning when the agent is up, as today.

### 2. One wake rule, one chain per session

Every door delivers through one function, `deliver(session, message)`:

1. **Log the message first.** Today a failed resume of a dozing session drops the message before it is
   saved: the web handler returns before `handleWebInbound` appends it.
2. **On the session's chain:** if the agent isn't live, wake it (archived → restore, suspended → resume,
   dead → revive), then hand the message over. One wake at a time; messages wait behind it in arrival order.
   This is the inbox's `ensureLive` and chain, used by the web, Telegram and `POST /sessions` paths too.

**Resume must start a never-started session for all six agents.** Today it does not:

- Codex is gated on `agent_session_id && agent_home` (`manager.ts:1609`, `:1686`, boot `~:1790`). Drop the
  thread-id gate: the dialect already calls `start()` without one.
- Cursor, OpenCode, Grok and Antigravity need `agent_home`, which a new row now has (§1.3).
- **Thread `session.account` through `ResumeRow` and all six `resume*Session` → `host.register`.** Today the
  chosen or Auto account is lost on every resume, not only on this path.

**A fresh checkout at wake does what `createWorktree` does today.** `ensureWorktreeAt` checks out the branch
from §1.2, then runs the `.worktreeinclude` copies, `.mux/worktree-setup.sh` and `captureBaseCommits`. A
half-made folder (it exists but is not in `git worktree list`, left by a crash) is removed and made again.
**It fails visibly:** a worktree that can't be made fails the wake with its reason. It never falls back to
starting in the main repo, as `main.ts:3759-3762` does today.

The files, git and terminal panes treat a session folder that doesn't exist yet as "not made yet"
(`fs_list`/`fs_read`, `gitStatusService`, terminal attach) instead of erroring.

### 3. "Starting" is broadcast, never stored

While a wake for the session is in flight (the chain's `pending(id)`), the broker sends `agent_state` with
`phase: "starting"`. The next `agent_state` (idle, working) or a failure replaces it. Nothing is written:
after a broker restart no wake is in flight, so the session shows as asleep, which is the truth.

There is no "Starting…" chat line, and nothing is inferred from the chat's last line. Either would leave a
session showing "Starting" forever after a restart. Apps show "Starting…" in the row and the header; an
older app sees an unknown phase, shows the session as idle, and still works.

### 4. Failure, and two quick messages

- **One chat line, in #71's words:** "Couldn't start Claude: <reason>. Your next message tries again."
  `startFailureNotice` passes the real reason through; today it hides non-auth errors behind a generic line.
  The row stays suspended (asleep). No button, no failed state.
- **Two messages sent fast:** both wait on one wake. If it fails, everything queued behind it fails with it,
  under one failure line, and is not re-sent. Only a **new** message retries the wake. The messages stay
  visible in the chat.

### 5. Duplicates, end to end (do this first)

- **The app mints `web-<uuid>`** for every message, including the first one in `POST /sessions` (new body
  field `client_message_id`; today the broker makes `spawn-${Date.now()}`, `main.ts:2875`). It shows its
  local copy under `in:web:<that id>`, the id the broker logs (`inbound-handler.ts:41`).
- **The broker marks the id seen when delivery starts, and unmarks it if delivery throws.** Today it marks
  only after `send()` returns (`inbound-delivery.ts:56`), which is when the whole turn ends, so a re-send
  during a turn is delivered twice. Marking without unmarking would dedupe the app's own retry forever.
- **The app's reducer upserts `message_append` by `entry.id`**, instead of appending and pruning its
  pending copy by text (`HostReducer.kt:185`).

### 6. Stop, archive and kill during a start

They go on the same per-session chain. **Stop** cancels the in-flight wake and drops the deliveries queued
behind it (the messages stay in the chat). **Archive and kill** cancel it too, and an agent arm that
finishes anyway is stopped, never registered on an archived or deleted row. Today `runInterrupt` with no
adapter is a no-op (`interrupt.ts:9-12`), and `kill`/`killAndArchive` don't know about an in-flight arm, so
it later registers a live agent on an archived session.

## The flow

```mermaid
sequenceDiagram
    autonumber
    participant App
    participant B as Broker (POST /sessions)
    participant C as Session chain (per session)
    participant G as git
    participant A as Agent

    App->>B: Send {workdir, agent, firstMessage, client_message_id}
    B->>G: git branch mux/<slug> <base> (~10 ms)
    B->>B: row (asleep: status=suspended, agent_home, account), workspace, first message logged
    B-->>App: 200 {id} + workspace_added / session_added (well under a second)
    B->>C: deliver(first message)
    C-->>App: agent_state phase "starting" (broadcast, not stored)
    C->>G: worktree add (checkout) + includes + setup hook + base commits
    C->>A: resume → fresh start (keeper, CLI, initialize)
    C->>A: hand over the message (seen marked, unmarked on throw)
    A-->>App: agent_state working … then the reply
    Note over C: on failure: one chat line "Couldn't start …; your next message tries again", row stays asleep
```

## What it does not change

- **The time to the first reply.** The worktree and the agent still start one after the other; the app just
  stops waiting for them. Making the start itself faster is a separate choice.
- **The other doors' answer** (they still return when the agent is up).
- **Streaming** (its own card).
- **The 45 s timeout** (fix 1 on #75), which stays as a safety net.

## Open choice for Ahmet

**Should a message caught waiting by a broker restart, or by a failed start, be delivered later on its
own?**

- **Codex:** yes. Store a per-message "queued / accepted by the agent" mark, so a message saved in the chat
  is never silently left unsent.
- **Antigravity and Fable:** no new storage. It is consistent with #71's "nothing is re-sent automatically",
  which exists because automatic re-sends are how double messages happen.

**My recommendation: no new storage now.** The window is narrow: a restart or a failure during a 5–25 s
start. The message stays visible in the chat with a plain failure line above it, and the next message
carries on. A per-message mark is the one stored fact this design would add if it turns out to happen
in practice.

## Order of work and tests

1. **Duplicates end to end** (§5), about half a day. Tests: the client id survives a retried create; seen is
   marked at hand-over and unmarked on a throw; the reducer upserts by id.
2. **Born suspended + resume for all six agents + account threading + `agent_home`** (§1, §2), about a day.
   Tests: a never-started row resumes to a fresh conversation for each kind (fake drivers); the account
   survives resume.
3. **One chain with Stop/archive/kill + the starting phase** (§2, §3, §6), half a day to a day. Tests: two
   quick messages, one wake; a failed wake fails both, a new message retries; archive during a wake leaves
   no live agent; the phase disappears after a restart.
4. **Worktree at wake** (§1.2, §2): branch at Send, includes/hook/base commits, half-made cleanup, visible
   failure; about half a day.
5. **Drafts on the same path**, about half a day.

Live check: `scripts/perf-start-probe.ts` against a shadow broker (card #75). Send → answer should drop
from ~4–26 s to well under a second; Send → first reply should be unchanged.

## As built (2026-11-05)

Where the implementation differs from the design above, and why:

- **"Starting" is `agent_state` with `detail: "starting"`** (`state: "working"`, `working: true`, legacy
  `phase: "thinking"`), computed from the chain (`SessionChain.wakingSince`), never stored. Not a new
  `phase`: an older app shows the start as working, with its Stop, instead of idle. The app shows
  "starting…" in the header and working row, and "starting" instead of "suspended" in the session row.
- **`session_state` carries `status`** when a wake ends (active) or a start stopped by Stop goes back to
  sleep (suspended). Before, a client kept a woken session's "suspended" badge until its next snapshot;
  this also covers the workers #78 leaves asleep at boot.
- **A half-made folder is removed only when it is empty.** A folder with anything in it may hold work, so
  it is used as it is; and the check needs no git call, so a repo that moved no longer fails a start.
- **A launcher session with no first message is woken at once, in the background**, as every launched
  session always started; the launcher still answers at once.
- **The files pane, folder subscriptions and terminals wait** (up to 30 s) for a session folder a wake is
  checking out, until git has checked the files out (not for the setup hook), instead of failing.
- **Doors** (spawn_session, delegate, council, the curator, /spawn, a draft's first message) wait for the
  wake and answer once the agent is up. A door's start that fails is archived and its Send-time branch
  deleted, as a failed spawn left nothing; its caller gets the reason. **agent-rpc workers** keep the
  direct start: their MCP config is not a row field, so a resume could not rebuild them.
- **Telegram keeps its "Resuming session …" line** (it can't show "starting"); a failed wake is said in
  the chat the message came from (the reply target is noted when the message is logged).
- **A start cut short by a broker restart** says "supermux restarted while it was starting" rather than
  whatever failed under it (seen live: git's half-written progress).
- **Kill and archive** cancel a start in flight through the SessionManager's kill funnel
  (`cleanup.cancelWake`); **Stop** cancels it and the wake that finishes anyway is put back to sleep
  (`SessionManager.putToSleep`).
