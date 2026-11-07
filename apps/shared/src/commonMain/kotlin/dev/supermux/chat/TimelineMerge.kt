// Shared chat timeline fold: messages + activity → ordered stream items.
// Android and desktop both render [TimelineItem]s; keep the pure fold here so
// clients cannot drift on tool_result folding / thinking drops / sort order.
package dev.supermux.chat

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.proto.CouncilView
import dev.supermux.proto.LogEntry
import dev.supermux.proto.Subagent

/** Resolved lifecycle of a tool-use row in the chat stream. */
enum class ToolStatus { RUNNING, DONE, ERROR }

/** One row in the merged chat stream (message or folded tool). */
sealed interface TimelineItem {
    data class Msg(val entry: LogEntry) : TimelineItem
    data class Tool(
        val event: ActivityEvent,
        val status: ToolStatus,
        /** Detail from the matching `tool_result` event (iOS folds as Output). */
        val output: String? = null,
        val resultBody: ActivityToolBody? = null,
    ) : TimelineItem
    data class Activity(val event: ActivityEvent) : TimelineItem

    /**
     * ONE card per subagent, in place of the parent's spawning tool row. [spawn] is that row
     * (the parent tool call whose `callId` == [Subagent.parentCallId]) folded with its result,
     * or null when the agent has none (Codex spawns without a tool row). [children] are the
     * subagent's own rows (`ActivityEvent.subagentId` == its id), folded exactly like the top
     * level. [subagent] is a bare `Subagent(id)` placeholder when rows arrived for an id the
     * caller has no view of (yet).
     */
    data class SubagentCard(
        val subagent: Subagent,
        val spawn: Tool? = null,
        val children: List<TimelineItem> = emptyList(),
    ) : TimelineItem

    /**
     * ONE card per council the session called (spec §5.1), its forwarded reports folded in. [call] is
     * the caller's `council` tool row ([CouncilView.callId]) the card takes the place of, when there
     * is one. [at] is where it sits: that call, else its first forwarded report, else its creation.
     * [failures]: member letter → the error its turn last stopped with (supermux's notice), for the
     * failed rows.
     */
    data class CouncilCard(
        val council: CouncilView,
        val call: Tool? = null,
        val at: String = council.createdAt,
        val failures: Map<String, String> = emptyMap(),
    ) : TimelineItem
}

/** Where an item sits in the stream (ISO-8601, sorts lexicographically). */
val TimelineItem.sortTs: String
    get() = when (this) {
        is TimelineItem.Msg -> entry.ts
        is TimelineItem.Tool -> event.ts
        is TimelineItem.Activity -> event.ts
        is TimelineItem.SubagentCard -> spawn?.event?.ts
            ?: children.firstOrNull()?.sortTs
            ?: epochMillisToIso(subagent.startedAt)
        is TimelineItem.CouncilCard -> at
    }

/**
 * Merge messages and activity events, sorted ascending by ts (ISO-8601 → lexicographic).
 *
 * Tool-call activity is folded by `callId`: the broker emits a `tool` (phase=started)
 * event and later a separate `tool_result` (phase=completed|failed) event with the same
 * callId. We resolve a single status per call and render ONE [TimelineItem.Tool] row —
 * the result event is not shown on its own (otherwise completed tools look stuck running).
 * Non-tool activity (notably "thinking" → "Thought for Ns") is dropped here: thinking is
 * surfaced only as a live status indicator, never as a persistent history row (matches web).
 *
 * Subagents: rows a subagent produced ([ActivityEvent.subagentId] set) never appear at the top
 * level. They are grouped into one [TimelineItem.SubagentCard] per subagent id, which takes the
 * place of the parent's spawning tool row (matched by [Subagent.parentCallId] == callId) or, when
 * there is none, sits at the subagent's first row / start time.
 *
 * Councils: each of [councils] is one [TimelineItem.CouncilCard], in place of the caller's `council`
 * tool row (matched by [CouncilView.callId]) or, without one, at its first forwarded report / its
 * creation. Forwarded reports (rows with a known [LogEntry.council_id]) live in the card and leave
 * the chat; a report of a council the session has no view of stays a row.
 *
 * @param hideTools when true (chat detail = low), omit tool cards; activity is still ingested by the caller.
 *   Subagent cards stay (the subagent is the unit of work), only their tool rows go.
 * @param subagents the session's subagent views (HostState.subagents); may be empty.
 * @param working whether the agent is mid-turn. A redacted reasoning row has nothing to show but
 *   "Thinking", so it is kept only while it is the live tail of a running turn and dropped as soon
 *   as anything follows it or the turn ends.
 */
