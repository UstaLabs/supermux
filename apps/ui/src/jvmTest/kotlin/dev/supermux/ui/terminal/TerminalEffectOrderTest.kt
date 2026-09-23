package dev.supermux.ui.terminal

import dev.supermux.net.CursorPos
import dev.supermux.terminal.TerminalEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ordering `GhosttyTerminalViewFactory` cannot be driven into getting wrong.
 *
 * WHY THIS TEST EXISTS SEPARATELY. `terminalEffects` puts the keystroke on the prediction lane
 * BEFORE handing the bytes to the client, and the comment above it explains at length why. Nothing
 * in the end-to-end suite fails if the two lines are swapped: the fake socket cannot deliver an
 * echo inside the window between them, because the window is two statements on one coroutine, and
 * a test can only push bytes in from the outside. Swapping them ships a terminal that predicts
 * nothing on any broker fast enough to echo before the lane drains — i.e. every local one — and
 * every other test stays green.
 *
 * So the order is asserted directly, on the handler, with both sides writing into one log. That is
 * the whole point of the handler being a function rather than a lambda inside a composable.
 */
class TerminalEffectOrderTest {

    private val log = mutableListOf<String>()

    private fun handler(caret: CursorPos? = CursorPos(row = 4, col = 9), atMs: Long = 77L) =
        terminalEffects(
            predict = { signal ->
                log += when (signal) {
                    is PredictionSignal.Typed -> "predict:${signal.bytes.decodeToString()}@${signal.caret.row},${signal.caret.col}:${signal.atMs}"
                    is PredictionSignal.Served -> "served"
                    PredictionSignal.Forget -> "forget"
                }
            },
            sendInput = { bytes -> log += "input:${bytes.decodeToString()}" },
            sendReply = { bytes -> log += "reply:${bytes.decodeToString()}" },
            now = { atMs },
            caret = { caret },
        )

    @Test
    fun a_keystroke_reaches_the_prediction_lane_before_it_reaches_the_client() {
        handler()(TerminalEffect.Input("a".encodeToByteArray()))

        assertEquals(listOf("predict:a@4,9:77", "input:a"), log)
    }

    @Test
    fun the_order_holds_for_every_keystroke_in_a_burst_not_just_the_first() {
        // A burst is where a "predict them all, then send them all" refactor would look correct
        // and still be wrong: the second keystroke's echo can land while the first is in flight.
        val handle = handler()
        for (ch in "abc") handle(TerminalEffect.Input(ch.toString().encodeToByteArray()))

        assertEquals(
            listOf("predict:a@4,9:77", "input:a", "predict:b@4,9:77", "input:b", "predict:c@4,9:77", "input:c"),
            log,
        )
    }

    @Test
    fun the_caret_and_the_clock_are_read_at_the_keystroke() {
        // Both are captured INTO the signal here, not left for the lane's consumer to read later:
        // by the time the lane drains, the echo may have moved the caret and the prediction would
        // be placed one cell to the right of where the user typed.
        var caret = CursorPos(row = 0, col = 0)
        var reads = 0
        val handle = terminalEffects(
            predict = { signal -> log += "predict@${(signal as PredictionSignal.Typed).caret.col}" },
            sendInput = { caret = CursorPos(row = 0, col = caret.col + 1) }, // the echo, arriving instantly
            sendReply = { },
            now = { reads++; 0L },
            caret = { caret },
        )

        handle(TerminalEffect.Input("x".encodeToByteArray()))
        handle(TerminalEffect.Input("y".encodeToByteArray()))

        assertEquals(listOf("predict@0", "predict@1"), log)
        assertEquals(2, reads, "the clock is sampled once per keystroke, at the keystroke")
    }

    @Test
    fun a_caret_from_a_session_that_has_published_no_frame_falls_back_to_the_origin() {
        handler(caret = null)(TerminalEffect.Input("z".encodeToByteArray()))

        assertEquals(listOf("predict:z@0,0:77", "input:z"), log)
    }

    @Test
    fun an_emulator_reply_takes_the_reply_route_and_is_never_predicted() {
        // A DA/DSR answer is the emulator's, not the user's. Predicting it would paint bytes the
        // user never typed, and sending it as input would let a non-owner viewer answer for the
        // screen — the broker applies opposite rules to the two routes.
        handler()(TerminalEffect.Response("\u001b[0n".encodeToByteArray()))

        assertEquals(listOf("reply:\u001b[0n"), log)
        assertTrue(log.none { it.startsWith("predict") })
    }
}
