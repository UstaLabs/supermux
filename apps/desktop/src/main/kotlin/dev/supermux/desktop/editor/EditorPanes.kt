// Phase 3 of the pane-system unification (docs/superpowers/specs/2026-08-09-…-design.md §7.2):
// the editor's three PARTS, each drawable as an ordinary workspace pane.
//
// [EditorPanel] is the composite — header + 192dp tree + its own tab row — and it stays for the old
// shell (SessionDetail) until phase 4 deletes it. What lives HERE is the same behaviour cut into
// three pieces that a workspace group can hold as tabs:
//
//   [ExplorerPane]  the file tree + the filename search   (view state mode = "tree")
//   [FilePane]      ONE document on one code surface      (view state mode = "file", path = …)
//   [DiffPane]      the diff + inline review comments     (view state mode = "diff")
//
// Two rules the composite did not have to obey, and these do:
//
//  1. The text is NOT in the pane. Every [FilePane] reads its [Document] out of a [DocumentStore]
//     that the WORKSPACE owns, so two panes over one path are two views of one buffer: a split
//     shows the same unsaved text on both sides, and dragging a file tab between groups cannot
//     lose an edit (the pane is destroyed and rebuilt; the document never moves).
//  2. A pane is composed only while it is the ACTIVE tab of its group — PaneHost guarantees that,
//     and it is load-bearing, not an optimisation. [FilePane] therefore builds its JCEF engine on
//     composition; one live engine per background tab would exhaust memory. Nothing here may
//     pre-warm a surface for a tab the user is not looking at.
package dev.supermux.desktop.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

// ── Explorer ──────────────────────────────────────────────────────────────────────────────────

/**
 * The file tree and the filename search, as a pane.
 *
 * This is [EditorPanel]'s 192dp sidebar plus its search field, with the sidebar's fixed width
 * removed: a pane is sized by the splitter around it, which is the point of the change — the tree
 * is a real split, not a strip nailed to the side of the editor.
 *
 * [onOpenFile] is a REQUEST, not an action: the pane does not know where the file will land. The
 * workspace decides that (see WorkspaceFileOpen.kt) and owns the document.
 */
