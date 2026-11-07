# A session exists on Send: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the launcher's `POST /sessions` answers in a fraction of a second with a session that is born asleep; a message to a sleeping session wakes it (worktree, then agent) through one per-session chain, for every door, with no new stored state.

**Architecture:** Spec: `docs/superpowers/specs/2026-11-05-session-exists-on-send-design.md`. One new per-session chain (`SessionChain`) is the only place a session's agent is woken and the only queue its messages wait in; the inbox, the web path, Telegram and `POST /sessions` all use it. A new session is a row registered then suspended (`createAsleep`); resume becomes able to start a never-started session for all six agents (account threaded, Codex's thread-id gate dropped). Doors that answer only when the agent is up (spawn_session, delegate, council, `/spawn`, curator) create asleep and await the wake; rpc workers and Agents (spawnPA) keep their direct spawn.

**Tech Stack:** Bun + TypeScript broker (`bun test --isolate`, `env -u MUX_SOCKETS_DIR`), supermux-core, Kotlin Multiplatform apps (Gradle under the heavy lock).

**Ground rules (from the card):** TDD for every behaviour; heavy lock for builds; no merge, no push; `#78` (boot resumes only Agents, workers wake on their next message) relies on the same wake path, so `SessionChain.wake` must be the single place a worker is woken.

---

## File structure

| File | Responsibility |
|---|---|
| `src/core/session-manager/session-chain.ts` (new) | Per-session promise chain: `run(id, work)`, `wake(id)`, `waking(id)`, `pending(id)`, `cancel(id)`; generation-based cancellation; "starting" change callback. |
| `src/core/session-manager/born-asleep.ts` (new) | `createAsleep(deps, args)`: name, agent_home, branch at Send, row registered then suspended. |
| `src/core/session-manager/inbound-delivery.ts` | Mark the message id seen at hand-over; unmark when `send()` throws. |
| `src/core/inbox/delivery.ts` | Use an injected `SessionChain` (default: its own) for wakes and passes; `ensureLive` treats an active row with no adapter as asleep. |
| `src/core/session-manager/manager.ts` | `resumeSuspended`/`resumeFromArchive`/`resumeOneAtBoot`: no Codex thread-id gate; account threaded; an arm that finishes after archive/kill/cancel is stopped, never registered. |
| `src/core/agents/session-types.ts` + `src/core/agents/*/session.ts` (6) | `ResumeRow.account`; every `resume*Session` passes it to `host.register`. |
| `src/core/worktree/manager.ts` | `deriveSessionBranch` unique against rows too; `createSessionBranch`; `ensureWorktreeAt` removes a half-made dir and, when it checks out fresh, runs includes + hook and reports it. |
| `src/core/session-manager/session-store.ts` | `setBaseCommits(id, map)`. |
| `src/core/session-manager/agent-state-frame.ts` | `startingFrame(session)`: `working: true, detail: "starting"`. |
| `src/main.ts` | Wiring: one `SessionChain`; `spawnSession` = `createAsleep` + awaited wake for doors (rpc/PA keep direct spawn); launcher = `createAsleep` + announce + answer + first message on the chain; web inbound/Telegram log first, then wake on the chain; drafts woken in place; Stop/archive/kill cancel the chain; snapshot overlays "starting". |
| `src/channels/web/index.ts` | `POST /sessions` takes `client_message_id` for the first message. |
| `apps/shared/.../proto/Frames.kt`, `state/HostStore.kt`, `state/HostReducer.kt`, `net/BrokerApi.kt` | App mints `web-<uuid>`; local echo `in:web:<id>`; reducer upserts by id; `SpawnRequest.clientMessageId`. |
| `apps/ui/.../chat/ChatPanel.kt` (+ row) | "Starting…" for `detail == "starting"`. |

## Tasks

### Task 1: Delivery marks the message id at hand-over, unmarks on throw
**Files:** `src/core/session-manager/inbound-delivery.ts`, test `src/core/session-manager/inbound-delivery.test.ts`
- [ ] Test: a second `deliverInbound` with the same `message_id` while the first `send()` is still pending returns `{ ok: true, deduped: true }` and calls `send` once.
- [ ] Test: when `send()` throws, the id is not left marked: a retry with the same id calls `send` again.
- [ ] Run, see both fail (today: two sends; the throw case passes already, keep it as a guard).
- [ ] Implement: `deps.seen.mark(sessionId, messageId)` before `await adapter.send(...)`; in a `catch`, `deps.seen.unmark(sessionId, messageId)` then rethrow (add `unmark` to `RecentInboundIds`).
- [ ] Run, commit `fix(broker): a message id is marked when it is handed over, and unmarked if that fails (#75)`.

### Task 2: Resume can start a never-started session for all six agents, with its account
**Files:** `src/core/agents/session-types.ts`, `src/core/agents/{claude,codex,cursor,opencode,grok,antigravity}/session.ts`, `src/core/session-manager/manager.ts`; tests in `src/core/agents/*/session.test.ts` (fake hosts in `tests/helpers/fake-*-host.ts`) and `src/core/session-manager/manager.test.ts`.
- [ ] Tests (per agent): `resume(ctx, { …, account: "acc-1", agent_session_id: undefined })` registers with `account: "acc-1"` and starts (not resumes) the adapter.
- [ ] Test (manager): `resumeSuspended` of a Codex row with `agent_home` and no `agent_session_id` takes the Codex arm and returns true (today `resume_suspended_no_path`); same for `resumeFromArchive` and boot.
- [ ] Run, see them fail.
- [ ] Implement: `ResumeRow.account?: string`; each `resume` passes `account: session.account` into its `resume*Session`, which spreads `...(account ? { account } : {})` into `host.register` (and `reregister`); manager arms pass the row's `account`; drop `&& session.agent_session_id` from the Codex branches (`manager.ts:1609`, `:1686`) and the boot skip.
- [ ] Run, commit `fix(agents): resume starts a never-started session for every agent and keeps its account (#75)`.

