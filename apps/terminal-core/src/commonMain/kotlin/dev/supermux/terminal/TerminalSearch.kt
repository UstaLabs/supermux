package dev.supermux.terminal

/** One occurrence of a search query: absolute [row] (the [TerminalPoint.row] space), columns inclusive. */
data class TerminalSearchMatch(val row: Long, val firstColumn: Int, val lastColumn: Int)

/**
 * One slice of a scrollback search.
 *
 * [nextRow] is where the next slice starts, or null when the search reached the bottom. [complete]
 * is false when the slice stopped early for a reason other than the end of the buffer — the match
 * [TerminalSession.search] limit was hit, or the program holds synchronized output and the screen
 * cannot be read.
 */
internal class TerminalSearchSlice(
    val matches: List<TerminalSearchMatch>,
    val nextRow: Long?,
    val complete: Boolean,
)

/**
 * Search rows [fromRow] onward — at most [maxRows] of them — for [query], on the engine's owner.
 *
 * **Why it walks the viewport.** The st_* ABI has no search entry point and no way to read an
 * arbitrary history row; what it has is `scrollTo` and a full `viewport` read. Adding a native
 * search would mean a new ABI version and rebuilding the engine for every target, so this reads the
 * scrollback one screen at a time instead and puts the viewport back where it was. Nothing is
 * PUBLISHED while it walks — the caller runs it as one command on the session's owner loop and
 * forces the next published frame to be full — so a renderer never sees the intermediate screens.
 *
 * **What a match is.** A run of cells on ONE row whose text contains [query]. Frames carry no
 * soft-wrap marker, so a word split across a wrap is not found; everything else is exact, including
 * wide glyphs (their continuation cells are part of the match's column span) and multi-character
 * grapheme clusters.
 */
internal fun searchSlice(
    engine: TerminalEngine,
    query: String,
    ignoreCase: Boolean,
    fromRow: Long,
    maxRows: Int,
    maxMatches: Int,
): TerminalSearchSlice {
    if (query.isEmpty()) return TerminalSearchSlice(emptyList(), null, complete = true)
    val start = engine.viewport(forceFull = true)
    // A synchronized-output hold returns the frame captured when it began whatever the viewport is
    // scrolled to, so a walk would read the same screen over and over. Report it; the caller retries.
    if (start.held) return TerminalSearchSlice(emptyList(), fromRow, complete = false)
    val restoreTop = start.viewportTop
    val rows = start.size.rows
    val totalRows = start.historyRows + rows
    val matches = ArrayList<TerminalSearchMatch>()
    var top = fromRow.coerceAtLeast(0L)
    val end = minOf(totalRows, top + maxRows.coerceAtLeast(rows))
    try {
        while (top < end) {
            engine.scrollTo(top)
            val page = if (page0Matches(start, top)) start else engine.viewport(forceFull = true)
            // The engine clamps: the last page starts at `historyRows`, possibly above `top`.
            for (row in page.rows) {
                val absolute = page.viewportTop + row.index
                if (absolute < top || absolute >= end) continue
                findInRow(row.cells, query, ignoreCase) { first, last ->
                    matches += TerminalSearchMatch(absolute, first, last)
                }
                if (matches.size >= maxMatches) {
                    return TerminalSearchSlice(matches.subList(0, maxMatches).toList(), absolute + 1, complete = false)
                }
            }
            top = page.viewportTop + rows
        }
    } finally {
        // Back to where the user was. At the bottom this re-pins the viewport to the active area,
        // so live output keeps scrolling in exactly as before.
        engine.scrollTo(restoreTop)
    }
    return TerminalSearchSlice(matches, if (end < totalRows) end else null, complete = true)
}

private fun page0Matches(start: TerminalViewport, top: Long): Boolean = start.viewportTop == top && start.full

/**
 * Every non-overlapping occurrence of [query] in one row's [cells], reported as inclusive column
 * spans. Width-0 continuation cells contribute no text but extend the span of the glyph they
 * continue, so a match on a wide glyph covers both of its cells.
 */
internal inline fun findInRow(
    cells: List<TerminalCell>,
    query: String,
    ignoreCase: Boolean,
    onMatch: (first: Int, last: Int) -> Unit,
) {
    if (query.isEmpty() || cells.isEmpty()) return
    val text = StringBuilder(cells.size)
    // For every char of `text`: the column of the cell it came from.
    var columnOf = IntArray(cells.size + 8)
    var length = 0
    for ((column, cell) in cells.withIndex()) {
        if (cell.width == 0) continue
        val piece = cell.text.ifEmpty { " " }
        for (ch in piece) {
            if (length == columnOf.size) columnOf = columnOf.copyOf(columnOf.size * 2)
            text.append(ch)
            columnOf[length++] = column
        }
    }
    val haystack = text.toString()
    var from = 0
    while (from <= haystack.length - query.length) {
        val at = haystack.indexOf(query, from, ignoreCase)
        if (at < 0 || at >= length) return
        val lastChar = at + query.length - 1
        if (lastChar >= length) return
        var lastColumn = columnOf[lastChar]
        // Take the wide glyph's continuation cells with it.
        while (lastColumn + 1 < cells.size && cells[lastColumn + 1].width == 0) lastColumn++
        onMatch(columnOf[at], lastColumn)
        from = at + query.length
    }
}

/** What [TerminalSession.search] found; [truncated] when it stopped before the end of the buffer. */
data class TerminalSearchResult(val matches: List<TerminalSearchMatch>, val truncated: Boolean)
