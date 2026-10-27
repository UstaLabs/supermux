package dev.supermux.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.oklchToArgb
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay

// A tool call row for agent transcripts, matched to uiarc.dev/components/tool-call: its tokens
// (neutral greys, 40dp rows, 16dp marks, 12sp muted meta, 16dp group radius) and its motion, which
// was measured off the live page — critically damped height/width springs (ω≈12 rad/s), a 0.58s
// critically damped chevron (ω≈14), status icons that leave at scale .6 and spring back in, titles
// that blur out/in on change, and a 4.4em highlight band that sweeps muted text in 1.8s, then rests.

enum class ToolCallStatus { Running, Success, Error, Approval, Denied }

/** The tool's mark: a 16dp glyph, drawn in the text colour unless it has a brand [tint]. */
@Immutable
data class ToolMark(val name: String, val glyph: ImageVector? = null, val tint: Color? = null)

/** Built-in marks, after the uiarc set. */
object ToolMarks {
    val github = ToolMark("GitHub", Icons.Outlined.Code)
    val linear = ToolMark("Linear", tint = Color(0xFF5E6AD2))
    val postgres = ToolMark("PostgreSQL", Icons.Outlined.Storage, Color(0xFF336791))
    val slack = ToolMark("Slack", Icons.Outlined.Tag)
    val gmail = ToolMark("Gmail", Icons.Outlined.MailOutline)
    val web = ToolMark("Web", Icons.Outlined.Public)
}

@Immutable
sealed interface ToolCallOutput {
    /** One result: `#1284  Checkout button stays disabled…  checkout`. */
    data class Item(val title: String, val lead: String? = null, val tag: String? = null)
    data class Items(val items: List<Item>) : ToolCallOutput
    data class Table(val columns: List<String>, val rows: List<List<String>>) : ToolCallOutput
    data class Json(val text: String) : ToolCallOutput
    /** Command output, diffs, file contents: monospace, folded like [Json]. */
    data class Code(val text: String) : ToolCallOutput
    data class Text(val text: String) : ToolCallOutput
}

@Immutable
data class ToolCallData(
    /** Stable key: keeps the row and its open state across status changes. */
    val id: String,
    val tool: ToolMark,
    /** Settled verb phrase, one line. */
    val title: String,
    val status: ToolCallStatus,
    /** Per-status phrasing ("Searching issues…" while running); falls back to [title]. */
    val titles: Map<ToolCallStatus, String> = emptyMap(),
    val input: List<Pair<String, String>> = emptyList(),
    val output: ToolCallOutput? = null,
    /** Meta such as "4 results"; derived from [output] when null. */
    val summary: String? = null,
    /** Error reason, or what allowing will do. */
    val message: String? = null,
    val durationMs: Long? = null,
    /** The title is a raw command (no description to show instead): set it in monospace. */
    val monoTitle: Boolean = false,
)

// ---------------------------------------------------------------------------
// Tokens
// ---------------------------------------------------------------------------

/** uiarc's neutral palette (its CSS custom properties), light and dark. */
@Immutable
data class ToolCallColors(
    val foreground: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val border: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentForeground: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
) {
    companion object {
        private fun ok(l: Double, c: Double = 0.0, h: Double = 0.0) = Color(oklchToArgb(l, c, h))
        val Light = ToolCallColors(
            foreground = ok(0.15), surface = ok(1.0), surfaceMuted = ok(0.978), border = ok(0.935),
            textSecondary = ok(0.46), textMuted = ok(0.59), accent = ok(0.33), accentForeground = ok(1.0),
            success = ok(0.49, 0.19, 150.0), danger = ok(0.54, 0.21, 25.0), warning = ok(0.58, 0.18, 75.0),
        )
        val Dark = ToolCallColors(
            foreground = ok(0.96), surface = ok(0.215), surfaceMuted = ok(0.26), border = ok(0.29),
            textSecondary = ok(0.77), textMuted = ok(0.64), accent = ok(0.80), accentForeground = ok(0.19),
            success = ok(0.78, 0.18, 150.0), danger = ok(0.76, 0.2, 25.0), warning = ok(0.83, 0.17, 80.0),
        )
    }
}

private val LocalToolCallColors = staticCompositionLocalOf { ToolCallColors.Light }

