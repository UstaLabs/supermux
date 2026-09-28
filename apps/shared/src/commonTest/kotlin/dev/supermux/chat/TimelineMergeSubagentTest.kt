package dev.supermux.chat

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.LogEntry
import dev.supermux.proto.Subagent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Subagent grouping in [mergeTimeline]: child rows never reach the top level. */
class TimelineMergeSubagentTest {
    private fun tool(ts: String, callId: String, tool: String = "Bash", sub: String? = null) =
        ActivityEvent(ts = ts, kind = "tool", tool = tool, title = tool, phase = "started", callId = callId, subagentId = sub)

    private fun result(ts: String, callId: String, sub: String? = null) =
        ActivityEvent(ts = ts, kind = "tool_result", title = "done", phase = "completed", detail = "ok", callId = callId, subagentId = sub)

    private val msg = LogEntry(id = "m1", ts = "2026-09-28T10:00:00.000Z", direction = "inbound", text = "go")
    private val reply = LogEntry(id = "m2", ts = "2026-09-28T10:00:09.000Z", direction = "outbound", text = "PELICAN")

    @Test fun spawnRowAndChildRowsBecomeOneCard() {
        val sub = Subagent(id = "a1", parentCallId = "spawn", description = "Inspect work dir", startedAt = 0)
        val activity = listOf(
            tool("2026-09-28T10:00:01.000Z", "spawn", tool = "Agent"),
            tool("2026-09-28T10:00:02.000Z", "c1", sub = "a1"),
            ActivityEvent(ts = "2026-09-28T10:00:02.500Z", kind = "reasoning", title = "Thinking", subagentId = "a1"),
            result("2026-09-28T10:00:03.000Z", "c1", sub = "a1"),
            tool("2026-09-28T10:00:04.000Z", "c2", tool = "Read", sub = "a1"),
            result("2026-09-28T10:00:08.000Z", "spawn"),
        )
        val items = mergeTimeline(listOf(msg, reply), activity, subagents = listOf(sub))
        assertEquals(3, items.size)
        assertTrue(items[0] is TimelineItem.Msg)
        val card = items[1] as TimelineItem.SubagentCard
        assertTrue(items[2] is TimelineItem.Msg)
        assertEquals("a1", card.subagent.id)
        assertEquals("spawn", card.spawn!!.event.callId)
        assertEquals(ToolStatus.DONE, card.spawn!!.status)
        assertEquals(listOf("c1", null, "c2"), card.children.map { (it as? TimelineItem.Tool)?.event?.callId })
        assertEquals(ToolStatus.DONE, (card.children[0] as TimelineItem.Tool).status)
        assertEquals(ToolStatus.RUNNING, (card.children[2] as TimelineItem.Tool).status)
    }

    @Test fun childRowsWithoutAKnownSubagentStillGroupUnderAPlaceholder() {
        val items = mergeTimeline(emptyList(), listOf(tool("2026-09-28T10:00:02.000Z", "c1", sub = "ghost")))
        val card = items.single() as TimelineItem.SubagentCard
        assertEquals(Subagent(id = "ghost"), card.subagent)
        assertNull(card.spawn)
        assertEquals(1, card.children.size)
    }

    @Test fun codexSubagentWithoutSpawnRowSitsAtItsFirstChildRow() {
        val sub = Subagent(id = "t1", parentCallId = "collab-1", startedAt = 0)
        val items = mergeTimeline(
            listOf(msg, reply),
            listOf(tool("2026-09-28T10:00:05.000Z", "exec-1", sub = "t1")),
            subagents = listOf(sub),
        )
        assertEquals(listOf("m1", "t1", "m2"), items.map {
            when (it) {
                is TimelineItem.Msg -> it.entry.id
                is TimelineItem.SubagentCard -> it.subagent.id
                else -> "?"
            }
        })
    }

    @Test fun aSubagentWithNoRowsSitsAtItsStartTime() {
        // 2026-09-28T10:00:05.000Z
        val sub = Subagent(id = "x", startedAt = 1_790_589_605_000L)
        val items = mergeTimeline(listOf(msg, reply), emptyList(), subagents = listOf(sub))
        assertEquals("x", (items[1] as TimelineItem.SubagentCard).subagent.id)
    }

    @Test fun hideToolsKeepsTheCardButDropsToolRows() {
        val sub = Subagent(id = "a1", parentCallId = "spawn")
        val items = mergeTimeline(
            emptyList(),
            listOf(
                tool("2026-09-28T10:00:01.000Z", "spawn", tool = "Agent"),
                tool("2026-09-28T10:00:02.000Z", "p2"),
                tool("2026-09-28T10:00:03.000Z", "c1", sub = "a1"),
                ActivityEvent(ts = "2026-09-28T10:00:04.000Z", kind = "reasoning", title = "Thinking", subagentId = "a1"),
            ),
            hideTools = true,
            subagents = listOf(sub),
        )
        val card = items.single() as TimelineItem.SubagentCard
        assertEquals(1, card.children.size)
        assertTrue(card.children[0] is TimelineItem.Activity)
    }

    @Test fun epochMillisFormatsLikeToIsoString() {
        assertEquals("1970-01-01T00:00:00.000Z", epochMillisToIso(0))
        assertEquals("2026-09-28T10:00:05.000Z", epochMillisToIso(1_790_589_605_000L))
        assertEquals("2000-02-29T23:59:59.999Z", epochMillisToIso(951_868_799_999L))
    }
}
