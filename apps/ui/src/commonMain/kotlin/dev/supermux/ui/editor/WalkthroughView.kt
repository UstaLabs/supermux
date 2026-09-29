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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import dev.supermux.net.BlobText
import dev.supermux.net.RepoDiff
import dev.supermux.net.ReviewComment
import dev.supermux.net.WalkthroughStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

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
    /** A lazy Changes file's base text by blob (repo, sha, force); null: a lazy step shows no diff. */
    baseText: (suspend (repo: String, sha: String, force: Boolean) -> BlobText)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val baseCache = remember { BaseTextCache() }
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
                    repos = repos,
                    readFile = readFile,
                    onAddComment = onAddComment,
                    onResolve = onResolve,
                    onOpenFile = onOpenFile,
                    baseText = baseText,
                    baseCache = baseCache,
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
    repos: List<RepoDiff>,
    readFile: suspend (String, String) -> Result<String>,
    onAddComment: suspend (AddCommentBody) -> ReviewComment?,
    onResolve: suspend (String) -> Boolean,
    onOpenFile: (String, String, Int?) -> Unit,
    baseText: (suspend (String, String, Boolean) -> BlobText)?,
    baseCache: BaseTextCache,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val path = step.path
    // A lazy Changes file has no patch: its base is the blob (none: a new file). Loading → null;
    // loaded → Result(base or null, null: no base to give, so the region reverses the (empty) patch).
    val diffFile = walkthroughDiffFile(repos, step)
    val lazy = diffFile?.lazy == true
    val baseBlob = diffFile?.baseBlob
    val lazyBase by produceState<Result<String?>?>(null, step.repo, path, baseBlob, lazy) {
        value = null
        value = Result.success(
            when {
                !lazy -> null
                baseBlob == null -> ""
                baseText == null -> null
                else -> when (val r = try { baseCache.get(step.repo, baseBlob, false, baseText) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }) {
                    is BlobText.Text -> LineEndings.load(r.text).text
                    else -> null
                }
            },
        )
    }
    var content by remember(step.repo, path) { mutableStateOf<String?>(null) }
    var loadError by remember(step.repo, path) { mutableStateOf<String?>(null) }

    LaunchedEffect(step.repo, path, state.walkthrough?.revision) {
        content = null
        loadError = null
        if (path != null) readFile(step.repo, path).fold(
            // The editor rope is \n-only, as is the base (the blob, or the patch diffBase strips).
            onSuccess = { content = LineEndings.load(it).text },
            onFailure = { loadError = it.message })
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
            content == null || lazyBase == null -> Text("Loading code…", color = cs.onSurfaceVariant)
            else -> {
                // The step's code on the diff plugin (threads, composer and paging inside).
                Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 240.dp)) {
                    NativeWalkthroughRegion(
                        state = state,
                        step = step,
                        path = path,
                        text = content.orEmpty(),
                        patch = diffFile?.diff,
                        onAddComment = onAddComment,
                        onResolve = onResolve,
                        modifier = Modifier.fillMaxSize(),
                        base = lazyBase?.getOrNull(),
                    )
                }
            }
        }
    }
}

private fun walkthroughDiffFile(repos: List<RepoDiff>, step: WalkthroughStep) =
    repos.firstOrNull { it.repo == step.repo }?.files?.firstOrNull { it.path == step.path }