@Composable
fun ExplorerPane(
    fsList: suspend (String) -> List<FsEntry>,
    explorer: ExplorerState,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
    fsSearch: suspend (String) -> List<FsSearchResult> = { emptyList() },
) {
    val cs = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val searchResults = remember { mutableStateListOf<FsSearchResult>() }

    // Same 200ms debounce as the composite panel — a keystroke must not be a broker round trip.
    LaunchedEffect(explorer.searchQuery) {
        delay(200)
        val q = explorer.searchQuery.trim()
        if (q.isEmpty()) {
            searchResults.clear()
            return@LaunchedEffect
        }
        searchResults.clear()
        searchResults.addAll(fsSearch(q))
    }

    fun open(path: String) {
        focusManager.clearFocus()
        explorer.searchQuery = ""
        searchResults.clear()
        onOpenFile(path)
    }

    // The tag goes on an INNER node, never on the caller's modifier: two testTag calls on one
    // modifier chain keep the OUTER one, so a pane that tagged `modifier` would be invisible to
    // any caller that had already tagged it.
    Box(modifier.fillMaxSize().background(cs.surfaceContainerHigh)) {
        Column(Modifier.fillMaxSize().testTag("editor_explorer_pane")) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorSearchField(
                    query = explorer.searchQuery,
                    onQueryChange = { explorer.searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)
            Box(Modifier.weight(1f).fillMaxWidth().testTag("editor_tree")) {
                FileTree(fsList = fsList, explorer = explorer, onOpenFile = { open(it) })
            }
        }
        if (searchResults.isNotEmpty()) {
            EditorSearchOverlay(
                results = searchResults,
                onSelect = { open(it) },
                onDismiss = {
                    focusManager.clearFocus()
                    explorer.searchQuery = ""
                    searchResults.clear()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ── One document ──────────────────────────────────────────────────────────────────────────────

/**
 * ONE file on one code surface: the save button, the changed-on-disk banner, the markdown-preview
 * swap and the LSP connect sequencing that [EditorPanel] runs for its ACTIVE tab — with no tab row
 * of its own, because the group's tab row IS the tab row now.
 *
 * The document comes from [documents]; this pane only asks for it. Everything mutable about the
 * file (text, dirty state, scroll, pending reveal) lives in that store, so this composable can be
 * destroyed and rebuilt — by a drag, a split, a tab switch — without the file noticing.
 *
 * The markdown preview is a SWAP, not an overlay, for the same reason it is in [EditorPanel]:
 * JCEF's heavyweight AWT child always paints above lightweight Compose siblings, so an overlay is
 * invisible while the engine is live. While the preview shows, [EditorSurface] is not composed.
 */
@Composable
fun FilePane(
    path: String,
    documents: DocumentStore,
    modifier: Modifier = Modifier,
    /** Used only by the stale banner's Reload button (the store's own reader is constructor-bound). */
    fsRead: suspend (String) -> Result<String> = { Result.failure(IllegalStateException("no reader")) },
    workdir: String = "",
    /** LSP is still keyed by session. Null → no code intelligence, and the pane says so quietly. */
    lspSessionId: String? = null,
    lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = MutableStateFlow(emptyMap()),
    lspRpc: SharedFlow<ServerFrame.LspRpcIn> = MutableSharedFlow(),
    lspStatusQuery: (String, String) -> Unit = { _, _ -> },
    lspOpen: (String, String) -> Unit = { _, _ -> },
    lspRpcOut: (String, String, String) -> Unit = { _, _, _ -> },
    prefs: EditorPrefs = EditorPrefs(),
    onFontSize: (Int) -> Unit = {},
    jcefStateFlow: StateFlow<JcefState> = JcefRuntime.state,
    onEnsureInit: (CoroutineScope) -> Unit = { JcefRuntime.ensureInit(it) },
    /**
     * Markdown preview, hoisted. It used to be local state driven by a button in this pane's action
     * row; that row is gone (the tab carries the per-file controls now), so the caller holds it.
     */
    previewMode: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val c = LocalPanes.current
    val scope = rememberCoroutineScope()
    val jcefState by jcefStateFlow.collectAsState()

    // Ask the store for the document. Already open (another pane, an earlier visit) → an immediate
    // hit and no read; otherwise the store's in-flight guard means two panes racing on one cold
    // path still issue ONE fsRead.
    LaunchedEffect(path) { documents.open(path) }
    val doc = documents.get(path)

    val reader = remember { EditorScrollReader() }
    val lspHandle = remember(lspSessionId) { EditorLspHandle() }
    var engineReady by remember(lspSessionId) { mutableStateOf(false) }
    val bridge = remember(lspSessionId, lspStatus, lspRpc) {
        lspSessionId?.let {
            DesktopLspBridge(
                sessionId = it,
                lspStatus = lspStatus,
                lspRpc = lspRpc,
                lspStatusQuery = lspStatusQuery,
                lspOpen = lspOpen,
                lspRpcOut = lspRpcOut,
            )
        }
    }

    val previewGate = editorPreviewGate(path, previewMode, showDiff = false)
    val showPreview = previewGate.showPreview

    // LSP connect sequencing — the EditorPanel effect with the tab lookup removed (this pane IS the
    // tab). Cancellation on a key change tears down the previous connection.
    LaunchedEffect(lspSessionId, path, showPreview, engineReady) {
        lspHandle.disconnect()
        if (bridge == null || showPreview || workdir.isEmpty() || !engineReady) return@LaunchedEffect
        val status = bridge.queryStatus(path)
        val serverId = status.serverId
        if (!status.supported || serverId == null || status.state != "ready") {
            println("[lsp] '$path' not ready for LSP (state=${status.state}, supported=${status.supported})")
            return@LaunchedEffect
        }
        launch { bridge.pumpRpcIn(serverId) { sid, msg -> lspHandle.message(sid, msg) } }
        if (!bridge.open(serverId)) {
            println("[lsp] open($serverId) failed for '$path'")
            return@LaunchedEffect
        }
        lspHandle.connect(serverId, dirUri(workdir), pathToUri(joinPath(workdir, path)), status.languageId ?: "")
    }

    val dirty = documents.isDirty(path)
    val stale = documents.isStale(path)

    // The tag goes on an INNER node — see the note in [ExplorerPane].
    Box(modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().testTag("editor_file_pane")) {
        if (lspSessionId == null) {
            Text(
                "No agent in this workspace — code intelligence is off.",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = Space.sm, vertical = Space.xs)
                    .testTag("editor-no-lsp"),
            )
        }
        // No action row. Save and the markdown toggle are per-FILE controls, so they live on the
        // file's TAB (see WorkspaceFileTab) — a strip of chrome above every document, holding two
        // buttons, was a row of the old composite editor that nothing here needed to inherit.

        if (stale) {
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
                    onClick = { scope.launch { documents.reload(path, fsRead) } },
                    modifier = Modifier.heightIn(min = 32.dp).pointerHoverIcon(PointerIcon.Hand).testTag("editor_reload"),
                ) {
                    Text("Reload", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showPreview) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(c.code))
                        .verticalScroll(rememberScrollState())
                        .padding(Space.lg)
                        .testTag("editor_preview"),
                ) {
                    dev.supermux.desktop.chat.MarkdownBody(doc?.content ?: "")
                }
            } else {
                EditorSurface(
                    jcefState = jcefState,
                    // An empty filename means "no document" to the surface, which lays the browser
                    // out at 0×0. Hold it back until the read lands so the engine is born full-size.
                    content = doc?.content ?: "",
                    filename = if (doc != null) path else "",
                    lineWrap = prefs.lineWrap,
                    fontSize = prefs.fontSize,
                    scrollTop = doc?.scrollTop ?: 0,
                    revealLine = doc?.revealLine,
                    onChange = { documents.update(path, it) },
                    onSave = { doc?.let { d -> documents.save(d) } },
                    onRevealConsumed = { doc?.revealLine = null },
                    onFontSize = onFontSize,
                    onEnsureInit = onEnsureInit,
                    scrollReader = reader,
                    onLspOut = { serverId, message -> bridge?.rpcOut(serverId, message) },
                    onEngineReadyChange = { engineReady = it },
                    lspHandle = lspHandle,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (doc == null) {
                val err = documents.loadError?.takeIf { documents.loadingPath != path }
                Box(
                    Modifier.fillMaxSize().background(Color(c.code).copy(alpha = 0.92f)).padding(Space.xl)
                        .testTag(if (err != null) "editor_load_error" else "editor_file_loading"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (err != null) {
                        Text(err, color = cs.onSurfaceVariant, fontSize = 13.sp)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = cs.primary)
                            Text(
                                path.substringAfterLast('/'),
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

// ── Diff ──────────────────────────────────────────────────────────────────────────────────────

/**
 * The diff and its inline review comments, as a pane. [EditorPanel] draws the same [DiffView] as a
 * MODE that replaces the whole panel; here the pane IS the mode, so there is nothing to swap.
 *
 * The fetch fires once on composition (the composite fires it from the "View changes" button, which
 * this pane does not have — opening the tab is the button).
 */
@Composable
fun DiffPane(
    diff: DiffState,
    fsDiff: suspend (String) -> FsDiffResult?,
    fsRefs: suspend () -> FsRefsResult?,
    modifier: Modifier = Modifier,
    onReviewAddComment: suspend (AddCommentBody) -> ReviewComment? = { null },
    onReviewResolve: suspend (String) -> Boolean = { false },
    onReviewSubmit: suspend () -> ReviewSubmitResult? = { null },
    onClose: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(diff) {
        if (diff.diffRepos.isEmpty() && !diff.diffLoading) diff.loadDiff(fsDiff, fsRefs)
    }

    Box(modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().testTag("editor_diff_pane")) {
        DiffView(
            repos = diff.diffRepos,
            comments = diff.diffComments,
            base = diff.diffBase,
            refs = diff.diffRefs,
            onSetBase = { spec -> scope.launch { diff.setDiffBase(spec, fsDiff) } },
            onAddComment = { repo, p, anchorLine, anchorContext, hunkHeader, body ->
                onReviewAddComment(
                    AddCommentBody(
                        repo = repo,
                        path = p,
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
            onReload = { scope.launch { diff.reloadDiff(fsDiff) } },
            // The pane's close IS the tab's close — there is no "back to the editor" here.
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
      }
    }
}
