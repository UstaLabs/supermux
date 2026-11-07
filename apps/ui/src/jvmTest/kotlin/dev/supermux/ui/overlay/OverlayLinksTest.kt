package dev.supermux.ui.overlay

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.shell.testFleet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every overlay surface that shows an Agent's words opens their links through the platform's opener (#62):
 * the reply in the bubble as it types out, the reply read in full, and the chat card. Each link is clicked
 * where it is drawn, with a mouse and with a finger, so a surface that draws a link without wiring it, draws
 * none, or lets the bubble's own click swallow it fails here. Any new overlay surface that shows message
 * text belongs in this suite.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayLinksTest {
    private val bare = "https://example.com/bare"
    private val labelled = "https://example.com/labelled"

    /** As an Agent writes it: a bare URL and a labelled link early on, then enough words to be cut short. */
    private val reply = "Found it: $bare and [the docs]($labelled). " + "And a few more words about it. ".repeat(8)

    // ── the bubble ─────────────────────────────────────────────────────────────────────────────

    /** The bubble as the overlay hosts it: the reply arrives from the fleet and types itself out. */
    private fun ComposeUiTest.bubble(platform: FakePlatform, opened: MutableList<String>): OverlayUiState {
        val app = overlayStore()
        val fleet = testFleet(app)
        val ui = OverlayUiState().apply { bubbleRoom = 700.dp }
        setPlatformContent(platform) {
            MaterialTheme {
                OverlayReplies(fleet, overlayAgent, ui)
                OverlayBubble(ui, onOpen = { opened += "chat" }, onClose = {}, onSendLocked = {}, onCancelLocked = {}, maxWidth = 340.dp)
            }
        }
        waitForIdle()
        app.agentReplies(reply)
        mainClock.advanceTimeBy(10_000)
        waitForIdle()
        return ui
    }

    @Test fun aLinkInTheReplyAsItTypesOutOpensThroughThePlatform() = runComposeUiTest {
        val platform = FakePlatform()
        val opened = mutableListOf<String>()
        bubble(platform, opened)
        for (url in listOf(bare, labelled)) for (mouse in listOf(true, false)) clickLink(url, mouse)
        assertEquals(listOf(bare, bare, labelled, labelled), platform.openedUrls)
        assertEquals(emptyList(), opened, "a link's click also opened the chat")
    }

    @Test fun aLinkInTheReplyReadInFullOpensThroughThePlatform() = runComposeUiTest {
        val platform = FakePlatform()
        val opened = mutableListOf<String>()
        bubble(platform, opened)
        onNodeWithTag("overlay_reply_more", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(onAllNodesWithTag("overlay_reply_full", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "not read in full")
        for (url in listOf(bare, labelled)) for (mouse in listOf(true, false)) clickLink(url, mouse)
        assertEquals(listOf(bare, bare, labelled, labelled), platform.openedUrls)
        assertEquals(emptyList(), opened, "a link's click also opened the chat")
    }

    // ── the chat card (a card on a wide screen, a sheet on a phone) ────────────────────────────

    private fun ComposeUiTest.card(platform: FakePlatform, compact: Boolean) {
        val app = overlayStore()
        app.agentReplies(reply)
        val fleet = testFleet(app)
        setPlatformContent(platform) {
            MaterialTheme {
                OverlayChat(
                    fleet, overlayAgent, rig = null, ui = OverlayUiState(), compact = compact,
                    onClose = {}, onOpenInApp = {}, modifier = Modifier.size(380.dp, 640.dp),
                )
            }
        }
        waitForIdle()
    }

    @Test fun aLinkInTheChatCardOpensThroughThePlatform() = runComposeUiTest {
        val platform = FakePlatform()
        card(platform, compact = false)
        for (url in listOf(bare, labelled)) for (mouse in listOf(true, false)) clickLink(url, mouse)
        assertEquals(listOf(bare, bare, labelled, labelled), platform.openedUrls)
    }

    @Test fun aLinkInThePhonesChatSheetOpensThroughThePlatform() = runComposeUiTest {
        val platform = FakePlatform()
        card(platform, compact = true)
        for (url in listOf(bare, labelled)) for (mouse in listOf(true, false)) clickLink(url, mouse)
        assertEquals(listOf(bare, bare, labelled, labelled), platform.openedUrls)
    }

    // ── finding and clicking a link where it is drawn ──────────────────────────────────────────

    private fun AnnotatedString.linkTo(url: String) =
        getLinkAnnotations(0, length).firstOrNull { (it.item as? LinkAnnotation.Url)?.url == url }

    private fun drawsLinkTo(url: String) = SemanticsMatcher("draws a link to $url") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.linkTo(url) != null }
    }

    /** Clicks the middle of the link to [url], by mouse or finger, in the text that draws it. */
    private fun ComposeUiTest.clickLink(url: String, mouse: Boolean) {
        val nodes = onAllNodes(drawsLinkTo(url), useUnmergedTree = true)
        assertTrue(nodes.fetchSemanticsNodes().isNotEmpty(), "no link to $url is drawn here")
        val node = nodes[0]
        val semantics = node.fetchSemanticsNode()
        val text = semantics.config[SemanticsProperties.Text].first { it.linkTo(url) != null }
        val link = text.linkTo(url)!!
        val layouts = mutableListOf<TextLayoutResult>()
        semantics.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        val at = layouts.first().getBoundingBox((link.start + link.end) / 2).center
        if (mouse) node.performMouseInput { click(at) } else node.performTouchInput { click(at) }
        waitForIdle()
    }
}
