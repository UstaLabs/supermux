// Chat search: the find bar over a transcript, in two scopes.
//
//   - This chat — the loaded transcript, filtered on the client (every chat holds its full log once
//     open), stepped through hit by hit; the current hit's row is tinted and scrolled to.
//   - All chats — `GET /search/messages` on every connected host ([ChatSearchHost.searchAll]); a
//     picked hit opens that chat and lands on the message, the bar open on the same query.
//
// The sidebar's search icon opens [ChatSearchDialog]: the All-chats half on its own, for when no
// chat is on screen.
package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.chatTimeLabel
import dev.supermux.net.MessageSearchHit
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * "Open [sessionId]'s find bar": on [query], landing on [messageId] (a picked hit), or just opened
 * as it was ([messageId] null — a header's search button).
 */
data class ChatJump(val sessionId: String, val messageId: String?, val query: String = "")

/**
 * The fleet-wide half of chat search, provided once at the app root ([LocalChatSearch]). Without
 * one (previews, tests, a host that has no fleet) the find bar is This-chat only and the sidebar
 * has no search icon.
 */
class ChatSearchHost(
    /** Every connected host's matching messages, newest first; null = the search failed. */
    val searchAll: suspend (query: String) -> List<MessageSearchHit>?,
    /** Bring [sessionId]'s chat on screen (the shell's chat-tab focus). */
    private val openSession: (sessionId: String) -> Unit,
) {
    private val _jump = MutableStateFlow<ChatJump?>(null)

    /** The jump the opened chat has not applied yet; that chat's panel consumes it. */
    val jump: StateFlow<ChatJump?> = _jump.asStateFlow()

    fun open(hit: MessageSearchHit, query: String) {
        _jump.value = ChatJump(hit.session_id, hit.message_id, query)
        openSession(hit.session_id)
    }

    /** Open [sessionId]'s find bar — for a header the chat panel does not draw itself. */
    fun openFind(sessionId: String) {
        _jump.value = ChatJump(sessionId, messageId = null)
    }

    fun consume(jump: ChatJump) {
        _jump.compareAndSet(jump, null)
    }
}

val LocalChatSearch = staticCompositionLocalOf<ChatSearchHost?> { null }

enum class ChatSearchScope { ThisChat, AllChats }

/** The find bar's state for one chat panel. */
@Stable
class ChatFindState {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var scope by mutableStateOf(ChatSearchScope.ThisChat)

    /** Index into the current match list; -1 = none. */
    var current by mutableIntStateOf(-1)

    /** Bumped by every [show], so a second Cmd+F re-focuses the open field and selects the query. */
    var focusRequests by mutableIntStateOf(0)
        private set

    fun show(scope: ChatSearchScope? = null) {
        if (scope != null) this.scope = scope
        open = true
        focusRequests++
    }

    fun close() {
        open = false
        current = -1
    }
}

/**
 * Timeline indices of the message rows whose text contains [query], case-insensitively, oldest
 * first. Tool and activity rows are not searched: the transcript's words are the messages.
 */
fun chatFindMatches(items: List<TimelineItem>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return items.indices.filter { i ->
        val item = items[i]
        item is TimelineItem.Msg && item.entry.text?.contains(q, ignoreCase = true) == true
    }
}

/** Ctrl/Cmd+F, exactly. */
fun isFindChord(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && event.key == Key.F &&
        (event.isCtrlPressed || event.isMetaPressed) && !event.isShiftPressed && !event.isAltPressed

/** "3/17", "No results", "" before a query. */
internal fun findCountLabel(query: String, matches: Int, current: Int): String = when {
    query.isBlank() -> ""
    matches == 0 -> "No results"
    else -> "${if (current >= 0) current + 1 else "–"}/$matches"
}

/** [text] with every case-insensitive occurrence of [query] bold in [color]. */
internal fun highlightMatches(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var from = 0
    while (from < text.length) {
        val at = text.indexOf(q, from, ignoreCase = true)
        if (at < 0) break
        append(text.substring(from, at))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(at, at + q.length)) }
        from = at + q.length
    }
    if (from < text.length) append(text.substring(from))
}

