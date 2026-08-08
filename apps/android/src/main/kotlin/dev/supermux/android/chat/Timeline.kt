package dev.supermux.android.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.platform.LocalUriHandler
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.proto.LogEntry
import dev.supermux.ui.resolveBashParts
import dev.supermux.ui.resolveEditParts
import coil3.compose.AsyncImage
import dev.supermux.ui.ColumnAlign
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.MdBlock
import dev.supermux.ui.SpanStyleKind
import dev.supermux.ui.parseInlineMarkdown
import dev.supermux.ui.parseMarkdownBlocks

// Shared fold — re-exported so package-local call sites keep working.
typealias TimelineItem = dev.supermux.chat.TimelineItem
typealias ToolStatus = dev.supermux.chat.ToolStatus

fun mergeTimeline(
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    hideTools: Boolean = false,
): List<TimelineItem> = dev.supermux.chat.mergeTimeline(messages, activity, hideTools)

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
    // Explicit open handler (desktop parity). LinkAnnotation.Url with NO listener relies on
    // LocalUriHandler, which SelectionContainer can swallow on Android — links then look like
    // plain text (styled or not) and taps do nothing. Always wire the click ourselves.
    val uriHandler = LocalUriHandler.current
    val openUrl: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }
    val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        text.split("\n").forEachIndexed { i, line ->
            if (i > 0) append("\n")
            for (s in parseInlineMarkdown(line)) {
                when (s.kind) {
                    SpanStyleKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        appendLinkified(s.text, linkStyles, openUrl)
                    }
                    SpanStyleKind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendLinkified(s.text, linkStyles, openUrl)
                    }
                    SpanStyleKind.CODE -> withStyle(
                        SpanStyle(
                            fontFamily = MonoFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    ) { append(s.text) } // never linkify inside inline code
                    SpanStyleKind.STRIKE -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendLinkified(s.text, linkStyles, openUrl)
                    }
                    SpanStyleKind.LINK -> {
                        val url = s.url
                        val ref = s.ref
                        when {
                            // Web links from `[label](url)` are always tappable (open the browser).
                            url != null -> withLink(
                                LinkAnnotation.Url(url, linkStyles) { openUrl(url) },
                            ) { append(s.text) }
                            // File paths only become editor links in agent messages (linkify).
                            ref != null && linkify -> withLink(
                                LinkAnnotation.Clickable(
                                    tag = "file:${ref.path}",
                                    styles = linkStyles,
                                ) { onOpenFile(ref) }
                            ) { append(s.text) }
                            else -> append(s.text)
                        }
                    }
                    SpanStyleKind.PLAIN -> appendLinkified(s.text, linkStyles, openUrl)
                }
            }
        }
    }
}

private val urlRegex = Regex("""https?://[^\s<>"'\])]+""")

