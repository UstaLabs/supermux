/**
 * Subagents in the chat: the timeline card ([SubagentCard]) and the running strip over the
 * composer ([RunningStrip]). Same visual family as the request cards — a hairline-bordered row
 * surface, compact 8dp [CardButton]s, no Material pills.
 *
 * The card is the unit of work: it takes the place of the parent's spawning tool row and swallows
 * the subagent's own rows (see `mergeTimeline`), so the transcript reads "the agent delegated this,
 * here is how it went" instead of a burst of unlabelled tool calls. Collapsed by default: header
 * (what, who, status, time), one live activity line while it runs, one stats line. Expanded: the
 * prompt, the nested rows, the result, and the Message / Stop actions.
 *
 * A per-subagent screen is a later step; everything here keys off [Subagent.id] and takes its
 * actions as lambdas, so a detail screen can reuse the same pieces.
 *
 * T3: expanded, the card is a THREAD — its task, the user's messages, its replies and its tool rows
 * in time order ([threadOf]) — with a reply box at the foot. Actions are truthful: the reply box only
 * while [Subagent.canMessage], Stop only while [Subagent.canStop], and otherwise the agent's own
 * one-line reason where the control would be (never a disabled dead button). Everything is called
 * by [displayName] — the real name when there is one.
 *
 * Test tags: `subagent-card:<id>`, `subagent-card-header:<id>`, `subagent-card-body:<id>`,
 * `subagent-activity:<id>`, `subagent-unread:<id>`, `subagent-ended:<id>`, `subagent-task:<id>`,
 * `subagent-thread-mine:<id>:<i>`, `subagent-thread-reply:<id>:<i>`, `subagent-message-field:<id>`,
 * `subagent-message-send:<id>`, `subagent-relay-hint:<id>`, `subagent-cannot-message:<id>`,
 * `subagent-cannot-stop:<id>`, `subagent-stop:<id>`, `subagent-error:<id>`, `subagent-note:<id>`,
 * `subagent-marker:<entryId>`, `subagent-strip`, `subagent-strip-row:<id>`, `subagent-strip-more`.
 */
package dev.supermux.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.chat.parseChatTs
import dev.supermux.net.SubagentActionResult
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.Subagent
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.session.hasAgentLogo
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import androidx.compose.material.icons.outlined.Info

// ── pure helpers (unit-tested) ────────────────────────────────────────────────────────────────

/** "12s", "2m 14s", "1h 03m" — the compact clock both the card and the strip tick with. */
internal fun compactElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600 / 60).toString().padStart(2, '0')}m"
    }
}

/** 950 → "950", 23_000 → "23k", 1_250_000 → "1.2M". */
internal fun compactCount(n: Long): String = when {
    n < 1_000 -> "$n"
    n < 10_000 -> "${(n / 100) / 10.0}k".replace(".0k", "k")
    n < 1_000_000 -> "${n / 1_000}k"
    else -> "${(n / 100_000) / 10.0}M".replace(".0M", "M")
}

/** "14 tool calls · 23k tokens" — only the counters the agent actually reported. */
internal fun subagentStatsLine(s: Subagent): String? = listOfNotNull(
    s.stats.toolCalls?.takeIf { it > 0 }?.let { if (it == 1) "1 tool call" else "$it tool calls" },
    s.stats.tokens?.takeIf { it > 0 }?.let { "${compactCount(it)} tokens" },
    s.stats.turns?.takeIf { it > 1 }?.let { "$it turns" },
).joinToString(" · ").ifEmpty { null }

/** How long it ran (finished) or has been running (live). Null when the start is unknown. */
internal fun subagentElapsedMs(s: Subagent, now: Long): Long? = when {
    !s.running && s.stats.durationMs != null -> s.stats.durationMs
    s.startedAt <= 0 -> null
    s.running -> now - s.startedAt
    else -> s.endedAt?.let { it - s.startedAt }
}

/**
 * The view the card renders. A bare placeholder (`Subagent(id)` for rows whose view has not
 * arrived) defaults to status "running"; if its spawning tool row already finished, trust that
 * instead of spinning forever, and borrow the spawn row's description as the label.
 */
internal fun effectiveSubagent(item: TimelineItem.SubagentCard): Subagent {
    val s = item.subagent
    val placeholder = s.startedAt <= 0 && s.description == null && s.name == null
    if (!placeholder) return s
    val spawn = item.spawn
    val status = when (spawn?.status) {
        ToolStatus.DONE -> "completed"
        ToolStatus.ERROR -> "failed"
        else -> s.status
    }
    return s.copy(status = status, description = spawn?.event?.description ?: s.description)
}

/** One line of the running strip: a subagent, or a background task with no subagent behind it. */
internal data class RunningItem(
    val key: String,
    val label: String,
    val activity: String?,
    val startedAt: Long,
    /** Non-null for a subagent — the strip row then jumps to its card. */
    val subagentId: String?,
)

/**
 * Everything that is running, once: subagents first (oldest first), then background tasks that
 * are NOT one of those subagents seen through the task channel (same launching call, or the same
 * id) — Claude reports a background agent both ways, and two rows for one agent is noise.
 */
internal fun runningItems(subagents: List<Subagent>, bgTasks: List<ServerFrame.BgTask>): List<RunningItem> {
    val running = subagents.filter { it.running }.sortedBy { it.startedAt }
    val calls = subagents.mapNotNull { it.parentCallId }.toSet()
    val ids = subagents.flatMap { listOfNotNull(it.id, it.nativeId) }.toSet()
    val tasks = bgTasks.filter { t ->
        t.status == "running" && t.id !in ids && (t.callId == null || t.callId !in calls)
    }
    return running.map { RunningItem("s:${it.id}", it.displayName, it.activity?.takeIf { a -> a.isNotBlank() }, it.startedAt, it.id) } +
        tasks.map { RunningItem("t:${it.id}", it.label.ifBlank { it.kind }, if (it.kind == "shell") "background shell" else it.kind, it.startedAt, null) }
}

/** One bar on a strip lane: a tool call, or a run of the same tool back to back (epoch millis). */
internal data class LaneBar(
    val start: Long,
    val end: Long,
    val label: String,
    /** The call still in flight — it grows into "now". */
    val live: Boolean = false,
    val failed: Boolean = false,
    /** The tool's glyph, as the chat's tool-call rows draw it. */
    val glyph: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** A gap between calls: the model thinking ([live] while it still is). */
    val thinking: Boolean = false,
    /**
     * Which row of the lane it sits in: calls the agent runs AT THE SAME TIME stack into rows
     * 0, 1, 2… A call's row is fixed when it starts and never changes. Thinking is always row 0.
     */
    val row: Int = 0,
)

/** Gaps shorter than this between two calls are not worth a thinking segment. */
private const val LANE_MIN_THINK_MS = 400L

