package dev.supermux.desktop.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeMicCapture(
    private val startsOk: Boolean = true,
    private val wavOnStop: ByteArray? = byteArrayOf(9, 9, 9),
) : MicCapture {
    var startCalls = 0; var stopCalls = 0; var cancelCalls = 0
    override fun start(): Boolean { startCalls++; return startsOk }
    override fun stop(): ByteArray? { stopCalls++; return wavOnStop }
    override fun cancel() { cancelCalls++ }
}

/**
 * M5-1 Task 3: [DesktopDictationController] — record -> transcribe -> append, driven entirely
 * through the [MicCapture]/transcribeAudio/onAppend seams. No real mic, no real broker: the
 * controller's own [CoroutineScope] is a [TestScope] backed by [UnconfinedTestDispatcher] so the
 * `scope.launch {}` inside [DesktopDictationController.stopMic] runs to completion synchronously
 * within the test body (same idiom [DesktopAppState] tests use for its `stateScope`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDictationControllerTest {

    private fun controller(fake: MicCapture) = DesktopDictationController(fake, TestScope(UnconfinedTestDispatcher()))

    @Test fun start_mic_flips_recording_true_when_the_line_opens() {
        val fake = FakeMicCapture(startsOk = true)
        val ctrl = controller(fake)

        ctrl.startMic()

        assertTrue(ctrl.recording)
        assertFalse(ctrl.micUnavailable)
        assertEquals(1, fake.startCalls)
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
        ctrl.transcribeAudio = { bytes, _ -> capturedBytes = bytes; "cleaned text" }
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
        ctrl.transcribeAudio = { _, _ -> transcribeCalled = true; "x" }
        ctrl.startMic()

        ctrl.stopMic()

        assertFalse(transcribeCalled)
        assertEquals("Didn't catch that", ctrl.errorMessage)
    }

    @Test fun a_blank_or_null_transcription_result_sets_a_failed_error_and_does_not_append() {
        var appendCalled = false
        val ctrl = controller(FakeMicCapture())
        ctrl.transcribeAudio = { _, _ -> "   " }
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
        ctrl.transcribeAudio = { _, _ -> transcribeCalled = true; "x" }
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
}
