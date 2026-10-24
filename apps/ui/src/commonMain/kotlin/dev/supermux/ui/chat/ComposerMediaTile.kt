// The composer's photo/video attachment: a square thumbnail tile instead of a name chip, so the
// user sees WHICH shot they are about to send. Every other file keeps its name chip.
//
// It carries the chip's whole contract — the same test tags, progress while uploading, the whole
// tile as Retry when failed, × once settled — so the strip behaves the same whatever it holds.
package dev.supermux.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.supermux.net.ChunkSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Photos and videos get a thumbnail tile; everything else stays a name chip. */
internal fun isMediaMime(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/")

/** Edge of the square tile. */
private val TileSize = 64.dp

/** Decode target in pixels — a little over the tile's edge on a 3x screen, so it stays sharp. */
private const val THUMB_PX = 256

/** Largest image the composer reads into memory itself when the platform gave no [PickedFile.preview]. */
private const val MAX_SELF_DECODE_BYTES = 15L * 1024 * 1024

/**
 * A photo or video tile.
 *
 * @param progress upload fraction while uploading, null when there is nothing in flight.
 * @param failed tints the tile and makes all of it [onRetry].
 * @param onRemove null hides the × (an upload in flight has none).
 */
@Composable
internal fun ComposerMediaTile(
    name: String,
    mime: String,
    source: ChunkSource,
    preview: Any?,
    testTag: String,
    label: String,
    progress: Float? = null,
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onRemove: (() -> Unit)?,
    removeTestTag: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val video = mime.startsWith("video/")
    val data = rememberThumbData(preview, mime, source)
    val decoded = when (data) {
        null -> DecodedImage.Failed
        ThumbPending -> DecodedImage.Loading
        else -> rememberDecodedImage(data, size = THUMB_PX, memoryCacheKey = null)
    }

    Box(
        modifier = Modifier
            .size(TileSize)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainerHigh)
            .then(if (failed && onRetry != null) Modifier.clickable { onRetry() }.testTag("composer-chip-retry") else Modifier)
            .semantics { contentDescription = label }
            .testTag(testTag),
    ) {
        when (decoded) {
            is DecodedImage.Ready -> Image(
                painter = decoded.painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            DecodedImage.Loading -> Unit
            DecodedImage.Failed -> Icon(
                if (video) Icons.Filled.Videocam else Icons.Filled.Image,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(24.dp).align(Alignment.Center),
            )
        }
        if (video && decoded is DecodedImage.Ready) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
            )
        }
        if (progress != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(24.dp).align(Alignment.Center),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                strokeWidth = 2.5.dp,
            )
        }
        if (failed) {
            Box(Modifier.fillMaxSize().background(cs.errorContainer.copy(alpha = 0.8f)))
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = cs.onErrorContainer,
                modifier = Modifier.size(24.dp).align(Alignment.Center),
            )
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onRemove() }
                    .then(if (removeTestTag != null) Modifier.testTag(removeTestTag) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove $name", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * What to hand Coil: the platform's [preview] when there is one, otherwise — for a photo small
 * enough — its bytes, read once off the UI thread ([ThumbPending] until they are in). Null means
 * "no thumbnail" (the tile shows a glyph).
 */
@Composable
private fun rememberThumbData(preview: Any?, mime: String, source: ChunkSource): Any? {
    if (preview != null) return preview
    if (!mime.startsWith("image/") || source.size !in 1..MAX_SELF_DECODE_BYTES) return null
    val bytes by produceState<ByteArray?>(null, source) {
        value = withContext(Dispatchers.Default) {
            // An unreadable source becomes empty bytes, which Coil fails on: the glyph, not a blank forever.
            runCatching { source.read(0, source.size.toInt()) }.getOrNull() ?: ByteArray(0)
        }
    }
    return bytes ?: ThumbPending
}

/** The bytes of a self-decoded photo are still being read. */
private object ThumbPending
