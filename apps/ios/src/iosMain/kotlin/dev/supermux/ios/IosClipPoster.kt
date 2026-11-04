// A phone's own clip gets its poster before it is sent: its first frame, grabbed with AVFoundation
// when it is picked — the iOS twin of Android's picker frame (MediaMetadataRetriever). The JPEG
// becomes the pick's preview (the composer chip paints it) and, through `posterJpeg`, the poster
// the composer uploads and links to the clip.
package dev.supermux.ios

import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.platform.PickedFile
import dev.supermux.util.toByteArray
import dev.supermux.util.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/** The poster's long side, in pixels — a tile's picture, not the clip. */
private const val POSTER_EDGE = 480.0

/** A poster is a small picture: good enough at this quality, a few tens of KB. */
private const val POSTER_JPEG_QUALITY = 0.82

/** The bridge's file as a [PickedFile]; a clip also gets its first frame as its preview. */
internal suspend fun IosPickedFile.toPickedFileWithPoster(): PickedFile {
    val poster = if (mime.startsWith("video/")) withContext(Dispatchers.Default) { clipFirstFrameJpeg(bytes, name) } else null
    return PickedFile(name, mime, ByteArrayChunkSource(bytes), preview = poster)
}

/**
 * The clip's first frame as a JPEG, upright (its track transform applied, so a portrait phone clip
 * stays portrait), at most [POSTER_EDGE] px on its long side. Null when the frame cannot be read.
 * AVFoundation reads a clip from a URL, so the bytes go to a temp file first and are removed after.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun clipFirstFrameJpeg(bytes: ByteArray, name: String): ByteArray? = runCatching {
    val dir = NSTemporaryDirectory() + "supermux-posters"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val ext = name.substringAfterLast('.', "").ifBlank { "mp4" }
    val path = "$dir/${NSUUID().UUIDString}.$ext"
    if (!bytes.toNSData().writeToFile(path, atomically = true)) return@runCatching null
    try {
        val generator = AVAssetImageGenerator(asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSizeMake(POSTER_EDGE, POSTER_EDGE)
        val frame = generator.copyCGImageAtTime(CMTimeMake(value = 0, timescale = 600), actualTime = null, error = null)
            ?: return@runCatching null
        try {
            UIImageJPEGRepresentation(UIImage.imageWithCGImage(frame), POSTER_JPEG_QUALITY)?.toByteArray()
        } finally {
            CGImageRelease(frame)
        }
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}.getOrNull()
