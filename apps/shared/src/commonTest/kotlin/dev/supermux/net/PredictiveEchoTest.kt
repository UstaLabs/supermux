package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-language parity suite: mirrors ALL 23 web tests from
 *   src/web-app/src/lib/predictive-echo/engine.test.ts  (19 tests)
 *   src/web-app/src/lib/predictive-echo/types.test.ts    (4 tests)
 * with the SAME inputs and the SAME expected op sequences.
 *
 * Run on JVM via `./gradlew :shared:jvmTest --tests "*PredictiveEchoTest*"`.
 */
class PredictiveEchoTest {

    // -----------------------------------------------------------------------
    // Helpers — mirrors the TS `mkEngine` / `enc` helpers in the web tests
    // -----------------------------------------------------------------------

    private data class EngineHandle(
        val eng: PredictionEngine,
        val tick: (Long) -> Unit,
    )

    private fun mkEngine(): EngineHandle {
        var t = 1000L
        val eng = PredictionEngine(DEFAULT_CONFIG) { t }
        return EngineHandle(eng) { ms -> t += ms }
    }

    /** Encode a String to UTF-8 bytes, exactly like TS `new TextEncoder().encode(s)`. */
    private fun enc(s: String) = s.encodeToByteArray()

    // -----------------------------------------------------------------------
    // 4 types parity tests — mirrors types.test.ts
    // -----------------------------------------------------------------------

    @Test fun decodeInput_classifies_a_printable_char() {
        assertEquals(CharInput("a"), decodeInput("a"))
    }

    @Test fun decodeInput_classifies_DEL_and_BS_as_backspace() {
        assertEquals(Backspace, decodeInput(""))
        assertEquals(Backspace, decodeInput(""))
    }

    @Test fun decodeInput_classifies_left_right_arrow_escape_sequences() {
        assertEquals(CursorLeft,  decodeInput("[D"))
        assertEquals(CursorRight, decodeInput("[C"))
    }

    @Test fun decodeInput_classifies_Enter_Tab_CtrlC_and_paste_as_opaque() {
        assertEquals(Opaque, decodeInput("\r"))
        assertEquals(Opaque, decodeInput("\t"))
        assertEquals(Opaque, decodeInput(""))
        assertEquals(Opaque, decodeInput("hello"))
    }

    // -----------------------------------------------------------------------
    // 19 engine parity tests — mirrors engine.test.ts
    // -----------------------------------------------------------------------

    // --- Group: gate + epoch ---

