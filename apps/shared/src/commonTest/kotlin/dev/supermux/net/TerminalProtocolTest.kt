package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Revision-2 wire fixtures. THE SAME STRINGS ARE ENCODED/DECODED BY THE BROKER:
 * `tests/terminal-protocol.test.ts` holds them verbatim and reads THIS file,
 * failing when a fixture drifts out of one side. A protocol both ends only
 * believe they share is how revision 1 ended up with a client that matched
 * frames by substring.
 */
private object Fixtures {
    // server → client
    const val READY = """{"type":"ready","version":2,"epoch":"c-1","replyOwner":false,"ownerGeneration":0}"""
    const val READY_UNKNOWN_VERSION = """{"type":"ready","version":3,"epoch":"c-1","replyOwner":false,"ownerGeneration":0}"""
    const val RESET_A = """{"type":"reset","epoch":"e-a"}"""
    const val REPLAY_START_A = """{"type":"replay-start","epoch":"e-a"}"""
    const val REPLAY_END_A = """{"type":"replay-end","epoch":"e-a"}"""
    const val RESET_B = """{"type":"reset","epoch":"e-b"}"""
    const val REPLAY_START_B = """{"type":"replay-start","epoch":"e-b"}"""
    const val REPLAY_END_B = """{"type":"replay-end","epoch":"e-b"}"""
    const val OWNER_ON = """{"type":"owner","epoch":"e-a","enabled":true,"ownerGeneration":1}"""
    const val OWNER_STALE_EPOCH = """{"type":"owner","epoch":"e-gone","enabled":true,"ownerGeneration":9}"""
    const val REPLAY_START_STALE_EPOCH = """{"type":"replay-start","epoch":"e-gone"}"""
    const val EXIT_NON_ZERO = """{"type":"exit","known":true,"code":3,"signal":null}"""
    const val EXIT_UNKNOWN = """{"type":"exit","known":false,"code":null,"signal":null}"""
    const val EXIT_SIGNALLED = """{"type":"exit","known":true,"code":null,"signal":9}"""
    const val FAILURE_RECOVERABLE_SAYING_EXIT =
        """{"type":"failure","code":"backend-unavailable","recoverable":true,"message":"zmx helper exited before the attach completed"}"""
    const val FAILURE_FATAL = """{"type":"failure","code":"target-not-found","recoverable":false,"message":"no such terminal"}"""
    const val FAILURE_UNSUPPORTED_REVISION =
        """{"type":"failure","code":"protocol-unsupported","recoverable":false,"message":"terminal protocol \"1\" is not supported; this broker speaks revision 2"}"""
    const val MALFORMED = """{"type":"reset","epoch":"""
    const val UNKNOWN_TYPE = """{"type":"bell","epoch":"e-a"}"""

    // client → server
    const val RESIZE = """{"type":"resize","cols":120,"rows":40}"""
    const val FOCUS = """{"type":"focus","focused":true,"cols":120,"rows":40}"""
    const val BLUR = """{"type":"focus","focused":false}"""
    const val REPLY = """{"type":"reply","epoch":"e-a","ownerGeneration":1,"data":"G1syNDsxUg=="}"""
    const val CLOSE = """{"type":"close"}"""
    const val RESIZE_NOT_A_NUMBER = """{"type":"resize","cols":"120","rows":40}"""
}

/** The bytes of "ok→\n" — the arrow is 3 bytes and the fixture splits it. */
private val UTF8_CHUNK_A = byteArrayOf(0x6f, 0x6b, 0xe2.toByte())
private val UTF8_CHUNK_B = byteArrayOf(0x86.toByte(), 0x92.toByte(), 0x0a)

private fun TerminalDecode.event(): TerminalEvent {
    assertIs<TerminalDecode.Deliver>(this)
    return event
}

class TerminalProtocolTest {

