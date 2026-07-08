package dev.supermux.android.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.supermux.proto.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.LogEntry
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.MdBlock
import dev.supermux.ui.SpanStyleKind
import dev.supermux.ui.parseInlineMarkdown
import dev.supermux.ui.parseMarkdownBlocks

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

enum class ToolStatus { RUNNING, DONE, ERROR }

sealed interface TimelineItem {
    data class Msg(val entry: LogEntry) : TimelineItem
    data class Tool(
        val event: ActivityEvent,
        val status: ToolStatus,
        val output: String? = null,   // detail from the matching tool_result event (iOS folds as Output)
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
 */
fun mergeTimeline(
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
): List<TimelineItem> {
    // callId -> resolved final status + output detail from `tool_result` events
    val resultStatus = HashMap<String, ToolStatus>()
    val resultDetail = HashMap<String, String?>()
    for (e in activity) {
        val id = e.callId
        if (e.kind == "tool_result" && id != null) {
            resultStatus[id] = if (e.phase == "failed") ToolStatus.ERROR else ToolStatus.DONE
            resultDetail[id] = e.detail
        }
    }
    val items = ArrayList<TimelineItem>(messages.size + activity.size)
    messages.forEach { items.add(TimelineItem.Msg(it)) }
    for (e in activity) {
        when (e.kind) {
            "tool" -> {
                val status = e.callId?.let { resultStatus[it] } ?: ToolStatus.RUNNING
                val output = e.callId?.let { resultDetail[it] }
                items.add(TimelineItem.Tool(e, status, output))
            }
            "tool_result" -> { /* folded into the matching tool row above */ }
            // "thinking" (and any other non-tool kind) is intentionally dropped — thinking
            // shows as a live indicator, not a persistent "Thought for Ns" history row.
            else -> { /* dropped */ }
        }
    }
    return items.sortedBy { item ->
        when (item) {
            is TimelineItem.Msg -> item.entry.ts
            is TimelineItem.Tool -> item.event.ts
        }
    }
}

// ---------------------------------------------------------------------------
// Markdown helpers
// ---------------------------------------------------------------------------

/**
 * Convert a markdown string to an [AnnotatedString] with inline bold/italic/code spans.
 *
 * Inline `code` spans get MonoFontFamily + subtle background tint via SpanStyle.
 * TODO: detect fenced ``` blocks as a pre-pass and render them as dedicated
 *       [FencedCodeBlock] composables instead of inline spans.
 */
@Composable
fun mdAnnotated(
    text: String,
    onOpenFile: (FilePathRef) -> Unit = {},
    linkify: Boolean = false,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        text.split("\n").forEachIndexed { i, line ->
            if (i > 0) append("\n")
            for (s in parseInlineMarkdown(line)) {
                when (s.kind) {
                    SpanStyleKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.text) }
                    SpanStyleKind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
                    SpanStyleKind.CODE -> withStyle(
                        SpanStyle(
                            fontFamily = MonoFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    ) { append(s.text) }
                    SpanStyleKind.LINK -> {
                        val ref = s.ref
                        if (ref == null || !linkify) append(s.text) else withLink(
                            LinkAnnotation.Clickable(
                                tag = "file:${ref.path}",
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                                ),
                            ) { onOpenFile(ref) }
                        ) { append(s.text) }
                    }
                    SpanStyleKind.PLAIN -> appendLinkified(s.text, linkColor)
                }
            }
        }
    }
}

private val urlRegex = Regex("""https?://[^\s<>"'\])]+""")

