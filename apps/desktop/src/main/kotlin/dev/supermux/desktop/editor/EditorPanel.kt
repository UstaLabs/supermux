// M3 editor: the desktop EditorPanel — a port of apps/android/.../editor/EditorScreen.kt trimmed to
// the DESKTOP TABLET arrangement (inline 192dp file-tree sidebar; no phone slide-over drawer).
//
// Desktop adaptations / deferrals vs. the Android source:
//   - The markdown-preview toggle landed in M4g-1: the header gets a preview/edit IconButton on
//     `.md`/`.markdown` tabs. UNLIKE Android (a same-parent-Box overlay works fine there),
//     toggling preview on desktop is a CONDITIONAL SWAP: EditorSurface is not composed at all while
//     showPreview is true, and the rendered MarkdownBody column takes its place — see the swap's
//     call-site comment further down for why (KCEF's heavyweight AWT SwingPanel always paints above
//     lightweight Compose siblings, so an overlay is invisible on this platform whenever KCEF is the
//     live surface; confirmed via a headless live-KCEF capture).
//   - Diff view + inline code-review landed in M4g-2 (DiffView.kt): "diff is a MODE of the panel"
//     (Android EditorScreen.kt:255-282 parity) — the SAME swap discipline as the preview toggle
//     above, but at the OUTER Box level: `if (editor.showDiff) { DiffView(...); return@Box }` before
//     the Column composes at all, so the tabs/tree/header AND EditorSurface (and its KCEF engine)
//     are never composed while a diff is showing. The preview gate now ANDs in `!showDiff` (Android
//     EditorScreen.kt:177-178 parity, restored — see [editorPreviewGate]'s `showDiff` param).
//   - LSP landed in M4g-3: the engine's `lspOut` is parsed + forwarded to a [DesktopLspBridge]
//     (port of AndroidLspBridge) via the [EditorLspHandle] seam, and the connect-sequencing
//     LaunchedEffect (keyed on sessionId — desktop reuses one SessionDetail, unlike Android's NavHost)
//     drives queryStatus → open → pumpRpcIn → lspConnect against the broker's LSP transport. The LSP
//     install/settings screen is the separate M4g-4 milestone (not wired here).
//   - The WebView surface is a KCEF engine ([EditorSurface] in WebCodeEditor.kt) with a native
//     BasicTextField fallback. The engine is built ONLY once KCEF is Ready and is NEVER created
//     optimistically; it is disposed when the surface leaves the composition.
//   - Prefs (lineWrap/fontSize) arrive as a plain [prefs] value + [onFontSize] writeback (the caller,
//     SessionDetail, owns the EditorPrefsStore) — the panel stays disk-free so it's runComposeUiTest-
//     able. The persisted lineWrap/fontSize are pushed into cm6 at engine init (cmInit args).
//
// TESTABILITY: [kcefState] + [onEnsureInit] are injectable so the panel's UI tests drive the whole
// engine state machine (cover / downloading / native fallback) WITHOUT booting Chromium — the engine
// is only built when Ready, which tests never pass. SessionDetail additionally hides the whole panel
// behind an `editorPanelContent` seam so ITS tests never touch KCEF at all.
package dev.supermux.desktop.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import dev.supermux.desktop.theme.LocalPanes
import dev.supermux.desktop.theme.Space
import dev.supermux.net.AddCommentBody
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsEntry
import dev.supermux.net.FsRefsResult
import dev.supermux.net.FsSearchResult
import dev.supermux.net.ReviewComment
import dev.supermux.net.ReviewSubmitResult
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A chat-initiated request to open a workdir-relative [path] at an optional [line] (Android parity).
 *  The full chat-tap → pendingOpen wiring lands in T5; T4 accepts it as an optional param. */
data class PendingEditorOpen(val path: String, val line: Int?, val endLine: Int?)

