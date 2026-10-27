package dev.supermux.ui.chat

import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCallAdapterTest {
    private fun ev(tool: String, arg: String, description: String? = null) = ActivityEvent(
        ts = "2026-10-27T12:00:00.000Z", kind = "tool", tool = tool, title = "$tool: $arg", detail = arg,
        description = description, callId = "c1",
        body = if (tool == "Bash") ActivityToolBody(kind = "bash", command = arg) else null,
    )

    @Test fun stripsLeadingCdHops() {
        assertEquals("make test", stripCd("cd /home/a/worktree && make test"))
        assertEquals("ls", stripCd("cd \"/a b\"; cd sub && ls"))
        assertEquals("cdk deploy", stripCd("cdk deploy"))
    }

    @Test fun descriptionWinsOverTheCommand() {
        val d = ev("Bash", "cd /x && rg foo", "Find foo call sites").toToolCallData(ToolStatus.DONE, "a\nb")
        assertEquals("Find foo call sites", d.title)
        assertEquals(false, d.monoTitle)
        assertEquals("2 lines", d.summary)
    }

    @Test fun bareCommandIsAMonoTitle() {
        val d = ev("Bash", "cd /x && rg foo").toToolCallData(ToolStatus.RUNNING)
        assertEquals("rg foo", d.title)
        assertTrue(d.monoTitle)
        assertEquals(ToolCallStatus.Running, d.status)
    }

    @Test fun phrasesPerTool() {
        assertEquals("Read Timeline.kt", ev("Read", "/a/b/Timeline.kt").toToolCallData(ToolStatus.DONE).title)
        assertEquals("Reading Timeline.kt", ev("Read", "/a/b/Timeline.kt").toToolCallData(ToolStatus.RUNNING).titles[ToolCallStatus.Running])
        assertEquals("Loaded attach, Monitor", ev("ToolSearch", "select:mcp__mux-shim__attach,Monitor").toToolCallData(ToolStatus.DONE).title)
        assertEquals("Rename session", ev("mcp__mux-shim__rename_session", "x").toToolCallData(ToolStatus.DONE).title)
    }

    @Test fun errorCarriesItsFirstLine() {
        val d = ev("Bash", "false").toToolCallData(ToolStatus.ERROR, "\nExit code 1\nmore")
        assertEquals(ToolCallStatus.Error, d.status)
        assertEquals("Exit code 1", d.message)
    }

    @Test fun consecutiveToolsFoldIntoRunsKeyedByTheirFirstIndex() {
        val items = listOf(msg(0), tool(1), tool(2), msg(3), tool(4), msg(5), tool(6), tool(7), tool(8))
        val runs = toolRuns(items)
        assertEquals(listOf(1, 4, 6), runs.keys.toList())
        assertEquals(listOf(2, 1, 3), runs.values.map { it.tools.size })
        assertEquals(setOf(2, 7, 8), foldedRows(runs))
        // The transcript ends inside a run, so its last drawn row is that run's first.
        assertEquals(6 to 5, shownTail(items.size, runs))
    }

    @Test fun hiddenThinkingKeepsARunTogetherButVisibleThinkingEndsIt() {
        val items = listOf(msg(0), tool(1), thinking(2, redacted = true), tool(3), thinking(4, redacted = true))
        val runs = toolRuns(items)
        // One run from 1 to the end, the trailing thinking row included (the run is still live).
        assertEquals(listOf(1), runs.keys.toList())
        assertEquals(2, runs.getValue(1).tools.size)
        assertEquals(4, runs.getValue(1).span)
        assertEquals(1 to 0, shownTail(items.size, runs))
        // Reasoning with text keeps its card, so it splits the run.
        val split = toolRuns(listOf(tool(0), thinking(1, redacted = false), tool(2)))
        assertEquals(listOf(0, 2), split.keys.toList())
        // Hidden thinking before any tool is not part of a run.
        assertEquals(listOf(1), toolRuns(listOf(thinking(0, redacted = true), tool(1))).keys.toList())
    }

    private fun tool(n: Int) = TimelineItem.Tool(ev("Bash", "ls $n"), ToolStatus.DONE)
    private fun msg(n: Int) = TimelineItem.Msg(LogEntry(id = "m$n", ts = "2026-10-27T12:00:0$n.000Z", direction = "outbound", text = "hi"))
    private fun thinking(n: Int, redacted: Boolean) = TimelineItem.Activity(
        ActivityEvent(ts = "2026-10-27T12:00:0$n.000Z", kind = "reasoning", title = "Thinking", redacted = redacted, detail = if (redacted) null else "Because…"),
    )
}
