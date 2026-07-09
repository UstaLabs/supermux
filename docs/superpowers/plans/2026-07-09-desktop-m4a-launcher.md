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

- [ ] Test first: a temp file of known bytes → `size` matches; `read(0, n)`/`read(offset, n)`/read past EOF (clamped) return exact bytes; concurrent reads from two threads are safe (open a fresh `RandomAccessFile`/`FileChannel.read(ByteBuffer, position)` per read, mirroring Android's ContentResolverChunkSource fresh-FD-per-read — NOT a shared mutable position). Run → FAIL.
- [ ] Implement over `java.io.File` (FileChannel positional read; clamp len to remaining). Green. Commit `feat(desktop): FileChunkSource — java.io.File ChunkSource for uploads`.

### Task 2: Launcher prefs/draft file store (TDD)

**Files:** Create `apps/desktop/.../session/LauncherStore.kt` (+ `LauncherPrefs`/`LauncherDraft` desktop DTOs mirroring Android's `session/LauncherState.kt`) + test.

- [ ] `LauncherPrefs(agent, models: Map<String,String>, reasoningLevels: Map<String,String>)`, `LauncherDraft(workdir?, useWorktree=true, baseBranch="", text="")`, both `@Serializable`. `LauncherStore(path)` with `loadPrefs/savePrefs/loadDraft/saveDraft/clearDraft` → JSON under the config dir (sibling of ui-state.json/editor-settings.json). Atomic write + corrupt-file-tolerant load (the EditorPrefs/WorkspaceStateStore pattern). Tests: round-trip, defaults on missing, corrupt→default, clearDraft. Commit.

### Task 3: `DesktopAppState` launcher + spawn wrappers

**Files:** Modify `apps/desktop/.../state/DesktopAppState.kt`.

- [ ] Add via `runApi` (getOrNull-degrading like Android's launcher helpers): `listProjects()`, `validatePath(path)`, `launcherModels(agent)`, `launcherReasoning(agent, model?)`, `launcherRepoInfo(workdir)`, `launcherCommands(agent, workdir)`; `uploadResumable(session, source, name, mime, kind?, onProgress): String?` (returns file_id, mirrors fsRead's discipline). And `createSessionWithFirstMessage(workdir, agent, model?, reasoningLevel?, text, staged: List<StagedUpload>, worktree, baseBranch?): String?` — validate path → `api.spawn(SpawnRequest(...))` → resolve id (blank → find by name in sessions) → upload each staged file via uploadResumable → return id; the CALLER then selects the session and sends `text` with the file_ids. Unit-test the id-resolution + spawn-request-shape logic against a MockEngine `BrokerApi` (the apiOverride seam) where feasible; document what's only live-verified.

### Task 4: The launcher screen (port)

**Files:** Create `apps/desktop/.../session/SessionLauncherScreen.kt` (+ `AgentPill`, `WorktreePill`, `WorktreeDialog`, `ProjectPicker` as private/sibling composables). Reuse desktop chat pills if present (ModelPill/EffortPill exist? check `chat/` — port from Android `chat/Pickers.kt` if missing).

- [ ] Port SessionLauncherScreen's structure (the composer card: project dropdown, worktree pill → dialog, agent pill, model pill, effort pill, message field, staged-attachment chips + an "Attach" button using `java.awt.FileDialog`, round send). Desktop deltas: ModalBottomSheet → DropdownMenu/DialogWindow; media pickers → a single FileDialog (multi-select) building `FileChunkSource`s; drag-and-drop optional (`Modifier.dragAndDropTarget` — nice-to-have, TODO if it balloons). Keep the `launcherRestoring` gate + `lastSeenAgent`/`lastSeenWorkdir` settle-vs-change logic (prevents a restore from resetting model/branch) — this is the subtle part; port it faithfully and test the pure bits (extract a `launcherReset(prev, next)` helper if it clarifies).
- [ ] Draft persistence: debounced ~400ms save + flush on dismiss, cleared on successful submit (LauncherStore). Prefs (agent+per-agent model/effort) persist forever.
- [ ] Voice/mic: OMIT on desktop launcher for now (TODO(M5) — dictation is an M5 surface); no MicButton.

### Task 5: Wire into the app shell

**Files:** Modify `apps/desktop/.../Main.kt` (menu New Session), `workspace/WorkspaceRoot.kt` (the onNewSession lambda + a launcher overlay/route), `SessionsRail.kt`/`SessionListPanel.kt` (the `+` already calls onNewSession).

- [ ] onNewSession opens the launcher (a full-pane overlay in WorkspaceRoot, or a DialogWindow — pick the cleaner; Android navigates to a route, desktop can overlay the workspace). On submit → createSessionWithFirstMessage → select the new session + send the first message + close the launcher. Ctrl+N + menu + rail `+` all reach it. Escape/close cancels (draft persists).
- [ ] UI tests via seams (broker lambdas faked): launcher renders the card; agent change resets model; a submit calls createSessionWithFirstMessage with the right args and closes; draft persists across open/close.

### Task 6: Live verification + report

- [ ] Add `SM_LAUNCH_TEST` env hook (documented, off by default): after settle, open the launcher pre-filled (workdir=a temp dir, agent=claude, message="M4a launcher smoke — reply ok") and submit — drives the real spawn path headlessly.
- [ ] Live checklist (screenshots m4av-*.png, live broker): (1) Ctrl+N/menu opens the launcher card; (2) project dropdown lists real projects (listProjects); (3) submit via SM_LAUNCH_TEST spawns a REAL session in a fresh temp workdir, it appears in the sidebar, the first message sends and gets a reply; (4) attach a file via SM (stage a temp file through the hook) → it uploads post-spawn (verify the file_id reaches the agent / the upload landed); (5) draft persists across a launcher close/reopen; (6) prefs (agent/model) persist across app relaunch. Kill the spawned session after (disposable). Suites green (desktop 231+new / shared / android). Plan tick, `docs(desktop): M4a plan executed`, report incl. what M4d (uploads) inherits (FileChunkSource + the upload wrapper) and any forge deferral.

## Self-review notes
Spec coverage: the launcher is the M4 "full-parity" must-ship (you can't start a session without it). FileChunkSource + LauncherStore + the upload wrapper are foundations M4d reuses. Forge clone/create may defer (documented). Voice defers to M5. The subtle restore-vs-change settle logic is the main port risk — test its pure core.
