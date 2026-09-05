// The COMPOSITE editor — header + tab row + tree + one code surface — as one panel.
//
// Where [ExplorerPane]/[FilePane]/[DiffPane] are the editor cut into three workspace panes (see
// EditorPanes.kt), this is the single-pane shape a phone (and desktop's SessionDetail) needs: the
// tree is a slide-over drawer under Compact and a 192dp side pane otherwise, the tabs live in the
// panel rather than in a group's strip, and diff is a MODE that swaps the whole panel.
//
// Ported from `apps/android/.../editor/EditorScreen.kt` (cluster C4). Android's behaviour is kept
// verbatim — the fs-watch lifecycle, the `onConsumesBackChange` contract, the haptics, the reveal
// on a chat-initiated open — with three substitutions that make it multiplatform: drawable ids
// become Material icons, `androidx.activity.compose.BackHandler` becomes Compose Multiplatform's
// own (inert where the platform has no back gesture), and the markdown preview renders through the
// shared [MarkdownBody] (cluster D2).
package dev.supermux.ui.editor

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.supermux.net.AddCommentBody
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsEntry
import dev.supermux.net.FsRefsResult
import dev.supermux.net.FsSearchResult
import dev.supermux.net.ReviewComment
import dev.supermux.net.ReviewSubmitResult
import dev.supermux.proto.ServerFrame
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.chat.MarkdownBody
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.editor.engine.EditorScrollReader
import dev.supermux.ui.editor.engine.captureOutgoingScroll
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A chat-initiated request to open a workdir-relative [path] at an optional [line]. */
data class PendingEditorOpen(val path: String, val line: Int?, val endLine: Int?)

/**
 * WHAT the panel is looking at: the session it belongs to, that session's workdir, and the
 * app-wide broker flows it filters by session (fs-watch pulses and the LSP channels).
 */
data class EditorPanelState(
    val sessionId: String,
    val workdir: String,
    /** Live file-watch pulses (all sessions); the panel keeps only its own. */
    val fsChanges: Flow<ServerFrame.FsChanged> = MutableSharedFlow(),
    val lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = MutableStateFlow(emptyMap()),
    val lspRpc: Flow<ServerFrame.LspRpcIn> = MutableSharedFlow(),
)

/**
 * Everything the panel DOES, as one holder rather than twelve parameters. Grouping matters beyond
 * tidiness: these lambdas capture the caller's session object, so they are re-instanced on every
 * background session update — see the `remember(sessionId)` note on [EditorState] below for why
 * nothing in the panel may key on them.
 */
data class EditorPanelActions(
    val fsList: suspend (String) -> Result<List<FsEntry>>,
    val fsRead: suspend (String) -> Result<String>,
    val fsWrite: suspend (String, String) -> Boolean,
    val fsSearch: suspend (String) -> List<FsSearchResult>,
    /** Takes the base spec; [fsRefs] lists refs for the adjustable diff-base picker. */
    val fsDiff: suspend (String) -> FsDiffResult? = { null },
    val fsRefs: suspend () -> FsRefsResult? = { null },
    val reviewAddComment: suspend (AddCommentBody) -> ReviewComment? = { null },
    val reviewResolve: suspend (String) -> Boolean = { false },
    val reviewSubmit: suspend () -> ReviewSubmitResult? = { null },
    /** Start / stop the broker's fs-watcher for this session. Without them fs_changed never
     *  fires and the stale banner is dead. */
    val editorOpen: (String) -> Unit = {},
    val editorClose: (String) -> Unit = {},
    val lspStatusQuery: (String, String) -> Unit = { _, _ -> },
    val lspOpen: (String, String) -> Unit = { _, _ -> },
    val lspRpcOut: (String, String, String) -> Unit = { _, _, _ -> },
    /** Broker-side teardown seam. The panel drops its client by cancelling the connect effect, so
     *  nothing calls this yet; it stays wired so the call sites keep their broker plumbing. */
    val lspClose: (String, String) -> Unit = { _, _ -> },
)

