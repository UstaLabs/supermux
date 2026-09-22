package dev.supermux.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.chat.MarkdownBody
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.net.AddCommentBody
import dev.supermux.net.RepoDiff
import dev.supermux.net.ReviewComment
import dev.supermux.net.WalkthroughStep
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import dev.supermux.ui.editor.engine.DiffRegionComment
import dev.supermux.ui.editor.engine.DiffRegionComposer
import dev.supermux.ui.editor.engine.DiffRegionRange
import dev.supermux.ui.editor.engine.DiffRegionThread
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.platform.LocalPlatform

private val WalkthroughBlue = Color(0xFF5C8FEF)

/** Desktop slideshow for the current session walkthrough. Navigation is intentionally pure state;
 * revision swaps update this component in place rather than changing its Compose identity. */
@Composable
fun WalkthroughView(
    state: WalkthroughState,
    repos: List<RepoDiff>,
    readFile: suspend (repo: String, path: String) -> Result<String>,
    onAddComment: suspend (AddCommentBody) -> ReviewComment?,
    onResolve: suspend (String) -> Boolean,
    onOpenFile: (repo: String, path: String, line: Int?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val engines = LocalPlatform.current.editorEngine
    val focusRequester = remember { FocusRequester() }
    var drawerOpen by remember { mutableStateOf(false) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val step = state.currentStep

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier.fillMaxSize().background(cs.surface)
            .focusRequester(focusRequester).focusable()
            .onKeyEvent { event ->
                // Bubble after focused children: a comment TextField gets arrows/j/k first and the
                // slideshow never pages while the user is editing a draft.
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.K -> { state.previous(); true }
                    Key.DirectionRight, Key.J -> { state.next(); true }
                    else -> false
                }
            }
            .pointerInput(state.walkthrough?.revision) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { _, amount -> dragTotal += amount },
                    onDragEnd = {
                        if (dragTotal <= -72f) state.next()
                        if (dragTotal >= 72f) state.previous()
                        dragTotal = 0f
                    },
                )
            }
            .testTag("walkthrough_view"),
    ) {
        if (drawerOpen) {
            StepDrawer(state, onSelect = { state.goTo(it); drawerOpen = false }, Modifier.width(280.dp))
        }
        Column(Modifier.weight(1f).fillMaxSize()) {
            WalkthroughTopBar(
                state = state,
                onToggleDrawer = { drawerOpen = !drawerOpen },
                onClose = onClose,
            )
            if (step == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (state.loading) "Loading walkthrough…" else "No walkthrough", color = cs.onSurfaceVariant)
                }
            } else {
                StepSlide(
                    state = state,
                    step = step,
                    engines = engines,
                    repos = repos,
                    readFile = readFile,
                    onAddComment = onAddComment,
                    onResolve = onResolve,
                    onOpenFile = onOpenFile,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun WalkthroughTopBar(
    state: WalkthroughState,
    onToggleDrawer: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val total = state.steps.size
    val index = if (total == 0) 0 else state.stepIndex + 1
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(cs.surfaceContainer).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleDrawer, modifier = Modifier.testTag("walkthrough_drawer")) {
            Icon(Icons.Filled.Menu, "Show steps", modifier = Modifier.size(18.dp))
        }
        TextButton(onClick = state::previous, enabled = state.stepIndex > 0) { Text("‹") }
        Text("$index / $total", fontFamily = MonoFontFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = state::next, enabled = state.stepIndex < total - 1) { Text("›") }
        Text(
            state.currentStep?.title ?: state.walkthrough?.title.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = Space.sm),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            state.steps.forEachIndexed { i, _ ->
                Box(
                    Modifier.size(if (i == state.stepIndex) 8.dp else 6.dp)
                        .background(if (i == state.stepIndex) cs.primary else cs.outlineVariant, CircleShape)
                        .clickable { state.goTo(i) },
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("walkthrough_close")) {
            Icon(Icons.Filled.Close, "Back to diff", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StepDrawer(state: WalkthroughState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(cs.surfaceContainerHigh).padding(vertical = Space.sm)) {
        Text("Walkthrough steps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(Space.md))
        state.steps.forEachIndexed { index, step ->
            val open = state.comments.count { c ->
                c.parentId == null && c.status == "open" && c.repo == step.repo && c.path == step.path &&
                    (c.currentLine ?: c.anchorLine) in (step.rangeStart ?: step.anchorLine ?: 0)..(step.rangeEnd ?: step.anchorLine ?: 0)
            }
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(index) }
                    .background(if (index == state.stepIndex) cs.primaryContainer else Color.Transparent)
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", fontFamily = MonoFontFamily, color = cs.onSurfaceVariant, modifier = Modifier.width(28.dp))
                Text(step.title, maxLines = 2, modifier = Modifier.weight(1f))
                if (open > 0) Text("$open", color = cs.onPrimary, fontSize = 10.sp,
                    modifier = Modifier.background(cs.primary, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun StepSlide(
    state: WalkthroughState,
    step: WalkthroughStep,
    engines: EditorEngineFactory,
    repos: List<RepoDiff>,
    readFile: suspend (String, String) -> Result<String>,
    onAddComment: suspend (AddCommentBody) -> ReviewComment?,
    onResolve: suspend (String) -> Boolean,
    onOpenFile: (String, String, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val path = step.path
    var content by remember(step.repo, path) { mutableStateOf<String?>(null) }
    var loadError by remember(step.repo, path) { mutableStateOf<String?>(null) }
    var selectedAnchor by remember(step.repo, path) { mutableStateOf<CommentAnchor?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var contextLines by remember(step.id) { mutableIntStateOf(20) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(step.repo, path, state.walkthrough?.revision) {
        content = null
        loadError = null
        if (path != null) readFile(step.repo, path).fold(onSuccess = { content = it }, onFailure = { loadError = it.message })
    }

    Column(modifier.padding(horizontal = Space.md, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (state.updatedStepIndex == state.stepIndex) {
            TextButton(onClick = state::clearUpdatedBadge, modifier = Modifier.testTag("walkthrough_updated_badge")) {
                Text("Step ${state.stepIndex + 1} updated", color = WalkthroughBlue, fontWeight = FontWeight.SemiBold)
            }
        }
        if (path == null) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                MarkdownBody(step.bodyMd, Modifier.fillMaxWidth())
            }
            return@Column
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            MarkdownBody(step.bodyMd, Modifier.fillMaxWidth())
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (step.repo.isBlank()) path else "${step.repo}/$path",
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onOpenFile(step.repo, path, step.anchorLine ?: step.rangeStart) }) { Text("Open file") }
        }
        if (step.anchorStatus == "outdated") {
            Text(
                "Code changed since authoring — showing the nearest available region.",
                color = cs.error,
                fontSize = 12.sp,
                modifier = Modifier.background(cs.errorContainer, RoundedCornerShape(6.dp)).padding(Space.sm),
            )
        } else if (step.anchorStatus == "not_in_diff") {
            Text(
                "Code is outside the current diff — showing file context.",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.background(cs.surfaceContainerHigh, RoundedCornerShape(6.dp)).padding(Space.sm),
            )
        }
        when {
            loadError != null -> Text(loadError ?: "Could not load file", color = cs.error)
            content == null -> Text("Loading code…", color = cs.onSurfaceVariant)
            else -> {
                val text = content.orEmpty()
                val start = step.rangeStart ?: step.anchorLine ?: 1
                val end = step.rangeEnd ?: start
                val inDiff = step.anchorStatus != "not_in_diff"
                val ranges = if (!inDiff) listOf(DiffRegionRange(start, end, "context")) else
                    walkthroughRegionRanges(repos, step).ifEmpty { listOf(DiffRegionRange(start, end, "change")) }
                val lines = walkthroughDiffLines(
                    repos, step, text, context = contextLines, focusLine = selectedAnchor?.line,
                    includeDiff = inDiff,
                )
                val viewportAnchor = CommentAnchor(step.repo, path, step.side, start)
                // Threads + composer live INSIDE CodeMirror now (block widgets). The native
                // DiffRows path below is only ever reached when JCEF is unavailable.
                val threads = walkthroughThreads(state.comments, step.repo, path)
                val composer = selectedAnchor?.let { DiffRegionComposer(it.line, state.draft(it)) }
                Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 240.dp)) {
                    DiffRegionSurface(
                        factory = engines,
                        path = path,
                        content = text,
                        ranges = ranges,
                        language = path,
                        onLineClick = { line -> selectedAnchor = CommentAnchor(step.repo, path, step.side, line) },
                        onPage = { direction -> if (direction == "next") state.next() else state.previous() },
                        onExpand = { contextLines += 20 },
                        threads = threads,
                        composer = composer,
                        onCommentSubmit = { line, body ->
                            val anchor = CommentAnchor(step.repo, path, step.side, line)
                            state.setDraft(anchor, body)
                            selectedAnchor = null
                            scope.launch {
                                postComment(
                                    state, anchor, text.split('\n').getOrElse(line - 1) { "" },
                                    onAddComment, { submitting = it },
                                )
                            }
                        },
                        onReplySubmit = { threadId, body ->
                            val root = state.comments.firstOrNull { it.id == threadId }
                            if (root != null) scope.launch {
                                submitting = true
                                val created = onAddComment(
                                    AddCommentBody(
                                        repo = root.repo, path = root.path, side = root.side,
                                        anchorLine = root.currentLine ?: root.anchorLine,
                                        anchorContext = root.anchorContext, body = body,
                                        deliver = "instant", parentId = threadId,
                                    ),
                                )
                                submitting = false
                                created?.let(state::applyComment)
                            }
                        },
                        onResolveThread = { id ->
                            scope.launch {
                                if (onResolve(id)) state.comments.firstOrNull { it.id == id }
                                    ?.let { state.applyComment(it.copy(status = "resolved")) }
                            }
                        },
                        onComposerState = { line, body ->
                            if (line <= 0) {
                                selectedAnchor?.let(state::clearDraft)
                                selectedAnchor = null
                            } else {
                                val anchor = CommentAnchor(step.repo, path, step.side, line)
                                selectedAnchor = anchor
                                state.setDraft(anchor, body)
                            }
                        },
                        scrollKey = viewportAnchor,
                        scrollTop = state.scroll(viewportAnchor),
                        onScrollChange = { state.setScroll(viewportAnchor, it) },
                        modifier = Modifier.fillMaxSize(),
                        fallback = { reason ->
                            WalkthroughNativeRegion(
                                state = state,
                                step = step,
                                lines = lines,
                                viewportAnchor = viewportAnchor,
                                selectedAnchor = selectedAnchor,
                                submitting = submitting,
                                reason = reason,
                                onSelectedAnchorChange = { selectedAnchor = it },
                                onSubmittingChange = { submitting = it },
                                onAddComment = onAddComment,
                                onResolve = onResolve,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WalkthroughNativeRegion(
    state: WalkthroughState,
    step: WalkthroughStep,
    lines: List<DiffLine>,
    viewportAnchor: CommentAnchor,
    selectedAnchor: CommentAnchor?,
    submitting: Boolean,
    reason: String?,
    onSelectedAnchorChange: (CommentAnchor?) -> Unit,
    onSubmittingChange: (Boolean) -> Unit,
    onAddComment: suspend (AddCommentBody) -> ReviewComment?,
    onResolve: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    key(viewportAnchor) {
        val scroll = rememberScrollState(state.scroll(viewportAnchor))
        LaunchedEffect(scroll, viewportAnchor) {
            snapshotFlow { scroll.value }.distinctUntilChanged().collect { state.setScroll(viewportAnchor, it) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (reason != null) {
                Text(reason, fontSize = 10.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(Space.xs))
            }
            DiffRows(
                repo = step.repo,
                path = step.path.orEmpty(),
                lines = lines,
                wrap = true,
                comments = state.comments,
                composerFor = selectedAnchor?.key,
                draft = selectedAnchor?.let(state::draft).orEmpty(),
                submitting = submitting,
                onToggleComposer = { value -> onSelectedAnchorChange(anchorFromKey(value, step.side)) },
                onDraftChange = { value -> selectedAnchor?.let { state.setDraft(it, value) } },
                onCancelComposer = { onSelectedAnchorChange(null) },
                onAdd = { repo, path, line, _ ->
                    val anchor = CommentAnchor(repo, path, step.side, line.newLine ?: viewportAnchor.line)
                    scope.launch {
                        postComment(state, anchor, line.content, onAddComment, onSubmittingChange)
                        onSelectedAnchorChange(null)
                    }
                },
                onResolve = { id ->
                    scope.launch {
                        if (onResolve(id)) state.comments.firstOrNull { it.id == id }
                            ?.let { state.applyComment(it.copy(status = "resolved")) }
                    }
                },
            )
        }
    }
}

private suspend fun postComment(
    state: WalkthroughState,
    anchor: CommentAnchor,
    context: String,
    add: suspend (AddCommentBody) -> ReviewComment?,
    submitting: (Boolean) -> Unit,
) {
    val body = state.draft(anchor).trim()
    if (body.isEmpty()) return
    submitting(true)
    val created = add(
        AddCommentBody(
            repo = anchor.repo, path = anchor.path, side = anchor.side, anchorLine = anchor.line,
            anchorContext = context, body = body, deliver = "instant",
        ),
    )
    submitting(false)
    if (created != null) {
        state.applyComment(created)
        state.clearDraft(anchor)
    }
}

private val CommentAnchor.key: String get() = "$repo||$path||$line"

private fun anchorFromKey(key: String, side: String): CommentAnchor? {
    val parts = key.split("||")
    if (parts.size != 3) return null
    return CommentAnchor(parts[0], parts[1], side, parts[2].toIntOrNull() ?: return null)
}

/** Project this file's review comments into the in-editor thread payload — roots (with their
 *  replies, root-first) anchored on the CURRENT new-side line. Threads whose line falls outside the
 *  rendered slice are simply not drawn by the bundle, so no filtering is needed here. */
internal fun walkthroughThreads(
    comments: List<ReviewComment>,
    repo: String,
    path: String,
): List<DiffRegionThread> {
    val forFile = comments.filter { it.repo == repo && it.path == path }
    return forFile.filter { it.parentId == null }.map { root ->
        DiffRegionThread(
            id = root.id,
            line = root.currentLine ?: root.anchorLine,
            status = root.status,
            comments = (listOf(root) + forFile.filter { it.parentId == root.id }).map { c ->
                DiffRegionComment(id = c.id, author = c.author.ifEmpty { "user" }, body = c.body)
            },
        )
    }
}

private fun walkthroughDiffFile(repos: List<RepoDiff>, step: WalkthroughStep) =
    repos.firstOrNull { it.repo == step.repo }?.files?.firstOrNull { it.path == step.path }

/** Translate the authored range to truthful current-file decorations from the unified diff.
 * Replacement pairs are `change`; surplus inserted lines are `add`. Deleted-only rows remain in
 * the native unified-diff fallback because a current-file CodeMirror document has no line on which
 * a deletion decoration could be placed. */
internal fun walkthroughRegionRanges(repos: List<RepoDiff>, step: WalkthroughStep): List<DiffRegionRange> {
    val diff = walkthroughDiffFile(repos, step)?.diff ?: return emptyList()
    val firstLine = step.rangeStart ?: step.anchorLine ?: 1
    val lastLine = step.rangeEnd ?: step.anchorLine ?: firstLine
    val ranges = mutableListOf<DiffRegionRange>()
    var newLine = 0
    var pendingDeleteCount = 0
    val pendingDeletedLines = mutableListOf<String>()
    var attachedDeletes = false
    var inHunk = false
    fun attachPureDeletionIfVisible() {
        if (pendingDeletedLines.isNotEmpty() && newLine in firstLine..lastLine) {
            ranges += DiffRegionRange(newLine, newLine, "delete", pendingDeletedLines.toList())
        }
        pendingDeleteCount = 0
        pendingDeletedLines.clear()
        attachedDeletes = false
    }
    diff.split('\n').forEach { raw ->
        when {
            raw.startsWith("@@") -> {
                attachPureDeletionIfVisible()
                inHunk = true
                newLine = Regex("\\+(\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }
            !inHunk -> Unit
            raw.startsWith("-") -> {
                pendingDeleteCount += 1
                pendingDeletedLines += raw.drop(1)
            }
            raw.startsWith("+") -> {
                if (newLine in firstLine..lastLine) {
                    val kind = if (pendingDeleteCount > 0) "change" else "add"
                    val deleted = if (!attachedDeletes) pendingDeletedLines.toList() else emptyList()
                    ranges += DiffRegionRange(newLine, newLine, kind, deleted)
                }
                if (!attachedDeletes) pendingDeletedLines.clear()
                if (pendingDeleteCount > 0) pendingDeleteCount -= 1
                attachedDeletes = true
                newLine += 1
            }
            raw.startsWith(" ") -> {
                attachPureDeletionIfVisible()
                newLine += 1
            }
        }
    }
    attachPureDeletionIfVisible()
    return ranges
}

/** Native region backed by the real unified diff, retaining deletion rows and their +/- gutter. */
internal fun walkthroughDiffLines(
    repos: List<RepoDiff>,
    step: WalkthroughStep,
    content: String,
    context: Int = 20,
    focusLine: Int? = null,
    includeDiff: Boolean = true,
): List<DiffLine> {
    val parsed = if (includeDiff) walkthroughDiffFile(repos, step)?.diff?.let(::parseDiffLines).orEmpty() else emptyList()
    val firstLine = step.rangeStart ?: step.anchorLine ?: 1
    val lastLine = step.rangeEnd ?: step.anchorLine ?: firstLine
    val all = content.split('\n')
    if (all.isEmpty()) return emptyList()
    val targetStart = minOf(firstLine, focusLine ?: firstLine).coerceIn(1, all.size)
    val targetEnd = maxOf(lastLine, focusLine ?: lastLine).coerceIn(targetStart, all.size)
    val sliceStart = (targetStart - context).coerceAtLeast(1)
    val sliceEnd = (targetEnd + context).coerceAtMost(all.size)
    val currentKinds = parsed.mapNotNull { line -> line.newLine?.let { it to line.type } }.toMap()
    val deletionsBefore = mutableMapOf<Int, MutableList<DiffLine>>()
    val pending = mutableListOf<DiffLine>()
    parsed.forEach { line ->
        when {
            line.type == DiffLineType.Del -> pending += line
            line.newLine != null -> {
                // `newLine` now lives in :ui, so it cannot smart-cast across the module boundary.
                val newLine = line.newLine!!
                if (pending.isNotEmpty()) {
                    deletionsBefore.getOrPut(newLine) { mutableListOf() }.addAll(pending)
                    pending.clear()
                }
            }
            line.type == DiffLineType.Hunk -> pending.clear()
        }
    }
    if (pending.isNotEmpty()) deletionsBefore.getOrPut(all.size) { mutableListOf() }.addAll(pending)
    return buildList {
        for (lineNumber in sliceStart..sliceEnd) {
            addAll(deletionsBefore[lineNumber].orEmpty())
            add(
                DiffLine(
                    type = currentKinds[lineNumber]?.takeIf { it == DiffLineType.Add } ?: DiffLineType.Ctx,
                    content = all[lineNumber - 1],
                    newLine = lineNumber,
                ),
            )
        }
    }
}

/** Build the native fallback slice with the same twenty-line context as CodeMirror. */
internal fun regionLines(content: String, rangeStart: Int, rangeEnd: Int, context: Int = 20): List<DiffLine> {
    val all = content.split('\n')
    if (all.isEmpty()) return emptyList()
    val safeRangeStart = rangeStart.coerceIn(1, all.size)
    val safeRangeEnd = rangeEnd.coerceIn(safeRangeStart, all.size)
    val start = (safeRangeStart - context).coerceAtLeast(1)
    val end = (safeRangeEnd + context).coerceAtMost(all.size)
    return (start..end).map { line ->
        DiffLine(
            type = if (line in safeRangeStart..safeRangeEnd) DiffLineType.Add else DiffLineType.Ctx,
            content = all[line - 1],
            newLine = line,
        )
    }
}