/** Same-tool calls this close together read as one bar ("Read ×4"), not a comb of slivers. */
private const val LANE_MERGE_GAP_MS = 1_500L

/**
 * What [s] did, as bars on a time axis: one per tool call it made (its own rows carry
 * [ActivityEvent.subagentId]), from the call to its result — or to the next call, or to now while
 * it is still in flight. Gaps are the model thinking. A subagent whose agent reports no rows is
 * one bar for its whole run, labelled with its live activity line.
 */
internal fun laneBars(s: Subagent, activity: List<ActivityEvent>, now: Long): List<LaneBar> {
    val own = activity.filter { it.subagentId == s.id }
    val results = HashMap<String, Pair<Long, Boolean>>()
    for (e in own) {
        if (e.kind != "tool_result" || e.callId == null) continue
        val t = parseChatTs(e.ts) ?: continue
        results[e.callId!!] = t to (e.title == "error" || e.phase == "failed")
    }
    val seen = HashSet<String>()
    val calls = own
        .filter { it.kind == "tool" && (it.callId == null || seen.add(it.callId!!)) }
        .mapNotNull { e -> parseChatTs(e.ts)?.let { e to it } }
        .sortedBy { it.second }
    val stop = if (s.running) now else (s.endedAt ?: now)
    if (calls.isEmpty()) {
        if (s.startedAt <= 0) return emptyList()
        val label = s.activity?.takeIf { it.isNotBlank() } ?: s.displayName
        return listOf(LaneBar(s.startedAt, maxOf(stop, s.startedAt), label, live = s.running, failed = s.status == "failed"))
    }
    data class Call(val tool: String, val bar: LaneBar)
    // An agent that reports results: each call runs until ITS result (several can be in flight at
    // once). One that never reports any: calls are taken as sequential, each ending at the next.
    val reportsResults = results.isNotEmpty()
    val raw = calls.mapIndexed { i, (e, t) ->
        val result = e.callId?.let { results[it] }
        val next = if (reportsResults) null else calls.getOrNull(i + 1)?.second
        val live = result == null && next == null && s.running
        val failed = result?.second == true
        val end = result?.first ?: next ?: stop
        // Phrased exactly as the chat's tool-call rows phrase it ("Reading auth.ts", "Edited x.sql").
        val data = e.toToolCallData(if (failed) ToolStatus.ERROR else if (live) ToolStatus.RUNNING else ToolStatus.DONE)
        // One phrasing for the call's whole life: a label that changes when it settles is a jump.
        val label = data.title
        Call(e.tool ?: "tool", LaneBar(t, maxOf(end, t), label, live = live, failed = failed, glyph = data.tool.glyph))
    }
    // Every call keeps its own bar: merging neighbours would move a bar's start and rewrite its
    // text under the user's eyes. Overlapping calls take the first row free at their start
    // (greedy, in start order — so a later call never moves an earlier one).
    val rowEnds = mutableListOf<Long>()
    val out = raw.map { c ->
        val b = c.bar
        val row = rowEnds.indexOfFirst { it <= b.start }.let { if (it < 0) rowEnds.size.also { rowEnds += 0L } else it }
        rowEnds[row] = b.end
        // Past LANE_MAX_ROWS concurrent calls, the extras share the last row.
        b.copy(row = row.coerceAtMost(LANE_MAX_ROWS - 1))
    }
    // The gaps are the model thinking — when NO call is in flight: before the first call, between
    // calls, and while it runs with nothing in flight, from the last result up to now.
    val withThinking = mutableListOf<LaneBar>()
    var prevEnd = s.startedAt.takeIf { it > 0 }
    for (bar in out) {
        if (prevEnd != null && bar.start - prevEnd >= LANE_MIN_THINK_MS) withThinking += LaneBar(prevEnd, bar.start, "Thinking", thinking = true)
        withThinking += bar
        prevEnd = maxOf(prevEnd ?: bar.end, bar.end)
    }
    if (s.running && out.none { it.live } && prevEnd != null && now - prevEnd >= LANE_MIN_THINK_MS) {
        withThinking += LaneBar(prevEnd, now, "Thinking", live = true, thinking = true)
    }
    return withThinking
}

/**
 * The lanes show the LAST 15 SECONDS, always: tool calls last seconds, so a window sized to a
 * whole run turns them into slivers. Older steps slide off the left edge, text and all.
 */
internal const val LANE_WINDOW_MS = 15_000L

/** Spacing of the axis ticks for a window: round minutes (or seconds), at most four. */
internal fun laneTickStepMs(window: Long): Long = when {
    window <= 15_000 -> 5_000
    window <= 60_000 -> 15_000
    window <= 120_000 -> 30_000
    window <= 300_000 -> 60_000
    window <= 600_000 -> 120_000
    window <= 1_800_000 -> window / 3
    else -> 900_000
}

/** An axis tick "ms ago": "−5m", "−30s". */
internal fun laneTickLabel(msAgo: Long): String =
    if (msAgo % 60_000 == 0L) "−${msAgo / 60_000}m" else "−${msAgo / 1000}s"

/** What a session-list row counts: running subagents (the broker's recent-finished stay out). */
fun runningSubagentCount(subagents: List<Subagent>?): Int = subagents?.count { it.running } ?: 0

// ── names, endings, the thread (pure; unit-tested) ────────────────────────────────────────────

/**
 * Agent TYPES that agents report in `name` (Claude `subagent_type`): not a name a person would
 * call it by. A real name (Codex nickname "Anscombe", a Cursor agent name) is a capitalised word
 * or phrase without dashes.
 */
private val AGENT_TYPES = setOf("Explore", "Plan", "Task", "Agent")

internal fun isAgentType(name: String): Boolean =
    name in AGENT_TYPES || name.any { it == '-' || it == '_' || it == '/' } || name.first().isLowerCase()

/** Its real name, when it has one ("Anscombe"); null for a bare type ("general-purpose"). */
internal val Subagent.realName: String?
    get() = name?.trim()?.takeIf { it.isNotEmpty() && !isAgentType(it) }

/** The agent type as a tag ("Explore", "general-purpose"), when [name] is a type. */
internal val Subagent.typeTag: String?
    get() = name?.trim()?.takeIf { it.isNotEmpty() && isAgentType(it) }

/** What the user calls it everywhere — card, strip, marker: name, else description, else id. */
internal val Subagent.displayName: String
    get() = realName ?: description?.takeIf { it.isNotBlank() } ?: id.take(8)

/** How it ended, in words (null while running): who ended it matters more than the phase. */
internal fun endedLabel(s: Subagent): String? = when {
    s.running -> null
    s.status == "failed" -> "failed"
    s.endedBy == "parent" -> "closed by the main agent"
    s.endedBy == "client" -> "stopped by you"
    s.status == "cancelled" -> "stopped"
    else -> "done"
}