    @Test fun a_fresh_connection_is_ready_reset_replay_output_replay_end_then_live_output() {
        val d = TerminalEventDecoder()
        assertEquals(
            TerminalEvent.Ready(2, "c-1", replyOwner = false, ownerGeneration = 0),
            d.onText(Fixtures.READY).event(),
        )
        assertEquals(TerminalEvent.Reset("e-a"), d.onText(Fixtures.RESET_A).event())
        assertEquals(TerminalEvent.ReplayStart("e-a"), d.onText(Fixtures.REPLAY_START_A).event())
        // Chunked UTF-8: the client NEVER decodes text, it forwards bytes. A
        // multi-byte character split across two frames must arrive split.
        assertEquals(TerminalEvent.Output(UTF8_CHUNK_A), d.onBinary(UTF8_CHUNK_A).event())
        assertEquals(TerminalEvent.Output(UTF8_CHUNK_B), d.onBinary(UTF8_CHUNK_B).event())
        assertEquals(TerminalEvent.ReplayEnd("e-a"), d.onText(Fixtures.REPLAY_END_A).event())
        assertTrue(d.replayClosed)
        assertEquals(TerminalEvent.Output(byteArrayOf(0x24)), d.onBinary(byteArrayOf(0x24)).event())
        assertEquals("ok→\n", (UTF8_CHUNK_A + UTF8_CHUNK_B).decodeToString())
    }

    @Test fun a_replay_may_contain_no_output_at_all() {
        val d = TerminalEventDecoder()
        d.onText(Fixtures.READY)
        d.onText(Fixtures.RESET_A)
        d.onText(Fixtures.REPLAY_START_A)
        assertEquals(TerminalEvent.ReplayEnd("e-a"), d.onText(Fixtures.REPLAY_END_A).event())
        assertTrue(d.replayClosed)
    }

    @Test fun output_before_ready_is_refused() {
        val d = TerminalEventDecoder()
        assertIs<TerminalDecode.Ignore>(d.onBinary(byteArrayOf(1, 2, 3)))
        assertIs<TerminalDecode.Ignore>(d.onText(Fixtures.RESET_A))
        d.onText(Fixtures.READY)
        assertEquals(TerminalEvent.Output(byteArrayOf(1, 2, 3)), d.onBinary(byteArrayOf(1, 2, 3)).event())
    }

    @Test fun a_failure_that_mentions_exiting_is_not_an_exit() {
        val d = TerminalEventDecoder()
        d.onText(Fixtures.READY)
        val event = d.onText(Fixtures.FAILURE_RECOVERABLE_SAYING_EXIT).event()
        assertEquals(
            TerminalEvent.Failure("backend-unavailable", recoverable = true, message = "zmx helper exited before the attach completed"),
            event,
        )
        assertTrue(event !is TerminalEvent.Exit)
        // ...and the non-recoverable one is still a failure, not an exit.
        assertEquals(
            TerminalEvent.Failure("target-not-found", recoverable = false, message = "no such terminal"),
            d.onText(Fixtures.FAILURE_FATAL).event(),
        )
        assertEquals(
            "protocol-unsupported",
            (d.onText(Fixtures.FAILURE_UNSUPPORTED_REVISION).event() as TerminalEvent.Failure).code,
        )
    }

    @Test fun malformed_and_unknown_control_frames_are_dropped_and_the_stream_survives() {
        val d = TerminalEventDecoder()
        d.onText(Fixtures.READY)
        assertIs<TerminalDecode.Ignore>(d.onText(Fixtures.MALFORMED))
        assertIs<TerminalDecode.Ignore>(d.onText(Fixtures.UNKNOWN_TYPE))
        assertIs<TerminalDecode.Ignore>(d.onText(Fixtures.RESIZE_NOT_A_NUMBER))
        assertIs<TerminalDecode.Ignore>(d.onText("not json at all"))
        // The connection is intact: the next real frame still decodes.
        assertEquals(TerminalEvent.Reset("e-a"), d.onText(Fixtures.RESET_A).event())
    }

