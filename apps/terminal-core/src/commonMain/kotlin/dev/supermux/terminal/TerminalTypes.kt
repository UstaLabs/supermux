package dev.supermux.terminal

// The shared terminal contract. Every binding (JNI on Android/JVM, cinterop on iOS, wasm in the
// browser) produces and consumes exactly these types; any field added here must be filled by every
// binding and every fixture. Integer-coded fields (flags, shapes, actions, key codes, colours) use
// the package-owned constants in TerminalConstants.kt — never Ghostty enum ordinals.

/** Grid size in cells plus the cell size in pixels (used for pixel-based mouse encoding / reports). */
data class TerminalSize(val columns: Int, val rows: Int, val cellWidthPx: Int, val cellHeightPx: Int) {
    init { require(columns > 0 && rows > 0 && cellWidthPx > 0 && cellHeightPx > 0) }
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

data class TerminalModes(val alternateScreen: Boolean, val mouseTracking: Boolean, val bracketedPaste: Boolean)

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
 */
data class TerminalViewport(
    val generation: Long, val size: TerminalSize, val rows: List<TerminalRow>,
    val cursor: TerminalCursor, val modes: TerminalModes,
    val historyRows: Long, val viewportTop: Long, val full: Boolean,
    val links: List<TerminalLink>, val selection: TerminalSelection?,
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
