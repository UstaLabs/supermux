package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlinx.coroutines.CompletableDeferred

/**
 * The real JPEG and PNG on the caller's thread, so UI tests stay deterministic: a decode the cache starts finishes
 * before the request that started it returns. Counts its work, and holds it while [encodeGate] or [decodeGate] is set,
 * as a slow device would.
 */
internal class TestCodec : ImageCodec {
    var encodes = 0
        private set
    var decodes = 0
        private set
    var encodeGate: CompletableDeferred<Unit>? = null
    var decodeGate: CompletableDeferred<Unit>? = null

    override suspend fun encode(image: ImageBitmap, opaque: Boolean): ByteArray? {
        encodes++
        encodeGate?.await()
        return SnapshotCodec.encodeNow(image, opaque)
    }

    override suspend fun decode(bytes: ByteArray): ImageBitmap? {
        decodes++
        decodeGate?.await()
        return SnapshotCodec.decodeNow(bytes)
    }
}

/**
 * A codec of [StubImage]s, for the cache's own tests, which need no pixels: [stored] bytes carry the image's size,
 * and decode back to a stub of that size. Holds decodes while [gate] is set; with [fails], decodes give nothing.
 */
internal class FakeCodec : ImageCodec {
    var decodes = 0
        private set
    var gate: CompletableDeferred<Unit>? = null
    var fails = false

    override suspend fun encode(image: ImageBitmap, opaque: Boolean): ByteArray = stored(64, image.width, image.height)

    override suspend fun decode(bytes: ByteArray): ImageBitmap? {
        decodes++
        gate?.await()
        if (fails || bytes.size < 8) return null
        return StubImage(bytes.int(0), bytes.int(4), ImageBitmapConfig.Argb8888)
    }

    companion object {
        /** [size] stored bytes of a [width]×[height] snapshot. */
        fun stored(size: Int, width: Int = 10, height: Int = 10): ByteArray = ByteArray(maxOf(size, 8)).also {
            it.putInt(0, width)
            it.putInt(4, height)
        }

        private fun ByteArray.putInt(at: Int, value: Int) {
            for (i in 0 until 4) this[at + i] = (value shr (8 * i)).toByte()
        }

        private fun ByteArray.int(at: Int): Int = (0 until 4).sumOf { (this[at + it].toInt() and 0xFF) shl (8 * it) }
    }
}
