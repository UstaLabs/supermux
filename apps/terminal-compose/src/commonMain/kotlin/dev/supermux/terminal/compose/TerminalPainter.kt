package dev.supermux.terminal.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import dev.supermux.terminal.CursorShape
import dev.supermux.terminal.TerminalRow
import dev.supermux.terminal.Underline
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * The one place a terminal frame becomes pixels.
 *
 * It is a plain `DrawScope` painter: no platform text widget, no DOM node, no `JTextPane`, no
 * `UITextView` — the same code draws on Android, the desktop JVM, iOS and the browser canvas, and
 * nothing in the draw path can call the native engine.
 *
 * Painting order is fixed: background runs, selection, text runs, decorations, cursor. Text is
 * placed against ONE baseline per row ([CellMetrics.baseline]) and each run is clipped to the
 * columns it owns horizontally (never vertically — an accent or a tall CJK glyph may overshoot its
 * line box, and clipping that is exactly the artefact this rule exists to avoid). Spacing is
 * geometry: nothing is ever padded with literal spaces.
 *
 * [scrollOffsetPx] shifts the whole grid up by that many pixels — the sub-row displacement of
 * smooth scrolling (see `ScrollController`); at 0 the grid starts at the top of the canvas. A
 * NEGATIVE value shifts it down, which happens while the engine is still catching up with the
 * anchor. Either way the exposed edge is filled by [drawOverscanRow], never left blank.
 */
fun DrawScope.drawTerminalFrame(
    frame: TerminalFrame,
    runs: FrameRuns,
    metrics: CellMetrics,
    theme: TerminalTheme,
    measurer: TextMeasurer,
    cache: TextLayoutCache,
    scrollOffsetPx: Float = 0f,
    cursorEnabled: Boolean = true,
    marked: String = "",
) {
    val fontSizePx = theme.fontSize.toPx()
    cache.retune(
        CacheSignature(
            fontFamily = theme.fontFamily,
            fontSizePx = fontSizePx,
            cellWidth = metrics.width,
            cellHeight = metrics.height,
            themeKey = theme.hashCode(),
        ),
    )
    val painter = RunPainter(metrics, theme, measurer, cache, fontSizePx)
    clipRect {
        translate(top = -scrollOffsetPx) {
            for (run in runs.backgrounds) painter.fill(this, run)
            for (run in runs.selections) painter.fill(this, run)
            for (run in runs.texts) painter.text(this, run)
            for (run in runs.decorations) painter.decorate(this, run)
            if (cursorEnabled) painter.cursor(this, frame)
            if (marked.isNotEmpty()) painter.marked(this, frame, marked)
        }
    }
}

/**
 * ONE row of scrollback drawn outside the grid the engine published — the overscan that keeps a
 * fractional [drawTerminalFrame] offset from tearing a blank strip into the screen.
 *
 * The engine hands out exactly the grid's rows, and asking it for one more would change the pty's
 * window size, so [row] comes from a neighbouring frame the surface still remembers (`ScrollStrip`)
 * and is drawn as a one-row frame of its own at [absoluteRow]. It carries no cursor — the cursor
 * belongs to the real frame — but it keeps the frame's selection, which is in absolute coordinates
 * and therefore still lands on the right columns of this row.
 *
 * This entry point builds the row's runs on every call. The surface itself does not use it: it
 * memoizes both the derived frame and its runs across paints ([OverscanRunsCache]), because a
 * smooth scroll repaints the same row at a different offset many times per second.
 */
fun DrawScope.drawOverscanRow(
    frame: TerminalFrame,
    row: TerminalRow,
    absoluteRow: Long,
    metrics: CellMetrics,
    theme: TerminalTheme,
    measurer: TextMeasurer,
    cache: TextLayoutCache,
    scrollOffsetPx: Float,
) {
    val single = overscanFrame(frame, row, absoluteRow)
    drawTerminalFrame(
        frame = single,
        runs = TerminalRuns.build(single, theme),
        metrics = metrics,
        theme = theme,
        measurer = measurer,
        cache = cache,
        scrollOffsetPx = scrollOffsetPx,
        cursorEnabled = false,
    )
}

/**
 * [row] as a one-row frame at absolute row [absoluteRow]: the shape [drawOverscanRow] and the
 * surface's own memoized overscan path both paint, so the two can never drift apart.
 */
internal fun overscanFrame(frame: TerminalFrame, row: TerminalRow, absoluteRow: Long): TerminalFrame =
    frame.copy(
        size = frame.size.copy(rows = 1),
        rows = listOf(row.copy(index = 0)),
        viewportTop = absoluteRow,
        links = emptyList(),
    )

/**
 * Measures the cell box for [theme]: the advance of the font's reference glyph, its line height and
 * where its baseline sits. Both axes are rounded to whole pixels — see [CellMetrics].
 */
