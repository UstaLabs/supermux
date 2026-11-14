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
import dev.supermux.fs.pathLeaf
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.theme.Space

// Maps the broker's tool rows (ActivityEvent + its result) onto ToolCallData. The broker titles a
// row "Tool: summary" and carries the agent's own "why" in [ActivityEvent.description] when there
// is one (Claude fills it for most Bash calls), so that wins as the title.


// Monochrome glyphs in the text colour, as on uiarc; only the mcp server name varies.
private fun toolMark(tool: String): ToolMark = when {
    tool.startsWith("mcp__") -> ToolMark(tool.removePrefix("mcp__").substringBefore("__"), Icons.Outlined.Extension)
    tool == "Bash" || tool == "BashOutput" || tool == "KillShell" -> ToolMark(localized(Str.chatline_tool_mark_terminal), Icons.Outlined.Terminal)
    tool == "Read" -> ToolMark(localized(Str.chatline_tool_mark_read), Icons.Outlined.Description)
    tool == "Edit" || tool == "Write" || tool == "MultiEdit" || tool == "NotebookEdit" -> ToolMark(localized(Str.chatline_tool_mark_edit), Icons.Outlined.Edit)
    tool == "Grep" || tool == "Glob" || tool == "ToolSearch" -> ToolMark(localized(Str.chatline_tool_mark_search), Icons.Outlined.Search)
    tool == "WebFetch" || tool == "WebSearch" -> ToolMark(localized(Str.chatline_tool_mark_web), Icons.Outlined.Public)
    tool == "Agent" || tool == "Task" || tool == "SendMessage" -> ToolMark(localized(Str.chatline_tool_mark_agent), Icons.Outlined.SmartToy)
    tool == "Skill" -> ToolMark(localized(Str.chatline_tool_mark_skill), Icons.Outlined.AutoAwesome)
    tool.startsWith("Cron") || tool == "ScheduleWakeup" -> ToolMark(localized(Str.chatline_tool_mark_schedule), Icons.Outlined.Schedule)
    else -> ToolMark(tool)
}

private fun baseName(path: String) = pathLeaf(path)

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
            "Read" -> localized(Str.chatline_tool_read, baseName(a)) to localized(Str.chatline_tool_reading, baseName(a))
            "Edit", "MultiEdit" -> localized(Str.chatline_tool_edited_file, baseName(a)) to localized(Str.chatline_tool_editing_file, baseName(a))
            "Write" -> localized(Str.chatline_tool_wrote, baseName(a)) to localized(Str.chatline_tool_writing, baseName(a))
            "Grep" -> localized(Str.chatline_tool_searched_for, a) to localized(Str.chatline_tool_searching_for, a)
            "Glob" -> localized(Str.chatline_tool_listed, a) to localized(Str.chatline_tool_listing, a)
            "WebFetch" -> localized(Str.chatline_tool_fetched, host(a)) to localized(Str.chatline_tool_fetching, host(a))
            "WebSearch" -> localized(Str.chatline_tool_searched_web, a) to localized(Str.chatline_tool_searching_web, a)
            "ToolSearch" -> if (a.startsWith("select:")) {
                localized(Str.chatline_tool_loaded, a.removePrefix("select:").split(",").joinToString(", ") { it.substringAfterLast("__") }) to localized(Str.chatline_tool_loading_tools)
            } else localized(Str.chatline_tool_searched_tools, a) to localized(Str.chatline_tool_searching_tools)
            "Skill" -> localized(Str.chatline_tool_used_skill, a) to localized(Str.chatline_tool_loading_skill, a)
            else -> "$tool $a" to "$tool $a"
        }
    }
}

/**
 * Converts one broker tool row, in the app's language. [durationMs] is optional: the timeline does not carry it today.
 */
internal fun ActivityEvent.toToolCallData(
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
            resultBody?.exitCode?.let { it != 0 } == true -> localized(Str.chatline_tool_exit, resultBody.exitCode ?: 0)
            lines > 1 -> localized(Str.chatline_tool_lines, lines)
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
    // The language is a key: a change re-words the rows.
    val calls = remember(tools, localized(Str.chatline_tool_read)) { tools.map { it.event.toToolCallData(it.status, it.output, it.resultBody) } }
    ToolCallGroup(calls, active = active, modifier = Modifier.padding(vertical = Space.xs))
}