/** One block of the expanded card, in time order. */
internal sealed interface ThreadEntry {
    /** What the main agent asked it to do. */
    data class Task(val text: String) : ThreadEntry
    /** A message the user sent it. */
    data class Mine(val text: String, val ts: String) : ThreadEntry
    /** Something it said back. */
    data class Reply(val text: String, val ts: String) : ThreadEntry
    /** Consecutive tool rows between two messages. */
    data class Steps(val tools: List<TimelineItem.Tool>) : ThreadEntry
    /** Its final result, only when the thread does not already end with it. */
    data class Result(val text: String, val clipped: Boolean) : ThreadEntry
    /** Why it failed. */
    data class Failure(val text: String) : ThreadEntry
}

private fun TimelineItem.Activity.messageText(): String = (event.text ?: event.title).orEmpty()

private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

/**
 * The card's thread: its task, the user's messages, its replies and its tool rows interleaved in
 * time order (the children already are). Falls back to [Subagent.prompt] for the task on a broker
 * that sends no task row, and adds the result only when no reply already says it.
 */
internal fun threadOf(s: Subagent, children: List<TimelineItem>, spawnOutput: String? = null): List<ThreadEntry> {
    val out = ArrayList<ThreadEntry>()
    var steps = ArrayList<TimelineItem.Tool>()
    fun flush() { if (steps.isNotEmpty()) { out += ThreadEntry.Steps(steps); steps = ArrayList() } }
    for (c in children) {
        when {
            c is TimelineItem.Tool -> steps += c
            c is TimelineItem.Activity && c.event.kind == "subagent_message" -> {
                val text = c.messageText()
                if (text.isBlank()) continue
                flush()
                out += when {
                    c.event.direction == "from" -> ThreadEntry.Reply(text, c.event.ts)
                    c.event.sender == "user" -> ThreadEntry.Mine(text, c.event.ts)
                    else -> ThreadEntry.Task(text)
                }
            }
            // Reasoning/plan rows of a subagent stay out of its thread: the thread is the dialogue.
            else -> Unit
        }
    }
    flush()
    if (out.none { it is ThreadEntry.Task }) {
        s.prompt?.takeIf { it.isNotBlank() }?.let { out.add(0, ThreadEntry.Task(it)) }
    }
    val result = s.result?.takeIf { it.isNotBlank() } ?: spawnOutput?.takeIf { it.isNotBlank() && !s.running }
    if (result != null) {
        if (s.status == "failed") {
            out += ThreadEntry.Failure(result)
        } else {
            val said = out.filterIsInstance<ThreadEntry.Reply>().map { norm(it.text) }
            val r = norm(result)
            if (said.none { it == r || it.contains(r) || (r.length > 40 && r.contains(it) && it.length > r.length / 2) }) {
                out += ThreadEntry.Result(result, s.resultClipped == true)
            }
        }
    }
    return out
}

/** "↪ to Anscombe: Is it only users?" → "Is it only users?" (the broker's transcript wording). */
internal fun markerBody(text: String): String {
    if (!text.startsWith("↪ to ")) return text
    val cut = text.indexOf(": ")
    return if (cut > 0) text.substring(cut + 2) else text
}

// ── shared bits ───────────────────────────────────────────────────────────────────────────────

/** Wall clock that ticks once a second while [live]; frozen (no recomposition) otherwise. */
@Composable
internal fun rememberTickingNow(live: Boolean): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(live) {
        now = Clock.System.now().toEpochMilliseconds()
        while (live) {
            delay(1000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

/** The quiet row surface cards in the transcript sit on (a faint lift in dark, white in light). */
@Composable
private fun cardSurface(): Color {
    val cs = MaterialTheme.colorScheme
    return if (cs.surface.luminance() < 0.5f) cs.surfaceContainer else cs.surfaceContainerLowest
}

/** Actions the card and the strip need, bound to one session. */
class SubagentActions(
    val message: suspend (subagentId: String, text: String) -> SubagentActionResult = { _, _ -> SubagentActionResult(ok = false) },
    val stop: suspend (subagentId: String) -> SubagentActionResult = { SubagentActionResult(ok = false) },
)

/** What a [TimelineItemRow] needs to draw subagent cards: expansion state + actions. */
class SubagentUi(
    val isExpanded: (String) -> Boolean,
    val onToggle: (String) -> Unit,
    val actions: SubagentActions,
    /** Replies since the user last had this card open (client-side, per session view). */
    val unread: (Subagent) -> Int = { 0 },
    /** Open the card and scroll to it — the strip and the main-chat markers both jump here. */
    val open: (String) -> Unit = {},
    /** The display name for a subagent id (markers), when the view knows it. */
    val nameOf: (String) -> String? = { null },
)

// ── the card ──────────────────────────────────────────────────────────────────────────────────

/** Children shown before "Show N earlier" takes over — a 60-call explore must not bury the chat. */
private const val VISIBLE_CHILDREN = 8

@Composable
fun SubagentCard(
    item: TimelineItem.SubagentCard,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: SubagentActions,
    media: dev.supermux.media.AttachmentMedia = dev.supermux.media.AttachmentMedia.None,
    onOpenFile: (dev.supermux.ui.FilePathRef) -> Unit = {},
    highDetail: Boolean = false,
    /** Replies the user has not seen yet; the "N new replies" badge while collapsed. */
    unread: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    val s = effectiveSubagent(item)
    val now = rememberTickingNow(s.running)
    val failed = s.status == "failed"
    val shape = RoundedCornerShape(Radii.md)
    val border = when {
        failed -> cs.error.copy(alpha = 0.45f)
        else -> cs.outlineVariant.copy(alpha = 0.8f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs)
            .clip(shape)
            .background(cardSurface())
            .border(Stroke.hairline, border, shape)
            .animateContentSize()
            .testTag("subagent-card:${s.id}"),
    ) {
        // ── header ── badge | title + status + chevron / tags + stats / live ticker
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag("subagent-card-header:${s.id}")
                .padding(start = Space.md, end = Space.sm, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgentBadge(status = s.status)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        s.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(Space.sm))
                    StatusBlock(s, now)
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp).size(IconSize.md + 2.dp),
                    )
                }
                // With a real name as the title, the description is the line under it.
                s.description?.takeIf { s.realName != null && it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurface.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // New replies, then who + how: the agent type as a mono tag, "background", counters.
                val stats = subagentStatsLine(s)
                val type = s.typeTag
                val badge = unread.takeIf { it > 0 && !expanded }
                if (badge != null || type != null || s.background == true || stats != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        badge?.let { UnreadBadge(it, s.id) }
                        type?.let { Tag(it) }
                        if (s.background == true) Tag("background")
                        if (stats != null) {
                            Text(
                                stats,
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                val activity = s.activity?.takeIf { it.isNotBlank() && s.running }
                if (activity != null) {
                    ActivityTicker(activity, Modifier.testTag("subagent-activity:${s.id}"))
                }
            }
        }
        if (expanded) {
            ExpandedBody(item, s, actions, media, onOpenFile, highDetail)
        }
    }
}

/** 24dp round badge with the agent glyph — distinct from the tool rows' dots and spinners. */
@Composable
private fun AgentBadge(status: String) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        "failed" -> cs.errorContainer to cs.onErrorContainer
        "cancelled" -> cs.surfaceContainerHighest to cs.onSurfaceVariant
        else -> cs.primaryContainer to cs.onPrimaryContainer
    }
    Box(Modifier.size(24.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.SmartToy, contentDescription = "Subagent", tint = fg, modifier = Modifier.size(14.dp))
    }
}

