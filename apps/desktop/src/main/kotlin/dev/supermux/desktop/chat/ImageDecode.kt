package dev.supermux.desktop.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/**
 * Decode raw image bytes to a **rasterised** Compose [ImageBitmap] via Skiko.
 *
 * The chat timeline itself no longer needs this — Coil decodes inline images on every host since
 * cluster D2 — but `SM_MD_IMAGE` (the Xvfb screenshot harness in `Main.kt`) still feeds the
 * markdown renderer's `loadImage` seam with local bytes, and that seam speaks [ImageBitmap].
 *
 * [Codec.readPixels] rasterises HERE rather than lazily at draw time, so a truncated or corrupt
 * payload fails inside this `runCatching` instead of throwing out of composition.
 */
internal fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching {
        val data = Data.makeFromBytes(bytes)
        val codec = Codec.makeFromData(data)
        val raster = codec.readPixels()
        if (raster.width <= 0 || raster.height <= 0 || raster.isNull) return@runCatching null
        raster.setImmutable()
        // SkiaBackedImageBitmap retains [raster]; do not close it. Codec/Data can be closed.
        codec.close()
        data.close()
        raster.asComposeImageBitmap()
    }.getOrNull()
