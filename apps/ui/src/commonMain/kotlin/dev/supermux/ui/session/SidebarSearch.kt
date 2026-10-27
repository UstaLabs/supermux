// The sidebar's search: the section header's search icon turns the header into this field, and
// while it holds a query the list below is the filter's answer instead of the groups —
//
//   1. workspaces whose NAME matches, best first (name prefix → a word in the name → anywhere in
//      the name → its project or one of its chats' names), then archived ones by name;
//   2. chats whose HISTORY matches (the broker's `/search/messages`, one hit per chat), for the
//      chats the names did not already surface.
//
// The ranking is plain functions here so the order is testable without a list on screen.
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.MessageSearchHit
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.WorkspaceDto

object SidebarSearchTestIds {
    const val OPEN = "sidebar_search"
    const val BAR = "sidebar_search_bar"
    const val FIELD = "sidebar_search_field"
    const val CLOSE = "sidebar_search_close"
    const val STATUS = "sidebar_search_status"
    const val CURSOR = "sidebar_search_cursor"
}

/** How well one row's text answers a query; lower is better, null is no match. */
internal fun sidebarMatchRank(name: String, query: String, extras: List<String> = emptyList()): Int? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null
    val n = name.lowercase()
    if (n.startsWith(q)) return 0
    if (wordStarts(n).any { n.startsWith(q, it) }) return 1
    if (q in n) return 2
    // Every word of the query somewhere in the name, the project or a chat's name.
    val words = q.split(Regex("\\s+"))
    val hay = (listOf(n) + extras.map { it.lowercase() }).joinToString("\n")
    if (words.all { it in hay }) return 3
    return null
}

/** Indexes where a word starts: right after a space, `-`, `_`, `/`, `.` or `:`. */
private fun wordStarts(lower: String): List<Int> =
    lower.indices.filter { i -> i > 0 && lower[i - 1] in " -_/.:" }

/**
 * The workspaces [query] matches, best rank first; equal ranks keep [workspaces]' order (the
 * sidebar's own, so a tie reads like the list it replaced).
 */
internal fun matchWorkspaces(
    workspaces: List<WorkspaceDto>,
    query: String,
    extras: (WorkspaceDto) -> List<String> = { emptyList() },
): List<WorkspaceDto> =
    workspaces
        .mapNotNull { w -> sidebarMatchRank(w.name, query, extras(w))?.let { w to it } }
        .sortedBy { it.second }
        .map { it.first }

/** [matchWorkspaces] for the session fallback list (a host with no workspaces). */
internal fun matchSessions(
    sessions: List<SessionInfo>,
    query: String,
    extras: (SessionInfo) -> List<String> = { emptyList() },
): List<SessionInfo> =
    sessions
        .mapNotNull { s -> sidebarMatchRank(s.name, query, extras(s))?.let { s to it } }
        .sortedBy { it.second }
        .map { it.first }

/**
 * [text] with [query] picked out in [color], the way chat-search snippets are: the whole query
 * where it occurs, otherwise each of its words (a rank-3 match spreads them across the name).
 */
internal fun highlightSearchMatches(text: String, query: String?, color: Color): AnnotatedString {
    val q = query?.trim().orEmpty()
    if (q.isEmpty()) return AnnotatedString(text)
    val ranges = matchRanges(text, q).ifEmpty { q.split(Regex("\\s+")).flatMap { matchRanges(text, it) } }
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        for (r in mergeRanges(ranges)) {
            append(text.substring(from, r.first))
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(r)) }
            from = r.last + 1
        }
        append(text.substring(from))
    }
}

private fun matchRanges(text: String, needle: String): List<IntRange> {
    if (needle.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var at = text.indexOf(needle, ignoreCase = true)
    while (at >= 0) {
        out += at until at + needle.length
        at = text.indexOf(needle, at + needle.length, ignoreCase = true)
    }
    return out
}

private fun mergeRanges(ranges: List<IntRange>): List<IntRange> =
    ranges.sortedBy { it.first }.fold(mutableListOf()) { acc, r ->
        val last = acc.lastOrNull()
        if (last != null && r.first <= last.last + 1) acc[acc.lastIndex] = last.first..maxOf(last.last, r.last)
        else acc += r
        acc
    }

/** The ↑/↓ cursor's tint, inset to the rows' own rounded shape; also tags the row for tests. */
internal fun Modifier.searchCursor(on: Boolean, color: Color): Modifier =
    if (!on) this else testTag(SidebarSearchTestIds.CURSOR).drawBehind {
        val inset = 8.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(inset, 0f),
            size = Size(size.width - 2 * inset, size.height),
            cornerRadius = CornerRadius(7.dp.toPx()),
        )
    }

/** The newest hit per chat, dropping chats in [shown] (already listed by name). Order kept. */
internal fun historyHitsByChat(hits: List<MessageSearchHit>, shown: Set<String>): List<MessageSearchHit> =
    hits.filter { it.session_id !in shown }.distinctBy { it.session_id }

/**
 * The search field that stands in for the section header: focused as soon as it appears; Esc or
 * the ✕ closes it (the caller clears the query), ↑/↓ move the result cursor and Enter opens it.
 */
@Composable
internal fun SidebarSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHighest)
            .padding(start = 8.dp)
            .testTag(SidebarSearchTestIds.BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Search workspaces and chats", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag(SidebarSearchTestIds.FIELD)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> { onSubmit(); true }
                            Key.DirectionUp -> { onUp(); true }
                            Key.DirectionDown -> { onDown(); true }
                            Key.Escape -> { onClose(); true }
                            else -> false
                        }
                    },
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClose)
                .testTag(SidebarSearchTestIds.CLOSE),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close search", tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
        }
    }
}
