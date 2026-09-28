# Native editor M5: integration into the app, parity gaps, and cutover. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the CodeMirror/web-view editor inside the real supermux app (`apps/ui` + the four app modules) with
the new native editor. Close the parity gaps, delete CodeMirror and every web view in the same merge, and pass the
device parity check on web, the Mac, the Fold, the iPad and the iPhone.

**The map is the source of truth:** `docs/superpowers/notes/2026-09-28-native-editor-m5-integration-map.md`. It has
every usage site with file:line, the data flows, the deletion list, the wiring, and the parity checklist. **Read it
fully before starting.** Line numbers there are as of `3a7fb580`, so re-locate them.

**Decisions (Ahmet, 2026-09-28):**
- **Theme:** the editor follows the app's light/dark mode. It was always dark before.
- **Changes pane:** keep the host tree/list, the base selector, the "Submit review" bar and the walkthrough toggle.
  The **per-file diff moves onto the diff plugin**: inline by default, with a side-by-side toggle.
- **Grammars:** file types without a tree-sitter grammar open as plain text (the list is in the map, §5).
- **Folds:** Backspace next to a fold unfolds it first (the default).
- **Accessory bar:** on.

**Spec:** `docs/superpowers/specs/2026-09-25-native-editor-design.md` §8 (host) and §9 (removed at cutover).
**Module READMEs:** editor-core, editor-compose, editor-syntax native README ("What M5 needs"), and every
`apps/editor-plugins/*` README (lsp "M5 lifetime", view "M5's mapping", diff `DiffHost`).

**Not pre-verified.** Use TDD for new host code. Rewrite the `:ui` tests listed in the map §3.

## Ground rules
- Builds happen **only on the Mac**, in the private clone `~/work/native-editor-m3b`.
  - Never touch Ahmet's `~/supermux-desktop-kmp` hot-reload tree or the shared `~/projects/supermux-dev`.
  - The full app build is heavy, so run one job at a time.
- The branch is `mux/supermux-65`. Every commit ends with a blank line + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Don't delete CodeMirror until Part C.** Parts A and B build the new path next to the old one inside the branch,
  so each step stays runnable. Part C is the single cutover commit series.
- **Real app on devices:**
  - Android: `scripts/deploy-android.sh`. See `~/.mux/domains/infra.digest.md` for the keystore linking from a
    worktree and the `apksigner` check. ⛔ Never uninstall the app, because that wipes paired hosts.
  - iOS: the `~/muxdev-device.sh` pattern, with the private-checkout variant from `infra.md`.
  - Desktop app.
  - Web: a throwaway broker, the 2026-09-24 terminal recipe in `infra.md`.
  - Ahmet's real app data must survive.

---

## Part A: host integration

### A1. Build wiring
- **`:ui` dependencies:** `:editor-compose`, `:editor-syntax` and the plugins (see map §4). Use `api` where their types
  appear in `:ui` signatures.
- **iOS:** `SUPERMUX_EDITOR_SYNTAX_TABLES: "YES"` in `apps/iosApp/project.yml`, then xcodegen.
- **Web:**
  - Add a `stageSyntaxWasmAssets` task.
  - Stage the tables under `assets/editor-syntax/tables/<manifest-sha>/`.
  - Pass that path to `WasmBackend.load(tablesUrl = …)`.
  - Add `declare module "*.sesz"` in `src/types/assets.d.ts`.
  - **Fix the gzip ceiling.** Exclude the lazily loaded syntax wasm and the tables from the ceiling, or raise it with
    a stated reason. Check that it stays honest.
- **Desktop:** extend `rewritePackagedNativeDigests` to cover `dev/supermux/editor/syntax/natives/`. Verify with a
  packaged `.app` that the syntax library loads.
- **Android:** make a missing ABI for `libsupermux_syntax_jni.so` a build failure, not a warning.
- **Linux/CI:** when the syntax library is missing, fall back to plain text (no crash).
  - Add `editor-syntax/native/build.sh` steps to `.github/workflows/release.yml` like the terminal-core ones.
  - Load-test the Linux and Windows natives there.
- [ ] Commit: `build(ui): wire the native editor modules into the apps`.

### A2. Document + view store (the view outlives the pane)
- Keep one `EditorView` per open `Document`, next to `DocumentStore`, started with `view.startPlugins(hostScope)`.
  Panes only borrow views: `PaneHost` composes only the active tab. This keeps LSP and highlight alive across tab
  switches and the markdown preview.
- **Load:** `EditorState.create` gets the extensions: basics, history, highlight, fold, view settings, search,
  autocomplete, lint, lsp (when available), the accessory bar placement, and theme-following.
