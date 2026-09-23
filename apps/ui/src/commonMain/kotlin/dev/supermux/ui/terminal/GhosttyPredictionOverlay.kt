// Plan 4 Task 3: predictive echo for the shared renderer.
//
// The four retired renderers all predicted the SAME way: they wrote the engine's DisplayOps back
// into the emulator as bytes — dim SGR, a CUP, the server chunk, another CUP. That works only for
// an emulator you are allowed to lie to, and the shared one is not: its screen is the
// AUTHORITATIVE state a server frame patches, and a speculative glyph written into it becomes
// indistinguishable from one the program sent. It would survive a reconnect, count towards
// scrollback, be reported to a screen reader as real text and show up in a copied selection.
//
// So the speculation lives ABOVE the grid instead, in its own overlay, and the engine never hears
// about it. Server bytes go into Ghostty exactly once, unchanged, through the event adapter; what
// the engine emits in return is interpreted HERE, as a set of cells to paint on top, and the
// `Passthrough` op — the whole of how the old adapters got the server's bytes onto the screen — is
// deliberately dropped on the floor.
package dev.supermux.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import dev.supermux.net.CursorPos
import dev.supermux.net.DEFAULT_CONFIG
import dev.supermux.net.DisplayOp
import dev.supermux.net.DrawDim
import dev.supermux.net.MoveCaret
import dev.supermux.net.PredictionEngine
import dev.supermux.net.RestoreCell
import dev.supermux.net.decodeInput
import dev.supermux.terminal.TerminalViewport
import dev.supermux.terminal.compose.TerminalTheme
import dev.supermux.terminal.compose.measureCellMetrics
import kotlin.time.TimeSource

/**
 * A monotonic millisecond clock, in common code.
 *
 * MONOTONIC on purpose: both things this drives — the latency gate and the mispredict cooldown —
 * are durations, and a wall clock that steps (NTP, a phone crossing a timezone, a laptop waking)
 * would either open the gate on a negative round trip or park the cooldown in the far future.
 */
private val PROCESS_START = TimeSource.Monotonic.markNow()

internal fun currentTimeMillis(): Long = PROCESS_START.elapsedNow().inWholeMilliseconds

/** The overlay's node, for tests and for anything that needs to find it on screen. */
const val TERMINAL_PREDICTION_TAG = "terminal_prediction_overlay"

/** How much dimmer than real text a glyph nobody has confirmed is drawn. */
private const val PREDICTION_ALPHA = 0.55f

/**
 * One speculative glyph, in VIEWPORT coordinates: row 0 is the top of the visible screen and
 * column 0 its left edge — the same space [TerminalViewport.cursor] uses, which is where every
 * prediction's position comes from.
 */
data class PredictedCell(val row: Int, val column: Int, val text: String)

/**
 * The shared prediction engine's decisions, kept as CELLS rather than bytes.
 *
 * The engine itself is unchanged and shared with every other client — this only changes who
 * executes its [DisplayOp]s and where. The mapping:
 *
 *  - [DrawDim]      → a cell to paint on top. The only op that puts anything on screen.
 *  - [RestoreCell]  → drop that cell. The authoritative screen underneath is already right, so
 *                     "restoring" is nothing more than getting out of its way — which is why a
 *                     mispredict here cannot leave a wrong glyph behind the way a written-in one
 *                     could.
 *  - [MoveCaret]    → where the caret should SIT while predictions are outstanding. Advisory.
 *  - `Passthrough`  → DROPPED. Those are the server's own bytes, and they have already been fed
 *                     to Ghostty once, in order, by the event adapter. Feeding them again here is
 *                     precisely the double-write this design exists to prevent.
 *  - `HideCaret`/`ShowCaret` → nothing. They exist to hide repositioning flicker in an emulator
 *                     being rewritten underneath the user; an overlay has no flicker to hide.
 *
 * CONFIRMS ARE IMPLICIT, and that survives the move. In the byte design the server's echo painted
 * over the dim cell, and that overwrite WAS the confirm. Here [reconcile] does the same job from
 * the other side: a predicted cell whose authoritative cell now shows the predicted text has been
 * confirmed and stops being drawn. So a typed character appears at once, the echo arrives, and the
 * glyph does not double — the overlay simply stops drawing the one the screen now has.
 *
 * Every observation comes from a PUBLISHED [TerminalViewport]. Nothing here reads the engine
 * concurrently: the frame a prediction is measured against is the same frame being painted, which
 * is the only way the two can agree.
 *
 * Compose-thread confined, like the surface that owns it.
 */
