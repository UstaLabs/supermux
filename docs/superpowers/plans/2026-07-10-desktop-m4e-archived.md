# Windows/Linux Desktop Client — Milestone 4e (Archived Sessions) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Archived-sessions screen: a searchable/project-filtered list of archived sessions with a per-row project label, a read-only chat view of an archived session's transcript, and Resume. Ports `apps/android/.../settings/MoreScreens.kt`'s `ArchivedScreen` + `ArchivedChatScreen`.

**Architecture:** A `ArchivedScreen` overlay (like the launcher overlay) reachable from a menu item / an entry point in the app. Uses `DesktopAppState.archived()` (→ List<ArchivedDto>), `resume(id)`, `archivedLogs(sessionId)` (already exists for ensureMessagesLoaded). The project filter uses shared `archivedProjects(sessions, home)` + `filterArchivedByProject`. Read-only chat reuses the existing Timeline rendering (mergeTimeline over the archived logs).

**Tech Stack:** Compose Desktop DropdownMenu (project filter), shared `BrokerApi.archived/resume/archivedLogs` + `ArchivedDto` + `session/ArchivedProjects.kt` (`archivedProjects`/`filterArchivedByProject`/`ArchivedProject`), the existing desktop Timeline.

---

## Ground rules

All prior-milestone rules hold (standard gradle invocation with /home/ahmet/.cache logs + TMPDIR; Xvfb :77 + `SKIKO_RENDER_API=SOFTWARE`; paired config; xwd+Pillow; NO xdotool — env hooks; never restart the broker; snake_case tests; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; touch ONLY apps/desktop/src, NEVER build). Suite baseline at M4e start: desktop 366 / shared jvmTest 292 / android compile green.

