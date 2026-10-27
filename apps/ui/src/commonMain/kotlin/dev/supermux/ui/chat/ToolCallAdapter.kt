package dev.supermux.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.chat.isRedactedReasoning
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.ui.theme.Space

// Maps the broker's tool rows (ActivityEvent + its result) onto ToolCallData. The broker titles a
// row "Tool: summary" and carries the agent's own "why" in [ActivityEvent.description] when there
// is one (Claude fills it for most Bash calls), so that wins as the title.

// Monochrome glyphs in the text colour, as on uiarc; only the mcp server name varies.
private fun toolMark(tool: String): ToolMark = when {
    tool.startsWith("mcp__") -> ToolMark(tool.removePrefix("mcp__").substringBefore("__"), Icons.Outlined.Extension)
    tool == "Bash" || tool == "BashOutput" || tool == "KillShell" -> ToolMark("Terminal", Icons.Outlined.Terminal)
    tool == "Read" -> ToolMark("Read", Icons.Outlined.Description)
    tool == "Edit" || tool == "Write" || tool == "MultiEdit" || tool == "NotebookEdit" -> ToolMark("Edit", Icons.Outlined.Edit)
    tool == "Grep" || tool == "Glob" || tool == "ToolSearch" -> ToolMark("Search", Icons.Outlined.Search)
    tool == "WebFetch" || tool == "WebSearch" -> ToolMark("Web", Icons.Outlined.Public)
    tool == "Agent" || tool == "Task" || tool == "SendMessage" -> ToolMark("Agent", Icons.Outlined.SmartToy)
    tool == "Skill" -> ToolMark("Skill", Icons.Outlined.AutoAwesome)
    tool.startsWith("Cron") || tool == "ScheduleWakeup" -> ToolMark("Schedule", Icons.Outlined.Schedule)
    else -> ToolMark(tool)
}

private fun baseName(path: String) = path.trimEnd('/').substringAfterLast('/')

private fun humanize(name: String) = name.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** `cd /long/worktree/path && make test` reads as `make test`: the directory is the session's own. */
internal fun stripCd(command: String): String {
    var c = command.trim()
    while (true) {
        val m = Regex("""^cd\s+("[^"]*"|'[^']*'|\S+)\s*(&&|;)\s*""").find(c) ?: return c
        c = c.substring(m.range.last + 1)
    }
}

private fun host(url: String) = url.substringAfter("://").substringBefore('/')

/** Settled and running phrasing for a tool whose agent gave no description. */
private fun phrases(tool: String, arg: String): Pair<String, String> {
    val a = arg.lineSequence().firstOrNull()?.trim().orEmpty()
    return when {
        tool.startsWith("mcp__") -> humanize(tool.substringAfterLast("__")).let { it to it }
        a.isEmpty() -> tool to tool
        else -> when (tool) {
            "Bash" -> stripCd(a).let { it to it }
            "Read" -> "Read ${baseName(a)}" to "Reading ${baseName(a)}"
            "Edit", "MultiEdit" -> "Edited ${baseName(a)}" to "Editing ${baseName(a)}"
            "Write" -> "Wrote ${baseName(a)}" to "Writing ${baseName(a)}"
            "Grep" -> "Searched for $a" to "Searching for $a"
            "Glob" -> "Listed $a" to "Listing $a"
            "WebFetch" -> "Fetched ${host(a)}" to "Fetching ${host(a)}"
            "WebSearch" -> "Searched the web for $a" to "Searching the web for $a"
            "ToolSearch" -> if (a.startsWith("select:")) {
                "Loaded " + a.removePrefix("select:").split(",").joinToString(", ") { it.substringAfterLast("__") } to "Loading tools"
            } else "Searched tools for $a" to "Searching tools"
            "Skill" -> "Used skill $a" to "Loading skill $a"
            else -> "$tool $a" to "$tool $a"
        }
    }
}