@Composable
private fun ProvideToolCallColors(colors: ToolCallColors?, content: @Composable () -> Unit) {
    val resolved = colors ?: if (MaterialTheme.colorScheme.background.luminance() < 0.5f) ToolCallColors.Dark else ToolCallColors.Light
    CompositionLocalProvider(LocalToolCallColors provides resolved, content = content)
}

private object ToolCallMotion {
    /** Height, width, list open/close: critically damped, ω≈12.2 (fitted to the page's motion values). */
    fun <T> height(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 149f)
    /** Chevron turn: the page's 0.58s `--ease-spring`, a critically damped ω≈13.9. */
    fun <T> chevron(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 193f)
    /** Status icon entering from scale .6. */
    fun <T> statusIn(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 420f)
    /** `--ease-standard`. */
    val standard = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    fun <T> fast(): FiniteAnimationSpec<T> = tween(160, easing = standard)
    fun <T> std(): FiniteAnimationSpec<T> = tween(240, easing = standard)
    const val STAGGER_MS = 20
    const val AUTO_COLLAPSE_MS = 450L
    const val FOLD_ROWS = 5
    const val FOLD_LINES = 10
    const val INPUT_LINES = 6
}

private val RowMinHeight = 40.dp
private val MarkSize = 16.dp
private val Gap = 10.dp
private val PadH = 12.dp
/** Panel indent: row padding + mark + gap, so the panel lines up under the title. */
private val Indent = PadH + MarkSize + Gap
private val Meta = TextStyle(fontSize = 12.sp, lineHeight = 16.8.sp, letterSpacing = (-0.14).sp)
private val Body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.14).sp)

internal fun formatToolDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 10_000 -> "${(ms / 100) / 10.0}s".replace(".0s", "s")
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

internal fun ToolCallData.derivedSummary(): String? = summary ?: when (val o = output) {
    is ToolCallOutput.Items -> plural(o.items.size, "result")
    is ToolCallOutput.Table -> plural(o.rows.size, "row")
    else -> null
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private val ToolCallStatus.needsAttention get() = this == ToolCallStatus.Approval || this == ToolCallStatus.Error

// ---------------------------------------------------------------------------
// ToolCall
// ---------------------------------------------------------------------------

/** One tool call as a standalone card. Inside a [ToolCallGroup] the same row renders flush. */
@Composable
fun ToolCall(
    call: ToolCallData,
    modifier: Modifier = Modifier,
    defaultOpen: Boolean = false,
    open: Boolean? = null,
    onOpenChange: ((Boolean) -> Unit)? = null,
    onApprove: (() -> Unit)? = null,
    onDeny: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    renderOutput: (@Composable (ToolCallData) -> Boolean)? = null,
    colors: ToolCallColors? = null,
) = ProvideToolCallColors(colors) {
    val c = LocalToolCallColors.current
    val shape = RoundedCornerShape(14.dp)
    val border by animateColorAsState(
        if (call.status == ToolCallStatus.Approval) lerp(c.border, c.accent, 0.6f) else c.border,
        ToolCallMotion.std(),
    )
    Box(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, border, shape)) {
        ToolCallRow(call, flush = false, defaultOpen, open, onOpenChange, onApprove, onDeny, onRetry, renderOutput)
    }
}

@Composable
private fun ToolCallRow(
    call: ToolCallData,
    flush: Boolean,
    defaultOpen: Boolean,
    open: Boolean?,
    onOpenChange: ((Boolean) -> Unit)?,
    onApprove: (() -> Unit)?,
    onDeny: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    renderOutput: (@Composable (ToolCallData) -> Boolean)?,
) {
    val c = LocalToolCallColors.current
    var openLocal by rememberSaveable(call.id) { mutableStateOf(defaultOpen) }
    val isOpen = open ?: openLocal
    val setOpen = { v: Boolean -> openLocal = v; onOpenChange?.invoke(v) }
    val approval = call.status == ToolCallStatus.Approval
    val shape = RoundedCornerShape(12.dp)

    // Flush approval: a 3% accent wash and a 1px accent ring, on the standard ease.
    val wash by animateColorAsState(if (flush && approval) c.accent.copy(alpha = 0.03f) else Color.Transparent, ToolCallMotion.std())
    val ring by animateColorAsState(if (flush && approval) c.accent.copy(alpha = 0.5f) else Color.Transparent, ToolCallMotion.std())

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(wash)
            .border(1.dp, ring, shape)
            .testTag("tool_call_${call.id}"),
    ) {
        ToolCallHead(call, isOpen, { setOpen(!isOpen) }, onApprove, onDeny, onRetry)
        AnimatedVisibility(
            visible = isOpen,
            enter = expandVertically(ToolCallMotion.height(), expandFrom = Alignment.Top) + fadeIn(tween(200, easing = ToolCallMotion.standard)),
            // The clip does the closing; the fade only finishes it, so no blank band trails the height.
            exit = shrinkVertically(ToolCallMotion.height(), shrinkTowards = Alignment.Top) + fadeOut(tween(220, delayMillis = 160)),
        ) {
            ToolCallPanel(call, renderOutput)
        }
    }
}

