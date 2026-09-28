// The walkthrough slide and the Changes pane's per-file diff on the diff plugin (M5 A5). The host
// keeps what it owns (the step shell and its paging keys, the file tree/list, the base selector,
// Submit review); the diff itself, its threads and its composer are the plugin's.
package dev.supermux.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.Prec
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapOf
import dev.supermux.editor.plugins.basics.basics
import dev.supermux.editor.plugins.diff.Diff
import dev.supermux.editor.plugins.diff.DiffConfig
import dev.supermux.editor.plugins.diff.DiffPage
import dev.supermux.editor.plugins.diff.DiffPair
import dev.supermux.editor.plugins.diff.InlineDiffEditor
import dev.supermux.editor.plugins.diff.Review
import dev.supermux.editor.plugins.diff.SideBySideDiff
import dev.supermux.editor.plugins.diff.inlineDiff
import dev.supermux.editor.plugins.diff.registerWidgets
import dev.supermux.editor.plugins.diff.review
import dev.supermux.editor.plugins.highlight.rememberSyntaxHost
import dev.supermux.editor.plugins.highlight.highlight
import dev.supermux.editor.plugins.history.history
import dev.supermux.editor.plugins.search.Search
import dev.supermux.editor.plugins.search.registerWidgets
import dev.supermux.editor.plugins.search.search
import dev.supermux.editor.plugins.view.EditorSettings
import dev.supermux.editor.plugins.view.ViewSettings
import dev.supermux.editor.plugins.view.viewSettings
import dev.supermux.editor.syntax.SyntaxBackend
import dev.supermux.net.AddCommentBody
import dev.supermux.net.DiffFile
import dev.supermux.net.ReviewComment
import dev.supermux.net.WalkthroughStep
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import dev.supermux.ui.prefs.LocalUiPrefs
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The diff views' widget content: expanders, deleted lines, threads, the composer, the search panel. */
@Composable
internal fun rememberReviewWidgets(): WidgetRegistry = remember {
    WidgetRegistry().also {
        Diff.registerWidgets(it)
        Review.registerWidgets(it)
        Search.registerWidgets(it)
    }
}

/** The platform's syntax backend once it has loaded (null until then, and where there is none). */
@Composable
internal fun rememberSyntaxBackend(): SyntaxBackend? {
    val syntax = LocalPlatform.current.editorSyntax
    val backend by produceState(syntax.loaded, syntax) { value = syntax.backend() }
    return backend
}

/** The app's editor font size (Settings → Editor, the last zoom). */
@Composable
internal fun rememberEditorFontSize(): Int {
    val prefs = LocalUiPrefs.current
    val size by prefs.editorFontSize.collectAsState(EDITOR_FONT_DEFAULT)
    return size
}

/**
 * One walkthrough step's code: the file on the diff plugin, one column, read-only, the step's lines
 * ± 20 with expanders (`DiffConfig(range = step, context = 20)`; `not_in_diff`: `plain`), the
 * session's review threads in it and the composer (drafts in [state], by anchor). Paging a
 * sideways swipe goes to the step shell ([WalkthroughState.next] / [previous]); the scroll is kept
 * per step anchor as a document position.
 */
