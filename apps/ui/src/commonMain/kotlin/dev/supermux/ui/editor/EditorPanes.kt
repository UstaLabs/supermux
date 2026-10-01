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
//     and it is load-bearing, not an optimisation. [FilePane] borrows its document's view on
//     composition and gives it back on disposal; nothing here may pre-warm a surface for a tab
//     the user is not looking at.
package dev.supermux.ui.editor

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.Space
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.chat.LocalMarkdownFiles
import dev.supermux.ui.chat.MarkdownBody
import dev.supermux.ui.chat.MarkdownFiles
import dev.supermux.ui.files.parentOf
import dev.supermux.net.AddCommentBody
import dev.supermux.net.BlobText
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsRefsResult
import dev.supermux.net.ReviewComment
import dev.supermux.net.ReviewSubmitResult
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import dev.supermux.ui.prefs.EDITOR_LINE_WRAP_DEFAULT
import dev.supermux.ui.prefs.FILES_REVEAL_ACTIVE_DEFAULT
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import dev.supermux.fs.FileSystemService
import dev.supermux.ui.files.FileTreeDialog
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
import dev.supermux.ui.files.FileTreeHeader
import dev.supermux.ui.files.FileTreeWithActions
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.files.TreeViewState
import dev.supermux.ui.files.FileSearch
import dev.supermux.ui.files.GoToEntry
import dev.supermux.ui.files.goToFileShortcut
import androidx.compose.ui.focus.FocusRequester
import dev.supermux.ui.files.editorAbsolutePath
import dev.supermux.ui.files.editorPathFor
import dev.supermux.ui.files.relativeToWorkdir

// ── Explorer ──────────────────────────────────────────────────────────────────────────────────

/**
 * The file tree and its "Go to file…" fuzzy search ([FileSearch]; ⌘P / Ctrl+P), as a pane.
 *
 * The tree is the live host tree ([FileTreeView]) over [fileSystem]; its paths are ABSOLUTE. What
 * leaves the pane is an editor key ([editorPathFor]): relative to [workdir] for a file inside it,
 * absolute for one outside it (reachable by browsing up via the breadcrumbs).
 *
 * [view] is held by the caller (per view id, outliving the pane), so a drag/split/re-tab keeps the
 * open folders, selection and scroll.
 *
 * [onOpenFile] is a REQUEST, not an action: the pane does not know where the file will land. The
 * workspace decides that (see WorkspaceFileOpen.kt) and owns the document.
 *
 * [onEntryMoved] reports a successful rename/delete from the tree (absolute paths; new = null for a
 * delete) so the workspace can retarget or flag the file tabs open under it — see
 * `applyEntryMoved` in WorkspaceSession.kt.
 */
