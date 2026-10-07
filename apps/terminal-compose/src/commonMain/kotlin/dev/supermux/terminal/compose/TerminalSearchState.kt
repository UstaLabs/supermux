package dev.supermux.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.supermux.terminal.TerminalSearchMatch
import dev.supermux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Find-in-scrollback for one [Terminal]: the query, what it matched, and which match is current.
 *
 * The SURFACE runs the search (`TerminalSession.search`, on the engine's owner, over the whole
 * scrollback), draws every match and scrolls the current one into view; the HOST draws the search
 * field, because a text field, its buttons and its placement are the app's design and not the
 * terminal's. The host shows its field while [isOpen], feeds [updateQuery], and calls [next] /
 * [previous] / [close]. Cmd+F / Ctrl+Shift+F and the surface's own menu call [open].
 *
 * **Direction.** Matches are ordered oldest first. [previous] walks UP, toward older output — the
 * direction a terminal user searches in, since what they want is almost always something that
 * already scrolled past — and wraps; [next] walks down. A new query starts at the newest match at or
 * above the bottom of what is on screen.
 *
 * Read and written on the composition's thread only.
 */
@Stable
class TerminalSearchState {
    /** True while the host should show its search field. */
    var isOpen: Boolean by mutableStateOf(false)
        private set

    /** The text being searched for. */
    var query: String by mutableStateOf("")
        private set

    /** Case-insensitive unless the host turns it off. */
    var ignoreCase: Boolean by mutableStateOf(true)

    /** Every match, oldest first. */
    var matches: List<TerminalSearchMatch> by mutableStateOf(emptyList())
        private set

    /** Index of the current match in [matches], or -1 when there is none. */
    var current: Int by mutableIntStateOf(-1)
        private set

    /** True while a search is running. */
    var searching: Boolean by mutableStateOf(false)
        internal set

    /** True when the search stopped at its match limit; [matches] is then a prefix. */
    var truncated: Boolean by mutableStateOf(false)
        private set

    /**
     * Incremented by every [open], so a host can re-focus (and select the text of) a field that is
     * already showing when the user presses Cmd+F again.
     */
    var focusRequests: Int by mutableIntStateOf(0)
        private set

    /** The match the surface should scroll to, or null. */
    val currentMatch: TerminalSearchMatch? get() = matches.getOrNull(current)

    /** Bumped to make the surface search again with the same query (history changed). */
    internal var refreshes: Int by mutableIntStateOf(0)
        private set

    /** Bumped whenever the current match moves, so the surface reveals it even if it is the same. */
    internal var reveals: Int by mutableIntStateOf(0)
        private set

    /** What the surface installs so [close] can hand the keyboard back to the terminal. */
    internal var onClosed: () -> Unit = {}

    fun open() {
        isOpen = true
        focusRequests++
    }

    /** Hide the field, forget the matches and give the keyboard back to the terminal. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        matches = emptyList()
        current = -1
        truncated = false
        onClosed()
    }

    fun updateQuery(text: String) {
        if (text == query) return
        query = text
        if (text.isEmpty()) {
            matches = emptyList()
            current = -1
            truncated = false
        }
    }

    /** Toward older output; wraps from the oldest match to the newest. */
    fun previous() = step(-1)

    /** Toward newer output; wraps from the newest match to the oldest. */
    fun next() = step(+1)

    /** Search again with the same query — the scrollback changed since the matches were found. */
    fun refresh() {
        refreshes++
    }

    private fun step(delta: Int) {
        val count = matches.size
        if (count == 0) return
        current = if (current < 0) {
            if (delta < 0) count - 1 else 0
        } else {
            ((current + delta) % count + count) % count
        }
        reveals++
    }