/** "2 new replies" — accent pill, cleared when the card is opened. */
@Composable
private fun UnreadBadge(n: Int, id: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        if (n == 1) "1 new reply" else "$n new replies",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = cs.onPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(cs.primary)
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .testTag("subagent-unread:$id"),
    )
}

/**
 * The main chat's trace of a message the user sent a subagent ("↪ to Anscombe: …"): a compact,
 * right-aligned line naming who it went to, that opens and scrolls to that agent's card — the
 * conversation itself lives in the card's thread.
 */
@Composable
fun SubagentMarker(entryId: String, text: String, name: String?, onOpen: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.md)
    val body = markerBody(text)
    val who = name ?: text.removePrefix("↪ to ").substringBefore(": ").takeIf { text.startsWith("↪ to ") } ?: "a subagent"
    Row(Modifier.fillMaxWidth().padding(start = Space.xl), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier
                .clip(shape)
                .border(Stroke.hairline, cs.outlineVariant, shape)
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .testTag("subagent-marker:$entryId")
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.SmartToy, contentDescription = null, tint = cs.primary, modifier = Modifier.padding(top = 2.dp).size(IconSize.sm))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("To $who", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(body, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A tiny mono tag: agent type ("Explore"), "background". */
@Composable
private fun Tag(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        fontFamily = MonoFontFamily,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        color = cs.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(cs.onSurface.copy(alpha = 0.07f))
            .padding(horizontal = 5.dp),
    )
}

/** Right side of the header: spinner + live clock, or how it ended. */
@Composable
private fun StatusBlock(s: Subagent, now: Long) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val elapsed = subagentElapsedMs(s, now)?.let(::compactElapsed)
    val (text, color) = when (s.status) {
        "running" -> (elapsed ?: "running") to cs.primary
        "failed" -> (if (elapsed != null) "failed · $elapsed" else "failed") to cs.error
        else -> {
            // Who ended it reads first ("closed by the main agent", "stopped by you"); a plain
            // finish keeps its time.
            val ended = endedLabel(s) ?: s.status
            (if (ended == "done" && elapsed != null) "done · $elapsed" else ended) to cs.onSurfaceVariant
        }
    }
    Row(
        Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (s.running) {
            CircularProgressIndicator(Modifier.size(11.dp), color = cs.primary, strokeWidth = 1.5.dp)
        } else if (endedLabel(s) == "done") {
            Box(Modifier.size(6.dp).clip(CircleShape).background(sem.success))
        }
        // The clock is mono (it ticks); a sentence of who-ended-it is not.
        if (s.running || text.first().isDigit() || text.startsWith("done") || text.startsWith("failed")) {
            Text(text, fontFamily = MonoFontFamily, fontSize = 11.sp, color = color, maxLines = 1)
        } else {
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, modifier = Modifier.testTag("subagent-ended:${s.id}"))
        }
    }
}

/** "↳ Reading src/main.ts" — one ellipsized line, the most recent thing it did. */
@Composable
private fun ActivityTicker(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "liga 0, calt 0"),
        fontFamily = MonoFontFamily,
        color = cs.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun ExpandedBody(
    item: TimelineItem.SubagentCard,
    s: Subagent,
    actions: SubagentActions,
    media: dev.supermux.media.AttachmentMedia,
    onOpenFile: (dev.supermux.ui.FilePathRef) -> Unit,
    highDetail: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var draft by remember(s.id) { mutableStateOf("") }
    var busy by remember(s.id) { mutableStateOf(false) }
    var messageError by remember(s.id) { mutableStateOf<String?>(null) }
    var stopError by remember(s.id) { mutableStateOf<String?>(null) }
    var note by remember(s.id) { mutableStateOf<String?>(null) }
    val thread = remember(s, item.children, item.spawn) { threadOf(s, item.children, item.spawn?.output) }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        busy = true; messageError = null; stopError = null; note = null
        scope.launch {
            val r = actions.message(s.id, text)
            busy = false
            if (r.ok) {
                draft = ""
                note = if (r.via == "relay" || s.messaging == "relay") "Sent via the main agent" else null
            } else {
                messageError = r.error?.takeIf { it.isNotBlank() } ?: "Couldn't send the message"
            }
        }
    }
    fun stop() {
        if (busy) return
        busy = true; messageError = null; stopError = null; note = null
        scope.launch {
            val r = actions.stop(s.id)
            busy = false
            if (!r.ok) stopError = r.error?.takeIf { it.isNotBlank() } ?: "Couldn't stop this agent"
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .testTag("subagent-card-body:${s.id}")
            // Aligned with the title (badge 24 + gap 10), so the card reads as one column.
            .padding(start = Space.md + 34.dp, end = Space.md, bottom = Space.md),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        thread.forEachIndexed { i, entry ->
            when (entry) {
                is ThreadEntry.Task -> TaskBlock(entry.text, s.id)
                is ThreadEntry.Mine -> MineBubble(entry.text, Modifier.testTag("subagent-thread-mine:${s.id}:$i"))
                is ThreadEntry.Reply -> ReplyBlock(s.displayName, entry.text, onOpenFile, Modifier.testTag("subagent-thread-reply:${s.id}:$i"))
                is ThreadEntry.Steps -> StepsBlock(entry.tools, highDetail, key = "${s.id}:$i")
                is ThreadEntry.Result -> ResultBlock(entry, onOpenFile)
                is ThreadEntry.Failure -> Text(
                    entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFontFamily,
                    color = cs.error,
                    modifier = Modifier.testTag("subagent-failure:${s.id}"),
                )
            }
        }
        if (thread.isEmpty() && s.running) {
            Text("Getting started…", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }

        // ── what the user can do, truthfully ──
        // The flags are read from `s` on every recomposition: a refusal that turns canMessage off
        // replaces the field with the agent's reason the moment the update lands.
        Column(Modifier.fillMaxWidth().testTag("subagent-actions:${s.id}"), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (s.canMessage) {
            MessageField(
                id = s.id,
                name = s.displayName,
                value = draft,
                enabled = !busy,
                relay = s.messaging == "relay",
                onChange = { draft = it },
                onSend = ::send,
            )
            messageError?.let { ErrorLine(it, s.id) }
        }
        note?.takeIf { s.canMessage }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.testTag("subagent-note:${s.id}"))
        }
        val reasons = listOfNotNull(
            s.cannotMessageReason?.takeIf { !s.canMessage && it.isNotBlank() }?.let { it to "subagent-cannot-message:${s.id}" },
            s.cannotStopReason?.takeIf { !s.canStop && s.running && it.isNotBlank() }?.let { it to "subagent-cannot-stop:${s.id}" },
        )
        if (reasons.isNotEmpty() || s.canStop) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    reasons.forEach { (text, tag) -> ReasonLine(text, Modifier.testTag(tag)) }
                }
                if (s.canStop) {
                    CardButton(
                        label = "Stop",
                        style = CardButtonStyle.Danger,
                        enabled = !busy,
                        testTag = "subagent-stop:${s.id}",
                        leading = Icons.Filled.Stop,
                        onClick = ::stop,
                    )
                }
            }
        }
        stopError?.takeIf { s.canStop }?.let { ErrorLine(it, s.id) }
        }
    }
}

