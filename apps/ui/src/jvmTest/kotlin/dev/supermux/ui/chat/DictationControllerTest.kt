package dev.supermux.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import kotlin.test.assertTrue

private class FakeMicCapture(
    private val startsOk: Boolean = true,
    private val wavOnStop: ByteArray? = byteArrayOf(9, 9, 9),
    private val filename: String = "dictation.wav",
    private val mime: String = "audio/wav",
) : MicCapture {
    var startCalls = 0; var stopCalls = 0; var cancelCalls = 0
    /** Every call in order, so "flush was awaited BEFORE stop" is assertable. */
    val calls = mutableListOf<String>()
    override fun start(): Boolean { startCalls++; calls.add("start"); return startsOk }
    override suspend fun flush() {
        calls.add("flush")
        kotlinx.coroutines.yield()
        calls.add("flushed")
    }
    override fun stop(): CapturedAudio? {
        stopCalls++
        calls.add("stop")
        return wavOnStop?.let { CapturedAudio(it, filename, mime) }
    }
    override fun cancel() { cancelCalls++ }
    override suspend fun requestPermission(): Boolean = true
    override val available: Boolean get() = true
    override val liveTranscript: LiveTranscript? get() = null
}

/**
 * [DictationController] — record -> transcribe -> append, driven entirely through the
 * `Platform.mic`/transcribeAudio/onAppend seams. No real mic, no real broker: the
 * controller's own [CoroutineScope] is a [TestScope] backed by [UnconfinedTestDispatcher] so the
 * `scope.launch {}` inside [DictationController.stopMic] runs to completion synchronously
 * within the test body (same idiom [HostStore] tests use for its `stateScope`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationControllerTest {

    private fun controller(fake: MicCapture) = DictationController(fake, TestScope(UnconfinedTestDispatcher()))

    @Test fun start_mic_flips_recording_true_when_the_line_opens() {
        val fake = FakeMicCapture(startsOk = true)
        val ctrl = controller(fake)

        ctrl.startMic()

        assertTrue(ctrl.recording)
        assertFalse(ctrl.micUnavailable)
        assertEquals(1, fake.startCalls)
    }

    /** The browser's recorder is still holding the tail of the clip when the user taps Stop, so
     *  the controller must await [MicCapture.flush] BEFORE the synchronous [MicCapture.stop]. */
    @Test fun stop_mic_awaits_the_flush_before_stopping_the_recorder() {
        val fake = FakeMicCapture()
        val ctrl = controller(fake)
        ctrl.transcribeAudio = { _, _, _ -> "text" }
        ctrl.startMic()

        ctrl.stopMic()

        assertEquals(listOf("start", "flush", "flushed", "stop"), fake.calls)
    }

    /** The browser records `audio/webm;codecs=opus`, not WAV: the recorded container has to reach
     *  the transcribe POST, or the broker's ffmpeg pass is told a lie about the bytes it got. */
    @Test fun stop_mic_passes_the_recorded_filename_and_mime_to_transcribe() {
        val fake = FakeMicCapture(filename = "dictation.webm", mime = "audio/webm;codecs=opus")
        var seen: Pair<String, String>? = null
        val ctrl = controller(fake)
        ctrl.transcribeAudio = { _, name, mime -> seen = name to mime; "cleaned text" }
        ctrl.startMic()

        ctrl.stopMic()

        assertEquals("dictation.webm" to "audio/webm;codecs=opus", seen)
    }

    @Test fun start_mic_sets_mic_unavailable_when_the_line_fails_to_open() {
        val ctrl = controller(FakeMicCapture(startsOk = false))

        ctrl.startMic()

        assertFalse(ctrl.recording)
        assertTrue(ctrl.micUnavailable)
    }

    @Test fun stop_mic_transcribes_the_captured_wav_and_appends_the_cleaned_text() {
        val fake = FakeMicCapture(wavOnStop = byteArrayOf(1, 2, 3))
        var appended: String? = null
        var capturedBytes: ByteArray? = null
        val ctrl = controller(fake)
        ctrl.transcribeAudio = { bytes, _, _ -> capturedBytes = bytes; "cleaned text" }
        ctrl.onAppend = { appended = it }
        ctrl.startMic()

        ctrl.stopMic()

        assertFalse(ctrl.recording)
        assertFalse(ctrl.transcribing)
        assertEquals("cleaned text", appended)
        assertEquals(listOf<Byte>(1, 2, 3), capturedBytes?.toList())
    }

    @Test fun stop_mic_with_nothing_captured_sets_an_error_and_never_calls_transcribe() {
        var transcribeCalled = false
        val ctrl = controller(FakeMicCapture(wavOnStop = null))
        ctrl.transcribeAudio = { _, _, _ -> transcribeCalled = true; "x" }
        ctrl.startMic()

        ctrl.stopMic()

        assertFalse(transcribeCalled)
        assertEquals("Didn't catch that", ctrl.errorMessage)
    }

    @Test fun a_blank_or_null_transcription_result_sets_a_failed_error_and_does_not_append() {
        var appendCalled = false
        val ctrl = controller(FakeMicCapture())
        ctrl.transcribeAudio = { _, _, _ -> "   " }
        ctrl.onAppend = { appendCalled = true }
        ctrl.startMic()

        ctrl.stopMic()

        assertFalse(appendCalled)
        assertEquals("Transcription failed", ctrl.errorMessage)
    }

    @Test fun cancel_mic_while_recording_discards_via_the_recorder_and_never_transcribes() {
        val fake = FakeMicCapture()
        var transcribeCalled = false
        val ctrl = controller(fake)
        ctrl.transcribeAudio = { _, _, _ -> transcribeCalled = true; "x" }
        ctrl.startMic()

        ctrl.cancelMic()

        assertFalse(ctrl.recording)
        assertEquals(1, fake.cancelCalls)
        assertEquals(0, fake.stopCalls)
        assertFalse(transcribeCalled)
    }

    @Test fun cancel_mic_when_not_recording_is_a_no_op() {
        val fake = FakeMicCapture()
        controller(fake).cancelMic()

        assertEquals(0, fake.cancelCalls)
    }

    @Test fun start_mic_is_a_no_op_while_already_busy() {
        val fake = FakeMicCapture()
        val ctrl = controller(fake)
        ctrl.startMic()

        ctrl.startMic() // already recording — must not re-open the line

        assertEquals(1, fake.startCalls)
    }

    /**
     * REGRESSION (cross-session dictation leak): a transcription still in flight when the composer
     * switches sessions must NOT resolve into the new session's draft. On a session switch,
     * `rememberDictation`'s `DisposableEffect(resetKey)` disposes the OUTGOING controller by
     * calling [DictationController.cancelMic] — which must cancel the pending whisper POST,
     * not just guard `recording`. Otherwise session A's ~20-30s POST resolves after the composer has
     * rebound `onAppend` to session B and appends A's text into B (the M4d attachment-leak class).
     *
     * Uses a [StandardTestDispatcher] (NOT the Unconfined one the other tests use) + a gated
     * [CompletableDeferred] so the transcribe coroutine genuinely SUSPENDS mid-flight — the only way
     * to model "user switched sessions before the POST came back". Without the [cancelMic] fix this
     * fails: the deferred resumes, `pastAwait` flips true, and `appended` receives A's text.
     */
    @Test fun cancel_mic_cancels_an_in_flight_transcription_so_it_never_appends_after_a_session_switch() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val ctrl = DictationController(FakeMicCapture(wavOnStop = byteArrayOf(1, 2, 3)), scope)

        val gate = CompletableDeferred<Unit>()
        var pastAwait = false
        var appended: String? = null
        // Session A's binding: a transcribe that hangs until the (fake) POST resolves.
        ctrl.transcribeAudio = { _, _, _ -> gate.await(); pastAwait = true; "session A dictation" }
        ctrl.onAppend = { appended = it }

        ctrl.startMic()
        ctrl.stopMic() // launches the transcribe coroutine
        scope.testScheduler.runCurrent() // let it reach `gate.await()` and suspend there

        // Session switch: the DisposableEffect(resetKey) onDispose fires cancelMic() on this
        // (outgoing) controller while the POST is still in flight.
        ctrl.cancelMic()

        // The POST finally resolves — but the coroutine was cancelled at the await, so nothing past
        // it runs.
        gate.complete(Unit)
        scope.testScheduler.advanceUntilIdle()

        assertFalse(pastAwait, "cancelled transcription must not run past its suspension point")
        assertNull(appended, "a cancelled session's dictation must never append")
        assertFalse(ctrl.transcribing)
    }
}
