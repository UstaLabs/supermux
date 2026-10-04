package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.net.SubagentActionResult
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.Subagent
import dev.supermux.proto.SubagentStats
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Composable
private fun Themed(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlatform provides FakePlatform()) { SupermuxTheme { content() } }
}

/** The subagent card (states, expansion, actions) and the running strip + its pure helpers. */
@OptIn(ExperimentalTestApi::class)
class SubagentCardTest {

    private val now = 1_790_600_000_000L

    private fun sub(status: String = "running", messaging: String? = "relay", extra: (Subagent) -> Subagent = { it }) = extra(
        Subagent(
            id = "sa1",
            name = "Explore",
            description = "Explore the auth flow",
            prompt = "Map how sessions are created.",
            status = status,
            activity = "Reading src/auth/session.ts",
            stats = SubagentStats(toolCalls = 14, tokens = 23_000),
            messaging = messaging,
            startedAt = now - 134_000,
            endedAt = if (status == "running") null else now,
            parentCallId = "p1",
        ),
    )

    private fun child(n: Int) = TimelineItem.Tool(
        ActivityEvent(ts = "2026-09-28T10:00:0$n.000Z", kind = "tool", tool = "Read", title = "Read: f$n.ts", callId = "c$n", subagentId = "sa1"),
        ToolStatus.DONE,
    )

    @Composable
    private fun Card(item: TimelineItem.SubagentCard, actions: SubagentActions = SubagentActions(), startOpen: Boolean = false) {
        var open by remember { mutableStateOf(startOpen) }
        Themed { SubagentCard(item, expanded = open, onToggle = { open = !open }, actions = actions) }
    }

    @Test
    fun running_card_shows_label_type_stats_and_live_activity_collapsed() = runComposeUiTest {
        setContent { Card(TimelineItem.SubagentCard(sub(), children = listOf(child(1)))) }
        onNodeWithText("Explore the auth flow").assertIsDisplayed()
        onNodeWithText("Explore").assertIsDisplayed()
        onNodeWithText("14 tool calls · 23k tokens").assertIsDisplayed()
        onNodeWithTag("subagent-activity:sa1", useUnmergedTree = true).assertIsDisplayed()
        // Collapsed by default: no body, no child rows.
        onNodeWithTag("subagent-card-body:sa1").assertDoesNotExist()
    }

    @Test
    fun finished_states_read_done_failed_stopped() = runComposeUiTest {
        var status by mutableStateOf("completed")
        setContent { Card(TimelineItem.SubagentCard(sub(status))) }
        onNodeWithText("done · 2m 14s").assertIsDisplayed()
        onNodeWithTag("subagent-activity:sa1", useUnmergedTree = true).assertDoesNotExist()
        status = "failed"
        waitForIdle()
        onNodeWithText("failed · 2m 14s").assertIsDisplayed()
        status = "cancelled"
        waitForIdle()
        onNodeWithText("stopped").assertIsDisplayed()
    }

    @Test
    fun expanding_shows_prompt_nested_rows_and_result() = runComposeUiTest {
        val s = sub("completed") { it.copy(result = "The flow is **fine**.") }
        setContent { Card(TimelineItem.SubagentCard(s, children = listOf(child(1), child(2)))) }
        onNodeWithTag("subagent-card-header:sa1").performClick()
        onNodeWithTag("subagent-card-body:sa1").assertExists()
        onNodeWithText("Map how sessions are created.").assertExists()
        onNodeWithText("Task from the main agent").assertExists()
        onNodeWithText("Result").assertExists()
    }

    @Test
    fun message_calls_messageSubagent_with_the_text() = runComposeUiTest {
        var sent: Pair<String, String>? = null
        val actions = SubagentActions(message = { id, text -> sent = id to text; SubagentActionResult(ok = true, via = "relay") })
        setContent { Card(TimelineItem.SubagentCard(sub()), actions, startOpen = true) }
        // The reply box is simply there while it can take a message — no extra "Message" step.
        onNodeWithTag("subagent-relay-hint:sa1").assertExists()
        onNodeWithTag("subagent-message-field:sa1").performTextInput("check logout too")
        onNodeWithTag("subagent-message-send:sa1").performClick()
        waitForIdle()
        assertEquals("sa1" to "check logout too", sent)
        onNodeWithTag("subagent-note:sa1").assertExists()
    }