private class Action(val label: String, val description: String, val primary: Boolean, val icon: ImageVector?, val onClick: () -> Unit)

/** `mark | title | aside | status | chevron`, with the note (and, when narrow, the actions) under the title. */
@Composable
private fun ToolCallHead(
    call: ToolCallData,
    isOpen: Boolean,
    toggle: () -> Unit,
    onApprove: (() -> Unit)?,
    onDeny: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    val c = LocalToolCallColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        when { pressed -> c.surfaceMuted; hovered -> c.surfaceMuted.copy(alpha = 0.75f); else -> Color.Transparent },
        ToolCallMotion.fast(),
    )
    val title = call.titles[call.status] ?: call.title
    val note = call.message?.takeIf { call.status == ToolCallStatus.Error || call.status == ToolCallStatus.Approval }
    val actions: List<Action> = when (call.status) {
        ToolCallStatus.Approval -> listOfNotNull(
            onDeny?.let { Action("Deny", "Deny ${call.title}", primary = false, icon = null, it) },
            onApprove?.let { Action("Allow", "Allow ${call.title}", primary = true, icon = null, it) },
        )
        ToolCallStatus.Error -> listOfNotNull(onRetry?.let { Action("Retry", "Retry ${call.title}", primary = false, icon = Icons.Outlined.Replay, it) })
        else -> emptyList()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .animateContentSize(ToolCallMotion.height())
            .heightIn(min = RowMinHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = toggle)
            .semantics { stateDescription = if (isOpen) "Expanded" else "Collapsed" }
            .testTag("tool_call_head_${call.id}"),
    ) {
        // Below 440dp the summary drops and the actions move under the reason line.
        val narrow = maxWidth < 440.dp
        val actionsInline = actions.isNotEmpty() && !narrow
        Column(Modifier.fillMaxWidth().padding(horizontal = PadH, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                MarkGlyph(call.tool, MarkSize)
                Spacer(Modifier.width(Gap))
                SwapText(
                    text = title,
                    shimmering = call.status == ToolCallStatus.Running,
                    style = if (call.monoTitle) Body.copy(fontFamily = MonoFontFamily, fontSize = 12.5.sp, letterSpacing = 0.sp) else Body,
                    color = if (call.status == ToolCallStatus.Denied) c.textMuted else c.foreground,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Gap))
                if (actionsInline) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        actions.forEach { ActionButton(it) }
                    }
                } else if (actions.isEmpty()) {
                    MetaLine(call, narrow)
                }
                Spacer(Modifier.width(Gap))
                StatusIcon(call.status)
                Spacer(Modifier.width(Gap))
                val turn by animateFloatAsState(if (isOpen) 180f else 0f, ToolCallMotion.chevron())
                Icon(
                    Icons.Outlined.KeyboardArrowDown, null,
                    tint = if (hovered) c.textSecondary else c.textMuted,
                    modifier = Modifier.size(16.dp).rotate(turn),
                )
            }
            if (note != null || (actions.isNotEmpty() && !actionsInline)) {
                Column(Modifier.padding(start = MarkSize + Gap)) {
                    note?.let {
                        Text(
                            it, style = Meta.copy(lineHeight = 18.sp), color = c.textSecondary,
                            maxLines = if (narrow) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (actions.isNotEmpty() && !actionsInline) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions.forEach { ActionButton(it) } }
                    }
                }
            }
        }
    }
}

