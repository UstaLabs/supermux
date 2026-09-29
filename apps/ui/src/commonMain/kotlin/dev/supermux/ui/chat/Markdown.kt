// The ONE markdown renderer. Both apps' `MarkdownBody`/`MarkdownImage`/`MarkdownTable` collapsed
// into this file (cluster D2) — desktop's copy is the base (it had the image policy, the shrink-only
// sizing and the tests), Android's Coil loader is now the production image fetcher on both.
//
// Nothing here may name `java.*`: image bytes come through Ktor (ImageFetch.kt) and decode through
// Coil, so the same code paints on Android, desktop and — next — iOS.
package dev.supermux.ui.chat

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import dev.supermux.ui.ColumnAlign
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.MdBlock
import dev.supermux.ui.SpanStyleKind
import dev.supermux.ui.parseInlineMarkdown
import dev.supermux.ui.parseMarkdownBlocks
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.Media
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Sizes
import dev.supermux.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Inline spans
// ---------------------------------------------------------------------------

/**
 * Convert a markdown string to an [AnnotatedString] with inline bold/italic/code spans.
 *
 * Inline `code` spans get MonoFontFamily. Web links are ALWAYS wired with an explicit click
 * handler (`Platform.openUrl`): a `LinkAnnotation.Url` with no listener leans on `LocalUriHandler`,
 * which `SelectionContainer` can swallow on Android — links then look like plain text and taps do
 * nothing.
 */
@Composable
fun mdAnnotated(
    text: String,
    onOpenFile: (FilePathRef) -> Unit = {},
    linkify: Boolean = false,
    onOpenUrl: ((String) -> Unit)? = null,
): AnnotatedString {
    val platform = LocalPlatform.current
    val openUrl: (String) -> Unit = onOpenUrl ?: { url -> runCatching { platform.openUrl(url) } }
    val linkColor = MaterialTheme.colorScheme.primary
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
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                    ) { append(s.text) } // never linkify inside inline code
                    SpanStyleKind.STRIKE -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendLinkified(s.text, linkStyles, openUrl)
                    }
                    SpanStyleKind.LINK -> {
                        val url = s.url
                        val ref = s.ref
                        when {
                            // Web links from `[label](url)` are always clickable — open the browser.
                            url != null -> withLink(
                                LinkAnnotation.Url(url, linkStyles) { openUrl(url) },
                            ) { append(s.text) }
                            // File paths only become editor links in agent messages (linkify).
                            ref != null && linkify -> withLink(
                                LinkAnnotation.Clickable(
                                    tag = "file:${ref.path}",
                                    styles = linkStyles,
                                ) { onOpenFile(ref) },
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

/**
 * Selects a whole link, not one word of it, when the link is right-clicked.
 *
 * Out of the box a right-click inside a `SelectionContainer` selects the WORD under the pointer
 * (unless the pointer is already inside the selection) and then opens the Copy menu — so right-click
 * → Copy on `https://github.com/foo/bar` copied `github`. [MdText] catches the secondary press
 * before the container's context-menu detector sees it (child pointer handlers run first) and
 * selects the link's full range here; the container's "select the word if not already selected"
 * then finds the pointer inside that selection and leaves it alone.
 */
private val LocalLinkSelector = staticCompositionLocalOf<((String, TextRange) -> Unit)?> { null }

/** `SelectionContainer` whose [MdText] children select a whole link on right-click. */
@Composable
fun LinkSelectionContainer(
    modifier: Modifier = Modifier,
    state: SelectionState = rememberSelectionState(),
    content: @Composable () -> Unit,
) {
    val selector = remember(state) { { text: String, range: TextRange -> state.selectLinkIn(text, range) } }
    SelectionContainer(state = state, modifier = modifier) {
        CompositionLocalProvider(LocalLinkSelector provides selector, content = content)
    }
}

/**
 * `SelectionState.select` takes an offset into every selectable's text laid end to end, in layout
 * order, so the link's range is shifted by the length of every selectable before the one holding it.
 * The ordered list itself is internal to Compose; `selectAll()` publishes it through [selectedTexts]
 * synchronously, and the narrowing `select` lands in the same frame, so nothing flashes.
 * A duplicate block picks the first match; if that is the wrong one the click falls back to the
 * stock select-the-word behavior.
 */
private fun SelectionState.selectLinkIn(text: String, range: TextRange) {
    selectAll()
    val texts = selectedTexts
    val index = texts.indexOfFirst { it.text == text }
    if (index < 0) return clear()
    val base = (0 until index).sumOf { texts[it].length }
    select(TextRange(base + range.start, base + range.end))
}

/** The link under [position], if any, as a range of [text]. */
internal fun linkRangeAt(text: AnnotatedString, layout: TextLayoutResult, position: Offset): TextRange? {
    if (text.isEmpty()) return null
    val offset = layout.getOffsetForPosition(position).coerceAtMost(text.length - 1)
    // getOffsetForPosition clamps to the nearest character, so a click in the blank space after a
    // line would otherwise pick up a link that merely ends the line.
    if (!layout.getBoundingBox(offset).contains(position)) return null
    val link = text.getLinkAnnotations(offset, offset + 1).firstOrNull() ?: return null
    return TextRange(link.start, link.end)
}

/** A markdown `Text` whose links select as a whole on right-click inside a [LinkSelectionContainer]. */
@Composable
internal fun MdText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle,
) {
    val selector = LocalLinkSelector.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val linkModifier = if (selector == null) Modifier else Modifier.pointerInput(text, selector) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                val position = event.changes.firstOrNull()?.position ?: continue
                val range = layout?.let { linkRangeAt(text, it, position) } ?: continue
                selector(text.text, range)
            }
        }
    }
    Text(
        text = text,
        // Last in the chain so pointer positions are in the text's own coordinates.
        modifier = modifier.then(linkModifier),
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        onTextLayout = { layout = it },
        style = style,
    )
}