    /**
     * A search finished. The current match is kept if the same one is still there; otherwise it is
     * the newest match at or above [bottomRow] (the last row on screen), or the newest overall.
     */
    internal fun onResult(found: List<TerminalSearchMatch>, truncated: Boolean, bottomRow: Long) {
        val previous = currentMatch
        matches = found
        this.truncated = truncated
        val kept = previous?.let { found.indexOf(it) } ?: -1
        current = when {
            found.isEmpty() -> -1
            kept >= 0 -> kept
            else -> found.indexOfLast { it.row <= bottomRow }.takeIf { it >= 0 } ?: found.lastIndex
        }
        if (kept < 0) reveals++
    }
}

/** A [TerminalSearchState] for one [Terminal]; the host passes it to both the terminal and its field. */
@Composable
fun rememberTerminalSearchState(): TerminalSearchState = remember { TerminalSearchState() }

/**
 * The matches of [matches] (sorted by row) that fall on absolute rows [first]..[last], as a
 * sub-list — the painter asks for one screen's worth of a list that can be thousands long.
 */
internal fun matchesOnRows(matches: List<TerminalSearchMatch>, first: Long, last: Long): List<TerminalSearchMatch> {
    if (matches.isEmpty() || last < first) return emptyList()
    var lo = 0
    var hi = matches.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (matches[mid].row < first) lo = mid + 1 else hi = mid
    }
    var end = lo
    while (end < matches.size && matches[end].row <= last) end++
    return matches.subList(lo, end)
}

/**
 * The surface's half of find: run the search whenever the query (or the scrollback, via
 * [TerminalSearchState.refresh]) changes, and scroll the current match into view.
 */
@Composable
internal fun TerminalSearchEffects(
    session: TerminalSession,
    model: ViewportModel,
    scroll: ScrollController,
    search: TerminalSearchState,
) {
    LaunchedEffect(session, search, search.isOpen, search.query, search.ignoreCase, search.refreshes) {
        if (!search.isOpen || search.query.isEmpty()) {
            search.searching = false
            return@LaunchedEffect
        }
        // Typing a query is a burst of changes; only the one the user paused on is searched.
        delay(SEARCH_DEBOUNCE_MS)
        search.searching = true
        try {
            val result = session.search(search.query, search.ignoreCase)
            val frame = model.frame
            val bottom = frame?.let { it.viewportTop + it.size.rows - 1 } ?: Long.MAX_VALUE
            search.onResult(result.matches, result.truncated, bottom)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A dead session reports itself through `failure`; find just finds nothing.
            search.onResult(emptyList(), truncated = false, bottomRow = 0L)
        } finally {
            search.searching = false
        }
    }
    LaunchedEffect(search, search.reveals) {
        val match = search.currentMatch ?: return@LaunchedEffect
        val rows = model.frame?.size?.rows ?: return@LaunchedEffect
        val top = scroll.position.row
        // Already on screen: leave the view alone, jumping would only lose the user's place.
        if (match.row in top until top + rows) return@LaunchedEffect
        scroll.jumpTo(match.row - rows / 2)
    }
}

/** Highlight the matches on [frame]'s rows (and the overscan row either side). */
internal fun DrawScope.drawSearchMatches(
    frame: TerminalFrame,
    search: TerminalSearchState,
    metrics: CellMetrics,
    scrollOffsetPx: Float,
    theme: TerminalTheme,
) {
    val visible = matchesOnRows(search.matches, frame.viewportTop - 1, frame.viewportTop + frame.size.rows)
    if (visible.isEmpty()) return
    val current = search.currentMatch
    for (match in visible) {
        val y = (match.row - frame.viewportTop) * metrics.height - scrollOffsetPx
        val x = match.firstColumn * metrics.width
        val width = (match.lastColumn - match.firstColumn + 1) * metrics.width
        drawRect(
            color = if (match == current) theme.searchCurrent else theme.searchMatch,
            topLeft = Offset(x, y),
            size = Size(width, metrics.height),
        )
    }
}

private const val SEARCH_DEBOUNCE_MS = 150L