### Task 3: An arm that finishes after archive, kill or cancel is stopped, never registered
**Files:** `src/core/session-manager/manager.ts`, test `src/core/session-manager/manager.test.ts`
- [ ] Test: start `resumeSuspended(row)` with an arm that waits on a gate; archive the row (`registry.unregister`) before releasing the gate; after it settles, `adapterFor(id)` is undefined, the fake adapter's `stop()` ran, and the result is false.
- [ ] Run, see it fail (today the runtime is registered on the archived row).
- [ ] Implement: after the arm, if `this.registry.get(id)` is not live (archived/missing) or `isCancelled(id)` (port from the chain), stop the adapter, `deleteRuntime(id)`, return false (cancelled-by-Stop also re-suspends the row).
- [ ] Run, commit.

### Task 4: `SessionChain`: one wake and one queue per session
**Files:** create `src/core/session-manager/session-chain.ts`, test `session-chain.test.ts`
- [ ] Tests: (a) two `run` calls for one session run in order; (b) `wake` while another wake is in flight joins it (one `ensureLive`); (c) a failed wake rejects every `run` queued behind it, and a later `run` wakes again; (d) `cancel(id)` rejects queued work with `CancelledError` and makes `isCancelled(id, generation)` true for the in-flight one; (e) `waking(id)` is true only while `ensureLive` runs for a session that was not deliverable, and `onChange` fires on both edges; (f) different sessions do not wait on each other.
- [ ] Implement (see the interface in the File structure table).
- [ ] Run, commit `feat(broker): one chain per session for waking it and delivering to it (#75)`.

### Task 5: The inbox uses the shared chain; `ensureLive` wakes an active row with no adapter
**Files:** `src/core/inbox/delivery.ts`, `src/core/inbox/delivery.test.ts`
- [ ] Test: with an injected chain, an inbox pass and a `chain.run` for the same session run in order; `ensureLive` of an active row whose `adapterState` is `"none"` calls `resumeSuspended` (today it waits 60 s and fails).
- [ ] Implement; existing inbox tests stay green.
- [ ] Commit.

### Task 6: Worktree: branch at Send, fresh checkout at wake
**Files:** `src/core/worktree/manager.ts`, `manager.test.ts`, `src/core/session-manager/session-store.ts`
- [ ] Tests: `deriveSessionBranch` avoids names already used by rows; `createSessionBranch(repo, branch, base)` creates the ref; `ensureWorktreeAt` on a missing dir checks the branch out, runs `.worktreeinclude` copies and the setup hook, and returns `{ created: true }`; a dir that exists but is not in `git worktree list` is removed and re-made; an existing healthy worktree returns `{ created: false }` and runs nothing.
- [ ] Implement; `setBaseCommits` on the session store.
- [ ] Commit.

### Task 7: `createAsleep`
**Files:** create `src/core/session-manager/born-asleep.ts`, test `born-asleep.test.ts`
- [ ] Tests: a worktree start registers a suspended row with the future worktree path, `repo_root`/`base_branch`/`session_branch`, `agent_home` = `join(STATE_DIR,"agents",kind,homeDirName(name))`, `account`, model/effort/permission mode, `connected: false`, and the branch exists; a non-repo folder registers in the folder itself; two quick creates get distinct branches; a pre-made worktree (council) is used as is with base commits captured now; a taken name is uniquified.
- [ ] Implement.
- [ ] Commit.

### Task 8: Wire it: doors, launcher, web, Telegram, drafts, Stop/archive/kill, "starting"
**Files:** `src/main.ts`, `src/core/session-manager/create-session.ts`, `src/channels/web/index.ts`, `src/core/session-manager/agent-state-frame.ts`; tests: `create-session.test.ts` (launcher answers before the wake; first message goes on the chain; doors await it), `src/channels/web/spawn-first-attachments.test.ts` (client id), a real-broker regression test (fixture claude) for launcher + spawn_session + a message to a dozing session.
- [ ] Tests first for each behaviour named above; then the wiring.
- [ ] Commit in small steps (launcher; web/Telegram log-first + chain; drafts; Stop/archive/kill; starting overlay in the snapshot).

### Task 9: Apps
**Files:** `apps/shared/.../proto/Frames.kt`, `state/HostStore.kt`, `state/HostReducer.kt`, `net/BrokerApi.kt`, `apps/ui/.../chat/ChatPanel.kt`; tests in `apps/shared/src/commonTest` (reducer upsert by id; send carries a `web-<uuid>`; the create request carries the first message's id) and `apps/ui` (Starting… label).
- [ ] Tests, then implementation; Gradle under the heavy lock.
- [ ] Commit.

### Task 10: Verify
- [ ] Full broker suite (compare failures with `dev`), core tests, Kotlin tests for touched modules.
- [ ] Shadow broker (`~/.mux/previews/perf75/start.sh`): launcher starts for Claude and Codex (answer < 1 s, first reply), a message to a dozing session, two quick messages, Stop and archive during a start, a restart mid-start, every agent kind's never-started resume, files/git/terminal panes before the worktree exists.
- [ ] Report ready-to-merge to Tally (not pushed).
