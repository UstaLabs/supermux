package dev.supermux.ui.terminal

import dev.supermux.net.CursorPos
import dev.supermux.net.PredictionConfig
import dev.supermux.net.PredictionEngine
import dev.supermux.terminal.CellStyle
import dev.supermux.terminal.TerminalCell
import dev.supermux.terminal.TerminalCursor
import dev.supermux.terminal.TerminalModes
import dev.supermux.terminal.TerminalRow
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.TerminalViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Predictive echo WITHOUT writing into the emulator.
 *
 * The property every one of these pins is the same one: Ghostty's screen is the authoritative
 * state, nothing speculative is ever written into it, and the overlay's job is to stop drawing a
 * glyph the moment the real screen has one. "Appears, then confirms without doubling" is not a
 * timing coincidence here — it is what the reconcile DOES.
 */
class GhosttyPredictionOverlayTest {

    private val style = CellStyle(foreground = 0L, background = 0L, flags = 0, underline = 0)

    private fun viewport(
        lines: List<String>,
        cursorColumn: Int = 0,
        alternate: Boolean = false,
        columns: Int = 20,
        rows: Int = 3,
        generation: Long = 1L,
    ): TerminalViewport {
        val geometry = TerminalSize(columns, rows, 8, 16)
        return TerminalViewport(
            generation = generation,
            size = geometry,
            rows = (0 until rows).map { index ->
                val text = lines.getOrElse(index) { "" }.padEnd(columns, ' ')
                TerminalRow(index, (0 until columns).map { TerminalCell(text[it].toString(), 1, style) })
            },
            cursor = TerminalCursor(column = cursorColumn, row = 0, shape = 0, visible = true),
            modes = TerminalModes(
                alternateScreen = alternate,
                mouseTracking = false,
                bracketedPaste = false,
                alternateScroll = false,
            ),
            historyRows = 0L,
            viewportTop = 0L,
            full = true,
            links = emptyList(),
            selection = null,
            held = false,
            sequence = generation,
        )
    }

    /** A clock the test drives, so the latency gate and the cooldown are not wall-clock luck. */
    private class Clock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    /**
     * A state that has passed BOTH of the engine's gates, the way a real session passes them.
     *
     *  1. The LATENCY gate. The estimate starts at 0 and only a real keystroke→echo round trip
     *     raises it, so the fixture performs one: type, let 60 ms pass, echo it back. Below the
     *     threshold nothing is ever predicted, which is the whole point on a local shell.
     *  2. WAIT-FOR-FIRST-CONFIRMATION. Even past the latency gate the first prediction of an epoch
     *     is tracked but NOT drawn until the server has confirmed one — that is what keeps a
     *     password prompt from ghosting the characters it deliberately does not echo. So the
     *     fixture types a second character and lets the server echo that too.
     *
     * After this, and only after this, a keystroke is drawn speculatively.
     */
    private fun primed(clock: Clock): GhosttyPredictionState {
        val state = GhosttyPredictionState(PredictionEngine(PredictionConfig(40, 600, 50), clock), clock)
        state.onInput("x".encodeToByteArray(), CursorPos(0, 0))
        clock.now += 60
        state.onServerData("x".encodeToByteArray())
        state.onInput("y".encodeToByteArray(), CursorPos(0, 1))
        clock.now += 50
        state.onServerData("y".encodeToByteArray())
        return state
    }

    @Test
    fun nothing_is_drawn_until_the_server_has_confirmed_one_prediction() {
        val clock = Clock()
        val state = GhosttyPredictionState(PredictionEngine(PredictionConfig(40, 600, 50), clock), clock)
        // Past the latency gate...
        state.onInput("x".encodeToByteArray(), CursorPos(0, 0))
        clock.now += 60
        state.onServerData("x".encodeToByteArray())

        // ...but nothing has ever been confirmed, so the first one is tracked and not drawn.
        state.onInput("y".encodeToByteArray(), CursorPos(0, 1))
        assertEquals(emptyList(), state.cells.toList())

        // A program that never echoes (a password prompt) therefore ghosts nothing at all.
        clock.now += 50
        state.onServerData("y".encodeToByteArray())
        state.onInput("z".encodeToByteArray(), CursorPos(0, 2))
        assertEquals(listOf(PredictedCell(0, 2, "z")), state.cells.toList())
    }