/** "4 results · 1.2s", right-aligned. Narrow rows drop the summary. */
@Composable
private fun MetaLine(call: ToolCallData, narrow: Boolean) {
    val c = LocalToolCallColors.current
    val parts = buildList {
        if (call.status == ToolCallStatus.Success && !narrow) call.derivedSummary()?.let(::add)
        if (call.status == ToolCallStatus.Success || call.status == ToolCallStatus.Error) call.durationMs?.let { add(formatToolDuration(it)) }
    }
    AnimatedContent(
        targetState = parts,
        transitionSpec = { fadeIn(ToolCallMotion.fast()) togetherWith fadeOut(tween(100)) },
        label = "meta",
    ) { p ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            p.forEachIndexed { i, s ->
                if (i > 0) Text("·", style = Meta, color = c.textMuted)
                Text(s, style = Meta, color = c.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActionButton(a: Action) {
    val c = LocalToolCallColors.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .heightIn(min = 28.dp)
            .clip(shape)
            .background(if (a.primary) c.accent else c.surface)
            .border(1.dp, if (a.primary) c.accent else c.border, shape)
            .clickable(role = Role.Button, onClick = a.onClick)
            .semantics { contentDescription = a.description }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        a.icon?.let { Icon(it, null, tint = c.foreground, modifier = Modifier.size(13.dp)) }
        Text(a.label, style = Meta.copy(fontWeight = FontWeight.Medium), color = if (a.primary) c.accentForeground else c.foreground)
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
internal fun MarkGlyph(mark: ToolMark, size: Dp) {
    val c = LocalToolCallColors.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (mark.glyph != null) {
            Icon(mark.glyph, contentDescription = mark.name, tint = mark.tint ?: c.foreground, modifier = Modifier.size(size))
        } else {
            Box(
                Modifier.size(size * 0.88f).clip(RoundedCornerShape(size * 0.24f)).background(mark.tint ?: c.foreground),
                contentAlignment = Alignment.Center,
            ) {
                Text(mark.name.take(1).uppercase(), color = c.surface, fontSize = (size.value * 0.56f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * A one-line label that blurs out (2px, rising .24em) and blurs back in (from 4px) when its text
 * changes — the page's text swap — and, while [shimmering], carries the highlight sweep.
 */
@Composable
private fun SwapText(text: String, shimmering: Boolean, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val rise = with(LocalDensity.current) { style.fontSize.toPx() * 0.24f }
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(120, delayMillis = 80)) togetherWith fadeOut(tween(90)) },
        contentAlignment = Alignment.CenterStart,
        modifier = modifier,
        label = "swap",
    ) { t ->
        val blur by transition.animateFloat(transitionSpec = { tween(160, easing = ToolCallMotion.standard) }, label = "blur") {
            when (it) { EnterExitState.PreEnter -> 4f; EnterExitState.Visible -> 0f; EnterExitState.PostExit -> 2f }
        }
        val dy by transition.animateFloat(transitionSpec = { tween(120) }, label = "dy") {
            if (it == EnterExitState.PostExit) -rise else 0f
        }
        ShimmerLabel(
            t, shimmering, style, color,
            Modifier.graphicsLayer { translationY = dy }.then(if (blur > 0.05f) Modifier.blur(blur.dp) else Modifier),
        )
    }
}

/**
 * The page's text shimmer: text sits in a muted base (12% of its colour into `--text-muted`) and a
 * 4.4em band of the full colour (stops 0/50/90/100/90/50/0%) sweeps left to right in 1.8s, then rests
 * .6s. When [shimmering] drops, the base eases back to the full colour.
 */
@Composable
private fun ShimmerLabel(text: String, shimmering: Boolean, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val c = LocalToolCallColors.current
    val strength by animateFloatAsState(if (shimmering) 1f else 0f, tween(if (shimmering) 160 else 420, easing = ToolCallMotion.standard))
    if (strength == 0f) {
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    var width by remember { mutableStateOf(1f) }
    val em = with(LocalDensity.current) { style.fontSize.toPx() }
    val pos by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 2400; 0f at 0 using LinearEasing; 1f at 1800; 1f at 2399 }, RepeatMode.Restart),
        label = "pos",
    )
    val base = lerp(color, lerp(c.textMuted, color, 0.12f), strength)
    val x = width * pos
    val brush = Brush.horizontalGradient(
        0f to base,
        (1.1f / 4.4f) to lerp(base, color, 0.5f),
        (1.76f / 4.4f) to lerp(base, color, 0.9f),
        0.5f to color,
        (2.64f / 4.4f) to lerp(base, color, 0.9f),
        (3.3f / 4.4f) to lerp(base, color, 0.5f),
        1f to base,
        startX = x - 2.2f * em, endX = x + 2.2f * em,
    )
    Text(
        text, style = style.copy(brush = brush), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.onSizeChanged { width = it.width.toFloat() },
    )
}

/** Running spinner, a check that draws itself, alert, shield, ban — swapped exit-then-enter at scale .6. */
@Composable
private fun StatusIcon(status: ToolCallStatus) {
    val c = LocalToolCallColors.current
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (scaleIn(ToolCallMotion.statusIn(), initialScale = 0.6f) + fadeIn(tween(90, delayMillis = 130))) togetherWith
                (scaleOut(tween(130, easing = ToolCallMotion.standard), targetScale = 0.6f) + fadeOut(tween(110)))
        },
        modifier = Modifier.size(16.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = status.name },
        contentAlignment = Alignment.Center,
        label = "status",
    ) { s ->
        when (s) {
            ToolCallStatus.Running -> Spinner(c.textMuted)
            ToolCallStatus.Success -> DrawnCheck(c.success)
            ToolCallStatus.Error -> AlertGlyph(c.danger)
            ToolCallStatus.Approval -> Icon(Icons.Outlined.Shield, null, tint = c.accent, modifier = Modifier.size(15.dp))
            ToolCallStatus.Denied -> Icon(Icons.Outlined.Block, null, tint = c.textMuted, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun Spinner(color: Color) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "a",
    )
    Canvas(Modifier.size(14.dp)) {
        val w = 1.4.dp.toPx()
        drawArc(
            color, startAngle = angle, sweepAngle = 290f, useCenter = false,
            topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

/** lucide `check` (4,12 → 9,17 → 20,6 on a 24 grid), drawn once. */
@Composable
private fun DrawnCheck(color: Color) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(300, delayMillis = 40, easing = ToolCallMotion.standard)) }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    Canvas(Modifier.size(16.dp)) {
        val u = size.width / 24f
        val p = Path().apply { moveTo(4 * u, 12 * u); lineTo(9 * u, 17 * u); lineTo(20 * u, 6 * u) }
        measure.setPath(p, false)
        segment.reset()
        measure.getSegment(0f, measure.length * progress.value, segment, true)
        drawPath(segment, color, style = Stroke(2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** lucide `circle-alert`. */
@Composable
private fun AlertGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val u = size.width / 24f
        val w = 2 * u
        drawCircle(color, radius = 10 * u, style = Stroke(w))
        drawLine(color, Offset(12 * u, 8 * u), Offset(12 * u, 12 * u), w, StrokeCap.Round)
        drawLine(color, Offset(12 * u, 16 * u), Offset(12.01f * u, 16 * u), w, StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------
// Panel: Input / Output / Error
// ---------------------------------------------------------------------------

@Composable
private fun ToolCallPanel(call: ToolCallData, renderOutput: (@Composable (ToolCallData) -> Boolean)?) {
    val c = LocalToolCallColors.current
    BoxWithConstraints {
        val narrow = maxWidth < 440.dp
        Column(
            Modifier.fillMaxWidth().padding(start = if (narrow) PadH else Indent, end = PadH, bottom = PadH),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (call.input.isNotEmpty()) {
                Section("Input", call.input.joinToString("\n") { (k, v) -> "$k: $v" }) { KeyValues(call.input) }
            }
            val out = call.output
            when {
                call.status == ToolCallStatus.Error && call.message != null ->
                    Section("Error", call.message) { Text(call.message, style = Body, color = c.foreground) }
                out != null && call.status != ToolCallStatus.Running && call.status != ToolCallStatus.Approval ->
                    Section("Output", out.copyText(), tight = out is ToolCallOutput.Items || out is ToolCallOutput.Table) {
                        if (call.status != ToolCallStatus.Success || renderOutput?.invoke(call) != true) OutputBody(out, narrow)
                    }
                call.status == ToolCallStatus.Running ->
                    Section("Output", null) { Text("Waiting for the result…", style = Body, color = c.textMuted) }
            }
        }
    }
}

@Composable
private fun Section(label: String, copyText: String?, tight: Boolean = false, content: @Composable () -> Unit) {
    val c = LocalToolCallColors.current
    val platform = LocalPlatform.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Meta, color = c.textMuted, modifier = Modifier.weight(1f))
            if (copyText != null) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(role = Role.Button) { platform.copyToClipboard(copyText) }
                        .semantics { contentDescription = "Copy ${label.lowercase()}" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.ContentCopy, null, tint = c.textMuted, modifier = Modifier.size(14.dp)) }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.surfaceMuted)
                .padding(horizontal = 12.dp, vertical = if (tight) 4.dp else 10.dp),
        ) { content() }
    }
}

private val MonoXs: TextStyle
    @Composable get() = TextStyle(fontFamily = MonoFontFamily, fontSize = 12.sp, lineHeight = 18.sp)

@Composable
private fun KeyValues(input: List<Pair<String, String>>) {
    val c = LocalToolCallColors.current
    // Agent prompts and heredoc commands run to thousands of characters: a few lines until asked.
    var full by remember { mutableStateOf(false) }
    var clipped by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize(ToolCallMotion.height()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        input.forEach { (k, v) ->
            Row {
                Text(k, style = MonoXs, color = c.textMuted, modifier = Modifier.widthIn(min = 56.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    v, style = MonoXs, color = c.foreground,
                    maxLines = if (full) Int.MAX_VALUE else ToolCallMotion.INPUT_LINES, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow) clipped = true },
                )
            }
        }
        if (clipped) MoreButton(if (full) "Show less" else "Show all", full) { full = !full }
    }
}

@Composable
private fun OutputBody(out: ToolCallOutput, narrow: Boolean) {
    val c = LocalToolCallColors.current
    when (out) {
        is ToolCallOutput.Items -> Foldable(out.items.size, ToolCallMotion.FOLD_ROWS) { n ->
            out.items.take(n).forEach { item ->
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    item.lead?.let { Text(it, style = MonoXs, color = c.textMuted); Spacer(Modifier.width(12.dp)) }
                    Text(item.title, style = Body, color = c.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!narrow) item.tag?.let { Spacer(Modifier.width(12.dp)); Text(it, style = Meta, color = c.textSecondary) }
                }
            }
        }
        is ToolCallOutput.Table -> Foldable(out.rows.size, ToolCallMotion.FOLD_ROWS) { n ->
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row(
                    Modifier.height(32.dp).drawBehind {
                        drawLine(c.border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    out.columns.forEach { Text(it, style = Meta, color = c.textMuted, modifier = Modifier.widthIn(min = 72.dp).padding(end = 16.dp)) }
                }
                out.rows.take(n).forEach { row ->
                    Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        row.forEach { Text(it, style = MonoXs, color = c.foreground, maxLines = 1, modifier = Modifier.widthIn(min = 72.dp).padding(end = 16.dp)) }
                    }
                }
            }
        }
        is ToolCallOutput.Json -> {
            val lines = out.text.lines()
            Foldable(lines.size, ToolCallMotion.FOLD_LINES) { n ->
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(highlightJson(lines.take(n).joinToString("\n"), c), style = MonoXs, color = c.textSecondary, softWrap = false)
                }
            }
        }
        is ToolCallOutput.Code -> {
            val lines = out.text.lines()
            Foldable(lines.size, ToolCallMotion.FOLD_LINES) { n ->
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(lines.take(n).joinToString("\n"), style = MonoXs, color = c.textSecondary, softWrap = false)
                }
            }
        }
        is ToolCallOutput.Text -> {
            // Prose wraps, so fold on rendered lines rather than newlines.
            var full by remember { mutableStateOf(false) }
            var clipped by remember { mutableStateOf(false) }
            Column(Modifier.animateContentSize(ToolCallMotion.height())) {
                Text(
                    out.text, style = Body, color = c.textSecondary,
                    maxLines = if (full) Int.MAX_VALUE else ToolCallMotion.FOLD_LINES, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow) clipped = true },
                )
                if (clipped) MoreButton(if (full) "Show less" else "Show all", full) { full = !full }
            }
        }
    }
}

