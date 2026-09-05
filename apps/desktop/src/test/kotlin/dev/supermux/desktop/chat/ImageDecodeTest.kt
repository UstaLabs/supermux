package dev.supermux.desktop.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [decodeImageBytes] is the only Skiko decode left on desktop: `SM_MD_IMAGE` (the Xvfb screenshot
 * harness in `Main.kt`) feeds local bytes into the shared markdown renderer's `loadImage` seam,
 * which speaks `ImageBitmap`. It force-rasterises, so a corrupt payload must fail HERE rather than
 * at Compose draw time — hence the garbage case.
 */
class ImageDecodeTest {

    @Test fun decodes_a_png_to_its_natural_size() {
        val bmp = decodeImageBytes(TINY_PNG_BYTES)
        assertTrue(bmp != null, "expected Skiko to force-decode a valid PNG")
        assertEquals(2, bmp!!.width)
        assertEquals(2, bmp.height)
        // Raster is fully materialised — prepareToDraw must not throw (lazy-encoded would risk that).
        bmp.prepareToDraw()
    }

    @Test fun rejects_garbage_and_truncated_payloads() {
        assertNull(decodeImageBytes(byteArrayOf(1, 2, 3, 4, 5)))
        // Valid PNG signature + IHDR length, body truncated mid-stream.
        assertNull(decodeImageBytes(TINY_PNG_BYTES.copyOf(24)))
    }

    companion object {
        // 2×2 RGB PNG (73 bytes).
        private val TINY_PNG_BYTES = hex(
            "89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a73" +
                "0000001049444154789c63f8cfc000440c100a001fee03fd8b5f14d40000000049454e44ae426082",
        )

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