@Stable
class GhosttyPredictionState internal constructor(
    private val engine: PredictionEngine,
    private val nowMs: () -> Long,
) {
    private val drawn = mutableStateMapOf<Int, PredictedCell>()

    /** Where the caret should sit while predictions are outstanding, or null for "wherever the
     * authoritative frame says". */
    var caret: CursorPos? by mutableStateOf(null)
        private set

    /**
     * When the keystroke still waiting for its echo was typed, or 0.
     *
     * The latency gate bootstraps from a real keystroke→echo round trip, INDEPENDENTLY of the
     * prediction path: the engine's estimate starts at 0, predictions need it above the threshold,
     * and the engine only samples it from a confirm — so without this the gate could never open.
     */
    private var lastKeyAt = 0L

    private var lastColumns = 0
    private var lastRows = 0
    private var lastAlternate = false

    /** What the overlay paints. Snapshot-backed: writing it invalidates the Canvas. */
    val cells: Collection<PredictedCell> get() = drawn.values

    /** Test/diagnostic view of the same thing, keyed by the engine's prediction id. */
    internal val byId: Map<Int, PredictedCell> get() = drawn

    /**
     * The user typed. [bytes] are what GHOSTTY encoded for the keystroke — the exact bytes going
     * to the pty — so the engine judges predictability on what the program will actually receive
     * rather than on a key name this layer would have to re-encode.
     *
     * [atMs] is when the key was ENCODED, not when this call happens. The two are the same only
     * if nothing queues in between, and something always does: these arrive through an ordered
     * lane, and stamping them on arrival measures how busy the UI thread was rather than how far
     * away the server is. A round trip mis-measured that way reads as ZERO latency, the gate
     * never opens, and predictive echo silently never happens — which is exactly what it did.
     */
    fun onInput(bytes: ByteArray, cursor: CursorPos, atMs: Long = nowMs()) {
        apply(engine.onInput(decodeInput(bytes.decodeToString()), cursor))
        lastKeyAt = atMs
    }

    /**
     * Server bytes arrived. They are NOT written anywhere from here — the adapter has already fed
     * them to Ghostty. This only lets the engine reconcile its outstanding predictions against
     * them and hand back what to stop (or start) drawing.
     *
     * [atMs] is when the bytes ARRIVED, for the same reason [onInput]'s is when the key was
     * encoded: together they are a round trip measured at the I/O boundary, which is the thing
     * predictive echo is deciding about.
     */
    fun onServerData(bytes: ByteArray, atMs: Long = nowMs()) {
        if (lastKeyAt > 0L) {
            engine.setLatencyEstimate((atMs - lastKeyAt).coerceAtLeast(0L))
            lastKeyAt = 0L
        }
        // A prediction bug must never take the terminal down with it: the authoritative screen is
        // already correct without any of this, so the worst honest outcome is no predictions.
        runCatching { apply(engine.onServerData(bytes)) }.onFailure { clear() }
    }

    /**
     * Forget everything, drawn and pending.
     *
     * Called on a new epoch, a reset, a resize, an alt-screen flip and on focus loss — every event
     * after which a prediction's coordinates mean something different from what they meant when it
     * was made.
     */
    fun clear() {
        engine.reset()
        drawn.clear()
        caret = null
        lastKeyAt = 0L
    }

    /**
     * Fold a freshly published frame in: drop what it confirmed, and drop EVERYTHING when the
     * screen it was predicted against is gone.
     *
     * A resize reflows; an alt-screen flip replaces the screen wholesale. In both cases a cell at
     * (row, column) is no longer the cell that was predicted there, and an overlay drawn against
     * the old geometry would paint glyphs into the middle of whatever is there now.
     */
    fun reconcile(viewport: TerminalViewport) {
        val columns = viewport.size.columns
        val rows = viewport.size.rows
        val alternate = viewport.modes.alternateScreen
        // The FIRST frame is not a change — there was nothing to predict against before it.
        val seenBefore = lastColumns > 0
        val geometryChanged = seenBefore && (columns != lastColumns || rows != lastRows)
        val screenChanged = seenBefore && alternate != lastAlternate
        lastColumns = columns
        lastRows = rows
        lastAlternate = alternate
        if (geometryChanged || screenChanged) {
            clear()
            return
        }
        if (drawn.isEmpty()) return
        val confirmed = drawn.filterValues { cell ->
            val actual = cellTextAt(viewport, cell.row, cell.column)
            actual == null || actual == cell.text
        }
        // `actual == null` is out of bounds: the frame shrank under a prediction, so it has no
        // cell to be right or wrong about and drawing it would paint outside the grid.
        for (id in confirmed.keys) drawn.remove(id)
        if (drawn.isEmpty()) caret = null
    }

    private fun apply(ops: List<DisplayOp>) {
        if (ops.isEmpty()) return
        for (op in ops) {
            when (op) {
                is DrawDim -> drawn[op.id] = PredictedCell(op.row, op.col, op.char)
                is RestoreCell -> drawn.remove(op.id)
                is MoveCaret -> caret = CursorPos(op.row, op.col)
                // Passthrough / HideCaret / ShowCaret: see the class KDoc. Nothing to do, and
                // doing something is the bug.
                else -> Unit
            }
        }
        if (drawn.isEmpty()) caret = null
    }
}

