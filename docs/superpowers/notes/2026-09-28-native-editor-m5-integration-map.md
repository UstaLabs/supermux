Everything below was found by reading the code; nothing was built. All paths are relative to `/home/ahmet/.mux/worktrees/supermux-3962b5bf/3e23a9e4-56f3-45aa-8463-2d950c00b90a/` (`apps/…`, `src/…`, `tests/…`, `docs/…`). Line numbers are as of commit `3a7fb580`.

# M5 integration map: CodeMirror → native Compose editor

## Things that break at cutover or aren't covered yet

1. **The web size check will fail.** `supermux-syntax.wasm` is 2.69 MB gzipped (`apps/editor-syntax/native/README.md:447`). The `stageForBroker` ceiling leaves only 1.43 MiB of room (`apps/web/build.gradle.kts:138-185`, the check at `:336-338`). Today `cm6.js` sits outside `assets/` and isn't counted; the syntax wasm will land in `assets/` and be counted. Either raise the ceiling or exclude the lazily loaded syntax wasm.
2. **The packaged macOS desktop app won't load the syntax library.** `rewritePackagedNativeDigests` only rewrites `native.properties` under `dev/supermux/terminal/native/` (`apps/desktop/build.gradle.kts:410`). macOS re-signs the dylib during packaging, so `SesNativeLoader`'s sha256 check fails for `dev/supermux/editor/syntax/natives/…`. Extend the prefix.
3. **`isMacOs()` lives in `JcefRuntime.kt:131`** but `Main.kt:58,572,588-589,1617-1619` use it for window chrome. Move it before deleting `editor/`.
4. **`Mod-S` → save is missing.** Spec §7.1 puts it in basics; basics doesn't have it. The host must add a `keymapFacet` binding (`apps/editor-core/.../Commands.kt:68,77`).
5. **Native libraries are only built on the Mac.** They're gitignored (`apps/.gitignore:4`), so Linux builds and tests of `:ui`/`:desktop` have no JNI library. They need a plain-text fallback, and `release.yml` needs `editor-syntax/native/build.sh` steps like the terminal-core ones (`.github/workflows/release.yml:238-256`).
6. **Editor views must outlive their pane.** `PaneHost` only composes the active tab (`EditorPanes.kt:18-21`), and the LSP plugin sends `didClose` when a view's plugins stop (`editor-plugins/lsp/README.md` "M5 lifetime"). So the host has to keep one `EditorView` per document in a store next to `DocumentStore`, not per pane. The same applies to the markdown-preview swap (`EditorPanes.kt:311-342`).
7. **Line endings.** Spec §8 says normalize `\r\n` on load and restore it on save. Nothing does this today (`DocumentStore.kt:101-118,188-197`).

---

## 1. Every place the old engine is used

### 1a. Engine layer in `:ui` (all deleted at cutover)

