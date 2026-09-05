package dev.supermux.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.FakeLiveTranscript
import dev.supermux.ui.platform.FakeMic
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LiveTranscript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A platform whose mic is scripted: does it open, and what audio does it hand back on stop. */
private fun micPlatform(
    startsOk: Boolean = true,
    wav: ByteArray? = byteArrayOf(1, 2, 3),
    permission: Boolean = true,
    live: LiveTranscript? = null,
) = FakePlatform().apply {
    mic = FakeMic(
        liveTranscript = live,
        startResult = startsOk,
        audio = wav?.let { CapturedAudio(it, "dictation.wav", "audio/wav") },
        permission = permission,
    )
}

/**
 * [Composer]'s mic wiring — clicking the mic drives the SAME [DictationController]
 * [DictationControllerTest] proves, appending its cleaned text onto the hoisted draft;
 * [ComposerExternalDictate] drives the identical transcribe→append path from OUTSIDE the click flow
 * (the SM_DICTATE headless hook) without ever touching the mic.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerDictationTest {

    @Test fun clicking_mic_then_stop_transcribes_and_appends_to_the_draft() = runComposeUiTest {
        var draft by mutableStateOf("")
        setPlatformContent(micPlatform()) {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onTranscribeAudio = { _, _ -> "hello from the mic" },
            )
        }
        onNodeWithTag("composer-mic").performClick() // start
        waitForIdle()
        onNodeWithTag("voice_stop").performClick()   // the RecordingBar takeover's STOP
        waitForIdle()
        assertEquals("hello from the mic", draft)
    }

    @Test fun appended_text_is_space_joined_onto_existing_draft_text() = runComposeUiTest {
        var draft by mutableStateOf("existing")
        setPlatformContent(micPlatform(wav = byteArrayOf(1))) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _ -> "more text" },
            )
        }
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        onNodeWithTag("voice_stop").performClick()
        waitForIdle()
        assertEquals("existing more text", draft)
    }

    @Test fun mic_unavailable_disables_the_button() = runComposeUiTest {
        setPlatformContent(micPlatform(startsOk = false, wav = null)) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _ -> "x" },
            )
        }
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        onNodeWithTag("composer-mic").assertIsNotEnabled()
    }

    @Test fun no_transcribe_seam_bound_hides_the_mic_button() = runComposeUiTest {
        setPlatformContent(micPlatform()) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                // onTranscribeAudio omitted -> null -> mic hidden, mirrors onUpload==null hiding Attach.
            )
        }
        onNodeWithTag("composer-mic").assertDoesNotExist()
    }

    // A refused RECORD_AUDIO grant shows the "enable it in Settings" dialog rather than a mic button
    // that silently does nothing (Android's flow; desktop's seam always answers true).
    @Test fun a_refused_permission_shows_the_mic_denied_dialog_and_never_records() = runComposeUiTest {
        val platform = micPlatform(permission = false)
        setPlatformContent(platform) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _ -> "never" },
            )
        }
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        onNodeWithTag("mic_denied_dialog").assertIsDisplayed()
        assertEquals(listOf("permission"), (platform.mic as FakeMic).events)
    }

    // Where the platform HAS on-device recognition, dictation takes that path: the RecordingBar
    // shows the growing partial and only the finished draft is POSTed (transcribeDraft), not audio.
    @Test fun live_transcript_path_shows_partials_and_cleans_the_draft_not_the_audio() = runComposeUiTest {
        var draft by mutableStateOf("")
        val live = FakeLiveTranscript("raw on device text")
        var audioCalls = 0
        setPlatformContent(micPlatform(live = live)) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _ -> audioCalls++; "from audio" },
                actions = ComposerActions(transcribeDraft = { "cleaned on device text" }),
            )
        }
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        live.emit("raw on")
        waitForIdle()
        onNodeWithTag("voice_live_transcript").assertIsDisplayed()

        onNodeWithTag("voice_stop").performClick()
        waitForIdle()
        assertEquals("cleaned on device text", draft)
        assertEquals(0, audioCalls) // the audio POST is the OTHER path
    }

    @Test fun external_dictate_transcribes_the_supplied_bytes_without_touching_the_mic() = runComposeUiTest {
        var draft by mutableStateOf("")
        var consumed = false
        val platform = micPlatform(startsOk = false, wav = null)
        setPlatformContent(platform) {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onTranscribeAudio = { _, _ -> "cleaned from file" },
                externalDictate = ComposerExternalDictate(byteArrayOf(1, 2, 3), "m5v.wav"),
                onExternalDictateConsumed = { consumed = true },
            )
        }
        waitForIdle()
        assertEquals("cleaned from file", draft)
        assertTrue(consumed)
        // Never opened the mic — the hook feeds bytes straight through the transcribe seam.
        assertTrue((platform.mic as FakeMic).events.isEmpty())
    }

    @Test fun external_dictate_with_a_missing_file_consumes_without_appending() = runComposeUiTest {
        var draft by mutableStateOf("")
        var consumed = false
        setPlatformContent(micPlatform()) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _ -> "should not be called" },
                // The host could not read the path — a null-bytes request.
                externalDictate = ComposerExternalDictate(null, "nope.wav"),
                onExternalDictateConsumed = { consumed = true },
            )
        }
        waitForIdle()
        assertEquals("", draft)
        assertTrue(consumed)
    }
}
