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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.chat.isRedactedReasoning
import dev.supermux.fs.pathLeaf
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.formatCatalogText
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.theme.Space

// Maps the broker's tool rows (ActivityEvent + its result) onto ToolCallData. The broker titles a
// row "Tool: summary" and carries the agent's own "why" in [ActivityEvent.description] when there
// is one (Claude fills it for most Bash calls), so that wins as the title.

/**
 * The adapter's wording, as catalog templates (`%1$s` / `%1$d`): [English] for a caller outside a composition (the
 * tests), [rememberToolWords] in the app's language for the UI.
 */
@Immutable
internal class ToolWords(
    val read: String, val reading: String,
    val edited: String, val editing: String,
    val wrote: String, val writing: String,
    val searchedFor: String, val searchingFor: String,
    val listed: String, val listing: String,
    val fetched: String, val fetching: String,
    val searchedWeb: String, val searchingWeb: String,
    val loaded: String, val loadingTools: String,
    val searchedTools: String, val searchingTools: String,
    val usedSkill: String, val loadingSkill: String,
    val lines: String, val exit: String,
    val markTerminal: String, val markRead: String, val markEdit: String, val markSearch: String,
    val markWeb: String, val markAgent: String, val markSkill: String, val markSchedule: String,
) {
    companion object {
        val English = ToolWords(
            read = "Read %1\$s", reading = "Reading %1\$s", // i18n-ok: test twin
            edited = "Edited %1\$s", editing = "Editing %1\$s", // i18n-ok: test twin
            wrote = "Wrote %1\$s", writing = "Writing %1\$s", // i18n-ok: test twin
            searchedFor = "Searched for %1\$s", searchingFor = "Searching for %1\$s", // i18n-ok: test twin
            listed = "Listed %1\$s", listing = "Listing %1\$s", // i18n-ok: test twin
            fetched = "Fetched %1\$s", fetching = "Fetching %1\$s", // i18n-ok: test twin
            searchedWeb = "Searched the web for %1\$s", searchingWeb = "Searching the web for %1\$s", // i18n-ok: test twin
            loaded = "Loaded %1\$s", loadingTools = "Loading tools", // i18n-ok: test twin
            searchedTools = "Searched tools for %1\$s", searchingTools = "Searching tools", // i18n-ok: test twin
            usedSkill = "Used skill %1\$s", loadingSkill = "Loading skill %1\$s", // i18n-ok: test twin
            lines = "%1\$d lines", exit = "exit %1\$d", // i18n-ok: test twin
            markTerminal = "Terminal", markRead = "Read", markEdit = "Edit", markSearch = "Search", // i18n-ok: test twin
            markWeb = "Web", markAgent = "Agent", markSkill = "Skill", markSchedule = "Schedule", // i18n-ok: test twin
        )
    }
}

/** [ToolWords] in the app's language. */
@Composable
internal fun rememberToolWords(): ToolWords {
    val w = listOf(
        localized(Str.chatline_tool_read), localized(Str.chatline_tool_reading),
        localized(Str.chatline_tool_edited_file), localized(Str.chatline_tool_editing_file),
        localized(Str.chatline_tool_wrote), localized(Str.chatline_tool_writing),
        localized(Str.chatline_tool_searched_for), localized(Str.chatline_tool_searching_for),
        localized(Str.chatline_tool_listed), localized(Str.chatline_tool_listing),
        localized(Str.chatline_tool_fetched), localized(Str.chatline_tool_fetching),
        localized(Str.chatline_tool_searched_web), localized(Str.chatline_tool_searching_web),
        localized(Str.chatline_tool_loaded), localized(Str.chatline_tool_loading_tools),
        localized(Str.chatline_tool_searched_tools), localized(Str.chatline_tool_searching_tools),
        localized(Str.chatline_tool_used_skill), localized(Str.chatline_tool_loading_skill),
        localized(Str.chatline_tool_lines), localized(Str.chatline_tool_exit),
        localized(Str.chatline_tool_mark_terminal), localized(Str.chatline_tool_mark_read),
        localized(Str.chatline_tool_mark_edit), localized(Str.chatline_tool_mark_search),
        localized(Str.chatline_tool_mark_web), localized(Str.chatline_tool_mark_agent),
        localized(Str.chatline_tool_mark_skill), localized(Str.chatline_tool_mark_schedule),
    )
    return remember(w) {
        ToolWords(
            read = w[0], reading = w[1], edited = w[2], editing = w[3], wrote = w[4], writing = w[5],
            searchedFor = w[6], searchingFor = w[7], listed = w[8], listing = w[9], fetched = w[10], fetching = w[11],
            searchedWeb = w[12], searchingWeb = w[13], loaded = w[14], loadingTools = w[15],
            searchedTools = w[16], searchingTools = w[17], usedSkill = w[18], loadingSkill = w[19],
            lines = w[20], exit = w[21],
            markTerminal = w[22], markRead = w[23], markEdit = w[24], markSearch = w[25],
            markWeb = w[26], markAgent = w[27], markSkill = w[28], markSchedule = w[29],
        )
    }
}