private val urlRegex = Regex("""https?://[^\s<>"'\])]+""")

/** Append [text], turning bare http(s) URLs into clickable, underlined links. */
internal fun AnnotatedString.Builder.appendLinkified(
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
        withLink(LinkAnnotation.Url(url, linkStyles) { openUrl(url) }) { append(url) }
        if (trail.isNotEmpty()) append(trail)
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

/**
 * Elegant mono code block for fenced ``` content.
 * Left accent + subtle header-tinted background + horizontal scroll.
 * A top-end copy button copies the raw code, flashing a check for ~1.5s. [extraAction] is one more
 * 28dp button drawn just before it (the mermaid block's back-to-diagram toggle), in the same row so
 * the two never overlap.
 */
@Composable
fun FencedCodeBlock(code: String, extraAction: (@Composable () -> Unit)? = null) {
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
                    .height(1.dp) // stretches with the Row's intrinsic content height
                    .background(cs.primary.copy(alpha = 0.4f)),
            )
            Box(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    // pad the right so the copy button never overlaps the first line of code
                    .padding(
                        start = Space.md,
                        end = Space.xl + Space.md + if (extraAction != null) 28.dp else 0.dp,
                        top = Space.sm,
                        bottom = Space.sm,
                    ),
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
        Row(Modifier.align(Alignment.TopEnd).padding(Space.xs)) {
            extraAction?.invoke()
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                    scope.launch { delay(1500); copied = false }
                },
                modifier = Modifier.size(28.dp),
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
}

// ---------------------------------------------------------------------------
// Blocks
// ---------------------------------------------------------------------------

/**
 * Reusable block-markdown renderer (prose + fenced code + tables + images), the single source for
 * chat, the editor's markdown preview and the walkthrough body. Splits via the shared
 * [parseMarkdownBlocks]. Keep all markdown surfaces routed through this so they never drift.
 *
 * [loadImage] and [onOpenUrl] are TEST seams: left null, images fetch through the production Ktor
 * policy and decode through Coil, and links open through `Platform.openUrl`.
 */
@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    onOpenFile: (FilePathRef) -> Unit = {},
    linkify: Boolean = false,
    loadImage: (suspend (String) -> ImageBitmap?)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
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
                        MdText(
                            text = mdAnnotated(block.text, onOpenFile, linkify = linkify, onOpenUrl = onOpenUrl),
                            color = cs.onSurface,
                            style = typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is MdBlock.Code ->
                    if (block.lang.equals("mermaid", ignoreCase = true)) MermaidBlock(block.code)
                    else FencedCodeBlock(block.code)
                is MdBlock.Heading -> MdText(
                    text = mdAnnotated(block.text, onOpenFile, linkify = linkify, onOpenUrl = onOpenUrl),
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
                    MdText(
                        text = mdAnnotated(block.text, onOpenFile, linkify = linkify, onOpenUrl = onOpenUrl),
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
                    MdText(
                        text = mdAnnotated(block.text, onOpenFile, linkify = linkify, onOpenUrl = onOpenUrl),
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
                    MdText(
                        text = mdAnnotated(block.text, onOpenFile, linkify = linkify, onOpenUrl = onOpenUrl),
                        color = cs.onSurface,
                        style = typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdBlock.Table -> MarkdownTable(block, onOpenFile, linkify, onOpenUrl)
                is MdBlock.Image -> MarkdownImage(block, loadImage = loadImage, onOpenUrl = onOpenUrl)
            }
        }
    }
}

/** ColumnAlign → Compose [TextAlign] (pure; unit-tested). */
fun columnTextAlign(align: ColumnAlign): TextAlign = when (align) {
    ColumnAlign.LEFT -> TextAlign.Left
    ColumnAlign.CENTER -> TextAlign.Center
    ColumnAlign.RIGHT -> TextAlign.Right
}

/**
 * GFM table as a bordered, horizontally-scrollable grid. Laid out column-major: each column is a
 * `Column(width = IntrinsicSize.Max)` so every cell shares the widest cell's width, and single-line
 * (no-wrap) cells mean wide tables scroll instead of squishing.
 */
@Composable
fun MarkdownTable(
    table: MdBlock.Table,
    onOpenFile: (FilePathRef) -> Unit,
    linkify: Boolean,
    onOpenUrl: ((String) -> Unit)? = null,
) {
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
                MarkdownTableCell(
                    table.headers.getOrElse(c) { "" },
                    table.aligns.getOrElse(c) { ColumnAlign.LEFT },
                    header = true,
                    onOpenFile = onOpenFile,
                    linkify = linkify,
                    onOpenUrl = onOpenUrl,
                )
                for (row in table.rows) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
                    MarkdownTableCell(
                        row.getOrElse(c) { "" },
                        table.aligns.getOrElse(c) { ColumnAlign.LEFT },
                        header = false,
                        onOpenFile = onOpenFile,
                        linkify = linkify,
                        onOpenUrl = onOpenUrl,
                    )
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
    onOpenUrl: ((String) -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (header) cs.surfaceContainerLow else Color.Transparent)
            .padding(horizontal = Space.sm + Space.xs, vertical = Space.sm),
    ) {
        MdText(
            text = mdAnnotated(text, onOpenFile, linkify = linkify, onOpenUrl = onOpenUrl),
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

// ---------------------------------------------------------------------------
// Images
// ---------------------------------------------------------------------------

/**
 * Layout tokens for inline markdown images — aliases of theme [Media]/[Sizes] so chat call sites
 * stay readable without reintroducing magic `N.dp` values.
 */
object MdImageDimens {
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
fun mdImagePaintSize(
    pixelWidth: Int,
    pixelHeight: Int,
    maxWidth: Dp,
    maxHeight: Dp,
    density: Density,
): Pair<Dp, Dp> {
    if (pixelWidth <= 0 || pixelHeight <= 0) return maxWidth to maxHeight
    val iw = with(density) { pixelWidth.toDp() }
    val ih = with(density) { pixelHeight.toDp() }
    val scale = minOf(1f, maxWidth / iw, maxHeight / ih)
    return (iw * scale) to (ih * scale)
}

/**
 * Standalone markdown image `![alt](url)`.
 *
 * - `https://` URLs: fetched under the [fetchImageBytesWithPolicy] policy (byte cap, bounded
 *   redirects, no scheme downgrade) then decoded by Coil, and painted inline.
 * - Everything else (http, relative, `data:`): a compact tappable link line — a message-content
 *   image is a tracking-pixel / IP-leak vector, so nothing but https is ever fetched.
 * - A load/decode failure falls back to a **distinct** failure link line (never a blank hole, never
 *   identical to the deliberate non-https fallback).
 * - A loaded image is clickable: `onOpenUrl` (or `Platform.openUrl`) opens the original.
 *
 * [loadImage] is the test seam that replaces fetch+decode entirely, so the suite never touches the
 * network.
 */
@Composable
fun MarkdownImage(
    image: MdBlock.Image,
    loadImage: (suspend (String) -> ImageBitmap?)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val platform = LocalPlatform.current
    val open: (String) -> Unit = onOpenUrl ?: { url -> runCatching { platform.openUrl(url) } }
    if (!isHttpsImageUrl(image.url)) {
        MarkdownImageLinkLine(image, loadFailed = false, onOpenUrl = open)
        return
    }
    if (loadImage != null) {
        InjectedMarkdownImage(image, loadImage, open)
        return
    }
    CoilMarkdownImage(image, open)
}

/** The `loadImage`-seam branch: an already-decoded bitmap, painted shrink-only. */
@Composable
private fun InjectedMarkdownImage(
    image: MdBlock.Image,
    loadImage: suspend (String) -> ImageBitmap?,
    onOpenUrl: (String) -> Unit,
) {
    var bitmap by remember(image.url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(image.url) { mutableStateOf(false) }
    LaunchedEffect(image.url) {
        val decoded = runCatching { loadImage(image.url) }.getOrNull()
        if (decoded != null) bitmap = decoded else failed = true
    }
    val bmp = bitmap
    when {
        bmp != null -> ShrinkOnlyImage(
            width = bmp.width,
            height = bmp.height,
            tag = "md_image",
            onClick = { onOpenUrl(image.url) },
        ) { modifier ->
            Image(
                bitmap = bmp,
                contentDescription = image.alt.ifEmpty { "Image" },
                contentScale = ContentScale.Fit,
                modifier = modifier,
            )
        }
        failed -> MarkdownImageLinkLine(image, loadFailed = true, onOpenUrl = onOpenUrl)
        else -> MdImageLoadingBox("md_image_loading")
    }
}

/** Production branch: Ktor-fetched bytes decoded by Coil. */
@Composable
private fun CoilMarkdownImage(image: MdBlock.Image, onOpenUrl: (String) -> Unit) {
    var bytes by remember(image.url) { mutableStateOf<ByteArray?>(null) }
    var fetchFailed by remember(image.url) { mutableStateOf(false) }
    LaunchedEffect(image.url) {
        val fetched = runCatching { loadMarkdownImageBytes(image.url) }.getOrNull()
        if (fetched == null) fetchFailed = true else bytes = fetched
    }
    if (fetchFailed) {
        MarkdownImageLinkLine(image, loadFailed = true, onOpenUrl = onOpenUrl)
        return
    }
    val data = bytes
    if (data == null) {
        MdImageLoadingBox("md_image_loading")
        return
    }
    when (val decoded = rememberDecodedImage(data)) {
        DecodedImage.Loading -> MdImageLoadingBox("md_image_loading")
        DecodedImage.Failed -> MarkdownImageLinkLine(image, loadFailed = true, onOpenUrl = onOpenUrl)
        is DecodedImage.Ready -> ShrinkOnlyImage(
            width = decoded.width,
            height = decoded.height,
            tag = "md_image",
            onClick = { onOpenUrl(image.url) },
        ) { modifier ->
            Image(
                painter = decoded.painter,
                contentDescription = image.alt.ifEmpty { "Image" },
                contentScale = ContentScale.Fit,
                modifier = modifier,
            )
        }
    }
}

/** What [rememberDecodedImage] knows about a set of image bytes. */
internal sealed interface DecodedImage {
    data object Loading : DecodedImage

    data object Failed : DecodedImage

    /** [width]/[height] are the decoded PIXEL dimensions, which drive the shrink-only sizing. */
    data class Ready(val painter: Painter, val width: Int, val height: Int) : DecodedImage
}

/**
 * Decode [data] (bytes, a URI, anything Coil accepts) through Coil, EAGERLY.
 *
 * Deliberately not `rememberAsyncImagePainter`: that painter only starts its request when it is
 * first drawn, so a caller that waits for `Success` before painting anything — which is exactly
 * what a "spinner, then the image at its natural size" layout does — deadlocks and shows the
 * spinner forever. Running the request ourselves also hands back the decoded pixel size, which
 * the shrink-only sizing needs before it can lay the image out.
 */
@Composable
internal fun rememberDecodedImage(
    data: Any?,
    /** Decode down to at most this many pixels per side (null = the image's full resolution). */
    size: Int? = null,
    /** Coil memory-cache key: a re-composed caller gets the decoded bitmap back without decoding. */
    memoryCacheKey: String? = null,
): DecodedImage {
    val context = LocalPlatformContext.current
    var state by remember(data, size, memoryCacheKey) { mutableStateOf<DecodedImage>(DecodedImage.Loading) }
    LaunchedEffect(data, size, memoryCacheKey) {
        if (data == null) {
            state = DecodedImage.Failed
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context).data(data).apply {
            // FILL: the short side covers [size], so a centre-cropped square stays sharp.
            if (size != null) size(size).scale(Scale.FILL)
            if (memoryCacheKey != null) memoryCacheKey(memoryCacheKey)
        }.build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
        val img = (result as? SuccessResult)?.image
        state = if (img == null) {
            DecodedImage.Failed
        } else {
            DecodedImage.Ready(img.asPainter(context), img.width, img.height)
        }
    }
    return state
}

/**
 * Paint [content] at natural size, shrunk-only to fit the column and [MdImageDimens.MaxHeight] —
 * never upscaled (`fillMaxWidth` + `ContentScale.Fit` would blow a 32×32 icon up to the column).
 */
@Composable
internal fun ShrinkOnlyImage(
    width: Int,
    height: Int,
    tag: String,
    radius: Dp = Radii.sm,
    alignEnd: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
    ) {
        val density = LocalDensity.current
        val (w, h) = mdImagePaintSize(
            pixelWidth = width,
            pixelHeight = height,
            maxWidth = maxWidth,
            maxHeight = MdImageDimens.MaxHeight,
            density = density,
        )
        var modifier = Modifier
            .width(w)
            .height(h)
            .clip(RoundedCornerShape(radius))
        if (onClick != null) {
            modifier = modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
        }
        content(modifier.testTag(tag))
    }
}

/** Reserve [MdImageDimens.LoadingHeight] while an image loads, so nothing reflows upward. */
@Composable
internal fun MdImageLoadingBox(tag: String, shape: Dp = Radii.sm) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MdImageDimens.LoadingHeight)
            .clip(RoundedCornerShape(shape))
            .background(cs.surfaceContainer)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            Modifier.size(MdImageDimens.SpinnerSize),
            color = cs.onSurfaceVariant,
            strokeWidth = MdImageDimens.SpinnerStroke,
        )
    }
}

/**
 * Tappable 🖼 + label link line. [loadFailed] distinguishes a deliberate non-https skip from a real
 * fetch/decode failure so the user can tell them apart.
 */
@Composable
private fun MarkdownImageLinkLine(
    image: MdBlock.Image,
    loadFailed: Boolean,
    onOpenUrl: (String) -> Unit,
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
