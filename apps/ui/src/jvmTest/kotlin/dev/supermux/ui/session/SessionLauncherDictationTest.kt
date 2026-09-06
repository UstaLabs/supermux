package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test

private class ScriptedMicCapture(private val startsOk: Boolean, private val wav: ByteArray?) : MicCapture {
    override fun start() = startsOk
    override fun stop(): CapturedAudio? = wav?.let { CapturedAudio(it, "d.wav", "audio/wav") }
    override fun cancel() {}
    override suspend fun requestPermission() = true
    override val available: Boolean get() = true
    override val liveTranscript: LiveTranscript? get() = null
}

/**
 * [SessionLauncherScreen]'s mic wiring — the pre-spawn composer, so `transcribeAudio` always routes
 * id-less (mirrors [dev.supermux.state.HostStore.transcribeAudio]'s `sessionId = null` path). Uses
 * the SAME [dev.supermux.ui.chat.MicButton]/[dev.supermux.ui.chat.DictationController] the composer
 * tests already prove — only the host composable + append target (the launcher's `TextFieldValue`
 * message) differ. Mostly driven under a POINTER, where the field stays and the mic button spins in
 * place; the last two cases pin the input-mode split itself — a TOUCH host hands the field (not the
 * whole card: the launcher keeps its capsule and any staged chips) over to the shared
 * [dev.supermux.ui.chat.RecordingBar], which is
 * [dev.supermux.ui.chat.ComposerRecordingTakeover.FieldOnTouch].
 */
@OptIn(ExperimentalTestApi::class)
class SessionLauncherDictationTest {

    @Composable
    private fun Harness(
        transcribeAudio: suspend (ByteArray, String) -> String? = { _, _ -> null },
        micCapture: MicCapture = ScriptedMicCapture(startsOk = true, wav = byteArrayOf(1)),
    ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            SessionLauncherScreen(
                sessions = emptyList(),
                home = "/home/u",
                onBack = {},
                actions = LauncherActions(transcribeAudio = transcribeAudio),
                loadPrefs = { LauncherPrefs() },
                onPrefsChange = {},
                loadDraft = { LauncherDraft() },
                onDraftChange = {},
                onClearDraft = {},
                onSubmit = { _, _, _, _, _, _, _, _, _ -> null },
                micCapture = micCapture,
            )
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.pointerContent(content: @Composable () -> Unit) =
        setPlatformContent(
            pointer = true,
            widthClass = WindowWidthClass.Expanded,
            inputMode = InputMode.Pointer,
            content = content,
        )

    private fun androidx.compose.ui.test.ComposeUiTest.touchContent(content: @Composable () -> Unit) =
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
            content = content,
        )

    @Test fun mic_button_renders_next_to_attach() = runComposeUiTest {
        pointerContent { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_attach").assertIsDisplayed()
        onNodeWithTag("launcher_mic").assertIsDisplayed()
    }

    @Test fun clicking_mic_then_stop_appends_cleaned_text_into_the_message_field() = runComposeUiTest {
        pointerContent { Harness(transcribeAudio = { _, _ -> "dictated task text" }) }
        waitForIdle()
        onNodeWithTag("launcher_mic").performClick() // start
        onNodeWithTag("launcher_mic").performClick() // stop -> transcribe
        waitForIdle()
        onNodeWithTag("launcher_message").assertIsDisplayed()
        // Enables Send — proof the text actually landed in the message field (canSend needs non-blank text).
        onNodeWithTag("launcher_submit").assertIsDisplayed()
    }

    @Test fun mic_unavailable_disables_the_button() = runComposeUiTest {
        pointerContent { Harness(micCapture = ScriptedMicCapture(startsOk = false, wav = null)) }
        waitForIdle()
        onNodeWithTag("launcher_mic").performClick()
        waitForIdle()
        onNodeWithTag("launcher_mic").assertIsNotEnabled()
    }

    @Test fun a_touch_host_hands_the_field_over_to_the_recording_bar() = runComposeUiTest {
        // ComposerRecordingTakeover.FieldOnTouch: the card and its border stay put, the field and
        // toolbar are replaced by the shared RecordingBar. (A pointer host keeps typing — below.)
        touchContent { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_mic").performClick()
        waitForIdle()
        onNodeWithTag("composer_recording_bar").assertIsDisplayed()
        onNodeWithTag("launcher_composer_card").assertIsDisplayed()
        onNodeWithTag("launcher_message").assertDoesNotExist()
        onNodeWithTag("launcher_submit").assertDoesNotExist()
        // Stopping hands the card back.
        onNodeWithTag("voice_stop").performClick()
        waitForIdle()
        onNodeWithTag("launcher_message").assertIsDisplayed()
    }

    @Test fun a_pointer_host_keeps_the_field_while_recording() = runComposeUiTest {
        pointerContent { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_mic").performClick()
        waitForIdle()
        onNodeWithTag("composer_recording_bar").assertDoesNotExist()
        onNodeWithTag("launcher_message").assertIsDisplayed()
    }
}
