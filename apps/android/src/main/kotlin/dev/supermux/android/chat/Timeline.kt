package dev.supermux.android.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.supermux.proto.Attachment
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.LogEntry
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
    data class Tool(val event: ActivityEvent, val status: ToolStatus) : TimelineItem
    data class Act(val event: ActivityEvent) : TimelineItem
}

/**
 * Merge messages and activity events, sorted ascending by ts (ISO-8601 → lexicographic).
 *
 * Tool-call activity is folded by `callId`: the broker emits a `tool` (phase=started)
 * event and later a separate `tool_result` (phase=completed|failed) event with the same
 * callId. We resolve a single status per call and render ONE [TimelineItem.Tool] row —
 * the result event is not shown on its own (otherwise completed tools look stuck running).
 * Other activity kinds (thinking…) pass through as [TimelineItem.Act].
 */
fun mergeTimeline(
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
): List<TimelineItem> {
    // callId -> resolved final status from `tool_result` events
    val resultStatus = HashMap<String, ToolStatus>()
    for (e in activity) {
        val id = e.callId
        if (e.kind == "tool_result" && id != null) {
            resultStatus[id] = if (e.phase == "failed") ToolStatus.ERROR else ToolStatus.DONE
        }
    }
    val items = ArrayList<TimelineItem>(messages.size + activity.size)
    messages.forEach { items.add(TimelineItem.Msg(it)) }
    for (e in activity) {
        when (e.kind) {
            "tool" -> {
                val status = e.callId?.let { resultStatus[it] } ?: ToolStatus.RUNNING
                items.add(TimelineItem.Tool(e, status))
            }
            "tool_result" -> { /* folded into the matching tool row above */ }
            else -> items.add(TimelineItem.Act(e))
        }
    }
    return items.sortedBy { item ->
        when (item) {
            is TimelineItem.Msg -> item.entry.ts
            is TimelineItem.Tool -> item.event.ts
            is TimelineItem.Act -> item.event.ts
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
fun mdAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    text.split("\n").forEachIndexed { i, line ->
        if (i > 0) append("\n")
        for (s in parseInlineMarkdown(line)) {
            when (s.kind) {
                SpanStyleKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.text) }
                SpanStyleKind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
                SpanStyleKind.CODE -> withStyle(
                    SpanStyle(
                        fontFamily = MonoFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    )
                ) { append(s.text) }
                SpanStyleKind.PLAIN -> append(s.text)
            }
        }
    }
}

/**
 * Elegant mono code block for fenced ``` content.
 * Horizontal accent line + subtle header-tinted background + horizontal scroll.
 */