    /**
     * The round trip is the WIRE's, not the UI thread's.
     *
     * Keystrokes and server bytes reach this engine through an ordered lane, so the moment they
     * are handed over is not the moment they happened. Stamping them on arrival measures the gap
     * between two queued calls — near zero — and a zero round trip never clears the latency gate,
     * so predictive echo silently never happens at all.
     */
    @Test
    fun the_latency_gate_opens_on_when_the_bytes_happened_not_when_the_lane_drained_them() {
        val clock = Clock()
        val state = GhosttyPredictionState(PredictionEngine(PredictionConfig(40, 600, 50), clock), clock)
        // The lane drained late: every call below happens at 5000 by the engine's own clock, and
        // the only thing that says otherwise is the stamp each one carries.
        clock.now = 5_000
        state.onInput("x".encodeToByteArray(), CursorPos(0, 0), atMs = 1_000)
        state.onServerData("x".encodeToByteArray(), atMs = 1_200)
        state.onInput("y".encodeToByteArray(), CursorPos(0, 1), atMs = 1_300)
        state.onServerData("y".encodeToByteArray(), atMs = 1_500)

        state.onInput("z".encodeToByteArray(), CursorPos(0, 2), atMs = 1_600)

        assertEquals(listOf(PredictedCell(0, 2, "z")), state.cells.toList())
    }

    @Test
    fun a_round_trip_that_looks_instant_never_opens_the_gate() {
        val clock = Clock()
        val state = GhosttyPredictionState(PredictionEngine(PredictionConfig(40, 600, 50), clock), clock)
        // What stamping on arrival at a busy UI thread produces: everything at one instant.
        for (ch in listOf("x", "y")) {
            state.onInput(ch.encodeToByteArray(), CursorPos(0, 0), atMs = 5_000)
            state.onServerData(ch.encodeToByteArray(), atMs = 5_000)
        }

        state.onInput("z".encodeToByteArray(), CursorPos(0, 2), atMs = 5_000)

        // A terminal that answers instantly does not need to be guessed at, and a measurement
        // that only LOOKS instant must not be mistaken for one that is.
        assertEquals(emptyList(), state.cells.toList())
    }

    @Test
    fun a_typed_character_is_drawn_at_once_and_stops_being_drawn_once_the_echo_lands() {
        val clock = Clock()
        val state = primed(clock)
        state.reconcile(viewport(listOf("$ ")))

        state.onInput("a".encodeToByteArray(), CursorPos(0, 2))

        // Drawn speculatively, at the caret, before any server byte.
        assertEquals(listOf(PredictedCell(0, 2, "a")), state.cells.toList())

        // The echo. It feeds Ghostty in the adapter, NOT here; all this does is reconcile.
        clock.now += 50
        state.onServerData("a".encodeToByteArray())
        state.reconcile(viewport(listOf("$ a"), cursorColumn = 3))

        // The authoritative screen shows the 'a'. Exactly one 'a' is on screen because the overlay
        // stopped drawing its own — nothing was written into the engine to double it.
        assertEquals(emptyList(), state.cells.toList())
    }

    @Test
    fun a_prediction_the_screen_contradicts_is_dropped_and_the_real_text_stands() {
        val clock = Clock()
        val state = primed(clock)
        state.reconcile(viewport(listOf("$ ")))

        state.onInput("a".encodeToByteArray(), CursorPos(0, 2))
        assertEquals(listOf(PredictedCell(0, 2, "a")), state.cells.toList())

        // The program echoed something ELSE — a password prompt's star, an editor's own redraw.
        clock.now += 50
        state.onServerData("*".encodeToByteArray())

        // The engine diverged and restored; nothing of ours is left over the real glyph.
        assertEquals(emptyList(), state.cells.toList())
        state.reconcile(viewport(listOf("$ *"), cursorColumn = 3))
        assertEquals(emptyList(), state.cells.toList())
    }

    @Test
    fun a_reflow_drops_every_prediction_rather_than_painting_into_the_new_geometry() {
        val clock = Clock()
        val state = primed(clock)
        state.reconcile(viewport(listOf("$ ")))
        state.onInput("a".encodeToByteArray(), CursorPos(0, 2))
        assertTrue(state.cells.isNotEmpty())

        state.reconcile(viewport(listOf("$ "), columns = 40))

        assertEquals(emptyList(), state.cells.toList())
    }

