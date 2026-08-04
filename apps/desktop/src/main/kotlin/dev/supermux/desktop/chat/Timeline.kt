// Ported from apps/android/src/main/kotlin/dev/supermux/android/chat/Timeline.kt — keep in sync
// until a shared UI module exists (spec 2026-07-09, Decision 1). The redesigned mono-gutter chat
// stream: mergeTimeline + the TimelineItem/StreamRow/ToolCard/UserMessage/AssistantMessage +
// FencedCodeBlock/MarkdownBody renderers, plus the inline-diff view.
//
// Desktop adaptations vs the Android source:
//  - Icons: Android renders bundled vector drawables (R.drawable.ic_check etc.); desktop has no
//    bundled icon set, so this uses the equivalent glyphs from compose.materialIconsExtended.
//  - Links: Android's LinkAnnotation.Url leans on the Android intent launcher; desktop opens the
//    system browser via java.awt.Desktop.browse (guarded by isDesktopSupported + runCatching).
//  - Attachments: chip with kind glyph + name + download; tap fetches via loadBytes, Save-as dialog,
//    then opens with the OS handler. Inline image/video preview is still Android-only for now.
//  - Text selection: Android gained long-press copyable messages; desktop's equivalent is mouse
//    selection via SelectionContainer, mirrored at the same wrap points.
//  - collectAsStateWithLifecycle → n/a (these are stateless renderers; no lifecycle collection).
package dev.supermux.desktop.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.LocalSemantics
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.openInBrowser
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.ui.resolveBashParts
import dev.supermux.ui.resolveEditParts
import dev.supermux.ui.resolveEditParts
import dev.supermux.proto.Attachment
import dev.supermux.proto.LogEntry
import dev.supermux.ui.ColumnAlign
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.MdBlock
import dev.supermux.ui.SpanStyleKind
import dev.supermux.ui.parseInlineMarkdown
import dev.supermux.ui.parseMarkdownBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jetbrains.skia.Image as SkiaImage

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
 */