    @Test
    fun stop_calls_stopSubagent_and_a_refusal_shows_inline() = runComposeUiTest {
        var stopped: String? = null
        val actions = SubagentActions(stop = { id -> stopped = id; SubagentActionResult(ok = false, error = "cursor cannot stop a subagent") })
        setContent { Card(TimelineItem.SubagentCard(sub()), actions, startOpen = true) }
        onNodeWithTag("subagent-stop:sa1").performClick()
        waitForIdle()
        assertEquals("sa1", stopped)
        onNodeWithText("cursor cannot stop a subagent").assertIsDisplayed()
    }

    @Test
    fun no_message_affordance_when_messaging_is_none_and_no_stop_when_done() = runComposeUiTest {
        setContent { Card(TimelineItem.SubagentCard(sub("completed", messaging = "none")), startOpen = true) }
        onNodeWithTag("subagent-message-field:sa1").assertDoesNotExist()
        onNodeWithTag("subagent-stop:sa1").assertDoesNotExist()
    }

    @Test
    fun placeholder_takes_its_status_from_the_finished_spawn_row() {
        val spawn = TimelineItem.Tool(ActivityEvent(ts = "t", kind = "tool", tool = "Agent", description = "Look around", callId = "p9"), ToolStatus.DONE)
        val s = effectiveSubagent(TimelineItem.SubagentCard(Subagent(id = "x"), spawn = spawn))
        assertEquals("completed", s.status)
        assertEquals("Look around", s.label)
    }

    @Test
    fun strip_merges_subagents_and_bg_tasks_without_duplicates() {
        val subs = listOf(sub(), sub("completed") { it.copy(id = "sa2", parentCallId = "p2") })
        val tasks = listOf(
            ServerFrame.BgTask(id = "t1", kind = "shell", label = "bun run dev", startedAt = now, status = "running"),
            ServerFrame.BgTask(id = "t2", kind = "agent", label = "Explore the auth flow", startedAt = now, callId = "p1"),
            ServerFrame.BgTask(id = "t3", kind = "shell", label = "old", status = "completed"),
        )
        val items = runningItems(subs, tasks)
        assertEquals(listOf("s:sa1", "t:t1"), items.map { it.key })
        assertEquals("sa1", items[0].subagentId)
        assertNull(items[1].subagentId)
    }

    @Test
    fun strip_folds_past_two_and_jumps_to_the_card() = runComposeUiTest {
        var opened: String? = null
        val subs = (1..4).map { i -> sub { it.copy(id = "a$i", description = "Agent $i", startedAt = now - i * 1000) } }
        setContent { Themed { RunningStrip(subs, emptyList(), onOpenSubagent = { opened = it }) } }
        onNodeWithText("4 agents running").assertIsDisplayed()
        onNodeWithTag("subagent-strip-row:a4").assertExists()
        onNodeWithTag("subagent-strip-row:a1").assertDoesNotExist()
        onNodeWithTag("subagent-strip-more").performClick()
        onNodeWithTag("subagent-strip-row:a1").assertExists().performClick()
        assertEquals("a1", opened)
    }

    @Test
    fun strip_is_absent_when_nothing_runs() = runComposeUiTest {
        setContent { Themed { RunningStrip(listOf(sub("completed")), emptyList(), onOpenSubagent = {}) } }
        onNodeWithTag("subagent-strip").assertDoesNotExist()
    }

    @Test
    fun compact_formats() {
        assertEquals("12s", compactElapsed(12_400))
        assertEquals("2m 14s", compactElapsed(134_000))
        assertEquals("1h 03m", compactElapsed(3_780_000))
        assertEquals("950", compactCount(950))
        assertEquals("2.3k", compactCount(2_340))
        assertEquals("23k", compactCount(23_000))
        assertEquals("1.2M", compactCount(1_250_000))
        assertEquals("1 tool call", subagentStatsLine(Subagent(id = "x", stats = SubagentStats(toolCalls = 1))))
        assertNull(subagentStatsLine(Subagent(id = "x")))
        assertTrue(runningSubagentCount(listOf(sub(), sub("failed"))) == 1)
    }
}
