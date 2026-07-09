# Windows/Linux Desktop Client — Milestone 3 (Editor) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "Editor — arrives in M3" placeholder with the real editor: KCEF (embedded Chromium) hosting the SAME committed CodeMirror bundle iOS/Android ship (`cm6.js` + `index.html`), a native Compose file tree/tabs/search ported from Android, save/stale/fs-watch wiring, and tappable chat file-paths opening the editor at line — per the M3 section of the spec.

**Architecture:** A `DesktopEditorEngine` drives one KCEF browser per editor panel: Kotlin→JS via `executeJavaScript` over the bundle's 12 `window.cm*` globals; JS→Kotlin via KCEF's `cefQuery` message router behind INJECTED shims (`window.AndroidEditor.*` + `window.webkit.messageHandlers.lsp` → cefQuery) so the committed bundle runs UNMODIFIED. Everything around the WebView (EditorState, tabs, tree, search, stale banner, native-fallback editor) is a port of `apps/android/.../editor/`. The bundle ships into desktop resources via a gradle Copy task from `apps/android/src/main/assets/editor/` (single committed source of truth, no Android change).

**Scope decisions:** LSP, DiffView, and the markdown-preview toggle are EXPLICITLY DEFERRED to M4+ (Android has them; the M3 core is the editing surface — the deferrals get TODO(M4) markers and a note in the M3 close report). The bundle's own font-zoom (Ctrl+/−/0 + pinch, 10-24px default 13) comes free; desktop persists it.

**Tech Stack:** `dev.datlag:kcef:2025.03.23` (catalog-pinned since M1; ARCHIVED upstream — works today, JetBrains-JCEF migration noted in the spec), jogamp repo (already declared). KCEF natives download at first init (~100-150MB, from JetBrainsRuntime GitHub releases; pin `jbr-release-17.0.10b1087.23` for JDK-17 hosts).

---

## Ground rules