/** Append [text], turning bare http(s) URLs into tappable, underlined links (opens the browser). */
private fun AnnotatedString.Builder.appendLinkified(
    text: String,
    linkStyles: TextLinkStyles,
    openUrl: (String) -> Unit,
) {
    var last = 0
    for (m in urlRegex.findAll(text)) {
        var url = m.value
        var trail = ""
        // A URL at the end of a sentence often swallows trailing punctuation — hand it back as plain text.
        while (url.isNotEmpty() && url.last() in ".,);:!?") { trail = url.last() + trail; url = url.dropLast(1) }
        if (url.isEmpty()) continue
        if (m.range.first > last) append(text.substring(last, m.range.first))
        withLink(
            LinkAnnotation.Url(url, linkStyles) { openUrl(url) },
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
 * Outbound (assistant) message — full-width prose, no card.
 * Footer: small local time + one-tap copy of the whole response (minimal chrome).
 */
@Composable
fun AssistantMessage(
    text: String,
    ts: String? = null,
    onOpenFile: (FilePathRef) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        // SelectionContainer makes the prose selectable; links inside stay tappable.
        SelectionContainer {
            MarkdownBody(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                onOpenFile = onOpenFile,
                linkify = true,
            )
        }
        MessageMetaRow(text = text, ts = ts)
    }
}

private val messageTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** ISO-8601 or epoch-ms/s → local `HH:mm` (null if unparseable). */
private fun formatMessageTime(ts: String?): String? {
    if (ts.isNullOrBlank()) return null
    val instant = ts.toLongOrNull()?.let { n ->
        Instant.ofEpochMilli(if (n < 1_000_000_000_000L) n * 1000L else n)
    } ?: runCatching { Instant.parse(ts) }.getOrNull()
    return instant?.atZone(ZoneId.systemDefault())?.format(messageTimeFmt)
}

/** Compact time + copy + read-aloud under an agent reply — quiet, minimal chrome. */
@Composable
private fun MessageMetaRow(text: String, ts: String?) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val speechKey = remember(text) { plainTextForSpeech(text) }
    // Reads Compose state from MessageTts so all rows recompose when speak starts/stops.
    val speaking = MessageTts.isSpeaking(speechKey)
    val time = formatMessageTime(ts)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (time != null) {
            Text(
                text = time,
                color = cs.onSurfaceVariant.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = Space.xs),
            )
        }
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
                scope.launch {
                    delay(1500)
                    copied = false
                }
            },
            modifier = Modifier
                .size(28.dp)
                .testTag("message_copy"),
        ) {
            Icon(
                painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
                contentDescription = if (copied) "Copied" else "Copy response",
                tint = if (copied) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp),
            )
        }
        IconButton(
            onClick = {
                if (speechKey.isBlank()) {
                    Toast.makeText(context, "Nothing to read", Toast.LENGTH_SHORT).show()
                } else {
                    MessageTts.toggle(context, text)
                }
            },
            modifier = Modifier
                .size(28.dp)
                .testTag("message_read_aloud"),
        ) {
            Icon(
                painter = painterResource(if (speaking) R.drawable.ic_square else R.drawable.ic_volume_2),
                contentDescription = if (speaking) "Stop reading" else "Read aloud",
                tint = if (speaking) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp),
            )
        }
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
                    // Task-list items show a checkbox glyph (display-only); plain bullets keep the dot.
                    val marker = when (block.task) {
                        true -> "☑"
                        false -> "☐"
                        null -> "•"
                    }
                    Text(marker, color = cs.onSurfaceVariant, style = typography.bodyLarge)
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
                is MdBlock.Table -> MarkdownTable(block, onOpenFile, linkify)
                is MdBlock.Image -> MarkdownImage(block)
            }
        }
    }
}

/**
 * GFM table as a bordered, horizontally-scrollable grid (iOS MarkdownTableView parity).
 * Laid out column-major: each column is a `Column(width = IntrinsicSize.Max)` so every cell in
 * it shares the widest cell's width, and single-line (no-wrap) cells mean wide tables scroll
 * instead of squishing. Cells keep inline formatting and per-column alignment.
 */