/**
 * The find bar over a chat's transcript. In This chat it steps through [matchCount] hits
 * (Enter / ↑ = older, Shift+Enter / ↓ = newer, like the terminal's); in All chats the fleet's
 * hits list under the field. Esc closes.
 */
@Composable
internal fun ChatFindBar(
    find: ChatFindState,
    matchCount: Int,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val host = LocalChatSearch.current
    val allChats = host != null && find.scope == ChatSearchScope.AllChats
    Surface(
        modifier = modifier.testTag(CHAT_FIND_TAG),
        shape = RoundedCornerShape(Radii.sm),
        color = cs.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column {
            Row(
                Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SearchField(
                    query = find.query,
                    onQueryChange = { find.query = it },
                    placeholder = if (allChats) "Search all chats" else "Find in chat",
                    focusRequests = find.focusRequests,
                    onEnter = { shift -> if (!allChats) { if (shift) onNewer() else onOlder() } },
                    onUp = { if (!allChats) onOlder() },
                    onDown = { if (!allChats) onNewer() },
                    onEscape = { find.close() },
                    fieldTag = CHAT_FIND_FIELD_TAG,
                    modifier = Modifier.weight(1f),
                )
                if (!allChats) {
                    Text(
                        text = findCountLabel(find.query, matchCount, find.current),
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.widthIn(min = 40.dp).padding(horizontal = 4.dp).testTag(CHAT_FIND_COUNT_TAG),
                    )
                    BarIcon(Icons.Filled.KeyboardArrowUp, "Older match", "chat_find_prev", onOlder)
                    BarIcon(Icons.Filled.KeyboardArrowDown, "Newer match", "chat_find_next", onNewer)
                }
                BarIcon(Icons.Filled.Close, "Close find", "chat_find_close") { find.close() }
            }
            if (host != null) {
                ScopeTabs(
                    scope = find.scope,
                    onScope = { find.scope = it; find.show() },
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                )
            }
            if (allChats && host != null) {
                HorizontalDivider(color = cs.outlineVariant)
                ChatSearchResults(
                    query = find.query,
                    host = host,
                    onPicked = { find.close() },
                    modifier = Modifier.heightIn(max = 360.dp),
                )
            }
        }
    }
}

/** The sidebar's search: All chats on its own, in a dialog; picking a hit closes it. */
@Composable
fun ChatSearchDialog(host: ChatSearchHost, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    dev.supermux.ui.widgets.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Radii.md),
            color = cs.surfaceContainerHigh,
            tonalElevation = 3.dp,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag(CHAT_SEARCH_DIALOG_TAG),
        ) {
            Column {
                Row(
                    Modifier.padding(start = Space.md, end = Space.xs, top = Space.sm, bottom = Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.sm))
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "Search all chats",
                        focusRequests = 0,
                        onEnter = {},
                        onUp = {},
                        onDown = {},
                        onEscape = onDismiss,
                        fieldTag = CHAT_SEARCH_DIALOG_FIELD_TAG,
                        modifier = Modifier.weight(1f),
                    )
                    BarIcon(Icons.Filled.Close, "Close search", "chat_search_dialog_close", onDismiss)
                }
                HorizontalDivider(color = cs.outlineVariant)
                ChatSearchResults(
                    query = query,
                    host = host,
                    onPicked = onDismiss,
                    modifier = Modifier.heightIn(max = 480.dp),
                )
            }
        }
    }
}

/** Keystrokes this close together are one search. */
private const val SEARCH_DEBOUNCE_MS = 250L

