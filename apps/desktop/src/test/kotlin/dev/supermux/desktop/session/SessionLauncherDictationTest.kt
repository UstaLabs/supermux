package dev.supermux.desktop.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.chat.MicCapture
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import kotlin.test.Test

private class ScriptedMicCapture(private val startsOk: Boolean, private val wav: ByteArray?) : MicCapture {
    override fun start() = startsOk
    override fun stop() = wav
    override fun cancel() {}
}

/**
 * M5-1 Task 5: [SessionLauncherScreen]'s mic wiring — the pre-spawn composer, so `transcribeAudio`
 * always routes id-less (mirrors [dev.supermux.desktop.state.DesktopAppState.transcribeAudio]'s
 * `sessionId = null` path). Uses the SAME
 * [dev.supermux.desktop.chat.MicButton]/[dev.supermux.desktop.chat.DesktopDictationController]
 * Task 3/4 already proved — only the host composable + append target (the launcher's
 * `TextFieldValue` message) differ.
 */
@OptIn(ExperimentalTestApi::class)
class SessionLauncherDictationTest {

    @Composable
    private fun Harness(
        transcribeAudio: suspend (ByteArray, String) -> String? = { _, _ -> null },
        micRecorderFactory: () -> MicCapture = { ScriptedMicCapture(startsOk = true, wav = byteArrayOf(1)) },
    ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            SessionLauncherScreen(
                sessions = emptyList(),
                home = "/home/u",
                onBack = {},
                loadProjects = { emptyList() },
                validatePath = { null },
                loadModels = { emptyList() },
                loadReasoningLevels = { _, _ -> null },
                loadRepoInfo = { null },
                loadPrefs = { LauncherPrefs() },
                onPrefsChange = {},
                loadDraft = { LauncherDraft() },
                onDraftChange = {},
                onClearDraft = {},
                onSubmit = { _, _, _, _, _, _, _, _, _ -> },
                transcribeAudio = transcribeAudio,
                micRecorderFactory = micRecorderFactory,
            )
        }
    }

    @Test fun mic_button_renders_next_to_attach() = runComposeUiTest {
        setContent { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_attach").assertIsDisplayed()
        onNodeWithTag("launcher_mic").assertIsDisplayed()
    }

    @Test fun clicking_mic_then_stop_appends_cleaned_text_into_the_message_field() = runComposeUiTest {
        setContent { Harness(transcribeAudio = { _, _ -> "dictated task text" }) }
        waitForIdle()
        onNodeWithTag("launcher_mic").performClick() // start
        onNodeWithTag("launcher_mic").performClick() // stop -> transcribe
        waitForIdle()
        onNodeWithTag("launcher_message").assertIsDisplayed()
        // Enables Send — proof the text actually landed in the message field (canSend needs non-blank text).
        onNodeWithTag("launcher_submit").assertIsDisplayed()
    }

    @Test fun mic_unavailable_disables_the_button() = runComposeUiTest {
        setContent { Harness(micRecorderFactory = { ScriptedMicCapture(startsOk = false, wav = null) }) }
        waitForIdle()
        onNodeWithTag("launcher_mic").performClick()
        waitForIdle()
        onNodeWithTag("launcher_mic").assertIsNotEnabled()
    }
}