- **Change:** a view listener maps edits to the `Document`.
  - Don't copy the whole string per keystroke. `Document.content` becomes derived, or updated lazily for save/dirty.
  - Dirty = rope ≠ saved version.
- **Save:** `Mod-S` host binding plus the existing buttons. Normalise `\r\n` to `\n` on load, remember the file's line
  ending, and restore it on save (spec §8).
- **Reload from disk:** replace the document in one transaction with userEvent `disk`. The stale banner stays.
- **Two panes on one path:** two views mirror each other with userEvent `remote`, the same model as `LspWorkspace`.
- **Tab scroll:** `EditorScrollPosition` replaces `Document.scrollTop`. `captureOutgoingScroll` is no longer needed
  because the views stay alive.
- [ ] Commit: `feat(ui): per-document EditorView store`.

### A3. The panels on the new `Editor`
- **Android `EditorPanel`** and workspace **`FilePane`:** replace `EditorSurface` with `Editor(view, …)`, with
  `EditorAccessories` on phones and theme-following light/dark. The header, drawer, search overlay (filename
  search), tabs, stale banner, empty/error/loading states, markdown preview (now an overlay everywhere) and haptics
  stay as they are.
- **Reveal from chat path taps:** add a **centred** reveal option to `EditorEffects.scrollTo` (or a new
  `scrollIntoView(center = true)`), then select `line..endLine` and focus with `showKeyboard = false`.
- **Font size and wrap:** use view settings plus `fontSizeReporter` → `UiPrefs`, and update the view when the setting
  changes (see the view README).
- **Zoom badge:** show a transient "15px" badge in the host from the `onFontSize` callback (900 ms).
- **Syntax backend:** `NativeBackend` everywhere except web, which uses an async `WasmBackend` via a `Platform` seam
  that replaces `editorEngine`.
- [ ] Commit: `feat(ui): editor panels render the native editor`.

### A4. LSP transport
- Adapt `LspBridge` to `LspTransport`:
  - `send` = `rpcOut`
  - `incoming` = the session/server-filtered `pumpRpcIn`
  - `status` and **`connection` generation** from `lspStatus` + `open`. Bump the generation on every (re)connect and
    after a failed send.
- One `LspClient` per (session, serverId), created with `LspClientConfig`:
  - `rootUri` = the workdir URI
  - `onNavigate` maps a URI to a workdir path, then calls `fileOpener.open(path, line)`
  - `onWorkspaceEdit` applies edits to unopened files through `fsRead`/`fsWrite`
- Each view gets `client.plugin(uri, languageId)`.
- When the view's language changes (rename) or the markdown preview hides it, reconfigure.
- Tests: the adapter with scripted frames (quick reconnect; a failed send bumps the generation).
- [ ] Commit: `feat(ui): LSP over the broker via LspTransport`.

### A5. Walkthrough and Changes pane on the diff plugin
- **Walkthrough:** `WalkthroughView`'s step slide uses `InlineDiffEditor`.
  - Config: `DiffConfig(range = step, context = 20)`, and `plain = true` for `not_in_diff`.
  - Base: `UnifiedPatch.base(content, file.diff)`.
  - Threads from `walkthroughThreads`; composer from `WalkthroughState` drafts.
  - Implement `DiffHost` on the existing REST and frames:
    - `onCommentSubmit` → `postComment` (`deliver = "instant"`)
    - `onReply` → `AddCommentBody(parentId)`
    - `onResolve` → resolve
    - `onComposerOpen` / `onComposerDraft` / `onComposerClosed` → `setDraft` / `clearDraft`
    - `onDiffPage` → `next` / `previous`
  - Lines are **0-based** in the plugin.
  - `WalkthroughState.scrollOffsets` becomes `EditorScrollPosition`.
  - The j/k/←/→ keyboard and drag paging in the Kotlin shell stay.
- **Changes pane (`DiffView`):** keep the tree/list, the wrap toggle, the base selector, "Submit review" and the
  walkthrough toggle.
  - The **per-file diff** body becomes the diff plugin, inline by default, with a **side-by-side toggle** per file or
    for the whole pane (persist it in UiPrefs).
  - The `+` gutter composer maps to `AddCommentBody(side = "RIGHT", anchorContext, hunkHeader)`. The host computes
    `anchorContext` and `hunkHeader` from the plugin's line and hunk.
  - Revert hunk is offered only if the host can write the working file. Wire it to `DocumentStore`/`fsWrite`.
    Otherwise hide it.
- Tests: rewrite `WalkthroughViewTest` and the `DiffView` tests on the new path.
- [ ] Commit: `feat(ui): walkthrough and Changes pane on the diff plugin`.

### A6. Settings
- `EditorSettingsScreen`: wrap and font size update live views through view settings.
- New toggles:
  - side-by-side diff default
  - accessory bar on/off (default on)
