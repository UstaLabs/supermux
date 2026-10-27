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
        fun tool(n: Int) = TimelineItem.Tool(ev("Bash", "ls $n"), ToolStatus.DONE)
        fun msg(n: Int) = TimelineItem.Msg(LogEntry(id = "m$n", ts = "2026-10-27T12:00:0$n.000Z", direction = "outbound", text = "hi"))
        val items = listOf(msg(0), tool(1), tool(2), msg(3), tool(4), msg(5), tool(6), tool(7), tool(8))
        val runs = toolRuns(items)
        assertEquals(listOf(1, 4, 6), runs.keys.toList())
        assertEquals(listOf(2, 1, 3), runs.values.map { it.size })
        // The transcript ends inside a run, so its last drawn row is that run's first.
        assertEquals(6 to 5, shownTail(items.size, runs))
        // A message after the run: the run's group is now the next-to-last drawn row.
        assertEquals(9 to 6, shownTail(items.size + 1, runs))
    }
}