/** Converts one broker tool row. [durationMs] is optional: the timeline does not carry it today. */
fun ActivityEvent.toToolCallData(
    status: ToolStatus,
    output: String? = null,
    resultBody: ActivityToolBody? = null,
    durationMs: Long? = null,
): ToolCallData {
    val tool = this.tool ?: "tool"
    val bodyIn = body ?: resultBody
    // The broker clips [title] at 120 chars, which can cut a command before its `cd …;` ends, so
    // the full command / detail come first.
    val arg = bodyIn?.command?.takeIf { it.isNotBlank() }
        ?: detail?.takeIf { it.isNotBlank() }
        ?: title?.removePrefix("$tool: ")?.takeIf { it != tool }.orEmpty()
    val (settled, running) = description?.takeIf { it.isNotBlank() }?.let { it to it } ?: phrases(tool, arg)
    val input = buildList {
        when {
            bodyIn?.command != null -> add("command" to bodyIn.command!!)
            bodyIn?.path != null -> add("path" to (bodyIn.rawPath ?: bodyIn.path!!))
            !detail.isNullOrBlank() -> add("input" to detail!!)
            !bodyIn?.input.isNullOrBlank() -> add("input" to bodyIn!!.input!!)
        }
    }
    val outText = (resultBody?.output ?: output)?.trimEnd()?.takeIf { it.isNotEmpty() }
        ?: bodyIn?.diff?.takeIf { it.isNotBlank() }
    val out = outText?.let { if (tool.startsWith("mcp__") || tool == "Agent" || tool == "Task") ToolCallOutput.Text(it) else ToolCallOutput.Code(it) }
    val lines = outText?.lines()?.size ?: 0
    val failed = status == ToolStatus.ERROR
    return ToolCallData(
        id = callId ?: "${ts}#${seq ?: 0}",
        tool = toolMark(tool),
        title = settled,
        status = when (status) {
            ToolStatus.RUNNING -> ToolCallStatus.Running
            ToolStatus.DONE -> ToolCallStatus.Success
            ToolStatus.ERROR -> ToolCallStatus.Error
        },
        titles = mapOf(ToolCallStatus.Running to running),
        input = input,
        output = out,
        summary = when {
            failed -> null
            resultBody?.exitCode?.let { it != 0 } == true -> "exit ${resultBody.exitCode}"
            lines > 1 -> "$lines lines"
            else -> null
        },
        message = if (failed) outText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(200) else null,
        durationMs = durationMs,
        monoTitle = tool == "Bash" && description.isNullOrBlank(),
    )
}

/** A run of tool rows plus the hidden-thinking rows inside or after it: [span] timeline rows in all. */
internal class ToolRun(val tools: List<TimelineItem.Tool>, val span: Int)

/**
 * Medium chat: consecutive tool rows fold into one [ToolCallGroup]. Keys are the index of each
 * run's first tool; the other rows of a run render nothing, so the timeline's keys and indices
 * (find, scroll-to) stay exactly as they were.
 *
 * A redacted reasoning row (Claude's hidden thinking — it says nothing but "thinking") does not
 * end a run: the group stays live and shows "Planning next moves" instead of folding away
 * between two calls. Reasoning that has text still ends the run and keeps its own card.
 */
internal fun toolRuns(items: List<TimelineItem>): Map<Int, ToolRun> {
    val runs = LinkedHashMap<Int, ToolRun>()
    var start = -1
    var tools = ArrayList<TimelineItem.Tool>()
    fun close(endExclusive: Int) {
        if (start >= 0) runs[start] = ToolRun(tools, endExclusive - start)
        start = -1; tools = ArrayList()
    }
    items.forEachIndexed { i, item ->
        when {
            item is TimelineItem.Tool -> { if (start < 0) start = i; tools.add(item) }
            start >= 0 && item is TimelineItem.Activity && item.event.isRedactedReasoning -> Unit
            else -> close(i)
        }
    }
    close(items.size)
    return runs
}

/** Rows a run covers beyond its first: they draw nothing. */
internal fun foldedRows(runs: Map<Int, ToolRun>): Set<Int> =
    runs.flatMapTo(HashSet()) { (start, run) -> (start + 1 until start + run.span) }

/**
 * The last and next-to-last timeline indices that draw anything once [runs] fold their rows into
 * their first one (-1 when there is none).
 */
internal fun shownTail(size: Int, runs: Map<Int, ToolRun>): Pair<Int, Int> {
    val hidden = foldedRows(runs)
    var last = -1
    var i = size - 1
    while (i >= 0) {
        if (i !in hidden) {
            if (last < 0) last = i else return last to i
        }
        i--
    }
    return last to -1
}

/** One run of tool rows as a group; [active] while the agent is still in it (the run ends the timeline). */
@Composable
internal fun ToolRunGroup(tools: List<TimelineItem.Tool>, active: Boolean) {
    val calls = remember(tools) { tools.map { it.event.toToolCallData(it.status, it.output, it.resultBody) } }
    ToolCallGroup(calls, active = active, modifier = Modifier.padding(vertical = Space.xs))
}
