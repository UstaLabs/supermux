package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scrolling back through a chat moves it one way only. A message from another session shows three lines and
 * "Show more"; it used to lay out at its full height first and fold a frame later. One coming into view at the top
 * then pulled every row under it toward the end by the height it folded away: every other frame of a fast scroll
 * back, and, near the end, all the way back to the end, so a slow scroll could not get past it (2026-11-06, the
 * Zoomsphere chat on the Mac).
 */
@OptIn(ExperimentalTestApi::class)
class ChatScrollBackJumpTest {

    private val session = SessionInfo(id = "s1", name = "Zoomsphere", workdir = "/home/u/proj", agent = "claude")

    private fun reply(n: Int, label: String, paragraphs: Int, tail: String = "") = LogEntry(
        id = "m$n",
        ts = "2026-11-05T20:%02d:00Z".format(n),
        direction = "outbound",
        text = (1..paragraphs).joinToString("\n\n") {
            "$label paragraph $it: the agent explains one more part of the change in a full sentence."
        } + tail,
    )

    /** A report from another session: far longer than the three lines it shows. */
    private fun card(n: Int, label: String, bullets: Int) = LogEntry(
        id = "m$n",
        ts = "2026-11-05T20:%02d:00Z".format(n),
        direction = "inbound",
        channel = "inbox",
        from_session_id = "sender-$n",
        from_session_name = "ZS 943 Apikey Renewal",
        from_agent = "claude",
        text = "$label: #943 review re-verification, partly done.\n\n" +
            (1..bullets).joinToString("\n") { "- Check $it: one more scenario re-run against the local API branch, passed." },
    )

    /** The end of the chat in the recording: two long reports, each above a reply, then the last reply. */
    private val messages = listOf(
        reply(1, "Earlier", 4),
        card(2, "First report", 16),
        reply(3, "Between", 10),
        card(4, "Second report", 30),
        reply(5, "Labelled", 9),
        reply(6, "Last", 12, "\n\nThe very end."),
    )

    /** Texts the scroll is watched by: a few in every reply. */
    private val watched = listOf(
        "Earlier paragraph 1:", "Between paragraph 1:", "Between paragraph 10:",
        "Labelled paragraph 1:", "Labelled paragraph 9:", "Last paragraph 1:", "Last paragraph 12:",
    )

    private fun ComposeUiTest.panel() {
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box(Modifier.requiredSize(width = 950.dp, height = 735.dp)) {
                    ChatPanel(
                        session = session,
                        state = ChatState(messages = messages),
                        actions = ChatActions(),
                        draft = "",
                        onDraftChange = {},
                        showHeader = false,
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.tops(): Map<String, Float> = watched.mapNotNull { text ->
        onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull()?.let { text to it.positionInRoot.y }
    }.toMap()

    /**
     * Scrolls back toward older messages from the end, finger down the whole time, [steps] px a frame. Returns every
     * frame where a reply moved up the screen (toward the end) instead of down.
     */
    private fun ComposeUiTest.scrollBack(steps: List<Float>): List<String> {
        mainClock.autoAdvance = false
        val body = onNodeWithTag("chat_body")
        body.performTouchInput { down(Offset(width * 0.2f, 40f)) }
        // Past the touch slop first.
        body.performTouchInput { moveBy(Offset(0f, 40f)) }
        mainClock.advanceTimeByFrame()
        var before = tops()
        val backward = ArrayList<String>()
        steps.forEachIndexed { frame, step ->
            body.performTouchInput { moveBy(Offset(0f, step)) }
            mainClock.advanceTimeByFrame()
            val now = tops()
            for ((text, top) in now) {
                val was = before[text] ?: continue
                if (top < was - 1f) backward += "frame $frame (finger moved $step): \"$text\" moved ${was - top}px toward the end"
            }
            before = now
        }
        body.performTouchInput { up() }
        mainClock.autoAdvance = true
        waitForIdle()
        return backward
    }

    @Test fun a_long_report_is_folded_from_its_first_layout() = runComposeUiTest {
        val heights = ArrayList<Int>()
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box(Modifier.requiredWidth(700.dp).onSizeChanged { heights += it.height }) {
                    TimelineItemRow(TimelineItem.Msg(card(2, "First report", 16)))
                }
            }
        }
        waitForIdle()
        onNodeWithTag("inbox-expand:m2").assertTextContains("Show more")
        assertEquals(listOf(heights.last()), heights, "the card was laid out at these heights, one after another")
    }

    /** The recording's first scroll back: slow off the end, then a fast coast that dies away. */
    @Test fun a_trackpad_scroll_back_past_reports_moves_one_way() = runComposeUiTest {
        panel()
        val backward = scrollBack(
            listOf(9f, 41f, 95f, 149f, 187f, 189f, 170f, 159f, 140f, 124f, 118f, 109f, 104f, 100f, 90f, 85f, 81f, 72f, 64f, 58f),
        )
        assertTrue(backward.isEmpty(), "the chat jumped toward the end while scrolling back:\n" + backward.joinToString("\n"))
        assertTrue(onAllNodesWithText("Earlier paragraph 1:", substring = true).fetchSemanticsNodes().isNotEmpty(), "the scroll reached the first message")
    }

    /** Slowly off the end: the report above the last two replies used to send the chat back to its end. */
    @Test fun a_slow_scroll_back_from_the_end_gets_past_a_report() = runComposeUiTest {
        panel()
        val backward = scrollBack(List(30) { 40f })
        assertTrue(backward.isEmpty(), "the chat jumped toward the end while scrolling back:\n" + backward.joinToString("\n"))
        assertTrue(onAllNodesWithText("Between paragraph 10:", substring = true).fetchSemanticsNodes().isNotEmpty(), "the scroll got past the second report")
    }
}