fun mergeTimeline(
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    hideTools: Boolean = false,
    subagents: List<Subagent> = emptyList(),
    working: Boolean = false,
    councils: List<CouncilView> = emptyList(),
): List<TimelineItem> {
    // callId -> resolved final status + output detail from `tool_result` events.
    // Broker sets phase=completed and title=error|done (not always phase=failed).
    val resultStatus = HashMap<String, ToolStatus>()
    val resultDetail = HashMap<String, String?>()
    val resultBodies = HashMap<String, ActivityToolBody?>()
    for (e in activity) {
        val id = e.callId
        if (e.kind == "tool_result" && id != null) {
            resultStatus[id] =
                if (e.title == "error" || e.phase == "failed") ToolStatus.ERROR else ToolStatus.DONE
            resultDetail[id] = e.detail
            resultBodies[id] = e.body
        }
    }
    fun fold(e: ActivityEvent): TimelineItem? = when (e.kind) {
        "tool" -> {
            val status = e.callId?.let { resultStatus[it] } ?: ToolStatus.RUNNING
            val output = e.callId?.let { resultDetail[it] }
            val resultBody = e.callId?.let { resultBodies[it] }
            TimelineItem.Tool(e, status, output, resultBody)
        }
        "tool_result" -> null // folded into the matching tool row above
        "reasoning", "plan", "task", "subagent_message" -> TimelineItem.Activity(e)
        // "thinking" (and any other non-tool kind) is intentionally dropped.
        else -> null
    }

    val byId = LinkedHashMap<String, Subagent>()
    subagents.forEach { byId[it.id] = it }
    val bySpawn = subagents.mapNotNull { s -> s.parentCallId?.let { it to s.id } }.toMap()
    val childRows = LinkedHashMap<String, MutableList<ActivityEvent>>()
    val spawnRows = HashMap<String, ActivityEvent>()
    val items = ArrayList<TimelineItem>(messages.size + activity.size)
    val councilIds = councils.mapTo(HashSet()) { it.id }
    val byCouncilCall = councils.mapNotNull { c -> c.callId?.let { it to c.id } }.toMap()
    val councilCalls = HashMap<String, ActivityEvent>()
    val firstReport = HashMap<String, String>()
    for (m in messages) {
        val council = m.council_id
        if (council != null && council in councilIds) {
            val prev = firstReport[council]
            if (prev == null || m.ts < prev) firstReport[council] = m.ts
            continue
        }
        items.add(TimelineItem.Msg(m))
    }
    // One row per tool call: a second `tool` event for a callId we already have (e.g. the
    // broker's "auto-approved: …" row when the permission policy answers) is folded away — two
    // rows with the same callId would also collide as LazyColumn keys and crash the chat.
    val seenToolCalls = HashSet<String>()
    for (e in activity) {
        if (e.kind == "tool" && e.callId != null && !seenToolCalls.add(e.callId)) continue
        val child = e.subagentId
        if (child != null) {
            childRows.getOrPut(child) { ArrayList() }.add(e)
            continue
        }
        val councilOf = e.callId?.let { byCouncilCall[it] }
        if (councilOf != null && e.kind == "tool") {
            if (councilOf !in councilCalls) councilCalls[councilOf] = e
            continue
        }
        val spawnOf = e.callId?.let { bySpawn[it] }
        if (spawnOf != null && e.kind == "tool") {
            if (spawnOf !in spawnRows) spawnRows[spawnOf] = e
            continue
        }
        // The same subagent reported through the task channel (Claude background agents): the
        // card already says it started/finished, a task row beside it would say it twice.
        if (spawnOf != null && e.kind == "task") continue
        if (hideTools && e.kind == "tool") continue
        fold(e)?.let(items::add)
    }
    for (id in childRows.keys) if (id !in byId) byId[id] = Subagent(id = id)
    for ((id, subagent) in byId) {
        val rows = childRows[id].orEmpty()
        val spawn = spawnRows[id]?.let { fold(it) as? TimelineItem.Tool }
        // A subagent we know of but have no rows for (trimmed activity) is still a card.
        val children = rows.asSequence()
            .filterNot { hideTools && it.kind == "tool" }
            .mapNotNull(::fold)
            .sortedBy { it.sortTs }
            .toList()
            .dropSettledRedacted(working)
        items.add(TimelineItem.SubagentCard(subagent, spawn, children))
    }
    for (c in councils) {
        val call = councilCalls[c.id]?.let { fold(it) as? TimelineItem.Tool }
        val at = call?.event?.ts ?: firstReport[c.id] ?: c.createdAt
        items.add(TimelineItem.CouncilCard(c, call, at, councilFailures(c, messages)))
    }
    return items.sortedBy { it.sortTs }.dropSettledRedacted(working)
}