/** The text a published frame shows at (row, column), or null when that cell is off the frame. */
internal fun cellTextAt(viewport: TerminalViewport, row: Int, column: Int): String? {
    val line = viewport.rows.getOrNull(row) ?: return null
    val cell = line.cells.getOrNull(column) ?: return null
    return cell.text
}

/**
 * A prediction state for one surface.
 *
 * [nowMs] is injectable because the latency gate and the mispredict cooldown are both clock-driven
 * and a test that cannot move the clock cannot exercise either.
 */
@Composable
fun rememberGhosttyPredictions(nowMs: () -> Long = { currentTimeMillis() }): GhosttyPredictionState =
    remember { GhosttyPredictionState(PredictionEngine(DEFAULT_CONFIG, nowMs), nowMs) }

/**
 * Paint the outstanding predictions over the grid.
 *
 * Mounted through `Terminal`'s `overlay` slot, so it draws INSIDE the terminal's box, on top of
 * the grid canvas and below nothing. Geometry is derived the same way the painter derives it
 * (`x = column * cellWidth`, `y = row * cellHeight`), from the same [TerminalTheme], so a
 * predicted glyph lands exactly where its confirmed twin will.
 *
 * [scrolledBack] is the one gate: a viewport scrolled into history is painted with an offset this
 * overlay has no business re-deriving, and a prediction drawn at the caret while the user is
 * reading old output would be a glyph in the middle of somebody's scrollback. Predictions stay
 * TRACKED while scrolled back — they are still outstanding, and the reconcile still confirms them
 * — they are just not drawn.
 */
@Composable
fun GhosttyPredictionOverlay(
    state: GhosttyPredictionState,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
    scrolledBack: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val metrics = remember(measurer, theme, density) { measureCellMetrics(measurer, theme, density) }
    val style = remember(theme) {
        TextStyle(fontFamily = theme.fontFamily, fontSize = theme.fontSize)
    }
    val color = remember(theme) { theme.foreground.copy(alpha = PREDICTION_ALPHA) }
    // Read inside the composable, not inside the draw lambda only: the Canvas must RECOMPOSE when
    // a prediction appears, and a snapshot read that happens only in the draw phase would
    // invalidate drawing without ever re-running this.
    val cells = state.cells.toList()
    Canvas(modifier.testTag(TERMINAL_PREDICTION_TAG)) {
        if (scrolledBack) return@Canvas
        for (cell in cells) {
            drawPredictedGlyph(measurer, style, color, cell, metrics.width, metrics.height, metrics.baseline)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPredictedGlyph(
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    cell: PredictedCell,
    cellWidth: Float,
    cellHeight: Float,
    baseline: Float,
) {
    if (cell.text.isBlank()) return
    val layout = measurer.measure(text = cell.text, style = style, softWrap = false, maxLines = 1)
    val left = cell.column * cellWidth + (cellWidth - layout.size.width) / 2f
    val top = cell.row * cellHeight + baseline - layout.firstBaseline
    drawText(layout, color = color, topLeft = Offset(left, top))
}