/** Keys in the text colour, strings toward success, numbers toward warning, literals muted. */
private fun highlightJson(src: String, c: ToolCallColors): AnnotatedString = buildAnnotatedString {
    val token = Regex(""""(?:\\.|[^"\\])*"(\s*:)?|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|\btrue\b|\bfalse\b|\bnull\b""")
    var last = 0
    for (m in token.findAll(src)) {
        append(src.substring(last, m.range.first))
        val t = m.value
        val color = when {
            t.startsWith("\"") && m.groups[1] != null -> c.foreground
            t.startsWith("\"") -> lerp(c.foreground, c.success, 0.8f)
            t == "true" || t == "false" || t == "null" -> c.textMuted
            else -> lerp(c.foreground, c.warning, 0.82f)
        }
        withStyle(SpanStyle(color = color)) { append(t) }
        last = m.range.last + 1
    }
    append(src.substring(last))
}

/** Shows [limit] entries, then "Show N more"; the well's height follows on the height spring. */
@Composable
private fun Foldable(total: Int, limit: Int, content: @Composable (shown: Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize(ToolCallMotion.height())) {
        content(if (expanded || total <= limit) total else limit)
        if (total > limit) MoreButton(if (expanded) "Show less" else "Show ${total - limit} more", expanded) { expanded = !expanded }
    }
}