- Keep the LSP server list as is.
- [ ] Commit: `feat(ui): editor settings drive the native editor`.

## Part B: parity gaps (map §5, MISSING items)

### B1. CM6 `defaultKeymap` extras (in `DefaultCommands`, or a basics `editing` extension)
- `moveLineUp`/`moveLineDown` (Alt-↑/↓)
- `copyLineUp`/`copyLineDown` (Shift-Alt-↑/↓)
- `deleteLine` (Shift-Mod-k)
- `toggleComment` (Mod-/), using the language's comment tokens (the syntax registry provides line and block comment
  tokens per language, added there)
- `toggleBlockComment` (Shift-Alt-a)
- `selectLine` (Alt-l / Mod-l per CM6)
- `selectParentSyntax` (Mod-i), using the syntax tree via a hook in editor-syntax
- `cursorMatchingBracket` (Shift-Mod-\\)
- `insertBlankLine` (Mod-Enter)
- `indentSelection` if CM6 binds it

All are multi-cursor, one undo step each, and CM6-parity. Check CM6 `@codemirror/commands` `defaultKeymap` for the
exact bindings. Also offer the most useful ones on the accessory bar overflow or long-press menu, which is optional.

- [ ] Commit: `feat(editor-plugins): CM6 default keymap parity (move/copy/delete line, comments, select parent)`.

### B2. Remaining gaps
- **Per-thread reply drafts survive widget disposal.** Keep them in review state, like the composer draft.
- **Desktop Edit ▸ Paste image `Ctrl+V` (`Main.kt:669`)** must not steal paste from a focused editor. Scope it to
  when the chat input has focus.
- [ ] Commit: `fix(ui,editor): parity gaps`.

## Part C: cutover and device parity pass

### C1. Delete CodeMirror and every web view (map §3, full list)
- Delete everything in spec §9 plus the "not in the spec list" items in map §3:
  - the engine layer in `:ui`, the platform factories, the JCEF dependency and repo
  - the `jcefAddOpens` / JBR-JCEF runtime tasks, including the Chromium marker check in jpackage
  - the `processResources` cm6 copy
  - the web editor staging
  - the iOS `syncEditorWeb` and the project.yml EditorWeb references
  - the Maestro surfaces updates
  - `tests/cm6-bundle-drift.test.ts`
  - the engine tests
  - `LocalHeavyweightShield` and `HeavyweightModalShield`
  - the WebView data-dir setup
  - the proguard `@JavascriptInterface` rules
- **Move `isMacOs()` out of `JcefRuntime.kt` first.**
- **Keep:** everything in map §3's "Looks editor-only but isn't" list (`KeepAlivePanel` etc.).
- Re-check `KeepAlivePanel`'s 0×0 hide with the Compose editor: no zero-width wrap layout thrash.
- Decide the jpackage runtime image now that JCEF is gone. `includeAllModules` was justified partly by JCEF.
- Optional broker cleanup: the `/editor/` gzip special case in `static-serve.ts`.
- Build all apps.
  - `grep` must show no references to `cm6`, `EditorEngine`, `JcefRuntime`, `WKWebViewEditorEngine`, `editor-shim`
    or `libs.jcef`. Allowed: docs/history.
  - Report the desktop app size before and after (Chromium gone).
- [ ] Commit: `refactor!: remove CodeMirror and every editor web view (native editor cutover)`.

### C2. Full test run
- All `:ui` / `:shared` / `:android` / `:desktop` / `:web` / `:ios` suites (Mac), the editor modules, the broker `bun test`
  for affected TS, and `ios-sim.sh`, `webInputTest` and the Maestro flows that exist.
- Report every failure honestly, with a cause.
- [ ] Commit any test fixes.

### C3. Device parity pass (Ahmet + the automated pass)
- Install the **real app** from this branch:
  - Fold (`deploy-android.sh`, keystore linked, `apksigner` verified)
  - iPad and iPhone (device build)
  - the Mac desktop app (packaged `.app`)
  - web via a throwaway broker
- Run the automated pass first (emulator, simulator, Chrome), as in M3b.
- Then send Ahmet the **parity checklist** generated from map §5, through the controller: every ✓ row as a
  one-line check, grouped by area (editing, LSP, walkthrough, Changes pane, settings), plus the new features.
- Fix every failure, re-install, and re-check the failing items.
- [ ] Commit fixes: `fix(...): cutover device-pass fixes`.
- [ ] Update the spec: mark §10 done. Update `~/.mux/domains/editor.md` and `infra.md`. Commit.

## Done means
- All four clients run the native editor, and no web view remains.
- The parity checklist is green on all five device classes.
- The desktop app ships without Chromium.
- The branch is ready to merge. **Don't merge or push without Ahmet's explicit go.**
