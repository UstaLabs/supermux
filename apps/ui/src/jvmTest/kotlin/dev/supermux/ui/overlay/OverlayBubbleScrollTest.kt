package dev.supermux.ui.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.supermux.ui.chat.setPlatformContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Read it all" in the bubble (#62), the same composable on Android and the desktop: a long reply
 * scrolls by wheel and by finger in a box of bounded height, and more text arriving moves you to it
 * only while you're at the end.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayBubbleScrollTest {
    private val long = (1..40).joinToString("\n\n") { "Paragraph $it of an answer far too long for the bubble to show at once." }

    private fun ComposeUiTest.bubble(text: String = long): OverlayUiState {
        val ui = OverlayUiState().apply {
            // A stored reply nobody saw being written: it shows as it is, whole.
            reply = OverlayReply("r1", "Anya", text)
            replyExpanded = true
            bubbleRoom = 700.dp
        }
        // The platform opens the reply's links (OverlayLinksTest); every chat surface reads it.
        setPlatformContent {
            MaterialTheme {
                OverlayBubble(ui, onOpen = {}, onClose = {}, onSendLocked = {}, onCancelLocked = {}, maxWidth = 340.dp)
            }
        }
        return ui
    }

    // The reply is clickable, so its words merge into it: the scrolling text is found unmerged.
    private val ComposeUiTest.full get() = onNodeWithTag("overlay_reply_full", useUnmergedTree = true)

    private fun ComposeUiTest.range(): ScrollAxisRange = full.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private fun ComposeUiTest.scrollBy(dy: Float) {
        full.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
        waitForIdle()
    }

    private fun ComposeUiTest.more(ui: OverlayUiState) {
        runOnIdle { ui.reply = ui.reply!!.copy(text = ui.reply!!.text + "\n\nAnd one more paragraph, arriving while you read.") }
        waitForIdle()
    }

    @Test
    fun aLongReplyOpensAtItsTopInABoxOfBoundedHeight() = runComposeUiTest {
        bubble()
        assertEquals(0f, range().value())
        assertTrue(range().maxValue() > 0f, "a reply this long should overflow the box")
        // At most REPLY_MAX (460) less the bubble's own chrome (100).
        assertTrue(full.fetchSemanticsNode().size.height <= 360, "the box grew to ${full.fetchSemanticsNode().size.height}")
    }

    @Test
    fun theMouseWheelScrollsIt() = runComposeUiTest {
        bubble()
        full.performMouseInput {
            moveTo(center)
            scroll(3f)
        }
        waitForIdle()
        assertTrue(range().value() > 0f, "the wheel didn't move it")
    }

    @Test
    fun aFingerScrollsIt() = runComposeUiTest {
        bubble()
        full.performTouchInput { swipeUp() }
        waitForIdle()
        assertTrue(range().value() > 0f, "a swipe didn't move it")
    }

    @Test
    fun moreTextWhileAtTheEndIsFollowed() = runComposeUiTest {
        val ui = bubble()
        scrollBy(100_000f)
        assertEquals(range().maxValue(), range().value())
        val before = range().maxValue()
        more(ui)
        assertTrue(range().maxValue() > before)
        assertEquals(range().maxValue(), range().value())
    }

    @Test
    fun moreTextWhileReadingHigherUpLeavesYouThere() = runComposeUiTest {
        val ui = bubble()
        scrollBy(200f)
        val at = range().value()
        more(ui)
        assertEquals(at, range().value())
        assertTrue(range().value() < range().maxValue())
    }
}
