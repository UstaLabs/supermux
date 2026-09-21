package dev.supermux.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.theme.LocalSemantics

object UnreadPillTestIds {
    const val ABOVE = "session_list_unread_above"
    const val BELOW = "session_list_unread_below"
    const val DISMISS_ABOVE = "session_list_unread_above_dismiss"
    const val DISMISS_BELOW = "session_list_unread_below_dismiss"
}

/**
 * One unread row of the list: its item [index] and a [token] naming *this* unread state (row key +
 * newest unread message ts). A dismissed pill remembers tokens, so a new message on a row it already
 * dismissed mints a new token and brings the pill back.
 */
data class UnreadRow(val index: Int, val token: String)

/**
 * Unread rows the viewer can't currently see: how many sit above / below the viewport, the nearest
 * one each way (the pill's scroll target), and their tokens (for dismissal). Rows count once
 * however many messages they hold.
 */
data class OffscreenUnread(
    val above: Int = 0,
    val nearestAbove: Int? = null,
    val below: Int = 0,
    val nearestBelow: Int? = null,
    val aboveTokens: Set<String> = emptySet(),
    val belowTokens: Set<String> = emptySet(),
)

/**
 * Pure split of [rows] around the visible window [firstVisible]..[lastVisible] (inclusive).
 * A row inside the window is on screen, so it counts neither way.
 */
fun offscreenUnread(rows: List<UnreadRow>, firstVisible: Int, lastVisible: Int): OffscreenUnread {
    if (firstVisible > lastVisible) return OffscreenUnread()
    val above = rows.filter { it.index < firstVisible }
    val below = rows.filter { it.index > lastVisible }
    return OffscreenUnread(
        above = above.size,
        nearestAbove = above.maxOfOrNull { it.index },
        below = below.size,
        nearestBelow = below.minOfOrNull { it.index },
        aboveTokens = above.mapTo(HashSet()) { it.token },
        belowTokens = below.mapTo(HashSet()) { it.token },
    )
}

/** A dismissed pill stays hidden until some offscreen unread row is one it didn't dismiss. */
fun unreadPillShows(tokens: Set<String>, dismissed: Set<String>): Boolean = (tokens - dismissed).isNotEmpty()

/**
 * The visible window as item indices, counting a row as seen once at least half of it is inside
 * the viewport — a sliver peeking at the edge (or tucked under a pill) is still "offscreen".
 * Returns null while nothing is laid out.
 */
fun LazyListLayoutInfo.mostlyVisibleRange(): IntRange? {
    val seen = visibleItemsInfo.filter { item ->
        val mid = item.offset + item.size / 2
        mid >= viewportStartOffset && mid <= viewportEndOffset
    }
    if (seen.isEmpty()) return null
    return seen.first().index..seen.last().index
}

/**
 * Scroll so item [index] sits in the middle of the viewport (as far as the list's ends allow).
 * An offscreen row's height isn't known until it is laid out, so first land it using the average
 * visible row height, then correct by its measured position.
 */
suspend fun LazyListState.animateScrollToCenter(index: Int) {
    fun viewportCenter() = layoutInfo.let { (it.viewportStartOffset + it.viewportEndOffset) / 2 }
    val seen = layoutInfo.visibleItemsInfo
    val guess = if (seen.isEmpty()) 0 else seen.sumOf { it.size } / seen.size
    animateScrollToItem(index, scrollOffset = -(viewportCenter() - layoutInfo.viewportStartOffset - guess / 2))
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = item.offset + item.size / 2 - viewportCenter()
    if (delta != 0) animateScrollBy(delta.toFloat())
}

/**
 * A [LazyListScope] that only records each item's key, in order — running the list's own DSL
 * through it maps every row key to its item index without composing any row, so rows far
 * offscreen (never laid out) can still be counted.
 */
class LazyKeyRecorder : LazyListScope {
    val keys = ArrayList<Any?>()

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        keys += key
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        for (i in 0 until count) keys += key?.invoke(i)
    }

    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        keys += key
    }
}

/** "↑ 3 unread" / "↓ 1 unread": taps scroll the list to the nearest offscreen unread row. */
@Composable
fun UnreadScrollPill(
    count: Int,
    up: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val direction = if (up) "above" else "below"
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = cs.inverseSurface,
        contentColor = cs.inverseOnSurface,
        shadowElevation = 3.dp,
        modifier = modifier
            .testTag(if (up) UnreadPillTestIds.ABOVE else UnreadPillTestIds.BELOW)
            .semantics { contentDescription = "$count unread $direction, scroll to it" },
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            // Same green as a row's unread dot, so the pill reads as "those dots, up there".
            Spacer(Modifier.size(6.dp).background(sem.success, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("$count unread", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
            // Its own click target inside the pill: hides the pill instead of scrolling.
            Box(
                Modifier
                    .testTag(if (up) UnreadPillTestIds.DISMISS_ABOVE else UnreadPillTestIds.DISMISS_BELOW)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .semantics { contentDescription = "Dismiss" }
                    .padding(2.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A pill that fades/slides in from its own edge; stays composed with its last count while leaving. */
@Composable
fun AnimatedUnreadScrollPill(
    count: Int,
    up: Boolean,
    visible: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the last real count while the pill animates out, so it never flashes "0 unread".
    var shown by remember { mutableIntStateOf(count) }
    if (count > 0) shown = count
    AnimatedVisibility(
        visible = visible && count > 0,
        enter = fadeIn() + slideInVertically { if (up) -it else it },
        exit = fadeOut() + slideOutVertically { if (up) -it else it },
        modifier = modifier,
    ) {
        UnreadScrollPill(count = shown, up = up, onClick = onClick, onDismiss = onDismiss)
    }
}

/**
 * The pair of pills over a session list: "↑ N unread" at the top edge while unread rows sit above
 * the viewport, "↓ N unread" at the bottom for rows below. A tap calls [onScrollTo] with the
 * nearest unread row's index in that direction; the × hides that pill until a row it didn't
 * dismiss (a new message) turns up on that side.
 *
 * Reads the scroll position itself (through a derived visible range) so scrolling recomposes only
 * this, never the list screen that hosts it.
 */
@Composable
fun BoxScope.UnreadScrollPills(
    listState: LazyListState,
    unreadRows: List<UnreadRow>,
    onScrollTo: (Int) -> Unit,
) {
    val range by remember(listState) { derivedStateOf { listState.layoutInfo.mostlyVisibleRange() } }
    val offscreen = range?.let { offscreenUnread(unreadRows, it.first, it.last) } ?: OffscreenUnread()
    var dismissedAbove by remember { mutableStateOf(emptySet<String>()) }
    var dismissedBelow by remember { mutableStateOf(emptySet<String>()) }
    AnimatedUnreadScrollPill(
        count = offscreen.above,
        up = true,
        visible = unreadPillShows(offscreen.aboveTokens, dismissedAbove),
        onClick = { offscreen.nearestAbove?.let(onScrollTo) },
        onDismiss = { dismissedAbove = dismissedAbove + offscreen.aboveTokens },
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
    )
    AnimatedUnreadScrollPill(
        count = offscreen.below,
        up = false,
        visible = unreadPillShows(offscreen.belowTokens, dismissedBelow),
        onClick = { offscreen.nearestBelow?.let(onScrollTo) },
        onDismiss = { dismissedBelow = dismissedBelow + offscreen.belowTokens },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
    )
}
