// Desktop chat stream renderers — aligned with Android's simple message list (no gutter/spine).
// Pure fold lives in shared [dev.supermux.chat.mergeTimeline]; UI stays platform-specific for
// icons, clipboard, TTS, attachments, and image load until a shared Compose UI module lands.
//
// Desktop adaptations vs the Android source:
//  - Icons: materialIconsExtended instead of R.drawable.*
//  - Links: openInBrowser / java.awt.Desktop (not Android intents)
//  - Attachments: save-as + OS open (no inline video yet)
//  - Selection: SelectionContainer for mouse copy
package dev.supermux.desktop.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
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
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.desktop.theme.LocalSemantics
import dev.supermux.desktop.theme.Media
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Sizes
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.openInBrowser
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.proto.Attachment
import dev.supermux.proto.LogEntry
import dev.supermux.ui.ColumnAlign
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.MdBlock
import dev.supermux.ui.SpanStyleKind
import dev.supermux.ui.parseInlineMarkdown
import dev.supermux.ui.parseMarkdownBlocks
import dev.supermux.ui.resolveBashParts
import dev.supermux.ui.resolveEditParts
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
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

// Shared fold — package re-export so `import dev.supermux.desktop.chat.mergeTimeline` still works.
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
                            fontSize = 12.sp,
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
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
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
fun AssistantMessage(
    text: String,
    onOpenFile: (FilePathRef) -> Unit = {},
    ts: String? = null,
    onOpenUrl: (String) -> Unit = ::openInBrowser,
    loadImage: suspend (String) -> ImageBitmap? = { loadMarkdownImageBitmap(it) },
) {
    Column(Modifier.fillMaxWidth()) {
        // SelectionContainer makes the prose mouse-selectable/copyable; links inside stay clickable.
        SelectionContainer {
            MarkdownBody(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                onOpenFile = onOpenFile,
                linkify = true,
                onOpenUrl = onOpenUrl,
                loadImage = loadImage,
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
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    onOpenFile: (FilePathRef) -> Unit = {},
    linkify: Boolean = false,
    /**
     * Open-URL seam for inline images and (via callers) click tests. Defaults to the system browser;
     * tests inject a recorder so clicks never launch Chrome (which would hang the Gradle worker).
     * Threaded into [MarkdownImage] so a future click test at the [MarkdownBody] level stays safe.
     */
    onOpenUrl: (String) -> Unit = ::openInBrowser,
    /** Load seam for https images — default production fetch; tests/SM_MD_IMAGE inject fakes. */
    loadImage: suspend (String) -> ImageBitmap? = { loadMarkdownImageBitmap(it) },
) {
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
                is MdBlock.Image -> MarkdownImage(block, loadImage = loadImage, onOpenUrl = onOpenUrl)
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

/** Default redirect hop budget for [fetchHttpsImageBytes] — loops must not hang the loader. */
internal const val MD_IMAGE_MAX_REDIRECTS: Int = 5

/**
 * Resolve a redirect [Location] against [currentUrl]. Absolute http(s) locations are used as-is;
 * relative locations are resolved with [URI.resolve]. Pure — unit-testable without the network.
 */
internal fun resolveImageRedirectUrl(currentUrl: String, location: String): String {
    val next = location.trim()
    if (next.startsWith("https://", ignoreCase = true) ||
        next.startsWith("http://", ignoreCase = true)
    ) {
        return next
    }
    return URI(currentUrl).resolve(next).toString()
}

/** Open a GET connection for image fetch. Extracted so tests can inject a local server opener. */
internal fun openImageHttpConnection(url: String): HttpURLConnection =
    (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 10_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("Accept", "image/*,*/*;q=0.8")
    }

/**
 * GET [url] and return the response body when every hop is allowed by [isAllowedUrl], the status is
 * 2xx, and the body is within [maxBytes]. Follows up to [maxRedirects] hops. Null on any failure —
 * callers fall back to the link line. Blocking; call from [Dispatchers.IO], never the UI thread.
 *
 * [isAllowedUrl] / [openConnection] are seams for the production network-matrix tests (local
 * HttpServer, redirect/downgrade/hop/oversize/404). Production uses [fetchHttpsImageBytes].
 */
internal fun fetchImageBytesWithPolicy(
    url: String,
    maxBytes: Long = MD_IMAGE_MAX_BYTES,
    maxRedirects: Int = MD_IMAGE_MAX_REDIRECTS,
    isAllowedUrl: (String) -> Boolean = ::isHttpsImageUrl,
    openConnection: (String) -> HttpURLConnection = ::openImageHttpConnection,
): ByteArray? {
    if (!isAllowedUrl(url)) return null
    return runCatching {
        var current = url
        repeat(maxRedirects) {
            if (!isAllowedUrl(current)) return null
            val conn = openConnection(current)
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
                    current = resolveImageRedirectUrl(current, next)
                    // Reject immediately on scheme downgrade / disallowed hop so we never open it.
                    if (!isAllowedUrl(current)) return null
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
 * GET [url] and return the response body when it is https, 2xx, and within [maxBytes]. Follows
 * redirects only while the next hop stays https. Null on any failure — callers fall back to the
 * link line. Blocking; call from [Dispatchers.IO], never the UI thread.
 */
internal fun fetchHttpsImageBytes(url: String, maxBytes: Long = MD_IMAGE_MAX_BYTES): ByteArray? =
    fetchImageBytesWithPolicy(
        url = url,
        maxBytes = maxBytes,
        maxRedirects = MD_IMAGE_MAX_REDIRECTS,
        isAllowedUrl = ::isHttpsImageUrl,
        openConnection = ::openImageHttpConnection,
    )

/**
 * Layout tokens for inline markdown images — aliases of theme [Media]/[Sizes] so chat call sites
 * stay readable without reintroducing magic `N.dp` values.
 */
internal object MdImageDimens {
    /** Max painted height for a successfully loaded image (shrink-only bound). */
    val MaxHeight = Media.inlineImageMaxHeight
    /**
     * Loading placeholder height when aspect ratio is not yet known — equals [MaxHeight] so the
     * timeline never grows upward when a tall bitmap arrives.
     */
    val LoadingHeight = MaxHeight
    val SpinnerSize = Sizes.iconSm
    val SpinnerStroke = Sizes.hairline
}

/**
 * Paint size for an inline image: natural pixel size in [density], only **shrunk** to fit within
 * [maxWidth] × [maxHeight] — never upscaled (desktop/browser convention).
 */
internal fun mdImagePaintSize(
    pixelWidth: Int,
    pixelHeight: Int,
    maxWidth: Dp,
    maxHeight: Dp,
    density: androidx.compose.ui.unit.Density,
): Pair<Dp, Dp> {
    if (pixelWidth <= 0 || pixelHeight <= 0) return maxWidth to maxHeight
    val iw = with(density) { pixelWidth.toDp() }
    val ih = with(density) { pixelHeight.toDp() }
    val scale = minOf(1f, maxWidth / iw, maxHeight / ih)
    return (iw * scale) to (ih * scale)
}

/**
 * Decode raw image bytes (PNG/JPEG/GIF/WebP/…) to a **rasterised** Compose [ImageBitmap].
 *
 * Uses Skiko [Codec.readPixels] so pixel decode happens **here** (inside the caller's
 * [runCatching] / IO dispatcher), not lazily at Compose draw time. [org.jetbrains.skia.Image.makeFromEncoded]
 * alone is lazy; handing that to Compose would let truncated/corrupt payloads throw outside
 * our catch and bypass the link fallback. Null on corrupt/unsupported/truncated payloads.
 */
internal fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching {
        val data = Data.makeFromBytes(bytes)
        val codec = Codec.makeFromData(data)
        // Fully rasterise on this thread — failures surface here, not at draw time.
        val raster = codec.readPixels()
        if (raster.width <= 0 || raster.height <= 0 || raster.isNull) return@runCatching null
        raster.setImmutable()
        // SkiaBackedImageBitmap retains [raster]; do not close it. Codec/Data can be closed.
        codec.close()
        data.close()
        raster.asComposeImageBitmap()
    }.getOrNull()

/**
 * Load + fully decode an https image on [Dispatchers.IO]. Returns null for non-https, oversize,
 * network, or decode failures. The returned [ImageBitmap] is already rasterised — safe to paint
 * on the UI thread without further decode work.
 *
 * This is the **production** load path used by [MarkdownImage]'s default [loadImage] seam —
 * tests that care about the dispatcher hop must call this (or the default seam), not reimplement
 * `withContext(IO)` themselves.
 */
internal suspend fun loadMarkdownImageBitmap(
    url: String,
    maxBytes: Long = MD_IMAGE_MAX_BYTES,
    fetchBytes: (String, Long) -> ByteArray? = { u, max -> fetchHttpsImageBytes(u, max) },
): ImageBitmap? = withContext(Dispatchers.IO) {
    val bytes = fetchBytes(url, maxBytes) ?: return@withContext null
    decodeImageBytes(bytes)
}

/**
 * Standalone markdown image `![alt](url)`.
 *
 * - `https://` URLs: fetch (size-capped) + force-decode off the UI thread, then paint inline.
 * - Everything else (http, relative, data:): compact tappable link line that opens the system
 *   browser — Android's Coil path is also https-only for the same tracking/IP-leak reason.
 * - Load/decode failure falls back to a **distinct** failure link line (never a blank hole, never
 *   identical to the deliberate non-https fallback).
 * - Successful images are clickable (open URL via [onOpenUrl]) so desktop users can inspect the
 *   full-resolution original. [onOpenUrl] defaults to the system browser; tests inject a recorder
 *   so a click never launches Chrome (which would hang the Gradle test worker).
 *
 * [loadImage] is the load seam (default = real network fetch); tests inject a fake so the UI path
 * is covered without the network.
 */
@Composable
fun MarkdownImage(
    image: MdBlock.Image,
    loadImage: suspend (String) -> ImageBitmap? = { loadMarkdownImageBitmap(it) },
    onOpenUrl: (String) -> Unit = ::openInBrowser,
) {
    val cs = MaterialTheme.colorScheme
    if (!isHttpsImageUrl(image.url)) {
        MarkdownImageLinkLine(image, loadFailed = false, onOpenUrl = onOpenUrl)
        return
    }
    var bitmap by remember(image.url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(image.url) { mutableStateOf(false) }
    LaunchedEffect(image.url) {
        // loadImage itself must not block the main dispatcher — the default seam hops to IO and
        // force-rasterises so draw-time decode cannot throw past our catch.
        val decoded = runCatching { loadImage(image.url) }.getOrNull()
        if (decoded != null) bitmap = decoded else failed = true
    }
    when {
        bitmap != null -> {
            val bmp = bitmap!!
            // Natural size, shrink-only past max width/height — never upscale a small icon to the
            // column width (fillMaxWidth + ContentScale.Fit would paint 32×32 as a 280×280 blob).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val (w, h) = mdImagePaintSize(
                    pixelWidth = bmp.width,
                    pixelHeight = bmp.height,
                    maxWidth = maxWidth,
                    maxHeight = MdImageDimens.MaxHeight,
                    density = density,
                )
                Image(
                    bitmap = bmp,
                    contentDescription = image.alt.ifEmpty { "Image" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(w)
                        .height(h)
                        .clip(RoundedCornerShape(Radii.sm))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onOpenUrl(image.url) }
                        .testTag("md_image"),
                )
            }
        }
        failed -> MarkdownImageLinkLine(image, loadFailed = true, onOpenUrl = onOpenUrl)
        else -> {
            // Reserve MaxHeight until dimensions are known (bounds upward reflow). Once the bitmap
            // arrives, paint size uses its aspect ratio via [mdImagePaintSize].
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MdImageDimens.LoadingHeight)
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(cs.surfaceContainer)
                    .testTag("md_image_loading"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.size(MdImageDimens.SpinnerSize),
                    color = cs.onSurfaceVariant,
                    strokeWidth = MdImageDimens.SpinnerStroke,
                )
            }
        }
    }
}

/**
 * Tappable 🖼 + label link line. [loadFailed] distinguishes a deliberate non-https skip from a
 * real fetch/decode failure so the user can tell them apart.
 */
@Composable
private fun MarkdownImageLinkLine(
    image: MdBlock.Image,
    loadFailed: Boolean,
    onOpenUrl: (String) -> Unit = ::openInBrowser,
) {
    val cs = MaterialTheme.colorScheme
    val linkColor = cs.primary
    val label = when {
        loadFailed && image.alt.isNotEmpty() -> "Couldn't load image — ${image.alt}"
        loadFailed -> "Couldn't load image"
        else -> image.alt.ifEmpty { image.url }
    }
    Text(
        text = buildAnnotatedString {
            append("🖼 ")
            withLink(
                LinkAnnotation.Url(
                    image.url,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ) { onOpenUrl(image.url) },
            ) { append(label) }
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("md_image"),
    )
}

/**
 * Inbound (user) message — right-aligned chat bubble, capped at ~75% width.
 * Android parity: short text hugs content; long text wraps inside the cap. No gutter, no label.
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
// Chat timeline layout — clean message stream (Android parity: no gutter dots / spine).
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
                            AssistantMessage(text, onOpenFile = onOpenFile, ts = item.entry.ts)
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