    @Test fun an_unknown_protocol_version_is_fatal_and_not_retried() {
        val d = TerminalEventDecoder()
        val decoded = d.onText(Fixtures.READY_UNKNOWN_VERSION)
        assertIs<TerminalDecode.Fatal>(decoded)
        assertEquals("protocol-unsupported", decoded.code)
        assertTrue(!d.ready)
    }

    @Test fun a_reset_mid_output_opens_a_new_epoch_and_voids_the_old_one() {
        val d = TerminalEventDecoder()
        d.onText(Fixtures.READY)
        d.onText(Fixtures.RESET_A)
        d.onText(Fixtures.REPLAY_START_A)
        d.onText(Fixtures.REPLAY_END_A)
        d.onBinary(UTF8_CHUNK_A)
        // The backend re-synchronised mid-stream.
        assertEquals(TerminalEvent.Reset("e-b"), d.onText(Fixtures.RESET_B).event())
        assertEquals("e-b", d.epoch)
        assertTrue(!d.replayClosed, "a new epoch re-opens the replay boundary")
        // Frames still addressed to the epoch that was just replaced are
        // history: acting on them would draw the old screen over the new one.
        assertIs<TerminalDecode.Ignore>(d.onText(Fixtures.REPLAY_START_A))
        assertIs<TerminalDecode.Ignore>(d.onText(Fixtures.REPLAY_START_STALE_EPOCH))
        assertEquals(TerminalEvent.ReplayStart("e-b"), d.onText(Fixtures.REPLAY_START_B).event())
        assertEquals(TerminalEvent.ReplayEnd("e-b"), d.onText(Fixtures.REPLAY_END_B).event())
    }

    @Test fun exit_carries_code_signal_and_whether_anybody_reaped_it() {
        fun exitOf(fixture: String): TerminalEvent.Exit {
            val d = TerminalEventDecoder()
            d.onText(Fixtures.READY)
            return d.onText(fixture).event() as TerminalEvent.Exit
        }
        assertEquals(TerminalEvent.Exit(code = 3, signal = null, known = true), exitOf(Fixtures.EXIT_NON_ZERO))
        // Nobody reaped a status: 0 would be a clean exit nobody observed.
        assertEquals(TerminalEvent.Exit(code = null, signal = null, known = false), exitOf(Fixtures.EXIT_UNKNOWN))
        assertEquals(TerminalEvent.Exit(code = null, signal = 9, known = true), exitOf(Fixtures.EXIT_SIGNALLED))
    }

    @Test fun owner_frames_are_accepted_on_the_live_epoch_and_a_rising_generation() {
        val d = TerminalEventDecoder()
        d.onText(Fixtures.READY)
        d.onText(Fixtures.RESET_A)
        assertIs<TerminalDecode.Ignore>(d.onText(Fixtures.OWNER_STALE_EPOCH))
        assertTrue(!d.replyOwner)
        assertEquals(TerminalEvent.Owner("e-a", enabled = true, ownerGeneration = 1), d.onText(Fixtures.OWNER_ON).event())
        assertTrue(d.replyOwner)
        assertEquals(1L, d.ownerGeneration)
        // A generation we already passed is a reordered frame, not a lease.
        assertIs<TerminalDecode.Ignore>(d.onText(Fixtures.OWNER_ON))
        assertTrue(d.replyOwner)
    }

    @Test fun viewer_commands_encode_to_the_bytes_the_broker_decodes() {
        assertEquals(Fixtures.RESIZE, encodeTerminalCommand(TerminalCommand.Resize(120, 40)))
        assertEquals(Fixtures.FOCUS, encodeTerminalCommand(TerminalCommand.Focus(true, 120, 40)))
        assertEquals(Fixtures.BLUR, encodeTerminalCommand(TerminalCommand.Focus(false)))
        assertEquals(Fixtures.CLOSE, encodeTerminalCommand(TerminalCommand.Close))
        assertEquals(
            Fixtures.REPLY,
            encodeTerminalCommand(
                TerminalCommand.Reply("e-a", 1, encodeReplyPayload("\u001b[24;1R".encodeToByteArray())),
            ),
        )
    }
}