@Composable
private fun MoreButton(label: String, open: Boolean, onClick: () -> Unit) {
    val c = LocalToolCallColors.current
    val turn by animateFloatAsState(if (open) 180f else 0f, ToolCallMotion.chevron())
    Row(
        Modifier
            .padding(top = 4.dp, bottom = 2.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.KeyboardArrowDown, null, tint = c.textSecondary, modifier = Modifier.size(14.dp).rotate(turn))
        Text(label, style = Meta, color = c.textSecondary)
    }
}

private fun ToolCallOutput.copyText(): String = when (this) {
    is ToolCallOutput.Items -> items.joinToString("\n") { listOfNotNull(it.lead, it.title, it.tag).joinToString("  ") }
    is ToolCallOutput.Table -> (listOf(columns) + rows).joinToString("\n") { it.joinToString("\t") }
    is ToolCallOutput.Json -> text
    is ToolCallOutput.Code -> text
    is ToolCallOutput.Text -> text
}

// ---------------------------------------------------------------------------
// ToolCallGroup
// ---------------------------------------------------------------------------

/**
 * "Using/Used N tools" over a stack of ringed tool marks, "N failed" or the total time on the right.
 * Open while anything runs, waits or failed; folds [ToolCallMotion.AUTO_COLLAPSE_MS] after every
 * call finishes unless the user opened or closed it themselves.
 */