@Composable
internal fun NativeWalkthroughRegion(
    state: WalkthroughState,
    step: WalkthroughStep,
    path: String,
    text: String,
    patch: String?,
    onAddComment: suspend (AddCommentBody) -> ReviewComment?,
    onResolve: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val syntax = LocalPlatform.current.editorSyntax
    val inDiff = step.anchorStatus != "not_in_diff"
    val base = remember(text, patch, inDiff) { if (inDiff) diffBase(text, patch) else text }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val config = walkthroughDiffConfig(step, lineCount)
    val fontSize = rememberEditorFontSize()
    val repo = step.repo
    val side = step.side
    val currentText by rememberUpdatedState(text)

    val host = remember(state, repo, path, side) {
        FileReviewHost(
            repo = repo,
            path = path,
            scope = scope,
            lineText = { line -> lineOf(currentText, line) },
            drafts = WalkthroughDrafts(state, repo, path, side),
            comments = { state.comments },
            submit = { line, context, body ->
                val created = onAddComment(AddCommentBody(repo = repo, path = path, side = side, anchorLine = line, anchorContext = context, body = body, deliver = "instant"))
                created?.let(state::applyComment)
                created != null
            },
            reply = { root, body ->
                val created = onAddComment(
                    AddCommentBody(
                        repo = root.repo, path = root.path, side = root.side,
                        anchorLine = root.currentLine ?: root.anchorLine,
                        anchorContext = root.anchorContext, body = body,
                        deliver = "instant", parentId = root.id,
                    ),
                )
                created?.let(state::applyComment)
                created != null
            },
            resolve = { id ->
                val ok = onResolve(id)
                if (ok) state.comments.firstOrNull { it.id == id }?.let { state.applyComment(it.copy(status = "resolved")) }
                ok
            },
            page = { dir -> if (dir == DiffPage.NEXT) state.next() else state.previous() },
        )
    }
    val view = remember(host, text, base) {
        EditorView(
            EditorState.create(
                text,
                extensions = extensionOf(
                    highlight(syntax.languageFor(path)),
                    basics(),
                    search(),
                    viewSettings(EditorSettings(fontSize = fontSize.toFloat(), lineWrap = true)),
                    inlineDiff(base, config, host),
                    review(host),
                ),
            ),
        ).also { host.target = it }
    }
    // A step of the same file: the next slice (what was expanded folds again), then its scroll.
    val anchor = CommentAnchor(repo, path, side, config.range?.first?.plus(1) ?: 1)
    var shown by remember(view) { mutableStateOf(config) }
    LaunchedEffect(view, config) {
        if (config != shown) {
            Diff.load(view, base, text, config)
            shown = config
        }
        state.position(anchor)?.let(view::restoreScroll)
    }
    // Kept as the view scrolls (a laid-out range that moved), not on dispose: by then the surface
    // may already have left and the position would read as the top. After the restore above.
    LaunchedEffect(view, anchor) {
        // drop(1): the replayed value is the PREVIOUS step's range (the restore is still pending).
        view.viewport.drop(1).collect { r -> if (!r.isEmpty()) state.setPosition(anchor, view.scrollPosition) }
    }
    val threads = remember(state.comments, repo, path) { reviewThreads(state.comments, repo, path) }
    LaunchedEffect(view, threads) { Review.setThreads(view, threads) }
    LaunchedEffect(view, fontSize) { ViewSettings.update(view) { it.copy(fontSize = fontSize.toFloat()) } }

    val widgets = rememberReviewWidgets()
    rememberSyntaxBackend()?.let { rememberSyntaxHost(view, it, syntax.registry, widgets) }
    InlineDiffEditor(
        view,
        modifier.testTag("walkthrough_native_region"),
        theme = rememberAppEditorTheme(),
        widgets = widgets,
        label = path.substringAfterLast('/'),
        lineWrap = true,
    )
}

/**
 * One changed file of the Changes pane on the diff plugin: the working copy (read with
 * [NativeDiffSupport.readFile]; a deleted file is empty) against its base (the patch applied in
 * reverse), inline or side by side, its review threads and the `+` gutter composer (a comment
 * carries the line's text and its hunk header, as the rows' composer did).
 *
 * READ-ONLY except for a hunk revert (review I2: the pane is a review surface; an edit typed here
 * would live in a lazy list item that a scroll, a collapse, a side-by-side toggle or the agent's
 * next diff refresh rebuilds from disk — editing belongs to the file's tab, where the store owns
 * dirty / save / reload). A revert is applied to the file's OPEN document when there is one (one
 * `edit.revert` step in its history, saved through the store: review I1), else written to disk;
 * it is not offered while that document has unsaved edits. Drafts and the scroll position are the
 * pane's ([NativeDiffSupport]), so a rebuild keeps them. A file that cannot be read, or whose patch
 * does not fit it, shows [fallback] (the patch rows).
 */
