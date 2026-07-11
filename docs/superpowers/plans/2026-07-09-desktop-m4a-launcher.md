# Windows/Linux Desktop Client — Milestone 4a (New-Session Launcher) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the desktop app able to START a session. Replace the "New Session" no-op (menu + Ctrl+N + rail `+`) with a real launcher: project picker, worktree/base-branch, agent + model + thinking-effort pills, message field, attachment staging, spawn → first message. Ports `apps/android/.../session/SessionLauncherScreen.kt`. Also lands the two reusable foundations M4d (uploads) needs: a JVM `FileChunkSource` and a launcher prefs/draft file store.

**Architecture:** A `SessionLauncherScreen` composable taking broker access as suspend-lambdas (Android's injection style — no VM ref inside the composable), bound to new `DesktopAppState` methods. Android's `ModalBottomSheet` pickers become desktop `DropdownMenu`/`Popup`/`DialogWindow`. Android DataStore prefs become a JSON file store (precedent: `WorkspaceStateStore`/`EditorPrefs`). Attachments stage as `StagedUpload(FileChunkSource, name, mime)` and upload post-spawn via the shared `uploadResumable`, then the first message sends with the returned file_ids.

**Tech Stack:** Compose Desktop, shared `BrokerApi` (all methods exist: spawn/listProjects/validatePath/listModels/getReasoningLevels/getRepoInfo/previewCommands/uploadResumable + forge methods), shared `ChunkSource`/`ReasoningLevels`, `java.awt.FileDialog` for the picker.

---

## Ground rules

All prior-milestone rules hold (standard gradle invocation with /home/ahmet/.cache logs + TMPDIR; Xvfb :77 + `SKIKO_RENDER_API=SOFTWARE`; paired config XDG_CONFIG_HOME=/home/ahmet/.cache/smx-test-config; xwd+Pillow screenshots; NO xdotool — env hooks + ui-state pre-writes; never restart the broker; snake_case tests; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; touch ONLY apps/desktop unless a task says shared). Suite baseline at M4a start: desktop 231 / shared jvmTest 292 / android compile green.

- `runApi` (DesktopAppState) is the house wrapper for new broker calls (cancellation/SKIE-sentinel discipline).
- **DesktopAppState.spawn's response id can be BLANK** — fall back to matching `resp.name` against the session list (Android AppViewModel:593 pattern).
- The first-message handoff: Android uses `setPendingFirst` + ChatScreen-sends-on-open. Desktop has `DesktopComposer`/`ChatPanel` that send inline. Simplest desktop shape: after spawn + uploads, select the new session and inject the first message via the SAME path SM_SMOKE_SEND uses (`app.sendMessage(id, text, fileIds)`) — no pendingFirst plumbing needed. Document the divergence.
- Forge omnibox (clone/create repo) is LOWER priority: land the local-project path first (listProjects + validatePath + a manual-path entry); gate the forge clone/create UI behind a `TODO(M4-forge)` if it balloons — document if deferred. The local-project + worktree + agent/model/effort + message + attach path is the must-ship.

---

### Task 1: JVM `FileChunkSource` (TDD)

**Files:** Create `apps/desktop/.../upload/FileChunkSource.kt` + test. (Shared `ChunkSource` interface: `val size: Long; fun read(offset: Long, len: Int): ByteArray` — synchronous, no coroutine capture.)

- [x] Test first: a temp file of known bytes → `size` matches; `read(0, n)`/`read(offset, n)`/read past EOF (clamped) return exact bytes; concurrent reads from two threads are safe (open a fresh `RandomAccessFile`/`FileChannel.read(ByteBuffer, position)` per read, mirroring Android's ContentResolverChunkSource fresh-FD-per-read — NOT a shared mutable position). Run → FAIL.
- [x] Implement over `java.io.File` (FileChannel positional read; clamp len to remaining). Green. Commit `feat(desktop): FileChunkSource — java.io.File ChunkSource for uploads`.

### Task 2: Launcher prefs/draft file store (TDD)

**Files:** Create `apps/desktop/.../session/LauncherStore.kt` (+ `LauncherPrefs`/`LauncherDraft` desktop DTOs mirroring Android's `session/LauncherState.kt`) + test.

- [x] `LauncherPrefs(agent, models: Map<String,String>, reasoningLevels: Map<String,String>)`, `LauncherDraft(workdir?, useWorktree=true, baseBranch="", text="")`, both `@Serializable`. `LauncherStore(path)` with `loadPrefs/savePrefs/loadDraft/saveDraft/clearDraft` → JSON under the config dir (sibling of ui-state.json/editor-settings.json). Atomic write + corrupt-file-tolerant load (the EditorPrefs/WorkspaceStateStore pattern). Tests: round-trip, defaults on missing, corrupt→default, clearDraft. Commit.

### Task 3: `DesktopAppState` launcher + spawn wrappers

**Files:** Modify `apps/desktop/.../state/DesktopAppState.kt`.

- [x] Add via `runApi` (getOrNull-degrading like Android's launcher helpers): `listProjects()`, `validatePath(path)`, `launcherModels(agent)`, `launcherReasoning(agent, model?)`, `launcherRepoInfo(workdir)`, `launcherCommands(agent, workdir)`; `uploadResumable(session, source, name, mime, kind?, onProgress): String?` (returns file_id, mirrors fsRead's discipline). And `createSessionWithFirstMessage(workdir, agent, model?, reasoningLevel?, text, staged: List<StagedUpload>, worktree, baseBranch?): String?` — validate path → `api.spawn(SpawnRequest(...))` → resolve id (blank → find by name in sessions) → upload each staged file via uploadResumable → return id; the CALLER then selects the session and sends `text` with the file_ids. Unit-test the id-resolution + spawn-request-shape logic against a MockEngine `BrokerApi` (the apiOverride seam) where feasible; document what's only live-verified.

### Task 4: The launcher screen (port)

**Files:** Create `apps/desktop/.../session/SessionLauncherScreen.kt` (+ `AgentPill`, `WorktreePill`, `WorktreeDialog`, `ProjectPicker` as private/sibling composables). Reuse desktop chat pills if present (ModelPill/EffortPill exist? check `chat/` — port from Android `chat/Pickers.kt` if missing).

- [x] Port SessionLauncherScreen's structure (the composer card: project dropdown, worktree pill → dialog, agent pill, model pill, effort pill, message field, staged-attachment chips + an "Attach" button using `java.awt.FileDialog`, round send). Desktop deltas: ModalBottomSheet → DropdownMenu/DialogWindow; media pickers → a single FileDialog (multi-select) building `FileChunkSource`s; drag-and-drop optional (`Modifier.dragAndDropTarget` — nice-to-have, TODO if it balloons). Keep the `launcherRestoring` gate + `lastSeenAgent`/`lastSeenWorkdir` settle-vs-change logic (prevents a restore from resetting model/branch) — this is the subtle part; port it faithfully and test the pure bits (extract a `launcherReset(prev, next)` helper if it clarifies).
- [x] Draft persistence: debounced ~400ms save + flush on dismiss, cleared on successful submit (LauncherStore). Prefs (agent+per-agent model/effort) persist forever.
- [x] Voice/mic: OMIT on desktop launcher for now (TODO(M5) — dictation is an M5 surface); no MicButton.

### Task 5: Wire into the app shell

**Files:** Modify `apps/desktop/.../Main.kt` (menu New Session), `workspace/WorkspaceRoot.kt` (the onNewSession lambda + a launcher overlay/route), `SessionsRail.kt`/`SessionListPanel.kt` (the `+` already calls onNewSession).

- [x] onNewSession opens the launcher (a full-pane overlay in WorkspaceRoot, or a DialogWindow — pick the cleaner; Android navigates to a route, desktop can overlay the workspace). On submit → createSessionWithFirstMessage → select the new session + send the first message + close the launcher. Ctrl+N + menu + rail `+` all reach it. Escape/close cancels (draft persists).
- [x] UI tests via seams (broker lambdas faked): launcher renders the card; agent change resets model; a submit calls createSessionWithFirstMessage with the right args and closes; draft persists across open/close.

### Task 6: Live verification + report

- [x] Add `SM_LAUNCH_TEST` env hook (documented, off by default): after settle, open the launcher pre-filled (workdir=a temp dir, agent=claude, message="M4a launcher smoke …") and submit — drives the real spawn path headlessly. Pipe-delimited `<workdir>|<agent>|<message>[|<attach>]` (message may contain colons/spaces); blank message = open-only (draft/prefs screenshots); `SM_LAUNCH_PAUSE_MS` holds the launcher open first. Commit `test(desktop): SM_LAUNCH_TEST env hook`.
- [x] Live checklist (screenshots m4av-*.png, live broker): (1) launcher card renders (project dropdown, agent/model/effort pills, message field, attach + send) — m4av-1; (2) project dropdown source lists 28 real projects (GET /projects, the endpoint listProjects calls); (3) SM_LAUNCH_TEST spawned a REAL session (m4av-launch-svqkkq, id ed586500) in a fresh temp workdir, it appeared in the sidebar, the first message sent and the agent replied "launched" — m4av-3, broker-confirmed; (4) a temp file staged via the hook's 4th field uploaded post-spawn and the attachment chip rode the first message to the agent — m4av-3; (5) draft persists across a launcher close/reopen — m4av-5 (RESTORE live; clear-on-submit is covered by T2 store + T4 UI tests since the hook bypasses SessionLauncherScreen.doSubmit where onClearDraft lives); (6) prefs (agent) persist across relaunch — m4av-6 (pre-written prefs.agent=codex restored into the pill; SAVE is pill-tap-driven → T2/T4). Spawned session killed via broker DELETE (204, archived). Suites green (desktop 277 / shared 292 / android compile). Plan ticked; `docs(desktop): M4a plan executed`.

## Self-review notes
Spec coverage: the launcher is the M4 "full-parity" must-ship (you can't start a session without it). FileChunkSource + LauncherStore + the upload wrapper are foundations M4d reuses. Forge clone/create may defer (documented). Voice defers to M5. The subtle restore-vs-change settle logic is the main port risk — test its pure core.
