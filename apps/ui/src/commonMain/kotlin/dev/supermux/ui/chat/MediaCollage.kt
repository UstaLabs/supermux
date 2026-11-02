// Two or more pictures/clips in one message, drawn as one grid (Telegram/WhatsApp style) instead
// of a stack of full-width rows.
package dev.supermux.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import androidx.compose.foundation.layout.padding
import dev.supermux.ui.editor.FilePreviewKind
import dev.supermux.ui.theme.Media
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import kotlin.math.roundToInt

/**
 * One message's media as a grid ([collageLayout]): square cells, a thin gap, the message's rounded
 * corners on the outside. A picture is cropped to its tile; a clip's tile is a dark ▶ frame that
 * downloads nothing — a transcript must never eagerly fetch every clip. A tap opens the viewer at
 * that item ([onOpen]); the "+N" tile opens at the first item it covers.
 */
@Composable
internal fun MediaCollage(
    items: List<ChatMediaItem.FromAttachment>,
    media: AttachmentMedia,
    onOpen: (ChatMediaItem) -> Unit,
) {
    val layout = collageLayout(items.size) ?: return
    val gap = Media.collageGap
    BoxWithConstraints(
        Modifier
            .collageSize(Media.inlineVideoWidthFraction, Media.collageMaxWidth, layout.columns.toFloat() / layout.rows)
            .clip(RoundedCornerShape(Radii.md))
            .testTag("media_collage"),
    ) {
        val cellW = (maxWidth - gap * (layout.columns - 1)) / layout.columns
        val cellH = (maxHeight - gap * (layout.rows - 1)) / layout.rows
        for (tile in layout.tiles) {
            val item = items[tile.index]
            val last = tile.index == layout.tiles.lastIndex && layout.overflow > 0
            Box(
                Modifier
                    .offset(x = (cellW + gap) * tile.column, y = (cellH + gap) * tile.row)
                    .size(span(cellW, gap, tile.columnSpan), span(cellH, gap, tile.rowSpan))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onOpen(item) }
                    .testTag("media_collage_tile:${tile.index}"),
                contentAlignment = Alignment.Center,
            ) {
                when (item.kind) {
                    FilePreviewKind.Image -> CollagePicture(item, media)
                    FilePreviewKind.Video -> ClipPoster(item.att, media, tile.index, "media_collage", Media.collagePlayGlyph)
                }
                if (last) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Text(
                            "+${layout.overflow}",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("media_collage_more"),
                        )
                    }
                }
            }
        }
    }
}

private fun span(cell: Dp, gap: Dp, cells: Int): Dp = cell * cells + gap * (cells - 1)

/**
 * [fraction] of the width on offer, but never wider than [max], at [aspect] (width / height).
 * Measured here rather than as `fillMaxWidth(fraction).widthIn(max)`: that chain cannot cap,
 * because `widthIn` gives way to the minimum `fillMaxWidth` already imposed.
 */
private fun Modifier.collageSize(fraction: Float, max: Dp, aspect: Float): Modifier = layout { measurable, constraints ->
    val cap = max.roundToPx()
    val offered = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).roundToInt() else cap
    val width = minOf(offered, cap).coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = (width / aspect).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

/** A picture cropped to its tile, through the same load + decode path as a single image. */
@Composable
private fun CollagePicture(item: ChatMediaItem.FromAttachment, media: AttachmentMedia) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        when (val image = rememberAttachmentImage(item.att, media)) {
            is AttachmentImage.Ready -> Image(
                painter = image.decoded.painter,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            AttachmentImage.Failed -> Icon(Icons.Filled.BrokenImage, contentDescription = "Couldn't load ${item.name}", tint = cs.onSurfaceVariant)
            AttachmentImage.Loading -> Unit
        }
    }
}

/**
 * A clip that has not been opened, as a tile: the picture the sending phone made of it when there
 * is one (a small picture of its own — the clip itself is never fetched), the ▶, and its length
 * when the broker read it from the file's header. Tags are `<tagPrefix>_video:<index>`,
 * `<tagPrefix>_poster:<index>` (once painted) and `<tagPrefix>_duration:<index>`.
 */
@Composable
internal fun ClipPoster(att: Attachment, media: AttachmentMedia, index: Int, tagPrefix: String, glyph: Dp) {
    Box(Modifier.fillMaxSize()) {
        val poster = att.poster_file_id
        // No phone poster: the frame the viewer kept when this clip last played, if it has.
        val frame = if (poster == null) ClipFrames.of(att.file_id) else null
        if (poster != null) PosterPicture(poster, media, Modifier.fillMaxSize().testTag("${tagPrefix}_poster:$index"))
        if (frame != null) {
            Image(BitmapPainter(frame), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("${tagPrefix}_frame:$index"))
        }
        VideoPosterTile(
            Modifier.testTag("${tagPrefix}_video:$index"),
            glyph = glyph,
            background = if (poster != null || frame != null) Color.Transparent else VideoPoster.Background,
        )
        att.duration_ms?.let {
            ClipDurationBadge(it, Modifier.align(Alignment.BottomEnd).padding(Space.xs).testTag("${tagPrefix}_duration:$index"))
        }
    }
}

/** A clip's poster picture, cropped to fill; nothing until it is painted. */
@Composable
internal fun PosterPicture(posterFileId: String, media: AttachmentMedia, modifier: Modifier) {
    val image = rememberAttachmentImage(Attachment(file_id = posterFileId, kind = "photo"), media)
    if (image is AttachmentImage.Ready) {
        Image(image.decoded.painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

/** `1:02` on a translucent pill, readable over any frame. */
@Composable
internal fun ClipDurationBadge(durationMs: Long, modifier: Modifier = Modifier) {
    Text(
        formatClipDuration(durationMs),
        color = VideoControls.Foreground,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(Radii.sm))
            .background(Color.Black.copy(alpha = VideoControls.DiscAlpha))
            .padding(horizontal = Space.xs + 2.dp, vertical = 1.dp),
    )
}

/**
 * A clip that has not been opened: a dark frame (or none, over a poster) with a ▶ on a
 * translucent disc. Fetches nothing.
 */
@Composable
internal fun VideoPosterTile(modifier: Modifier = Modifier, glyph: Dp = 30.dp, background: Color = VideoPoster.Background) {
    Box(modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(glyph + Space.md)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = VideoControls.DiscAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = VideoControls.Foreground, modifier = Modifier.size(glyph))
        }
    }
}

internal object VideoPoster {
    /** A frame that reads as "video" in both themes, the way an unloaded player does everywhere. */
    val Background = Color(0xFF1C1C1E)
}
