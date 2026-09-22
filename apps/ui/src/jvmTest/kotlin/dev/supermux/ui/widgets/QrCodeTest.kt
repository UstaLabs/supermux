package dev.supermux.ui.widgets

import androidx.compose.ui.graphics.toPixelMap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.supermux.host.PairingPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure round-trip proofs for the ONE QR encoder (cluster E4; was desktop's `host/QrCodeTest`): a
 * payload encoded with [encodeQr] decodes back to the exact original string via ZXing's own
 * [QRCodeReader]. No Skiko / display / network needed — the matrix is turned into a synthetic
 * luminance grid the reader consumes directly.
 *
 * These cover the RENDERING half (the scale-to-sizePx step and the run-length Canvas raster). The
 * encoder itself — versions, EC tables, Reed–Solomon, masks — is covered by `qr/QrEncoderTest`.
 */
class QrCodeTest {

    /** ZXing decode over a generated matrix. PURE_BARCODE: the input is a clean synthetic bitmap. */
    private fun decode(text: String, sizePx: Int = 512): String {
        val matrix = encodeQr(text, sizePx)
        // Build an ARGB pixel grid (dark module → black, else white) and hand it to ZXing's own
        // RGBLuminanceSource so the round trip uses a real decode path, not a hand-rolled source.
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source: LuminanceSource = RGBLuminanceSource(w, h, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(DecodeHintType.PURE_BARCODE to true)
        return QRCodeReader().decode(bitmap, hints).text
    }

    @Test fun encodesAndRoundTripsShortText() {
        assertEquals("hello-supermux", decode("hello-supermux"))
    }

    @Test fun roundTripsAPairingPayloadJson() {
        val payload = PairingPayload(
            v = 1,
            action = "pair",
            hostId = "abcdefghijklmnopqrstuvwxyz".take(26), // 26-char base32 shape
            name = "This computer",
            relayUrl = null,
            directUrl = "http://127.0.0.1:9898",
            claimSecret = "s3cr3t-one-time-claim-value-0001",
        )
        val json = Json.encodeToString(payload)
        assertEquals(json, decode(json, sizePx = 640))
    }

    @Test fun encodeProducesASquareMarginedMatrix() {
        val matrix = encodeQr("x", sizePx = 300)
        assertEquals(300, matrix.width)
        assertEquals(300, matrix.height)
        // A non-degenerate QR has both dark and light modules.
        var dark = 0
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) dark++
        assertTrue(dark in 1 until matrix.width * matrix.height, "expected a mix of dark/light modules, got $dark dark")
    }

    @Test fun qrBitmapHasRequestedDimensions() {
        val bmp = qrBitmap("dimension-check", sizePx = 256)
        assertEquals(256, bmp.width)
        assertEquals(256, bmp.height)
    }

    /**
     * The RENDERED image decodes — the part that changed in E4. The matrix is now rasterised
     * through a Compose `Canvas` (no `java.awt.BufferedImage`, no Android `BarcodeEncoder`), so
     * the run-length rectangle painting has to land on exactly the same modules.
     */
    @Test fun renderedBitmapDecodesBackToItsContent() {
        val url = "https://pair.example/rendered-bitmap-token"
        val bmp = qrBitmap(url, sizePx = 512)
        val map = bmp.toPixelMap()
        val pixels = IntArray(bmp.width * bmp.height) { i ->
            val px = map[i % bmp.width, i / bmp.width]
            if (px.red < 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, pixels)))
        val decoded = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
        assertEquals(url, decoded)
    }
}
