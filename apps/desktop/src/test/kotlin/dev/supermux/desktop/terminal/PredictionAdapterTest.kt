package dev.supermux.desktop.terminal

import dev.supermux.net.CursorPos
import dev.supermux.net.DrawDim
import dev.supermux.net.Passthrough
import dev.supermux.net.RestoreCell
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the desktop [PredictionAdapter] against a REAL headless JediTerm model + [MuxTtyConnector]
 * (see [TermTestHarness]): every op is rendered by injecting ANSI escapes into the connector's FIFO,
 * which a real [com.jediterm.terminal.emulator.JediEmulator] drains and parses into the buffer — the
 * same path production uses. These tests own the adapter's op→escape translation and the JediTerm
 * coordinate mapping; the shared 26 engine parity tests own the engine logic (not re-tested here).
 *
 * Coordinate mapping under test (pinned empirically by [JediTermSmokeTest], NOT re-derived):
 *  - engine [CursorPos] is 0-based in both axes (matches the Android adapter's cursor()/cup() math);
 *  - JediTerm `terminal.cursorX/cursorY` are 1-based → cursor() subtracts 1;
 *  - `buffer.getCharAt(x, y)` is 0-based both axes → readCell reads getCharAt(col, row);
 *  - CUP (`ESC[r;cH`) is 1-based → cup(row, col) emits row+1;col+1.
 */
class PredictionAdapterTest {

    private val h = TermTestHarness()
    private val adapter get() = PredictionAdapter(h.terminal, h.buffer, h.connector)

    @AfterTest fun tearDown() = h.close()

    @Test
    fun draw_dim_lands_the_char_at_the_mapped_buffer_cell() {
        val a = adapter
        // Prime the row so the target cell holds a real char (proves it's overwritten, not just filled).
        h.connector.injectDisplayBytes("abc".toByteArray())
        h.awaitChar(2, 0, 'c')

        // DrawDim at engine coords (row=0, col=2) → CUP ESC[1;3H + dim 'X'. getCharAt(2,0) is that cell.
        a.render(listOf(DrawDim(id = 1, row = 0, col = 2, char = "X")))

        assertEquals('X', h.awaitChar(2, 0, 'X'))
    }

    @Test
    fun restore_cell_puts_the_snapshotted_original_back() {
        val a = adapter
        h.connector.injectDisplayBytes("abc".toByteArray())
        h.awaitChar(2, 0, 'c')

        // DrawDim snapshots the pre-prediction 'c' BEFORE overwriting with 'X'.
        a.render(listOf(DrawDim(id = 7, row = 0, col = 2, char = "X")))
        h.awaitChar(2, 0, 'X')

        // RestoreCell replays the snapshot ('c') at the same cell.
        a.render(listOf(RestoreCell(id = 7, row = 0, col = 2)))
        assertEquals('c', h.awaitChar(2, 0, 'c'))
    }

    @Test
    fun restore_cell_without_a_snapshot_paints_a_space() {
        val a = adapter
        h.connector.injectDisplayBytes("zzz".toByteArray())
        h.awaitChar(2, 0, 'z')

        // No prior DrawDim for id=99 → the fallback is a space (mirrors the engine's rollback intent).
        a.render(listOf(RestoreCell(id = 99, row = 0, col = 2)))
        assertEquals(' ', h.awaitChar(2, 0, ' '))
    }

    @Test
    fun passthrough_bytes_reach_the_buffer_verbatim() {
        val a = adapter
        a.render(listOf(Passthrough("hi".toByteArray())))
        h.awaitChar(1, 0, 'i')
        assertEquals("hi", h.textAt(0, 0, 2))
    }

    @Test
    fun cursor_maps_jediterm_1_based_to_0_based_cursorpos() {
        val a = adapter
        // Fresh terminal: JediTerm reports (1,1) → 0-based (0,0).
        assertEquals(CursorPos(row = 0, col = 0), a.cursor())

        // Type-echo round trip: after "ab" the caret is at JediTerm (3,1) → 0-based (row=0, col=2).
        // This is exactly the serverCursor the pipeline hands PredictionEngine.onInput.
        h.connector.injectDisplayBytes("ab".toByteArray())
        h.awaitChar(1, 0, 'b')
        assertEquals(CursorPos(row = 0, col = 2), a.cursor())
    }

    @Test
    fun available_is_true_public_api_needs_no_reflection_probe() {
        assertEquals(true, adapter.available)
    }
}