@Composable
fun FencedCodeBlock(code: String) {
    val cs = MaterialTheme.colorScheme
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
                .padding(horizontal = Space.md, vertical = Space.sm),
        ) {
            Text(
                text = code,
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = cs.onSurface.copy(alpha = 0.9f),
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
fun AssistantMessage(text: String) {
    val cs = MaterialTheme.colorScheme
    val blocks = parseMarkdownBlocks(text)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Prose -> {
                    if (block.text.isNotBlank()) {
                        Text(
                            text = mdAnnotated(block.text),
                            color = cs.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is MdBlock.Code -> FencedCodeBlock(block.code)
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
    val bubbleShape = RoundedCornerShape(
        topStart = Radii.lg,
        topEnd = Radii.lg,
        bottomStart = Radii.lg,
        bottomEnd = 4.dp,   // tightened trailing corner
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .fillMaxWidth(0.84f)
                .wrapContentWidth(Alignment.End)
                .clip(bubbleShape)
                .background(cs.surfaceContainer.copy(alpha = 0.85f))
                .border(1.dp, cs.outline, bubbleShape)
                .padding(horizontal = Space.md, vertical = Space.sm + Space.xs),
        ) {
            Text(
                text = mdAnnotated(text),
                color = cs.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Calm Premium — tool-use activity.
 * Quiet left-rail: thin 2dp vertical accent + tool name + mono summary ellipsis
 * + trailing status indicator. Tappable to expand detail block (collapsed by default).
 */
@Composable
fun ToolCard(event: ActivityEvent, status: ToolStatus) {
    val cs = MaterialTheme.colorScheme
    val isRunning = status == ToolStatus.RUNNING
    val isError = status == ToolStatus.ERROR
    val accentColor = if (isError) cs.error else cs.primary
    val accentAlpha = if (isRunning) 1f else 0.4f
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { if (event.detail != null) expanded = !expanded }
            .padding(vertical = Space.xs),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 2dp vertical accent rail
            Box(
                Modifier
                    .width(2.dp)
                    .height(Space.xl)
                    .clip(RoundedCornerShape(1.dp))
                    .background(accentColor.copy(alpha = accentAlpha)),
            )
            Spacer(Modifier.width(Space.sm))

            // Tool name (labelLarge)
            val toolName = event.tool ?: "tool"
            Text(
                text = toolName,
                color = cs.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )

            Spacer(Modifier.width(Space.sm))

            // Mono summary — ellipsis, flex
            val titleText = event.title
            if (titleText != null) {
                Text(
                    text = titleText,
                    color = cs.onSurfaceVariant,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.width(Space.sm))

            // Trailing status: spinner (running) · faint check (done) · red ✕ (error)
            when (status) {
                ToolStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = cs.primary,
                    strokeWidth = 1.5.dp,
                )
                ToolStatus.DONE -> Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
                ToolStatus.ERROR -> Icon(
                    painter = painterResource(R.drawable.ic_x),
                    contentDescription = null,
                    tint = cs.error,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Expandable detail block
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val detail = event.detail
            if (detail != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Space.md, top = Space.xs),
                ) {
                    FencedCodeBlock(detail)
                }
            }
        }
    }
}

/**
 * Calm Premium — reasoning/thinking activity.
 * Collapsed by default: a faint "✦ Thought for Ns" row (labelMedium, italic,
 * mutedForeground @70%) with a tiny chevron. Tapping expands detail if present.
 * Feels like a faint timestamp, not a card.
 */
@Composable
fun ReasoningLine(event: ActivityEvent) {
    val cs = MaterialTheme.colorScheme
    val hasDetail = !event.detail.isNullOrBlank()
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDetail) { expanded = !expanded }
            .padding(vertical = Space.xs),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "✦ ${event.title ?: "Thought"}",
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
                fontStyle = FontStyle.Italic,
            )
            if (hasDetail) {
                Spacer(Modifier.width(Space.xs))
                Text(
                    text = if (expanded) "∧" else "∨",
                    color = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val detail = event.detail
            if (detail != null) {
                Box(Modifier.padding(top = Space.xs)) {
                    Text(
                        text = detail,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
        }
    }
}

/** Dispatches a single TimelineItem to the correct composable. */
@Composable
fun TimelineItemRow(item: TimelineItem, loadBytes: suspend (String) -> ByteArray? = { null }) {
    when (item) {
        is TimelineItem.Msg -> {
            // Local vals avoid cross-module smart-cast restriction on nullable fields
            val text = item.entry.text
            val atts = item.entry.attachments
            val isUser = item.entry.direction == "inbound"
            if (!text.isNullOrBlank() || !atts.isNullOrEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    if (!text.isNullOrBlank()) {
                        if (isUser) UserMessage(text) else AssistantMessage(text)
                    }
                    if (!atts.isNullOrEmpty()) AttachmentList(atts, isUser, loadBytes)
                }
            }
        }
        is TimelineItem.Tool -> ToolCard(item.event, item.status)
        is TimelineItem.Act -> {
            when (item.event.kind) {
                "thinking" -> ReasoningLine(item.event)
                // other kinds: no-op
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
    val isVideo = att.kind == "video" || mime.startsWith("video/")
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
                Image(
                    bitmap = b,
                    contentDescription = att.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(Radii.md)),
                )
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
        isVideo -> AttachmentChip(R.drawable.ic_play, att.name ?: "video", att, loadBytes)
        isAudio -> AttachmentChip(R.drawable.ic_volume_2, att.name ?: "voice message", att, loadBytes)
        else -> AttachmentChip(R.drawable.ic_file, att.name ?: att.file_id, att, loadBytes)
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
            fontSize = 12.sp,
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
