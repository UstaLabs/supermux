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

- [ ] `suspend fun archived(): List<ArchivedDto>` (runApi ?: emptyList), `suspend fun resume(id): Boolean` (runCatching→isSuccess kickoff — the resumed session arrives via a session_added/snapshot frame; check the real BrokerApi.resume return), and expose `archivedLogs(sessionId): List<LogEntry>` if not already public. Match Android AppViewModel:677-678. MockEngine-test the method/path shapes. Confirm the real ArchivedDto shape (id/name/workdir/agent/killed_at?/repo_root?).

### Task 2: ArchivedScreen + read-only chat + resume (port)

**Files:** Create `apps/desktop/.../session/ArchivedScreen.kt` (screen + a read-only ArchivedChatView) + wire an entry point.

- [ ] `ArchivedScreen(archived: List<ArchivedDto>, home, onBack, onResume, loadLogs, ...)`: a list grouped/filtered by project (shared `archivedProjects` for the filter menu — "All projects" + per-project "label (count)" with a check on the selected; `filterArchivedByProject`), each row shows name + relative time (relTime) + a per-row project label (formatWorkdir). A search field (client-side name/path filter). Tapping a row opens `ArchivedChatView(sessionId, name, loadLogs, onResume, onBack)` — a read-only Timeline (mergeTimeline over archivedLogs, no composer) + a Resume button. Resume → onResume(id) → the session comes back live + close the archived screen.
- [ ] Entry point: a File menu item "Archived…" (Main.kt) + optionally a sidebar affordance — opens the ArchivedScreen overlay (like the launcher). Load `app.archived()` on open.
- [ ] Reuse the desktop Timeline for the read-only view (no onOpenFile editor wiring needed — pass {}; or wire it if cheap). Desktop deltas from Android: ModalBottomSheet filter → DropdownMenu; nav route → overlay.
- [ ] UI tests via seams (faked archived list + loadLogs): the list renders grouped; the project filter narrows it (assert filterArchivedByProject applied); search narrows by name; opening a row shows the read-only transcript (no composer); Resume fires onResume + closes. Extract any pure bits (the search predicate) + test.

### Task 3: Live verification + report

- [ ] `SM_ARCHIVED=1` hook (Main.kt, documented): open the ArchivedScreen on start (loads real archived()). Optionally `SM_ARCHIVED_RESUME=<name>` to resume a named archived session.
- [ ] Live checklist (m4ev-*.png): (1) archive a throwaway session (spawn one, then archive/kill it — a killed session archives); (2) SM_ARCHIVED opens the screen → the real archived list renders, grouped by project, with the project filter + search; screenshot; (3) the throwaway appears; open it → read-only transcript renders (its messages, no composer); (4) Resume it → it returns to the live sidebar + the archived screen closes; verify via the live session list / GET /sessions. Kill the throwaway again after. Suites green. Plan tick, `docs(desktop): M4e plan executed`, report incl. what M4f-g inherit.

## Self-review notes
Spec coverage: archived list + project filter + per-row label + read-only transcript + resume = the archived-sessions parity. Reuses shared archivedProjects grouping + the desktop Timeline (no new rendering). Resume live-tested on a self-archived throwaway (non-destructive). Smaller milestone — mostly a list screen + a read-only Timeline reuse.