fun mergeTimeline(
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    /** When true (chat detail = low), omit tool cards; activity is still ingested by the caller. */
    hideTools: Boolean = false,
): List<TimelineItem> {
    // callId -> resolved final status + output detail from `tool_result` events
    val resultStatus = HashMap<String, ToolStatus>()
    val resultDetail = HashMap<String, String?>()
    val resultBodies = HashMap<String, ActivityToolBody?>()
    for (e in activity) {
        val id = e.callId
        if (e.kind == "tool_result" && id != null) {
            resultStatus[id] = if (e.title == "error" || e.phase == "failed") ToolStatus.ERROR else ToolStatus.DONE
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
                // "thinking" (and any other non-tool kind) is intentionally dropped — thinking
                // shows as a live indicator, not a persistent "Thought for Ns" history row.
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

// ---------------------------------------------------------------------------
// Markdown helpers
// ---------------------------------------------------------------------------

/**
 * Convert a markdown string to an [AnnotatedString] with inline bold/italic/code spans.
 *
 * Inline `code` spans get MonoFontFamily + subtle background tint via SpanStyle.
 */
@Composable
fun mdAnnotated(
    text: String,
    onOpenFile: (FilePathRef) -> Unit = {},
    linkify: Boolean = false,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        text.split("\n").forEachIndexed { i, line ->
            if (i > 0) append("\n")
            for (s in parseInlineMarkdown(line)) {
                when (s.kind) {
                    SpanStyleKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        appendLinkified(s.text, linkStyles)
                    }
                    SpanStyleKind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendLinkified(s.text, linkStyles)
                    }
                    SpanStyleKind.CODE -> withStyle(
                        SpanStyle(
                            fontFamily = MonoFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    ) { append(s.text) } // never linkify inside inline code
                    SpanStyleKind.STRIKE -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendLinkified(s.text, linkStyles)
                    }
                    SpanStyleKind.LINK -> {
                        val url = s.url
                        val ref = s.ref
                        when {
                            // Web links from `[label](url)` are always clickable — open the system browser.
                            url != null -> withLink(
                                LinkAnnotation.Url(url, linkStyles) { openInBrowser(url) },
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
                    SpanStyleKind.PLAIN -> appendLinkified(s.text, linkStyles)
                }
            }
        }
    }
}

private val urlRegex = Regex("""https?://[^\s<>"'\])]+""")

/** Append [text], turning bare http(s) URLs into clickable, underlined links (opens the browser). */
private fun AnnotatedString.Builder.appendLinkified(text: String, linkStyles: TextLinkStyles) {
    var last = 0
    for (m in urlRegex.findAll(text)) {
        var url = m.value
        var trail = ""
        // A URL at the end of a sentence often swallows trailing punctuation — hand it back as plain text.
        while (url.isNotEmpty() && url.last() in ".,);:!?") { trail = url.last() + trail; url = url.dropLast(1) }
        if (url.isEmpty()) continue
        if (m.range.first > last) append(text.substring(last, m.range.first))
        withLink(
            LinkAnnotation.Url(url, linkStyles) { openInBrowser(url) },
        ) { append(url) }
        if (trail.isNotEmpty()) append(trail)
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

/**
 * Elegant mono code block for fenced ``` content.
 * Left accent + subtle header-tinted background + horizontal scroll.
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
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
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
 * Prose: bodyLarge, full width, no box or border. Code: FencedCodeBlock (mono, scroll, accent).
 * Footer: copy + native read-aloud (Android/web parity).
 */
@Composable
fun AssistantMessage(text: String, onOpenFile: (FilePathRef) -> Unit = {}, ts: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        // SelectionContainer makes the prose mouse-selectable/copyable; links inside stay clickable.
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

private fun formatMessageTime(ts: String?): String? {
    if (ts.isNullOrBlank()) return null
    val instant = ts.toLongOrNull()?.let { n ->
        Instant.ofEpochMilli(if (n < 1_000_000_000_000L) n * 1000L else n)
    } ?: runCatching { Instant.parse(ts) }.getOrNull()
    return instant?.atZone(ZoneId.systemDefault())?.format(messageTimeFmt)
}

/** Compact time + copy + read-aloud under an agent reply. */
@Composable
private fun MessageMetaRow(text: String, ts: String?) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val speechKey = remember(text) { plainTextForSpeech(text) }
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
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy response",
                tint = if (copied) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp),
            )
        }
        IconButton(
            onClick = { if (speechKey.isNotBlank()) MessageTts.toggle(text) },
            modifier = Modifier
                .size(28.dp)
                .testTag("message_read_aloud"),
        ) {
            Icon(
                imageVector = if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (speaking) "Stop reading" else "Read aloud",
                tint = if (speaking) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Reusable block-markdown renderer (prose + fenced code), the single source for chat and the
 * PWA-parity rendering. Splits via the shared [parseMarkdownBlocks]; prose uses inline
 * bold/italic/code spans, code uses [FencedCodeBlock]. Keep all markdown surfaces routed through
 * this so they never drift.
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

/** ColumnAlign → Compose [TextAlign] (pure; unit-tested). */
internal fun columnTextAlign(align: ColumnAlign): TextAlign = when (align) {
    ColumnAlign.LEFT -> TextAlign.Left
    ColumnAlign.CENTER -> TextAlign.Center
    ColumnAlign.RIGHT -> TextAlign.Right
}

/**
 * GFM table as a bordered, horizontally-scrollable grid (Android MarkdownTable / iOS
 * MarkdownTableView parity). Laid out column-major: each column is a `Column(width =
 * IntrinsicSize.Max)` so every cell shares the widest cell's width, and single-line (no-wrap)
 * cells mean wide tables scroll instead of squishing. Cells keep inline formatting and per-column
 * alignment.
 */
@Composable
fun MarkdownTable(table: MdBlock.Table, onOpenFile: (FilePathRef) -> Unit, linkify: Boolean) {
    val cs = MaterialTheme.colorScheme
    val cols = table.headers.size
    if (cols == 0) return
    Row(
        Modifier
            .testTag("md_table")
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
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (header) cs.surfaceContainerLow else Color.Transparent)
            .padding(horizontal = Space.sm + Space.xs, vertical = Space.sm),
    ) {
        Text(
            text = mdAnnotated(text, onOpenFile, linkify = linkify),
            color = cs.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = columnTextAlign(align),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Max download size for inline markdown images (bytes). Keeps a malicious/huge asset from
 * ballooning RAM; anything over this falls back to the tappable link line.
 */
internal const val MD_IMAGE_MAX_BYTES: Long = 8L * 1024L * 1024L

/** Only `https://` image URLs are fetched (message-content images are a tracking / IP-leak vector). */
internal fun isHttpsImageUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true)

/**
 * Read up to [maxBytes] from [stream]. Returns null if the stream would exceed the cap (size-
 * capped so a huge body never lands fully in memory). Pure relative to the stream — unit-testable
 * without the network.
 */
internal fun readBytesCapped(stream: InputStream, maxBytes: Long = MD_IMAGE_MAX_BYTES): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * GET [url] and return the response body when it is https, 2xx, and within [maxBytes]. Follows
 * redirects only while the next hop stays https. Null on any failure — callers fall back to the
 * link line. Blocking; call from [Dispatchers.IO], never the UI thread.
 */
internal fun fetchHttpsImageBytes(url: String, maxBytes: Long = MD_IMAGE_MAX_BYTES): ByteArray? {
    if (!isHttpsImageUrl(url)) return null
    return runCatching {
        var current = url
        // Cap redirect hops so a loop cannot hang the fetch.
        repeat(5) {
            if (!isHttpsImageUrl(current)) return null
            val conn = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
            }
            conn.connect()
            val code = conn.responseCode
            when (code) {
                in 200..299 -> {
                    val declared = conn.contentLengthLong
                    if (declared > maxBytes) {
                        conn.disconnect()
                        return null
                    }
                    return conn.inputStream.use { readBytesCapped(it, maxBytes) }.also { conn.disconnect() }
                }
                in 300..399 -> {
                    val next = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (next.isNullOrBlank()) return null
                    current = if (next.startsWith("https://", ignoreCase = true) ||
                        next.startsWith("http://", ignoreCase = true)
                    ) {
                        next
                    } else {
                        // Relative redirect — resolve against the current URL.
                        URI(current).resolve(next).toString()
                    }
                }
                else -> {
                    conn.disconnect()
                    return null
                }
            }
        }
        null
    }.getOrNull()
}

/**
 * Decode raw image bytes (PNG/JPEG/GIF/WebP/…) to a Compose [ImageBitmap] via Skiko's
 * [SkiaImage.makeFromEncoded] + [toComposeImageBitmap] — no Coil, no extra dependency. Null on
 * corrupt/unsupported payloads. (`createImageBitmap` is internal to Compose, so we call the
 * underlying Skiko path that it wraps.)
 */
internal fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/**
 * Load + decode an https image off the caller's thread (typically [Dispatchers.IO]). Returns null
 * for non-https, oversize, network, or decode failures.
 */
internal suspend fun loadMarkdownImageBitmap(
    url: String,
    maxBytes: Long = MD_IMAGE_MAX_BYTES,
): ImageBitmap? = withContext(Dispatchers.IO) {
    val bytes = fetchHttpsImageBytes(url, maxBytes) ?: return@withContext null
    decodeImageBytes(bytes)
}

/**
 * Standalone markdown image `![alt](url)`.
 *
 * - `https://` URLs: fetch (size-capped) + Skiko-decode off the UI thread, then paint inline.
 * - Everything else (http, relative, data:): compact tappable link line that opens the system
 *   browser — Android's Coil path is also https-only for the same tracking/IP-leak reason.
 * - Load/decode failure falls back to the same link line (never a blank hole).
 *
 * [loadImage] is the load seam (default = real network fetch); tests inject a fake so the UI path
 * is covered without the network.
 */
@Composable
fun MarkdownImage(
    image: MdBlock.Image,
    loadImage: suspend (String) -> ImageBitmap? = { loadMarkdownImageBitmap(it) },
) {
    val cs = MaterialTheme.colorScheme
    if (!isHttpsImageUrl(image.url)) {
        MarkdownImageLinkLine(image)
        return
    }
    var bitmap by remember(image.url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(image.url) { mutableStateOf(false) }
    LaunchedEffect(image.url) {
        // loadImage itself must not block the main dispatcher — the default seam hops to IO.
        val decoded = runCatching { loadImage(image.url) }.getOrNull()
        if (decoded != null) bitmap = decoded else failed = true
    }
    when {
        bitmap != null -> {
            Image(
                bitmap = bitmap!!,
                contentDescription = image.alt.ifEmpty { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(Radii.sm))
                    .testTag("md_image"),
            )
        }
        failed -> MarkdownImageLinkLine(image)
        else -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(Radii.sm))
                .background(cs.surfaceContainer)
                .testTag("md_image_loading"),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                color = cs.onSurfaceVariant,
                strokeWidth = 1.5.dp,
            )
        }
    }
}

/** Tappable 🖼 + alt/url link line — fallback for non-https and failed loads. */
@Composable
private fun MarkdownImageLinkLine(image: MdBlock.Image) {
    val cs = MaterialTheme.colorScheme
    val linkColor = cs.primary
    Text(
        text = buildAnnotatedString {
            append("🖼 ")
            withLink(
                LinkAnnotation.Url(
                    image.url,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ) { openInBrowser(image.url) },
            ) { append(image.alt.ifEmpty { image.url }) }
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("md_image"),
    )
}

/**
 * Calm Premium — inbound (user) message.
 * Left-aligned bubble with a mono "you" label and a tightened leading corner so it reads as the
 * human's turn on the thread. Card background @90%, 1px border, bodyMedium text.
 */
@Composable
fun UserMessage(text: String) {
    val cs = MaterialTheme.colorScheme
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

/** Strip an mcp__server__tool name down to its last segment (iOS label parity). */
private fun toolLabel(tool: String): String =
    if (tool.startsWith("mcp__")) tool.substringAfterLast("__") else tool

/**
 * Tool-use activity.
 * Medium: quiet inline row. High: terminal pane for Bash, diff pane for Edit/Write.
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
                Text(
                    text = "·",
                    color = if (status == ToolStatus.ERROR) cs.error.copy(alpha = 0.7f)
                    else cs.onSurfaceVariant.copy(alpha = 0.55f),
                    fontSize = 14.sp,
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
    val sem = LocalSemantics.current
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
 * one continuous thread. drawBehind (not IntrinsicSize) keeps it safe with scroll content.
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
    highDetail: Boolean = false,
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
                            if (isUser) UserMessage(text) else AssistantMessage(text, onOpenFile, ts = item.entry.ts)
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
                ToolCard(item.event, item.status, item.output, item.resultBody, highDetail = highDetail)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------
// Desktop keeps a compact chip (name + kind glyph + download) for every attachment kind. Tap
// fetches bytes via loadBytes, offers a Save dialog, writes the file, then opens it with the OS
// handler. Inline image/video preview remains a future enhancement (Android has it already).

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
    val mime = att.mime ?: ""
    val isImage = att.kind == "photo" || att.kind == "image" || mime.startsWith("image/")
    val isVideo = att.kind == "video" || att.kind == "video_note" || mime.startsWith("video/")
    val isAudio = att.kind == "voice" || att.kind == "audio" || mime.startsWith("audio/")
    val label = when {
        isAudio -> att.name ?: "voice message"
        isVideo -> att.name ?: "video"
        isImage -> att.name ?: "image"
        else -> att.name ?: att.file_id
    }
    val icon = when {
        isAudio -> Icons.AutoMirrored.Filled.VolumeUp
        isVideo -> Icons.Filled.Movie
        isImage -> Icons.Filled.Image
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    AttachmentChip(icon, label, att, loadBytes)
}

/** Compact chip: kind glyph + name + download. Tap downloads and Save-as via AWT FileDialog. */
@Composable
private fun AttachmentChip(
    icon: ImageVector,
    label: String,
    att: Attachment,
    loadBytes: suspend (String) -> ByteArray?,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.sm)
    val scope = rememberCoroutineScope()
    var busy by remember(att.file_id) { mutableStateOf(false) }
    var failed by remember(att.file_id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline, shape)
            .clickable(enabled = !busy) {
                busy = true
                failed = false
                scope.launch {
                    val bytes = loadBytes(att.file_id)
                    if (bytes == null) {
                        busy = false
                        failed = true
                        return@launch
                    }
                    val saved = withContext(Dispatchers.IO) {
                        saveAttachmentBytes(bytes, att.name ?: att.file_id)
                    }
                    busy = false
                    failed = saved == null
                    if (saved != null) openLocalFile(saved)
                }
            }
            .padding(horizontal = Space.sm, vertical = Space.xs)
            .testTag("attachment_chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(16.dp),
                color = cs.onSurfaceVariant,
                strokeWidth = 1.5.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = if (failed) "Download failed — tap to retry" else label,
            color = if (failed) cs.error else cs.onSurface,
            fontSize = 13.sp,
            fontFamily = MonoFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = "Download",
            tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Save dialog (AWT FileDialog.SAVE). Returns the written file, or null if cancelled/failed.
 *  Must run off the Compose frame path's hot path; FileDialog itself is modal on the AWT EDT
 *  (Compose Desktop main == EDT on most hosts). */
internal fun saveAttachmentBytes(bytes: ByteArray, name: String): File? {
    val safeName = name.substringAfterLast('/').ifBlank { "file" }
    val dialog = FileDialog(null as Frame?, "Save attachment", FileDialog.SAVE)
    dialog.file = safeName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val fileName = dialog.file ?: return null
    return runCatching {
        File(dir, fileName).also { it.writeBytes(bytes) }
    }.getOrNull()
}

/** Open a local file with the OS default handler (viewer / player / folder). */
internal fun openLocalFile(file: File) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
            return
        }
        val os = System.getProperty("os.name")?.lowercase(Locale.US).orEmpty()
        val cmd = when {
            os.contains("win") -> arrayOf("cmd", "/c", "start", "", file.absolutePath)
            os.contains("mac") || os.contains("darwin") -> arrayOf("open", file.absolutePath)
            else -> arrayOf("xdg-open", file.absolutePath)
        }
        ProcessBuilder(*cmd).inheritIO().start()
    }
}
