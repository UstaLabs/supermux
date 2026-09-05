// The ONE QR encoder for both apps (cluster E4).
//
// Was two: desktop's `host/QrCode.kt` (ZXing core → an AWT BufferedImage → `toComposeImageBitmap`)
// and Android's `BarcodeEncoder` inside `MoreScreens.kt` (which pulled the whole
// zxing-android-embedded scanner in just to draw a bitmap). Both encoded the same payload with the
// same error-correction level; only the raster step differed, and that step is the part `:ui`
// commonMain cannot borrow from either — neither AWT nor the Android graphics stack exists here.
//
// So the matrix still comes from ZXing (pure Java, on both consumers' classpath) and the raster is
// Compose's own: an `ImageBitmap` painted through `androidx.compose.ui.graphics.Canvas`, which is
// the same drawing surface on JVM, Android and — when it arrives — iOS.
package dev.supermux.ui.widgets

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

// Standard scan colors: dark modules on a light quiet zone. Held FIXED (not theme-tinted) because
// a phone camera expects a high-contrast dark-on-light target — a dark-mode-inverted QR scans
// poorly. The callers therefore also paint a white plate under the image.
private val QR_DARK = Color.Black
private val QR_LIGHT = Color.White

/**
 * Encode [text] to a square QR [BitMatrix] scaled to [sizePx]×[sizePx] with a [margin]-module quiet
 * zone. `M` error correction (~15%) balances density against scan robustness for a pairing
 * payload. Pure ZXing — throws only on genuinely un-encodable input (e.g. text exceeding QR
 * capacity), which is why [qrBitmap]'s callers wrap it in `runCatching`.
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
 * Render [content] as a Compose [ImageBitmap] QR code (dark-on-light, [sizePx] square).
 *
 * Pure: the same text always produces the same image, and nothing here touches the network.
 * Dark modules are painted as horizontal RUNS rather than pixel by pixel — the scaled matrix is
 * blocky by construction, so one `drawRect` per run keeps a 512px code to a few thousand draw
 * calls instead of a quarter of a million.
 */
fun qrBitmap(content: String, sizePx: Int = 512, margin: Int = 2): ImageBitmap {
    val matrix = encodeQr(content, sizePx, margin)
    val w = matrix.width
    val h = matrix.height
    val image = ImageBitmap(w, h)
    val canvas = Canvas(image)
    val light = Paint().apply { color = QR_LIGHT }
    val dark = Paint().apply { color = QR_DARK }
    canvas.drawRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), light)
    for (y in 0 until h) {
        var x = 0
        while (x < w) {
            if (!matrix[x, y]) {
                x++
                continue
            }
            val start = x
            while (x < w && matrix[x, y]) x++
            canvas.drawRect(
                Rect(start.toFloat(), y.toFloat(), x.toFloat(), (y + 1).toFloat()),
                dark,
            )
        }
    }
    return image
}
