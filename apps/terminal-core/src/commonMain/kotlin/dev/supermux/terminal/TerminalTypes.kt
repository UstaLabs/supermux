package dev.supermux.terminal

// The shared terminal contract. Every binding (JNI on Android/JVM, cinterop on iOS, wasm in the
// browser) produces and consumes exactly these types; any field added here must be filled by every
// binding and every fixture. Integer-coded fields (flags, shapes, actions, key codes, colours) use
// the package-owned constants in TerminalConstants.kt — never Ghostty enum ordinals.

/**
 * Grid size in cells plus the cell size in pixels (used for pixel-based mouse encoding / reports).
 * Columns and rows are 1..[MAX_DIMENSION] and `columns * rows` is at most [MAX_CELLS]: the engine
 * refuses larger grids up front because a worst-case frame of them would not fit the 8 MiB codec
 * payload (native `ST_MAX_CELLS`).
 */
data class TerminalSize(val columns: Int, val rows: Int, val cellWidthPx: Int, val cellHeightPx: Int) {
    init {
        require(columns in 1..MAX_DIMENSION && rows in 1..MAX_DIMENSION) { "size ${columns}x$rows out of range" }
        require(columns.toLong() * rows <= MAX_CELLS) { "${columns}x$rows exceeds $MAX_CELLS cells" }
        require(cellWidthPx > 0 && cellHeightPx > 0)
    }

    companion object {
        /** Maximum columns or rows (native `ST_MAX_DIMENSION`). */
        const val MAX_DIMENSION: Int = 4096
        /** Maximum `columns * rows` (native `ST_MAX_CELLS`). */
        const val MAX_CELLS: Int = 100_000
        /** Maximum UTF-8 bytes of one cell's text on the wire; longer clusters are cut (native `ST_MAX_CELL_TEXT`). */
        const val MAX_CELL_TEXT_BYTES: Int = 32
    }
}

/**
 * Scrollback limits. Both are enforced by the engine, the first one reached wins; Ghostty enforces
 * them page-granularly, so the line limit may be exceeded by up to ~one page. `historyBytes = 0`
 * disables scrollback.
 */
data class TerminalLimits(val historyLines: Int = 50_000, val historyBytes: Long = 32L * 1024 * 1024) {
    init { require(historyLines >= 0 && historyBytes >= 0) }
}

/**
 * Resolved style of one cell.
 * - [foreground] / [background]: [TerminalColor] encoding (RGBA, or [TerminalColor.DEFAULT] for
 *   "the terminal's default colour"). Palette indices are already resolved to RGBA; bold-brightening
 *   is NOT applied (the renderer decides).
 * - [flags]: bit set of [CellFlags].
 * - [underline]: one of [Underline].
 */
data class CellStyle(val foreground: Long, val background: Long, val flags: Int, val underline: Int)

/**
 * One grid cell. [text] is the whole grapheme cluster ("" for an empty cell).
 * [width]: 1 = narrow leading cell, 2 = wide leading cell, 0 = continuation of the wide cell to its
 * left (or a wrap spacer) — never render text for width 0.
 */
data class TerminalCell(val text: String, val width: Int, val style: CellStyle) {
    init { require(width in 0..2) }
}

/** A viewport row; [index] is 0-based from the top of the viewport. */
data class TerminalRow(val index: Int, val cells: List<TerminalCell>)

/** Cursor in viewport cell coordinates; [shape] is one of [CursorShape]. */
data class TerminalCursor(val column: Int, val row: Int, val shape: Int, val visible: Boolean)

/**
 * The negotiated modes a renderer has to route input by.
 * - [alternateScreen]: mode 1047/1049, the full-screen program's own screen (no scrollback).
 * - [mouseTracking]: any of 9/1000/1002/1003 — the program asked for the mouse.
 * - [bracketedPaste]: mode 2004; the engine wraps pastes itself, this only says the program knows.
 * - [alternateScroll]: mode 1007. On the alternate screen, with mouse tracking OFF, the wheel is
 *   conventionally translated into cursor-key presses so a pager scrolls. The mode says the program
 *   wants that; it produces NO bytes of its own (the engine's KEY encoder does, from
 *   [TerminalKeys.ARROW_UP] / [TerminalKeys.ARROW_DOWN], so application-cursor mode is respected).
 */
data class TerminalModes(
    val alternateScreen: Boolean,
    val mouseTracking: Boolean,
    val bracketedPaste: Boolean,
    val alternateScroll: Boolean,
)

/**
 * Default colours ([TerminalColor] encoding, never [TerminalColor.DEFAULT]) and the 256-entry
 * palette (0–15 ANSI, 16–255 xterm cube/greys). Remote OSC colour changes layer on top.
 */
data class TerminalColors(val foreground: Long, val background: Long, val cursor: Long, val palette: List<Long>) {
    init { require(palette.size == TerminalColor.PALETTE_SIZE) { "palette must have ${TerminalColor.PALETTE_SIZE} entries" } }
}

/** An OSC 8 hyperlink span on one viewport row, columns inclusive. */
data class TerminalLink(val row: Int, val firstColumn: Int, val lastColumn: Int, val uri: String)

/**
 * A rendered frame.
 * - [generation]: increases on every state change; pass it to [TerminalEngine.acknowledge] once drawn.
 * - [rows]: all rows when [full], otherwise only the rows changed since the last acknowledged generation.
 * - [historyRows]: scrollback rows above the active screen; [viewportTop] is the absolute row
 *   (0 = oldest history row) shown at the top of the viewport, the same space as [TerminalPoint.row].
 * - [held]: this is the frame captured when the program began synchronized output (mode 2026) and
 *   the hold is still active; the owner keeps showing it and, after its timeout (~1 s), asks for
 *   `viewport(breakHold = true)`.
 */
data class TerminalViewport(
    val generation: Long, val size: TerminalSize, val rows: List<TerminalRow>,
    val cursor: TerminalCursor, val modes: TerminalModes,
    val historyRows: Long, val viewportTop: Long, val full: Boolean,
    val links: List<TerminalLink>, val selection: TerminalSelection?,
    val held: Boolean,
)

/**
 * A key event. [physicalCode] is a [TerminalKeys] code (layout-independent physical key);
 * [text] is the layout text the key produced WITHOUT Ctrl/Meta applied ("" if none — never C0/DEL);
 * [modifiers] is a [Modifiers] bit set; [action] is a [KeyAction].
 */
data class TerminalKey(val physicalCode: Int, val text: String, val modifiers: Int, val action: Int)

/**
 * A mouse event in viewport CELL coordinates (the binding converts to Ghostty's surface pixels with
 * the cell size). [button] is a [MouseButton], [modifiers] a [Modifiers] set, [action] a [MouseAction].
 */
data class TerminalMouse(val column: Int, val row: Int, val button: Int, val modifiers: Int, val action: Int)

/** An absolute grid point: [row] in the history+screen space (0 = oldest history row). */
data class TerminalPoint(val row: Long, val column: Int)

/** A linear selection, both ends inclusive, in either order. */
data class TerminalSelection(val start: TerminalPoint, val end: TerminalPoint)

/**
 * Where fed bytes come from. [REPLAY] is buffered history re-rendered after a (re)attach: it updates
 * the screen but must never produce [TerminalEffect.Response], [TerminalEffect.Bell] or
 * [TerminalEffect.ClipboardRequest] — the remote program that asked is long gone.
 */
enum class OutputOrigin { LIVE, REPLAY }
