// "Go to file…" for the Files pane: a field over the tree that fuzzy-searches the workspace through
// the host's FileSystemService and lists the hits with their matched characters in bold. Empty
// query + focused field lists the files recently opened from this pane instead.
//
// The broker's SearchHit.hits index into the ABSOLUTE path; the list shows workdir-relative text,
// so every index is shifted into the part it is drawn in (name / folder) and out-of-range ones are
// dropped — see [toGoToEntry].
package dev.supermux.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.fs.FileSystemService
import dev.supermux.fs.SearchHit
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import kotlinx.coroutines.delay

/** Keystrokes this close together are one search. */
const val GoToFileDebounceMs = 120L

/** One line of the Go-to-file list. [nameHits]/[folderHits] index into [name]/[folder]. */
data class GoToEntry(
    val absolutePath: String,
    val relativePath: String,
    val name: String,
    val folder: String,
    val isDir: Boolean,
    val nameHits: List<Int> = emptyList(),
    val folderHits: List<Int> = emptyList(),
)

/**
 * Split [display] into consecutive runs covering all of it, each flagged matched or not.
 * Out-of-range and duplicate indexes are ignored.
 */
fun highlightRuns(display: String, hitIndexes: List<Int>): List<Pair<IntRange, Boolean>> {
    if (display.isEmpty()) return emptyList()
    val hit = hitIndexes.filter { it in display.indices }.toSet()
    val runs = mutableListOf<Pair<IntRange, Boolean>>()
    var start = 0
    for (i in 1..display.length) {
        if (i == display.length || (i in hit) != (start in hit)) {
            runs += (start until i) to (start in hit)
            start = i
        }
    }
    return runs
}

/** [hits] (indexes into a longer string) shifted to a slice starting at [start] of [length]. */
fun hitsWithin(hits: List<Int>, start: Int, length: Int): List<Int> =
    hits.map { it - start }.filter { it in 0 until length }

/** A broker hit as a list entry, or null when it is not inside [workdir] (or is the workdir). */
fun toGoToEntry(workdir: String, hit: SearchHit): GoToEntry? {
    val rel = relativeToWorkdir(workdir, hit.path)?.takeIf { it != "." && it.isNotEmpty() } ?: return null
    val path = hit.path.trimEnd('/').ifEmpty { hit.path }
    val relStart = path.length - rel.length
    if (relStart < 0 || !path.endsWith(rel)) return null
    val name = rel.substringAfterLast('/')
    val folder = rel.substringBeforeLast('/', "")
    return GoToEntry(
        absolutePath = hit.path,
        relativePath = rel,
        name = name,
        folder = folder,
        isDir = hit.type == "dir",
        nameHits = hitsWithin(hit.hits, path.length - name.length, name.length),
        folderHits = hitsWithin(hit.hits, relStart, folder.length),
    )
}

/** A recently opened workdir-relative file as a list entry (nothing highlighted). */
fun recentEntry(workdir: String, relativePath: String): GoToEntry = GoToEntry(
    absolutePath = relativePath.split('/').filter { it.isNotEmpty() }.fold(workdir) { acc, seg -> childOf(acc, seg) },
    relativePath = relativePath,
    name = relativePath.substringAfterLast('/'),
    folder = relativePath.substringBeforeLast('/', ""),
    isDir = false,
)

/** ⌘P / Ctrl+P, and nothing else (no Shift, no Alt). */
fun isGoToFileChord(letter: Char?, ctrlOrMeta: Boolean, shift: Boolean, alt: Boolean): Boolean =
    letter?.uppercaseChar() == 'P' && ctrlOrMeta && !shift && !alt

/**
 * The pane's key handler for ⌘P / Ctrl+P. Bubble phase, like the shell's shortcuts: it sees the
 * chord only when focus is inside the pane and nothing focused there consumed it.
 */
fun Modifier.goToFileShortcut(onGoToFile: () -> Unit): Modifier = onKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
    val letter = if (e.key == Key.P) 'P' else null
    if (!isGoToFileChord(letter, e.isCtrlPressed || e.isMetaPressed, e.isShiftPressed, e.isAltPressed)) return@onKeyEvent false
    onGoToFile()
    true
}

/**
 * The "Go to file…" field on top, [content] (the tree and its header) underneath; while the search
 * is active its results cover [content].
 */
