package dev.supermux.ui.chat

import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A mic whose loudness the test sets by hand; every snapshot is numbered so a pass is traceable. */
private class MeteredMic(
    override val liveTranscript: LiveTranscript? = null,
) : MicCapture {
    val loud = MutableStateFlow(0f)
    var snapshots = 0
    override val level: StateFlow<Float> get() = loud
    override suspend fun snapshot(): CapturedAudio? {
        snapshots++
        return CapturedAudio(byteArrayOf(snapshots.toByte()), "live.wav", "audio/wav")
    }
    override fun start(): Boolean = true
    override fun stop(): CapturedAudio? = CapturedAudio(byteArrayOf(99), "final.wav", "audio/wav")
    override fun cancel() = Unit
    override suspend fun requestPermission(): Boolean = true
    override val available: Boolean get() = true
}

private class FakeLive : LiveTranscript {
    var started = 0
    override fun start(glossary: List<String>): Boolean { started++; return true }
    override suspend fun stop(): String = "on device"
    override fun cancel() = Unit
    override val partial: StateFlow<String> = MutableStateFlow("")
}

/**
 * The live partial passes: a pause (or a long run of speech) sends the audio so far, one pass in
 * flight at a time, and the answer fills [DictationController.liveTranscript]. Virtual time — the
 * 50 ms sampling loop runs on the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationLivePassTest {

    private fun TestScope.controller(mic: MicCapture) =
        DictationController(mic, TestScope(StandardTestDispatcher(testScheduler)))

    @Test fun a_pause_after_speech_sends_the_audio_so_far_and_shows_the_answer() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val seen = mutableListOf<Byte>()
        ctrl.transcribePartial = { r -> seen += r.bytes[0]; LivePass("hello there") }
        ctrl.startMic()
        assertEquals("", ctrl.liveTranscript)

        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(1_000)
        assertEquals(0, mic.snapshots, "no pass while still talking")

        mic.loud.value = 0f
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()

        assertEquals(1, mic.snapshots)
        assertEquals("hello there", ctrl.liveTranscript)

        // Silence with no new speech sends nothing more.
        testScheduler.advanceTimeBy(3_000)
        assertEquals(1, mic.snapshots)
        ctrl.cancelMic()
    }

    @Test fun silence_alone_never_sends_a_pass() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.transcribePartial = { _ -> LivePass("x") }
        ctrl.startMic()
        testScheduler.advanceTimeBy(10_000)
        assertEquals(0, mic.snapshots)
        ctrl.cancelMic()
    }

    @Test fun talking_without_a_pause_still_sends_a_pass_every_six_seconds() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.transcribePartial = { _ -> LivePass("so far") }
        ctrl.startMic()
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(6_100)
        testScheduler.runCurrent()
        assertEquals(1, mic.snapshots)
        testScheduler.advanceTimeBy(6_000)
        testScheduler.runCurrent()
        assertEquals(2, mic.snapshots)
        ctrl.cancelMic()
    }

    @Test fun one_pass_in_flight_and_exactly_one_more_after_it() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val gates = mutableListOf<CompletableDeferred<LivePass>>()
        ctrl.transcribePartial = { _ -> CompletableDeferred<LivePass>().also { gates += it }.await() }
        ctrl.startMic()

        // Three pauses while the first pass is still out.
        repeat(3) {
            mic.loud.value = 0.3f
            testScheduler.advanceTimeBy(300)
            mic.loud.value = 0f
            testScheduler.advanceTimeBy(800)
        }
        testScheduler.runCurrent()
        assertEquals(1, gates.size, "only one pass in flight")

        gates[0].complete(LivePass("first"))
        testScheduler.runCurrent()
        assertEquals("first", ctrl.liveTranscript)
        assertEquals(2, gates.size, "the queued pauses collapse into one follow-up pass")

        gates[1].complete(LivePass("first and second"))
        testScheduler.runCurrent()
        assertEquals("first and second", ctrl.liveTranscript)
        assertEquals(2, gates.size)
        ctrl.cancelMic()
    }

    @Test fun a_failed_or_empty_pass_keeps_the_text_already_shown() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val answers = ArrayDeque(listOf<LivePass?>(LivePass("kept"), null, LivePass("  ")))
        ctrl.transcribePartial = { _ ->
            val next = answers.removeFirst()
            next ?: throw IllegalStateException("network")
        }
        ctrl.startMic()
        repeat(3) {
            mic.loud.value = 0.3f
            testScheduler.advanceTimeBy(300)
            mic.loud.value = 0f
            testScheduler.advanceTimeBy(800)
            testScheduler.runCurrent()
        }
        assertEquals(3, mic.snapshots)
        assertEquals("kept", ctrl.liveTranscript)
        ctrl.cancelMic()
    }

    @Test fun a_skipped_pass_stops_live_passes_for_the_recording() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.transcribePartial = { _ -> LivePass("", skipped = true) }
        ctrl.startMic()
        repeat(3) {
            mic.loud.value = 0.3f
            testScheduler.advanceTimeBy(300)
            mic.loud.value = 0f
            testScheduler.advanceTimeBy(800)
            testScheduler.runCurrent()
        }
        assertEquals(1, mic.snapshots)
        ctrl.cancelMic()
    }

    @Test fun no_live_passes_past_the_cap() = runTest {
        val mic = MeteredMic()
        val ctrl = DictationController(
            mic, TestScope(StandardTestDispatcher(testScheduler)),
            timing = LivePassTiming(capMs = 2_000),
        )
        ctrl.transcribePartial = { _ -> LivePass("x") }
        ctrl.startMic()
        testScheduler.advanceTimeBy(2_500)
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals(0, mic.snapshots)
        assertTrue(ctrl.recording)
        ctrl.cancelMic()
    }

    /** Talk → pause (a pass goes out) → talk again → Stop: the last pass does not cover the new
     *  words, so the whole recording is transcribed and that final text wins. */
    @Test fun stop_after_new_speech_transcribes_again_and_the_final_text_wins() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val gate = CompletableDeferred<LivePass>()
        ctrl.transcribePartial = { _ -> gate.await() }
        var finals = 0
        ctrl.transcribeAudio = { bytes, _, _ -> finals++; if (bytes[0] == 99.toByte()) "final text" else null }
        var appended: String? = null
        ctrl.onAppend = { appended = it }
        ctrl.startMic()
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f
        testScheduler.advanceTimeBy(800)
        mic.loud.value = 0.3f                 // more words after the pass's snapshot
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()

        ctrl.stopMic()
        testScheduler.advanceUntilIdle()
        gate.complete(LivePass("late partial"))
        testScheduler.advanceUntilIdle()

        assertEquals(1, finals)
        assertEquals("final text", appended)
        assertNull(ctrl.liveTranscript)
        assertFalse(ctrl.transcribing)
    }

    /** Talk → pause (the pass lands) → Stop with nothing said since: the pass IS the transcript. */
    @Test fun stop_with_nothing_said_since_the_last_pass_reuses_it() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.transcribePartial = { _ -> LivePass("all of it") }
        var finals = 0
        ctrl.transcribeAudio = { _, _, _ -> finals++; "transcribed again" }
        var appended: String? = null
        ctrl.onAppend = { appended = it }
        ctrl.startMic()
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals("all of it", ctrl.liveTranscript)

        ctrl.stopMic()
        testScheduler.advanceUntilIdle()

        assertEquals(0, finals, "no second transcription of audio already transcribed")
        assertEquals("all of it", appended)
        assertFalse(ctrl.transcribing)
    }

    /** Stop while the covering pass is still out: wait for it instead of starting another. */
    @Test fun stop_while_the_covering_pass_is_out_waits_for_it() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val gate = CompletableDeferred<LivePass>()
        ctrl.transcribePartial = { _ -> gate.await() }
        var finals = 0
        ctrl.transcribeAudio = { _, _, _ -> finals++; "transcribed again" }
        var appended: String? = null
        ctrl.onAppend = { appended = it }
        ctrl.startMic()
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()

        ctrl.stopMic()
        testScheduler.advanceUntilIdle()
        assertTrue(ctrl.transcribing)
        gate.complete(LivePass("from the pass"))
        testScheduler.advanceUntilIdle()

        assertEquals(0, finals)
        assertEquals("from the pass", appended)
        assertFalse(ctrl.transcribing)
    }

    /** The final pass fails, but a live pass had text: keep it rather than raising an error. */
    @Test fun a_failed_final_pass_keeps_the_live_text() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.transcribePartial = { _ -> LivePass("said so far") }
        ctrl.transcribeAudio = { _, _, _ -> null }
        var appended: String? = null
        ctrl.onAppend = { appended = it }
        ctrl.startMic()
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f
        testScheduler.advanceTimeBy(800)
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()

        ctrl.stopMic()
        testScheduler.advanceUntilIdle()

        assertEquals("said so far", appended)
        assertNull(ctrl.failedAudio)
    }

    @Test fun discard_cancels_and_tells_the_composer() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        var discarded = 0
        ctrl.onDiscard = { discarded++ }
        ctrl.startMic()
        ctrl.discardMic()
        assertEquals(1, discarded)
        assertFalse(ctrl.recording)
    }

    /**
     * After a pass taken in a pause, later passes send only the audio after the middle of that
     * pause, with the text so far as context, and their answer is appended to it. Stop does the
     * same for the words said after the last pass.
     */
    @Test fun passes_after_a_pause_are_incremental_and_continue_the_text() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val requests = mutableListOf<LivePassRequest>()
        val answers = ArrayDeque(listOf(
            LivePass("Hello there.", durationMs = 2_000),              // whole clip: fromMs 0
            LivePass("How are you?", fromMs = 1_600, durationMs = 4_000), // after the cut
            LivePass("Fine.", fromMs = 3_600, durationMs = 5_000),        // Stop's tail
        ))
        ctrl.transcribePartial = { r -> requests += r; answers.removeFirst() }
        var finals = 0
        ctrl.transcribeAudio = { _, _, _ -> finals++; "whole thing again" }
        var appended: String? = null
        ctrl.onAppend = { appended = it }
        ctrl.startMic()

        mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f; testScheduler.advanceTimeBy(800); testScheduler.runCurrent()
        assertEquals(0L, requests[0].fromMs)
        assertEquals("Hello there.", ctrl.liveTranscript)

        mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f; testScheduler.advanceTimeBy(800); testScheduler.runCurrent()
        // Cut in the middle of the ~700 ms pause that ended the first snapshot.
        assertEquals(2_000L - 350, requests[1].fromMs)
        assertEquals("Hello there.", requests[1].previousText)
        assertEquals("Hello there. How are you?", ctrl.liveTranscript)

        mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300); testScheduler.runCurrent()
        ctrl.stopMic()
        testScheduler.advanceUntilIdle()

        assertEquals(4_000L - 350, requests[2].fromMs)
        assertEquals("Hello there. How are you?", requests[2].previousText)
        assertEquals(0, finals, "Stop sent only the tail, not the whole recording")
        assertEquals("Hello there. How are you? Fine.", appended)
    }

    /** A pass forced by 6 s of continuous talking is mid-speech: it never moves the cut. */
    @Test fun a_pass_mid_speech_does_not_commit() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val requests = mutableListOf<LivePassRequest>()
        ctrl.transcribePartial = { r -> requests += r; LivePass("still talking", durationMs = 6_000) }
        ctrl.startMic()
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(12_100)
        testScheduler.runCurrent()
        assertEquals(2, requests.size)
        assertEquals(0L, requests[1].fromMs)
        ctrl.cancelMic()
    }

    /** An engine that ignores the cut answers fromMs 0: its text is the whole recording's. */
    @Test fun an_engine_without_cuts_still_gives_cumulative_text() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val answers = ArrayDeque(listOf(LivePass("one", durationMs = 2_000), LivePass("one two", durationMs = 4_000)))
        ctrl.transcribePartial = { _ -> answers.removeFirst() }
        ctrl.startMic()
        repeat(2) {
            mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300)
            mic.loud.value = 0f; testScheduler.advanceTimeBy(800); testScheduler.runCurrent()
        }
        assertEquals("one two", ctrl.liveTranscript)
        ctrl.cancelMic()
    }

    /**
     * Someone who talks in a flow never pauses 700 ms, but does leave short breaks between
     * phrases. Once the audio clock is known (from the first answer), a pass cuts at the latest
     * break, fixes the text up to it, and a preview pass shows the words after it at once.
     */
    @Test fun a_flowing_talker_gets_text_fixed_at_short_breaks() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val requests = mutableListOf<LivePassRequest>()
        ctrl.transcribePartial = { r ->
            requests += r
            when (requests.size) {
                1 -> LivePass("a b c", durationMs = 6_000)                          // whole clip
                2 -> LivePass("a b c d", durationMs = 12_000, toMs = r.toMs)       // up to the break
                else -> LivePass("e f", fromMs = r.fromMs, durationMs = 12_000)    // after it
            }
        }
        ctrl.startMic()
        fun talk(ms: Long) { mic.loud.value = 0.3f; testScheduler.advanceTimeBy(ms) }
        fun brk(ms: Long) { mic.loud.value = 0f; testScheduler.advanceTimeBy(ms) }

        talk(2_000); brk(300); talk(3_750)          // forced pass at 6 s — no clock yet, no cut
        testScheduler.runCurrent()
        assertEquals(1, requests.size)
        assertEquals(0L, requests[0].toMs)

        talk(1_000); brk(300); talk(4_750)          // a break at ~7.2 s, then the 12 s pass
        testScheduler.runCurrent()
        assertEquals(3, requests.size)
        assertEquals(7_200L, requests[1].toMs, "cut in the middle of the latest break")
        assertEquals(7_200L, requests[2].fromMs, "the preview continues from the cut")
        assertEquals("a b c d", requests[2].previousText)
        assertEquals("a b c d e f", ctrl.liveTranscript)
        ctrl.cancelMic()
    }

    @Test fun no_partial_seam_means_no_live_passes() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.startMic()
        assertNull(ctrl.liveTranscript)
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f
        testScheduler.advanceTimeBy(800)
        assertEquals(0, mic.snapshots)
        ctrl.cancelMic()
    }

    @Test fun the_broker_saying_no_live_passes_means_none_are_sent() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.livePartialsAvailable = false
        ctrl.transcribePartial = { _ -> LivePass("x") }
        ctrl.startMic()
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f
        testScheduler.advanceTimeBy(800)
        assertEquals(0, mic.snapshots)
        ctrl.cancelMic()
    }

    @Test fun broker_mode_records_even_when_the_device_has_a_recogniser() = runTest {
        val live = FakeLive()
        val mic = MeteredMic(liveTranscript = live)
        val ctrl = controller(mic)
        ctrl.codexAvailable = true
        ctrl.startMic()
        assertTrue(ctrl.recording)
        assertEquals(0, live.started)
        ctrl.cancelMic()
    }

    @Test fun device_mode_uses_the_recogniser() = runTest {
        val live = FakeLive()
        val mic = MeteredMic(liveTranscript = live)
        val ctrl = controller(mic)
        ctrl.preferredMode = DictationMode.Device
        ctrl.startMic()
        assertTrue(ctrl.listening)
        assertEquals(1, live.started)
        ctrl.cancelMic()
    }
}

