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
        ctrl.transcribePartial = { bytes, _, _ -> seen += bytes[0]; LivePass("hello there") }
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
        ctrl.transcribePartial = { _, _, _ -> LivePass("x") }
        ctrl.startMic()
        testScheduler.advanceTimeBy(10_000)
        assertEquals(0, mic.snapshots)
        ctrl.cancelMic()
    }

    @Test fun talking_without_a_pause_still_sends_a_pass_every_six_seconds() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        ctrl.transcribePartial = { _, _, _ -> LivePass("so far") }
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
        ctrl.transcribePartial = { _, _, _ -> CompletableDeferred<LivePass>().also { gates += it }.await() }
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
        ctrl.transcribePartial = { _, _, _ ->
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
        ctrl.transcribePartial = { _, _, _ -> LivePass("", skipped = true) }
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
        ctrl.transcribePartial = { _, _, _ -> LivePass("x") }
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

    @Test fun stop_drops_a_pass_still_in_flight_and_the_final_text_wins() = runTest {
        val mic = MeteredMic()
        val ctrl = controller(mic)
        val gate = CompletableDeferred<LivePass>()
        ctrl.transcribePartial = { _, _, _ -> gate.await() }
        ctrl.transcribeAudio = { bytes, _, _ -> if (bytes[0] == 99.toByte()) "final text" else null }
        var appended: String? = null
        ctrl.onAppend = { appended = it }
        ctrl.startMic()
        mic.loud.value = 0.3f
        testScheduler.advanceTimeBy(300)
        mic.loud.value = 0f
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()

        ctrl.stopMic()
        assertNull(ctrl.liveTranscript)
        testScheduler.advanceUntilIdle()
        gate.complete(LivePass("late partial"))
        testScheduler.advanceUntilIdle()

        assertEquals("final text", appended)
        assertNull(ctrl.liveTranscript)
        assertFalse(ctrl.transcribing)
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
        ctrl.transcribePartial = { _, _, _ -> LivePass("x") }
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
