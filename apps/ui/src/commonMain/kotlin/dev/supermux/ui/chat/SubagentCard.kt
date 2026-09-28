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
 * Test tags: `subagent-card:<id>`, `subagent-card-header:<id>`, `subagent-card-body:<id>`,
 * `subagent-activity:<id>`, `subagent-message:<id>`, `subagent-message-field:<id>`,
 * `subagent-message-send:<id>`, `subagent-stop:<id>`, `subagent-error:<id>`, `subagent-strip`,
 * `subagent-strip-row:<id>`, `subagent-strip-more`.
 */
package dev.supermux.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.net.SubagentActionResult
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.Subagent
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

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
    return running.map { RunningItem("s:${it.id}", it.label, it.activity?.takeIf { a -> a.isNotBlank() }, it.startedAt, it.id) } +
        tasks.map { RunningItem("t:${it.id}", it.label.ifBlank { it.kind }, if (it.kind == "shell") "background shell" else it.kind, it.startedAt, null) }
}

/** What a session-list row counts: running subagents (the broker's recent-finished stay out). */
fun runningSubagentCount(subagents: List<Subagent>?): Int = subagents?.count { it.running } ?: 0

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
    loadBytes: suspend (String) -> ByteArray? = { null },
    onOpenFile: (dev.supermux.ui.FilePathRef) -> Unit = {},
    highDetail: Boolean = false,
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
                        s.label,
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
                // Who + how: the agent type as a mono tag, "background", then the counters.
                val stats = subagentStatsLine(s)
                val type = s.name?.takeIf { it.isNotBlank() && it != s.label }
                if (type != null || s.background == true || stats != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            ExpandedBody(item, s, actions, loadBytes, onOpenFile, highDetail)
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
        "completed" -> (if (elapsed != null) "done · $elapsed" else "done") to cs.onSurfaceVariant
        "failed" -> (if (elapsed != null) "failed · $elapsed" else "failed") to cs.error
        "cancelled" -> "stopped" to cs.onSurfaceVariant
        else -> s.status to cs.onSurfaceVariant
    }
    Row(
        Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (s.running) {
            CircularProgressIndicator(Modifier.size(11.dp), color = cs.primary, strokeWidth = 1.5.dp)
        } else if (s.status == "completed") {
            Box(Modifier.size(6.dp).clip(CircleShape).background(sem.success))
        }
        Text(text, fontFamily = MonoFontFamily, fontSize = 11.sp, color = color, maxLines = 1)
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
    loadBytes: suspend (String) -> ByteArray?,
    onOpenFile: (dev.supermux.ui.FilePathRef) -> Unit,
    highDetail: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var promptOpen by remember(s.id) { mutableStateOf(false) }
    var resultOpen by remember(s.id) { mutableStateOf(false) }
    var showAllChildren by remember(s.id) { mutableStateOf(false) }
    var messageOpen by remember(s.id) { mutableStateOf(false) }
    var draft by remember(s.id) { mutableStateOf("") }
    var busy by remember(s.id) { mutableStateOf(false) }
    var error by remember(s.id) { mutableStateOf<String?>(null) }
    var note by remember(s.id) { mutableStateOf<String?>(null) }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        busy = true; error = null; note = null
        scope.launch {
            val r = actions.message(s.id, text)
            busy = false
            if (r.ok) {
                draft = ""; messageOpen = false
                note = if (r.via == "relay" || s.messaging == "relay") "Sent via the main agent" else "Sent"
            } else {
                error = r.error?.takeIf { it.isNotBlank() } ?: "Couldn't send the message"
            }
        }
    }
    fun stop() {
        if (busy) return
        busy = true; error = null; note = null
        scope.launch {
            val r = actions.stop(s.id)
            busy = false
            if (!r.ok) error = r.error?.takeIf { it.isNotBlank() } ?: "Couldn't stop this agent"
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .testTag("subagent-card-body:${s.id}")
            // Aligned with the title (badge 24 + gap 10), so the card reads as one column.
            .padding(start = Space.md + 34.dp, end = Space.md, bottom = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // Prompt: what it was asked, clipped until asked for.
        s.prompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            Section("Prompt") {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurface.copy(alpha = 0.85f),
                    maxLines = if (promptOpen) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (prompt.length > 180 || prompt.count { it == '\n' } >= 3) {
                    Toggle(if (promptOpen) "Show less" else "Show more") { promptOpen = !promptOpen }
                }
            }
        }
        // Its own rows, nested under a rule, the same renderer as the top level.
        val children = item.children
        if (children.isNotEmpty()) {
            Section(if (children.size == 1) "1 step" else "${children.size} steps") {
                val hidden = if (showAllChildren) 0 else (children.size - VISIBLE_CHILDREN).coerceAtLeast(0)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawLine(
                                color = cs.outlineVariant,
                                start = Offset(0f, 4.dp.toPx()),
                                end = Offset(0f, size.height - 4.dp.toPx()),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        .padding(start = Space.md),
                ) {
                    if (hidden > 0) Toggle("Show $hidden earlier") { showAllChildren = true }
                    // Tool rows straight through ToolCard: the stream's per-row padding is for
                    // top-level rhythm and made a nested list twice as tall as it needs to be.
                    children.drop(hidden).forEach { child ->
                        when (child) {
                            is TimelineItem.Tool -> ToolCard(child.event, child.status, child.output, child.resultBody, highDetail)
                            else -> TimelineItemRow(child, loadBytes = loadBytes, onOpenFile = onOpenFile, highDetail = highDetail)
                        }
                    }
                }
            }
        }
        // The answer it handed back (or why it failed).
        val result = s.result?.takeIf { it.isNotBlank() } ?: item.spawn?.output?.takeIf { it.isNotBlank() && !s.running }
        if (result != null) {
            Section(if (s.status == "failed") "Error" else "Result") {
                if (s.status == "failed") {
                    Text(result, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFontFamily, color = cs.error)
                } else {
                    val long = result.length > 600 || result.count { it == '\n' } > 8
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .then(if (long && !resultOpen) Modifier.heightIn(max = 180.dp).clip(RoundedCornerShape(0.dp)) else Modifier),
                    ) {
                        MarkdownBody(result, Modifier.fillMaxWidth(), onOpenFile = onOpenFile)
                    }
                    if (long) Toggle(if (resultOpen) "Show less" else "Show more") { resultOpen = !resultOpen }
                    if (s.resultClipped == true && (resultOpen || !long)) {
                        Text(
                            "The broker kept the first 8k characters of this result.",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // Message field.
        if (messageOpen) {
            MessageField(
                id = s.id,
                value = draft,
                enabled = !busy,
                relay = s.messaging == "relay",
                onChange = { draft = it },
                onSend = ::send,
                onClose = { messageOpen = false; draft = "" },
            )
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = cs.error, modifier = Modifier.testTag("subagent-error:${s.id}"))
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.testTag("subagent-note:${s.id}"))
        }
        // Actions: quiet, right-aligned, the request cards' buttons.
        val canMessage = s.canMessage && !messageOpen
        if (canMessage || s.running) {
            ActionRow {
                if (s.running) {
                    CardButton(
                        label = "Stop",
                        style = CardButtonStyle.Danger,
                        enabled = !busy,
                        testTag = "subagent-stop:${s.id}",
                        leading = Icons.Filled.Stop,
                        onClick = ::stop,
                    )
                }
                if (canMessage) {
                    CardButton(
                        label = "Message",
                        style = CardButtonStyle.Secondary,
                        enabled = !busy,
                        testTag = "subagent-message:${s.id}",
                        onClick = { messageOpen = true; error = null; note = null },
                    )
                }
            }
        }
    }
}

/** A small caps-less caption over a block of the expanded card. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
        content()
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
 * The inline composer for one subagent: a row surface like the request cards' "Other" field, a
 * compact Send, and — for a relayed message — the honest caveat that the main agent carries it.
 */
@Composable
private fun MessageField(
    id: String,
    value: String,
    enabled: Boolean,
    relay: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
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
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .padding(vertical = 10.dp)
                    .testTag("subagent-message-field:$id"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text("Message this agent…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            IconBtn(Icons.Filled.Close, "Cancel", "subagent-message-cancel:$id", cs.onSurfaceVariant, enabled = true, onClick = onClose)
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

/** Rows shown before the strip folds into "N running". */
private const val STRIP_VISIBLE = 2

/**
 * ONE strip over the composer for everything still running — subagents and background tasks —
 * each with what it is doing right now and a ticking clock. A subagent row jumps to (and opens)
 * its card. Past [STRIP_VISIBLE] rows a header "N running" folds the rest away. Absent when
 * nothing runs; its height animates so the composer eases instead of jumping.
 */
@Composable
fun RunningStrip(
    subagents: List<Subagent>,
    bgTasks: List<ServerFrame.BgTask>,
    onOpenSubagent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(subagents, bgTasks) { runningItems(subagents, bgTasks) }
    Box(modifier.fillMaxWidth().animateContentSize()) {
        if (items.isEmpty()) return@Box
        val cs = MaterialTheme.colorScheme
        val now = rememberTickingNow(true)
        var open by remember { mutableStateOf(false) }
        val folds = items.size > STRIP_VISIBLE + 1
        val shown = if (folds && !open) items.take(STRIP_VISIBLE) else items
        val shape = RoundedCornerShape(Radii.md)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = Space.sm)
                .clip(shape)
                .background(cs.surfaceContainerHigh)
                .border(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.7f), shape)
                .testTag("subagent-strip"),
        ) {
            if (folds) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open = !open }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("subagent-strip-more")
                        .padding(horizontal = Space.md, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    val agents = items.count { it.subagentId != null }
                    val tasks = items.size - agents
                    val summary = listOfNotNull(
                        agents.takeIf { it > 0 }?.let { if (it == 1) "1 agent" else "$it agents" },
                        tasks.takeIf { it > 0 }?.let { if (it == 1) "1 task" else "$it tasks" },
                    ).joinToString(" · ") + " running"
                    Text(summary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f))
                    Text(if (open) "Show less" else "+${items.size - STRIP_VISIBLE} more", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                    Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = null, tint = cs.primary, modifier = Modifier.size(IconSize.md))
                }
            }
            shown.forEachIndexed { i, it ->
                if (i > 0 || folds) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Space.md).heightIn(min = 1.dp, max = 1.dp).background(cs.outlineVariant.copy(alpha = 0.5f)))
                }
                StripRow(it, now, onOpenSubagent)
            }
        }
    }
}

@Composable
private fun StripRow(item: RunningItem, now: Long, onOpenSubagent: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clickable = item.subagentId != null
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onOpenSubagent(item.subagentId!!) }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .testTag("subagent-strip-row:${item.subagentId ?: item.key}")
            .heightIn(min = 36.dp)
            .padding(horizontal = Space.md, vertical = 6.dp),
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
    }
}

