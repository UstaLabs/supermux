package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The transcript follows the end only while the reader is AT the end. A reply taller than the
 * screen (screenshots, then a long write-up) is the case that broke it: anywhere inside the last two
 * rows used to count as "at the bottom", so scrolling back into the last reply — or the reply
 * growing, or a new message landing — yanked the reader to the very end of it.
 */
@OptIn(ExperimentalTestApi::class)
class ChatAutoscrollTest {

    private val session =
        SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")

    private fun entry(n: Int, text: String, direction: String = "outbound") =
        LogEntry(id = "m$n", ts = "2026-01-01T00:00:%02dZ".format(n), direction = direction, text = text)

    private fun paragraphs(label: String, count: Int) = (1..count).joinToString("\n\n") {
        "$label paragraph $it: the agent explains one more part of the new interface in a full sentence."
    }

    private val history = (1..4).map { entry(it, "short message $it", if (it % 2 == 1) "inbound" else "outbound") }

    /** The next-to-last row: taller than the screen, like a message carrying a stack of screenshots. */
    private val shots = entry(5, paragraphs("Shot", 18))

    /** The last row: several screens tall. */
    private val reply = entry(6, paragraphs("Reply", 40) + "\n\n$END")

    private var state by mutableStateOf(ChatState())

    private fun ComposeUiTest.panel() {
        state = ChatState(messages = history + shots + reply)
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            Box(Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
                ChatPanel(
                    session = session,
                    state = state,
                    actions = ChatActions(),
                    draft = "",
                    onDraftChange = {},
                    showHeader = false,
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.bodyTop() = onNodeWithTag("chat_body").fetchSemanticsNode().boundsInRoot.top

    /**
     * The transcript's visible height: from the top of the chat body down to the composer dock, or to
     * the body's bottom while the dock is tucked away (a zero-height node reports empty bounds at 0,0).
     */
    private fun ComposeUiTest.transcriptHeight(): Float {
        val body = onNodeWithTag("chat_body").fetchSemanticsNode().boundsInRoot
        val dock = onNodeWithTag("chat_composer_dock").fetchSemanticsNode().boundsInRoot
        return (if (dock.height > 0f) dock.top else body.bottom) - body.top
    }

    /** The top of the first node showing [text], relative to the transcript's top; null when it is not laid out. */
    private fun ComposeUiTest.topOf(text: String): Float? =
        onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull()?.let { it.positionInRoot.y - bodyTop() }

    private fun ComposeUiTest.onScreen(text: String): Boolean {
        val top = topOf(text) ?: return false
        return top >= 0f && top < transcriptHeight()
    }

    /**
     * Where a finger goes down on the transcript: left of the jump-to-bottom button (bottom centre),
     * near the top for a downward drag and above the composer for an upward one.
     */
    private fun ComposeUiTest.fingerAt(downward: Boolean): Offset {
        val left = onNodeWithTag("chat_body").fetchSemanticsNode().boundsInRoot.width * 0.2f
        return if (downward) Offset(left, 20f) else Offset(left, transcriptHeight() - 60f)
    }

    /**
     * A finger drag that holds still before it lifts, so it ends without a fling. Positive [dy] moves
     * the finger down: the transcript scrolls UP, toward older messages.
     */
    private fun ComposeUiTest.drag(dy: Float) {
        val start = fingerAt(downward = dy > 0)
        onNodeWithTag("chat_body").performTouchInput {
            down(start)
            repeat(10) { moveBy(Offset(0f, dy / 10)) }
            advanceEventTime(300)
            up()
        }
        waitForIdle()
    }

    /** The first reply paragraph on screen right now: where a reader inside the reply is looking. */
    private fun ComposeUiTest.readingAnchor(): String =
        (1..40).map { "Reply paragraph $it:" }.firstOrNull { onScreen(it) }
            ?: fail("no paragraph of the last reply is on screen")

    /** Scroll up about two screens: inside the last reply, well away from its end. */
    private fun ComposeUiTest.readInsideTheLastReply(): String {
        repeat(4) { drag(+300f) }
        assertFalse(onScreen(END), "scrolled away from the end")
        return readingAnchor()
    }

    @Test fun scrolling_back_into_a_tall_last_reply_does_not_jump_to_its_end() = runComposeUiTest {
        panel()
        assertTrue(onScreen(END), "the chat opens at the end of the last reply")

        // Up past the top of the last reply, until none of it is laid out (the screenshots fill the screen)…
        var guard = 0
        while (topOf("Reply paragraph") != null) {
            drag(+300f)
            if (guard++ > 40) fail("never scrolled above the last reply")
        }
        assertTrue(topOf("Shot paragraph") != null, "the row before it is on screen")

        // …then back down, the finger still on the glass, until the reply's opening is on screen again.
        val body = onNodeWithTag("chat_body")
        val start = fingerAt(downward = false)
        body.performTouchInput { down(start) }
        guard = 0
        while (!onScreen("Reply paragraph 1:")) {
            body.performTouchInput { repeat(5) { moveBy(Offset(0f, -12f)) } }
            waitForIdle()
            if (guard++ > 80) fail("the last reply never came back into view")
        }
        val held = topOf("Reply paragraph 1:")!!

        // Let go. Nothing may move the transcript: its end is screens away.
        body.performTouchInput {
            advanceEventTime(300)
            up()
        }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        assertFalse(onScreen(END), "the transcript jumped to the end of the reply")
        assertEquals(held, topOf("Reply paragraph 1:") ?: Float.NaN, 1f, "the transcript stays where the finger left it")
    }

    @Test fun the_last_reply_growing_does_not_move_a_reader_inside_it() = runComposeUiTest {
        panel()
        val anchor = readInsideTheLastReply()
        val before = topOf(anchor)!!

        state = state.copy(messages = history + shots + reply.copy(text = reply.text + "\n\nA paragraph that arrived late."))
        waitForIdle()

        assertEquals(before, topOf(anchor) ?: Float.NaN, 1f, "growth below the reader moved the transcript")
    }

    @Test fun a_new_message_does_not_move_a_reader_inside_a_tall_last_reply() = runComposeUiTest {
        panel()
        val anchor = readInsideTheLastReply()
        val before = topOf(anchor)!!

        state = state.copy(messages = history + shots + reply + entry(7, "A new message arrived."))
        waitForIdle()

        assertEquals(before, topOf(anchor) ?: Float.NaN, 1f, "a new message moved the transcript")
        assertFalse(onScreen("A new message arrived."))
    }

    @Test fun at_the_end_the_transcript_follows_growth_and_new_messages() = runComposeUiTest {
        panel()
        assertTrue(onScreen(END), "the chat opens at the end of the last reply")

        state = state.copy(messages = history + shots + reply.copy(text = reply.text + "\n\nA paragraph that arrived late."))
        waitForIdle()
        assertTrue(onScreen("A paragraph that arrived late."), "the growing reply keeps its end in view")

        state = state.copy(messages = state.messages + entry(7, "A new message arrived."))
        waitForIdle()
        assertTrue(onScreen("A new message arrived."), "a new message is followed")
    }

    @Test fun scrolling_back_to_the_end_follows_again() = runComposeUiTest {
        panel()
        readInsideTheLastReply()

        var guard = 0
        while (!onScreen(END)) {
            drag(-300f)
            if (guard++ > 20) fail("never got back to the end")
        }
        repeat(2) { drag(-300f) } // and on to the very end

        state = state.copy(messages = state.messages + entry(7, "A new message arrived."))
        waitForIdle()
        assertTrue(onScreen("A new message arrived."), "back at the end, a new message is followed again")
    }

    // Card #76: the reply while it is written.
    private fun live(text: String) = dev.supermux.state.LiveReply(stream = "k1", kind = "reply", text = text)

    @Test fun at_the_end_a_live_reply_is_followed_as_it_grows() = runComposeUiTest {
        panel()
        state = state.copy(live = listOf(live("Live paragraph one.")))
        waitForIdle()
        assertTrue(onScreen("Live paragraph one."), "the live reply is followed into view")
        state = state.copy(live = listOf(live("Live paragraph one.\n\n" + paragraphs("Live", 12) + "\n\nLive tail.")))
        waitForIdle()
        assertTrue(onScreen("Live tail."), "its growing end stays in view")
    }

    @Test fun a_live_reply_growing_does_not_move_a_reader_inside_the_last_reply() = runComposeUiTest {
        panel()
        val anchor = readInsideTheLastReply()
        val before = topOf(anchor)!!
        state = state.copy(live = listOf(live("Live paragraph one.")))
        waitForIdle()
        state = state.copy(live = listOf(live("Live paragraph one.\n\nLive paragraph two.")))
        waitForIdle()
        assertEquals(before, topOf(anchor) ?: Float.NaN, 1f, "live text below the reader moved the transcript")
    }

    @Test fun the_stored_message_takes_the_live_replys_place_without_a_jump() = runComposeUiTest {
        panel()
        state = state.copy(live = listOf(live("Live paragraph one.\n\nThe live end.")))
        waitForIdle()
        val before = topOf("Live paragraph one.")!!
        state = state.copy(
            messages = state.messages + entry(7, "Live paragraph one.\n\nThe live end.").copy(stream = "k1"),
            live = emptyList(),
        )
        waitForIdle()
        // Same row, same place: at most the finished message's time / copy row is added below, and the
        // transcript, being at its end, follows that.
        val after = topOf("Live paragraph one.") ?: fail("the stored reply is not laid out")
        assertTrue(kotlin.math.abs(after - before) <= 64f, "the swap moved the reply from $before to $after")
        assertTrue(onScreen("The live end."), "the end stays in view")
    }

    private companion object {
        const val END = "The very end of the reply."
    }
}
