package dev.supermux.ui.overlay

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import dev.supermux.state.HostStore
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.shell.testFleet
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The overlay shows an Agent's reply as it is actually written (#62 on #76's streaming): its live text, under the
 * same ink-edge reveal as the chat's live row, finished by the stored reply that replaces it. When nothing streams
 * (live replies off, an older broker) the stored reply shows as it is, at once: the bubble no longer fakes a
 * typewriter of its own. And the overlay asks for its Agent's live text without claiming the chat is on screen.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayStreamingTest {
    private val sent: MutableList<ClientFrame> = Collections.synchronizedList(mutableListOf())

    /** The overlay's reply bubble, hosted as the overlay hosts it: the replies arrive from the fleet. */
    private fun ComposeUiTest.bubble(app: HostStore): OverlayUiState {
        val fleet = testFleet(app)
        val ui = OverlayUiState().apply { bubbleRoom = 700.dp }
        setPlatformContent {
            MaterialTheme {
                OverlayReplies(fleet, overlayAgent, ui)
                OverlayBubble(ui, onOpen = {}, onClose = {}, onSendLocked = {}, onCancelLocked = {}, maxWidth = 340.dp)
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        return ui
    }

    /** What the bubble shows of the reply right now ("" when it shows none). */
    private fun ComposeUiTest.shown(): String =
        onAllNodesWithTag("overlay_reply_text", useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun ComposeUiTest.frames(ms: Long) {
        mainClock.advanceTimeBy(ms)
        waitForIdle()
    }

    @Test fun aReplyShowsInTheBubbleAsItIsWritten() = runComposeUiTest {
        val app = overlayStore(sent)
        val ui = bubble(app)
        app.agentWrites("Hello there, how are you doing today?")
        frames(80)
        // The reveal's pace, not all at once: some of it is on screen, not yet the whole.
        val early = shown()
        assertTrue(early.isNotEmpty(), "nothing shown while the reply is being written")
        assertTrue(early.length < "Hello there, how are you doing today?".length, "shown all at once: \"$early\"")
        assertTrue(ui.speaking, "the head should be speaking while the reply comes")
        frames(3_000)
        assertEquals("Hello there, how are you doing today?", shown())
        // More is written: it follows.
        app.agentWrites(" Anything new?", at = "Hello there, how are you doing today?".length)
        frames(3_000)
        assertEquals("Hello there, how are you doing today? Anything new?", shown())
    }

    @Test fun theStoredReplyFinishesTheRevealOfItsStream() = runComposeUiTest {
        val app = overlayStore(sent)
        val ui = bubble(app)
        app.agentWrites("Half of it")
        frames(60)
        app.agentReplies("Half of it, and **the rest**.", stream = "k1")
        frames(3_000)
        assertEquals("Half of it, and the rest.", shown())
        assertFalse(ui.speaking, "the reply is done")
    }

    @Test fun withNothingStreamingTheStoredReplyShowsAtOnce() = runComposeUiTest {
        val app = overlayStore(sent)
        val ui = bubble(app)
        app.agentReplies("All of it, at once.")
        frames(16)
        assertEquals("All of it, at once.", shown())
        assertFalse(ui.speaking, "no typewriter of its own")
    }

    @Test fun theOverlayAsksForItsAgentsLiveTextWithoutClaimingTheChatIsOnScreen() = runComposeUiTest {
        val app = overlayStore(sent)
        val fleet = testFleet(app)
        val ui = OverlayUiState()
        var hosted by mutableStateOf(true)
        setPlatformContent { MaterialTheme { if (hosted) OverlayReplies(fleet, overlayAgent, ui) } }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { sent.any { it is ClientFrame.Live } }
        assertEquals(ClientFrame.Live(listOf(overlaySession.id)), sent.last { it is ClientFrame.Live })
        val shownAsOnScreen = sent.filterIsInstance<ClientFrame.Viewing>()
            .filter { it.visible && (it.session == overlaySession.id || it.sessions.orEmpty().contains(overlaySession.id)) }
        assertEquals(emptyList(), shownAsOnScreen, "the overlay is not a chat on screen")
        hosted = false
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { sent.last { it is ClientFrame.Live } == ClientFrame.Live(emptyList()) }
    }

    @Test fun theChatCardShowsTheReplyBeingWritten() = runComposeUiTest {
        val app = overlayStore(sent)
        val fleet = testFleet(app)
        setPlatformContent {
            MaterialTheme {
                OverlayChat(
                    fleet, overlayAgent, rig = null, ui = OverlayUiState(), compact = false,
                    onClose = {}, onOpenInApp = {}, modifier = Modifier.size(380.dp, 640.dp),
                )
            }
        }
        waitForIdle()
        // As the broker tells it: the Agent is working, and writes.
        app.reduce(ServerFrame.AgentState(session = overlaySession.id, phase = "thinking", state = "working", working = true))
        app.agentWrites("Writing this right now")
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        assertTrue(
            onAllNodesWithTag("chat-live-reply", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            "the card's chat shows no reply being written",
        )
    }
}
