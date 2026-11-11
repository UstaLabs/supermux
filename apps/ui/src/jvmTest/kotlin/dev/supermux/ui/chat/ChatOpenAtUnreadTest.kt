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

    private fun ComposeUiTest.open() {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                Box(Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
                    ChatPanel(session = session, state = state, actions = ChatActions(), draft = "", onDraftChange = {}, showHeader = false)
                }
            }
        }
    }

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
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(onScreen("message number 31 of"), "the first unread message is on screen")
        assertTrue(onScreen("10 new messages"), "the line above it says how many are new")
        assertFalse(onScreen("message number 10 of"), "the chat did not open at its top")
        assertTrue(moved < 2f, "the first unread moved $moved px after it appeared: a visible scroll")
        val line = topOf("10 new messages")!!
        assertTrue(line < topOf("message number 31 of")!!, "the line sits above the first unread message")
    }

    @Test fun the_users_own_messages_are_not_counted_as_new() = runComposeUiTest {
        val log = (1..30).map(::msg) + msg(31, "inbound") + msg(32) + msg(33)
        state = ChatState(messages = log, readMarkAtOpen = ts(30))
        open()
        waitForIdle()
        assertTrue(onScreen("2 new messages"))
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

    @Test fun the_line_stays_while_the_chat_is_open_even_after_it_is_marked_read() = runComposeUiTest {
        state = ChatState(messages = (1..40).map(::msg), readMarkAtOpen = ts(30))
        open()
        waitForIdle()
        state = state.copy(readMarkAtOpen = ts(40))
        waitForIdle()
        assertTrue(onScreen("10 new messages"))
    }
}
