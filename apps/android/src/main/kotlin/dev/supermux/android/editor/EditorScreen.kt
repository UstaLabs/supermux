package dev.supermux.android.editor

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.supermux.android.R
import dev.supermux.android.chat.MarkdownBody
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.net.AddCommentBody
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import dev.supermux.net.ReviewComment
import dev.supermux.net.ReviewSubmitResult
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A chat-initiated request to open a workdir-relative [path] at an optional [line]. */
data class PendingEditorOpen(val path: String, val line: Int?, val endLine: Int?)

/**
 * Code editor panel: lazy file tree, multi-tab editing, filename search.
 * Tablet (Expanded): split sidebar. Phone: slide-over tree drawer.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun EditorPanel(
    sessionId: String,
    workdir: String,
    fsList: suspend (String) -> List<FsEntry>,
    fsRead: suspend (String) -> Result<String>,
    fsWrite: suspend (String, String) -> Boolean,
    fsSearch: suspend (String) -> List<FsSearchResult>,
    // Phase 2 — diff + inline code-review.
    fsDiff: suspend () -> FsDiffResult? = { null },
    reviewAddComment: suspend (AddCommentBody) -> ReviewComment? = { null },
    reviewResolve: suspend (String) -> Boolean = { false },
    reviewSubmit: suspend () -> ReviewSubmitResult? = { null },
    // Phase 4 + 5 — LSP + live file-watch. Flows are app-wide; bridge/banner filter by session.
    fsChanges: SharedFlow<ServerFrame.FsChanged> = MutableSharedFlow(),
    lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = MutableStateFlow(emptyMap()),
    lspRpc: SharedFlow<ServerFrame.LspRpcIn> = MutableSharedFlow(),
    editorOpen: (String) -> Unit = {},
    editorClose: (String) -> Unit = {},
    lspStatusQuery: (String, String) -> Unit = { _, _ -> },
    lspOpen: (String, String) -> Unit = { _, _ -> },
    lspRpcOut: (String, String, String) -> Unit = { _, _, _ -> },
    lspClose: (String, String) -> Unit = { _, _ -> },
    onConsumesBackChange: (Boolean) -> Unit = {},
    pendingOpen: PendingEditorOpen? = null,
    onPendingOpenConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val windowSizeClass = calculateWindowSizeClass(context as Activity)
    val expanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    // Own the editor state for the LIFETIME OF THE SESSION — deliberately NOT keyed on the
    // fs* lambdas. Those lambdas capture the whole `session` object, so every background
    // session update (status / git / finish-job flips while the agent works) re-instances
    // them; keying on them here would rebuild EditorState and wipe every open tab + unsaved
    // edit on each pulse. fsRead/fsWrite only ever call vm.<fs>(session.id, …) and session.id
    // is invariant for a given sessionId, so capturing the first instances stays correct.
    val editor = remember(sessionId) {
        EditorState(fsRead, fsWrite, scope)
    }

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
        searchResults.addAll(fsSearch(q))
    }

    val prefs = context.getSharedPreferences("cmux-editor-settings", Context.MODE_PRIVATE)
    val lineWrap = prefs.getBoolean("lineWrap", true)
    val fontSize = prefs.getInt("fontSize", 13)

    // LSP bridge — orchestrates the cm6 LSPClient over the Phase-2 flows, filtered by session.
    val bridge = remember(sessionId, lspStatus, lspRpc) {
        AndroidLspBridge(
            sessionId = sessionId,
            lspStatus = lspStatus,
            lspRpc = lspRpc,
            lspStatusQuery = lspStatusQuery,
            lspOpen = lspOpen,
            lspRpcOut = lspRpcOut,
        )
    }

    val engine = rememberEditorEngine(
        lineWrap = lineWrap,
        fontSize = fontSize,
        onChange = { content -> editor.activeTab?.path?.let { editor.updateContent(it, content) } },
        onSave = { editor.saveActive() },
        // cm6 posts `{serverId,message}` JSON; forward it verbatim to the broker.
        onLspOut = { payload ->
            val (sid, msg) = parseLspOut(payload) ?: return@rememberEditorEngine
            bridge.rpcOut(sid, msg)
        },
        // A pinch / keyboard zoom in the WebView persists here so it survives reopen.
        // The engine already applied it live, so this only writes the pref (no rebuild).
        onFontSize = { px -> prefs.edit().putInt("fontSize", px.coerceIn(10, 24)).apply() },
    )

    val activeIsMarkdown = editor.activeTab?.path?.let(::isMarkdownPath) == true
    val showPreviewToggle = activeIsMarkdown && !editor.showDiff
    val showPreview = editor.previewMode && activeIsMarkdown && !editor.showDiff

    // Editor lifecycle: tell the broker to start/stop the fs-watcher for this session.
    // This is ALSO what makes fs_changed fire — the stale banner is dead without it.
    DisposableEffect(sessionId) {
        editorOpen(sessionId)
        onDispose { editorClose(sessionId) }
    }

    // Live file-watch: fold fs_changed pulses for this session into the stale set.
    LaunchedEffect(sessionId, fsChanges) {
        fsChanges.collect { f -> if (f.session == sessionId) editor.markChanged(f.paths) }
    }

    // (Re)wire code intelligence whenever the active file (or diff/preview mode) changes.
    // LaunchedEffect cancellation tears down the prior client on a fast tab switch, and
    // re-keying on engine.failed re-runs after a renderer crash recovers (parity §7.3).
    LaunchedEffect(editor.activeTabPath, editor.showDiff, showPreview, engine.failed) {
        engine.lspDisconnect()
        val tab = editor.activeTab
        if (editor.showDiff || showPreview || tab == null || workdir.isEmpty() || engine.failed) {
            return@LaunchedEffect
        }
        delay(1_200) // let the WebView reach `ready` (parity EditorPane.swift:102)
        val status = bridge.queryStatus(tab.path)
        val serverId = status.serverId
        // Status.isReady: supported && serverId != null && state == "ready" (LspBridge.swift:18).
        if (!status.supported || serverId == null || status.state != "ready") return@LaunchedEffect
        // Pump inbound RPC for this server in a child coroutine (cancelled with this effect).
        launch { bridge.pumpRpcIn(serverId) { sid, msg -> engine.lspMessage(sid, msg) } }
        if (!bridge.open(serverId)) return@LaunchedEffect
        val rootUri = dirUri(workdir)
        val fileUri = pathToUri(joinPath(workdir, tab.path))
        engine.lspConnect(serverId, rootUri, fileUri, status.languageId ?: "")
    }

    fun revealFile(path: String, line: Int? = null, endLine: Int? = null) {
        focusManager.clearFocus()
        engine.readScrollTop { scroll -> editor.captureActiveScroll(scroll) }
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

    Box(modifier.fillMaxSize()) {
        // Diff is a MODE of the panel (parity EditorPane.swift:44): when showDiff, the
        // DiffView swaps the whole pane — header/tabs/tree/editor — exactly like iOS.
        if (editor.showDiff) {
            DiffView(
                repos = editor.diffRepos,
                comments = editor.diffComments,
                onAddComment = { repo, path, anchorLine, anchorContext, hunkHeader, body ->
                    reviewAddComment(
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
                onResolve = { commentId -> reviewResolve(commentId); Unit },
                onSubmit = { reviewSubmit(); Unit },
                onReload = { scope.launch { editor.reloadDiff(fsDiff) } },
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
                IconButton(onClick = {
                    haptic(HapticKind.Tick)
                    focusManager.clearFocus()
                    searchResults.clear()
                    editor.treeVisible = !treeVisible
                }) {
                    Icon(
                        painter = painterResource(
                            if (treeVisible) R.drawable.ic_chevron_down else R.drawable.ic_folder_open,
                        ),
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
                    IconButton(onClick = { haptic(HapticKind.Tick); editor.previewMode = !editor.previewMode }) {
                        Icon(
                            painter = painterResource(
                                if (editor.previewMode) R.drawable.ic_pencil else R.drawable.ic_eye,
                            ),
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
                    IconButton(onClick = {
                        haptic(HapticKind.Tick)
                        focusManager.clearFocus()
                        scope.launch { editor.loadDiff(fsDiff) }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_diff),
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
                            haptic(HapticKind.Confirm)
                            editor.saveActive()
                        },
                        enabled = editor.activeTab?.let { editor.isDirty(it.path) } == true,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
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
                                .background(cs.surfaceContainerHigh),
                        ) {
                            FileTree(fsList = fsList, editor = editor, onOpenFile = { revealFile(it) })
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
                                engine.readScrollTop { scroll -> editor.captureActiveScroll(scroll) }
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
                                    .padding(horizontal = Space.md, vertical = Space.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_alert_triangle),
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
                                    onClick = { scope.launch { editor.reload(activeTab.path, fsRead) } },
                                    modifier = Modifier.heightIn(min = 36.dp),
                                ) {
                                    Text("Reload", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // Pre-warm WebView as soon as the editor panel opens.
                            WebCodeEditor(
                                engine = engine,
                                content = activeTab?.content ?: "",
                                filename = activeTab?.path ?: "",
                                fontSize = fontSize,
                                scrollTop = activeTab?.scrollTop ?: 0,
                                revealLine = activeTab?.revealLine,
                                onRevealConsumed = { activeTab?.revealLine = null },
                                onChange = { content ->
                                    activeTab?.path?.let { editor.updateContent(it, content) }
                                },
                                onSave = { editor.saveActive() },
                                modifier = Modifier.fillMaxSize(),
                            )

                            // Markdown preview overlay — covers (but keeps warm) the WebView when
                            // toggled on a .md tab (parity EditorPane.swift:240-245). Opaque so the
                            // editor underneath is hidden; the engine stays alive in remember.
                            if (showPreview && activeTab != null) {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(c.code))
                                        .verticalScroll(rememberScrollState())
                                        .padding(Space.lg),
                                ) {
                                    MarkdownBody(activeTab.content)
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
                                        .padding(Space.xl),
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
                                        .background(Color(c.code).copy(alpha = 0.72f)),
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
                                .clickable {
                                    haptic(HapticKind.Tick)
                                    editor.treeVisible = false
                                },
                        )
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(280.dp)
                                .background(cs.surfaceContainerHigh),
                        ) {
                            FileTree(fsList = fsList, editor = editor, onOpenFile = { revealFile(it) })
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

// ─── Editor helpers (markdown detection, LSP-out parsing, file URIs) ───────────

/** `.md` / `.markdown` → markdown preview eligible (parity EditorPane.swift:30-33). */
private fun isMarkdownPath(path: String): Boolean =
    path.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }

/** Parse cm6's outbound `{serverId,message}` JSON payload → (serverId, message). */
private fun parseLspOut(payload: String): Pair<String, String>? = runCatching {
    val o = org.json.JSONObject(payload)
    val serverId = o.optString("serverId")
    val message = o.optString("message")
    if (serverId.isEmpty()) null else serverId to message
}.getOrNull()

// file:// URI construction (port EditorPane.swift:120-131). Uri.encode keeps "/" so path
// separators survive, matching iOS's .urlPathAllowed percent-encoding.
private fun joinPath(dir: String, rel: String): String {
    val d = dir.removeSuffix("/")
    val r = rel.removePrefix("/")
    return "$d/$r"
}

private fun pathToUri(abs: String): String = "file://" + android.net.Uri.encode(abs, "/")

private fun dirUri(workdir: String): String = pathToUri(workdir.removeSuffix("/")) + "/"