    @Test
    fun entering_the_alternate_screen_drops_every_prediction() {
        val clock = Clock()
        val state = primed(clock)
        state.reconcile(viewport(listOf("$ ")))
        state.onInput("a".encodeToByteArray(), CursorPos(0, 2))
        assertTrue(state.cells.isNotEmpty())

        state.reconcile(viewport(listOf("        "), alternate = true))

        assertEquals(emptyList(), state.cells.toList())
    }

    @Test
    fun clear_forgets_the_engine_state_as_well_as_the_drawn_cells() {
        val clock = Clock()
        val state = primed(clock)
        state.reconcile(viewport(listOf("$ ")))
        state.onInput("a".encodeToByteArray(), CursorPos(0, 2))
        assertTrue(state.cells.isNotEmpty())

        state.clear()

        assertEquals(emptyList(), state.cells.toList())
        assertEquals(null, state.caret)
        // The engine forgot the pending prediction too: an echo of it confirms nothing and, more
        // to the point, does not come back as a divergence against a screen that is gone.
        clock.now += 50
        state.onServerData("a".encodeToByteArray())
        assertEquals(emptyList(), state.cells.toList())
    }

    @Test
    fun a_keystroke_below_the_latency_threshold_is_never_predicted() {
        val clock = Clock()
        // No priming: the estimate starts at 0, which is below the 40 ms gate.
        val state = GhosttyPredictionState(PredictionEngine(PredictionConfig(40, 600, 50), clock), clock)
        state.reconcile(viewport(listOf("$ ")))

        state.onInput("a".encodeToByteArray(), CursorPos(0, 2))

        // A local terminal echoes faster than the eye: predicting there is all risk and no gain.
        assertEquals(emptyList(), state.cells.toList())
    }

    @Test
    fun a_paste_is_opaque_and_predicts_nothing() {
        val clock = Clock()
        val state = primed(clock)
        state.reconcile(viewport(listOf("$ ")))

        state.onInput("rm -rf /tmp/x\n".encodeToByteArray(), CursorPos(0, 2))

        // Multi-character input changes the line unpredictably; the engine says so and the overlay
        // has nothing to draw.
        assertEquals(emptyList(), state.cells.toList())
    }

    @Test
    fun a_prediction_off_the_end_of_a_shrunken_frame_is_dropped_not_painted() {
        val clock = Clock()
        val state = primed(clock)
        state.reconcile(viewport(listOf("$ "), columns = 20))
        state.onInput("a".encodeToByteArray(), CursorPos(0, 18))
        assertEquals(listOf(PredictedCell(0, 18, "a")), state.cells.toList())

        // Same geometry, but the frame arrives with fewer cells than the prediction's column.
        val narrow = viewport(listOf("$ "), columns = 20).let { frame ->
            frame.copy(rows = frame.rows.map { TerminalRow(it.index, it.cells.take(4)) })
        }
        state.reconcile(narrow)

        assertEquals(emptyList(), state.cells.toList())
    }

    @Test
    fun the_first_frame_is_not_a_geometry_change() {
        val clock = Clock()
        val state = primed(clock)
        // No reconcile yet: the very first frame must not be read as "the screen changed shape".
        state.onInput("a".encodeToByteArray(), CursorPos(0, 2))
        assertEquals(listOf(PredictedCell(0, 2, "a")), state.cells.toList())

        state.reconcile(viewport(listOf("$ ")))

        assertEquals(listOf(PredictedCell(0, 2, "a")), state.cells.toList())
    }

    @Test
    fun a_published_frame_is_the_only_thing_a_prediction_is_measured_against() {
        // cellTextAt is the whole of the observation path, and it reads a FRAME — never the engine.
        val frame = viewport(listOf("hello"))
        assertEquals("h", cellTextAt(frame, 0, 0))
        assertEquals("o", cellTextAt(frame, 0, 4))
        assertEquals(" ", cellTextAt(frame, 0, 5))
        assertEquals(null, cellTextAt(frame, 0, 99))
        assertEquals(null, cellTextAt(frame, 9, 0))
    }
}
