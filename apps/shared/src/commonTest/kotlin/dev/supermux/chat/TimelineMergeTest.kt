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

    @Test fun reasoningPlanAndTaskBecomeActivityItems() {
        val items = mergeTimeline(
            emptyList(),
            listOf(
                ActivityEvent(ts = "2026-01-01T00:00:01Z", kind = "reasoning", title = "Thinking", detail = "hmm"),
                ActivityEvent(ts = "2026-01-01T00:00:02Z", kind = "plan", title = "Plan", detail = "pending: do it"),
                ActivityEvent(ts = "2026-01-01T00:00:03Z", kind = "task", title = "build", phase = "started", taskKind = "shell"),
            ),
        )
        assertEquals(3, items.size)
        assertTrue(items.all { it is TimelineItem.Activity })
        assertEquals(listOf("reasoning", "plan", "task"), items.map { (it as TimelineItem.Activity).event.kind })
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

    // ---- hideTools (chat detail = low) — iOS ChatBlocksDerivationTests parity ----------------

    /**
     * Low chat detail must be exactly the full timeline minus its tool rows — nothing reordered,
     * nothing dropped, no messages lost. If [mergeTimeline] ever gains hideTools-specific
     * clustering, this fails loudly instead of silently changing what Low shows.
     */
    @Test fun hideToolsYieldsExactlyTheMessageSubsetOfTheFullTimeline() {
        val messages = listOf(
            msg("m1", "2026-01-01T00:00:00Z"),
            msg("m2", "2026-01-01T00:00:30Z"),
            msg("m3", "2026-01-01T00:02:00Z"),
        )
        val activity = listOf(
            tool("2026-01-01T00:00:10Z", callId = "c1", tool = "Read"),
            result("2026-01-01T00:00:15Z", callId = "c1", phase = "completed"),
            tool("2026-01-01T00:01:00Z", callId = "c2", tool = "Edit"),
        )

        val hidden = mergeTimeline(messages, activity, hideTools = true)
        val filtered = mergeTimeline(messages, activity).filterIsInstance<TimelineItem.Msg>()

        assertEquals(filtered.map { it.entry.id }, hidden.map { (it as TimelineItem.Msg).entry.id })
        assertEquals(messages.size, hidden.size)
        assertTrue(hidden.all { it is TimelineItem.Msg })
    }

    /** With tools hidden, activity alone renders nothing at all — not an empty tool row. */
    @Test fun hideToolsWithActivityOnlyProducesAnEmptyTimeline() {
        val items = mergeTimeline(
            emptyList(),
            listOf(
                tool("2026-01-01T00:00:01Z", callId = "c1"),
                result("2026-01-01T00:00:02Z", callId = "c1", phase = "completed"),
            ),
            hideTools = true,
        )
        assertTrue(items.isEmpty())
    }

    /**
     * The transcript re-derives from the buffer's contents on every append. Re-running the fold
     * over the grown list must produce the previous timeline plus the new row — a stale or
     * mis-ordered re-derivation is how a transcript silently freezes mid-conversation.
     */
    @Test fun appendingOneMessageRederivesToThePreviousTimelinePlusIt() {
        val activity = listOf(tool("2026-01-01T00:00:10Z", callId = "c1"))
        val before = mergeTimeline(listOf(msg("m1", "2026-01-01T00:00:00Z")), activity)
        assertEquals(2, before.size)

        val after = mergeTimeline(
            listOf(msg("m1", "2026-01-01T00:00:00Z"), msg("m2", "2026-01-01T00:01:00Z")),
            activity,
        )
        assertEquals(3, after.size)
        assertEquals(ids(before), ids(after).dropLast(1))
        assertEquals("m2", (after.last() as TimelineItem.Msg).entry.id)

        // …and the same append under hideTools grows the message-only view by exactly one row.
        assertEquals(1, mergeTimeline(listOf(msg("m1", "2026-01-01T00:00:00Z")), activity, hideTools = true).size)
        assertEquals(2, mergeTimeline(
            listOf(msg("m1", "2026-01-01T00:00:00Z"), msg("m2", "2026-01-01T00:01:00Z")),
            activity,
            hideTools = true,
        ).size)
    }

    /** Row identity for order comparisons: message id, or the tool's callId/ts. */
    private fun ids(items: List<TimelineItem>) = items.map {
        when (it) {
            is TimelineItem.Msg -> "m:${it.entry.id}"
            is TimelineItem.Tool -> "t:${it.event.callId ?: it.event.ts}"
            is TimelineItem.Activity -> "a:${it.event.kind}:${it.event.ts}"
        }
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