@Composable
private fun ErrorLine(text: String, id: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("subagent-error:$id"))
}

/** Why an action is not on offer — one quiet line where the control would have been. */
@Composable
private fun ReasonLine(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(IconSize.sm))
        Text(text, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

/** Its brief from the main agent: a quiet quote, clipped until asked for. */
@Composable
private fun TaskBlock(text: String, id: String) {
    val cs = MaterialTheme.colorScheme
    var open by remember(id, text) { mutableStateOf(false) }
    val long = text.length > 220 || text.count { it == '\n' } >= 4
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(cs.onSurface.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("subagent-task:$id"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Task from the main agent", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurface.copy(alpha = 0.85f),
            maxLines = if (open || !long) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (long) Toggle(if (open) "Show less" else "Show more") { open = !open }
    }
}

/** The user's own message: right-aligned, accent-tinted, like the main chat's user lines. */
@Composable
private fun MineBubble(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(start = Space.xl), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("You", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.md))
                .background(cs.primary.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * A card's replies read one step smaller than the main chat's prose: they are nested inside a card,
 * and at the chat's reading size a three-exchange thread filled a phone screen per reply.
 */
@Composable
private fun ThreadProse(content: @Composable () -> Unit) {
    val t = MaterialTheme.typography
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = t.copy(bodyLarge = t.bodyMedium)) {
        content()
    }
}

/** What it said: its name, then the reply as markdown. */
@Composable
private fun ReplyBlock(name: String, text: String, onOpenFile: (dev.supermux.ui.FilePathRef) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.primary)
        ThreadProse { MarkdownBody(text, Modifier.fillMaxWidth(), onOpenFile = onOpenFile) }
    }
}

/** Steps shown before a long run folds into "Show N more steps". */
private const val VISIBLE_STEPS = 4

/** A run of tool rows between two messages, compact, under a rule. */
@Composable
private fun StepsBlock(tools: List<TimelineItem.Tool>, highDetail: Boolean, key: String) {
    val cs = MaterialTheme.colorScheme
    var all by remember(key) { mutableStateOf(false) }
    val hidden = if (all) 0 else (tools.size - VISIBLE_STEPS).coerceAtLeast(0)
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = cs.outlineVariant,
                    start = Offset(0f, 2.dp.toPx()),
                    end = Offset(0f, size.height - 2.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(start = Space.md),
    ) {
        if (hidden > 0) Toggle(if (hidden == 1) "Show 1 earlier step" else "Show $hidden earlier steps") { all = true }
        tools.drop(hidden).forEach { ToolCard(it.event, it.status, it.output, it.resultBody, highDetail) }
    }
}

@Composable
private fun ResultBlock(entry: ThreadEntry.Result, onOpenFile: (dev.supermux.ui.FilePathRef) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember(entry.text) { mutableStateOf(false) }
    val long = entry.text.length > 600 || entry.text.count { it == '\n' } > 8
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Result", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().then(if (long && !open) Modifier.heightIn(max = 180.dp).clip(RoundedCornerShape(0.dp)) else Modifier)) {
            ThreadProse { MarkdownBody(entry.text, Modifier.fillMaxWidth(), onOpenFile = onOpenFile) }
        }
        if (long) Toggle(if (open) "Show less" else "Show more") { open = !open }
        if (entry.clipped && (open || !long)) {
            Text("The broker kept the first 8k characters of this result.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Toggle(text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = cs.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.sm))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 4.dp),
    )
}

/**
 * The reply box at the foot of the thread: a row surface like the request cards' "Other" field and a
 * compact Send. Always there while the agent can take a message — no extra "Message" step — and
 * never there when it can't. For a relayed message, the honest caveat that the main agent carries it.
 */
@Composable
private fun MessageField(
    id: String,
    name: String,
    value: String,
    enabled: Boolean,
    relay: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.sm)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cs.surfaceContainerLowest)
                .border(Stroke.hairline, if (focused) cs.primary else cs.outline, shape)
                .padding(start = Space.md, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused }
                    .padding(vertical = 10.dp)
                    .testTag("subagent-message-field:$id"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text("Message $name…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                },
            )
            IconBtn(Icons.AutoMirrored.Filled.Send, "Send", "subagent-message-send:$id", cs.primary, enabled = enabled && value.isNotBlank(), onClick = onSend)
        }
        if (relay) {
            Text(
                "Sent via the main agent — it passes your message on.",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.testTag("subagent-relay-hint:$id"),
            )
        }
    }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(Radii.sm))
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(IconSize.md))
    }
}

// ── the running strip ─────────────────────────────────────────────────────────────────────────

/** One lane of the strip: a subagent (running, or finished inside the window) or a background task. */
private data class Lane(
    val key: String,
    val name: String,
    val model: String?,
    val subagentId: String?,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val bars: List<LaneBar>,
)

private val LANE_HEIGHT = 46.dp
/** Space between stacked rows of one lane (calls running at the same time). */
private val LANE_ROW_GAP = 4.dp
/** A lane grows to at most this many rows; calls past it share the last row. */
internal const val LANE_MAX_ROWS = 3
/** The lanes area stops growing here and scrolls (the axis stays pinned above it). */
private val LANES_MAX_HEIGHT = 280.dp
private val LANE_BAR_HEIGHT = 28.dp
/** The track fades out over its first stretch, so bars slide off instead of being cut. */
private val LANE_FADE = 32.dp

