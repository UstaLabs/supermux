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
    fun strip_folds_to_one_line_past_one_and_jumps_to_the_card() = runComposeUiTest {
        var opened: String? = null
        val subs = (1..2).map { i -> sub { it.copy(id = "a$i", description = "Agent $i", startedAt = now - i * 1000) } }
        setContent { Themed { RunningStrip(subs, emptyList(), onOpenSubagent = { opened = it }) } }
        onNodeWithText("2 agents running").assertIsDisplayed()
        onNodeWithTag("subagent-strip-row:a2").assertDoesNotExist()
        onNodeWithTag("subagent-strip-row:a1").assertDoesNotExist()
        onNodeWithTag("subagent-strip-more").performClick()
        onNodeWithTag("subagent-strip-row:a1").assertExists().performClick()
        assertEquals("a1", opened)
    }

    @Test
    fun strip_shows_a_lone_item_as_its_row() = runComposeUiTest {
        setContent { Themed { RunningStrip(listOf(sub { it.copy(id = "a1") }), emptyList(), onOpenSubagent = {}) } }
        onNodeWithTag("subagent-strip-row:a1").assertExists()
        onNodeWithTag("subagent-strip-more").assertDoesNotExist()
    }

    private fun iso(ms: Long) = ms.toString() // parseChatTs reads epoch millis as-is

    @Test
    fun lane_bars_are_its_tool_calls_with_the_live_one_reaching_now() {
        val t0 = 1_700_000_000_000L
        val s = Subagent(id = "sa1", status = "running", startedAt = t0)
        val rows = listOf(
            ActivityEvent(ts = iso(t0 + 1_000), kind = "tool", tool = "Read", title = "Read: a.kt", detail = "/w/a.kt", callId = "c1", subagentId = "sa1"),
            ActivityEvent(ts = iso(t0 + 2_000), kind = "tool_result", callId = "c1", subagentId = "sa1", phase = "completed"),
            ActivityEvent(ts = iso(t0 + 2_500), kind = "tool", tool = "Read", title = "Read: b.kt", detail = "/w/b.kt", callId = "c2", subagentId = "sa1"),
            ActivityEvent(ts = iso(t0 + 3_000), kind = "tool_result", callId = "c2", subagentId = "sa1", phase = "completed"),
            ActivityEvent(ts = iso(t0 + 10_000), kind = "tool", tool = "Bash", title = "Bash: npm test", detail = "npm test", callId = "c3", subagentId = "sa1"),
            ActivityEvent(ts = iso(t0 + 11_000), kind = "tool", tool = "Read", title = "parent", callId = "p1"),
        )
        val bars = laneBars(s, rows, now = t0 + 20_000)
        // Every call keeps its own bar and one label; the gaps (>= 400ms) are thinking.
        assertEquals(listOf(true, false, true, false, true, false), bars.map { it.thinking })
        val tools = bars.filterNot { it.thinking }
        assertEquals(listOf("Read a.kt", "Read b.kt", "npm test"), tools.map { it.label })
        assertEquals(t0 + 1_000 to t0 + 2_000, tools[0].start to tools[0].end)
        assertEquals(t0 + 3_000 to t0 + 10_000, bars[4].start to bars[4].end)
        assertTrue(tools[2].live)
        assertEquals(t0 + 20_000, tools[2].end)
    }

    @Test
    fun a_running_lane_with_no_call_in_flight_is_thinking_up_to_now() {
        val t0 = 1_700_000_000_000L
        val s = Subagent(id = "sa1", status = "running", startedAt = t0)
        val rows = listOf(
            ActivityEvent(ts = iso(t0 + 1_000), kind = "tool", tool = "Read", detail = "/w/a.kt", callId = "c1", subagentId = "sa1"),
            ActivityEvent(ts = iso(t0 + 2_000), kind = "tool_result", callId = "c1", subagentId = "sa1", phase = "completed"),
        )
        val last = laneBars(s, rows, now = t0 + 9_000).last()
        assertTrue(last.thinking && last.live)
        assertEquals(t0 + 2_000 to t0 + 9_000, last.start to last.end)
    }

    @Test
    fun calls_in_flight_together_stack_into_rows_and_each_ends_at_its_own_result() {
        val t0 = 1_700_000_000_000L
        val s = Subagent(id = "sa1", status = "running", startedAt = t0)
        fun call(id: String, at: Long) = ActivityEvent(ts = iso(t0 + at), kind = "tool", tool = "WebFetch", detail = "https://$id.dev/x", callId = id, subagentId = "sa1")
        fun done(id: String, at: Long) = ActivityEvent(ts = iso(t0 + at), kind = "tool_result", callId = id, subagentId = "sa1", phase = "completed")
        val rows = listOf(call("a", 1_000), call("b", 1_200), call("c", 1_400), done("b", 3_000), call("d", 3_500), done("a", 6_000))
        val tools = laneBars(s, rows, now = t0 + 8_000).filterNot { it.thinking }
        assertEquals(listOf(0, 1, 2, 1), tools.map { it.row }) // d reuses b's row once b is done
        assertEquals(t0 + 6_000, tools[0].end) // a runs to ITS result, not to b's start
        assertTrue(tools[2].live && tools[3].live) // c and d are still in flight
    }

    @Test
    fun a_lane_without_rows_is_one_bar_for_the_whole_run() {
        val s = Subagent(id = "sa1", status = "completed", startedAt = 1_000, endedAt = 9_000, activity = "Reading x")
        assertEquals(listOf(LaneBar(1_000, 9_000, "Reading x")), laneBars(s, emptyList(), now = 50_000))
    }

    @Test
    fun the_last_15s_ticks_every_5s() {
        assertEquals(5_000L, laneTickStepMs(LANE_WINDOW_MS))
        assertEquals("−5m", laneTickLabel(300_000))
        assertEquals("−45s", laneTickLabel(45_000))
    }

    @Test
    fun a_lone_item_opens_its_lanes_only_when_expanded() = runComposeUiTest {
        val s = sub { it.copy(id = "a1", startedAt = System.currentTimeMillis() - 30_000) }
        setContent { Themed { RunningStrip(listOf(s), emptyList(), onOpenSubagent = {}) } }
        onNodeWithTag("subagent-lane-bar:a1:0", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("subagent-strip-lanes").performClick()
        onNodeWithTag("subagent-lane-bar:a1:0", useUnmergedTree = true).assertExists()
        onNodeWithTag("subagent-strip-more").performClick()
        onNodeWithTag("subagent-lane-bar:a1:0", useUnmergedTree = true).assertDoesNotExist()
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