/**
 * Code editor panel: inline lazy file tree, multi-tab editing, filename search, save + stale banner,
 * and the KCEF-backed editing surface with a native fallback.
 *
 * @param workdir the session's absolute workdir — used by the M4g-3 LSP connect flow to build the
 *   root/file `file://` URIs, exactly as Android's EditorScreen does (dirUri/pathToUri).
 */
@Composable
fun EditorPanel(
    sessionId: String,
    workdir: String,
    fsList: suspend (String) -> List<FsEntry>,
    fsRead: suspend (String) -> Result<String>,
    fsWrite: suspend (String, String) -> Boolean,
    fsSearch: suspend (String) -> List<FsSearchResult>,
    // M4g-2 — diff + inline code-review (pure HTTP; Android EditorScreen.kt:90-93 parity).
    // fsDiff takes the diff-base spec (session-start/head/commit:<sha>/branch:<name>); fsRefs feeds
    // the base picker's commit/branch submenus (diff-base selector — Android EditorScreen parity).
    fsDiff: suspend (String) -> FsDiffResult? = { null },
    fsRefs: suspend () -> FsRefsResult? = { null },
    onReviewAddComment: suspend (AddCommentBody) -> ReviewComment? = { null },
    onReviewResolve: suspend (String) -> Boolean = { false },
    onReviewSubmit: suspend () -> ReviewSubmitResult? = { null },
    fsChanges: SharedFlow<ServerFrame.FsChanged> = MutableSharedFlow(),
    // LSP (M4g-3) — app-wide flows + outbound senders, mirrors Android EditorScreen.kt:94-103.
    // EditorPanel builds its own DesktopLspBridge from these (same non-owning-the-flows shape as
    // fsChanges above); DesktopEditorPanel/SessionDetail wires the real DesktopAppState.lsp*.
    lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = MutableStateFlow(emptyMap()),
    lspRpc: SharedFlow<ServerFrame.LspRpcIn> = MutableSharedFlow(),
    lspStatusQuery: (String, String) -> Unit = { _, _ -> },
    lspOpen: (String, String) -> Unit = { _, _ -> },
    lspRpcOut: (String, String, String) -> Unit = { _, _, _ -> },
    editorOpen: (String) -> Unit = {},
    editorClose: (String) -> Unit = {},
    pendingOpen: PendingEditorOpen? = null,
    onPendingOpenConsumed: () -> Unit = {},
    prefs: EditorPrefs = EditorPrefs(),
    onFontSize: (Int) -> Unit = {},
    kcefStateFlow: StateFlow<KcefState> = KcefRuntime.state,
    onEnsureInit: (CoroutineScope) -> Unit = { KcefRuntime.ensureInit(it) },
    // Test seam: inject a fake reader to drive/observe the tab-switch scroll capture; defaults to a
    // panel-owned reader that EditorSurface wires to the live engine's getScrollTop.
    scrollReader: EditorScrollReader? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val c = LocalPanes.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // Own the editor state for the LIFETIME OF THE SESSION — keyed only on sessionId (NOT the fs*
    // lambdas, which capture the whole session and re-instance on every background pulse; keying on
    // them would wipe open tabs + unsaved edits on each pulse — Android EditorScreen:118-126).
    val editor = remember(sessionId) { EditorState(fsRead, fsWrite, scope) }

    // The scroll-capture seam: EditorSurface installs the live engine reader into this; the panel
    // reads it right before a tab switch/reveal (Android EditorScreen:406-408/:216 parity).
    val reader = scrollReader ?: remember { EditorScrollReader() }

    // LSP (M4g-3): the bridge drives the control-plane flows; the handle is EditorSurface's
    // non-owning seam for pushing lspConnect/lspMessage/lspDisconnect into the live engine
    // (mirrors `reader` above); engineReady mirrors the live engine's cm6-first-paint gate.
    val bridge = remember(sessionId, lspStatus, lspRpc) {
        DesktopLspBridge(
            sessionId = sessionId,
            lspStatus = lspStatus,
            lspRpc = lspRpc,
            lspStatusQuery = lspStatusQuery,
            lspOpen = lspOpen,
            lspRpcOut = lspRpcOut,
        )
    }
    val lspHandle = remember(sessionId) { EditorLspHandle() }
    var engineReady by remember(sessionId) { mutableStateOf(false) }

    val kcefState by kcefStateFlow.collectAsState()

    // Desktop is always the tablet arrangement — the inline tree defaults visible.
    val treeVisible = editor.treeVisible ?: true
    val searchResults = remember { mutableStateListOf<FsSearchResult>() }

    LaunchedEffect(editor.searchQuery) {
        delay(200)
        val q = editor.searchQuery.trim()
        if (q.isEmpty()) {
            searchResults.clear()
            return@LaunchedEffect
        }
        searchResults.clear()
        searchResults.addAll(fsSearch(q))
    }

    // Editor lifecycle: start/stop the broker fs-watcher for this session. This is ALSO what makes
    // fs_changed fire — the stale banner is dead without it (Android EditorScreen:182-185).
    DisposableEffect(sessionId) {
        editorOpen(sessionId)
        onDispose { editorClose(sessionId) }
    }

    // Live file-watch: fold fs_changed pulses for THIS session into the stale set (EditorScreen:188-190).
    LaunchedEffect(sessionId, fsChanges) {
        fsChanges.collect { f -> if (f.session == sessionId) editor.markChanged(f.paths) }
    }

    fun revealFile(path: String, line: Int? = null, endLine: Int? = null) {
        focusManager.clearFocus()
        // Capture the outgoing tab's scroll before the switch (Android EditorScreen:216 parity).
        captureOutgoingScroll(editor, reader)
        editor.openFileAtLine(path, line, endLine)
        editor.searchQuery = ""
        searchResults.clear()
    }

    LaunchedEffect(pendingOpen) {
        pendingOpen?.let {
            revealFile(it.path, it.line, it.endLine)
            onPendingOpenConsumed()
        }
    }

    // Off-by-default headless save-verification hook (M3): SM_EDITOR_SAVE_TEST="<marker>" — once a
    // file is open, appends the marker line to the active tab through the SAME sink cm6's onChange
    // uses (EditorState.updateContent) and calls the SAME save path the header button drives
    // (EditorState.saveActive → fsWrite → broker PUT), so the editor→disk round-trip can be proven
    // under Xvfb where the save button can't be clicked (no xdotool). Fires ONCE per panel. Harmless
    // in production (unset by default).
    val saveTestMarker = remember { System.getenv("SM_EDITOR_SAVE_TEST")?.takeIf { it.isNotBlank() } }
    if (saveTestMarker != null) {
        var fired by remember(sessionId) { mutableStateOf(false) }
        LaunchedEffect(editor.activeTab?.path) {
            val tab = editor.activeTab
            if (!fired && tab != null) {
                delay(1_000) // let cm6 settle so the dirty→saved lifecycle is observable
                editor.updateContent(tab.path, tab.content + "\n// SM_EDITOR_SAVE_TEST: $saveTestMarker\n")
                delay(500)
                editor.saveActive()
                fired = true
                println("[editorsave] appended marker + saved '${tab.path}' for session $sessionId")
            }
        }
    }

    // Off-by-default headless markdown-preview verification hook (M4g-1): SM_EDITOR_PREVIEW=
    // "<session-name>|<md-path>" is resolved+opened in Main.kt (the SAME externalOpen chain
    // SM_OPEN_FILE uses); THIS side only watches for <md-path> (the part after the last '|') to
    // become the active tab and flips editor.previewMode = true ONCE, so the rendered preview
    // overlay can be screenshotted headlessly (no xdotool). Harmless in production (unset by
    // default) and safe to run alongside a plain SM_OPEN_FILE (this hook no-ops unless its own env
    // var is set). Mirrors the SM_EDITOR_SAVE_TEST hook above.
    val previewTestPath = System.getenv("SM_EDITOR_PREVIEW")?.substringAfterLast('|')?.takeIf { it.isNotBlank() }
    if (previewTestPath != null) {
        var previewFired by remember(sessionId) { mutableStateOf(false) }
        LaunchedEffect(editor.activeTab?.path) {
            if (!previewFired && editor.activeTab?.path == previewTestPath) {
                delay(500) // let the tab/content settle before flipping the overlay on
                editor.previewMode = true
                previewFired = true
                println("[editorpreview] previewMode=true for '$previewTestPath' in session $sessionId")
            }
        }
    }

    // Off-by-default headless diff-verification hook (M4g-2): SM_DIFF="<session-name>" is resolved+
    // selected in Main.kt (the SAME session-select chain SM_EDITOR_PREVIEW uses); THIS side fires
    // the SAME editor.loadDiff(fsDiff) the "View changes" button drives — a real GET /fs/diff — ONCE
    // per panel mount, so the rendered DiffView can be screenshotted headlessly (no xdotool).
    // Harmless in production (unset by default). Mirrors the SM_EDITOR_SAVE_TEST hook above.
    val diffTestOn = System.getenv("SM_DIFF")?.isNotBlank() == true
    if (diffTestOn) {
        var diffFired by remember(sessionId) { mutableStateOf(false) }
        LaunchedEffect(sessionId) {
            if (!diffFired) {
                diffFired = true
                editor.loadDiff(fsDiff, fsRefs)
                println("[editordiff] loadDiff fired for session $sessionId")
            }
        }
    }

    // Off-by-default headless review-comment hook (M4g-2): SM_DIFF_COMMENT=1, paired with SM_DIFF,
    // fires a real POST /review/comments (the SAME onReviewAddComment the +-gutter composer drives)
    // on the loaded diff's first addable line, then reloads so the resulting comment thread renders
    // — never touches reviewSubmit. Harmless in production (unset by default; also no-ops without
    // SM_DIFF, since editor.diffRepos never populates otherwise).
    val diffCommentTestOn = System.getenv("SM_DIFF_COMMENT")?.isNotBlank() == true
    if (diffTestOn && diffCommentTestOn) {
        var commentFired by remember(sessionId) { mutableStateOf(false) }
        LaunchedEffect(editor.diffRepos) {
            if (commentFired) return@LaunchedEffect
            val repoWithFile = editor.diffRepos.firstOrNull { it.files.isNotEmpty() } ?: return@LaunchedEffect
            val file = repoWithFile.files.first()
            val lines = parseDiffLines(file.diff)
            val addLine = lines.firstOrNull { it.type == DiffLineType.Add && it.newLine != null } ?: return@LaunchedEffect
            val hunk = lines.take(lines.indexOf(addLine)).lastOrNull { it.type == DiffLineType.Hunk }?.content ?: ""
            commentFired = true
            onReviewAddComment(
                AddCommentBody(
                    repo = repoWithFile.repo,
                    path = file.path,
                    side = "RIGHT",
                    anchorLine = addLine.newLine!!,
                    anchorContext = addLine.content,
                    body = "SM_DIFF_COMMENT verification comment",
                    diffHunkHeader = hunk,
                ),
            )
            editor.reloadDiff(fsDiff)
            println("[editordiff] SM_DIFF_COMMENT fired on ${file.path}:${addLine.newLine}")
        }
    }

    val activeTab = editor.activeTab
    val loadingNew = editor.loadingPath?.let { path -> editor.tabs.none { it.path == path } } == true
    val previewGate = editorPreviewGate(activeTab?.path, editor.previewMode, editor.showDiff)
    val showPreviewToggle = previewGate.showPreviewToggle
    val showPreview = previewGate.showPreview

    // LSP connect sequencing (M4g-3; port of Android EditorScreen.kt:192-212). Re-runs whenever the
    // session, active file, preview/diff mode, or the live engine's ready-gate changes; LaunchedEffect
    // cancellation tears down the prior connection on a fast tab switch — same pattern as Android's
    // engine.failed re-key.
    // NOTE — `sessionId` MUST be in the key list. Desktop reuses ONE SessionDetail/EditorPanel across
    // session switches (AppShell renders SessionDetail WITHOUT key(session.id) — see
    // DesktopAppState.kt:130-135's comment), unlike Android which recreates EditorScreen via NavHost.
    // Without `sessionId` here, two sessions whose (activeTabPath, showPreview, showDiff, engineReady)
    // tuple coincides across a switch (realistic: same relative path, both no-tab, or both mid-load
    // with engineReady=false — which it ALWAYS is right after a switch) would NOT relaunch the effect,
    // so the coroutine keeps running with the previous session's stale bridge/lspHandle/workdir
    // closures and drives LSP for the wrong session. `bridge`/`lspHandle` are already remember(sessionId)
    // {} for the same reason; the effect key must match. (Android's EditorScreen.kt:195 omits it only
    // because its NavHost lifecycle makes it structurally unnecessary there.)
    LaunchedEffect(sessionId, editor.activeTabPath, showPreview, editor.showDiff, engineReady) {
        lspHandle.disconnect()
        val tab = editor.activeTab
        if (showPreview || editor.showDiff || tab == null || workdir.isEmpty() || !engineReady) {
            return@LaunchedEffect
        }
        val status = bridge.queryStatus(tab.path)
        val serverId = status.serverId
        // Status.isReady: supported && serverId != null && state == "ready" (LspBridge.swift:18 /
        // AndroidLspBridge parity).
        if (!status.supported || serverId == null || status.state != "ready") {
            println("[lsp] '${tab.path}' not ready for LSP (state=${status.state}, supported=${status.supported})")
            return@LaunchedEffect
        }
        // Pump inbound RPC for this server in a child coroutine (cancelled with this effect).
        launch {
            bridge.pumpRpcIn(serverId) { sid, msg ->
                println("[lsp] rpc_in $sid ${msg.take(120)}")
                lspHandle.message(sid, msg)
            }
        }
        if (!bridge.open(serverId)) {
            println("[lsp] open($serverId) failed for '${tab.path}'")
            return@LaunchedEffect
        }
        val rootUri = dirUri(workdir)
        val fileUri = pathToUri(joinPath(workdir, tab.path))
        println("[lsp] connecting $serverId root=$rootUri file=$fileUri lang=${status.languageId}")
        lspHandle.connect(serverId, rootUri, fileUri, status.languageId ?: "")
    }

    Box(modifier.fillMaxSize()) {
        // Diff is a MODE of the panel (parity EditorScreen.kt:255-282 / EditorPane.swift:44): when
        // showDiff, DiffView swaps the WHOLE panel — header/tabs/tree/editor — never composing the
        // Column below at all. This is a full SWAP, not an overlay: the same discipline the M4g-1
        // markdown-preview fix adopted (see the "WHY NOT AN OVERLAY" note further down) — KCEF's
        // heavyweight AWT SwingPanel (inside EditorSurface, reached via the Column) always paints
        // above any lightweight Compose sibling, so an overlay would be invisible whenever KCEF is
        // the live surface. Returning early here means EditorSurface (and its KCEF engine) is never
        // composed while a diff is showing, sidestepping the z-order limitation entirely.
        if (editor.showDiff) {
            DiffView(
                repos = editor.diffRepos,
                comments = editor.diffComments,
                base = editor.diffBase,
                refs = editor.diffRefs,
                onSetBase = { spec -> scope.launch { editor.setDiffBase(spec, fsDiff) } },
                onAddComment = { repo, path, anchorLine, anchorContext, hunkHeader, body ->
                    onReviewAddComment(
                        AddCommentBody(
                            repo = repo,
                            path = path,
                            side = "RIGHT",
                            anchorLine = anchorLine,
                            anchorContext = anchorContext,
                            body = body,
                            diffHunkHeader = hunkHeader,
                        ),
                    )
                    Unit
                },
                onResolve = { commentId -> onReviewResolve(commentId); Unit },
                onSubmit = { onReviewSubmit(); Unit },
                onReload = { scope.launch { editor.reloadDiff(fsDiff) } },
                onClose = { editor.showDiff = false },
                // Off-by-default headless hook (M4g-2), paired with SM_DIFF: SM_DIFF_EXPAND=1 auto-
                // expands every file so a live screenshot shows diff lines with no pointer/xdotool.
                autoExpandAll = System.getenv("SM_DIFF_EXPAND")?.isNotBlank() == true,
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
            // ── Header: tree toggle · search · preview toggle · view-changes · save ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(cs.surfaceContainerLow)
                    .padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        searchResults.clear()
                        editor.treeVisible = !treeVisible
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("editor_tree_toggle"),
                ) {
                    Icon(
                        imageVector = if (treeVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.FolderOpen,
                        contentDescription = if (treeVisible) "Hide file tree" else "Show file tree",
                        tint = cs.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                EditorSearchField(
                    query = editor.searchQuery,
                    onQueryChange = { editor.searchQuery = it },
                    modifier = Modifier.weight(1f).padding(horizontal = Space.xs),
                )
                if (showPreviewToggle) {
                    IconButton(
                        onClick = { editor.previewMode = !editor.previewMode },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("editor_preview_toggle"),
                    ) {
                        Icon(
                            imageVector = if (editor.previewMode) Icons.Filled.Edit else Icons.Filled.Visibility,
                            contentDescription = if (editor.previewMode) "Edit" else "Preview",
                            tint = if (editor.previewMode) cs.primary else cs.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // "View changes" (diff) — opens the DiffView mode (M4g-2; Android EditorScreen
                // .kt:327-348 parity). A spinner replaces the button while the fetch is in flight.
                if (editor.diffLoading) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
                    }
                } else {
                    IconButton(
                        onClick = { scope.launch { editor.loadDiff(fsDiff, fsRefs) } },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("editor_view_changes"),
                    ) {
                        Icon(
                            Icons.Filled.Difference,
                            contentDescription = "View changes",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (editor.saving) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
                    }
                } else {
                    val dirty = activeTab?.let { editor.isDirty(it.path) } == true
                    IconButton(
                        onClick = { editor.saveActive() },
                        enabled = dirty,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("editor_save"),
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Save",
                            tint = if (dirty) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (treeVisible) {
                        Box(
                            Modifier
                                .width(192.dp)
                                .fillMaxHeight()
                                .background(cs.surfaceContainerHigh)
                                .testTag("editor_tree"),
                        ) {
                            FileTree(fsList = fsList, explorer = editor.explorer, onOpenFile = { revealFile(it) })
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(cs.outlineVariant))
                    }

                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        EditorTabs(
                            tabs = editor.tabs,
                            activeTabPath = editor.activeTabPath,
                            loadingPath = if (loadingNew) editor.loadingPath else null,
                            isDirty = editor::isDirty,
                            onSelect = { path ->
                                // Capture the outgoing tab's scroll before switching (Android
                                // EditorScreen:406-408 parity, via the EditorScrollReader seam).
                                captureOutgoingScroll(editor, reader)
                                editor.selectTab(path)
                            },
                            onClose = editor::closeTab,
                        )
                        HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)

                        // Stale-on-disk banner for the active tab (Android EditorScreen:416-443).
                        if (activeTab != null && editor.isStale(activeTab.path)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(cs.errorContainer.copy(alpha = 0.5f))
                                    .padding(horizontal = Space.md, vertical = Space.xs)
                                    .testTag("editor_stale_banner"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = cs.error, modifier = Modifier.size(16.dp))
                                Text(
                                    "File changed on disk",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onErrorContainer,
                                    modifier = Modifier.weight(1f).padding(start = Space.sm),
                                )
                                FilledTonalButton(
                                    onClick = { scope.launch { editor.reload(activeTab.path, fsRead) } },
                                    modifier = Modifier.heightIn(min = 36.dp).pointerHoverIcon(PointerIcon.Hand).testTag("editor_reload"),
                                ) {
                                    Text("Reload", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // Markdown preview is a CONDITIONAL SWAP, not an overlay (M4g-1 revision —
                            // see the "KNOWN DESKTOP LIMITATION" note this replaces, below). While
                            // showPreview is true, EditorSurface is NOT composed at all; the rendered
                            // MarkdownBody column takes its place. When toggled back off, EditorSurface
                            // recomposes from scratch (fresh KCEF engine init — the "keep it warm"
                            // optimization is traded away here) but no content is lost: activeTab.content
                            // lives in EditorState (remembered at the panel level, independent of
                            // EditorSurface's composition), so a preview→edit round-trip round-trips the
                            // same in-memory (possibly still-unsaved/dirty) text back into cm6's onChange
                            // sink — see EditorPanelTest's round-trip coverage.
                            //
                            // WHY NOT AN OVERLAY (the original M4g-1 design): DesktopTerminalPanel.kt
                            // :164-166 documents the same root cause — while KCEF is the active surface,
                            // EditorSurface hosts it via a heavyweight AWT [androidx.compose.ui.awt.
                            // SwingPanel] (WebCodeEditor.kt), which Compose Desktop paints ABOVE every
                            // lightweight Compose sibling regardless of composition order, with no
                            // Software-renderer workaround (`compose.interop.blending` is GPU-only).
                            // A same-parent-Box overlay is therefore invisible whenever KCEF is the live
                            // surface — confirmed by a headless Xvfb capture with a real (non-forced-
                            // error) KCEF session: the overlay never painted over the CodeMirror view.
                            // Swapping instead of overlaying sidesteps the z-order limitation entirely
                            // (there is no sibling to be occluded by), so the preview is visible on ANY
                            // renderer, not just a hypothetical future GPU-blending build.
                            if (showPreview && activeTab != null) {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(c.code))
                                        .verticalScroll(rememberScrollState())
                                        .padding(Space.lg)
                                        .testTag("editor_preview"),
                                ) {
                                    dev.supermux.desktop.chat.MarkdownBody(activeTab.content)
                                }
                            } else {
                                // The engine surface stays composed (pre-warmed) for the session; keyed
                                // on sessionId so a session switch rebuilds the engine rather than
                                // binding the wrong session's browser into this slot (obligation: key the
                                // unkeyed engine remember). NOT composed while showPreview is true (see
                                // above) — toggling preview off rebuilds it.
                                key(sessionId) {
                                    EditorSurface(
                                        kcefState = kcefState,
                                        content = activeTab?.content ?: "",
                                        filename = activeTab?.path ?: "",
                                        lineWrap = prefs.lineWrap,
                                        fontSize = prefs.fontSize,
                                        scrollTop = activeTab?.scrollTop ?: 0,
                                        revealLine = activeTab?.revealLine,
                                        onChange = { content -> activeTab?.path?.let { editor.updateContent(it, content) } },
                                        onSave = { editor.saveActive() },
                                        onRevealConsumed = { activeTab?.revealLine = null },
                                        onFontSize = onFontSize,
                                        onEnsureInit = onEnsureInit,
                                        scrollReader = reader,
                                        onLspOut = { serverId, message -> bridge.rpcOut(serverId, message) },
                                        onEngineReadyChange = { engineReady = it },
                                        lspHandle = lspHandle,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }

                            // Empty-state prompt over the (warm) surface until a file is opened.
                            if (editor.tabs.isEmpty() && editor.loadingPath == null && editor.loadError == null) {
                                Box(
                                    Modifier.fillMaxSize().background(Color(c.code).copy(alpha = 0.92f)).testTag("editor_empty"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Open a file from the tree or search", color = cs.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }

                            editor.loadError?.takeIf { editor.tabs.isEmpty() }?.let { err ->
                                Box(
                                    Modifier.fillMaxSize().background(Color(c.code).copy(alpha = 0.92f)).padding(Space.xl).testTag("editor_load_error"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(err, color = cs.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }

                            // Full-area spinner only while waiting for the very first file.
                            if (editor.tabs.isEmpty() && editor.loadingPath != null) {
                                Box(
                                    Modifier.fillMaxSize().background(Color(c.code).copy(alpha = 0.72f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = cs.primary)
                                        Text(
                                            editor.loadingPath!!.substringAfterLast('/'),
                                            color = cs.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = Space.sm),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (searchResults.isNotEmpty()) {
            EditorSearchOverlay(
                results = searchResults,
                onSelect = { revealFile(it) },
                onDismiss = {
                    focusManager.clearFocus()
                    editor.searchQuery = ""
                    searchResults.clear()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ─── Markdown-preview toggle (M4g-1) ───────────────────────────────────────

/** `.md` / `.markdown` → markdown preview eligible (verbatim port of Android
 *  EditorScreen.kt:582-583). `internal` (not `private`) so [EditorPanelMarkdownTest] can drive it
 *  directly — the pure/testable-seam discipline this module uses for KCEF-adjacent logic. */
internal fun isMarkdownPath(path: String): Boolean =
    path.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }

/** Pure derivation of the preview toggle/overlay visibility from the active tab's path and
 *  [EditorState.previewMode] — extracted so it's unit-testable without hosting Compose (the panel
 *  itself just calls this at composition time; see EditorPanel body). */
internal data class EditorPreviewGate(val showPreviewToggle: Boolean, val showPreview: Boolean)

/** M4g-2: restores the `&& !showDiff` clauses Android EditorScreen.kt:177-178 has and M4g-1
 *  deliberately omitted (diff mode didn't exist yet on desktop) — full Android parity now that
 *  [EditorState.showDiff] exists. Diff mode fully replaces the column (see the swap gate in
 *  [EditorPanel]'s body), so the preview toggle/overlay must never show alongside it. */
internal fun editorPreviewGate(activePath: String?, previewMode: Boolean, showDiff: Boolean = false): EditorPreviewGate {
    val activeIsMarkdown = activePath?.let(::isMarkdownPath) == true
    return EditorPreviewGate(
        showPreviewToggle = activeIsMarkdown && !showDiff,
        showPreview = previewMode && activeIsMarkdown && !showDiff,
    )
}

// ─── LSP file:// URI helpers (M4g-3; port of Android EditorScreen.kt:595-603) ─────────────────

/** Join a directory and a workdir-relative path with exactly one '/' between them. */
internal fun joinPath(dir: String, rel: String): String {
    val d = dir.removeSuffix("/")
    val r = rel.removePrefix("/")
    return "$d/$r"
}

/**
 * `file://` URI for an absolute path, percent-encoding every path SEGMENT except the `/`
 * separators — the JVM equivalent of Android's `android.net.Uri.encode(abs, "/")`
 * (`EditorScreen.kt:601`). [java.net.URLEncoder] is form-encoding (encodes space as `+`, not
 * `%20`), so each segment is encoded individually and `+` is repaired to `%20` before rejoining.
 */
internal fun pathToUri(abs: String): String {
    val encoded = abs.split("/").joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
    return "file://$encoded"
}

/** Directory URI for a workdir — always trailing-slash (Android `EditorScreen.kt:603` parity). */
internal fun dirUri(workdir: String): String = pathToUri(workdir.removeSuffix("/")) + "/"
