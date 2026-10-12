package dev.supermux.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
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

/** A headless-safe AWT source: `Label`/`Canvas` peers throw HeadlessException on the build Mac. */
private val awtSource = object : java.awt.Component() {}

/**
 * A REAL desktop key event, AWT-backed. The test injector's synthetic events carry no AWT event, so
 * the text field never types from them; desktop types on the separate `KEY_TYPED` event, and these
 * are what make a test see the space the field itself inserts.
 */
@OptIn(InternalComposeUiApi::class)
private fun awtSpace(id: Int, key: Key, type: KeyEventType, code: Int): KeyEvent = KeyEvent(
    key, type, ' '.code, false, false, false, false,
    java.awt.event.KeyEvent(awtSource, id, 0L, 0, code, ' '),
)

private val spacePressed get() =
    awtSpace(java.awt.event.KeyEvent.KEY_PRESSED, Key.Spacebar, KeyEventType.KeyDown, java.awt.event.KeyEvent.VK_SPACE)

// What AWT's KEY_TYPED becomes on desktop: no key, an `Unknown` type, the character.
private val spaceTyped get() =
    awtSpace(java.awt.event.KeyEvent.KEY_TYPED, Key.Unknown, KeyEventType.Unknown, java.awt.event.KeyEvent.VK_UNDEFINED)

