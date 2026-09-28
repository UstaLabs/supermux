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
    DisposableEffect(view, anchor) { onDispose { state.setPosition(anchor, view.scrollPosition) } }
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
 * One changed file of the Changes pane on the diff plugin: the working copy (read with [readFile];
 * a deleted file is empty) against its base (the patch applied in reverse), inline or
 * [sideBySide], its review threads and the `+` gutter composer (a comment carries the line's text
 * and its hunk header, as the rows' composer did). When [writeFile] is given the working copy is
 * editable: a revert writes the file at once, a typed edit is saved with Mod-S or the Save button.
 * A file that cannot be read, or whose patch does not fit it, shows [fallback] (the patch rows).
 */
@Composable
internal fun NativeFileDiff(
    repo: String,
    file: DiffFile,
    wrap: Boolean,
    sideBySide: Boolean,
    comments: List<ReviewComment>,
    readFile: suspend (repo: String, path: String) -> Result<String>,
    writeFile: (suspend (repo: String, path: String, text: String) -> Boolean)?,
    onAddComment: suspend (repo: String, path: String, anchorLine: Int, anchorContext: String, hunkHeader: String, body: String) -> Unit,
    onReply: suspend (root: ReviewComment, body: String) -> Unit,
    onResolve: suspend (commentId: String) -> Unit,
    onReload: () -> Unit,
    testTagIndex: Int,
    fallback: @Composable () -> Unit,
) {
    val deleted = file.status == "deleted"
    val loaded by produceState<Result<LineEndings.Loaded>?>(null, repo, file.path, file.diff) {
        value = if (deleted) Result.success(LineEndings.Loaded("", false)) else readFile(repo, file.path).map(LineEndings::load)
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
    NativeFileDiffEditor(
        repo, file, patch, working, base, wrap, sideBySide, comments,
        writeFile?.takeIf { !deleted }, onAddComment, onReply, onResolve, onReload, testTagIndex,
    )
}

@Composable
private fun NativeFileDiffEditor(
    repo: String,
    file: DiffFile,
    patch: String,
    working: LineEndings.Loaded,
    base: String,
    wrap: Boolean,
    sideBySide: Boolean,
    comments: List<ReviewComment>,
    writeFile: (suspend (repo: String, path: String, text: String) -> Boolean)?,
    onAddComment: suspend (repo: String, path: String, anchorLine: Int, anchorContext: String, hunkHeader: String, body: String) -> Unit,
    onReply: suspend (root: ReviewComment, body: String) -> Unit,
    onResolve: suspend (commentId: String) -> Unit,
    onReload: () -> Unit,
    testTagIndex: Int,
) {
    val scope = rememberCoroutineScope()
    val syntax = LocalPlatform.current.editorSyntax
    val fontSize = rememberEditorFontSize()
    val path = file.path
    val editable = writeFile != null
    val currentComments by rememberUpdatedState(comments)
    val currentWrite by rememberUpdatedState(writeFile)
    val currentReload by rememberUpdatedState(onReload)
    // The view shown now, for the host's callbacks (a side-by-side toggle makes new views).
    val shown = remember { ShownView() }
    val saver = remember(repo, path, working) {
        FileSaver(scope, { currentWrite }, { currentReload() }, repo, path, working.crlf)
    }

    val host = remember(repo, path, working) {
        FileReviewHost(
            repo = repo,
            path = path,
            scope = scope,
            lineText = { line -> shown.view?.state?.doc?.let { d -> if (line < d.lineCount) d.line(line + 1).text else "" }.orEmpty() },
            drafts = MapReviewDrafts(),
            comments = { currentComments },
            submit = { line, context, body ->
                onAddComment(repo, path, line, context, hunkHeaderFor(patch, line), body)
                currentReload()
                true
            },
            reply = { root, body -> onReply(root, body); currentReload(); true },
            resolve = { id -> onResolve(id); currentReload(); true },
            // A revert applied in the working copy: the file on disk follows at once.
            revert = { shown.view?.let(saver::save) },
        )
    }
    val common: Extension = extensionOf(
        highlight(syntax.languageFor(path)),
        viewSettings(EditorSettings(fontSize = fontSize.toFloat(), lineWrap = wrap)),
        search(),
    )
    val views = remember(host, base, sideBySide) {
        lateinit var w: EditorView
        w = EditorView(
            EditorState.create(
                working.text,
                extensions = extensionOf(
                    Prec.highest(keymapOf(KeyBinding("Mod-s", Command { saver.save(w); true }))),
                    common,
                    basics(),
                    history(),
                    review(host),
                    if (sideBySide) extensionOf() else inlineDiff(base, DiffConfig(editable = editable), host),
                ),
            ),
        )
        val a = if (sideBySide) EditorView(EditorState.create(base, extensions = common)) else null
        host.target = w
        shown.view = w
        saver.saved = saver.saved ?: w.state.doc
        w to a
    }
    val workingView = views.first
    val baseView = views.second
    var pair by remember(views) { mutableStateOf<DiffPair?>(null) }
    if (baseView != null) DisposableEffect(views) {
        val p = DiffPair(baseView, workingView, DiffConfig(editable = editable), host)
        pair = p
        onDispose { p.dispose(); if (pair === p) pair = null }
    }
    val threads = remember(comments, repo, path) { reviewThreads(comments, repo, path) }
    LaunchedEffect(views, threads) { Review.setThreads(workingView, threads) }
    LaunchedEffect(views, wrap, fontSize) {
        for (v in listOfNotNull(workingView, baseView)) ViewSettings.update(v) { it.copy(lineWrap = wrap, fontSize = fontSize.toFloat()) }
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
    val doc = workingView.state.doc
    val savedDoc = saver.saved
    val dirty = editable && savedDoc != null && doc !== savedDoc && doc != savedDoc

    Column(Modifier.fillMaxWidth().testTag("diff_native_$testTagIndex")) {
        if (dirty) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Unsaved changes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { saver.save(workingView) }, enabled = !saver.saving, modifier = Modifier.testTag("diff_native_save_$testTagIndex")) { Text("Save") }
            }
        }
        Box(Modifier.fillMaxWidth().height(height)) {
            val p = pair
            if (baseView != null) {
                if (p != null) SideBySideDiff(p, Modifier.fillMaxSize(), theme = theme, widgets = widgets, lineWrap = wrap)
            } else {
                InlineDiffEditor(workingView, Modifier.fillMaxSize(), theme = theme, widgets = widgets, label = path.substringAfterLast('/'), lineWrap = wrap)
            }
        }
    }
}

/** The view a Changes-pane file shows now (its host's callbacks read it). */
private class ShownView { var view: EditorView? = null }

/**
 * Writes a Changes-pane working copy back (a revert, Mod-S, Save): with the file's own line
 * endings, one write at a time; what was written counts as saved, then the diff is fetched again.
 */
private class FileSaver(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val write: () -> (suspend (repo: String, path: String, text: String) -> Boolean)?,
    private val reload: () -> Unit,
    private val repo: String,
    private val path: String,
    private val crlf: Boolean,
) {
    var saved: Rope? by mutableStateOf(null)
    var saving: Boolean by mutableStateOf(false)
        private set

    fun save(view: EditorView) {
        val w = write() ?: return
        if (saving) return
        val rope = view.state.doc
        saving = true
        scope.launch {
            val ok = try { w(repo, path, LineEndings.save(rope.toString(), crlf)) } finally { saving = false }
            if (ok) { saved = rope; reload() }
        }
    }
}

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