/**
 * ONE strip over the composer for everything still running — subagents and background tasks.
 * Collapsed it is the compact strip: one item is its row (what it is doing right now, a ticking
 * clock; a subagent row jumps to and opens its card), several fold into a single "N running"
 * line. Expanded (the chevron / that line) it becomes LANES over the last minute, ending at
 * "now": a lane per agent, a bar per tool call it made ([laneBars]), the call in flight growing
 * into now; subagents that finished inside the minute stay as quiet lanes. Absent when nothing
 * runs; its height animates so the composer eases instead of jumping.
 *
 * Bars are placed in the layout phase off [rememberGlidingNow], so the track slides left
 * continuously (under a pixel per step) without recomposing the lanes.
 */
@Composable
fun RunningStrip(
    subagents: List<Subagent>,
    bgTasks: List<ServerFrame.BgTask>,
    onOpenSubagent: (String) -> Unit,
    modifier: Modifier = Modifier,
    activity: List<ActivityEvent> = emptyList(),
    /** The session's agent kind ("claude", "codex", …): its subagents wear its logo. */
    agent: String? = null,
) {
    val items = remember(subagents, bgTasks) { runningItems(subagents, bgTasks) }
    Box(modifier.fillMaxWidth().animateContentSize()) {
        if (items.isEmpty()) return@Box
        val cs = MaterialTheme.colorScheme
        val now = rememberTickingNow(true)
        var open by remember { mutableStateOf(false) }
        val folds = items.size > 1
        val shape = RoundedCornerShape(if (open) 10.dp else Radii.md)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = Space.sm)
                .clip(shape)
                .background(if (open) cardSurface() else cs.surfaceContainerHigh)
                .border(Stroke.hairline, cs.outlineVariant.copy(alpha = if (open) 0.6f else 0.7f), shape)
                .testTag("subagent-strip"),
        ) {
            val agents = items.count { it.subagentId != null }
            val tasks = items.size - agents
            val summary = listOfNotNull(
                agents.takeIf { it > 0 }?.let { if (it == 1) "1 agent" else "$it agents" },
                tasks.takeIf { it > 0 }?.let { if (it == 1) "1 task" else "$it tasks" },
            ).joinToString(" · ") + " running"
            when {
                open -> LanesView(items, subagents, bgTasks, activity, agent, now, summary, onCollapse = { open = false }, onOpenSubagent = onOpenSubagent)
                folds -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open = true }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("subagent-strip-more")
                        .padding(horizontal = Space.md, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Text(summary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f))
                    Text("Show", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                    Icon(Icons.Filled.ExpandLess, contentDescription = null, tint = cs.primary, modifier = Modifier.size(IconSize.md))
                }
                else -> StripRow(items.single(), now, onOpenSubagent, onExpand = { open = true })
            }
        }
    }
}

@Composable
private fun StripRow(item: RunningItem, now: Long, onOpenSubagent: (String) -> Unit, onExpand: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clickable = item.subagentId != null
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onOpenSubagent(item.subagentId!!) }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .testTag("subagent-strip-row:${item.subagentId ?: item.key}")
            .heightIn(min = 36.dp)
            .padding(start = Space.md, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (clickable) {
            CircularProgressIndicator(Modifier.size(11.dp), color = cs.primary, strokeWidth = 1.5.dp)
        } else {
            Icon(Icons.Filled.Terminal, contentDescription = "Background task", tint = cs.onSurfaceVariant, modifier = Modifier.size(12.dp))
        }
        // Label keeps its words; the activity takes whatever width is left and ellipsizes.
        Text(
            item.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Text(
            item.activity.orEmpty(),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "liga 0, calt 0"),
            fontFamily = MonoFontFamily,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.startedAt > 0) {
            Text(compactElapsed(now - item.startedAt), fontFamily = MonoFontFamily, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1)
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(Radii.sm))
                .clickable(onClick = onExpand)
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag("subagent-strip-lanes"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ExpandLess, contentDescription = "Show timeline", tint = cs.onSurfaceVariant, modifier = Modifier.size(IconSize.md))
        }
    }
}

/** The expanded strip: a header that folds it back, the time axis, and a lane per item. */
@Composable
private fun LanesView(
    items: List<RunningItem>,
    subagents: List<Subagent>,
    bgTasks: List<ServerFrame.BgTask>,
    activity: List<ActivityEvent>,
    agent: String?,
    now: Long,
    summary: String,
    onCollapse: () -> Unit,
    onOpenSubagent: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val window = LANE_WINDOW_MS
    val lanes = stripLanes(items, subagents, bgTasks, activity, now, now - window)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onCollapse)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag("subagent-strip-more")
            .padding(start = Space.md, end = Space.sm, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        PulseDot(cs.primary)
        Text(summary, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            "Last ${window / 1000}s",
            fontSize = 12.sp,
            color = cs.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(cs.onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Icon(Icons.Filled.ExpandMore, contentDescription = "Show less", tint = cs.onSurfaceVariant, modifier = Modifier.size(IconSize.md))
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(cs.outlineVariant.copy(alpha = 0.45f)))
    val glide = rememberGlidingNow(window)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A phone keeps most of its width for the time axis.
        val nameWidth = if (maxWidth < 480.dp) 140.dp else 240.dp
        Column(Modifier.padding(start = Space.md, end = Space.md, top = 6.dp, bottom = 6.dp)) {
            LaneAxis(window, nameWidth)
            // Capped: past LANES_MAX_HEIGHT the lanes scroll under the pinned axis, so a busy
            // session never pushes the composer off screen.
            Column(Modifier.heightIn(max = LANES_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                lanes.forEachIndexed { i, lane -> LaneRow(lane, agent, now, glide, window, nameWidth, last = i == lanes.lastIndex, onOpenSubagent = onOpenSubagent) }
            }
        }
    }
}

/** Wall-clock millis that advance in steps of about half a pixel of a [window] wide track. */
@Composable
private fun rememberGlidingNow(window: Long): State<Long> {
    val state = remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(window) {
        val step = (window / 700).coerceIn(16L, 1_000L)
        while (true) {
            state.longValue = Clock.System.now().toEpochMilliseconds()
            delay(step)
        }
    }
    return state
}

@Composable
private fun PulseDot(color: Color) {
    val alpha by rememberInfiniteTransition(label = "strip-pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "strip-pulse-alpha",
    )
    Box(Modifier.size(7.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(color))
}

private fun stripLanes(
    items: List<RunningItem>,
    subagents: List<Subagent>,
    bgTasks: List<ServerFrame.BgTask>,
    activity: List<ActivityEvent>,
    now: Long,
    from: Long,
): List<Lane> {
    val byId = subagents.associateBy { it.id }
    val running = items.mapNotNull { it.subagentId?.let(byId::get) }
    val recent = subagents.filter { !it.running && (it.endedAt ?: 0) >= from && it.startedAt > 0 }
    val agentLanes = (running + recent).sortedBy { it.startedAt }.map { s ->
        Lane("s:${s.id}", s.displayName, s.model, s.id, s.status, s.startedAt, s.endedAt, laneBars(s, activity, now))
    }
    val taskById = bgTasks.associateBy { "t:${it.id}" }
    val taskLanes = items.filter { it.subagentId == null }.map { item ->
        val t = taskById[item.key]
        Lane(
            item.key, item.label, null, null, t?.status ?: "running", item.startedAt, null,
            if (item.startedAt > 0) listOf(LaneBar(item.startedAt, Long.MAX_VALUE, item.activity ?: item.label, live = true)) else emptyList(),
        )
    }
    return agentLanes + taskLanes
}

/** The axis over the lanes: round ticks centred on their grid lines, and a "now" chip at the end. */
@Composable
private fun LaneAxis(window: Long, nameWidth: Dp) {
    val cs = MaterialTheme.colorScheme
    val step = laneTickStepMs(window)
    val ticks = generateSequence(step) { it + step }.takeWhile { it < window }.toList()
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(nameWidth))
        Layout(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            content = {
                ticks.forEach { ago ->
                    Text(laneTickLabel(ago), fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.32f), maxLines = 1, style = TabularNums)
                }
                Text(
                    "now",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.primary.copy(alpha = 0.1f))
                        .padding(horizontal = 5.dp),
                )
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = measurables.map { it.measure(loose) }
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            layout(w, h) {
                val nowChip = placeables.last()
                val nowX = w - nowChip.width
                nowChip.place(nowX, (h - nowChip.height) / 2)
                var right = nowX - 4.dp.roundToPx()
                // Nearest-to-now first (ticks[0]), so a tick that would crowd its right neighbour (or
                // the chip) is the one dropped.
                for (i in ticks.indices) {
                    val p = placeables[i]
                    val x = (w * (window - ticks[i]).toFloat() / window).toInt() - p.width / 2
                    if (x < 0 || x + p.width > right) continue
                    p.place(x, (h - p.height) / 2)
                    right = x - 6.dp.roundToPx()
                }
            }
        }
    }
}