| File:line | What it does |
|---|---|
| `apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/engine/EditorEngine.kt:24-73` | `interface EditorEngine`: `ready`/`failed` flows; `setDocument`, `revealLine`, `setFontSize`, `setLineWrap`, `setScrollTop`/`readScrollTop`, `getContent`; `lspConnect`/`lspMessage`/`lspDisconnect`; `showDiffRegion` (`:58`) and `updateDiffThreads` (`:69`); `dispose` |
| `…/engine/EditorEngine.kt:80-97` | `data class EditorCallbacks`, 11 callbacks: `onChange`, `onSave`, `onLspOut`, `onFontSize`, `onDiffLineClick`, `onDiffExpand`, `onDiffPage`, `onCommentSubmit`, `onReplySubmit`, `onResolveThread`, `onComposerState` |
| `…/engine/EditorEngine.kt:107-151` | `EngineState` (Ready / Initializing / Failed), `EditorEngineFactory` (`state`, `prewarmHost`, `ensureInit`, `create(lineWrap, fontSize)`), `UnavailableEditorEngineFactory` |
| `…/engine/EditorBridge.kt` | The pure JS bridge. `jsQuote` :33, `bridgeShimJs` :44, `initScript` :75, `BridgeEvent`/`parseBridgeEvent` :87/:137, `evalResultJs` :212, `parseLspOut` :234. Region types `DiffRegionRange`/`DiffRegionComment`/`DiffRegionThread`/`DiffRegionComposer` :249-277, `showDiffRegionJs` :294, `lsp*Js` :313-323, `EditorPushPlanner` :335, `EDITOR_READY_TIMEOUT_MS`/`EDITOR_BG`/`EDITOR_FG` :448-454 |
| `…/engine/EditorScrollReader.kt:164-179` | `EditorScrollReader` and `captureOutgoingScroll` (snapshots the outgoing tab's scroll before a tab switch) |
| `apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/EditorSurface.kt` | `expect EditorEngineHost` :80; `EditorLspHandle` :88-102 (how a panel reaches the LSP); `EditorSurface` :114-248 (state machine, pushes document/wrap/font/reveal, 8 s ready timeout, `prewarmHost`/`shownOnce` latch); `DiffRegionSurface` :253-328 (walkthrough; binds the 7 diff callbacks :288-296, `showDiffRegion` :301-304, `updateDiffThreads` :310-313); `NativeCodeEditor` fallback :356-410; `fallbackReason` :413 |
| `…/androidMain/…/EditorSurface.android.kt` | `WebViewEditorEngine` plus a host that attaches the view two frames late and keeps it invisible until ready (`AndroidView` :56) |
| `…/jvmMain/…/EditorSurface.jvm.kt` | `SwingEditorEngine`, `LocalHeavyweightShield` :36, `SwingPanel` inside `KeepAlivePanel` :62 |
| `…/iosMain/…/EditorSurface.ios.kt` | `UIKitView` :53, uses `UIView.hidden` |
| `…/wasmJsMain/…/EditorSurface.wasmJs.kt` | `DomEditorEngine`, `HtmlElementView` :61 |
| `…/iosMain/…/IosEditorEngineFactory.kt:24-54` | iOS factory; `prewarmHost = true` |
| `…/iosMain/…/WKWebViewEditorEngine.kt` (417 lines) | iOS engine |
| `apps/ui/src/commonMain/kotlin/dev/supermux/ui/platform/Platform.kt:150-156` | `val editorEngine: EditorEngineFactory` on `Platform` |

### 1b. Panels and state in `:ui` (they stay, but need rewiring)

- **`EditorPanel.kt`** — the composite panel. It's used only by Android `ChatScreen.kt:689`.
  - Types: `PendingEditorOpen` :98, `EditorPanelState` :104, `EditorPanelActions` :119.
  - Per-session state: `remember(sessionId) { EditorState }` :174.
  - Prefs: :199-205. `LspBridge`: :208-217. Engine hooks (`reader`, `lspHandle`, `engineReady`): :221-223.
  - File watcher: `editorOpen`/`editorClose` :231-234, fs_changed → `markChanged` :237-239.
  - LSP connect effect: :245-261.
  - Opening files: `revealFile`/`pendingOpen` :263-277.
  - Layout: `DiffView` mode :306-334; header search field :360; preview toggle :368; diff button :379-403; save :404-431; `EditorTabs` :461-471; stale banner :476-504; `EditorSurface` :507-527; markdown overlay :532-551; empty/error/loading states :553-604; tree drawer :609-643; search overlay :647-660.
- **`EditorPanes.kt`** — the three workspace panes.
  - `ExplorerPane` :104.
  - `FilePane` :186. Opens its document at :222; engine hooks :225-227; `LspBridge` :230; LSP connect :246-261 (`connect` :260); stale banner :285-307; preview swap :311-321; `EditorSurface` :323-342.
  - `DiffPane` :382. First fetch :406; comment seeding :408-426; walkthrough toggle ~:434; `WalkthroughView` :456; `DiffView` :470.
- **`WalkthroughView.kt`**
  - `LocalPlatform.current.editorEngine` :89.
  - `StepSlide` :220, then `DiffRegionSurface` :307-380: `onLineClick` :313, `onPage` :314, `onExpand` :315, `onCommentSubmit` :318, `onReplySubmit` :329, `onResolveThread` :345, `onComposerState` :351, scroll :361-363, fallback :365.
  - `WalkthroughNativeRegion` :388; `postComment` :442; `walkthroughThreads` :476; `walkthroughRegionRanges` :501; `walkthroughDiffLines` :553; `regionLines` :603.
- **`WalkthroughState.kt:14-141`** — one per session.
  - `applyServerFrame` :88, `applyComment` :96, `seedComments` :112.
  - Navigation: `next`/`previous`/`goTo`/`open`/`close` :117-134.
  - Drafts and scroll keyed by `CommentAnchor`: :136-140. Scroll is stored as `Int` pixels.
- **`DiffView.kt:120`** — pure Compose (unified rows, no engine). `baseLabel` :443, `BaseSelector` :466, `DiffRows` :913, `Composer` :1046, `CommentThreadRow` :1082, `parseDiffLines` :1184.
- **Other files in the package:**
  - `DiffState.kt:167-209` and `DiffTree.kt`.
  - `DocumentStore.kt:41-238`: `Document` :41, `open` :89, `openAtLine` :142, `close` :176, `update` :184, `save` :188, `markChanged`/`isStale` :202-206, `reload` :219.
  - `EditorState.kt:48-218`: tab order and `previewMode`, delegating to the store.
  - `ExplorerState.kt:112`.
  - `FileTree.kt:105`.
  - `EditorTabs.kt:48`.
  - `EditorSearchBar.kt`: `EditorSearchField` :48 and `EditorSearchOverlay` :101. This is **filename** search, not find-in-file.
  - `EditorPathHelpers.kt`: `isMarkdownPath` :16, `editorPreviewGate` :27, `pathToUri` :56, `dirUri` :71.
  - `LspBridge.kt:22-100`.
- **`EditorSettingsScreen.kt:87`** — prefs read :154-155, wrap switch :171, font stepper :193-216. Hosted by `FleetSettingsSections.kt:59` and desktop `DesktopSettingsSections.kt:92`.
- **Shell:**
  - `ViewHost.kt`: `editor` view routing :250-292; `editorEngineFactory` parameter :205; `rememberWorkspaceDocuments` :590; `ExplorerPaneForWorkspace` :603; `FilePaneForWorkspace` :629 (prefs :649-650, font persistence :666); `DiffPaneForWorkspace` :674 (`DiffState` seeded from the view's `diffBase` :692); `openTappedPath` :354-358.
  - `WorkspacePanes.kt`: `WorkspaceFileTab` save/preview :277-290; `onOpenFile` → `fileOpener.open` :635; walkthrough open :647.
  - `SupermuxApp.kt`: `editorOpen`/`Close` :1609-1613; fs_changed → `ws.documents.markChanged` :1614-1617; `externalOpen` :1618-1627; `rememberHostWorkspaceSession` :1764.
  - `WorkspaceSession.kt`: shared `DocumentStore` :84-90; `previewModes` :99; `WorkspaceFileOpener.reveal = documents.openAtLine` :111.
  - `ShellActions.kt`: fs/LSP/review/walkthrough seams :128-158, bound at :208-228 and :270-290.
- **Chat:** `ChatPanel.kt:244` and `FleetChat.kt:29` read `WalkthroughState` for the unread badge.

### 1c. Platform engine factories and how `Platform.editorEngine` is installed

| Platform | Engine files | Installed at | App-level extras |
|---|---|---|---|
| Android | `apps/android/src/main/kotlin/dev/supermux/android/editor/AndroidEditorEngineFactory.kt` (`prewarmHost = true` :40), `AndroidEditorEngine.kt` (340 lines) | `AndroidPlatform.kt:116-118` | `SupermuxApplication.kt:8,11-13,29-31` (`WebView.setDataDirectorySuffix`); `proguard-rules.pro:9,66-71` (`@JavascriptInterface` keep); `android/build.gradle.kts:99` comment; `ChatScreen.kt:242-253` (chat path tap → `PendingEditorOpen`), `:689-717` (`EditorPanel`), `:606-614` (WebView-flash `shownPanels` workaround) |
| Desktop | `apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/{DesktopEditorEngine.kt (410), DesktopEditorEngineFactory.kt (71), EditorWebAssets.kt (75), JcefRuntime.kt (159)}` | `DesktopPlatform.kt:96-98` (`DesktopEditorEngineFactory.shared`) | `Main.kt`: env docs :283/:290, `compose.interop.blending` ~:332, dispose :1807-1811. `DesktopTheme.kt:62,77-81` (`LocalHeavyweightShield`). `ui/ModalPresence.kt:104-160` (`HeavyweightModalShield`) |
| iOS | `apps/ui/src/iosMain/…/IosEditorEngineFactory.kt`, `WKWebViewEditorEngine.kt` (they live in `:ui`, not `:ios`) | `apps/ios/src/iosMain/kotlin/dev/supermux/ios/IosPlatform.kt:120-124` | Bundle files in `apps/iosApp/Supermux/EditorWeb/{cm6.js,index.html}` |
| Web | `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/editor/WebEditorEngine.kt` (412; `DEFAULT_INDEX_URL = "editor/index.html"` :361), `WebEditorEngineFactory.kt` (31) | `WebPlatform.kt:107-108` | `apps/web/editor/editor-shim.js` (66), `apps/web/karma.config.d/editor-shim.js` |

Tests: `FakePlatform.kt:47` (`UnavailableEditorEngineFactory`), `ViewHostTest.kt:71,107,371,391` (`noJcef`), and `EditorPanelTest.kt:48`, `EditorPanesTest.kt:45`, `WalkthroughViewTest.kt:51` (`FakeEditorEngineFactory`).

---

## 2. Data flows the new editor must plug into

| Flow | Where the state lives | Callbacks and wiring today | What changes with the new editor |
|---|---|---|---|
| **Open, load, save** | `DocumentStore` (`DocumentStore.kt:49`). Its `Document` holds `content`, `savedContent`, `scrollTop`, `revealLine` (:41-47). Workspace: one store per workspace (`WorkspaceSession.kt:84`). Android: one per session (`EditorPanel.kt:174`) | `fsRead`/`fsWrite` → `workspaceFsRead`/`workspaceFsWrite` (`SupermuxApp.kt:1775-1776`) or `vm.fleet.fsRead` (Android `SessionChatFallback.kt`). `open` has an in-flight guard (:97), `close` cancels an in-flight load (:176), `save` guards on `saving` (:188) | Engine `onChange(fullText)` → `update` (`EditorPanes.kt:333`) becomes an `EditorView.addListener` on the view. Build the doc from the rope, not by copying a String per keystroke. Save runs from the host's `Mod-S` binding plus the tab/header button (`WorkspacePanes.kt:285`, `EditorPanel.kt:416`). Add CRLF handling |
| **Dirty state** | `DocumentStore.isDirty` = `content != savedContent` (:84-87) | Tab dot (`EditorTabs`, `WorkspacePanes.kt:282`), save enabled (`EditorPanel.kt:418`) | Keep, or compare the rope/history against the saved version |
| **Conflict with changes on disk** | `DocumentStore.changedPaths`/`isStale`/`reload` (:63, :202-237) | Watcher start/stop: `editorOpen`/`editorClose` (`EditorPanel.kt:231-234`; `SupermuxApp.kt:1609-1613` → `HostStore.kt:1273-1278`). Pulses: `HostStore.fsChanges` (:279-283, fold :439) → `FleetStore.kt:293` → `markChanged` (`EditorPanel.kt:237-239`, `SupermuxApp.kt:1614-1617`). Banner and Reload: `EditorPanel.kt:476-504`, `EditorPanes.kt:285-307` | Reload replaces the view's document in one transaction with userEvent `disk` (fold/history already treat `disk` as non-local) instead of pushing a new String. There's no merge today, only a banner |
| **Per-session state** | Android `EditorState` `remember(sessionId)` (`EditorPanel.kt:174`). Workspace: `DocumentStore` per workspace; LSP and review use `primarySessionId` resolved via `actions.resolvedSessionId` (`ViewHost.kt:639,686`). `WalkthroughState` per session through the `WalkthroughSeam` (`HostStore.kt:239-244`, `WalkthroughSeam.kt`; installed by `AppViewModel.kt:23-27,63`, desktop `Main.kt:135-137`, iOS `MainViewController.kt:72-74`, web `Main.kt:29`) | — | One `EditorView` per `Document`, owned next to the store and started with `view.startPlugins(hostScope)`. One `LspClient` per (session, serverId) |
| **Tabs and panes** | Android: `EditorState.tabs`/`activeTabPath` (:62-63). Workspace: layout tree plus `WorkspaceFileOpener` (`shared/.../workspace/WorkspaceFileOpen.kt:159`) | `EditorTabs` (`onSelect` → `captureOutgoingScroll` + `selectTab`, `EditorPanel.kt:466-470`). `WorkspaceFileTab` (`WorkspacePanes.kt:277`). Scroll is saved as `Document.scrollTop` Int pixels | Scroll becomes `EditorView.scrollPosition`/`restoreScroll`, or simply keep the view alive per tab. Two panes on one path currently share `Document.content`; two `EditorView`s would need edits mirrored between them (userEvent `remote`) |
| **Tapping a file path in chat** | Workspace: `openTappedPath` (`ViewHost.kt:354-358`) → `onOpenFile(rel, line, endLine)` → `fileOpener.open` (`WorkspacePanes.kt:635`) → `documents.openAtLine` (`WorkspaceSession.kt:111`). Desktop `externalOpen`: `SupermuxApp.kt:1618`. Android: `ChatScreen.kt:242-253` → `PendingEditorOpen` → `EditorPanel.kt:272-277` → `openFileAtLine` | `Document.revealLine`, consumed once by `EditorSurface.kt:182-191` → `cmRevealLine` (selects line..endLine, **centered**, focus; `cm6-entry.mjs:289-299`) | Dispatch a selection plus `EditorEffects.scrollTo` (`editor-compose/.../EditorFacets.kt:307-313`). There is **no center option** (minor gap) |
| **Font-size persistence** | `UiPrefs.editorFontSize`/`putEditorFontSize`, clamped 10–24, default 13 (`UiPrefs.kt:37-39,74-80`) | Reads: `EditorPanel.kt:199-205`, `ViewHost.kt:650`, `EditorSettingsScreen.kt:155`. Writes: engine `onFontSize` → `EditorPanel.kt:521`, `ViewHost.kt:666`; settings stepper `:193-216` | `viewSettings(EditorSettings(fontSize, lineWrap))`, plus `Editor(onFontSize = ViewSettings.fontSizeReporter(view) { prefs.putEditorFontSize(it) })`, plus `ViewSettings.update(view){…}` when the pref changes (`editor-plugins/view/README.md` "M5's mapping") |
| **Line wrap** | `UiPrefs.editorLineWrap`, default true (`UiPrefs.kt:42,67-71`) | `EditorSettingsScreen.kt:171`. The panel reads it once and pushes `setLineWrap` live (`EditorSurface.kt:180`). The walkthrough always wraps (`EditorSurface.kt:281`). `DiffView` has its own local toggle (`DiffView.kt:159`) | `ViewSettings.update { copy(lineWrap = …) }` |
| **LSP status / open / rpc** | `HostStore.lspStatus` (keyed `"session\|path"`) and `lspRpc` (`HostStore.kt:301-307`, fold :442) → `FleetStore.kt:287,294` → `ShellActions.kt:140-144,214-218` | `LspBridge.queryStatus` (9 s / 1.5 s windows) :38, `open` (2 s failure window) :73, `rpcOut` :87, `pumpRpcIn` :95. Senders: `HostStore.kt:1287-1300` (`lspClose` exists but nothing calls it). Connect runs per active file: `EditorPanel.kt:245-261`, `EditorPanes.kt:246-261` → `cmLspConnect(serverId, dirUri(workdir), pathToUri(...), languageId)` | An `LspTransport` adapter over `LspBridge` (`editor-plugins/lsp/.../LspTransport.kt:27-39`): `send` = `rpcOut`, `incoming` = `pumpRpcIn` filtered, `status`/`connection` from `lspStatus` + `open`. Then `LspClient(transport, scope, LspClientConfig(rootUri, onNavigate, onWorkspaceEdit, onMessage))` and `client.plugin(fileUri, languageId)`. `onNavigate` (go to definition in another file) needs a new file URI → workdir path → `fileOpener.open` mapping |
| **Review / walkthrough frames** | `ServerFrame.WalkthroughUpdated` / `ReviewCommentFrame` → `applyWalkthroughFrame` (`HostStore.kt:406,440-441`) → `WalkthroughState.applyServerFrame` (:88). REST: `reviewAddComment`/`reviewResolve`/`reviewSubmit`/`reviewComments`/`getWalkthrough` (`HostStore.kt:1244-1268`, `ShellActions.kt:147-158`) | Walkthrough callbacks at `WalkthroughView.kt:313-363`. `postComment` :442 (`deliver = "instant"`). Replies go through `AddCommentBody(parentId)` :333. Drafts: `WalkthroughState.setDraft`/`clearDraft` :137-138. Scroll: `state.scroll(anchor)` :139 | The diff plugin's `DiffHost` (`editor-plugins/diff/.../Diff.kt:51-67`): `onCommentSubmit(line, text)`, `onReply`, `onResolve`, `onComposerOpen` (was `onDiffLineClick`), `onComposerDraft` / `onComposerClosed` (was `onComposerState(line \| 0)`), `onDiffPage`. `onDiffExpand` is internal now (`DiffConfig.context = 20`, expanders). Lines are **0-based**. Feed `Review.setThreads(ReviewThread(...))` from `walkthroughThreads` and `Review.setComposer(ReviewComposer(line, draft, focus=false))`. Step: `DiffConfig(range = step, context = 20)`; `not_in_diff` → `plain = true`. Base comes from `UnifiedPatch.base(content, file.diff)`. `WalkthroughState.scrollOffsets` changes from `Int` to `EditorScrollPosition` |
| **Diff base selector** | `DiffState.diffBase` / `diffRefs` / `setDiffBase` (`DiffState.kt:176-208`), seeded from `ViewDto` `diffBase` (`ViewHost.kt:692`) | `DiffView` `BaseSelector` :466 → `onSetBase` → `setDiffBase` → `workspaceFsDiff(spec)` (`HostStore.kt:1198`). The walkthrough uses `diff.diffRepos` from the same pane (`EditorPanes.kt:458`), so the base choice changes the walkthrough decorations | Stays in the host (spec §7.7). Whatever renders the per-file diff (`DiffRows` today, `InlineDiffEditor`/`SideBySideDiff` if replaced) takes the base from `UnifiedPatch.base` per file |
| **Markdown preview** | Android: `EditorState.previewMode` (:68). Workspace: `WorkspaceSession.previewModes[viewId]` (:99), toggled from `WorkspaceFileTab` (`WorkspacePanes.kt:284-288`). Gate: `editorPreviewGate` (`EditorPathHelpers.kt:27`) | Android draws an overlay (`EditorPanel.kt:532-551`); `FilePane` swaps (`EditorPanes.kt:311-321`, because JCEF is heavyweight). `MarkdownBody(linkify, onOpenFile)`. LSP disconnects while preview shows (`EditorPanel.kt:248`, `EditorPanes.kt:248`) | Unchanged per spec §7. An overlay now works everywhere, and keeping the view alive avoids LSP close/reopen churn |

---

## 3. What can be deleted at cutover (spec §9, checked against the tree)

**Listed in spec §9. All of these exist:**

- `apps/android/codemirror/` (README.md, bun.lock, cm6-entry.mjs, cm6-entry.test.ts, package.json). The test is also picked up by a root `bun test`.
- `apps/android/src/main/assets/editor/` (cm6.js, index.html). This is the whole `assets/` directory.
- `SupermuxApplication.kt:8,11-13,29-31`. The TTS and updater lifetime code in the same file stays.
- `apps/iosApp/Supermux/EditorWeb/` (cm6.js, index.html).
- `apps/ui/src/iosMain/kotlin/dev/supermux/ui/editor/WKWebViewEditorEngine.kt` and `IosEditorEngineFactory.kt`.
- `apps/web/editor/editor-shim.js`, `apps/web/karma.config.d/editor-shim.js`, `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/editor/WebEditorEngine{,Factory}.kt`.
- Desktop `editor/`: `JcefRuntime.kt` (move `isMacOs` out first), `EditorWebAssets.kt`, `DesktopEditorEngine{,Factory}.kt`.
- **JCEF itself.** The spec says "KCEF", but the dependency is really `implementation(libs.jcef)` (`desktop/build.gradle.kts:44-47`; `gradle/libs.versions.toml:53,106`) plus the JetBrains maven repo (`:27-29`). KCEF survives only as the `probeKcefBlending` alias (`:561-564`).
- `tests/cm6-bundle-drift.test.ts`.

**Other editor-only pieces not in the spec list:**

- **`:ui` engine layer:** `engine/EditorEngine.kt`, `engine/EditorBridge.kt`, `engine/EditorScrollReader.kt`, `EditorSurface.kt` and its 4 actuals, `Platform.editorEngine` (`Platform.kt:150-156`) and its 4 installs.
- **Walkthrough fallback code:** `WalkthroughNativeRegion`, `walkthroughRegionRanges`, `walkthroughDiffLines`, `regionLines` (`WalkthroughView.kt:388-617`). These are replaced by the plugin's model; `walkthroughThreads` gets adapted.
- **`:ui` tests:** `engine/EditorBridgeTest`, `engine/EditorPushPlannerTest`, `engine/EditorScrollCaptureTest`, `FakeEditorEngine`, `EditorSurfaceTest`, `NativeFallbackLayoutTest`, `WalkthroughRegionTest`. Rewrite: `EditorPanelTest`, `EditorPanesTest`, `WalkthroughViewTest`, `ViewHostTest`, `FakePlatform`, `SettingsSharedTest`, `EditorZoomPersistenceTest`.
- **Android:** `android/src/test/.../editor/AndroidEditorBridgeTest.kt`, `AndroidEditorEngineFactoryTest.kt`; `proguard-rules.pro:9,66-71`.
- **Desktop build (`desktop/build.gradle.kts`):**
  - `processResources` copy of `index.html` + `cm6.js` (`:285-296`).
  - `jcefAddOpens` (`:215-236`) and all its uses (`:254, 303, 535, 545, 556, 572, 583`).
  - JBR-JCEF runtime: `prepareJbrJcefRuntime` / `jbrJcefLauncher` (`:94-213`), the run/hotRun hooks (`:254-263`), jpackage `runtimeImage` / `--app-content` / Chromium marker check (`:469-498`). You'll have to delete the marker check or packaging fails.
  - `smokeJcefEditor` (`:566-574`), `probeJcefBlending` (`:551-559`).
- **Desktop tests:** `EditorBundleResourceTest`, `EditorWebAssetsTest`, `JcefEditorSmoke`, `JcefRuntimeTest` (move its `isMacOs` cases), `shell/JcefBlendingProbe`.
- **Desktop code:**
  - `LocalHeavyweightShield` (`EditorSurface.jvm.kt:36`), `DesktopTheme.kt:62,77-81`.
  - `HeavyweightModalShield` and the `ModalPresence` counting (`ui/ModalPresence.kt`). The shield is `anyOpen`'s only consumer, and after cutover there's no AWT/UIKit/DOM interop left except Android's `ScrcpyView`.
  - Comments: `Main.kt:283,290,321-332,687,944,1807-1811`; `DesktopContextMenu.kt:51-52`; `ShellShortcuts.kt:5-18`.
- **Web build (`web/build.gradle.kts`):** editor staging in `stageForBroker` (`:193-203, 219-220, 289-318, 341`); `editorShimTestResource` (`:12-17, 62-64, 354-360`); `EditorBridgeIframeTest.kt`.
- **iOS:** `:ios:syncEditorWeb` (`apps/ios/build.gradle.kts:57-80`); `project.yml:53-61` (EditorWeb exclude + folder reference) and `:166-169` (the `syncEditorWeb` call). Update `apps/iosApp/maestro/surfaces.yaml:2,18-21,70-105`.
- **Broker (optional cleanup):** `/editor/` gzip-cache special case (`src/channels/web/static-serve.ts:46-56`) and its tests (`static-serve.test.ts:72-78,128-140`).
- **CI:** `release.yml:443-444,722-723` (JBR/JCEF notes; JDK 21 is only needed for `--app-content`).

**Looks editor-only but isn't, so keep it:**

- **`KeepAlivePanel`** and all its actuals. Used by `TerminalTabs.kt:411` and `WorkspaceKeepAlive.kt:48`; `Modifier.keepAlivePanel` is used by Android `ChatScreen` and `DisplayPanel`. Re-check the 0×0 hide on JVM/iOS/wasm for a Compose editor (a zero-width wrap layout).
- `WalkthroughState`, `WalkthroughSeam`, and the 4 seam objects (the chat unread badge uses them).
- `LspBridge` (it becomes the transport adapter).
- `DocumentStore`, `EditorState`, `ExplorerState`, `DiffState`, `DiffTree`, `FileTree`, `EditorTabs`, `EditorSearchBar` (filename search), `EditorPathHelpers`.
- `DiffView` / `DiffRows` / `Composer` / `CommentThreadRow` / `parseDiffLines`. Pure Compose; they only need replacing if you move the Changes pane onto the diff plugin.
- `EditorSettingsScreen` / `LspSettingsScreen`, the `UiPrefs` editor keys, `src/core/settings/editor-config.ts`.
- The CSP `frame-ancestors` header (`static-serve.ts:81-87`), which applies to the whole site.
- `LocalModalHost` (`widgets/Dialogs.kt`).
- `libs.jbr.api` (`desktop/build.gradle.kts:43`) and the JBR toolchain that Compose Hot Reload needs (`settings.gradle.kts` comment).
- `rewritePackagedNativeDigests`: keep it and **extend** it.
- `compose.interop.blending` (inert).
- Chat `ToolDiffPane` (`Timeline.kt:576`) and `WorkspaceSingletonView.isDiffView`.

---

## 4. Wiring the new modules into the apps

- **`apps/settings.gradle.kts`:** nothing to add. `:editor-core`, `:editor-syntax`, `:editor-compose`, `:editor-sample`, `:editor-plugins:{basics, history, highlight, fold, view, search, autocomplete, lint, lsp, diff, lsp-fake}` are all already included. `lsp-fake` is test/sample only.
- **`:ui` dependencies** (`apps/ui/build.gradle.kts` `commonMain.dependencies`, next to `api(project(":terminal-compose"))` ~:44): add `:editor-compose`, `:editor-syntax`, and `:editor-plugins:{basics, history, highlight, fold, view, search, autocomplete, lint, lsp, diff}`. Use `api` for any whose types appear in `:ui`'s signatures (e.g. `EditorView` held by `Document`). The dependency must point one way only, `:ui` → editor, as with terminal-compose. Targets and toolchains match (jvm, android, ios×2, wasmJs; jvmToolchain 17; minSdk 26).
- **iOS:**
  - `:ui` brings `:editor-syntax` in transitively; its cinterop `.a` rides in the klib.
  - No `export(...)` is needed in `apps/ios/build.gradle.kts:35-45`, because Swift never calls editor APIs.
  - The framework is dynamic (`isStatic = false` :40), and tables still come from the app bundle.
  - Set `SUPERMUX_EDITOR_SYNTAX_TABLES: "YES"` (`apps/iosApp/project.yml:123`); the script is at `:170-195`. Regenerate with xcodegen.
  - Delete the EditorWeb pieces listed in §3.
- **Web:**
  - Add a `stageSyntaxWasmAssets` Copy task, modelled on `stageTerminalWasmAssets` (`web/build.gradle.kts:94-104`) and the sample's (`editor-sample/build.gradle.kts:126-136`). It copies `:editor-syntax:stageWasmResources` (`build/gradle/generated/wasmResources/supermux-syntax.wasm`) and `editor-syntax/src/wasmJsMain/resources/syntax-loader.mjs` into `wasmJsMain.resources`.
  - `stageForBroker` then hashes both into `assets/` (`:243-270`).
  - The tables (`:editor-syntax:stageTables` → `build/gradle/generated/tables/editor-syntax/tables/*.sesz`, 29 files, 5.4 MB) are fetched by name. Stage them explicitly, e.g. under `assets/editor-syntax/tables/<manifest-sha>/` (the README's suggestion), and pass that path to `WasmBackend.load(tablesUrl = …)` (`editor-syntax/.../WasmBackend.kt:42`). By default the loader looks next to the wasm, which ends up in `assets/` after hashing.
  - Fix the gzip ceiling (the first item at the top).
  - `WebPlatform` needs an async syntax-backend seam in place of `editorEngine` (`NativeBackend()` everywhere else).
  - For the compiled broker binary, add `declare module "*.sesz"` to `src/types/assets.d.ts:73-88`; `generate-static-manifest.ts` imports every file with `type: "file"`. MIME falls back to `application/octet-stream` (`static-serve.ts:32`), which is fine.
- **Android:** nothing in the app module.
  - `:editor-syntax`'s `androidComponents` adds `jniLibs/<abi>/libsupermux_syntax_jni.so` and the tables as Java resources to the AAR (`editor-syntax/build.gradle.kts:250-282,414-423`).
  - ABIs match: `abiFilters` from `supermux.terminal.androidAbis = arm64-v8a, x86_64` (`android/build.gradle.kts:55-67`, `gradle.properties:43`) vs editor-syntax `:41`.
  - A missing ABI only **warns** (`editor-syntax/build.gradle.kts:~270`), so an APK can ship with no syntax library. Consider failing like terminal-core does.
- **Desktop:** natives and tables ride in the `:editor-syntax` jvm jar (`stageJvmNativeResources` :214-248 → `dev/supermux/editor/syntax/natives/<target>/{lib, native.properties}`; `stageTables` :322-330).
  - Extend `rewritePackagedNativeDigests` (`desktop/build.gradle.kts:403-460`, filter :410).
  - Linux and Windows libraries are cross-built on the Mac and need load-testing in CI (native README :97-99).
  - Once JCEF is gone, decide which runtime image jpackage uses (`includeAllModules` is justified partly by JCEF, `:331-336`).

---

## 5. Parity checklist

Status: **✓** covered, **~** partly covered or behaviour changes, **MISSING** not covered.

**Editing and view (`cm6-entry.mjs:235-303`)**

| Feature | Covered by | Status |
|---|---|---|
| Line numbers, active-line gutter and line | basics `ActiveLine`, `lineNumbers` | ✓ |
| History (undo/redo) | history plugin | ✓ |
| Fold gutter | fold plugin | ✓ |
| Selection drawing, rectangular (Alt-drag) selection | editor-compose `EditorPointer` | ✓ |
| Bracket matching, close brackets, indent on input, selection-match highlight | basics | ✓ |
| Lint gutter | lint plugin | ✓ |
| Syntax highlighting, ~50 languages | highlight + `LanguageRegistry.forFile` (`Languages.kt:19`) | ~ These become plain text: erlang, crystal, coffeescript, ini/properties, powershell, protobuf, tex, diff/patch, pug, fortran, vbscript, cmake, dockerfile (`Languages.kt` `""` entries). nginx, vb, wat are fine |
| oneDark theme | `EditorTheme.dark` | ~ CM6 was always dark; decide whether to follow the app's light/dark |
| Keymap: Tab / indentWithTab, `Mod-[`/`]` | `DefaultCommands` | ✓ |
| Keymap: search | search plugin | ✓ |
| Keymap: completion | autocomplete | ✓ |
| Keymap: lint (F8, `Mod-Shift-m`) | lint | ✓ |
| CM6 `defaultKeymap` extras: move/copy line (Alt/Shift-Alt-↑↓), delete line (`Shift-Mod-k`), toggle comment (`Mod-/`), block comment, select line, select parent syntax, jump to matching bracket, insert blank line (`Mod-Enter`) | — | **MISSING** (`DefaultCommands.kt:395-450` binds none of them) |
| `Mod-S` → save (`onSave`) | spec says basics | **MISSING**: host keymap binding |
| Zoom: `Mod-=`/`Mod-+`/`Shift-Mod-=`/`Mod--`/`Mod-0` (10–24, default 13) and pinch; `onFontSize` persistence | editor-compose zoom + view `fontSizeReporter` | ✓ (pinch rounds only when the fingers lift) |
| Font-size badge ("15px" for 900 ms) | — | **MISSING** (host shows it from the callback) |
| `cmSetLineWrap` live | view `ViewSettings.update` | ✓ |
| `cmSetContent` without echo / `onChange` | `EditorView.dispatch` / listeners | ✓ |
| `cmGetScrollTop`/`SetScrollTop` (tab scroll restore) | `scrollPosition`/`restoreScroll` | ✓ (by document position) |
| `cmRevealLine(line, endLine)`: selection, centered, focus | selection + `EditorEffects.scrollTo` + `focus()` | ~ No centering option |
| `cmSetLanguage` by filename | `highlight(LanguageRegistry.forFile(path))` in a compartment / `setState` | ✓ |

**LSP (`cm6-entry.mjs:731-853`)**

| Feature | Covered by | Status |
|---|---|---|
| Diagnostics | lsp → lint | ✓ |
| Completion with trigger characters | lsp + autocomplete | ✓ |
| Hover | `hoverTooltip("lsp")`; touch: `Hover.showHover` needs a host button | ✓ |
| Signature help | lsp | ✓ |
| Format / rename / go to definition / find references keymaps | lsp | ✓ (cross-file definition needs host `onNavigate`) |
| Code actions | lsp | new, not in CM6 today |
| Transport over broker frames | `LspTransport` adapter (host) | host work |

**Walkthrough region (`cm6-entry.mjs:311-721`; the 7 diff callbacks)**

| Feature | Covered by | Status |
|---|---|---|
| Read-only slice, 20 context lines, "expand ↑/↓ 20" (`onDiffExpand`) | diff `DiffConfig(range, context = 20)`, expanders | ✓ (internal, no callback) |
| Add/change/delete line tints; `+ − ±` gutter glyphs | `diff-add`/`diff-change`/`diff:deleted` widget, drawn bars | ~ Bars instead of glyphs; deleted lines lose syntax colouring |
| Click a line → composer (`onDiffLineClick`) | `onComposerOpen` via the gutter comment cell | ~ No longer "click anywhere on the line" |
| Composer ⌘↩ / Esc, draft persisted (`onComposerState`) | `onComposerDraft` / `onComposerClosed`, `Review.setComposer` | ✓ |
| Thread widgets: authors, resolved collapse, Resolve (`onResolveThread`) | `review:thread`, `onResolve` | ✓ |
| Reply (`onReplySubmit`) | `onReply` | ✓ |
| Per-thread reply drafts survive a re-render | `rememberTextFieldState` (`ReviewWidgets.kt:162`) | ~ Lost when the widget is disposed |
| Sideways wheel/swipe paging (`onDiffPage`), plus j/k/←/→/drag in the Kotlin shell (`WalkthroughView.kt:100-120`) | `Modifier.diffPaging` / `DiffHost.onDiffPage`; the Kotlin shell stays | ✓ |
| Scroll kept across a live update; scroll per anchor | plugin keeps it; `WalkthroughState` → `EditorScrollPosition` | ✓ / host change |
| Region identity resets expansion | `Diff.load` folds again | ✓ |
| `not_in_diff` / outdated steps | `plain = true`; the host banner stays | ✓ |

**Host UI (`EditorSurface.kt`, `EditorPanel.kt`)**

| Feature | Covered by | Status |
|---|---|---|
| Initializing strip, 8 s ready timeout, native `BasicTextField` fallback | Not needed. Syntax failure is handled by the `syntax-off` panel / `Syntax.isOff` | Deleted; need a plain-text path when no JNI library is present |
| Header: tree toggle, drawer, scrim, back handling; filename search (200 ms debounce, overlay); tabs (dirty dot, loading, close); stale banner + Reload; empty/error/loading states; haptics | Host, unchanged | ✓ |
| Markdown preview toggle | Host, unchanged | ✓ |
| Diff button + spinner | Host, unchanged | ✓ |
| Save button + spinner | Host, unchanged | ✓ |
| In-file find/replace, go to line | search plugin; accessory-bar Find | new (CM6 had `searchKeymap` only) |
| Mobile accessory bar | `EditorAccessories`, placed by the host | new |

**Changes pane (`DiffView.kt`, pure Compose today)**

| Feature | Covered by | Status |
|---|---|---|
| Repo/folder tree vs list (`editorDiffTreeView` pref), expand/collapse with +/- stats | Host | ✓ keep |
| Wrap toggle | Host | ✓ keep |
| Base selector (dropdown / bottom sheet on Compact) | Host (spec) | ✓ keep |
| `+` gutter composer → `AddCommentBody(side = "RIGHT", anchorContext, hunkHeader)` | Keep `DiffRows`, or use `InlineDiffEditor`/`SideBySideDiff` + `DiffHost` | ~ If ported, the host must compute `anchorContext` and `hunkHeader` from the line; the plugin only reports `line` |
| Threads + Resolve | Host or plugin | ✓ |
| Sticky "Submit review" with open count | Host | ✓ |
| Walkthrough toggle | Host | ✓ |
| Revert hunk, side-by-side | diff plugin | new |

**Settings (`EditorSettingsScreen.kt`)**

| Feature | Covered by | Status |
|---|---|---|
| Soft-wrap switch, font stepper (write `UiPrefs`) | View settings reconfigured live | ✓ |
| LSP server list/toggle/install/custom | Host, unchanged | ✓ |

**To verify on devices:**
- The desktop Edit ▸ "Paste image" accelerator `Ctrl+V` (`Main.kt:669`) must not steal paste from a focused Compose editor. JCEF used to take native focus.
- Shell `Mod-B`/`Mod-N` (`ShellShortcuts.kt:71-90`) now bubble up from the editor. No binding conflict found.