/** Append [text], turning bare http(s) URLs into tappable, underlined links (opens the browser). */
private fun AnnotatedString.Builder.appendLinkified(text: String, linkColor: Color) {
    var last = 0
    for (m in urlRegex.findAll(text)) {
        var url = m.value
        var trail = ""
        // A URL at the end of a sentence often swallows trailing punctuation — hand it back as plain text.
        while (url.isNotEmpty() && url.last() in ".,);:!?") { trail = url.last() + trail; url = url.dropLast(1) }
        if (url.isEmpty()) continue
        if (m.range.first > last) append(text.substring(last, m.range.first))
        withLink(
            LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
        ) { append(url) }
        if (trail.isNotEmpty()) append(trail)
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

/**
 * Elegant mono code block for fenced ``` content.
 * Horizontal accent line + subtle header-tinted background + horizontal scroll.
 * A top-end copy button (iOS CodeBlock parity) copies the raw code, flashing a check for ~1.5s.
 */
@Composable
fun FencedCodeBlock(code: String) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 0.dp, topEnd = Radii.sm, bottomStart = 0.dp, bottomEnd = Radii.sm))
                .background(cs.surfaceContainerLow)
                .padding(start = 0.dp),
        ) {
            // 2dp left accent
            Box(
                Modifier
                    .width(2.dp)
                    .height(1.dp) // height will stretch with the Row's intrinsic content height
                    .background(cs.primary.copy(alpha = 0.4f)),
            )
            Box(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    // pad the right so the copy button never overlaps the first line of code
                    .padding(start = Space.md, end = Space.xl + Space.md, top = Space.sm, bottom = Space.sm),
            ) {
                Text(
                    text = code,
                    fontFamily = MonoFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 19.5.sp,
                    color = cs.onSurface.copy(alpha = 0.9f),
                )
            }
        }
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(code))
                copied = true
                scope.launch { delay(1500); copied = false }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Space.xs)
                .size(28.dp),
        ) {
            Icon(
                painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
                contentDescription = if (copied) "Copied" else "Copy",
                tint = if (copied) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

/**
 * Calm Premium — outbound (assistant) message.
 * Splits text into prose and fenced code blocks; renders each accordingly.
 * Prose: bodyLarge (15sp / 24sp line-height), full width, no box or border.
 * Code: FencedCodeBlock composable (mono, horizontal scroll, left accent).
 */
@Composable
fun AssistantMessage(text: String, onOpenFile: (FilePathRef) -> Unit = {}) {
    // SelectionContainer makes the prose selectable/copyable; links inside stay tappable.
    SelectionContainer {
        MarkdownBody(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            onOpenFile = onOpenFile,
            linkify = true,
        )
    }
}

/**
 * Reusable block-markdown renderer (prose + fenced code), the single source for chat,
 * the editor markdown preview, and the PWA-parity rendering. Splits via the shared
 * [parseMarkdownBlocks]; prose uses inline bold/italic/code spans, code uses
 * [FencedCodeBlock]. Keep all markdown surfaces routed through this so they never drift.
 */
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier, onOpenFile: (FilePathRef) -> Unit = {}, linkify: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val blocks = parseMarkdownBlocks(text)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Prose -> {
                    if (block.text.isNotBlank()) {
                        Text(
                            text = mdAnnotated(block.text, onOpenFile, linkify = linkify),
                            color = cs.onSurface,
                            style = typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is MdBlock.Code -> FencedCodeBlock(block.code)
                is MdBlock.Heading -> Text(
                    text = mdAnnotated(block.text, onOpenFile, linkify = linkify),
                    color = cs.onSurface,
                    style = when (block.level) {
                        1 -> typography.titleLarge
                        2 -> typography.titleMedium
                        else -> typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
                is MdBlock.Quote -> Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(cs.primary.copy(alpha = 0.5f)),
                    )
                    Text(
                        text = mdAnnotated(block.text, onOpenFile, linkify = linkify),
                        color = cs.onSurfaceVariant,
                        style = typography.bodyLarge,
                    )
                }
                is MdBlock.Bullet -> Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Text("•", color = cs.onSurfaceVariant, style = typography.bodyLarge)
                    Text(
                        text = mdAnnotated(block.text, onOpenFile, linkify = linkify),
                        color = cs.onSurface,
                        style = typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdBlock.Numbered -> Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Text("${block.n}.", color = cs.onSurfaceVariant, style = typography.bodyLarge)
                    Text(
                        text = mdAnnotated(block.text, onOpenFile, linkify = linkify),
                        color = cs.onSurface,
                        style = typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Calm Premium — inbound (user) message.
 * Subtle end-aligned bubble: card background @85%, 1px border, rounded with a
 * tightened bottom-end corner, max 84% width, bodyMedium text.
 */
@Composable
fun UserMessage(text: String) {
    val cs = MaterialTheme.colorScheme
    // Inbound entry in the session log: left-aligned, a mono "you" label, a tightened
    // leading corner so it reads as the human's turn on the thread.
    val bubbleShape = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = Radii.md,
        bottomStart = Radii.md,
        bottomEnd = Radii.md,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(bubbleShape)
            .background(cs.surfaceContainer.copy(alpha = 0.9f))
            .border(1.dp, cs.outlineVariant, bubbleShape)
            .padding(horizontal = Space.md, vertical = Space.sm),
    ) {
        Text(
            text = "you",
            color = cs.primary,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        SelectionContainer {
            Text(
                text = mdAnnotated(text),
                color = cs.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Per-tool leading glyph (iOS ToolRowView.icon parity). Missing icons fall back to existing ones. */
private fun toolIcon(tool: String): Int = when (tool) {
    "Bash" -> R.drawable.ic_terminal
    "Read" -> R.drawable.ic_file
    "Edit", "Write" -> R.drawable.ic_pencil
    "Grep" -> R.drawable.ic_search
    "Glob" -> R.drawable.ic_folder_open
    "Task", "Agent" -> R.drawable.ic_sparkle
    "Skill" -> R.drawable.ic_file
    "WebFetch", "WebSearch" -> R.drawable.ic_globe
    else -> R.drawable.ic_settings
}

/** Strip an mcp__server__tool name down to its last segment (iOS label parity). */
private fun toolLabel(tool: String): String =
    if (tool.startsWith("mcp__")) tool.substringAfterLast("__") else tool

/**
 * Calm Premium — tool-use activity.
 * Quiet row: per-tool leading icon + thin accent rail + tool label + mono summary ellipsis
 * + trailing status indicator. Tappable to expand split Input / Output blocks (collapsed by
 * default; expand affordance only when there is input or output).
 */
@Composable
fun ToolCard(event: ActivityEvent, status: ToolStatus, output: String? = null) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    val input = event.detail
    val hasContent = !input.isNullOrBlank() || !output.isNullOrBlank()
    val verb = toolLabel(event.tool ?: "tool").lowercase()
    // Strip a leading "<Tool>: " label — Claude puts it in the title; other agents
    // (codex/cursor/opencode) emit the bare command/path, so this is a no-op for them.
    val arg = event.title?.let { t -> event.tool?.let { t.removePrefix("$it: ") } ?: t }

    Column(Modifier.fillMaxWidth().testTag("tool_card")) {
        // Terminal-style operation line: ▸ verb · arg … status. A sunken surface, mono
        // throughout, so a tool call reads as an executed command, not a chat card.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.sm))
                .background(cs.surfaceContainerLowest)
                .clickable(enabled = hasContent) { expanded = !expanded }
                .padding(horizontal = Space.sm + Space.xs, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▸", color = cs.primary, fontFamily = MonoFontFamily, fontSize = 13.sp)
            Spacer(Modifier.width(Space.sm))
            Text(
                text = verb,
                color = cs.onSurface,
                fontFamily = MonoFontFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (arg != null) {
                Text(
                    text = arg,
                    color = cs.onSurfaceVariant,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Space.sm),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(Space.sm))
            when (status) {
                ToolStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    color = cs.primary,
                    strokeWidth = 1.5.dp,
                )
                ToolStatus.DONE -> Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(13.dp),
                )
                ToolStatus.ERROR -> Icon(
                    painter = painterResource(R.drawable.ic_x),
                    contentDescription = null,
                    tint = cs.error,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        // Expandable Input + Output (mono; a diff renders with semantic add/remove).
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                Modifier.padding(top = Space.xs),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                input?.takeIf { it.isNotBlank() }?.let { ioBlock("input", it, error = false) }
                output?.takeIf { it.isNotBlank() }?.let {
                    if (looksLikeDiff(it)) InlineDiff(it)
                    else ioBlock("output", it, error = status == ToolStatus.ERROR)
                }
            }
        }
    }
}

/** True when [text] reads as a unified diff (hunk header or several ± lines). */
private fun looksLikeDiff(text: String): Boolean {
    val lines = text.lineSequence().take(40).toList()
    if (lines.any { it.startsWith("@@ ") || it.startsWith("diff --git") }) return true
    val pm = lines.count { (it.startsWith("+") && !it.startsWith("+++")) || (it.startsWith("-") && !it.startsWith("---")) }
    return pm >= 3
}

/** Inline unified-diff renderer: mono, semantic add/remove tints, horizontal scroll. */
@Composable
private fun InlineDiff(text: String) {
    val cs = MaterialTheme.colorScheme
    val sem = dev.supermux.android.theme.LocalSemantics.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(cs.surfaceContainerLowest)
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(vertical = Space.xs),
    ) {
        text.lineSequence().forEach { line ->
            val add = line.startsWith("+") && !line.startsWith("+++")
            val del = line.startsWith("-") && !line.startsWith("---")
            val hunk = line.startsWith("@@")
            val fg = when { add -> sem.success; del -> sem.danger; hunk -> cs.primary; else -> cs.onSurfaceVariant }
            val bg = when { add -> sem.success.copy(alpha = 0.10f); del -> sem.danger.copy(alpha = 0.10f); else -> Color.Transparent }
            Text(
                text = line.ifEmpty { " " },
                fontFamily = MonoFontFamily,
                fontSize = 12.5.sp,
                lineHeight = 18.5.sp,
                color = fg,
                modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = Space.md, vertical = 0.5.dp),
            )
        }
    }
}

/** A labelled, height-capped mono block — used for a tool call's Input / Output (iOS ioBlock). */
@Composable
private fun ioBlock(label: String, text: String, error: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            text = label,
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .clip(RoundedCornerShape(Radii.sm))
                .background(cs.surfaceContainerLow)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.md, vertical = Space.sm),
        ) {
            Text(
                text = text,
                fontFamily = MonoFontFamily,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
                color = if (error) cs.error else cs.onSurface.copy(alpha = 0.9f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Session-log stream layout — a mono gutter (time + status node, threaded by a
// hairline spine) beside each entry. Consecutive spine rows join into one thread.
// ---------------------------------------------------------------------------

/** Status marker drawn in a stream row's gutter. */
enum class StreamNode { NONE, RUNNING, DONE, ERROR, USER }

private val gutterFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** ISO-8601 ts → local `HH:mm` for the gutter (null if unparseable). */
private fun gutterTime(ts: String?): String? =
    ts?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(gutterFmt) }.getOrNull() }

@Composable
private fun NodeDot(node: StreamNode, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    when (node) {
        StreamNode.NONE -> Box(modifier.size(7.dp))
        StreamNode.USER -> Text("▸", color = cs.primary, fontFamily = MonoFontFamily, fontSize = 11.sp, modifier = modifier)
        StreamNode.DONE -> Box(modifier.size(7.dp).clip(CircleShape).background(cs.primary))
        StreamNode.ERROR -> Box(modifier.size(7.dp).clip(CircleShape).background(cs.error))
        StreamNode.RUNNING -> Box(modifier.size(7.dp).clip(CircleShape).background(cs.surface).border(1.5.dp, cs.primary, CircleShape))
    }
}

/** A softly breathing dot — the live pulse of a running/thinking agent. Respects the theme accent. */
@Composable
fun BreathingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "breathe")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.size(size).clip(CircleShape).background(color.copy(alpha = alpha)))
}

/**
 * One entry in the session log: a 44dp mono gutter (time + status [node]) beside [content],
 * with a hairline [spine] drawn full-height via drawBehind so consecutive spine rows join into
 * one continuous thread. drawBehind (not IntrinsicSize) keeps it safe with video/scroll content.
 */
@Composable
fun StreamRow(node: StreamNode, spine: Boolean, time: String?, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val lineColor = cs.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (spine) {
                    val x = 33.dp.toPx()
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                }
            },
    ) {
        Box(Modifier.width(44.dp)) {
            if (time != null) {
                Text(
                    text = time,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    color = if (node == StreamNode.USER) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp, top = 12.dp),
                )
            }
            NodeDot(node, Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 11.dp))
        }
        Box(Modifier.weight(1f).padding(top = 7.dp, bottom = 7.dp)) { content() }
    }
}

/** Dispatches a single TimelineItem to a gutter-threaded stream row. */
@Composable
fun TimelineItemRow(
    item: TimelineItem,
    loadBytes: suspend (String) -> ByteArray? = { null },
    onOpenFile: (FilePathRef) -> Unit = {},
) {
    when (item) {
        is TimelineItem.Msg -> {
            val text = item.entry.text
            val atts = item.entry.attachments
            val isUser = item.entry.direction == "inbound"
            if (!text.isNullOrBlank() || !atts.isNullOrEmpty()) {
                StreamRow(
                    node = if (isUser) StreamNode.USER else StreamNode.DONE,
                    spine = !isUser,
                    time = if (isUser) gutterTime(item.entry.ts) else null,
                ) {
                    Column {
                        if (!text.isNullOrBlank()) {
                            if (isUser) UserMessage(text) else AssistantMessage(text, onOpenFile)
                        }
                        if (!atts.isNullOrEmpty()) AttachmentList(atts, alignEnd = false, loadBytes = loadBytes)
                    }
                }
            }
        }
        is TimelineItem.Tool -> {
            val node = when (item.status) {
                ToolStatus.RUNNING -> StreamNode.RUNNING
                ToolStatus.ERROR -> StreamNode.ERROR
                ToolStatus.DONE -> StreamNode.DONE
            }
            StreamRow(node = node, spine = true, time = gutterTime(item.event.ts)) {
                ToolCard(item.event, item.status, item.output)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------

/** Renders a message's attachments below its text, aligned to the sender side. */
@Composable
fun AttachmentList(
    attachments: List<Attachment>,
    alignEnd: Boolean,
    loadBytes: suspend (String) -> ByteArray?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        for (att in attachments) AttachmentItem(att, loadBytes)
    }
}

@Composable
private fun AttachmentItem(att: Attachment, loadBytes: suspend (String) -> ByteArray?) {
    val cs = MaterialTheme.colorScheme
    val mime = att.mime ?: ""
    val isImage = att.kind == "image" || mime.startsWith("image/")
    val isVideo = att.kind == "video" || att.kind == "video_note" || mime.startsWith("video/")
    val isAudio = att.kind == "voice" || att.kind == "audio" || mime.startsWith("audio/")
    when {
        isImage -> {
            var bmp by remember(att.file_id) { mutableStateOf<ImageBitmap?>(null) }
            var failed by remember(att.file_id) { mutableStateOf(false) }
            LaunchedEffect(att.file_id) {
                val bytes = loadBytes(att.file_id)
                val decoded = if (bytes != null) {
                    withContext(Dispatchers.Default) {
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    }
                } else null
                if (decoded != null) bmp = decoded.asImageBitmap() else failed = true
            }
            val b = bmp
            if (b != null) {
                var showLightbox by remember(att.file_id) { mutableStateOf(false) }
                Image(
                    bitmap = b,
                    contentDescription = att.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(Radii.md))
                        .clickable { showLightbox = true },
                )
                if (showLightbox) ImageLightbox(b, onDismiss = { showLightbox = false })
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(120.dp)
                        .clip(RoundedCornerShape(Radii.md))
                        .background(cs.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (failed) {
                        Text("image unavailable", color = cs.onSurfaceVariant, fontSize = 11.sp, fontFamily = MonoFontFamily)
                    } else {
                        CircularProgressIndicator(Modifier.size(18.dp), color = cs.onSurfaceVariant, strokeWidth = 1.5.dp)
                    }
                }
            }
        }
        isVideo -> InlineVideo(att, loadBytes)
        isAudio -> AttachmentChip(R.drawable.ic_volume_2, att.name ?: "voice message", att, loadBytes)
        else -> AttachmentChip(R.drawable.ic_file, att.name ?: att.file_id, att, loadBytes)
    }
}

/**
 * Inline video playback (design 2026-07-02 Phase 1). Renders `video`/`video_note` attachments
 * with a tap-to-play poster so the transcript never eagerly downloads every clip (a video can be
 * up to 500 MB). On tap we fetch the bytes via loadBytes, cache them to a file (same
 * cacheDir/attachments dir openAttachment uses), and mount a media3 ExoPlayer inside an
 * AndroidView (PlayerView + default controls, autoplay once the user opted in). A missing
 * download or a decode error falls back to the pre-Phase-1 system-viewer AttachmentChip.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun InlineVideo(att: Attachment, loadBytes: suspend (String) -> ByteArray?) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var playing by remember(att.file_id) { mutableStateOf(false) }
    var file by remember(att.file_id) { mutableStateOf<File?>(null) }
    var failed by remember(att.file_id) { mutableStateOf(false) }

    // Fetch + cache the bytes only once the user opts into playback.
    LaunchedEffect(playing) {
        if (!playing || file != null || failed) return@LaunchedEffect
        val bytes = loadBytes(att.file_id)
        if (bytes == null) {
            failed = true
            return@LaunchedEffect
        }
        val cached = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                // Name by the unique file_id so two clips never collide in the cache dir; keep the
                // original extension as a container hint for ExoPlayer, defaulting to mp4.
                val safeId = att.file_id.substringAfterLast('/')
                val ext = (att.name ?: "").substringAfterLast('.', "").ifBlank { "mp4" }
                File(dir, "video_$safeId.$ext").apply { writeBytes(bytes) }
            }.getOrNull()
        }
        if (cached != null) file = cached else failed = true
    }

    val f = file
    when {
        failed -> AttachmentChip(R.drawable.ic_play, att.name ?: "video", att, loadBytes)
        !playing -> Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(200.dp)
                .clip(RoundedCornerShape(Radii.md))
                .background(cs.surfaceContainer)
                .clickable { playing = true }
                .testTag("attachment_video_poster"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = att.name ?: "Play video",
                tint = cs.onSurface,
                modifier = Modifier.size(40.dp),
            )
        }
        f == null -> Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(200.dp)
                .clip(RoundedCornerShape(Radii.md))
                .background(cs.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), color = cs.onSurfaceVariant, strokeWidth = 1.5.dp)
        }
        else -> {
            val exo = remember(f) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
                    prepare()
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            failed = true
                        }
                    })
                }
            }
            DisposableEffect(exo) {
                onDispose { exo.release() }
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = true
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(Radii.md))
                    .testTag("attachment_video_player"),
            )
        }
    }
}

/**
 * Fullscreen image lightbox (iOS Lightbox parity): black backdrop, fit-scaled image with
 * pinch-to-zoom + pan (clamped ≥1×), a close button. A Dialog with usePlatformDefaultWidth=false
 * is the idiomatic Android-native fullscreen overlay; predictive-back dismisses it for free.
 */
@Composable
private fun ImageLightbox(image: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .testTag("image_lightbox"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(Space.md),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_x),
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    iconRes: Int,
    label: String,
    att: Attachment,
    loadBytes: suspend (String) -> ByteArray?,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(att.file_id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.sm)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline, shape)
            .clickable(enabled = !busy) {
                busy = true
                scope.launch {
                    val bytes = loadBytes(att.file_id)
                    busy = false
                    if (bytes != null) openAttachment(context, att.name ?: att.file_id, att.mime, bytes)
                    else Toast.makeText(context, "Couldn't download attachment", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = cs.onSurfaceVariant, strokeWidth = 1.5.dp)
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            color = cs.onSurface,
            fontSize = 13.sp,
            fontFamily = MonoFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painter = painterResource(R.drawable.ic_download),
            contentDescription = "Download",
            tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Writes [bytes] to a cache file and opens it via the system (FileProvider), falling back to share. */
private fun openAttachment(context: Context, name: String, mime: String?, bytes: ByteArray) {
    try {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val safeName = name.substringAfterLast('/').ifBlank { "file" }
        val file = File(dir, safeName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val type = mime?.ifBlank { null } ?: context.contentResolver.getType(uri) ?: "*/*"
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val view = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, type); addFlags(flags) }
        try {
            context.startActivity(Intent.createChooser(view, "Open $safeName").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {
            val send = Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(flags)
            }
            context.startActivity(Intent.createChooser(send, "Share $safeName").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    } catch (_: Exception) {
        Toast.makeText(context, "Couldn't open attachment", Toast.LENGTH_SHORT).show()
    }
}