class JoinContinuationTest {
    @Test fun plain_continuation_is_appended() {
        assertEquals("Hello there. How are you?", joinContinuation("Hello there.", "How are you?"))
    }

    @Test fun a_repeated_tail_is_dropped() {
        assertEquals("I went to the shop and bought milk", joinContinuation("I went to the shop", "the shop and bought milk"))
        assertEquals("Okay, so the plan is set", joinContinuation("Okay, so the plan", "The plan is set"))
    }

    @Test fun an_echo_of_everything_is_a_full_transcript() {
        assertEquals("One two. Three four.", joinContinuation("One two.", "one two. Three four."))
    }

    @Test fun a_single_shared_word_is_not_treated_as_a_repeat() {
        assertEquals("we saw it it was big", joinContinuation("we saw it", "it was big"))
    }

    @Test fun empty_sides() {
        assertEquals("x", joinContinuation("", "x"))
        assertEquals("x", joinContinuation("x", ""))
    }
}

class ResolveDictationModeTest {
    @Test fun a_host_without_a_recogniser_always_uses_the_broker() {
        assertEquals(DictationMode.Broker, resolveDictationMode(DictationMode.Device, false, hasDeviceRecognizer = false))
    }

    @Test fun an_explicit_choice_wins() {
        assertEquals(DictationMode.Device, resolveDictationMode(DictationMode.Device, true, hasDeviceRecognizer = true))
        assertEquals(DictationMode.Broker, resolveDictationMode(DictationMode.Broker, false, hasDeviceRecognizer = true))
    }

    @Test fun the_default_follows_the_brokers_chatgpt_login() {
        assertEquals(DictationMode.Broker, resolveDictationMode(null, true, hasDeviceRecognizer = true))
        assertEquals(DictationMode.Device, resolveDictationMode(null, false, hasDeviceRecognizer = true))
        assertEquals(DictationMode.Broker, resolveDictationMode(null, null, hasDeviceRecognizer = true))
    }

    @Test fun the_wire_values_round_trip() {
        DictationMode.entries.forEach { assertEquals(it, DictationMode.parse(it.wire)) }
        assertNull(DictationMode.parse("bogus"))
        assertNull(DictationMode.parse(null))
    }
}
