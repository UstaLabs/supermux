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

    /** Talk → pause (a pass lands) → talk again → Stop: one more merge pass carries the new
     *  words — the text so far plus only the audio since — and its text is the final one. */
    @Test fun stop_after_new_speech_merges_the_new_words_once_more() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val requests = mutableListOf<LivePassRequest>()
        val answers = ArrayDeque(listOf(
            LivePass("first", full = true, durationMs = 1_000),
            LivePass("first second", full = true, durationMs = 1_500),
        ))
        ctrl.transcribePartial = { r -> requests += r; answers.removeFirst() }
        var finals = 0
        ctrl.transcribeAudio = { _, _, _ -> finals++; "whole thing" }
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

        assertEquals(0, finals, "the whole recording is not transcribed again")
        assertEquals(600L, requests[1].fromMs, "from the last pass's end, less the overlap")
        assertEquals("first", requests[1].previousText)
        assertEquals("first second", appended)
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
        assertNull(appended, "the field already holds it — nothing to write")
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
        assertNull(appended)
        assertFalse(ctrl.transcribing)
    }

    /** The final pass fails, but a live pass had text: keep it rather than raising an error. */
    @Test fun a_failed_final_pass_keeps_the_live_text() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        var calls = 0
        ctrl.transcribePartial = { _ ->
            if (++calls == 1) LivePass("said so far", full = true, durationMs = 1_000) else error("network")
        }
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

    /** Each pass sends the text so far and the audio from the last pass's end (less a short
     *  overlap); its answer is the complete text and replaces what was shown. */
    @Test fun passes_send_the_text_so_far_and_the_answer_replaces_it() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val requests = mutableListOf<LivePassRequest>()
        val answers = ArrayDeque(listOf(
            LivePass("Meet on Monday. Bring snacks.", full = true, durationMs = 2_000),
            LivePass("Meet on Tuesday.", full = true, durationMs = 4_000),   // "Tuesday, and drop the last sentence"
        ))
        ctrl.transcribePartial = { r -> requests += r; answers.removeFirst() }
        ctrl.startMic()
        repeat(2) {
            mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300)
            mic.loud.value = 0f; testScheduler.advanceTimeBy(800); testScheduler.runCurrent()
            if (it == 0) assertEquals("Meet on Monday. Bring snacks.", ctrl.liveTranscript)
        }
        assertEquals(0L, requests[0].fromMs)
        assertEquals(1_600L, requests[1].fromMs)
        assertEquals("Meet on Monday. Bring snacks.", requests[1].previousText)
        assertEquals("Meet on Tuesday.", ctrl.liveTranscript, "a spoken edit may shorten it")
        ctrl.cancelMic()
    }

    /** What the user typed into the dictated text goes out as the current text. */
    @Test fun hand_edits_are_the_current_text_of_the_next_pass() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val requests = mutableListOf<LivePassRequest>()
        ctrl.transcribePartial = { r -> requests += r; LivePass(r.previousText + " more", full = true, durationMs = 1_000L * requests.size) }
        var field: String? = null
        ctrl.currentText = { field }
        ctrl.startMic()
        mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f; testScheduler.advanceTimeBy(800); testScheduler.runCurrent()
        field = "typed by hand"
        mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f; testScheduler.advanceTimeBy(800); testScheduler.runCurrent()
        assertEquals("typed by hand", requests[1].previousText)
        ctrl.cancelMic()
    }

    /** Typing while a pass is out: its answer would drop the typing, so it is asked again. */
    @Test fun typing_while_a_pass_is_out_asks_again_with_the_typing() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val requests = mutableListOf<LivePassRequest>()
        var field: String? = ""
        val gate = CompletableDeferred<Unit>()
        ctrl.transcribePartial = { r ->
            requests += r
            if (requests.size == 1) gate.await()
            LivePass("${r.previousText}|spoken", full = true, durationMs = 1_000)
        }
        ctrl.currentText = { field }
        ctrl.startMic()
        mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f; testScheduler.advanceTimeBy(800); testScheduler.runCurrent()
        field = "typed"
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(2, requests.size)
        assertEquals("typed", requests[1].previousText)
        assertEquals("typed|spoken", ctrl.liveTranscript)
        ctrl.cancelMic()
    }

    /** An older broker that answers with only the new words after a cut still reads right. */
    @Test fun an_older_brokers_answers_still_read_right() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val answers = ArrayDeque(listOf(LivePass("one", durationMs = 2_000), LivePass("two", fromMs = 1_600, durationMs = 4_000)))
        ctrl.transcribePartial = { _ -> answers.removeFirst() }
        ctrl.startMic()
        repeat(2) {
            mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300)
            mic.loud.value = 0f; testScheduler.advanceTimeBy(800); testScheduler.runCurrent()
        }
        assertEquals("one two", ctrl.liveTranscript)
        ctrl.cancelMic()
    }

    /** A click or a breath is not speech enough for a pass: models invent text from such clips. */
    @Test fun a_blip_of_sound_sends_no_pass() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.transcribePartial = { _ -> LivePass("x", full = true, durationMs = 1_000) }
        ctrl.startMic()
        mic.loud.value = 0.3f; testScheduler.advanceTimeBy(150)
        mic.loud.value = 0f; testScheduler.advanceTimeBy(2_000); testScheduler.runCurrent()
        assertEquals(0, mic.snapshots)
        ctrl.cancelMic()
    }

    /** After a pass, a blip before Stop (the tap itself) does not cost another transcription. */
    @Test fun a_blip_before_stop_still_reuses_the_last_pass() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.transcribePartial = { _ -> LivePass("all of it", full = true, durationMs = 1_000) }
        var finals = 0
        ctrl.transcribeAudio = { _, _, _ -> finals++; "again" }
        ctrl.startMic()
        mic.loud.value = 0.3f; testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f; testScheduler.advanceTimeBy(800); testScheduler.runCurrent()
        mic.loud.value = 0.3f; testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        ctrl.stopMic()
        testScheduler.advanceUntilIdle()
        assertEquals(1, mic.snapshots)
        assertEquals(0, finals)
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
