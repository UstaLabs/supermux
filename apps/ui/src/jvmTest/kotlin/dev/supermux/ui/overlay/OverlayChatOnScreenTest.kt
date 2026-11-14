package dev.supermux.ui.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.state.HostStore
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.shell.testFleet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Card #154: with the Agent's own chat open and the app in front on this device, a reply is already being read where
 * it lands, so the overlay doesn't pop it up (and talk it) a second time. Anywhere else (another chat on screen, the
 * window not in front, the app in the background) the bubble comes as before.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayChatOnScreenTest {
    private fun ComposeUiTest.overlay(app: HostStore): OverlayUiState {
        val ui = OverlayUiState().apply { bubbleRoom = 700.dp }
        setPlatformContent {
            MaterialTheme {
                OverlayReplies(testFleet(app), overlayAgent, ui)
                OverlayBubble(ui, onOpen = {}, onClose = {}, onSendLocked = {}, onCancelLocked = {}, maxWidth = 340.dp)
            }
        }
        waitForIdle()
        return ui
    }

    private fun ComposeUiTest.bubbles() = onAllNodesWithTag("overlay_reply", useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun aReplyInTheChatOnScreenDoesNotPopUp() = runComposeUiTest {
        val app = overlayStore()
        app.updateViewingSessions(listOf(overlaySession.id), visible = true)
        val ui = overlay(app)
        app.agentWrites("Rerunning it now.")
        app.agentReplies("Rerunning it now.", stream = "k1")
        waitForIdle()
        assertNull(ui.reply)
        assertEquals(0, bubbles())
    }

    @Test fun anotherChatOnScreenStillGetsTheBubble() = runComposeUiTest {
        val app = overlayStore()
        app.updateViewingSessions(listOf("someone-else"), visible = true)
        val ui = overlay(app)
        app.agentReplies("Done.")
        waitForIdle()
        assertNotNull(ui.reply)
        assertEquals(1, bubbles())
    }

    // The app's window not in front (or the app in the background) is not looking at the chat.
    @Test fun theChatOpenButTheAppNotInFrontStillGetsTheBubble() = runComposeUiTest {
        val app = overlayStore()
        app.updateViewingSessions(listOf(overlaySession.id), visible = false)
        val ui = overlay(app)
        app.agentReplies("Done.")
        waitForIdle()
        assertNotNull(ui.reply)
    }

    @Test fun aBubbleGoesWhenItsChatComesOnScreen() = runComposeUiTest {
        val app = overlayStore()
        val ui = overlay(app)
        app.agentReplies("Done.")
        waitForIdle()
        assertNotNull(ui.reply)
        app.updateViewingSessions(listOf(overlaySession.id), visible = true)
        waitForIdle()
        assertNull(ui.reply)
        assertEquals(0, bubbles())
    }
}
