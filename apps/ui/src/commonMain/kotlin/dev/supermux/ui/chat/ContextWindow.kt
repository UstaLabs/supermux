// The context window (#144): how full the agent's context is, as a ring in the composer's toolbar; its panel (what
// fills it, where the agent reports that; what this chat used), Compact, the near-autocompact line above the
// composer, and the "Compacted" line in the chat. Approved prototype: ~/.mux/prototypes/144-context.
//   - The ring is a toolbar control (toolbarControl: 32dp under a pointer, 36dp on touch) before the mic; a pointer
//     also gets the %.
//   - Its panel is RunSettingsPopover's container under a pointer and ComposerSheet on touch, in
//     RunSettingsPanel's measures (13sp rows, 11.5sp details, 12sp section labels, padH 10/16dp, listPad 6/8dp).
//   - Categories and the autocompact mark only where the agent reports them; nothing is estimated.
package dev.supermux.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.SessionContextDto
import dev.supermux.proto.CompactionDto
import dev.supermux.proto.ContextUsageDto
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import kotlinx.coroutines.launch

/** Tokens the way the panel says them: 961, 6.9k, 154k. */
internal fun tokensLabel(n: Long): String = when {
    n < 1000 -> n.toString()
    n < 100_000 -> {
        val tenths = (n + 50) / 100
        if (tenths % 10 == 0L) "${tenths / 10}k" else "${tenths / 10}.${tenths % 10}k"
    }
    else -> "${(n + 500) / 1000}k"
}

private fun percentLabel(part: Long, whole: Long): String {
    if (whole <= 0) return "0%"
    val tenths = (part * 1000 + whole / 2) / whole
    return "${tenths / 10}.${tenths % 10}%"
}

/** How close the window is to where the agent compacts (or, without that, to full). */
internal enum class ContextLevel { Normal, Near, Over }

/**
 * Amber within 10% of the window under where the agent compacts by itself, red past it. Without a reported
 * threshold, the limit is 95% of the window: no estimate of the agent's own point.
 */
internal fun contextLevel(used: Long, size: Long, threshold: Long?): ContextLevel {
    if (size <= 0) return ContextLevel.Normal
    val limit = threshold ?: (size * 95 / 100)
    return when {
        used >= limit -> ContextLevel.Over
        used >= limit - size / 10 -> ContextLevel.Near
        else -> ContextLevel.Normal
    }
}

/** What the panel shows: the drill-ins are read-only views over Claude's per-item lists (MCP management is #145). */
private sealed interface ContextView {
    data object Main : ContextView
    data object Compact : ContextView
    data object Mcp : ContextView
    data class Server(val name: String) : ContextView
    data object Skills : ContextView
    data object Memory : ContextView
}

/**
 * The ring, and the panel it opens. [context] is the live number (the session row), [canCompact]/[compactNote] the
 * session's capabilities, [lastCompaction] the chat's newest "Compacted" line (its result shows in the panel).
 */
