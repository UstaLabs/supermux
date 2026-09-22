package dev.supermux.terminal.compose

import dev.supermux.terminal.CellFlags
import dev.supermux.terminal.CellStyle
import dev.supermux.terminal.CursorShape
import dev.supermux.terminal.TerminalCell
import dev.supermux.terminal.TerminalColor
import dev.supermux.terminal.TerminalCursor
import dev.supermux.terminal.TerminalLink
import dev.supermux.terminal.TerminalModes
import dev.supermux.terminal.TerminalRow
import dev.supermux.terminal.TerminalSelection
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.TerminalViewport
import dev.supermux.terminal.Underline

/**
 * Viewport fixtures shared by the reducer and surface suites. They build exactly what the engine
 * would publish — full frames carrying every row, partial frames carrying only the rows that
 * changed — so a test never has to know how the codec works.
 */
object ViewportFixtures {

    /** The primary screen, no mouse reporting, no bracketed paste. */
    val LIVE_MODES = TerminalModes(alternateScreen = false, mouseTracking = false, bracketedPaste = false)

    val DEFAULT_STYLE = CellStyle(TerminalColor.DEFAULT, TerminalColor.DEFAULT, CellFlags.NONE, Underline.NONE)

    /** A row of [columns] cells holding [text], padded with blanks. Narrow cells only. */
    fun row(index: Int, text: String, columns: Int, style: CellStyle = DEFAULT_STYLE): TerminalRow =
        TerminalRow(index, List(columns) { column ->
            TerminalCell(if (column < text.length) text[column].toString() else "", 1, style)
        })

    /** A full frame whose rows are [lines] (missing lines are blank). */
    fun full(
        generation: Long,
        size: TerminalSize,
        lines: List<String> = emptyList(),
        cursor: TerminalCursor = TerminalCursor(0, 0, CursorShape.BLOCK, visible = true),
        selection: TerminalSelection? = null,
        viewportTop: Long = 0,
        historyRows: Long = 0,
        modes: TerminalModes = LIVE_MODES,
    ): TerminalViewport = viewport(
        generation = generation,
        size = size,
        rows = List(size.rows) { index -> row(index, lines.getOrElse(index) { "" }, size.columns) },
        full = true,
        cursor = cursor,
        selection = selection,
        viewportTop = viewportTop,
        historyRows = historyRows,
        modes = modes,
    )

    /** A partial frame carrying only the rows in [lines] (index to text). */
    fun partial(
        generation: Long,
        size: TerminalSize,
        lines: Map<Int, String>,
        cursor: TerminalCursor = TerminalCursor(0, 0, CursorShape.BLOCK, visible = true),
    ): TerminalViewport = viewport(
        generation = generation,
        size = size,
        rows = lines.entries.sortedBy { it.key }.map { row(it.key, it.value, size.columns) },
        full = false,
        cursor = cursor,
    )

    fun viewport(
        generation: Long,
        size: TerminalSize,
        rows: List<TerminalRow>,
        full: Boolean,
        cursor: TerminalCursor = TerminalCursor(0, 0, CursorShape.BLOCK, visible = true),
        selection: TerminalSelection? = null,
        viewportTop: Long = 0,
        historyRows: Long = 0,
        links: List<TerminalLink> = emptyList(),
        held: Boolean = false,
        modes: TerminalModes = LIVE_MODES,
    ): TerminalViewport = TerminalViewport(
        generation = generation,
        size = size,
        rows = rows,
        cursor = cursor,
        modes = modes,
        historyRows = historyRows,
        viewportTop = viewportTop,
        full = full,
        links = links,
        selection = selection,
        held = held,
    )
}