/**
 * Code editor panel: lazy file tree, multi-tab editing, filename search.
 * Non-Compact (tablet / desktop): split sidebar. Compact (phone): slide-over tree drawer.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditorPanel(
    state: EditorPanelState,
    actions: EditorPanelActions,
    modifier: Modifier = Modifier,
    onConsumesBackChange: (Boolean) -> Unit = {},
    pendingOpen: PendingEditorOpen? = null,
    onPendingOpenConsumed: () -> Unit = {},
    /** Where a file link inside the markdown preview lands. Defaults to opening it in this panel. */
    onOpenFile: ((FilePathRef) -> Unit)? = null,
) {
    val sessionId = state.sessionId
    val workdir = state.workdir
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // The tree is a drawer only where the window cannot hold both at once.
    val expanded = LocalWindowWidthClass.current != WindowWidthClass.Compact

    // Own the editor state for the LIFETIME OF THE SESSION — deliberately NOT keyed on the
    // fs* lambdas. Those lambdas capture the whole `session` object, so every background
    // session update (status / git / finish-job flips while the agent works) re-instances
    // them; keying on them here would rebuild EditorState and wipe every open tab + unsaved
    // edit on each pulse. fsRead/fsWrite only ever call vm.<fs>(session.id, …) and session.id
    // is invariant for a given sessionId, so capturing the first instances stays correct.
    val editor = remember(sessionId) { EditorState(actions.fsRead, actions.fsWrite, scope) }

    if (editor.treeVisible == null) {
        SideEffect { editor.treeVisible = expanded }
    }
    val treeVisible = editor.treeVisible ?: expanded
    val searchResults = remember { mutableStateListOf<FsSearchResult>() }

    LaunchedEffect(editor.searchQuery) {
        delay(200)
        val q = editor.searchQuery.trim()
        if (q.isEmpty()) {
            searchResults.clear()
            return@LaunchedEffect
        }
        searchResults.clear()
        searchResults.addAll(actions.fsSearch(q))
    }

    // Editor prefs come from the shared SettingsStore (ui/prefs/UiPrefs.kt), whose reads are
    // asynchronous. The engine no longer rebuilds on a wrap change (the surface pushes
    // `cmSetLineWrap` into the live editor), so this wait is now only about not flashing the
    // default: nothing renders until the persisted values have landed (a single DataStore read —
    // the panel is already mounted asynchronously anyway).
    val prefs = LocalUiPrefs.current
    val loadedPrefs by produceState<Pair<Boolean, Int>?>(null, prefs) {
        value = prefs.editorLineWrap.first() to prefs.editorFontSize.first()
    }
    val (lineWrap, initialFontSize) = loadedPrefs ?: return
    // Live changes (Settings → Editor, or a pinch) still flow through; the seed above only fixes
    // the FIRST composition.
    val fontSize by prefs.editorFontSize.collectAsState(initialFontSize)

    // LSP bridge — orchestrates the cm6 LSPClient over the Phase-2 flows, filtered by session.
    val bridge = remember(sessionId, state.lspStatus, state.lspRpc) {
        LspBridge(
            sessionId = sessionId,
            lspStatus = state.lspStatus,
            lspRpc = state.lspRpc,
            lspStatusQuery = actions.lspStatusQuery,
            lspOpen = actions.lspOpen,
            lspRpcOut = actions.lspRpcOut,
        )
    }

    // The engine itself is owned by the shared [EditorSurface]; the panel reaches the live one
    // through these seams (scroll reads for a tab switch, the LSP push channel, its ready gate).
    val reader = remember { EditorScrollReader() }
    val lspHandle = remember(sessionId) { EditorLspHandle() }
    var engineReady by remember(sessionId) { mutableStateOf(false) }

    val activeIsMarkdown = editor.activeTab?.path?.let(::isMarkdownPath) == true
    val showPreviewToggle = activeIsMarkdown && !editor.showDiff
    val showPreview = editor.previewMode && activeIsMarkdown && !editor.showDiff

    // Editor lifecycle: tell the broker to start/stop the fs-watcher for this session.
    // This is ALSO what makes fs_changed fire — the stale banner is dead without it.
    DisposableEffect(sessionId) {
        actions.editorOpen(sessionId)
        onDispose { actions.editorClose(sessionId) }
    }

    // Live file-watch: fold fs_changed pulses for this session into the stale set.
    LaunchedEffect(sessionId, state.fsChanges) {
        state.fsChanges.collect { f -> if (f.session == sessionId) editor.markChanged(f.paths) }
    }

    // (Re)wire code intelligence whenever the active file (or diff/preview mode) changes.
    // LaunchedEffect cancellation tears down the prior client on a fast tab switch, and the
    // engine's OWN ready gate is a key — desktop's rule, replacing a fixed 1.2s "the WebView is
    // probably up by now" sleep, so a slow first paint no longer loses code intelligence.
    LaunchedEffect(editor.activeTabPath, editor.showDiff, showPreview, engineReady) {
        lspHandle.disconnect()
        val tab = editor.activeTab
        if (editor.showDiff || showPreview || tab == null || workdir.isEmpty() || !engineReady) {
            return@LaunchedEffect
        }
        val status = bridge.queryStatus(tab.path)
        val serverId = status.serverId
        // Status.isReady: supported && serverId != null && state == "ready" (LspBridge.swift:18).
        if (!status.supported || serverId == null || status.state != "ready") return@LaunchedEffect
        // Pump inbound RPC for this server in a child coroutine (cancelled with this effect).
        launch { bridge.pumpRpcIn(serverId) { sid, msg -> lspHandle.message(sid, msg) } }
        if (!bridge.open(serverId)) return@LaunchedEffect
        val rootUri = dirUri(workdir)
        val fileUri = pathToUri(joinPath(workdir, tab.path))
        lspHandle.connect(serverId, rootUri, fileUri, status.languageId ?: "")
    }

    fun revealFile(path: String, line: Int? = null, endLine: Int? = null) {
        focusManager.clearFocus()
        captureOutgoingScroll(editor, reader)
        editor.openFileAtLine(path, line, endLine)
        editor.searchQuery = ""
        searchResults.clear()
        if (!expanded) editor.treeVisible = false
    }

    LaunchedEffect(pendingOpen) {
        pendingOpen?.let {
            revealFile(it.path, it.line, it.endLine)
            onPendingOpenConsumed()
        }
    }
    val searchOpen = searchResults.isNotEmpty()
    val treeDrawerOpen = !expanded && treeVisible

    SideEffect {
        onConsumesBackChange(searchOpen || treeDrawerOpen)
    }

    DisposableEffect(Unit) {
        onDispose { onConsumesBackChange(false) }
    }

    BackHandler(enabled = searchOpen) {
        focusManager.clearFocus()
        editor.searchQuery = ""
        searchResults.clear()
    }
    BackHandler(enabled = treeDrawerOpen) {
        editor.treeVisible = false
    }

    val activeTab = editor.activeTab
    val loadingNew = editor.loadingPath?.let { path ->
        editor.tabs.none { it.path == path }
    } == true

    Box(modifier.fillMaxSize().testTag("editor_panel")) {
        // Diff is a MODE of the panel (parity EditorPane.swift:44): when showDiff, the
        // DiffView swaps the whole pane — header/tabs/tree/editor — exactly like iOS.
        if (editor.showDiff) {
            DiffView(
                repos = editor.diffRepos,
                comments = editor.diffComments,
                base = editor.diffBase,
                refs = editor.diffRefs,
                onSetBase = { base -> scope.launch { editor.setDiffBase(base, actions.fsDiff) } },
                onAddComment = { repo, path, anchorLine, anchorContext, hunkHeader, body ->
                    actions.reviewAddComment(
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
                onResolve = { commentId -> actions.reviewResolve(commentId); Unit },
                onSubmit = { actions.reviewSubmit(); Unit },
                onReload = { scope.launch { editor.reloadDiff(actions.fsDiff) } },
                onClose = { editor.showDiff = false },
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
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
                        haptic.perform(HapticKind.Tick)
                        focusManager.clearFocus()
                        searchResults.clear()
                        editor.treeVisible = !treeVisible
                    },
                    modifier = Modifier.testTag("editor_tree_toggle"),
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
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Space.xs),
                )
                // Markdown preview toggle — only on .md tabs (parity EditorPane.swift:158-166).
                if (showPreviewToggle) {
                    IconButton(onClick = { haptic.perform(HapticKind.Tick); editor.previewMode = !editor.previewMode }) {
                        Icon(
                            imageVector = if (editor.previewMode) Icons.Filled.Edit else Icons.Filled.Visibility,
                            contentDescription = if (editor.previewMode) "Edit" else "Preview",
                            tint = if (editor.previewMode) cs.primary else cs.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // View changes (diff) — opens the DiffView mode (parity EditorPane.swift:168-180).
                if (editor.diffLoading) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = cs.primary,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            haptic.perform(HapticKind.Tick)
                            focusManager.clearFocus()
                            scope.launch { editor.loadDiff(actions.fsDiff, actions.fsRefs) }
                        },
                        modifier = Modifier.testTag("editor_diff_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Difference,
                            contentDescription = "View changes",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (editor.saving) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = cs.primary,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            haptic.perform(HapticKind.Confirm)
                            editor.saveActive()
                        },
                        enabled = editor.activeTab?.let { editor.isDirty(it.path) } == true,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Save",
                            tint = if (editor.activeTab?.let { editor.isDirty(it.path) } == true) {
                                cs.primary
                            } else {
                                cs.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (expanded && treeVisible) {
                        Box(
                            Modifier
                                .width(192.dp)
                                .fillMaxHeight()
                                .background(cs.surfaceContainerHigh)
                                .testTag("editor_tree_pane"),
                        ) {
                            FileTree(
                                fsList = actions.fsList,
                                explorer = editor.explorer,
                                workdir = workdir,
                                onOpenFile = { revealFile(it) },
                            )
                        }
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(cs.outlineVariant),
                        )
                    }

                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        EditorTabs(
                            tabs = editor.tabs,
                            activeTabPath = editor.activeTabPath,
                            loadingPath = if (loadingNew) editor.loadingPath else null,
                            isDirty = editor::isDirty,
                            onSelect = { path ->
                                captureOutgoingScroll(editor, reader)
                                editor.selectTab(path)
                            },
                            onClose = editor::closeTab,
                        )
                        HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)

                        // Stale-on-disk banner for the active tab (parity EditorPane.swift:54-56,
                        // 275-287). Inline (not a Snackbar) since it's tied to the tab's state.
                        if (activeTab != null && editor.isStale(activeTab.path)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(cs.errorContainer.copy(alpha = 0.5f))
                                    .padding(horizontal = Space.md, vertical = Space.xs)
                                    .testTag("editor_stale_banner"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = cs.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    "File changed on disk",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onErrorContainer,
                                    modifier = Modifier.weight(1f).padding(start = Space.sm),
                                )
                                FilledTonalButton(
                                    onClick = { scope.launch { editor.reload(activeTab.path, actions.fsRead) } },
                                    modifier = Modifier.heightIn(min = 36.dp).testTag("editor_reload"),
                                ) {
                                    Text("Reload", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            EditorSurface(
                                content = activeTab?.content ?: "",
                                filename = activeTab?.path ?: "",
                                lineWrap = lineWrap,
                                fontSize = fontSize,
                                scrollTop = activeTab?.scrollTop ?: 0,
                                revealLine = activeTab?.revealLine,
                                onRevealConsumed = { activeTab?.revealLine = null },
                                onChange = { content ->
                                    activeTab?.path?.let { editor.updateContent(it, content) }
                                },
                                onSave = { editor.saveActive() },
                                // A pinch / keyboard zoom already applied itself in-page; this only
                                // persists it so it survives reopen (no rebuild).
                                onFontSize = { px -> scope.launch { prefs.putEditorFontSize(px) } },
                                scrollReader = reader,
                                onLspOut = { sid, msg -> bridge.rpcOut(sid, msg) },
                                onEngineReadyChange = { engineReady = it },
                                lspHandle = lspHandle,
                                modifier = Modifier.fillMaxSize(),
                            )

                            // Markdown preview overlay — covers (but keeps warm) the code surface
                            // when toggled on a .md tab (parity EditorPane.swift:240-245). Opaque so
                            // the editor underneath is hidden; the engine stays alive in remember.
                            if (showPreview && activeTab != null) {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(c.code))
                                        .verticalScroll(rememberScrollState())
                                        .padding(Space.lg)
                                        .testTag("editor_preview"),
                                ) {
                                    MarkdownBody(
                                        text = activeTab.content,
                                        linkify = true,
                                        onOpenFile = { ref ->
                                            val open = onOpenFile
                                            if (open != null) open(ref)
                                            else revealFile(ref.path, ref.line, ref.endLine)
                                        },
                                    )
                                }
                            }

                            if (editor.tabs.isEmpty() && editor.loadingPath == null && editor.loadError == null) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(c.code).copy(alpha = 0.92f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "Open a file from the tree or search",
                                        color = cs.onSurfaceVariant,
                                        fontSize = 13.sp,
                                    )
                                }
                            }

                            editor.loadError?.takeIf { editor.tabs.isEmpty() }?.let { err ->
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(c.code).copy(alpha = 0.92f))
                                        .padding(Space.xl)
                                        .testTag("editor_load_error"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(err, color = cs.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }

                            // Full-area spinner only while waiting for the very first file.
                            if (editor.tabs.isEmpty() && editor.loadingPath != null) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(c.code).copy(alpha = 0.72f))
                                        .testTag("editor_file_loading"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            color = cs.primary,
                                        )
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

                androidx.compose.animation.AnimatedVisibility(
                    visible = !expanded && treeVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .testTag("editor_tree_scrim")
                                .clickable {
                                    haptic.perform(HapticKind.Tick)
                                    editor.treeVisible = false
                                },
                        )
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(280.dp)
                                .background(cs.surfaceContainerHigh)
                                .testTag("editor_tree_drawer"),
                        ) {
                            FileTree(
                                fsList = actions.fsList,
                                explorer = editor.explorer,
                                workdir = workdir,
                                onOpenFile = { revealFile(it) },
                            )
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
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f),
            )
        }
    }
}
