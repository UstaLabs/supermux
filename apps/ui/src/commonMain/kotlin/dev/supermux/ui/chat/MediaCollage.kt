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
                    FilePreviewKind.Video -> VideoPosterTile(Modifier.testTag("media_collage_video:${tile.index}"), glyph = Media.collagePlayGlyph)
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
 * A clip that has not been opened: a dark frame with a ▶ on a translucent disc. Fetches nothing.
 * Shared by the collage, the viewer's not-yet-current page and its thumbnail strip.
 */
@Composable
internal fun VideoPosterTile(modifier: Modifier = Modifier, glyph: Dp = 30.dp) {
    Box(modifier.fillMaxSize().background(VideoPoster.Background), contentAlignment = Alignment.Center) {
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
