package dev.supermux.ui.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakeMic
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.MicTrouble
import dev.supermux.ui.shell.testFleet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Held to talk and the mic won't open (#62): the bubble says why, as the OS tells it. A machine with no microphone at
 * all is told so plainly, and never asked to allow what can't be allowed; "allow" is only for a mic the OS keeps from
 * supermux.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayMicTroubleTest {
    private val allow = "Allow the microphone in supermux to talk to it here."
    private val noMic = "No microphone found. Connect one to talk to Anya."

    /** The overlay's voice and bubble on a platform with [mic], held to talk as the head's hold does. */
    private fun ComposeUiTest.holdToTalk(mic: FakeMic): OverlayUiState {
        val fleet = testFleet(overlayStore())
        val ui = OverlayUiState()
        setPlatformContent(platform = FakePlatform().apply { this.mic = mic }) {
            MaterialTheme {
                OverlayVoice(fleet, overlayAgent.id, ui)
                OverlayBubble(ui, onOpen = {}, onClose = {}, onSendLocked = {}, onCancelLocked = {}, maxWidth = 340.dp)
            }
        }
        waitForIdle()
        hold(ui)
        return ui
    }

    private fun ComposeUiTest.hold(ui: OverlayUiState) = runOnIdle {
        ui.listening = true
        ui.voice!!.start()
    }

    private fun ComposeUiTest.says(text: String) =
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.never(text: String) =
        assertEquals(0, onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size, "it said \"$text\"")

    @Test fun noMicrophoneAtAllIsSaidPlainly() = runComposeUiTest {
        holdToTalk(FakeMic(startResult = false, whyNot = MicTrouble.NONE_FOUND))
        says(noMic)
        never("Allow")
    }

    // Let go and held again: the mic fails just as before, and it says so again. (It used to sit listening to
    // nothing: the second try left the "didn't open" flag as the first had, so nothing saw it fail.)
    @Test fun everyHoldWithNoMicrophoneSaysSo() = runComposeUiTest {
        val ui = holdToTalk(FakeMic(startResult = false, whyNot = MicTrouble.NONE_FOUND))
        says(noMic)
        runOnIdle { ui.voice!!.send() }
        mainClock.advanceTimeBy(4_000)
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText(noMic).fetchSemanticsNodes().isEmpty() }
        hold(ui)
        says(noMic)
        assertFalse(ui.listening, "it is listening to a mic that never opened")
    }

    // No capture backend at all: nothing is even tried, and it is no microphone too.
    @Test fun noWayToRecordIsNoMicrophone() = runComposeUiTest {
        val mic = FakeMic(available = false, whyNot = MicTrouble.NONE_FOUND)
        holdToTalk(mic)
        says(noMic)
        assertFalse("start" in mic.events, "a mic was tried with nothing to record from")
    }

    @Test fun aMicTheOsKeepsFromSupermuxAsksToAllowIt() = runComposeUiTest {
        holdToTalk(FakeMic(startResult = false, whyNot = MicTrouble.NOT_ALLOWED))
        says(allow)
    }

    // There, allowed, and it still didn't open: no "allow", no "connect one".
    @Test fun aMicThatDidntOpenSaysJustThat() = runComposeUiTest {
        holdToTalk(FakeMic(startResult = false, whyNot = MicTrouble.DIDNT_OPEN))
        says("The microphone didn't open. Try again in a moment.")
        never("Allow")
        never("Connect one")
    }
}