fun measureCellMetrics(measurer: TextMeasurer, theme: TerminalTheme, density: Density): CellMetrics {
    val style = TextStyle(fontFamily = theme.fontFamily, fontSize = theme.fontSize)
    // A run, not one glyph: one glyph's reported width includes its side bearings, while the
    // difference between n and n+1 glyphs is the advance the font will actually use.
    val layout = measurer.measure(
        text = REFERENCE_RUN,
        style = style,
        softWrap = false,
        maxLines = 1,
        density = density,
    )
    val advance = layout.getLineRight(0) / REFERENCE_RUN.length
    val lineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
    val width = max(1f, round(advance))
    val height = max(1f, round(lineHeight * theme.lineHeightScale))
    val baseline = layout.firstBaseline + (height - lineHeight) / 2f
    return CellMetrics(width = width, height = height, baseline = baseline, lineHeight = lineHeight)
}

private const val REFERENCE_RUN = "MMMMMMMMMM"

/** Everything the three draw passes share; one per draw, never retained. */
private class RunPainter(
    private val metrics: CellMetrics,
    private val theme: TerminalTheme,
    private val measurer: TextMeasurer,
    private val cache: TextLayoutCache,
    private val fontSizePx: Float,
) {
    private val thin = max(1f, metrics.height / 16f)

    private fun x(column: Int) = column * metrics.width
    private fun y(row: Int) = row * metrics.height

    fun fill(scope: DrawScope, run: BackgroundRun) = with(scope) {
        drawRect(
            color = run.color,
            topLeft = Offset(x(run.column), y(run.row)),
            size = Size(run.columns * metrics.width, metrics.height),
        )
    }

    fun text(scope: DrawScope, run: TextRun) = with(scope) {
        val layout = layoutOf(run.text, run.style)
        val advance = layout.size.width.toFloat()
        val wanted = run.columns * metrics.width
        // A run whose measured advance matches the cells it owns is drawn in one go; anything else
        // (a proportional fallback font, a font with a different advance for box drawing) is drawn
        // cell by cell so that column N never lands anywhere but at column N.
        if (abs(advance - wanted) <= TOLERANCE_PER_COLUMN * run.columns) {
            draw(this, layout, run.style, x(run.column), y(run.row), wanted)
            return@with
        }
        if (run.monospaced) {
            for (offset in run.text.indices) {
                val glyph = layoutOf(run.text[offset].toString(), run.style)
                draw(this, glyph, run.style, x(run.column + offset), y(run.row), metrics.width)
            }
        } else {
            // One cluster that does not fit its cells: centre it instead of letting it start at the
            // left edge and bleed right, and clip it to its own columns.
            draw(this, layout, run.style, x(run.column), y(run.row), wanted, centre = true)
        }
    }

    private fun draw(
        scope: DrawScope,
        layout: TextLayoutResult,
        style: ResolvedStyle,
        left: Float,
        top: Float,
        width: Float,
        centre: Boolean = false,
    ) = with(scope) {
        val offsetX = if (centre) left + (width - layout.size.width) / 2f else left
        val offsetY = top + metrics.baseline - layout.firstBaseline
        // Horizontal clip only: a glyph may legitimately overshoot its line box vertically (accents,
        // CJK), and clipping that is what "clipped accents" looks like on a screenshot diff.
        clipRect(left = left, top = 0f, right = left + width, bottom = size.height) {
            drawText(layout, color = style.foreground, topLeft = Offset(offsetX, offsetY))
        }
    }

    fun decorate(scope: DrawScope, run: DecorationRun) = with(scope) {
        val left = x(run.column)
        val right = left + run.columns * metrics.width
        val top = y(run.row)
        val baseline = top + metrics.baseline
        if (run.overline) {
            drawRect(run.color, Offset(left, top), Size(right - left, thin))
        }
        if (run.strikethrough) {
            val middle = top + metrics.baseline - metrics.lineHeight * STRIKE_HEIGHT
            drawRect(run.color, Offset(left, middle), Size(right - left, thin))
        }
        if (run.underline == Underline.NONE) return@with
        val gap = max(1f, metrics.height * UNDERLINE_GAP)
        val underlineY = min(baseline + gap, top + metrics.height - thin)
        when (run.underline) {
            Underline.SINGLE -> drawRect(run.color, Offset(left, underlineY), Size(right - left, thin))
            Underline.DOUBLE -> {
                val second = min(underlineY + thin * 2f, top + metrics.height - thin)
                drawRect(run.color, Offset(left, underlineY), Size(right - left, thin))
                drawRect(run.color, Offset(left, second), Size(right - left, thin))
            }
            Underline.CURLY -> {
                val amplitude = max(1f, thin)
                val period = max(4f, metrics.width / 2f)
                val path = Path()
                path.moveTo(left, underlineY)
                var cursor = left
                var up = true
                while (cursor < right) {
                    val next = min(cursor + period, right)
                    path.quadraticTo(
                        (cursor + next) / 2f,
                        if (up) underlineY - amplitude else underlineY + amplitude,
                        next,
                        underlineY,
                    )
                    up = !up
                    cursor = next
                }
                drawPath(path, run.color, style = Stroke(width = thin))
            }
            Underline.DOTTED, Underline.DASHED -> {
                val on = if (run.underline == Underline.DOTTED) thin else thin * 3f
                val off = if (run.underline == Underline.DOTTED) thin * 2f else thin * 3f
                drawLine(
                    color = run.color,
                    start = Offset(left, underlineY + thin / 2f),
                    end = Offset(right, underlineY + thin / 2f),
                    strokeWidth = thin,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, off)),
                )
            }
        }
    }

    fun cursor(scope: DrawScope, frame: TerminalFrame) = with(scope) {
        val cursor = frame.cursor
        if (!cursor.visible) return@with
        if (cursor.row !in 0 until frame.size.rows || cursor.column !in 0 until frame.size.columns) return@with
        val cell = frame.cellAt(cursor.row, cursor.column)
        val left = x(cursor.column)
        val top = y(cursor.row)
        // A wide glyph's cursor covers both of its cells, like every other terminal.
        val columns = if (cell != null && cell.width == 2) 2 else 1
        val width = columns * metrics.width
        when (cursor.shape) {
            CursorShape.BAR -> drawRect(theme.cursor, Offset(left, top), Size(max(1f, metrics.width / 6f), metrics.height))
            CursorShape.UNDERLINE -> {
                val bar = max(1f, metrics.height / 8f)
                drawRect(theme.cursor, Offset(left, top + metrics.height - bar), Size(width, bar))
            }
            CursorShape.BLOCK_HOLLOW -> drawRect(
                color = theme.cursor,
                topLeft = Offset(left + thin / 2f, top + thin / 2f),
                size = Size(width - thin, metrics.height - thin),
                style = Stroke(width = thin),
            )
            else -> {
                drawRect(theme.cursor, Offset(left, top), Size(width, metrics.height))
                // The glyph under a filled block has to be redrawn in a colour that survives it.
                val text = cell?.text
                if (cell != null && cell.width != 0 && !text.isNullOrEmpty()) {
                    val style = TerminalRuns.resolve(cell, theme)
                    val contrast = theme.cursorText ?: style.background
                    val layout = layoutOf(text, style)
                    draw(this, layout, style.copy(foreground = contrast), left, top, width, centre = columns == 2)
                }
            }
        }
    }

    /**
     * The text an IME is still composing, drawn AT the cursor and over the cells it would occupy.
     *
     * It is not terminal content and never will be unless the user commits it, so it is painted
     * here rather than folded into the frame: the engine has never heard of these characters and
     * the next frame would wipe them. The underline is the convention every platform uses for
     * marked text, and it is what tells the user these characters are not in the shell yet.
     *
     * It is clipped to the row: a long composition runs off the right edge instead of wrapping onto
     * a row of real output and hiding it.
     */
    fun marked(scope: DrawScope, frame: TerminalFrame, text: String) = with(scope) {
        val cursor = frame.cursor
        if (cursor.row !in 0 until frame.size.rows) return@with
        val left = x(cursor.column.coerceIn(0, maxOf(0, frame.size.columns - 1)))
        val top = y(cursor.row)
        val right = frame.size.columns * metrics.width
        if (right <= left) return@with
        val style = ResolvedStyle(foreground = theme.foreground, background = theme.background)
        val layout = layoutOf(text, style)
        val width = minOf(layout.size.width.toFloat(), right - left)
        clipRect(left = left, top = top, right = left + width, bottom = top + metrics.height) {
            drawRect(theme.background, Offset(left, top), Size(width, metrics.height))
            drawText(layout, color = theme.foreground, topLeft = Offset(left, top + metrics.baseline - layout.firstBaseline))
            drawRect(theme.cursor, Offset(left, top + metrics.height - thin), Size(width, thin))
        }
    }

    private fun layoutOf(text: String, style: ResolvedStyle): TextLayoutResult {
        val key = TextRunKey(
            text = text,
            fontSizePx = fontSizePx,
            fontFamily = theme.fontFamily,
            bold = style.bold,
            italic = style.italic,
        )
        return cache.getOrPut(key) {
            measurer.measure(
                text = it.text,
                style = TextStyle(
                    fontFamily = theme.fontFamily,
                    fontSize = theme.fontSize,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                    color = Color.Unspecified,
                ),
                softWrap = false,
                maxLines = 1,
            )
        }
    }

    private companion object {
        /** How far a run's measured advance may stray from its cells before it is split, per cell. */
        const val TOLERANCE_PER_COLUMN = 0.5f

        /** Strikethrough height above the baseline, as a fraction of the line box. */
        const val STRIKE_HEIGHT = 0.28f

        /** Gap between the baseline and an underline, as a fraction of the cell height. */
        const val UNDERLINE_GAP = 0.08f
    }
}

/**
 * The two touch handles of the current selection.
 *
 * Drawn by the surface and not by [drawTerminalFrame], because they are chrome rather than grid:
 * they sit outside the cells (above the first, below the last) and must not be clipped to the grid
 * rectangle the way an overscan row is.
 */
fun DrawScope.drawSelectionHandles(
    handles: List<SelectionHandleSpot>,
    theme: TerminalTheme,
    radiusPx: Float,
) {
    if (radiusPx <= 0f) return
    for (spot in handles) {
        drawCircle(color = theme.selectionHandle, radius = radiusPx, center = spot.position)
    }
}