    @Test fun gate_does_not_predict_when_latency_is_below_threshold() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(10)
        assertEquals(emptyList(), eng.onInput(CharInput("a"), CursorPos(0, 5)))
    }

    @Test fun gate_holds_first_char_tentative_nothing_drawn_caret_unmoved() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        assertEquals(emptyList(), eng.onInput(CharInput("a"), CursorPos(0, 5)))
    }

    @Test fun epoch_draws_backlog_and_rides_caret_when_first_prediction_confirms() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("a"), CursorPos(0, 5)) // tentative id1 @5, tentative→6, physical=5
        eng.onInput(CharInput("b"), CursorPos(0, 5)) // tentative id2 @6, tentative→7
        assertEquals(
            listOf(
                HideCaret,
                DrawDim(id = 2, row = 0, col = 6, char = "b"), // backlog drawn BEFORE passthrough
                MoveCaret(row = 0, col = 5),
                Passthrough(enc("a")),
                MoveCaret(row = 0, col = 7),
                ShowCaret,
            ),
            eng.onServerData(enc("a")),
        )
    }

    @Test fun epoch_stays_confirmed_across_drain_every_char_after_first_draws() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        assertEquals(emptyList(), eng.onInput(CharInput("a"), CursorPos(0, 5))) // tentative
        eng.onServerData(enc("a")) // confirm epoch + drain
        assertEquals(
            listOf(
                DrawDim(id = 2, row = 0, col = 6, char = "b"),
                MoveCaret(row = 0, col = 7),
            ),
            eng.onInput(CharInput("b"), CursorPos(0, 6)),
        )
    }

    @Test fun epoch_never_draws_when_app_does_not_echo_password_prompt_safety() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        assertEquals(emptyList(), eng.onInput(CharInput("p"), CursorPos(0, 9)))
        assertEquals(emptyList(), eng.onInput(CharInput("w"), CursorPos(0, 9)))
        assertEquals(emptyList(), eng.onInput(CharInput("d"), CursorPos(0, 9)))
    }

    @Test fun gate_stops_predicting_once_maxPending_is_reached() {
        var t2 = 1000L
        val eng = PredictionEngine(
            PredictionConfig(latencyThresholdMs = 40, cooldownMs = 600, maxPending = 2)
        ) { t2 }
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("a"), CursorPos(0, 0)) // tentative id1, pending=1
        eng.onServerData(enc("a"))                   // confirm + drain, pending=0
        assertEquals(2, eng.onInput(CharInput("b"), CursorPos(0, 1)).size) // drawDim+moveCaret
        assertEquals(2, eng.onInput(CharInput("c"), CursorPos(0, 1)).size)
        assertEquals(emptyList(), eng.onInput(CharInput("d"), CursorPos(0, 1))) // maxPending
    }

    // --- Group: caret + reconciliation ---

    @Test fun caret_draws_char_dim_and_advances_caret_once_epoch_confirmed() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("a"), CursorPos(0, 5))
        eng.onServerData(enc("a")) // warm
        assertEquals(
            listOf(
                DrawDim(id = 2, row = 0, col = 6, char = "b"),
                MoveCaret(row = 0, col = 7),
            ),
            eng.onInput(CharInput("b"), CursorPos(0, 6)),
        )
    }

    @Test fun reconcile_confirms_drawn_prediction_in_place_via_passthrough_caret_rides() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("a"), CursorPos(0, 5))
        eng.onServerData(enc("a")) // warm
        eng.onInput(CharInput("b"), CursorPos(0, 6)) // drawn id2 @6, physical=6, tentative=7
        assertEquals(
            listOf(
                HideCaret,
                MoveCaret(row = 0, col = 6),
                Passthrough(enc("b")),
                ShowCaret,
            ),
            eng.onServerData(enc("b")),
        )
    }

    @Test fun reconcile_partial_echo_confirms_prefix_and_replaces_caret_after_dim_tail() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("x"), CursorPos(0, 0))
        eng.onServerData(enc("x")) // warm
        eng.onInput(CharInput("a"), CursorPos(0, 1)) // drawn id2 @1, physical=1, tentative→2
        eng.onInput(CharInput("b"), CursorPos(0, 1)) // drawn id3 @2, tentative→3
        assertEquals(
            listOf(
                HideCaret,
                MoveCaret(row = 0, col = 1),
                Passthrough(enc("a")),
                MoveCaret(row = 0, col = 3), // caret after the still-dim 'b'
                ShowCaret,
            ),
            eng.onServerData(enc("a")),
        )
    }

    @Test fun reconcile_resyncs_when_surviving_prediction_shares_chunk_with_escape() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("x"), CursorPos(0, 0))
        eng.onServerData(enc("x")) // warm
        eng.onInput(CharInput("a"), CursorPos(0, 1)) // drawn id2 @1
        eng.onInput(CharInput("b"), CursorPos(0, 1)) // drawn id3 @2
        // Echo "a" + clear-to-EOL: 'a' confirms, 'b' survives alongside an escape → resync
        assertEquals(
            listOf(
                HideCaret,
                RestoreCell(id = 3, row = 0, col = 2), // erase the surviving dim 'b'
                MoveCaret(row = 0, col = 1),
                Passthrough(enc("a[K")),
                ShowCaret,
            ),
            eng.onServerData(enc("a[K")),
        )
    }

    @Test fun passthrough_when_no_predictions() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        assertEquals(
            listOf(Passthrough(enc("hello"))),
            eng.onServerData(enc("hello")),
        )
    }

    @Test fun divergence_drawn_erases_every_drawn_prediction_replays_resets_cooldown() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("x"), CursorPos(0, 0))
        eng.onServerData(enc("x")) // warm
        eng.onInput(CharInput("a"), CursorPos(0, 1)) // drawn id2 @1
        eng.onInput(CharInput("b"), CursorPos(0, 1)) // drawn id3 @2
        // 'a' confirms, 'Y' diverges from 'b'
        assertEquals(
            listOf(
                HideCaret,
                RestoreCell(id = 3, row = 0, col = 2), // reverse order (stacked-cell safe)
                RestoreCell(id = 2, row = 0, col = 1),
                MoveCaret(row = 0, col = 1),
                Passthrough(enc("aY")),
                ShowCaret,
            ),
            eng.onServerData(enc("aY")),
        )
        assertEquals(emptyList(), eng.onInput(CharInput("c"), CursorPos(0, 2))) // cooldown
    }

    @Test fun divergence_tentative_only_no_restore_ops_still_replays_and_resets() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("a"), CursorPos(0, 5)) // tentative id1
        eng.onInput(CharInput("b"), CursorPos(0, 5)) // tentative id2
        assertEquals(
            listOf(
                HideCaret,
                MoveCaret(row = 0, col = 5),
                Passthrough(enc("X")),
                ShowCaret,
            ),
            eng.onServerData(enc("X")),
        )
    }

    @Test fun physical_persists_across_chunks_two_partial_echoes_land_caret_correctly() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("x"), CursorPos(0, 0))
        eng.onServerData(enc("x")) // warm
        eng.onInput(CharInput("a"), CursorPos(0, 1)) // drawn id2 @1, physical=1, tentative→2
        eng.onInput(CharInput("b"), CursorPos(0, 1)) // drawn id3 @2, tentative→3
        assertEquals(
            listOf(
                HideCaret,
                MoveCaret(row = 0, col = 1),
                Passthrough(enc("a")),
                MoveCaret(row = 0, col = 3),
                ShowCaret,
            ),
            eng.onServerData(enc("a")),
        )
        // Second partial echo: origPhysical must be the ADVANCED physical (col 2), not stale col 1.
        assertEquals(
            listOf(
                HideCaret,
                MoveCaret(row = 0, col = 2),
                Passthrough(enc("b")),
                ShowCaret,
            ),
            eng.onServerData(enc("b")),
        )
    }

    @Test fun resync_on_destructive_backspace_echo_with_prediction_pending() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("s"), CursorPos(0, 2))
        eng.onServerData(enc("s")) // warm at col 2, drain
        eng.onInput(Backspace, CursorPos(0, 3)) // predict ' '@2 (id2), physical=3, tentative→2
        eng.onInput(CharInput("t"), CursorPos(0, 3)) // predict 't'@2 (id3), tentative→3
        // Destructive backspace echo: the ' ' matches, 't' survives → resync
        assertEquals(
            listOf(
                HideCaret,
                RestoreCell(id = 3, row = 0, col = 2), // erase the surviving dim 't'
                MoveCaret(row = 0, col = 3),            // origPhysical, BEFORE any drift
                Passthrough(enc(" ")),
                ShowCaret,
            ),
            eng.onServerData(enc(" ")),
        )
        // State reset → the retyped char's echo lands at the real caret, not a drifted column.
        assertEquals(
            listOf(Passthrough(enc("t"))),
            eng.onServerData(enc("t")),
        )
    }

    // --- Group: backspace / boundary + opaque + reset ---

    @Test fun backspace_predicts_space_rides_caret_back_stops_at_line_start_column() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("l"), CursorPos(0, 2))
        eng.onServerData(enc("l")) // warm at col 2 (after "$ ")
        eng.onInput(CharInput("s"), CursorPos(0, 3)) // drawn id2 @3, tentative→4
        assertEquals(
            listOf(
                DrawDim(id = 3, row = 0, col = 3, char = " "),
                MoveCaret(row = 0, col = 3),
            ),
            eng.onInput(Backspace, CursorPos(0, 3)),
        )
        assertEquals(
            listOf(
                DrawDim(id = 4, row = 0, col = 2, char = " "),
                MoveCaret(row = 0, col = 2),
            ),
            eng.onInput(Backspace, CursorPos(0, 3)),
        )
        assertEquals(emptyList(), eng.onInput(Backspace, CursorPos(0, 3))) // at start col 2 → refused
    }

    @Test fun opaque_input_erases_drawn_predictions_snaps_caret_to_physical_resets() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("x"), CursorPos(0, 0))
        eng.onServerData(enc("x")) // warm
        eng.onInput(CharInput("a"), CursorPos(0, 1)) // drawn id2 @1, physical=1
        assertEquals(
            listOf(
                HideCaret,
                RestoreCell(id = 2, row = 0, col = 1),
                MoveCaret(row = 0, col = 1),
                ShowCaret,
            ),
            eng.onInput(Opaque, CursorPos(0, 1)),
        )
    }

    @Test fun reset_erases_drawn_predictions_and_clears_state() {
        val (eng, _) = mkEngine()
        eng.setLatencyEstimate(120)
        eng.onInput(CharInput("a"), CursorPos(0, 0))
        eng.onServerData(enc("a")) // warm
        eng.onInput(CharInput("b"), CursorPos(0, 1)) // drawn id2 @1, physical=1
        assertEquals(
            listOf(
                HideCaret,
                RestoreCell(id = 2, row = 0, col = 1),
                MoveCaret(row = 0, col = 1),
                ShowCaret,
            ),
            eng.reset(),
        )
        assertEquals(emptyList(), eng.reset())
    }

    // --- Group: latency measurement ---

    @Test fun latency_learns_from_confirm_timing_EWMA_so_it_keeps_engaging() {
        val (eng, tick) = mkEngine()
        eng.primeForTest()
        eng.onInput(CharInput("a"), CursorPos(0, 0)) // tentative
        tick(150)
        eng.onServerData(enc("a")) // confirm + sample latency
        val ops = eng.onInput(CharInput("b"), CursorPos(0, 1))
        assertTrue(ops.isNotEmpty() && ops[0].let { it is DrawDim && it.char == "b" })
    }
}