private val TabularNums = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun LaneRow(lane: Lane, agent: String?, now: Long, glide: State<Long>, window: Long, nameWidth: Dp, last: Boolean, onOpenSubagent: (String) -> Unit) {
    // Rows in view: one per call running at the same time, eased so a second row grows in.
    val rows = 1 + (lane.bars.filter { it.end >= now - window || it.live }.maxOfOrNull { it.row } ?: 0)
    val laneHeight by animateDpAsState(LANE_HEIGHT + (LANE_BAR_HEIGHT + LANE_ROW_GAP) * (rows - 1), tween(250, easing = FastOutSlowInEasing), label = "lane-height")
    val cs = MaterialTheme.colorScheme
    val clickable = lane.subagentId != null
    val running = lane.status == "running"
    // A phone's name column has no status chip: the name dims when it finished, a failed one is red.
    val compact = nameWidth < 200.dp
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(if (clickable) Modifier.clickable { onOpenSubagent(lane.subagentId!!) }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .testTag("subagent-strip-row:${lane.subagentId ?: lane.key}")
            .height(laneHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.align(Alignment.Top).height(LANE_HEIGHT).width(nameWidth).padding(end = Space.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The bare brand mark, as everywhere else in the app (AgentLogo draws no tile).
            Box(Modifier.size(16.dp).graphicsLayer { alpha = if (running) 1f else 0.5f }, contentAlignment = Alignment.Center) {
                if (clickable && hasAgentLogo(agent)) {
                    AgentLogo(agent, 15.dp)
                } else {
                    Icon(
                        if (clickable) Icons.Filled.SmartToy else Icons.Filled.Terminal,
                        contentDescription = if (clickable) null else "Background task",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        lane.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (compact && lane.status == "failed") cs.error else cs.onSurface.copy(alpha = if (running) 0.9f else 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!compact) when (lane.status) {
                        "failed" -> LaneChip("Failed", cs.error)
                        "completed", "cancelled" -> LaneChip(if (lane.status == "completed") "Done" else "Stopped", cs.onSurfaceVariant)
                    }
                }
                val elapsed = lane.startedAt.takeIf { it > 0 }?.let { compactElapsed((lane.endedAt ?: now) - it) }
                val meta = listOfNotNull(lane.model?.takeIf { it.isNotBlank() }, elapsed).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TabularNums)
                }
            }
        }
        LaneTrack(lane, glide, window, last, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun LaneChip(text: String, color: Color) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 5.dp),
    )
}

/** A child of a [LaneTrack]: bar [i]'s surface, or its label (placed separately, so it can stick). */
private data class LaneSlot(val i: Int, val label: Boolean)