@Composable
internal fun ContextRingControl(
    agentName: String,
    context: ContextUsageDto?,
    canCompact: Boolean,
    compactNote: Boolean,
    compacting: Boolean,
    lastCompaction: CompactionDto?,
    actions: ChatActions,
    pointer: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    /** Opens straight on Compact (the near-limit line's "Compact now"); consumed once. */
    startOnCompact: Boolean = false,
    onStartOnCompactConsumed: () -> Unit = {},
    /** The threshold the panel last learned (Claude's, Grok's): drives the ring's colour between openings. */
    threshold: Long?,
    onThreshold: (Long?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val used = context?.used ?: 0L
    val size = context?.size ?: 0L
    val frac = if (size > 0) (used.toFloat() / size).coerceIn(0f, 1f) else 0f
    // A window the agent didn't report (size 0, Antigravity before its DB answers): tokens, no %.
    val pct = if (size > 0) "${((used * 100 + size / 2) / size)}%" else if (context != null) tokensLabel(used) else ""
    val level = if (context != null && size > 0) contextLevel(used, size, threshold) else ContextLevel.Normal
    val fill = when {
        compacting -> cs.primary
        level == ContextLevel.Over -> cs.error
        level == ContextLevel.Near -> MaterialTheme.colorScheme.tertiary
        else -> cs.primary
    }
    val description = if (context == null) localized(Str.context_ring_none_cd, agentName) else localized(Str.context_ring_cd, pct)
    Row(
        Modifier
            .toolbarControl(pointer, open)
            .clickable { onOpenChange(!open) }
            .padding(horizontal = if (pointer) 8.dp else 9.dp)
            .semantics { contentDescription = description }
            .testTag("composer_context_ring"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val track = cs.onSurface.copy(alpha = 0.14f)
        val ringSize = if (pointer) 16.dp else 18.dp
        Canvas(Modifier.size(ringSize)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            if (context == null) {
                // Nothing reported: a dashed, empty ring (no invented number).
                for (i in 0 until 12) drawArc(track, i * 30f, 18f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            } else {
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (size > 0) drawArc(fill, -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        if (pointer && context != null) {
            Text(
                pct,
                color = if (level == ContextLevel.Normal) cs.onSurfaceVariant else fill,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
    if (!open) return
    val panel: @Composable (sheet: Boolean) -> Unit = { sheet ->
        ContextPanel(
            sheet = sheet,
            agentName = agentName,
            context = context,
            canCompact = canCompact,
            compactNote = compactNote,
            compacting = compacting,
            lastCompaction = lastCompaction,
            actions = actions,
            startOnCompact = startOnCompact,
            onStartOnCompactConsumed = onStartOnCompactConsumed,
            onThreshold = onThreshold,
            onClose = { onOpenChange(false) },
        )
    }
    if (pointer) RunSettingsPopover(onDismiss = { onOpenChange(false) }) { panel(false) }
    else ComposerSheet(onDismiss = { onOpenChange(false) }) { panel(true) }
}

@Composable
private fun ContextPanel(
    sheet: Boolean,
    agentName: String,
    context: ContextUsageDto?,
    canCompact: Boolean,
    compactNote: Boolean,
    compacting: Boolean,
    lastCompaction: CompactionDto?,
    actions: ChatActions,
    startOnCompact: Boolean,
    onStartOnCompactConsumed: () -> Unit,
    onThreshold: (Long?) -> Unit,
    onClose: () -> Unit,
) {
    var view by remember { mutableStateOf<ContextView>(if (startOnCompact && canCompact) ContextView.Compact else ContextView.Main) }
    LaunchedEffect(startOnCompact) { if (startOnCompact) onStartOnCompactConsumed() }
    var report by remember { mutableStateOf<SessionContextDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(context?.used, compacting) {
        report = actions.loadContext()
        loading = false
        onThreshold(report?.breakdown?.threshold)
    }
    val padH = if (sheet) 16.dp else 10.dp
    val listPad = if (sheet) 8.dp else 6.dp
    Column(
        Modifier
            .then(if (sheet) Modifier.fillMaxWidth() else Modifier.width(400.dp))
            .heightIn(max = if (sheet) 640.dp else 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = if (sheet) 0.dp else 8.dp)
            .testTag("context_panel"),
    ) {
        when (val v = view) {
            ContextView.Main -> MainView(sheet, padH, listPad, agentName, context, report, loading, canCompact, compacting, lastCompaction) { view = it }
            ContextView.Compact -> CompactView(sheet, padH, agentName, context, compactNote, actions, onBack = { view = ContextView.Main }, onDone = onClose)
            ContextView.Mcp -> McpView(sheet, padH, listPad, report, onBack = { view = ContextView.Main }) { view = ContextView.Server(it) }
            is ContextView.Server -> ServerView(sheet, padH, listPad, report, v.name) { view = ContextView.Mcp }
            ContextView.Skills -> SkillsView(sheet, padH, listPad, report) { view = ContextView.Main }
            ContextView.Memory -> MemoryView(sheet, padH, listPad, report) { view = ContextView.Main }
        }
    }
}

private val CATEGORY_NAMES: Map<String, dev.supermux.ui.i18n.StringKey> = mapOf(
    "messages" to Str.context_cat_messages,
    "system_prompt" to Str.context_cat_system_prompt,
    "system_tools" to Str.context_cat_system_tools,
    "mcp_tools" to Str.context_cat_mcp_tools,
    "mcp_instructions" to Str.context_cat_mcp_instructions,
    "memory_files" to Str.context_cat_memory_files,
    "skills" to Str.context_cat_skills,
    "custom_agents" to Str.context_cat_custom_agents,
    "autocompact_buffer" to Str.context_cat_autocompact_buffer,
    "tool_definitions" to Str.context_cat_tool_definitions,
    "workflows" to Str.context_cat_workflows,
)

/** The category rows' dots, in the order the agent lists its used categories. */
@Composable
private fun categoryColors(): List<Color> {
    val cs = MaterialTheme.colorScheme
    return listOf(cs.primary, Color(0xFFE07A5F), Color(0xFF6FBF73), cs.tertiary, Color(0xFFA48BE0), Color(0xFF6BA4E0), cs.onSurfaceVariant)
}

/** Which drill-in a category opens, where the agent gave the per-item list. */
private fun drillFor(id: String, report: SessionContextDto?): ContextView? {
    val b = report?.breakdown ?: return null
    return when (id) {
        "mcp_tools" -> ContextView.Mcp.takeIf { b.mcpTools.isNotEmpty() }
        "skills" -> ContextView.Skills.takeIf { b.skills.isNotEmpty() }
        "memory_files" -> ContextView.Memory.takeIf { b.memoryFiles.isNotEmpty() }
        else -> null
    }
}

@Composable
private fun ColumnScope.MainView(
    sheet: Boolean,
    padH: androidx.compose.ui.unit.Dp,
    listPad: androidx.compose.ui.unit.Dp,
    agentName: String,
    context: ContextUsageDto?,
    report: SessionContextDto?,
    loading: Boolean,
    canCompact: Boolean,
    compacting: Boolean,
    lastCompaction: CompactionDto?,
    go: (ContextView) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val breakdown = report?.breakdown
    val live = report?.context ?: context
    // Title row: "Context window · 51% used", then "154k of 300k tokens".
    Column(Modifier.fillMaxWidth().padding(horizontal = padH + 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(localized(Str.context_title), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (live != null && live.size > 0) {
                Text(localized(Str.context_pct_used, "${(live.used * 100 + live.size / 2) / live.size}%"), color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        if (live == null) {
            Spacer(Modifier.height(6.dp))
            Text(localized(Str.context_src_none, agentName), color = cs.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
        } else if (live.size <= 0) {
            Text(localized(Str.context_tokens_no_window, tokensLabel(live.used)), color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            Text(localized(Str.context_of_tokens, tokensLabel(live.used), tokensLabel(live.size)), color = cs.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            ContextBar(live, breakdown?.threshold, breakdown?.categories?.filter { it.kind == "used" }?.map { it.tokens })
            breakdown?.threshold?.let {
                Text(
                    localized(Str.context_compacts_at, tokensLabel(it)),
                    color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                    modifier = Modifier.align(Alignment.End).padding(top = 6.dp),
                )
            }
        }
    }
    // The state of a compaction: running, or how the last one went.
    if (compacting) {
        StateBox(padH) {
            CircularProgressIndicator(strokeWidth = 1.5.dp, color = cs.primary, modifier = Modifier.size(14.dp))
            Column {
                Text(localized(Str.context_compacting), color = cs.onSurface, fontSize = 13.sp)
                Text(localized(Str.context_compacting_detail, agentName), color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    } else if (lastCompaction?.status == "completed" && live != null) {
        StateBox(padH) {
            Icon(Icons.Filled.Check, null, tint = Color(0xFF53BE70), modifier = Modifier.size(16.dp))
            val before = lastCompaction.before
            val after = lastCompaction.after
            Column {
                if (before != null && after != null) {
                    Text(localized(Str.context_result_sizes, tokensLabel(before), tokensLabel(after)), color = cs.onSurface, fontSize = 13.sp)
                    Text(localized(Str.context_result_freed, tokensLabel(before - after)), color = cs.onSurfaceVariant, fontSize = 12.sp)
                } else if (before != null) {
                    Text(localized(Str.context_result_was, tokensLabel(before)), color = cs.onSurface, fontSize = 13.sp)
                    Text(localized(Str.context_result_after_next, agentName), color = cs.onSurfaceVariant, fontSize = 12.sp)
                } else {
                    Text(localized(Str.context_line_done), color = cs.onSurface, fontSize = 13.sp)
                }
            }
        }
    }
    // What fills it: only where the agent reports it.
    if (breakdown != null && live != null) {
        Divider(top = 12.dp)
        SectionLabel(localized(Str.context_what_fills), listPad, sheet)
        val colors = categoryColors()
        var deferredShown by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = listPad)) {
            breakdown.categories.filter { it.kind == "used" }.forEachIndexed { i, c ->
                val to = drillFor(c.id, report)
                CategoryRow(sheet, colors[i % colors.size], CATEGORY_NAMES[c.id]?.let { localized(it) } ?: c.name, c.tokens, live.size, onClick = to?.let { { go(it) } })
            }
            breakdown.categories.filter { it.kind == "buffer" }.forEach { c ->
                CategoryRow(sheet, cs.onSurface.copy(alpha = 0.14f), CATEGORY_NAMES[c.id]?.let { localized(it) } ?: c.name, c.tokens, live.size, dim = true)
            }
            val free = ((breakdown.threshold ?: live.size) - live.used).coerceAtLeast(0)
            CategoryRow(sheet, Color.Transparent, localized(Str.context_free), free, live.size, dim = true, outlined = true)
            val deferred = breakdown.categories.filter { it.kind == "deferred" }
            if (deferred.isNotEmpty()) {
                if (deferredShown) deferred.forEach { c -> CategoryRow(sheet, Color.Transparent, c.name, c.tokens, null, dim = true) }
                Text(
                    localized(if (deferredShown) Str.context_deferred_hide else Str.context_deferred_show),
                    color = cs.onSurfaceVariant, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { deferredShown = !deferredShown }.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    } else if (!loading) {
        // An agent that reports no breakdown: what this chat called instead (from supermux's own record).
        UsedInChat(sheet, listPad, agentName, report)
    }
    // Compact.
    if (canCompact && !compacting) {
        Divider(top = 12.dp)
        Column(Modifier.padding(horizontal = listPad, vertical = 6.dp)) {
            DrillRow(
                sheet,
                icon = { Icon(Icons.Filled.Compress, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                title = localized(Str.context_compact_option),
                detail = localized(Str.context_compact_how, agentName),
                tag = "context_compact_option",
            ) { go(ContextView.Compact) }
        }
    }
    if (live != null) {
        Text(
            if (breakdown != null) localized(Str.context_src_breakdown, agentName) else localized(Str.context_src_totals, agentName),
            color = cs.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(start = listPad + 10.dp, end = listPad + 10.dp, top = 8.dp, bottom = 4.dp),
        )
    }
}

/** The bar: the used categories in their colours (or one fill for a total), and the autocompact mark. */
@Composable
private fun ContextBar(live: ContextUsageDto, threshold: Long?, parts: List<Long>?) {
    val cs = MaterialTheme.colorScheme
    val colors = categoryColors()
    val track = cs.onSurface.copy(alpha = 0.08f)
    val mark = cs.onSurface.copy(alpha = 0.5f)
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val w = size.width
        val h = size.height
        val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
        drawRoundRect(track, size = Size(w, h), cornerRadius = r)
        var x = 0f
        val segments = parts?.takeIf { it.isNotEmpty() } ?: listOf(live.used)
        segments.forEachIndexed { i, tokens ->
            val sw = w * tokens.toFloat() / live.size.coerceAtLeast(1)
            drawRect(if (parts.isNullOrEmpty()) colors[0] else colors[i % colors.size], topLeft = Offset(x, 0f), size = Size(sw.coerceAtMost(w - x), h))
            x += sw
        }
        threshold?.let {
            val tx = w * it.toFloat() / live.size.coerceAtLeast(1)
            drawRect(mark, topLeft = Offset(tx - 1.dp.toPx(), -3.dp.toPx()), size = Size(2.dp.toPx(), h + 6.dp.toPx()))
        }
    }
}

@Composable
private fun StateBox(padH: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = padH, vertical = 4.dp)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) { content() }
}

@Composable
private fun Divider(top: androidx.compose.ui.unit.Dp) {
    HorizontalDivider(Modifier.padding(top = top), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
}

@Composable
private fun SectionLabel(text: String, listPad: androidx.compose.ui.unit.Dp, sheet: Boolean, trailing: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = listPad + 10.dp, end = listPad + 10.dp, top = if (sheet) 8.dp else 12.dp, bottom = 6.dp)) {
        Text(text, color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = cs.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun CategoryRow(
    sheet: Boolean,
    dot: Color,
    name: String,
    tokens: Long,
    size: Long?,
    dim: Boolean = false,
    outlined: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (sheet) 36.dp else 28.dp)
            .clip(RoundedCornerShape(if (sheet) 14.dp else 10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(dot)
                .then(if (outlined) Modifier.border1(cs.outline) else Modifier),
        )
        Text(name, color = if (dim) cs.onSurfaceVariant else cs.onSurface, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(tokensLabel(tokens), color = if (dim) cs.onSurfaceVariant else cs.onSurface, fontSize = 13.sp)
        Text(size?.let { percentLabel(tokens, it) } ?: "—", color = cs.onSurfaceVariant, fontSize = 11.5.sp, modifier = Modifier.width(44.dp), maxLines = 1)
        if (onClick != null) Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
    }
}

private fun Modifier.border1(color: Color): Modifier = this.border(1.dp, color, CircleShape)

/** RunSettingsPanel's DrillOption: 16dp icon · title over a one-line detail · chevron. */
@Composable
private fun DrillRow(sheet: Boolean, icon: @Composable () -> Unit, title: String, detail: String?, tag: String, trailing: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (sheet) 14.dp else 10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (sheet) 10.dp else 7.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = cs.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun BackHeader(padH: androidx.compose.ui.unit.Dp, title: String, sub: String? = null, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = padH - 4.dp, end = padH + 6.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onBack).testTag("context_back"),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, localized(Str.context_cancel), tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(8.dp))
        Text(title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (sub != null) Text(sub, color = cs.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun UsedBadge(count: Int) {
    val cs = MaterialTheme.colorScheme
    val used = count > 0
    Text(
        if (used) localized(Str.context_used_n, count) else localized(Str.context_not_used),
        color = if (used) cs.primary else cs.onSurfaceVariant,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (used) cs.primary.copy(alpha = 0.14f) else cs.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** How many times this chat called each tool (from the broker's record): name → count. */
private fun usedCounts(report: SessionContextDto?): Map<String, Int> = report?.used.orEmpty().associate { it.name to it.count }

@Composable
private fun ToolRow(sheet: Boolean, name: String, count: Int, tokens: Long?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(if (sheet) 36.dp else 28.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            name,
            color = if (count > 0) cs.onSurface else cs.onSurfaceVariant,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        UsedBadge(count)
        if (tokens != null) Text(tokensLabel(tokens), color = if (count > 0) cs.onSurface else cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(48.dp), maxLines = 1)
    }
}

@Composable
private fun Note(text: String, listPad: androidx.compose.ui.unit.Dp) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 15.sp,
        modifier = Modifier.padding(start = listPad + 10.dp, end = listPad + 10.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun Callout(text: String, padH: androidx.compose.ui.unit.Dp) {
    val cs = MaterialTheme.colorScheme
    Text(
        text, color = cs.onSurface, fontSize = 12.5.sp, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = padH, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(cs.onSurface.copy(alpha = 0.05f)).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun McpView(sheet: Boolean, padH: androidx.compose.ui.unit.Dp, listPad: androidx.compose.ui.unit.Dp, report: SessionContextDto?, onBack: () -> Unit, open: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tools = report?.breakdown?.mcpTools.orEmpty()
    val used = usedCounts(report)
    val servers = tools.filter { it.loaded }.groupBy { it.server }
        .map { (server, list) -> Triple(server, list, list.sumOf { it.tokens }) }
        .sortedByDescending { it.third }
    val usedTools = { server: String, list: List<dev.supermux.net.McpToolCostDto> -> list.count { (used["mcp__${server}__${it.name}"] ?: 0) > 0 } }
    val idle = servers.filter { (s, list, _) -> usedTools(s, list) == 0 }
    BackHeader(padH, localized(Str.context_mcp_title), "${tokensLabel(servers.sumOf { it.third })} · ${servers.size}", onBack)
    if (idle.isNotEmpty()) Callout(localized(Str.context_mcp_idle, idle.size, servers.size, tokensLabel(idle.sumOf { it.third })), padH)
    SectionLabel(localized(Str.context_mcp_servers), listPad, sheet, localized(Str.context_used_in_chat))
    Column(Modifier.padding(horizontal = listPad)) {
        servers.forEach { (server, list, total) ->
            val n = usedTools(server, list)
            DrillRow(
                sheet,
                icon = {},
                title = server,
                detail = if (n > 0) localized(Str.context_server_tools_used, list.size, n) else localized(Str.context_server_tools_none, list.size),
                tag = "context_mcp_server",
                trailing = tokensLabel(total),
            ) { open(server) }
        }
    }
    Note(localized(Str.context_mcp_note), listPad)
}

@Composable
private fun ServerView(sheet: Boolean, padH: androidx.compose.ui.unit.Dp, listPad: androidx.compose.ui.unit.Dp, report: SessionContextDto?, server: String, onBack: () -> Unit) {
    val all = report?.breakdown?.mcpTools.orEmpty().filter { it.server == server }
    val used = usedCounts(report)
    val count = { name: String -> used["mcp__${server}__${name}"] ?: 0 }
    val loaded = all.filter { it.loaded }.sortedWith(compareByDescending<dev.supermux.net.McpToolCostDto> { count(it.name) > 0 }.thenByDescending { it.tokens })
    val deferred = all.filterNot { it.loaded }
    BackHeader(padH, server, "${tokensLabel(loaded.sumOf { it.tokens })} · ${all.size}", onBack)
    SectionLabel(localized(Str.context_tools), listPad, sheet, localized(Str.context_in_context))
    Column(Modifier.padding(horizontal = listPad)) { loaded.forEach { ToolRow(sheet, it.name, count(it.name), it.tokens) } }
    if (deferred.isNotEmpty()) {
        SectionLabel(localized(Str.context_deferred_group), listPad, sheet)
        Column(Modifier.padding(horizontal = listPad)) { deferred.forEach { ToolRow(sheet, it.name, count(it.name), it.tokens) } }
    }
}

@Composable
private fun SkillsView(sheet: Boolean, padH: androidx.compose.ui.unit.Dp, listPad: androidx.compose.ui.unit.Dp, report: SessionContextDto?, onBack: () -> Unit) {
    val skills = report?.breakdown?.skills.orEmpty()
    val used = usedCounts(report)
    val count = { name: String -> used["Skill:$name"] ?: 0 }
    val sorted = skills.sortedWith(compareByDescending<dev.supermux.net.SkillCostDto> { count(it.name) > 0 }.thenByDescending { it.tokens })
    BackHeader(padH, localized(Str.context_skills_title), "${tokensLabel(skills.sumOf { it.tokens })} · ${skills.size}", onBack)
    Callout(localized(Str.context_skills_used, skills.count { count(it.name) > 0 }, skills.size), padH)
    SectionLabel(localized(Str.context_skills_title), listPad, sheet, localized(Str.context_in_context))
    Column(Modifier.padding(horizontal = listPad)) { sorted.forEach { ToolRow(sheet, it.name, count(it.name), it.tokens) } }
    Note(localized(Str.context_skills_note), listPad)
}

@Composable
private fun MemoryView(sheet: Boolean, padH: androidx.compose.ui.unit.Dp, listPad: androidx.compose.ui.unit.Dp, report: SessionContextDto?, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val files = report?.breakdown?.memoryFiles.orEmpty()
    BackHeader(padH, localized(Str.context_memory_title), "${tokensLabel(files.sumOf { it.tokens })} · ${files.size}", onBack)
    Column(Modifier.padding(horizontal = listPad)) {
        files.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (sheet) 10.dp else 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.path, color = cs.onSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    f.type?.let { Text(it, color = cs.onSurfaceVariant, fontSize = 11.5.sp) }
                }
                Text(tokensLabel(f.tokens), color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    Note(localized(Str.context_memory_note), listPad)
}

/** For agents that report no per-item costs: what this chat called, by name and count only. */
@Composable
private fun UsedInChat(sheet: Boolean, listPad: androidx.compose.ui.unit.Dp, agentName: String, report: SessionContextDto?) {
    val cs = MaterialTheme.colorScheme
    val used = report?.used.orEmpty()
    Divider(top = 12.dp)
    SectionLabel(localized(Str.context_used_title), listPad, sheet, used.size.toString())
    if (used.isEmpty()) {
        Text(localized(Str.context_used_none), color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = listPad + 10.dp, vertical = 4.dp))
    } else {
        Column(Modifier.padding(horizontal = listPad)) {
            used.forEach { u -> ToolRow(sheet, u.name.replaceFirst("Skill:", "Skill · "), u.count, null) }
        }
    }
    Note(localized(Str.context_used_note, agentName), listPad)
}

@Composable
private fun CompactView(
    sheet: Boolean,
    padH: androidx.compose.ui.unit.Dp,
    agentName: String,
    context: ContextUsageDto?,
    compactNote: Boolean,
    actions: ChatActions,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var note by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    BackHeader(padH, localized(Str.context_compact_option), onBack = onBack)
    val now = context?.let { localized(Str.context_now_size, tokensLabel(it.used), tokensLabel(it.size)) }.orEmpty()
    if (compactNote) {
        Column(Modifier.padding(horizontal = padH)) {
            Text(localized(Str.context_keep_label), color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
                    .background(cs.onSurface.copy(alpha = 0.06f)).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (note.isEmpty()) Text(localized(Str.context_keep_placeholder), color = cs.onSurfaceVariant.copy(alpha = 0.8f), fontSize = 14.sp)
                BasicTextField(
                    value = note,
                    onValueChange = { note = it },
                    textStyle = TextStyle(color = cs.onSurface, fontSize = 14.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth().testTag("context_keep_note"),
                )
            }
            Text(
                "${localized(Str.context_compact_how, agentName)} ${localized(Str.context_keep_explain)} $now".trim(),
                color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 4.dp, top = 10.dp),
            )
        }
    } else {
        Text(
            "${localized(Str.context_compact_how, agentName)} ${localized(Str.context_no_note)} $now".trim(),
            color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = padH + 4.dp, vertical = 2.dp),
        )
    }
    if (refused) {
        Text(localized(Str.context_compact_refused), color = cs.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = padH + 4.dp, vertical = 6.dp))
    }
    Row(
        Modifier.fillMaxWidth().padding(start = padH, end = padH, top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(localized(Str.context_cancel), filled = false, sheet = sheet, tag = "context_compact_cancel", onClick = onBack)
        PillButton(localized(Str.context_compact), filled = true, sheet = sheet, tag = "context_compact_go", enabled = !busy) {
            busy = true
            refused = false
            scope.launch {
                val ok = actions.compact(note.trim().takeIf { compactNote && it.isNotEmpty() })
                busy = false
                if (ok) { note = ""; onDone() } else refused = true
            }
        }
    }
}

@Composable
private fun PillButton(text: String, filled: Boolean, sheet: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(if (sheet) 40.dp else 32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (filled) cs.primary.copy(alpha = if (enabled) 1f else 0.5f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (filled) (if (sheet) 20.dp else 16.dp) else 12.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) cs.onPrimary else cs.primary, fontSize = if (sheet) 14.sp else 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The line above the composer when the window is close to where the agent compacts (ConnectionStrip's in-chat
 * look, a warning dot): "Context 87% full · Claude compacts by itself at 267k" · Compact now.
 */
@Composable
internal fun ContextHint(agentName: String, context: ContextUsageDto, threshold: Long?, onCompactNow: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pct = "${(context.used * 100 + context.size / 2) / context.size.coerceAtLeast(1)}%"
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, bottom = 6.dp).testTag("context_hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(cs.tertiary))
        Text(
            if (threshold != null) localized(Str.context_hint_threshold, pct, agentName, tokensLabel(threshold)) else localized(Str.context_hint_full, pct),
            color = cs.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            localized(Str.context_not_now), color = cs.onSurfaceVariant, fontSize = 12.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss).padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            localized(Str.context_compact_now), color = cs.primary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCompactNow).padding(horizontal = 4.dp, vertical = 2.dp)
                .testTag("context_hint_compact"),
        )
    }
}

/**
 * The "Compacted" line in a chat (ChatPanel's NewMessagesLine, in onSurfaceVariant): "Compacted · 262k → 20k".
 * Where the agent kept a summary (Claude, OpenCode) a tap shows it, with the note the user asked to keep.
 */
@Composable
internal fun CompactionLine(compaction: CompactionDto, agentName: String) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val before = compaction.before
    val after = compaction.after
    val label = when (compaction.status) {
        "failed" -> localized(Str.context_line_failed)
        "cancelled" -> localized(Str.context_line_cancelled)
        else -> when {
            before != null && after != null -> localized(Str.context_line_sizes, tokensLabel(before), tokensLabel(after))
            before != null -> localized(Str.context_line_was, tokensLabel(before))
            else -> localized(Str.context_line_done)
        } + if (compaction.trigger == "auto") " · ${localized(Str.context_line_auto)}" else ""
    }
    val hasSummary = !compaction.summary.isNullOrBlank()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("chat_compaction_line")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(cs.onSurfaceVariant.copy(alpha = 0.35f)))
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .then(if (hasSummary) Modifier.clickable { open = !open } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, color = if (compaction.status == "failed") cs.error else cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (hasSummary) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown, null, tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(14.dp).then(if (open) Modifier.rotate180() else Modifier),
                    )
                }
            }
            Box(Modifier.weight(1f).height(1.dp).background(cs.onSurfaceVariant.copy(alpha = 0.35f)))
        }
        if (open && hasSummary) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp))
                    .background(cs.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(localized(Str.context_line_summary_title, agentName), color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(compaction.summary.orEmpty(), color = cs.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
                compaction.note?.let { note ->
                    Spacer(Modifier.height(6.dp))
                    Text("${localized(Str.context_line_you_asked)} $note", color = cs.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

private fun Modifier.rotate180(): Modifier = this.rotate(180f)
