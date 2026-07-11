# Windows/Linux Desktop Client — Milestone 4b (Finish Flow) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the Finish flow to the desktop app: a Merge / Open PR / Keep / Discard sheet with the up-front Run-tests/Skip choice, the readiness card, running/outcome states (15 outcome variants), and the async `finish_job` progress. Also lands the two reducer branches this needs — `finish_job` and `session_git` — which desktop currently no-ops (unblocks M4c's git badge live-update too). Ports `apps/android/.../chat/FinishSheet.kt` + `FinishChoices.kt`.

**Architecture:** A `FinishButton` (shown when `session.session_branch != null`) opens a `FinishDialog` (desktop `DialogWindow`/overlay — Android's ModalBottomSheet). The dialog is a pure 3-state machine off the session's `FinishJobDto` (menu / running / outcome). New `DesktopAppState`: a `finishJobs: StateFlow<Map<String,FinishJobDto>>` seeded from `SessionInfo.finish_job` in the Snapshot reducer + updated by the `FinishJobFrame`, plus reducer handling for `session_git` (live `SessionInfo.git` update) and wrappers `finish/finishReadiness/verifySuggest/verifySave/clearFinishJob`. All DTOs are shared and already available.

**Tech Stack:** Compose Desktop, shared `BrokerApi.finish/finishReadiness/verifySuggest/verifySave` + `FinishResult/FinishReadiness/VerifySuggestResult/VerifySaveResult` DTOs + `FinishJobDto`/`ServerFrame.FinishJobFrame`/`ServerFrame.SessionGit` (all exist).

---

## Ground rules

All prior-milestone rules hold (standard gradle invocation with /home/ahmet/.cache logs + TMPDIR; Xvfb :77 + `SKIKO_RENDER_API=SOFTWARE`; paired config; xwd+Pillow; NO xdotool — env hooks; never restart the broker; snake_case tests; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; touch ONLY apps/desktop/src, NEVER apps/desktop/build). Suite baseline at M4b start: desktop 277 / shared jvmTest 292 / android compile green.

- `runApi` is the house wrapper for new broker calls. But `finish` is a fire-and-forget kickoff returning `status:"running"` — the async job progress arrives via `FinishJobFrame`; mirror Android's `runCatching{api.finish}.isSuccess → onKickoff`.
- The reducer currently has `finish_job`/`session_git`/`lsp_*`/`display_*` as `else {}` no-ops — this task fills `finish_job` + `session_git` ONLY (lsp/display stay for M4g/M5). Seed both from the Snapshot the way Android does (SessionInfo carries `finish_job` and `git` per session).
- **DANGEROUS-ACTION NOTE for live verification (T4):** `finish` with action=discard/merge MUTATES git. In T4, only run finish against a THROWAWAY session you spawned in a temp git repo — NEVER a real project session. Prefer testing the readiness card + the menu render + a `keep` (non-destructive) action live; merge/discard can be exercised in a scratch git repo you create. Document exactly what you ran.

---

### Task 1: Reducer — finish_job + session_git branches (TDD)

**Files:** Modify `apps/desktop/.../state/DesktopAppState.kt` + `DesktopAppStateReducerTest.kt`.

- [x] Add `_finishJobs: MutableStateFlow<Map<String,FinishJobDto>>` + public `finishJobs`. In the Snapshot reducer branch, seed `_finishJobs` from each `SessionInfo.finish_job` (non-null). Add a `FinishJobFrame` reducer branch: update `_finishJobs[session]=job` AND write it back onto the `SessionInfo.finish_job` in `_sessions` (Android AppViewModel:275 parity). Add a `SessionGit` branch: update the matching `SessionInfo.git` in `_sessions` (live badge update — replaces the `else{}` no-op). `clearFinishJob(id)` removes the entry (client-only). Tests (via the reduce() seam, no network): snapshot seeds finishJobs from session.finish_job; FinishJobFrame updates both finishJobs and the session's finish_job; SessionGit updates the session's git; clearFinishJob removes. Keep the else-branch for lsp_*/display_* (still deferred) — verify those don't crash.

### Task 2: Finish/verify broker wrappers

**Files:** Modify `apps/desktop/.../state/DesktopAppState.kt`.

- [x] `suspend fun finishReadiness(id): FinishReadiness?` (runApi), `verifySuggest(id): VerifySuggestResult?`, `verifySave(id, content): VerifySaveResult?`, and `finish(id, action, skipVerify?, commitFirst?, commitMessage?, prTitle?, prBody?, draft?, prRequiresGreen?): Boolean` (runCatching→isSuccess kickoff — the job progress comes via the frame, so this returns only whether the kickoff was accepted). Mirror the exact BrokerApi signatures (read them). MockEngine-test finish's request body shape + finishReadiness decode via the apiOverride seam where feasible.

### Task 3: FinishButton + FinishDialog port

**Files:** Create `apps/desktop/.../chat/FinishDialog.kt` (+ `FinishButton`, the readiness card, verify-choice rows, the 15 outcome bodies) + `chat/FinishChoices.kt` (port `canSkipTests(action, prRequiresGreen)` — PURE, unit-test it).

- [x] Port `FinishButton(finishJob, isUnacked, onClick)` — TextButton + unacked dot (red if failed else primary); shown only when `session.session_branch != null`. Port `FinishDialog` (Android FinishSheet) as a desktop Dialog: the 3-state machine off `finishJob` — null→Menu (loads finishReadiness → ReadinessCard + Merge/PR/Keep/Discard rows, each with the inline VerifyChoiceRows(showSkip=canSkipTests(action, readiness.prRequiresGreen)) → onRun(skipVerify=false)/onSkip(skipVerify=true)); running→Running (job.stage text, no swipe-dismiss); else→Outcome (per outcome.status recovery — port all 15: integrated/pr_opened/branch_published/tests_failed/sync_conflict/dirty_overlap/uncommitted/no_verify/push_auth_failed/push_rejected/nothing_to_do/kept/discarded/non_ff/else-fail, with the "Let the agent fix it" → sendMessage path where Android has it). Port `issueMessage(FinishResult)` (pure) + test it. Desktop deltas: ModalBottomSheet→Dialog; keep the bodies (pure Compose) faithful.
- [x] Wire FinishButton into `SessionDetail`'s header (the TODO(M4) list there — remove the "Finish button" mention from that comment; the git-badge-menu/session-links/overflow stay for M4c). FinishDialog opens on click; consumes finishJobs[session.id]; onFinish→app.finish(...); onClearJob→app.clearFinishJob; onSendToAgent→app.sendMessage.
- [x] UI tests via seams: the state machine (null→menu with readiness, running→running body, failed-outcome→recovery); canSkipTests + issueMessage pure tests; FinishButton visibility gate on session_branch; a Merge-with-Run-tests click calls onFinish(action="merge", skipVerify=false).

### Task 4: Live verification + report

- [x] Add `SM_FINISH_TEST=<session-name>` (documented, off by default): opens the FinishDialog for the named session (menu state, loads readiness) — screenshot the readiness card + action rows WITHOUT clicking a destructive action.
- [x] Live checklist (screenshots m4bv-*.png): (1) spawn a throwaway session in a temp GIT repo you `git init` with a commit + a branch (so session_branch != null and finishReadiness has real data); (2) FinishButton appears; (3) SM_FINISH_TEST opens the menu → readiness card shows real ahead/behind/dirty + the four action rows + the Run-tests/Skip choice; (4) OPTIONAL non-destructive: trigger a `keep` and screenshot the outcome; (5) session_git live badge update — do a git change in the temp repo (via SM_TERM_INPUT on that session) and confirm the sidebar git badge updates from the SessionGit frame. Kill the throwaway session + remove the temp repo. Suites green (desktop 277+new/shared/android). Plan tick, `docs(desktop): M4b plan executed`, report incl. what M4c inherits (session_git reducer now live; git-op wrappers still needed).

## Self-review notes
Spec coverage: Finish flow = a core "full parity" surface. The reducer branches (finish_job + session_git) are shared infrastructure M4c's git badge also needs — landing them here unblocks both. Destructive git actions are live-tested ONLY in a throwaway temp repo. The 15 outcome bodies are the bulk of the port — keep them faithful; issueMessage + canSkipTests are the pure testable cores.