@Composable
internal fun NativeFileDiff(
    repo: String,
    file: DiffFile,
    wrap: Boolean,
    comments: List<ReviewComment>,
    support: NativeDiffSupport,
    onReload: () -> Unit,
    testTagIndex: Int,
    fallback: @Composable () -> Unit,
) {
    val deleted = file.status == "deleted"
    val loaded by produceState<Result<LineEndings.Loaded>?>(null, repo, file.path, file.diff) {
        value = if (deleted) Result.success(LineEndings.Loaded("", false)) else support.readFile(repo, file.path).map(LineEndings::load)
    }
    val working = loaded?.getOrNull()
    if (loaded == null) {
        Text("Loading…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        return
    }
    val patch = remember(file.diff) { file.diff.replace("\r\n", "\n") }
    val base = remember(working, patch) { working?.let { runCatching { dev.supermux.editor.plugins.diff.UnifiedPatch.base(it.text, patch) }.getOrNull() } }
    if (working == null || base == null) {
        fallback()
        return
    }
    NativeFileDiffEditor(repo, file, patch, working, base, wrap, comments, support, deleted, onReload, testTagIndex)
}

@Composable
private fun NativeFileDiffEditor(
    repo: String,
    file: DiffFile,
    patch: String,
    working: LineEndings.Loaded,
    base: String,
    wrap: Boolean,
    comments: List<ReviewComment>,
    support: NativeDiffSupport,
    deleted: Boolean,
    onReload: () -> Unit,
    testTagIndex: Int,
) {
    val scope = rememberCoroutineScope()
    val syntax = LocalPlatform.current.editorSyntax
    val fontSize = rememberEditorFontSize()
    val path = file.path
    val sideBySide = support.sideBySide
    val key = "$repo $path"
    val currentComments by rememberUpdatedState(comments)
    val currentReload by rememberUpdatedState(onReload)
    // The file's open document (a tab), if any: a revert goes through it; not offered while it is dirty.
    val openDoc = support.documents?.get(repoPath(repo, path))
    val openDirty = openDoc?.isDirty == true
    val revertable = support.writeFile != null && !deleted && !openDirty
    val shown = remember { ShownView() }

    val host = remember(repo, path, working) {
        FileReviewHost(
            repo = repo,
            path = path,
            scope = scope,
            lineText = { line -> shown.view?.state?.doc?.let { d -> if (line < d.lineCount) d.line(line + 1).text else "" }.orEmpty() },
            drafts = support.drafts(repo, path),
            comments = { currentComments },
            submit = { line, context, body ->
                val created = support.postComment(
                    AddCommentBody(repo = repo, path = path, side = "RIGHT", anchorLine = line, anchorContext = context, body = body, diffHunkHeader = hunkHeaderFor(patch, line)),
                )
                currentReload()
                created != null
            },
            reply = { root, body -> val created = support.postComment(replyBody(root, body)); currentReload(); created != null },
            resolve = { id -> support.onResolve(id); currentReload(); true },
            revert = { _ ->
                val reverted = shown.view?.state?.doc?.toString()
                if (reverted != null) scope.launch {
                    applyRevert(support, repo, path, reverted, working.crlf)
                    currentReload()
                }
            },
        )
    }
    // Only a revert may edit these views (read-only otherwise, see the KDoc).
    val common: Extension = extensionOf(
        highlight(syntax.languageFor(path)),
        viewSettings(EditorSettings(fontSize = fontSize.toFloat(), lineWrap = wrap)),
        search(),
    )
    val views = remember(host, base, sideBySide, revertable) {
        val w = EditorView(
            EditorState.create(
                working.text,
                extensions = extensionOf(
                    dev.supermux.editor.compose.readOnlyAllowFacet.of(Diff.REVERT_EVENT),
                    common,
                    basics(),
                    review(host),
                    if (sideBySide) extensionOf() else inlineDiff(base, DiffConfig(editable = revertable), host),
                ),
            ),
        )
        val a = if (sideBySide) EditorView(EditorState.create(base, extensions = common)) else null
        host.target = w
        shown.view = w
        w to a
    }
    val workingView = views.first
    val baseView = views.second
    var pair by remember(views) { mutableStateOf<DiffPair?>(null) }
    if (baseView != null) DisposableEffect(views) {
        val p = DiffPair(baseView, workingView, DiffConfig(editable = revertable), host)
        pair = p
        onDispose { p.dispose(); if (pair === p) pair = null }
    }
    val threads = remember(comments, repo, path) { reviewThreads(comments, repo, path) }
    LaunchedEffect(views, threads) { Review.setThreads(workingView, threads) }
    LaunchedEffect(views, wrap, fontSize) {
        for (v in listOfNotNull(workingView, baseView)) ViewSettings.update(v) { it.copy(lineWrap = wrap, fontSize = fontSize.toFloat()) }
    }
    // The scroll survives a rebuild (a revert's reload, a toggle, scrolling the list out and back).
    LaunchedEffect(views) {
        support.scroll[key]?.let(workingView::restoreScroll)
        workingView.viewport.drop(1).collect { r -> if (!r.isEmpty()) support.scroll[key] = workingView.scrollPosition }
    }

    val widgets = rememberReviewWidgets()
    val backend = rememberSyntaxBackend()
    if (backend != null) {
        rememberSyntaxHost(workingView, backend, syntax.registry, widgets)
        if (baseView != null) rememberSyntaxHost(baseView, backend, syntax.registry)
    }
    val theme = rememberAppEditorTheme()
    // The editor scrolls itself: give it about what the patch shows (its rows and a folded run per
    // hunk), bounded so a huge file never takes the whole pane.
    val rows = remember(patch) { parseDiffLines(patch).size + 1 }
    val height = (rows * (fontSize * 1.55f) + 16f).coerceIn(120f, 560f).dp

    Column(Modifier.fillMaxWidth().testTag("diff_native_$testTagIndex")) {
        if (openDirty && support.writeFile != null && !deleted) {
            Text(
                "This file has unsaved changes in a tab: save them to revert hunks here.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("diff_native_dirty_$testTagIndex"),
            )
        }
        Box(Modifier.fillMaxWidth().height(height)) {
            val p = pair
            if (baseView != null) {
                if (p != null) SideBySideDiff(p, Modifier.fillMaxSize(), theme = theme, widgets = widgets, lineWrap = wrap, readOnly = true)
            } else {
                InlineDiffEditor(workingView, Modifier.fillMaxSize(), theme = theme, widgets = widgets, label = path.substringAfterLast('/'), lineWrap = wrap, readOnly = true)
            }
        }
    }
}

/**
 * A hunk reverted in the Changes pane reaches the file: through its OPEN document when the store
 * has one (one `edit.revert` transaction in the tab's own history, then the store's save, so a later
 * Mod-S or Reload in that tab never fights it), else straight to disk with the file's line endings.
 */
internal suspend fun applyRevert(support: NativeDiffSupport, repo: String, path: String, reverted: String, crlf: Boolean): Boolean {
    val store = support.documents
    val doc = store?.get(repoPath(repo, path))
    if (store != null && doc != null) {
        if (doc.isDirty) return false
        val native = store.nativeFor(doc)
        if (native != null) {
            minimalChange(native.text(), reverted)?.let { change ->
                native.primary.dispatch(dev.supermux.editor.core.TransactionSpec(changes = listOf(change), userEvent = Diff.REVERT_EVENT))
            }
        } else doc.content = reverted
        return store.saveNow(doc)
    }
    val write = support.writeFile ?: return false
    return write(repo, path, LineEndings.save(reverted, crlf))
}

/** The view a Changes-pane file shows now (its host's callbacks read it). */
private class ShownView { var view: EditorView? = null }

/** 0-based [line] of [text] ("" past its end). */
internal fun lineOf(text: String, line: Int): String {
    var start = 0
    repeat(line) {
        val nl = text.indexOf('\n', start)
        if (nl < 0) return ""
        start = nl + 1
    }
    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    return text.substring(start, end)
}
