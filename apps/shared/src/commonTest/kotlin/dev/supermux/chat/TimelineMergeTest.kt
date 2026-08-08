package dev.supermux.chat

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.LogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic tests for [mergeTimeline] — the message+activity fold that drives the chat stream
 * on Android and desktop.
 */
class TimelineMergeTest {

    private fun msg(id: String, ts: String, dir: String = "outbound", text: String = "hi") =
        LogEntry(id = id, ts = ts, direction = dir, text = text)

    private fun tool(ts: String, callId: String?, tool: String = "Bash", title: String? = null, detail: String? = null) =
        ActivityEvent(ts = ts, kind = "tool", tool = tool, title = title, detail = detail, phase = "started", callId = callId)

    private fun result(ts: String, callId: String?, phase: String, detail: String? = null) =
        ActivityEvent(ts = ts, kind = "tool_result", phase = phase, detail = detail, callId = callId)

    @Test fun emptyInputsProduceEmptyTimeline() {
        assertEquals(emptyList(), mergeTimeline(emptyList(), emptyList()))
    }

    @Test fun messagesOnlyBecomeMsgItemsInTsOrder() {
        val items = mergeTimeline(
            listOf(msg("b", "2026-01-01T00:00:02Z"), msg("a", "2026-01-01T00:00:01Z")),
            emptyList(),
        )
        assertEquals(2, items.size)
        assertTrue(items.all { it is TimelineItem.Msg })
        assertEquals("a", (items[0] as TimelineItem.Msg).entry.id)
        assertEquals("b", (items[1] as TimelineItem.Msg).entry.id)
    }

    @Test fun toolEventBecomesToolItemRunningWhenNoResult() {
        val items = mergeTimeline(emptyList(), listOf(tool("2026-01-01T00:00:01Z", callId = "c1")))
        assertEquals(1, items.size)
        val t = items[0] as TimelineItem.Tool
        assertEquals(ToolStatus.RUNNING, t.status)
    }

    @Test fun toolResultCompletedMapsToDoneAndFolds() {
        val items = mergeTimeline(
            emptyList(),
            listOf(
                tool("2026-01-01T00:00:01Z", callId = "c1"),
                result("2026-01-01T00:00:02Z", callId = "c1", phase = "completed", detail = "ok"),
            ),
        )
        assertEquals(1, items.size)
        val t = items[0] as TimelineItem.Tool
        assertEquals(ToolStatus.DONE, t.status)
        assertEquals("ok", t.output)
    }

    @Test fun toolResultFailedMapsToError() {
        val items = mergeTimeline(
            emptyList(),
            listOf(
                tool("2026-01-01T00:00:01Z", callId = "c1"),
                result("2026-01-01T00:00:02Z", callId = "c1", phase = "failed", detail = "boom"),
            ),
        )
        assertEquals(1, items.size)
        val t = items[0] as TimelineItem.Tool
        assertEquals(ToolStatus.ERROR, t.status)
        assertEquals("boom", t.output)
    }

    @Test fun thinkingAndUnknownKindsAreDropped() {
        val items = mergeTimeline(
            emptyList(),
            listOf(
                ActivityEvent(ts = "2026-01-01T00:00:01Z", kind = "thinking", detail = "Thought for 2s"),
                ActivityEvent(ts = "2026-01-01T00:00:02Z", kind = "misc"),
            ),
        )
        assertTrue(items.isEmpty())
    }

    @Test fun messagesAndToolsInterleaveByTs() {
        val items = mergeTimeline(
            listOf(
                msg("m1", "2026-01-01T00:00:01Z", dir = "inbound"),
                msg("m2", "2026-01-01T00:00:05Z"),
            ),
            listOf(
                tool("2026-01-01T00:00:03Z", callId = "c1"),
                result("2026-01-01T00:00:04Z", callId = "c1", phase = "completed"),
            ),
        )
        assertEquals(3, items.size)
        assertEquals("m1", (items[0] as TimelineItem.Msg).entry.id)
        assertTrue(items[1] is TimelineItem.Tool)
        assertEquals("m2", (items[2] as TimelineItem.Msg).entry.id)
    }

    @Test fun equalTimestampsPreserveStableInsertionOrder() {
        val ts = "2026-01-01T00:00:01Z"
        val items = mergeTimeline(
            listOf(msg("m", ts)),
            listOf(tool(ts, callId = "c1")),
        )
        assertEquals(2, items.size)
        assertTrue(items[0] is TimelineItem.Msg)
        assertTrue(items[1] is TimelineItem.Tool)
    }

    @Test fun toolWithoutCallIdStaysRunningEvenIfAResultExists() {
        val items = mergeTimeline(
            emptyList(),
            listOf(
                tool("2026-01-01T00:00:01Z", callId = null),
                result("2026-01-01T00:00:02Z", callId = "other", phase = "completed"),
            ),
        )
        assertEquals(1, items.size)
        assertEquals(ToolStatus.RUNNING, (items[0] as TimelineItem.Tool).status)
    }
}
