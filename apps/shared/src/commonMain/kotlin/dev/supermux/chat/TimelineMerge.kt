// Shared chat timeline fold: messages + activity → ordered stream items.
// Android and desktop both render [TimelineItem]s; keep the pure fold here so
// clients cannot drift on tool_result folding / thinking drops / sort order.
package dev.supermux.chat

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.proto.LogEntry

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
 * @param hideTools when true (chat detail = low), omit tool cards; activity is still ingested by the caller.
 */
fun mergeTimeline(
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    hideTools: Boolean = false,
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
    val items = ArrayList<TimelineItem>(messages.size + activity.size)
    messages.forEach { items.add(TimelineItem.Msg(it)) }
    if (!hideTools) {
        for (e in activity) {
            when (e.kind) {
                "tool" -> {
                    val status = e.callId?.let { resultStatus[it] } ?: ToolStatus.RUNNING
                    val output = e.callId?.let { resultDetail[it] }
                    val resultBody = e.callId?.let { resultBodies[it] }
                    items.add(TimelineItem.Tool(e, status, output, resultBody))
                }
                "tool_result" -> { /* folded into the matching tool row above */ }
                // "thinking" (and any other non-tool kind) is intentionally dropped.
                else -> { /* dropped */ }
            }
        }
    }
    return items.sortedBy { item ->
        when (item) {
            is TimelineItem.Msg -> item.entry.ts
            is TimelineItem.Tool -> item.event.ts
        }
    }
}