- `runApi` for new broker calls. `archived()`/`resume()`/`archivedLogs()` — archived + resume may be new wrappers on DesktopAppState (archivedLogs exists privately for ensureMessagesLoaded — expose or reuse).
- Reuse shared `archivedProjects`/`filterArchivedByProject` (commonMain) — do NOT reimplement the grouping. Reuse the desktop Timeline/mergeTimeline for the read-only transcript.
- Resume is non-destructive (un-archives + reconnects). Live-verify it on a session YOU archived (archive a throwaway, resume it back). Do NOT resume a random real archived session unexpectedly (it'll spin up an agent) — use a throwaway.
- No snackbar host (recurring M4-polish gap) — resume feedback is the session appearing back in the live list + closing the archived screen.

---

### Task 1: DesktopAppState archived/resume wrappers (TDD)

**Files:** Modify `apps/desktop/.../state/DesktopAppState.kt` + test.

- [x] `suspend fun archived(): List<ArchivedDto>` (runApi ?: emptyList), `suspend fun resume(id): Boolean` (runCatching→isSuccess kickoff — the resumed session arrives via a session_added/snapshot frame; check the real BrokerApi.resume return), and expose `archivedLogs(sessionId): List<LogEntry>` if not already public. Match Android AppViewModel:677-678. MockEngine-test the method/path shapes. Confirm the real ArchivedDto shape (id/name/workdir/agent/killed_at?/repo_root?).

### Task 2: ArchivedScreen + read-only chat + resume (port)

**Files:** Create `apps/desktop/.../session/ArchivedScreen.kt` (screen + a read-only ArchivedChatView) + wire an entry point.

- [x] `ArchivedScreen(archived: List<ArchivedDto>, home, onBack, onResume, loadLogs, ...)`: a list grouped/filtered by project (shared `archivedProjects` for the filter menu — "All projects" + per-project "label (count)" with a check on the selected; `filterArchivedByProject`), each row shows name + relative time (relTime) + a per-row project label (formatWorkdir). A search field (client-side name/path filter). Tapping a row opens `ArchivedChatView(sessionId, name, loadLogs, onResume, onBack)` — a read-only Timeline (mergeTimeline over archivedLogs, no composer) + a Resume button. Resume → onResume(id) → the session comes back live + close the archived screen.
- [x] Entry point: a File menu item "Archived…" (Main.kt) + optionally a sidebar affordance — opens the ArchivedScreen overlay (like the launcher). Load `app.archived()` on open.
- [x] Reuse the desktop Timeline for the read-only view (no onOpenFile editor wiring needed — pass {}; or wire it if cheap). Desktop deltas from Android: ModalBottomSheet filter → DropdownMenu; nav route → overlay.
- [x] UI tests via seams (faked archived list + loadLogs): the list renders grouped; the project filter narrows it (assert filterArchivedByProject applied); search narrows by name; opening a row shows the read-only transcript (no composer); Resume fires onResume + closes. Extract any pure bits (the search predicate) + test.

### Task 3: Live verification + report

- [x] `SM_ARCHIVED=1` hook (Main.kt, documented): open the ArchivedScreen on start (loads real archived()). `SM_ARCHIVED_OPEN=<name>` additionally seeds a new one-shot `ui.forceArchivedOpenFor` (WorkspaceRoot/ArchivedScreen, Compose-tested) so a named archived row's read-only transcript renders with no click — added beyond the original two-hook sketch because live-verifying the transcript view (checklist item b) needed it. `SM_ARCHIVED_RESUME=<name>` resolves the named row and drives the same `onResume` path (`app.resume(id)` fire-and-forget + close). Committed ahead of the checklist (`eeb9f3f`, `c1657d9`).
- [x] Live checklist (screenshots in `/home/ahmet/.cache/m4ev-shots/`, not committed — ephemeral verification artifacts): **(a) PASS** — spawned a throwaway (`m4ev-archived-throwaway`, workdir `/home/ahmet/.cache/m4ev-workdir`), sent it a message, archived it via `DELETE /sessions/<id>`; `GET /archived-sessions` returned **477** real archived sessions across **~180 distinct projects** (confirmed by both the API response and the on-screen per-row project labels — home dirs like `~/projects/supermux`, `~/.mux/workspace/*`, dozens of `~/.mux/worktrees/*` checkouts). `SM_ARCHIVED=1` → `m4ev-a-open.png`: the list renders immediately with the throwaway at the top ("now"), the "All projects" filter control, and the search field — no empty-state flash was observed (the loading→list transition is additionally covered by `loading_shows_a_spinner_not_the_empty_text`). **(b) PASS** — `SM_ARCHIVED_OPEN=m4ev-archived-throwaway` → `m4ev-b-transcript.png`: the read-only `ArchivedChatView` renders both real transcript messages (inbound prompt + outbound "OK"), header shows name + "archived" + Resume, and there is no composer. **(c) SUBSTITUTED (CODE-VERIFIED)** — no pointer under Xvfb to type into the search field live; `search_narrows_by_name` drives `OutlinedTextField` via Compose's `performTextInput` (a real semantics-level keyboard-event dispatch, not a mock) and asserts the list narrows — same substitution class as M4d's drag-drop. **(d) PASS** — `SM_ARCHIVED_RESUME=m4ev-archived-throwaway`: log showed `rx SessionAdded` then `resumed … closed the overlay`; `GET /sessions` confirmed the throwaway live again (`connected:true`, no `killed_at`) and `GET /archived-sessions` no longer listed it; `m4ev-d-resumed.png` shows the overlay closed and the ordinary workspace restored. **(e) SUBSTITUTED (CODE-VERIFIED + API-confirmed)** — ≥2 projects trivially satisfied (~180, see (a)); opening the `DropdownMenu` itself needs a pointer, so `project_filter_narrows_to_the_selected_project` (a real Compose click-driven test, not a mock) stands in for the live click. Cleanup: throwaway re-archived via `DELETE /sessions/<id>` (confirmed absent from `GET /sessions`, present in `GET /archived-sessions`), `/home/ahmet/.cache/m4ev-workdir` removed, `ui-state.json` at rest (no stale `selectedId`), orphaned Xvfb `:77` (pre-existing, not spawned by this pass) killed. Suites green with `--rerun-tasks`: desktop **394** (393 baseline + 1 new `force_open_id_…` test), shared jvmTest **292** (unchanged), android `compileDebugKotlin` green. Plan ticked, `docs(desktop): M4e plan executed` committed. M4e is CLOSED — see the report for what M4f/M4g inherit.

## Self-review notes
Spec coverage: archived list + project filter + per-row label + read-only transcript + resume = the archived-sessions parity. Reuses shared archivedProjects grouping + the desktop Timeline (no new rendering). Resume live-tested on a self-archived throwaway (non-destructive). Smaller milestone — mostly a list screen + a read-only Timeline reuse.