/**
 * Member letter → the error its turn last stopped with, from supermux's notices to the caller
 * (`"<council> · <letter> [<name>:<id>] stopped with an error:\n<error>"`, the broker's text).
 */
internal fun councilFailures(council: CouncilView, messages: List<LogEntry>): Map<String, String> {
    val prefix = "${council.name} · "
    val out = HashMap<String, String>()
    for (m in messages) {
        if (m.channel != "inbox" || m.from_session_id != "supermux") continue
        val text = m.text ?: continue
        if (!text.startsWith(prefix)) continue
        val head = text.substringBefore('\n')
        if (!head.endsWith("stopped with an error:")) continue
        val letter = head.removePrefix(prefix).substringBefore(" [")
        if (council.members.none { it.letter == letter }) continue
        val reason = text.substringAfter('\n', "").trim()
        if (reason.isNotEmpty()) out[letter] = reason
    }
    return out
}

/** A reasoning row whose thought stream the model redacted (no text to expand). */
val ActivityEvent.isRedactedReasoning: Boolean
    get() = kind == "reasoning" &&
        (redacted == true || title?.contains("redacted", ignoreCase = true) == true)

private fun List<TimelineItem>.dropSettledRedacted(working: Boolean): List<TimelineItem> {
    val last = lastIndex
    return filterIndexed { i, item ->
        !(item is TimelineItem.Activity && item.event.isRedactedReasoning && !(working && i == last))
    }
}

/**
 * [ms] as the broker's `Date.toISOString()` ("2026-09-28T12:00:00.000Z"), so an epoch time sorts
 * correctly against the ISO `ts` of messages and activity rows.
 */
fun epochMillisToIso(ms: Long): String {
    val days = ms.floorDiv(86_400_000L)
    val msOfDay = ms.mod(86_400_000L)
    // Civil-from-days (Howard Hinnant), proleptic Gregorian.
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    fun p(v: Long, n: Int) = v.toString().padStart(n, '0')
    val h = msOfDay / 3_600_000
    val min = msOfDay / 60_000 % 60
    val sec = msOfDay / 1000 % 60
    val milli = msOfDay % 1000
    return "${p(y, 4)}-${p(m, 2)}-${p(d, 2)}T${p(h, 2)}:${p(min, 2)}:${p(sec, 2)}.${p(milli, 3)}Z"
}