All M1/M2 rules hold (standard gradle invocation with /home/ahmet/.cache logs + TMPDIR; Xvfb :77 + `SKIKO_RENDER_API=SOFTWARE`; paired config at XDG_CONFIG_HOME=/home/ahmet/.cache/smx-test-config; xwd+Pillow screenshots; NO xdotool — env hooks + ui-state.json pre-writes; never restart the broker; snake_case tests; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`). M2 additions that matter here:
- **KCEF is a heavyweight AWT child like SwingPanel** — same rules: `KeepAlivePanel` composable (0×0) to hide, no Compose sibling paints above it, status/error UI in lightweight strips beside it, `key(...)` around anything owning an unkeyed `remember`.
- **CEF under Xvfb needs `--no-sandbox --disable-gpu`** (add `--disable-gpu-compositing` if artifacts appear). Init AFTER the Compose window exists (init-before-compose freeze is a known CMP issue) from a LaunchedEffect on Dispatchers.IO.
- KCEF needs JVM args `--add-opens java.desktop/sun.awt=ALL-UNNAMED` + `--add-opens java.desktop/java.awt.peer=ALL-UNNAMED` (compose.desktop.application.jvmArgs — affects :desktop:run AND jpackage later; NOT unit tests).
- `runApi` is the house pattern for new broker calls in DesktopAppState.
- Suite baseline at M3 start: desktop 129 / shared jvmTest 292 / android compile green.
- The throwaway session talebe-dummy-excel is GONE (killed in M2 verification). For live write-tests, SPAWN a fresh disposable session via the broker REST (`POST /sessions` body `{name:"desktop-editor-verify", workdir:"<a fresh temp dir you create with a few sample files>", agent:"claude"}` with the bearer token) and KILL it when done. Do not touch real project sessions' files.

---

### Task 1: Bundle shipping + KCEF init smoke (the risk gate)

**Files:** Modify `apps/desktop/build.gradle.kts` (kcef dep + jvmArgs + a `Copy` task wiring `apps/android/src/main/assets/editor/{index.html,cm6.js}` into `processResources` under `editor/`); Create `apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/KcefRuntime.kt` (init-once holder: `KCEF.init` with installDir = `<config dir>/kcef-bundle`, pinned JBR release, `--no-sandbox --disable-gpu`, progress + error + restart-required callbacks surfaced as a StateFlow<KcefState> of Downloading(pct)/Ready/Error(msg)); Create a probe test.

- [ ] Dep + Copy task + resources land (`build/resources/main/editor/cm6.js` ≈1.16MB present after processResources — assert in a unit test reading the classpath resource).
- [ ] `KcefRuntime` implemented; extraction helper that copies the two classpath resources into `<config>/editor-web/` on first run (CEF loads `file://` from a real path, not the classpath) — version-stamp the dir (bundle byte-size or hash) so a new cm6.js re-extracts.
- [ ] LIVE PROBE (the whole point of this task): headless run where the editor pane region initializes KCEF and loads `file://<config>/editor-web/index.html` in a plain CEF browser inside a temporary pane body — screenshot must show the editor's dark background (#282c34) with a CodeMirror caret/gutter (cmInit not yet called → whatever the bundle renders bare; even a blank dark page with no JS errors in the CEF log is the pass bar — document exactly what rendered). If KCEF init fails headlessly, iterate on CEF switches BEFORE any other M3 work proceeds; escalate BLOCKED if Chromium fundamentally can't run on this box.
- [ ] First-init downloads ~100-150MB — do it once in the probe; the installDir persists for later tasks. Commit.

### Task 2: `DesktopEditorEngine` — the KCEF bridge (probe-verified)

**Files:** Create `apps/desktop/.../editor/DesktopEditorEngine.kt` + `EditorBridgeShims.kt`; probe additions; unit tests for the pure parts.

- Mirror Android `EditorEngine.kt`'s surface: `setDocument(path, content)`, `revealLine(line, endLine)`, `setFontSize`, `setLineWrap`, `getContent(cb)`, `getScrollTop/setScrollTop`, `ready: StateFlow<Boolean>`, callbacks `onChange/onSave/onReady/onFontSize` — implemented over `browser.executeJavaScript` with proper JS string quoting (kotlinx-serialization `JsonPrimitive(str).toString()` — NOT hand-rolled escaping) calling the 12 `cm*` globals (contract in the recon: cmInit/cmSetContent/cmGetContent/cmSetLanguage/cmSetLineWrap/cmSetFontSize/cmGetScrollTop/cmSetScrollTop/cmRevealLine + LSP trio unused-for-now).
- JS→Kotlin: a `CefMessageRouter` on the KCEF client handling `cefQuery` payloads `{fn: "onChange"|"onSave"|"onReady"|"onFontSize"|"lspOut", arg: string}`; INJECT after page load (onLoadEnd) the shim JS defining `window.AndroidEditor = {onChange,onSave,onReady,onFontSize}` + `window.webkit = {messageHandlers:{lsp:{postMessage}}}` all routing through cefQuery — the committed bundle runs UNMODIFIED (Android precedent: it injects an LSP shim the same way). ⚠️ TIMING: the bundle calls `bridge()` lazily per event and `onReady` fires from cmInit — inject shims BEFORE calling cmInit (onLoadEnd → inject → cmInit), and have the shim queue-or-drop gracefully if cefQuery isn't ready. cmGetContent returns a value — executeJavaScript is fire-and-forget; use KCEF's `evaluateJavaScript(expr) { result }` for reads (KCEFBrowser API) and document the async shape.
- Threading: CEF callbacks arrive on CEF threads — marshal to the EDT/Main before touching state (Dispatchers.Swing). Document per the M2 threading-doc convention.
- LIVE PROBE extension: load a real file's text via cmInit, cmGetContent round-trips it, onChange fires when text is mutated via an injected JS edit, cmRevealLine scrolls (screenshot with a tall file). This is the T8-analog proof for the bridge; unit tests cover quoting/payload parsing (pure).

### Task 3: EditorState + tabs + tree + search ports (TDD — iOS tests are the parity reference)

**Files:** `apps/desktop/.../editor/EditorState.kt`, `FileTree.kt`, `EditorTabs.kt`, `EditorSearchBar.kt` (ports of the Android files, keep-in-sync headers); Tests: PORT `apps/iosApp/SupermuxTests/EditorStateTests.swift` semantics to Kotlin (`EditorStateTest.kt`) — open-appends-and-activates, no-duplicate-reopen, load-error, close-selects-neighbor, closing-inactive-keeps-active, isDirty, markChanged/isStale/reload — plus tree-node sorting (dirs-first) tests. These are the FIRST tests this state model has on any Kotlin platform; write them FIRST from the Swift file, then port the Android implementation against them.

### Task 4: EditorPanel assembly + fs wiring + SessionDetail swap

**Files:** `apps/desktop/.../editor/EditorPanel.kt` + `WebCodeEditor.kt` (native-fallback `BasicTextField` editor if the engine misses ready in 8s or KcefState=Error — port Android's), DesktopAppState additions (fs wrappers via runApi: fsListFiles/fsRead(Result)/fsWrite/fsSearch mirroring AppViewModel:791-798; `editorOpen/editorClose` sending the ClientFrame; reduce() gains the `FsChanged` branch feeding a new `fsChanges` flow), SessionDetail editorPane swap (+ header comment update), WorkspaceStateStore or a small prefs entry for `lineWrap`/`fontSize` (mirror the `cmux-editor-settings` keys).
- Desktop layout = Android's tablet arrangement (inline 192dp tree sidebar, no phone drawer). Header row: tree toggle, search field, save button (diff/preview buttons OMITTED — TODO(M4) markers).
- editor_open/editor_close on panel mount/dispose (fs-watch gate); FsChanged → markChanged → stale banner with Reload.
- KCEF panel hidden via `KeepAlivePanel`; `key(session.id)` wherever an unkeyed remember owns the engine; UI tests via an injectable `editorContent` seam (KCEF can't run under runComposeUiTest — the established template).

### Task 5: Chat file-path taps + zoom persistence + shortcuts

- Wire `onOpenFile` end-to-end: ChatPanel's threaded param (stubbed `{}` since M1) → SessionDetail/WorkspaceRoot handler → `toWorkdirRelativePath` → `PendingEditorOpen(path, line, endLine)` → editor pane on → `openFileAtLine` → `cmRevealLine` (Android ChatScreen:221/SessionWorkspaceDetail:174 pattern).
- onFontSize callback persists; persisted value pushed on init (never re-keys the engine — zoom must not reload the file).
- Ctrl+E workspace shortcut already toggles the pane (M1); confirm no conflicts with the bundle's Ctrl+/−/0 (they live inside CEF focus).
- UI tests for the pending-open flow via the seam.

### Task 6: M3 verification pass + report

- New env hook `SM_OPEN_FILE` (`<session-name>:<path>[:line]`) driving the real PendingEditorOpen path (documented like the others, off by default).
- Spawn `desktop-editor-verify` (fresh temp dir with a few sample files incl. a 200+ line one, git init optional), live checklist with screenshots (m3v-*.png): (1) editor pane opens with tree + tabs; (2) SM_OPEN_FILE at :150 → content + syntax highlighting + revealed line; (3) edit via injected JS + save → fsWrite lands (verify file content via the terminal hook or direct read) + dirty dot lifecycle; (4) stale banner: modify the file behind the editor (SM_TERM_INPUT `echo >> file`) → FsChanged → banner → Reload; (5) native-fallback path (force KcefState=Error via an env/flag) renders the BasicTextField fallback; (6) font zoom persisted across relaunch; (7) suites (desktop/shared/android) green; (8) kill the throwaway session, cleanup, plan tick, `docs(desktop): M3 plan executed`, report (incl. what M4 should know).

## Self-review notes
Spec M3 coverage: KCEF + shared bundle (T1/T2, bundle UNMODIFIED via injected shims — stronger than the spec's "same bridge contract" ask), file tree native (T3/T4), tappable paths→editor-at-line (T5), degraded error card (T4's native fallback — exceeds the spec's minimum), editor font-zoom persistence (T5). Deferred with markers: LSP / DiffView / markdown preview (M4+). Risk register: KCEF-under-Xvfb is the unknown → T1 is a hard gate before any other work; the archived-KCEF migration note stands in the spec.