private val spaceReleased get() =
    awtSpace(java.awt.event.KeyEvent.KEY_RELEASED, Key.Spacebar, KeyEventType.KeyUp, java.awt.event.KeyEvent.VK_SPACE)

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
                onTranscribeAudio = { _, _, _ -> "hello from the mic" },
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
                onTranscribeAudio = { _, _, _ -> "more text" },
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
                onTranscribeAudio = { _, _, _ -> "x" },
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
                onTranscribeAudio = { _, _, _ -> "never" },
            )
        }
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        onNodeWithTag("mic_denied_dialog").assertIsDisplayed()
        assertEquals(listOf("permission"), (platform.mic as FakeMic).events)
    }

    // Where the platform HAS on-device recognition and the broker has no ChatGPT login, dictation
    // takes the on-device path by default: the RecordingBar shows the growing partial and only the
    // finished draft is POSTed (transcribeDraft), not audio.
    @Test fun live_transcript_path_shows_partials_and_cleans_the_draft_not_the_audio() = runComposeUiTest {
        var draft by mutableStateOf("")
        val live = FakeLiveTranscript("raw on device text")
        var audioCalls = 0
        setPlatformContent(micPlatform(live = live)) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _, _ -> audioCalls++; "from audio" },
                actions = ComposerActions(
                    transcribeDraft = { "cleaned on device text" },
                    loadVoiceCaps = { VoiceCaps(codexAvailable = false, livePartials = false) },
                ),
            )
        }
        waitForIdle()
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        live.emit("raw on")
        waitForIdle()
        // The live partial is written into the field itself.
        assertEquals("raw on", draft)

        onNodeWithTag("voice_stop").performClick()
        waitForIdle()
        assertEquals("cleaned on device text", draft)
        assertEquals(0, audioCalls) // the audio POST is the OTHER path
    }

    // With a ChatGPT login on the broker the same device records for the broker instead.
    @Test fun a_chatgpt_login_on_the_broker_makes_dictation_record_audio() = runComposeUiTest {
        var draft by mutableStateOf("")
        val live = FakeLiveTranscript("raw on device text")
        setPlatformContent(micPlatform(live = live)) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _, _ -> "from audio" },
                actions = ComposerActions(
                    transcribeDraft = { "cleaned on device text" },
                    loadVoiceCaps = { VoiceCaps(codexAvailable = true, livePartials = true) },
                ),
            )
        }
        waitForIdle()
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        onNodeWithTag("voice_stop").performClick()
        waitForIdle()
        assertEquals("from audio", draft)
    }

    // The field stays on screen while recording, and the user can type into it.
    @Test fun the_field_stays_while_recording() = runComposeUiTest {
        var draft by mutableStateOf("")
        setPlatformContent(micPlatform()) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _, _ -> "from audio" },
            )
        }
        onNodeWithTag("composer-mic").performClick()
        waitForIdle()
        onNodeWithTag("composer_recording_bar").assertIsDisplayed()
        onNodeWithTag("composer-input").assertIsDisplayed()
        onNodeWithTag("voice_cancel").performClick()
        waitForIdle()
        assertEquals("", draft)
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
                onTranscribeAudio = { _, _, _ -> "cleaned from file" },
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
                onTranscribeAudio = { _, _, _ -> "should not be called" },
                // The host could not read the path — a null-bytes request.
                externalDictate = ComposerExternalDictate(null, "nope.wav"),
                onExternalDictateConsumed = { consumed = true },
            )
        }
        waitForIdle()
        assertEquals("", draft)
        assertTrue(consumed)
    }

    // ── the two append formulas (cluster G1 restores the launcher's) ─────────────────────────────

    @Test fun chat_appends_dictated_text_to_the_draft_exactly_as_typed() {
        assertEquals("hello there", appendDictated("hello", "there", trimDraft = false))
        assertEquals("there", appendDictated("", "there", trimDraft = false))
        // Chat keeps whitespace the user typed: a blank-but-not-empty draft is not "nothing".
        assertEquals("  there", appendDictated("  ", "there", trimDraft = false))
        assertEquals("hello  there", appendDictated("hello ", "there", trimDraft = false))
    }

    @Test fun the_launcher_trims_the_draft_before_appending() {
        assertEquals("hello there", appendDictated("hello", "there", trimDraft = true))
        assertEquals("there", appendDictated("", "there", trimDraft = true))
        // A whitespace-only draft is nothing at all, and a trailing space never doubles up.
        assertEquals("there", appendDictated("  ", "there", trimDraft = true))
        assertEquals("hello there", appendDictated("hello ", "there", trimDraft = true))
        assertEquals("hello there", appendDictated("hello\n\t", "there", trimDraft = true))
    }

    /** The headless `SM_DICTATE` hook labels its part from the FILE name. `.webm` must be the
     *  AUDIO type — `mimeForFileName` answers `video/webm` for the shared container. */
    @Test fun dictation_mime_is_derived_from_the_audio_extension() {
        assertEquals("audio/webm", dictationMimeFor("dictation.webm"))
        assertEquals("audio/mp4", dictationMimeFor("voice.m4a"))
        assertEquals("audio/wav", dictationMimeFor("dictation.wav"))
    }

    // ── hold Space to dictate ────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.holdSpaceComposer(
        platform: dev.supermux.ui.platform.FakePlatform,
        draft: () -> String,
        onDraft: (String) -> Unit,
    ) {
        setPlatformContent(platform) {
            Composer(
                draft = draft(), onDraftChange = onDraft, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onTranscribeAudio = { _, _, _ -> "hello from the mic" },
            )
        }
        onNodeWithTag("composer-input").performClick() // focus the field
        waitForIdle()
        // The hold is a 350 ms timer: from here on the test moves the clock itself.
        mainClock.autoAdvance = false
    }

    @Test fun holding_space_dictates_and_letting_go_stops() = runComposeUiTest {
        var draft by mutableStateOf("")
        val platform = micPlatform()
        val mic = platform.mic as FakeMic
        holdSpaceComposer(platform, { draft }, { draft = it })

        onNodeWithTag("composer-input").performKeyInput { keyDown(Key.Spacebar) }
        mainClock.advanceTimeBy(SPACE_HOLD_MS / 2)
        assertTrue(mic.events.isEmpty(), "no dictation before the hold time: ${mic.events}")
        mainClock.advanceTimeBy(SPACE_HOLD_MS)
        assertEquals(listOf("permission", "start"), mic.events)
        onNodeWithTag("composer_recording_bar").assertIsDisplayed()

        onNodeWithTag("composer-input").performKeyInput { keyUp(Key.Spacebar) }
        mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("permission", "start", "stop"), mic.events)
        assertEquals("hello from the mic", draft)
    }

    @Test fun tapping_space_types_exactly_one_space() = runComposeUiTest {
        var draft by mutableStateOf("")
        val platform = micPlatform()
        holdSpaceComposer(platform, { draft }, { draft = it })

        val input = onNodeWithTag("composer-input")
        input.performKeyPress(spacePressed)
        input.performKeyPress(spaceTyped)
        mainClock.advanceTimeBy(80)
        input.performKeyPress(spaceReleased)
        mainClock.advanceTimeBy(1_000)
        assertEquals(" ", draft)
        assertTrue((platform.mic as FakeMic).events.isEmpty())
    }

    @Test fun a_held_space_is_taken_back_and_its_repeats_type_nothing() = runComposeUiTest {
        var draft by mutableStateOf("")
        val platform = micPlatform()
        val mic = platform.mic as FakeMic
        holdSpaceComposer(platform, { draft }, { draft = it })

        val input = onNodeWithTag("composer-input")
        input.performKeyPress(spacePressed)
        input.performKeyPress(spaceTyped)
        mainClock.advanceTimeBy(32)
        assertEquals(" ", draft) // the field typed it, as it types any space
        repeat(3) { // the OS auto-repeat begins before the hold time is up
            input.performKeyPress(spacePressed)
            input.performKeyPress(spaceTyped)
            mainClock.advanceTimeBy(50)
        }
        assertEquals(" ", draft)
        mainClock.advanceTimeBy(SPACE_HOLD_MS)
        assertEquals("", draft) // a hold: the space is taken back
        assertEquals(listOf("permission", "start"), mic.events)
        repeat(5) {
            input.performKeyPress(spacePressed)
            input.performKeyPress(spaceTyped)
            mainClock.advanceTimeBy(30)
        }
        input.performKeyPress(spaceReleased)
        mainClock.advanceTimeBy(1_000)
        assertEquals("hello from the mic", draft)
        assertEquals(listOf("permission", "start", "stop"), mic.events)
    }

    @Test fun another_key_while_space_is_down_is_typing_not_a_hold() = runComposeUiTest {
        var draft by mutableStateOf("")
        val platform = micPlatform()
        holdSpaceComposer(platform, { draft }, { draft = it })

        onNodeWithTag("composer-input").performKeyInput {
            keyDown(Key.Spacebar)
            keyDown(Key.W) // rollover: the next letter goes down before Space comes up
        }
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("composer-input").performKeyInput {
            keyUp(Key.Spacebar)
            keyUp(Key.W)
        }
        mainClock.advanceTimeBy(100)
        assertTrue((platform.mic as FakeMic).events.isEmpty())
    }

    @Test fun shift_space_held_is_never_a_hold() = runComposeUiTest {
        var draft by mutableStateOf("")
        val platform = micPlatform()
        holdSpaceComposer(platform, { draft }, { draft = it })

        onNodeWithTag("composer-input").performKeyInput {
            keyDown(Key.ShiftLeft)
            keyDown(Key.Spacebar)
        }
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("composer-input").performKeyInput {
            keyUp(Key.Spacebar)
            keyUp(Key.ShiftLeft)
        }
        mainClock.advanceTimeBy(100)
        assertTrue((platform.mic as FakeMic).events.isEmpty())
    }

    @Test fun no_mic_means_space_is_only_ever_a_space() = runComposeUiTest {
        var draft by mutableStateOf("")
        val platform = micPlatform()
        setPlatformContent(platform) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                // onTranscribeAudio omitted: no mic button, so no hold either.
            )
        }
        val input = onNodeWithTag("composer-input")
        input.performClick()
        waitForIdle()
        mainClock.autoAdvance = false
        input.performKeyPress(spacePressed)
        input.performKeyPress(spaceTyped)
        mainClock.advanceTimeBy(1_000)
        input.performKeyPress(spacePressed) // auto-repeat types, as it always has
        input.performKeyPress(spaceTyped)
        input.performKeyPress(spaceReleased)
        mainClock.advanceTimeBy(100)
        assertEquals("  ", draft)
        assertTrue((platform.mic as FakeMic).events.isEmpty())
    }

}
