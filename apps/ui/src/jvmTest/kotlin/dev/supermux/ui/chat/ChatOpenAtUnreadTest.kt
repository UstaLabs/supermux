package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Card #121, as Ahmet approved it: a chat opens in place, at its first unread message under a "N new
 * messages" line, or at its end when nothing is unread — never with a visible scroll. "Unread" is the
 * chat's read mark when it was opened (the broker's, the one behind the unread dots).
 */
@OptIn(ExperimentalTestApi::class)
class ChatOpenAtUnreadTest {
    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")

    private fun ts(n: Int) = "2026-01-01T00:%02d:00Z".format(n)
    private fun msg(n: Int, direction: String = "outbound") =
        LogEntry(id = "m$n", ts = ts(n), direction = direction, text = "message number $n of the chat")

    private var state by mutableStateOf(ChatState())
    /** Whether the kept-alive chat is the one on screen (what the keep-alive hosts provide). */
    private var onScreenNow by mutableStateOf(true)

    private fun ComposeUiTest.open() {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            androidx.compose.runtime.CompositionLocalProvider(dev.supermux.ui.keys.LocalKeyScopeActive provides onScreenNow) {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                    Box(Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
                        ChatPanel(session = session, state = state, actions = ChatActions(), draft = "", onDraftChange = {}, showHeader = false)
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.lineShown(): Boolean =
        onAllNodesWithText("new message", substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.bodyTop() = onNodeWithTag("chat_body").fetchSemanticsNode().boundsInRoot.top
    private fun ComposeUiTest.topOf(text: String): Float? =
        onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull()?.let { it.positionInRoot.y - bodyTop() }
    private fun ComposeUiTest.onScreen(text: String): Boolean {
        val top = topOf(text) ?: return false
        val body = onNodeWithTag("chat_body").fetchSemanticsNode().boundsInRoot
        val dock = onNodeWithTag("chat_composer_dock").fetchSemanticsNode().boundsInRoot
        val height = (if (dock.height > 0f) dock.top else body.bottom) - body.top
        return top >= 0f && top < height
    }

    @Test fun a_chat_with_unread_messages_opens_at_the_first_one_under_a_new_messages_line() = runComposeUiTest {
        state = ChatState(messages = (1..40).map(::msg), readMarkAtOpen = ts(30))
        mainClock.autoAdvance = false
        open()
        var firstTop: Float? = null
        var moved = 0f
        repeat(30) {
            mainClock.advanceTimeByFrame()
            val top = topOf("message number 31 of") ?: return@repeat
            if (firstTop == null) firstTop = top else moved = maxOf(moved, kotlin.math.abs(top - firstTop!!))
        }
        assertTrue(onScreen("message number 31 of"), "the first unread message is on screen")
        assertTrue(onScreen("10 new messages"), "the line above it says how many are new")
        assertFalse(onScreen("message number 10 of"), "the chat did not open at its top")
        assertTrue(moved < 2f, "the first unread moved $moved px after it appeared: a visible scroll")
        val line = topOf("10 new messages")!!
        assertTrue(line < topOf("message number 31 of")!!, "the line sits above the first unread message")
        mainClock.autoAdvance = true
    }

    @Test fun the_users_own_messages_are_not_counted_as_new() = runComposeUiTest {
        val log = (1..30).map(::msg) + msg(31, "inbound") + msg(32) + msg(33)
        state = ChatState(messages = log, readMarkAtOpen = ts(30))
        mainClock.autoAdvance = false
        open()
        repeat(5) { mainClock.advanceTimeByFrame() }
        assertTrue(onScreen("2 new messages"))
        mainClock.autoAdvance = true
    }

    @Test fun a_chat_with_nothing_unread_opens_at_its_end_without_a_line() = runComposeUiTest {
        state = ChatState(messages = (1..40).map(::msg), readMarkAtOpen = ts(40))
        open()
        waitForIdle()
        assertTrue(onScreen("message number 40 of"))
        assertEquals(0, onAllNodesWithText("new message", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun more_unread_than_a_screen_offers_a_jump_with_the_count() = runComposeUiTest {
        state = ChatState(messages = (1..60).map(::msg), readMarkAtOpen = ts(10))
        open()
        waitForIdle()
        assertTrue(onScreen("50 new messages"))
        onNodeWithTag("chat_jump_new_count").assertTextContains("50")
    }

    @Test fun the_line_stays_while_the_new_messages_are_unseen_even_after_the_chat_is_marked_read() = runComposeUiTest {
        // More than a screen is new: the last new message has not been on screen yet.
        state = ChatState(messages = (1..60).map(::msg), readMarkAtOpen = ts(10))
        open()
        waitForIdle()
        state = state.copy(readMarkAtOpen = ts(60))
        mainClock.advanceTimeBy(5_000)
        waitForIdle()
        assertTrue(onScreen("50 new messages"))
    }

    @Test fun the_line_fades_once_the_new_messages_have_been_on_screen() = runComposeUiTest {
        // Card #121 round 3: it stayed until the chat was left; Ahmet wants it gone once he has seen them.
        state = ChatState(messages = (1..40).map(::msg), readMarkAtOpen = ts(37))
        mainClock.autoAdvance = false
        open()
        repeat(10) { mainClock.advanceTimeByFrame() }
        assertTrue(lineShown(), "the line shows at first")
        mainClock.advanceTimeBy(1_000)
        assertTrue(lineShown(), "it stays a moment")
        mainClock.advanceTimeBy(3_000)
        mainClock.autoAdvance = true
        waitForIdle()
        assertFalse(lineShown(), "it is gone a moment after the new messages were on screen")
        assertTrue(onScreen("message number 40 of"), "fading it moved nothing out of view")
    }

    @Test fun scrolling_to_the_last_new_message_lets_the_line_fade() = runComposeUiTest {
        state = ChatState(messages = (1..60).map(::msg), readMarkAtOpen = ts(10))
        open()
        waitForIdle()
        onNodeWithTag("chat_jump_to_bottom").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(4_000)
        waitForIdle()
        // Back up the chat: the jump button returns, without a count, since every new message was seen.
        repeat(3) { onNodeWithTag("chat_body").performTouchInput { swipeDown() }; waitForIdle() }
        assertEquals(1, onAllNodesWithTag("chat_jump_to_bottom").fetchSemanticsNodes().size, "the jump button is back")
        assertEquals(0, onAllNodesWithTag("chat_jump_new_count").fetchSemanticsNodes().size, "and it counts nothing as new")
    }

    @Test fun a_kept_alive_chat_coming_back_on_screen_is_a_new_opening() = runComposeUiTest {
        // Card #121 round 3: a kept-alive chat kept its first opening's line (and place) until the app restarted.
        state = ChatState(messages = (1..40).map(::msg), readMarkAtOpen = ts(30))
        open()
        waitForIdle()
        onScreenNow = false
        waitForIdle()
        // Viewing marked it read up to 40; then five more arrived while it was hidden.
        state = ChatState(messages = (1..45).map(::msg), readMarkAtOpen = ts(40))
        waitForIdle()
        mainClock.autoAdvance = false
        onScreenNow = true
        repeat(5) { mainClock.advanceTimeByFrame() }
        assertTrue(onScreen("5 new messages"), "the new opening counts only what came while it was away")
        assertTrue(onScreen("message number 41 of"))
        mainClock.autoAdvance = true
    }
}