@Composable
fun MarkdownTable(table: MdBlock.Table, onOpenFile: (FilePathRef) -> Unit, linkify: Boolean) {
    val cs = MaterialTheme.colorScheme
    val cols = table.headers.size
    if (cols == 0) return
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(Radii.sm))
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(Radii.sm)),
    ) {
        for (c in 0 until cols) {
            if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(cs.outlineVariant))
            Column(Modifier.width(IntrinsicSize.Max)) {
                MarkdownTableCell(table.headers.getOrElse(c) { "" }, table.aligns.getOrElse(c) { ColumnAlign.LEFT }, header = true, onOpenFile, linkify)
                for (row in table.rows) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
                    MarkdownTableCell(row.getOrElse(c) { "" }, table.aligns.getOrElse(c) { ColumnAlign.LEFT }, header = false, onOpenFile, linkify)
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableCell(
    text: String,
    align: ColumnAlign,
    header: Boolean,
    onOpenFile: (FilePathRef) -> Unit,
    linkify: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val alignment = when (align) {
        ColumnAlign.LEFT -> Alignment.CenterStart
        ColumnAlign.CENTER -> Alignment.Center
        ColumnAlign.RIGHT -> Alignment.CenterEnd
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (header) cs.surfaceContainerLow else Color.Transparent)
            .padding(horizontal = Space.sm + Space.xs, vertical = Space.sm),
        contentAlignment = alignment,
    ) {
        Text(
            text = mdAnnotated(text, onOpenFile, linkify = linkify),
            color = cs.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Standalone markdown image `![alt](url)`. Only `https://` URLs are loaded (a message-content
 * image is a tracking-pixel / IP-leak vector); anything else renders as a tappable link line.
 */
@Composable
fun MarkdownImage(image: MdBlock.Image) {
    val cs = MaterialTheme.colorScheme
    if (image.url.startsWith("https://")) {
        AsyncImage(
            model = image.url,
            contentDescription = image.alt.ifEmpty { null },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(Radii.sm)),
            contentScale = ContentScale.Fit,
        )
    } else {
        val linkColor = cs.primary
        val uriHandler = LocalUriHandler.current
        val url = image.url
        Text(
            text = buildAnnotatedString {
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ) { runCatching { uriHandler.openUri(url) } },
                ) { append(image.alt.ifEmpty { url }) }
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Inbound (user) message — right-aligned chat bubble, capped at ~75% width.
 * Short text hugs content; long text wraps inside the cap. No gutter, no label.
 */
@Composable
fun UserMessage(text: String) {
    val cs = MaterialTheme.colorScheme
    // Tail sits on the bottom-end (right) so it reads as a sent bubble.
    val bubbleShape = RoundedCornerShape(
        topStart = Radii.md,
        topEnd = Radii.md,
        bottomStart = Radii.md,
        bottomEnd = 4.dp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        // Cap width at 75%; inner wrap keeps short messages snug.
        Box(
            modifier = Modifier.fillMaxWidth(0.75f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                Modifier
                    .wrapContentWidth()
                    .clip(bubbleShape)
                    .background(cs.surfaceContainer.copy(alpha = 0.92f))
                    .border(1.dp, cs.outlineVariant, bubbleShape)
                    .padding(horizontal = Space.md, vertical = Space.sm),
            ) {
                SelectionContainer {
                    Text(
                        text = mdAnnotated(text),
                        color = cs.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
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
 * Tool-use activity.
 * Medium: quiet inline row (no chip card). High: terminal pane for Bash, diff pane for Edit/Write.
 */
@Composable
fun ToolCard(
    event: ActivityEvent,
    status: ToolStatus,
    output: String? = null,
    resultBody: ActivityToolBody? = null,
    highDetail: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    val input = event.detail
    val toolName = event.tool ?: "tool"
    val bash = if (highDetail) resolveBashParts(event.body, resultBody, input, output, toolName) else null
    val edit = if (highDetail) resolveEditParts(event.body, input, toolName) else null

    if (bash != null) {
        ToolTerminalPane(
            command = bash.command,
            output = bash.output,
            exitCode = bash.exitCode,
            description = event.description,
            status = status,
            truncated = event.truncated == true,
        )
        return
    }
    if (edit != null) {
        ToolDiffPane(
            path = edit.path,
            mode = edit.mode,
            diff = edit.diff,
            content = edit.content,
            description = event.description,
            status = status,
            truncated = event.truncated == true,
        )
        return
    }

    val hasContent = !input.isNullOrBlank() || !output.isNullOrBlank()
    val verb = toolLabel(toolName)
    val primary = event.description
        ?: event.title?.let { t -> event.tool?.let { t.removePrefix("$it: ") } ?: t }

    Column(Modifier.fillMaxWidth().testTag("tool_card")) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hasContent) { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status == ToolStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    strokeWidth = 1.5.dp,
                )
            } else {
                Icon(
                    painter = painterResource(toolIcon(toolName)),
                    contentDescription = null,
                    tint = if (status == ToolStatus.ERROR) cs.error.copy(alpha = 0.7f)
                    else cs.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(12.dp),
                )
            }
            Spacer(Modifier.width(Space.xs + 2.dp))
            Text(
                text = verb,
                color = cs.onSurfaceVariant.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            if (!primary.isNullOrBlank()) {
                Text(
                    text = primary,
                    color = cs.onSurfaceVariant.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Space.xs),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (status == ToolStatus.ERROR) {
                Text(
                    text = "failed",
                    color = cs.error.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                Modifier
                    .padding(start = 16.dp, top = 2.dp, bottom = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.25f),
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(start = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                input?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                output?.takeIf { it.isNotBlank() }?.let {
                    if (looksLikeDiff(it)) InlineDiff(it)
                    else Text(
                        text = it + if (event.truncated == true) " …" else "",
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = if (status == ToolStatus.ERROR) cs.error.copy(alpha = 0.8f)
                        else cs.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolTerminalPane(
    command: String?,
    output: String?,
    exitCode: Int?,
    description: String?,
    status: ToolStatus,
    truncated: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val statusLabel = when {
        status == ToolStatus.RUNNING -> "running"
        status == ToolStatus.ERROR && exitCode != null -> "exit $exitCode"
        status == ToolStatus.ERROR -> "error"
        exitCode != null -> "exit $exitCode"
        else -> "done"
    }
    val statusColor = when (status) {
        ToolStatus.RUNNING -> cs.tertiary
        ToolStatus.ERROR -> cs.error
        ToolStatus.DONE -> cs.primary.copy(alpha = 0.85f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(Color(0xFF0C0C0E))
            .border(0.5.dp, cs.outline.copy(alpha = 0.35f), RoundedCornerShape(Radii.sm))
            .testTag("tool_terminal"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .padding(horizontal = Space.sm + 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("●", color = Color(0xFFFF5F57), fontSize = 8.sp)
            Text(" ●", color = Color(0xFFFEBC2E).copy(alpha = 0.85f), fontSize = 8.sp)
            Text(" ●", color = Color(0xFF28C840).copy(alpha = 0.85f), fontSize = 8.sp)
            Spacer(Modifier.width(Space.sm))
            Icon(
                painter = painterResource(R.drawable.ic_terminal),
                contentDescription = null,
                tint = Color(0xFFA1A1AA),
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("terminal", color = Color(0xFFA1A1AA), fontSize = 11.sp)
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = Color(0xFFD4D4D8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Space.xs),
                )
            } else Spacer(Modifier.weight(1f))
            if (status == ToolStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = statusColor,
                    strokeWidth = 1.2.dp,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(statusLabel, color = statusColor, fontSize = 10.sp, fontFamily = MonoFontFamily)
        }
        Column(
            Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.sm + 2.dp, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!command.isNullOrBlank()) {
                Row {
                    Text("$ ", color = Color(0xFF4ADE80).copy(alpha = 0.9f), fontFamily = MonoFontFamily, fontSize = 12.sp)
                    Text(command, color = Color(0xFFF4F4F5), fontFamily = MonoFontFamily, fontSize = 12.sp)
                }
            }
            if (!output.isNullOrBlank()) {
                Text(
                    text = output + if (truncated) " …" else "",
                    color = if (status == ToolStatus.ERROR) Color(0xFFFCA5A5) else Color(0xFFD4D4D8),
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            } else if (status == ToolStatus.RUNNING && command.isNullOrBlank()) {
                Text("Running…", color = Color(0xFF71717A), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ToolDiffPane(
    path: String,
    mode: String?,
    diff: String?,
    content: String?,
    description: String?,
    status: ToolStatus,
    truncated: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val rendered = diff ?: content?.lineSequence()?.joinToString("\n") { "+$it" }.orEmpty()
    val modeLabel = when (mode?.lowercase()) {
        "add", "added" -> "added"
        "delete", "deleted" -> "deleted"
        "move", "renamed" -> "moved"
        else -> "edited"
    }
    val statusLabel = when (status) {
        ToolStatus.RUNNING -> "applying"
        ToolStatus.ERROR -> "error"
        ToolStatus.DONE -> "done"
    }
    val statusColor = when (status) {
        ToolStatus.RUNNING -> cs.tertiary
        ToolStatus.ERROR -> cs.error
        ToolStatus.DONE -> cs.primary.copy(alpha = 0.85f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(Color(0xFF0C0C0E))
            .border(0.5.dp, cs.outline.copy(alpha = 0.35f), RoundedCornerShape(Radii.sm))
            .testTag("tool_diff"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .padding(horizontal = Space.sm + 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pencil),
                contentDescription = null,
                tint = Color(0xFFA1A1AA),
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = path,
                color = Color(0xFFF4F4F5),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = Color(0xFFA1A1AA),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Space.xs),
                )
            }
            Spacer(Modifier.width(Space.xs))
            Text(modeLabel, color = Color(0xFF71717A), fontSize = 10.sp)
            Spacer(Modifier.width(Space.xs))
            Text(statusLabel, color = statusColor, fontSize = 10.sp)
        }
        if (rendered.isNotBlank()) {
            InlineDiff(rendered + if (truncated) "\n… truncated" else "")
        } else {
            Text(
                text = if (status == ToolStatus.RUNNING) "Preparing edit…" else "No diff content",
                color = Color(0xFF71717A),
                fontSize = 11.sp,
                modifier = Modifier.padding(Space.sm),
            )
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
// Chat timeline layout — clean message stream (no gutter dots / spine).
// User: right bubble · Agent: full-width prose + time/copy meta · Tools: plain rows.
// ---------------------------------------------------------------------------

/** Dispatches a single TimelineItem into the chat stream. */
@Composable
fun TimelineItemRow(
    item: TimelineItem,
    loadBytes: suspend (String) -> ByteArray? = { null },
    onOpenFile: (FilePathRef) -> Unit = {},
    highDetail: Boolean = false,
) {
    when (item) {
        is TimelineItem.Msg -> {
            val text = item.entry.text
            val atts = item.entry.attachments
            val isUser = item.entry.direction == "inbound"
            if (!text.isNullOrBlank() || !atts.isNullOrEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Space.sm),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    if (!text.isNullOrBlank()) {
                        if (isUser) {
                            UserMessage(text)
                        } else {
                            AssistantMessage(text, ts = item.entry.ts, onOpenFile = onOpenFile)
                        }
                    }
                    if (!atts.isNullOrEmpty()) {
                        AttachmentList(atts, alignEnd = isUser, loadBytes = loadBytes)
                    }
                }
            }
        }
        is TimelineItem.Tool -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.xs),
            ) {
                ToolCard(
                    event = item.event,
                    status = item.status,
                    output = item.output,
                    resultBody = item.resultBody,
                    highDetail = highDetail,
                )
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
            var imageBytes by remember(att.file_id) { mutableStateOf<ByteArray?>(null) }
            var failed by remember(att.file_id) { mutableStateOf(false) }
            LaunchedEffect(att.file_id) {
                val bytes = loadBytes(att.file_id)
                imageBytes = bytes
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
                if (showLightbox) {
                    ImageLightbox(
                        image = b,
                        name = att.name ?: att.file_id,
                        mime = att.mime,
                        bytes = imageBytes,
                        onDismiss = { showLightbox = false },
                    )
                }
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(Radii.md)),
            ) {
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
                        .fillMaxSize()
                        .testTag("attachment_video_player"),
                )
                // Share/open the cached clip so the user can save it outside the app.
                val scope = rememberCoroutineScope()
                IconButton(
                    onClick = {
                        scope.launch {
                            val bytes = withContext(Dispatchers.IO) {
                                runCatching { f.readBytes() }.getOrNull()
                            }
                            if (bytes != null) openAttachment(context, att.name ?: "video", att.mime, bytes)
                            else Toast.makeText(context, "Couldn't download video", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Space.sm)
                        .testTag("attachment_video_download"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = "Download video",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * Fullscreen image lightbox (iOS Lightbox parity): black backdrop, fit-scaled image with
 * pinch-to-zoom + pan (clamped ≥1×), close + download (share/open) buttons. A Dialog with
 * usePlatformDefaultWidth=false is the idiomatic Android-native fullscreen overlay;
 * predictive-back dismisses it for free.
 */
@Composable
private fun ImageLightbox(
    image: ImageBitmap,
    name: String,
    mime: String?,
    bytes: ByteArray?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
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
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                if (bytes != null) {
                    IconButton(
                        onClick = { openAttachment(context, name, mime, bytes) },
                        modifier = Modifier.testTag("image_lightbox_download"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = "Download",
                            tint = Color.White,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
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
