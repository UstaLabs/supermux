package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.Subagent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T3: the card as a thread, truthful actions, names, the unread badge and the main-chat marker. */
@OptIn(ExperimentalTestApi::class)
class SubagentThreadTest {

    private fun m(ts: Int, direction: String, text: String, sender: String? = null) = TimelineItem.Activity(
        ActivityEvent(ts = "2026-10-04T10:00:${ts.toString().padStart(2, '0')}.000Z", kind = "subagent_message", title = text.lineSequence().first(), text = text, direction = direction, sender = sender, subagentId = "a"),
    )

    private fun tool(ts: Int, n: Int) = TimelineItem.Tool(
        ActivityEvent(ts = "2026-10-04T10:00:${ts.toString().padStart(2, '0')}.000Z", kind = "tool", tool = "Read", title = "Read: f$n", callId = "c$n", subagentId = "a"),
        ToolStatus.DONE,
    )

    private val codex = Subagent(
        id = "01a10304-7c2e", name = "Anscombe", description = "Check the failing migration", status = "running",
        messaging = "direct", canMessageFlag = true, canStopFlag = true, replies = 3, startedAt = 1,
    )

    private val children = listOf(
        m(0, "to", "Find why the users migration fails.", "parent"),
        tool(1, 1), tool(2, 2),
        m(3, "from", "It adds NOT NULL without a default."),
        m(4, "to", "Only users?", "user"),
        m(5, "from", "Only users."),
        m(6, "to", "Fix?", "user"),
        tool(7, 3),
        m(8, "from", "Add DEFAULT 'free'."),
    )

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalPlatform provides FakePlatform()) { SupermuxTheme { content() } }
    }

    @Test
    fun thread_is_task_then_messages_replies_and_steps_in_time_order() {
        val t = threadOf(codex, children)
        assertEquals(
            listOf("Task", "Steps", "Reply", "Mine", "Reply", "Mine", "Steps", "Reply"),
            t.map { it::class.simpleName },
        )
        assertEquals(2, (t[1] as ThreadEntry.Steps).tools.size)
    }

    @Test
    fun the_result_is_not_repeated_when_the_thread_already_ends_with_it() {
        val done = codex.copy(status = "completed", result = "Add DEFAULT 'free'.")
        assertTrue(threadOf(done, children).none { it is ThreadEntry.Result })
        val other = codex.copy(status = "completed", result = "A separate final summary.")
        assertTrue(threadOf(other, children).last() is ThreadEntry.Result)
    }

    @Test
    fun prompt_stands_in_for_a_missing_task_row() {
        val t = threadOf(codex.copy(prompt = "Do the thing"), listOf(m(1, "from", "Done")))
        assertEquals(ThreadEntry.Task("Do the thing"), t.first())
    }

    @Test
    fun names_real_name_first_types_as_tags() {
        assertEquals("Anscombe", codex.displayName)
        val claude = Subagent(id = "x1234567890", name = "general-purpose", description = "Run the tests")
        assertEquals("Run the tests", claude.displayName)
        assertEquals("general-purpose", claude.typeTag)
        assertEquals("Explore", Subagent(id = "y", name = "Explore").typeTag)
        assertEquals("x1234567", Subagent(id = "x1234567890").displayName)
    }

    @Test
    fun ended_wording_says_who_ended_it() {
        assertEquals("closed by the main agent", endedLabel(codex.copy(status = "cancelled", endedBy = "parent")))
        assertEquals("stopped by you", endedLabel(codex.copy(status = "cancelled", endedBy = "client")))
        assertEquals("done", endedLabel(codex.copy(status = "completed", endedBy = "self")))
        assertEquals("failed", endedLabel(codex.copy(status = "failed")))
    }

    @Test
    fun marker_body_drops_the_broker_prefix() {
        assertEquals("Only users?", markerBody("↪ to Anscombe: Only users?"))
        assertEquals("plain", markerBody("plain"))
    }

    @Test
    fun expanded_card_renders_the_thread_with_a_reply_box() = runComposeUiTest {
        setContent { Themed { SubagentCard(TimelineItem.SubagentCard(codex, children = children), expanded = true, onToggle = {}, actions = SubagentActions()) } }
        // Name first, description under it; each reply is signed with the name too.
        onNodeWithTag("subagent-card-header:01a10304-7c2e").assertTextContains("Anscombe").assertTextContains("Check the failing migration")
        assertEquals(4, onAllNodesWithText("Anscombe").fetchSemanticsNodes().size)
        onNodeWithTag("subagent-task:01a10304-7c2e").assertExists()
        onNodeWithText("Only users?").assertExists()
        onNodeWithText("Fix?").assertExists()
        onNodeWithTag("subagent-message-field:01a10304-7c2e").assertExists()
        onNodeWithTag("subagent-stop:01a10304-7c2e").assertExists()
    }

    @Test
    fun flags_off_show_reasons_not_controls_and_flip_live() = runComposeUiTest {
        var s by mutableStateOf(codex)
        setContent { Themed { SubagentCard(TimelineItem.SubagentCard(s, children = children), expanded = true, onToggle = {}, actions = SubagentActions()) } }
        onNodeWithTag("subagent-message-field:01a10304-7c2e").assertExists()
        // The main agent closes it: the update turns both flags off.
        s = codex.copy(status = "cancelled", endedBy = "parent", canMessageFlag = false, canStopFlag = false,
            cannotMessageReason = "Closed by the main agent — it can't take messages")
        waitForIdle()
        onNodeWithTag("subagent-message-field:01a10304-7c2e").assertDoesNotExist()
        onNodeWithTag("subagent-stop:01a10304-7c2e").assertDoesNotExist()
        onNodeWithText("Closed by the main agent — it can't take messages").assertIsDisplayed()
        onNodeWithText("closed by the main agent").assertIsDisplayed()
    }

    @Test
    fun cursor_running_offers_message_but_explains_no_stop() = runComposeUiTest {
        val cursor = codex.copy(id = "k", name = "Licence auditor", messaging = "relay", canStopFlag = false, cannotStopReason = "Cursor can't stop subagents")
        setContent { Themed { SubagentCard(TimelineItem.SubagentCard(cursor), expanded = true, onToggle = {}, actions = SubagentActions()) } }
        onNodeWithTag("subagent-message-field:k").assertExists()
        onNodeWithTag("subagent-relay-hint:k").assertExists()
        onNodeWithTag("subagent-stop:k").assertDoesNotExist()
        onNodeWithText("Cursor can't stop subagents").assertIsDisplayed()
    }

    @Test
    fun badge_counts_unseen_replies_only_while_collapsed() = runComposeUiTest {
        var expanded by mutableStateOf(false)
        setContent { Themed { SubagentCard(TimelineItem.SubagentCard(codex), expanded = expanded, onToggle = { expanded = !expanded }, actions = SubagentActions(), unread = 2) } }
        onNodeWithText("2 new replies").assertIsDisplayed()
        onNodeWithTag("subagent-card-header:01a10304-7c2e").performClick()
        onNodeWithTag("subagent-unread:01a10304-7c2e").assertDoesNotExist()
        assertEquals(2, codex.copy(replies = 5).unreadReplies(3))
    }

    @Test
    fun marker_names_the_agent_and_jumps() = runComposeUiTest {
        var opened = 0
        setContent { Themed { SubagentMarker("in:web:1", "↪ to Anscombe: Only users?", name = "Anscombe", onOpen = { opened++ }) } }
        onNodeWithText("To Anscombe").assertIsDisplayed()
        onNodeWithText("Only users?").assertIsDisplayed()
        onNodeWithTag("subagent-marker:in:web:1").performClick()
        assertEquals(1, opened)
    }
}