@Composable
fun ToolCallGroup(
    calls: List<ToolCallData>,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    defaultOpen: Boolean? = null,
    open: Boolean? = null,
    onOpenChange: ((Boolean) -> Unit)? = null,
    autoCollapse: Boolean = true,
    onApprove: ((ToolCallData) -> Unit)? = null,
    onDeny: ((ToolCallData) -> Unit)? = null,
    onRetry: ((ToolCallData) -> Unit)? = null,
    renderOutput: (@Composable (ToolCallData) -> Boolean)? = null,
    colors: ToolCallColors? = null,
) = ProvideToolCallColors(colors) {
    val c = LocalToolCallColors.current
    // "Busy" is work still in flight; a failed call is finished, it only keeps the list open.
    val busy = active || calls.any { it.status == ToolCallStatus.Running || it.status == ToolCallStatus.Approval }
    val attention = calls.any { it.status.needsAttention }
    // Saveable: a group scrolled out of a LazyColumn and back keeps what the user did to it.
    var openLocal by rememberSaveable { mutableStateOf(defaultOpen ?: (busy || attention)) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val isOpen = open ?: openLocal
    val setOpen = { v: Boolean -> openLocal = v; onOpenChange?.invoke(v) }

    LaunchedEffect(attention) { if (attention && !touched) setOpen(true) }
    LaunchedEffect(busy, attention, autoCollapse, touched) {
        if (autoCollapse && !busy && !attention && !touched && isOpen) {
            delay(ToolCallMotion.AUTO_COLLAPSE_MS)
            setOpen(false)
        }
    }

    val failed = calls.count { it.status == ToolCallStatus.Error }
    val totalMs = calls.mapNotNull { it.durationMs }.sum()
    val marks = calls.map { it.tool }.distinctBy { it.name }
    val shape = RoundedCornerShape(16.dp)

    Column(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val pressed by interaction.collectIsPressedAsState()
        val bg by animateColorAsState(
            when { pressed -> c.surfaceMuted; hovered -> c.surfaceMuted.copy(alpha = 0.75f); else -> Color.Transparent },
            ToolCallMotion.fast(),
        )
        Box(Modifier.padding(4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RowMinHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) { touched = true; setOpen(!isOpen) }
                    .semantics { stateDescription = if (isOpen) "Expanded" else "Collapsed" }
                    .testTag("tool_group_head")
                    .padding(horizontal = PadH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarkStack(marks)
                Spacer(Modifier.width(Gap))
                SwapText(
                    text = "${if (active) "Using" else "Used"} ${plural(calls.size, "tool")}",
                    shimmering = active,
                    style = Body,
                    color = if (hovered) c.foreground else c.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Gap))
                when {
                    failed > 0 -> Text("$failed failed", style = Meta, color = lerp(c.textSecondary, c.danger, 0.85f))
                    !busy && totalMs > 0 -> Text(formatToolDuration(totalMs), style = Meta, color = c.textMuted)
                }
                Spacer(Modifier.width(Gap))
                val turn by animateFloatAsState(if (isOpen) 180f else 0f, ToolCallMotion.chevron())
                Icon(
                    Icons.Outlined.KeyboardArrowDown, null,
                    tint = if (hovered) c.textSecondary else c.textMuted,
                    modifier = Modifier.size(16.dp).rotate(turn),
                )
            }
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = expandVertically(ToolCallMotion.height(), expandFrom = Alignment.Top) + fadeIn(tween(200, easing = ToolCallMotion.standard)),
            exit = shrinkVertically(ToolCallMotion.height(), shrinkTowards = Alignment.Top) + fadeOut(tween(220, delayMillis = 160)),
        ) {
            // Rows present when the list opens stagger in; rows appended later grow from zero height.
            val atOpen = remember { calls.map { it.id }.toSet() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(c.border, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
                    .padding(4.dp),
            ) {
                calls.forEachIndexed { i, call ->
                    key(call.id) {
                        val row = @Composable {
                            ToolCallRow(
                                call, flush = true, defaultOpen = false, open = null, onOpenChange = { touched = true },
                                onApprove = onApprove?.let { { it(call) } },
                                onDeny = onDeny?.let { { it(call) } },
                                onRetry = onRetry?.let { { it(call) } },
                                renderOutput = renderOutput,
                            )
                        }
                        if (call.id in atOpen) Staggered(i) { row() } else GrowIn { row() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Staggered(index: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.toLong() * ToolCallMotion.STAGGER_MS)
        p.animateTo(1f, ToolCallMotion.height())
    }
    val settle = with(LocalDensity.current) { 6.dp.toPx() }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * settle }) { content() }
}

/** A row appended mid-run: height from zero on the height spring, faded in once it is ~60% tall. */
@Composable
private fun GrowIn(content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = expandVertically(ToolCallMotion.height(), expandFrom = Alignment.Top) + fadeIn(tween(100, delayMillis = 150)),
    ) { content() }
}

/**
 * 20dp circles, 12dp marks, a 1px border ring inside a 2px surface ring, overlapping by 4dp. A mark
 * that joins widens the stack (0 → 16dp) on the height spring, scaling up from .6, fading in late.
 */
@Composable
private fun MarkStack(marks: List<ToolMark>) {
    val c = LocalToolCallColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        marks.take(5).forEachIndexed { i, mark ->
            key(mark.name) {
                val p = remember { Animatable(if (i == 0) 1f else 0f) }
                LaunchedEffect(Unit) { p.animateTo(1f, ToolCallMotion.height()) }
                Box(
                    Modifier
                        .layout { measurable, _ ->
                            val circle = 20.dp.roundToPx()
                            val placeable = measurable.measure(Constraints.fixed(circle, circle))
                            val slot = if (i == 0) 18.dp.toPx() else 16.dp.toPx()
                            val dx = if (i == 0) -2.dp.roundToPx() else -4.dp.roundToPx()
                            layout((slot * p.value).roundToInt(), circle) {
                                placeable.placeWithLayer(dx, 0) {
                                    val k = p.value
                                    scaleX = 0.6f + 0.4f * k; scaleY = scaleX
                                    alpha = ((k - 0.55f) / 0.2f).coerceIn(0f, 1f)
                                }
                            }
                        }
                        .drawBehind {
                            val r = size.minDimension / 2
                            drawCircle(c.surface, r + 2.dp.toPx())
                            drawCircle(c.border, r + 1.dp.toPx())
                            drawCircle(c.surface, r)
                        },
                    contentAlignment = Alignment.Center,
                ) { MarkGlyph(mark, 12.dp) }
            }
        }
        if (marks.size > 5) Text("+${marks.size - 5}", style = Meta, color = c.textMuted, modifier = Modifier.padding(start = 6.dp))
    }
}