@Composable
fun ExplorerPane(
    fileSystem: FileSystemService?,
    view: TreeViewState,
    workdir: String,
    onOpenFile: (editorPath: String) -> Unit,
    modifier: Modifier = Modifier,
    activeRelativePath: String? = null,
    onEntryMoved: (oldAbsolutePath: String, newAbsolutePath: String?) -> Unit = { _, _ -> },
) {
    val cs = MaterialTheme.colorScheme
    val searchFocus = remember { FocusRequester() }

    val openAbsolute: (String) -> Unit = { abs ->
        relativeToWorkdir(workdir, abs)?.takeIf { it != "." }?.let(view::noteOpened)
        onOpenFile(editorPathFor(workdir, abs))
    }

    val onGoTo: (GoToEntry) -> Unit = { e ->
        if (e.isDir) {
            // A folder hit is shown, not opened: open it in the tree and select it.
            view.reveal(e.absolutePath)
            view.expand(e.absolutePath)
        } else {
            openAbsolute(e.absolutePath)
        }
    }

    val activePath = activeRelativePath?.takeIf { it.isNotEmpty() && it != "." }?.let { editorAbsolutePath(workdir, it) }
    val prefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val hardwareKeyboard = LocalHardwareKeyboard.current
    val revealActive by prefs.filesRevealActive.collectAsState(FILES_REVEAL_ACTIVE_DEFAULT)

    // The tag goes on an INNER node, never on the caller's modifier: two testTag calls on one
    // modifier chain keep the OUTER one, so a pane that tagged `modifier` would be invisible to
    // any caller that had already tagged it.
    Box(
        modifier
            .fillMaxSize()
            .background(cs.surfaceContainerHigh)
            // ⌘P / Ctrl+P while focus is anywhere in the pane (the tree, the header).
            .goToFileShortcut { runCatching { searchFocus.requestFocus() } },
    ) {
        FileSearch(
            fileSystem = fileSystem,
            view = view,
            workdir = workdir,
            onOpen = onGoTo,
            focusRequester = searchFocus,
            modifier = Modifier.fillMaxSize().testTag("editor_explorer_pane"),
        ) {
            FileTreeHeader(
                view = view,
                fileSystem = fileSystem,
                revealActive = revealActive,
                onRevealActiveChange = { on -> scope.launch { prefs.putFilesRevealActive(on) } },
                // Creates at the tree's root; the tree below draws the dialog or the in-place row
                // (it reads view.dialog / view.inlineEdit).
                onNewEntry = if (fileSystem == null) null else { folder ->
                    view.startAction(FileTreeDialog.NewEntry(view.rootPath, folder), inline = hardwareKeyboard)
                },
            )
            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)
            // FileTreeView tags its own list `editor_tree`; the offline hint carries the tag itself
            // so the pane has exactly one `editor_tree` node either way.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (fileSystem == null) {
                    Box(Modifier.fillMaxSize().testTag("editor_tree"), contentAlignment = Alignment.Center) {
                        Text("Host offline", color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.testTag("editor_tree_offline"))
                    }
                } else {
                    FileTreeWithActions(
                        fileSystem = fileSystem,
                        view = view,
                        onOpenFile = openAbsolute,
                        activePath = activePath,
                        revealActive = revealActive,
                        compact = !LocalPointerAvailable.current || LocalWindowWidthClass.current == WindowWidthClass.Compact,
                        onEntryMoved = onEntryMoved,
                    )
                }
            }
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
 * The markdown preview is an OVERLAY: the native editor stays composed (read-only, unfocused) under
 * it, so the view, its LSP and its scroll survive a toggle.
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
    lspRpc: Flow<ServerFrame.LspRpcIn> = MutableSharedFlow(),
    lspStatusQuery: (String, String) -> Unit = { _, _ -> },
    lspOpen: (String, String) -> Unit = { _, _ -> },
    lspRpcOut: (String, String, String) -> Unit = { _, _, _ -> },
    lspClose: (String, String) -> Unit = { _, _ -> },
    lineWrap: Boolean = EDITOR_LINE_WRAP_DEFAULT,
    fontSize: Int = EDITOR_FONT_DEFAULT,
    onFontSize: (Int) -> Unit = {},
    /**
     * Markdown preview, hoisted. It used to be local state driven by a button in this pane's action
     * row; that row is gone (the tab carries the per-file controls now), so the caller holds it.
     */
    previewMode: Boolean = false,
    /** Where a file link inside the preview lands. */
    onOpenFile: (FilePathRef) -> Unit = {},
    /** Where an LSP definition or reference in another file opens (an editor key, 1-based line). */
    onNavigate: (path: String, line: Int) -> Unit = { _, _ -> },
    /**
     * A file's bytes by ABSOLUTE path (`/fs/raw`). With it an image or a video previews instead of
     * opening as text, and a binary file the text reader refuses offers to open elsewhere.
     */
    rawBytes: (suspend (String) -> Result<ByteArray>)? = null,
    /** Keeps an image preview current when the file changes on disk. */
    fileSystem: FileSystemService? = null,
) {
    val cs = MaterialTheme.colorScheme
    val c = LocalPanes.current
    val scope = rememberCoroutineScope()

    // Absolute: an editor key outside the workdir is one already; a relative one needs the workdir.
    val absPath = if (dev.supermux.ui.isAbsoluteEditorPath(path) || workdir.isNotEmpty()) editorAbsolutePath(workdir, path) else null
    val previewKind = filePreviewKind(path)
    if (rawBytes != null && absPath != null && previewKind != null) {
        BinaryFilePreview(absPath, previewKind, rawBytes, fileSystem, modifier)
        return
    }

    // Ask the store for the document. Already open (another pane, an earlier visit) → an immediate
    // hit and no read; otherwise the store's in-flight guard means two panes racing on one cold
    // path still issue ONE fsRead.
    LaunchedEffect(path) { documents.open(path) }
    val doc = documents.get(path)
    // Native views on the STORE's scope (the workspace's), never this pane's: they outlive it.
    rememberNativeDocuments(documents)

    val bridge = remember(lspSessionId, lspStatus, lspRpc) {
        lspSessionId?.let {
            LspBridge(
                sessionId = it,
                lspStatus = lspStatus,
                lspRpc = lspRpc,
                lspStatusQuery = lspStatusQuery,
                lspOpen = lspOpen,
                lspRpcOut = lspRpcOut,
                lspClose = lspClose,
            )
        }
    }

    val previewGate = editorPreviewGate(path, previewMode, showDiff = false)
    val showPreview = previewGate.showPreview

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
            // The native editor stays composed under the preview (an overlay), so the view, its LSP
            // and its scroll survive a toggle. The LSP connection is the view's (LspLink).
            if (doc != null) {
                NativeDocumentEditor(
                    documents = documents,
                    doc = doc,
                    lineWrap = lineWrap,
                    fontSize = fontSize,
                    onFontSize = onFontSize,
                    modifier = Modifier.fillMaxSize(),
                    lsp = remember(bridge, workdir) { bridge?.let { b -> LspLink(b.session, workdir, b) } },
                    onNavigate = onNavigate,
                    covered = showPreview,
                )
            }
            if (showPreview) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(c.code))
                        .verticalScroll(rememberScrollState())
                        .padding(Space.lg)
                        .testTag("editor_preview"),
                ) {
                    // `![](img/a.png)` resolves against the file's own folder, read through the host.
                    val mdFiles = remember(absPath, rawBytes) {
                        if (absPath != null && rawBytes != null) MarkdownFiles(parentOf(absPath), rawBytes) else null
                    }
                    CompositionLocalProvider(LocalMarkdownFiles provides mdFiles) {
                        MarkdownBody(doc?.content ?: "", linkify = true, onOpenFile = onOpenFile)
                    }
                }
            }

            if (doc == null) {
                val err = documents.loadError?.takeIf { documents.loadingPath != path }
                Box(
                    Modifier.fillMaxSize().background(Color(c.code).copy(alpha = 0.92f)).padding(Space.xl)
                        .testTag(if (err != null) "editor_load_error" else "editor_file_loading"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (err != null && documents.loadErrorBinary && rawBytes != null && absPath != null) {
                        BinaryFileCard(absPath, rawBytes)
                    } else if (err != null) {
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
    walkthrough: WalkthroughState? = null,
    reviewWalkthrough: WalkthroughState? = null,
    fsDiff: suspend (String) -> FsDiffResult?,
    fsRefs: suspend () -> FsRefsResult?,
    getWalkthrough: suspend () -> dev.supermux.net.Walkthrough? = { null },
    getWalkthroughComments: suspend () -> List<ReviewComment> = { emptyList() },
    getReviewComments: suspend () -> List<ReviewComment> = { emptyList() },
    readWalkthroughFile: suspend (repo: String, path: String) -> Result<String> = { _, _ -> Result.failure(IllegalStateException("File unavailable")) },
    onOpenWalkthroughFile: (repo: String, path: String, line: Int?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    onReviewAddComment: suspend (AddCommentBody) -> ReviewComment? = { null },
    onReviewResolve: suspend (String) -> Boolean = { false },
    onWalkthroughAddComment: (suspend (AddCommentBody) -> ReviewComment?)? = null,
    onWalkthroughResolve: (suspend (String) -> Boolean)? = null,
    onWalkthroughClosed: () -> Unit = {},
    onReviewSubmit: suspend () -> ReviewSubmitResult? = { null },
    onClose: () -> Unit = {},
    /** Writes a changed file's working copy back (the native diff's revert / save); null: read-only. */
    writeDiffFile: (suspend (repo: String, path: String, text: String) -> Boolean)? = null,
    /** The workspace's open documents: a revert of an open file goes through its tab's document. */
    diffDocuments: DocumentStore? = null,
    /** A lazy file's base text by blob (repo, sha, force). */
    baseText: (suspend (repo: String, sha: String, force: Boolean) -> BlobText)? = null,
) {
    val scope = rememberCoroutineScope()
    val reviewState = reviewWalkthrough ?: walkthrough

    LaunchedEffect(diff) {
        if (diff.diffRepos.isEmpty() && !diff.diffLoading) diff.loadDiff(fsDiff, fsRefs)
    }
    LaunchedEffect(walkthrough) {
        if (walkthrough != null && walkthrough.walkthrough == null && !walkthrough.loading) {
            walkthrough.load(getWalkthrough)
        }
        if (walkthrough != null) walkthrough.seedComments(getWalkthroughComments())
    }
    LaunchedEffect(reviewState) {
        if (reviewState != null && reviewState !== walkthrough) {
            reviewState.seedComments(getReviewComments())
        }
    }
    LaunchedEffect(reviewState, diff.diffComments) {
        if (reviewState != null && diff.diffComments.isNotEmpty()) reviewState.seedComments(diff.diffComments)
    }
    LaunchedEffect(reviewState?.comments) {
        if (reviewState != null && reviewState.comments != diff.diffComments) {
            diff.diffComments = reviewState.comments
        }
    }

    // Keep the host's pane tag and this component's tag on separate semantics nodes. Compose merges
    // duplicate TestTag properties on one modifier chain, which would hide editor_diff_pane.
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().testTag("editor_diff_pane")) {
        // The toggle only exists where the app installed a WalkthroughSeam on its HostStore —
        // both platforms do since cluster C4, but a host without one has no holder to read.
        val hasWalkthrough = LocalPlatform.current.caps.walkthrough && walkthrough?.steps?.isNotEmpty() == true
        if (hasWalkthrough) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = Space.sm, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        if (walkthrough.isOpen) {
                            walkthrough.close()
                            onWalkthroughClosed()
                        } else walkthrough.open()
                    },
                    modifier = Modifier.testTag("walkthrough_toggle"),
                ) {
                    Text("📖 Walkthrough · ${walkthrough.steps.size} steps")
                }
                if (walkthrough.isOpen) Text("Showing walkthrough", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (walkthrough?.isOpen == true && hasWalkthrough) {
            WalkthroughView(
                state = walkthrough,
                repos = diff.diffRepos,
                readFile = readWalkthroughFile,
                onAddComment = onWalkthroughAddComment ?: onReviewAddComment,
                onResolve = onWalkthroughResolve ?: onReviewResolve,
                onOpenFile = onOpenWalkthroughFile,
                onClose = {
                    walkthrough.close()
                    onWalkthroughClosed()
                },
                modifier = Modifier.weight(1f),
                baseText = baseText,
            )
        } else {
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
            modifier = Modifier.weight(1f),
            // M5: each file on the native diff plugin (the same reader the walkthrough uses).
            readFile = readWalkthroughFile,
            writeFile = writeDiffFile,
            postComment = onReviewAddComment,
            documents = diffDocuments,
            baseText = baseText,
        )
        }
    }
    }
}