@Composable
fun FileSearch(
    fileSystem: FileSystemService?,
    view: TreeViewState,
    workdir: String,
    onOpen: (GoToEntry) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<GoToEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selectedIdx by remember { mutableIntStateOf(0) }
    val q = view.query.trim()

    // A new key restarts the effect, which cancels the pending search: debounce + cancel in one.
    LaunchedEffect(q, fileSystem, workdir) {
        if (q.isEmpty() || fileSystem == null) {
            results = null; failed = false
            return@LaunchedEffect
        }
        delay(GoToFileDebounceMs)
        val r = fileSystem.search(workdir, q)
        failed = r.isFailure
        results = r.getOrNull().orEmpty().mapNotNull { toGoToEntry(workdir, it) }
        selectedIdx = 0
    }

    val shown: List<GoToEntry> = if (q.isEmpty()) view.recent.map { recentEntry(workdir, it) } else results.orEmpty()
    val active = q.isNotEmpty() || (focused && shown.isNotEmpty())
    val sel = selectedIdx.coerceIn(0, shown.lastIndex.coerceAtLeast(0))

    fun choose(e: GoToEntry) {
        view.query = ""
        results = null
        focusManager.clearFocus()
        onOpen(e)
    }

    fun onFieldKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        return when (e.key) {
            Key.DirectionDown -> { if (shown.isNotEmpty()) selectedIdx = (sel + 1).coerceAtMost(shown.lastIndex); active }
            Key.DirectionUp -> { selectedIdx = (sel - 1).coerceAtLeast(0); active }
            Key.Enter, Key.NumPadEnter -> { shown.getOrNull(sel)?.let { choose(it) }; true }
            Key.Escape -> {
                if (view.query.isNotEmpty()) { view.query = ""; results = null } else focusManager.clearFocus()
                true
            }
            else -> false
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(cs.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = Space.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                BasicTextField(
                    value = view.query,
                    onValueChange = { view.query = it; selectedIdx = 0 },
                    singleLine = true,
                    textStyle = TextStyle(color = cs.onSurface, fontFamily = MonoFontFamily, fontSize = 12.sp),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Space.sm)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent(::onFieldKey)
                        .testTag("files_search_field"),
                    decorationBox = { inner ->
                        Box {
                            if (view.query.isEmpty()) {
                                Text("Go to file…", color = cs.onSurfaceVariant, fontFamily = MonoFontFamily, fontSize = 12.sp)
                            }
                            inner()
                        }
                    },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) { content() }
            if (active) {
                GoToResults(
                    entries = shown,
                    selectedIdx = sel,
                    emptyText = when {
                        q.isEmpty() -> null
                        fileSystem == null -> "Host offline"
                        failed -> "Search failed"
                        results == null -> null // still searching
                        else -> "No matching files"
                    },
                    onChoose = ::choose,
                    modifier = Modifier.fillMaxSize().background(cs.surfaceContainerHigh),
                )
            }
        }
    }
}

@Composable
private fun GoToResults(
    entries: List<GoToEntry>,
    selectedIdx: Int,
    emptyText: String?,
    onChoose: (GoToEntry) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    LaunchedEffect(selectedIdx, entries) {
        val visible = list.layoutInfo.visibleItemsInfo
        val fully = visible.any { it.index == selectedIdx && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }
        if (!fully && selectedIdx in entries.indices) list.scrollToItem(selectedIdx)
    }
    Box(modifier.testTag("files_search_results")) {
        if (entries.isEmpty()) {
            if (emptyText != null) {
                Text(
                    emptyText,
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = Space.md).testTag("files_search_empty"),
                )
            }
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            itemsIndexed(entries, key = { _, e -> e.absolutePath }) { i, e ->
                val selected = i == selectedIdx
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .background(if (selected) cs.secondaryContainer else cs.surfaceContainerHigh)
                        .pointerHoverIcon(PointerIcon.Hand)
                        // The field keeps focus (↑/↓/Enter drive the list); a row taking it on
                        // press would hide the recent-files list before the click lands.
                        .focusProperties { canFocus = false }
                        .clickable { onChoose(e) }
                        .padding(horizontal = Space.sm, vertical = 3.dp)
                        .testTag("files_search_result:${e.relativePath}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    FileBadgeBox(fileBadge(e.name, e.isDir), alpha = 1f)
                    Column(Modifier.weight(1f)) {
                        Text(
                            highlighted(e.name, e.nameHits),
                            color = if (selected) cs.onSecondaryContainer else cs.onSurface,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (e.folder.isNotEmpty()) {
                            Text(
                                highlighted(e.folder, e.folderHits),
                                color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun highlighted(text: String, hits: List<Int>): AnnotatedString = buildAnnotatedString {
    for ((range, hit) in highlightRuns(text, hits)) {
        val part = text.substring(range.first, range.last + 1)
        if (hit) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}