/** The lane's track: dashed grid, a divider under it, and its bars placed off [glide] in layout. */
@Composable
private fun LaneTrack(lane: Lane, glide: State<Long>, window: Long, last: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val grid = cs.onSurface.copy(alpha = 0.08f)
    val divider = cs.outlineVariant.copy(alpha = 0.4f)
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 4f)) }
    val step = laneTickStepMs(window)
    val minBar = with(LocalDensity.current) { 4.dp.roundToPx() }
    val dim = lane.status != "running"
    Layout(
        modifier = modifier
            .drawBehind {
                var ago = step
                while (ago < window) {
                    val x = size.width * (window - ago).toFloat() / window
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f, pathEffect = dash)
                    ago += step
                }
                if (!last) drawLine(divider, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
            }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true // bars past the left edge (x < 0) are cut here, after the fade
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = LANE_FADE.toPx()),
                    blendMode = BlendMode.DstIn,
                )
            },
        content = {
            val tag = lane.subagentId ?: lane.key
            lane.bars.forEachIndexed { i, bar ->
                key(bar.start, bar.thinking) {
                    if (bar.thinking) {
                        ThinkingView(bar.live, Modifier.layoutId(LaneSlot(i, label = false)).testTag("subagent-lane-think:$tag:$i"))
                    } else {
                        val (fill, ink) = laneBarColors(bar, dim)
                        LaneBarSurface(bar, fill, Modifier.layoutId(LaneSlot(i, label = false)).testTag("subagent-lane-bar:$tag:$i"))
                        LaneBarLabel(bar, ink, Modifier.layoutId(LaneSlot(i, label = true)))
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // Read the gliding clock HERE (layout phase): the track slides without recomposition.
        val now = glide.value
        val from = now - window
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val barH = LANE_BAR_HEIGHT.roundToPx()
        // Bars keep their true start even past the left edge (x < 0): a bar leaving the window
        // slides out whole and the track clips it. Its width only ever grows (the call in flight,
        // to the gliding now) and never jumps.
        val spans = lane.bars.map { bar ->
            val end = (if (bar.live) now else bar.end).coerceAtMost(now)
            // A bar that began long before the window (a background task, a whole-run bar) starts
            // at most one track width off the left edge, so its width stays a sane number of pixels.
            val start = bar.start.coerceAtLeast(from - window)
            val x = (w * (start - from).toFloat() / window).toInt()
            val bw = if (end < from) 0 else (w * (end - start).toFloat() / window).toInt().coerceAtLeast(minBar)
            x to bw
        }
        val placed = measurables.mapNotNull { m ->
            // Every child carries a slot; tolerate one that does not (a composition from before a
            // hot reload) instead of crashing the window.
            val slot = m.layoutId as? LaneSlot ?: return@mapNotNull null
            if (slot.i !in spans.indices) return@mapNotNull null
            val (x, bw) = spans[slot.i]
            val c = if (slot.label) Constraints(maxWidth = bw.coerceAtLeast(0), maxHeight = barH) else Constraints.fixed(bw.coerceAtLeast(0), barH)
            Triple(slot, m.measure(c), x)
        }
        layout(w, h) {
            placed.forEach { (slot, p, x) ->
                val bw = spans[slot.i].second
                if (bw <= 0) return@forEach
                // Row 0 sits where a one-row lane centres its bar; further rows stack below it.
                val rowTop = (LANE_HEIGHT.roundToPx() - barH) / 2 + lane.bars[slot.i].row * (barH + LANE_ROW_GAP.roundToPx())
                if (!slot.label) {
                    p.place(x, rowTop)
                } else {
                    // Sticky: the label holds at the track's left edge while its bar is still in
                    // view, and is carried out by the bar's END once that reaches it.
                    val lx = maxOf(x, 0).coerceAtMost(x + bw - p.width).coerceAtLeast(x)
                    p.place(lx, rowTop + (barH - p.height) / 2)
                }
            }
        }
    }
}

/** A bar's fill (opaque: tint over the card) and ink, easing when a call settles or fails. */
@Composable
private fun laneBarColors(bar: LaneBar, dim: Boolean): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val accent = when {
        bar.failed -> cs.error
        bar.live -> cs.primary
        else -> null
    }
    val base = cardSurface()
    val fill by animateColorAsState((accent?.copy(alpha = 0.12f) ?: cs.onSurface.copy(alpha = if (dim) 0.04f else 0.06f)).compositeOver(base), tween(300), label = "lane-bar-fill")
    val ink by animateColorAsState(accent ?: cs.onSurface.copy(alpha = if (dim) 0.45f else 0.62f), tween(300), label = "lane-bar-ink")
    return fill to ink
}

@Composable
private fun LaneBarSurface(bar: LaneBar, fill: Color, modifier: Modifier) {
    val edge = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    Box(
        modifier
            .background(fill) // square corners
            .drawBehind {
                // The live edge: where the call in flight meets now.
                if (bar.live) drawRect(edge, topLeft = Offset(size.width - 2.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
            },
    )
}

/** Glyph + text; a short bar ellipsizes the text into whatever width it has. */
@Composable
private fun LaneBarLabel(bar: LaneBar, ink: Color, modifier: Modifier) {
    Row(modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        bar.glyph?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp)) }
        Text(
            bar.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A thinking gap: a grid of squares as tall as the bars, that stays put. Live, each square fires
 * on its own rhythm — brightening and dimming like neurons — so the gap reads as activity
 * without anything travelling; settled, the grid is dark and still.
 *
 * It animates in (squares grow in, left to right, when thinking starts) and out (the firing
 * fades to the dark grid when the next call begins). Everything is read in the draw phase.
 */
@Composable
private fun ThinkingView(live: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val appear = remember { Animatable(if (live) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    val liveness = animateFloatAsState(if (live) 1f else 0f, tween(600, easing = FastOutSlowInEasing), label = "lane-think-live")
    // Seconds 0..60, looping; every square's frequency is a whole number of cycles per minute, so
    // the loop is seamless. Only runs while the gap is (or is fading from) live.
    val clock = if (live || liveness.value > 0.01f) {
        rememberInfiniteTransition(label = "lane-think").animateFloat(
            initialValue = 0f,
            targetValue = 60f,
            animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)),
            label = "lane-think-clock",
        )
    } else null
    val hot = cs.primary
    val cold = cs.onSurface
    Box(
        modifier.drawBehind {
            val sq = 3.5.dp.toPx()
            val gap = 2.5.dp.toPx()
            val inset = 3.dp.toPx()
            val pitch = sq + gap
            val rows = ((size.height + gap) / pitch).toInt().coerceAtLeast(1)
            val cols = ((size.width - 2 * inset + gap) / pitch).toInt()
            if (cols <= 0) return@drawBehind
            val top = (size.height - (rows * sq + (rows - 1) * gap)) / 2
            val t = clock?.value ?: 0f
            val l = liveness.value
            val ap = appear.value
            val color = androidx.compose.ui.graphics.lerp(cold, hot, l)
            val corner = androidx.compose.ui.geometry.CornerRadius(0.8.dp.toPx())
            for (c in 0 until cols) for (r in 0 until rows) {
                // Grow in left to right: each column starts a little after the one before it.
                val local = ((ap - 0.4f * c / cols) / 0.6f).coerceIn(0f, 1f)
                if (local <= 0f) continue
                // A stable per-square hash: its own phase and rhythm.
                var hsh = (c * 73856093) xor (r * 19349663)
                hsh = hsh xor (hsh ushr 13); hsh *= 0x5bd1e995; hsh = hsh xor (hsh ushr 15)
                val u = (hsh and 0xffff) / 65535f
                val v = ((hsh ushr 16) and 0xffff) / 65535f
                val dormant = 0.07f + 0.07f * u
                val firing = if (clock == null) dormant else {
                    val cyclesPerMin = 12 + (v * 24).toInt() // 0.2..0.6 Hz: a slow, calm flicker
                    val s = 0.5f + 0.5f * kotlin.math.sin(2 * kotlin.math.PI.toFloat() * (cyclesPerMin * t / 60f + u))
                    0.12f + 0.78f * s * s * s * s // mostly dim, now and then a bright flash
                }
                val a = (dormant + (firing - dormant) * l) * local
                val side = sq * (0.4f + 0.6f * local)
                val off = (sq - side) / 2
                drawRoundRect(
                    color.copy(alpha = a),
                    topLeft = Offset(inset + c * pitch + off, top + r * pitch + off),
                    size = androidx.compose.ui.geometry.Size(side, side),
                    cornerRadius = corner,
                )
            }
        },
    )
}
