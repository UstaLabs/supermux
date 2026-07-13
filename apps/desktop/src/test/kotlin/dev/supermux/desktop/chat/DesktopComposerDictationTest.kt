package dev.supermux.desktop.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ScriptedMicCapture(private val startsOk: Boolean, private val wav: ByteArray?) : MicCapture {
    override fun start() = startsOk
    override fun stop() = wav
    override fun cancel() {}
}

/**
 * M5-1 Task 4: [DesktopComposer]'s mic wiring — clicking the mic drives the SAME
 * [DesktopDictationController] Task 3 already proved, appending its cleaned text onto the hoisted
 * draft; [ComposerExternalDictate] drives the identical transcribe->append path from OUTSIDE the
 * click flow (the SM_DICTATE headless hook, Task 6) without ever touching [MicCapture].
 */
@OptIn(ExperimentalTestApi::class)
class DesktopComposerDictationTest {

    @Test fun clicking_mic_then_stop_transcribes_and_appends_to_the_draft() = runComposeUiTest {
        var draft by mutableStateOf("")
        setContent {
            DesktopComposer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onTranscribeAudio = { _, _ -> "hello from the mic" },
                micRecorderFactory = { ScriptedMicCapture(startsOk = true, wav = byteArrayOf(1, 2, 3)) },
            )
        }
        onNodeWithTag("composer-mic").performClick() // start
        onNodeWithTag("composer-mic").performClick() // stop -> transcribe
        waitForIdle()
        assertEquals("hello from the mic", draft)
    }

    @Test fun appended_text_is_space_joined_onto_existing_draft_text() = runComposeUiTest {
        var draft by mutableStateOf("existing")
        setContent {
            DesktopComposer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _ -> "more text" },
                micRecorderFactory = { ScriptedMicCapture(startsOk = true, wav = byteArrayOf(1)) },
            )
        }
        onNodeWithTag("composer-mic").performClick()
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        assertEquals("existing more text", draft)
    }

    @Test fun mic_unavailable_disables_the_button() = runComposeUiTest {
        setContent {
            DesktopComposer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _ -> "x" },
                micRecorderFactory = { ScriptedMicCapture(startsOk = false, wav = null) },
            )
        }
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        onNodeWithTag("composer-mic").assertIsNotEnabled()
    }

    @Test fun no_transcribe_seam_bound_hides_the_mic_button() = runComposeUiTest {
        setContent {
            DesktopComposer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                // onTranscribeAudio omitted -> null -> mic hidden, mirrors onUpload==null hiding Attach.
            )
        }
        onNodeWithTag("composer-mic").assertDoesNotExist()
    }

    @Test fun external_dictate_reads_the_wav_file_and_appends_without_starting_the_recorder() = runComposeUiTest {
        var draft by mutableStateOf("")
        var consumed = false
        val wavFile = java.io.File.createTempFile("m5v-dictate", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        setContent {
            DesktopComposer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onTranscribeAudio = { _, _ -> "cleaned from file" },
                // A recorder whose start() would throw/return false — proves externalDictate never
                // touches MicCapture at all (it reads the file straight to bytes).
                micRecorderFactory = { ScriptedMicCapture(startsOk = false, wav = null) },
                externalDictate = ComposerExternalDictate(wavFile.absolutePath),
                onExternalDictateConsumed = { consumed = true },
            )
        }
        waitForIdle()
        assertEquals("cleaned from file", draft)
        assertTrue(consumed)
    }

    @Test fun external_dictate_with_a_missing_file_consumes_without_appending() = runComposeUiTest {
        var draft by mutableStateOf("")
        var consumed = false
        setContent {
            DesktopComposer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _ -> "should not be called" },
                externalDictate = ComposerExternalDictate("/nonexistent/path.wav"),
                onExternalDictateConsumed = { consumed = true },
            )
        }
        waitForIdle()
        assertEquals("", draft)
        assertTrue(consumed)
    }
}
