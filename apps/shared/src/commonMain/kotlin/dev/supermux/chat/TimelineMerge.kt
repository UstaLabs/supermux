// Shared chat timeline fold: messages + activity → ordered stream items.
// Android and desktop both render [TimelineItem]s; keep the pure fold here so
// clients cannot drift on tool_result folding / thinking drops / sort order.
package dev.supermux.chat

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
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
 * @param hideTools when true (chat detail = low), omit tool cards; activity is still ingested by the caller.
 *   Subagent cards stay (the subagent is the unit of work), only their tool rows go.
 * @param subagents the session's subagent views (HostState.subagents); may be empty.
 */
fun mergeTimeline(
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    hideTools: Boolean = false,
    subagents: List<Subagent> = emptyList(),
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
        "reasoning", "plan", "task" -> TimelineItem.Activity(e)
        // "thinking" (and any other non-tool kind) is intentionally dropped.
        else -> null
    }

    val byId = LinkedHashMap<String, Subagent>()
    subagents.forEach { byId[it.id] = it }
    val bySpawn = subagents.mapNotNull { s -> s.parentCallId?.let { it to s.id } }.toMap()
    val childRows = LinkedHashMap<String, MutableList<ActivityEvent>>()
    val spawnRows = HashMap<String, ActivityEvent>()
    val items = ArrayList<TimelineItem>(messages.size + activity.size)
    messages.forEach { items.add(TimelineItem.Msg(it)) }
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
        items.add(TimelineItem.SubagentCard(subagent, spawn, children))
    }
    return items.sortedBy { it.sortTs }
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