/** The fleet's hits for [query]: chat name, when, and the snippet with the match picked out. */
@Composable
private fun ChatSearchResults(
    query: String,
    host: ChatSearchHost,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val q = query.trim()
    var hits by remember { mutableStateOf<List<MessageSearchHit>?>(null) }
    var failed by remember { mutableStateOf(false) }
    // A new key restarts the effect, which cancels the pending search: debounce + cancel in one.
    LaunchedEffect(q) {
        hits = null
        failed = false
        if (q.isEmpty()) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        val found = host.searchAll(q)
        failed = found == null
        hits = found.orEmpty()
    }
    val status = when {
        q.isEmpty() -> "Type to search every chat"
        failed -> "Search failed"
        hits == null -> "Searching…"
        hits!!.isEmpty() -> "No messages match"
        else -> null
    }
    if (status != null) {
        Text(
            status,
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = Space.md, vertical = Space.md).testTag(CHAT_SEARCH_STATUS_TAG),
        )
        return
    }
    val now = remember(hits) { Clock.System.now().toEpochMilliseconds() }
    LazyColumn(modifier.testTag(CHAT_SEARCH_RESULTS_TAG), contentPadding = PaddingValues(vertical = Space.xs)) {
        items(hits!!, key = { "${it.session_id}/${it.message_id}" }) { hit ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        host.open(hit, q)
                        onPicked()
                    }
                    .padding(horizontal = Space.md, vertical = Space.sm)
                    .testTag("chat_search_hit:${hit.message_id}"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hit.session_name.ifBlank { "Untitled chat" },
                        color = cs.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val project = hit.workdir.trimEnd('/').substringAfterLast('/')
                    if (project.isNotEmpty()) {
                        Text(
                            "  ·  $project",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    chatTimeLabel(hit.ts, now)?.let {
                        Text(it, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                    }
                }
                Text(
                    highlightMatches(
                        (if (hit.direction == "inbound") "You: " else "") + hit.snippet,
                        q,
                        cs.primary,
                    ),
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ScopeTabs(scope: ChatSearchScope, onScope: (ChatSearchScope) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        ScopeTab("This chat", scope == ChatSearchScope.ThisChat, "chat_find_scope_this") { onScope(ChatSearchScope.ThisChat) }
        ScopeTab("All chats", scope == ChatSearchScope.AllChats, "chat_find_scope_all") { onScope(ChatSearchScope.AllChats) }
    }
}

@Composable
private fun ScopeTab(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(if (selected) cs.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .testTag(tag),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    focusRequests: Int,
    onEnter: (shift: Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEscape: () -> Unit,
    fieldTag: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(query)) }
    // The query can change from outside (a jump opens the bar on the picked hit's query).
    if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
    LaunchedEffect(focusRequests) {
        field = field.copy(selection = TextRange(0, field.text.length))
        runCatching { focus.requestFocus() }
    }
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (field.text.isEmpty()) Text(placeholder, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != query) onQueryChange(it.text)
            },
            singleLine = true,
            textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // A soft keyboard's Search key: the same as Enter.
            keyboardActions = KeyboardActions(onSearch = { onEnter(false) }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(fieldTag)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { onEnter(event.isShiftPressed); true }
                        Key.DirectionUp -> { onUp(); true }
                        Key.DirectionDown -> { onDown(); true }
                        Key.Escape -> { onEscape(); true }
                        else -> false
                    }
                },
        )
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

internal const val CHAT_FIND_TAG = "chat_find"
internal const val CHAT_FIND_FIELD_TAG = "chat_find_field"
internal const val CHAT_FIND_COUNT_TAG = "chat_find_count"
internal const val CHAT_SEARCH_RESULTS_TAG = "chat_search_results"
internal const val CHAT_SEARCH_STATUS_TAG = "chat_search_status"
internal const val CHAT_SEARCH_DIALOG_TAG = "chat_search_dialog"
internal const val CHAT_SEARCH_DIALOG_FIELD_TAG = "chat_search_dialog_field"
