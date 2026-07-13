package dev.supermux.desktop.host

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

/**
 * Pure, offline QR rendering for the first-run host wizard's pairing payload (Plan 3 Task 3 / spec §6).
 *
 * Uses the bundled ZXing-core encoder — NO network, NO Android deps (that's `zxing-android-embedded`,
 * which this deliberately avoids). The encode step ([encodeQr]) is framework-free so it unit-tests on
 * the JVM (round-trips through ZXing's own decoder in [QrCodeTest]); [qrBitmap] only wraps the produced
 * black-and-white module grid into a Compose [ImageBitmap] the wizard can draw.
 */

// Standard scan colors: dark modules on a light quiet zone. Held FIXED (not theme-tinted) because a
// phone camera expects a high-contrast dark-on-light target — a dark-mode-inverted QR scans poorly.
private const val QR_DARK = 0xFF000000.toInt()
private const val QR_LIGHT = 0xFFFFFFFF.toInt()

/**
 * Encode [text] to a square QR [BitMatrix] scaled to [sizePx]×[sizePx] with a [margin]-module quiet
 * zone. `M` error correction (~15%) balances density against scan robustness for a pairing payload.
 * Pure ZXing — throws only on genuinely un-encodable input (e.g. text exceeding QR capacity).
 */
internal fun encodeQr(text: String, sizePx: Int = 512, margin: Int = 2): BitMatrix {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to margin,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
}

/**
 * Render [text] as a Compose [ImageBitmap] QR code (dark-on-light, [sizePx] square). Pure function:
 * given the same text it always produces the same image, and it touches no network. The wizard draws
 * the result with `Image(bitmap = qrBitmap(payloadJson), …)`.
 */
fun qrBitmap(text: String, sizePx: Int = 512, margin: Int = 2): ImageBitmap {
    val matrix = encodeQr(text, sizePx, margin)
    val w = matrix.width
    val h = matrix.height
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until h) {
        for (x in 0 until w) {
            img.setRGB(x, y, if (matrix[x, y]) QR_DARK else QR_LIGHT)
        }
    }
    return img.toComposeImageBitmap()
}