private fun fill(template: String, arg: Any): String = formatCatalogText(template, arrayOf(arg))

// Monochrome glyphs in the text colour, as on uiarc; only the mcp server name varies.
private fun toolMark(tool: String, w: ToolWords): ToolMark = when {
    tool.startsWith("mcp__") -> ToolMark(tool.removePrefix("mcp__").substringBefore("__"), Icons.Outlined.Extension)
    tool == "Bash" || tool == "BashOutput" || tool == "KillShell" -> ToolMark(w.markTerminal, Icons.Outlined.Terminal)
    tool == "Read" -> ToolMark(w.markRead, Icons.Outlined.Description)
    tool == "Edit" || tool == "Write" || tool == "MultiEdit" || tool == "NotebookEdit" -> ToolMark(w.markEdit, Icons.Outlined.Edit)
    tool == "Grep" || tool == "Glob" || tool == "ToolSearch" -> ToolMark(w.markSearch, Icons.Outlined.Search)
    tool == "WebFetch" || tool == "WebSearch" -> ToolMark(w.markWeb, Icons.Outlined.Public)
    tool == "Agent" || tool == "Task" || tool == "SendMessage" -> ToolMark(w.markAgent, Icons.Outlined.SmartToy)
    tool == "Skill" -> ToolMark(w.markSkill, Icons.Outlined.AutoAwesome)
    tool.startsWith("Cron") || tool == "ScheduleWakeup" -> ToolMark(w.markSchedule, Icons.Outlined.Schedule)
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
private fun phrases(tool: String, arg: String, w: ToolWords): Pair<String, String> {
    val a = arg.lineSequence().firstOrNull()?.trim().orEmpty()
    return when {
        tool.startsWith("mcp__") -> humanize(tool.substringAfterLast("__")).let { it to it }
        a.isEmpty() -> tool to tool
        else -> when (tool) {
            "Bash" -> stripCd(a).let { it to it }
            "Read" -> fill(w.read, baseName(a)) to fill(w.reading, baseName(a))
            "Edit", "MultiEdit" -> fill(w.edited, baseName(a)) to fill(w.editing, baseName(a))
            "Write" -> fill(w.wrote, baseName(a)) to fill(w.writing, baseName(a))
            "Grep" -> fill(w.searchedFor, a) to fill(w.searchingFor, a)
            "Glob" -> fill(w.listed, a) to fill(w.listing, a)
            "WebFetch" -> fill(w.fetched, host(a)) to fill(w.fetching, host(a))
            "WebSearch" -> fill(w.searchedWeb, a) to fill(w.searchingWeb, a)
            "ToolSearch" -> if (a.startsWith("select:")) {
                fill(w.loaded, a.removePrefix("select:").split(",").joinToString(", ") { it.substringAfterLast("__") }) to w.loadingTools
            } else fill(w.searchedTools, a) to w.searchingTools
            "Skill" -> fill(w.usedSkill, a) to fill(w.loadingSkill, a)
            else -> "$tool $a" to "$tool $a"
        }
    }
}

/**
 * Converts one broker tool row. [durationMs] is optional: the timeline does not carry it today. [words] is the
 * wording: English unless the UI passes [rememberToolWords].
 */
internal fun ActivityEvent.toToolCallData(
    status: ToolStatus,
    output: String? = null,
    resultBody: ActivityToolBody? = null,
    durationMs: Long? = null,
    words: ToolWords = ToolWords.English,
): ToolCallData {
    val tool = this.tool ?: "tool"
    val bodyIn = body ?: resultBody
    // The broker clips [title] at 120 chars, which can cut a command before its `cd …;` ends, so
    // the full command / detail come first.
    val arg = bodyIn?.command?.takeIf { it.isNotBlank() }
        ?: detail?.takeIf { it.isNotBlank() }
        ?: title?.removePrefix("$tool: ")?.takeIf { it != tool }.orEmpty()
    val (settled, running) = description?.takeIf { it.isNotBlank() }?.let { it to it } ?: phrases(tool, arg, words)
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
        tool = toolMark(tool, words),
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
            resultBody?.exitCode?.let { it != 0 } == true -> fill(words.exit, resultBody.exitCode ?: 0)
            lines > 1 -> fill(words.lines, lines)
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
    val words = rememberToolWords()
    val calls = remember(tools, words) { tools.map { it.event.toToolCallData(it.status, it.output, it.resultBody, words = words) } }
    ToolCallGroup(calls, active = active, modifier = Modifier.padding(vertical = Space.xs))
}
