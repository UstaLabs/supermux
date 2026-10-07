// The ONE QR encoder for both apps (cluster E4; ZXing removed in H1).
//
// Was two: desktop's `host/QrCode.kt` (ZXing core → an AWT BufferedImage → `toComposeImageBitmap`)
// and Android's `BarcodeEncoder` inside `MoreScreens.kt` (which pulled the whole
// zxing-android-embedded scanner in just to draw a bitmap). Both encoded the same payload with the
// same error-correction level; only the raster step differed, and that step is the part `:ui`
// commonMain cannot borrow from either — neither AWT nor the Android graphics stack exists here.
//
// The matrix now comes from [QrEncoder], a pure-Kotlin encoder written from ISO/IEC 18004: ZXing
// core is a JVM-ONLY jar, so it stopped resolving the moment `:ui` gained iOS targets, and this
// phase adds no new libraries. Same byte mode, same EC level M, same modules — proven by decoding
// what it produces with ZXing's own reader in `jvmTest` (see `QrEncoderTest`, `QrCodeTest`).
//
// The raster is Compose's own: an `ImageBitmap` painted through
// `androidx.compose.ui.graphics.Canvas`, the same drawing surface on JVM, Android and iOS.
package dev.supermux.ui.widgets

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import dev.supermux.ui.widgets.qr.QrEcLevel
import dev.supermux.ui.widgets.qr.QrEncoder
import dev.supermux.ui.widgets.qr.QrMatrix

// Standard scan colors: dark modules on a light quiet zone. Held FIXED (not theme-tinted) because
// a phone camera expects a high-contrast dark-on-light target — a dark-mode-inverted QR scans
// poorly. The callers therefore also paint a white plate under the image.
private val QR_DARK = Color.Black
private val QR_LIGHT = Color.White

/**
 * Encode [text] to a square QR module matrix scaled to [sizePx]×[sizePx] with a [margin]-module
 * quiet zone. `M` error correction (~15%) balances density against scan robustness for a pairing
 * payload — the level this app has always used.
 *
 * Throws only on genuinely un-encodable input (text exceeding the version-40 byte capacity), which
 * is why [qrBitmap]'s callers wrap it in `runCatching`.
 *
 * The scaling rule is ZXing's `QRCodeWriter.renderResult` verbatim, so the rendered code is
 * pixel-identical to what shipped before: an integer module size that fits the symbol PLUS the
 * quiet zone into [sizePx], with the symbol centred in whatever is left over (which is why the
 * effective quiet zone is usually wider than [margin]).
 */
internal fun encodeQr(text: String, sizePx: Int = 512, margin: Int = 2): QrMatrix {
    val symbol = QrEncoder.encode(text, QrEcLevel.MEDIUM)
    val quiet = symbol.width + margin * 2
    val output = maxOf(sizePx, quiet)
    val scale = output / quiet
    val pad = (output - symbol.width * scale) / 2
    val scaled = QrMatrix(output, output, BooleanArray(output * output))
    for (y in 0 until symbol.height) {
        for (x in 0 until symbol.width) {
            if (symbol[x, y]) scaled.setRegion(pad + x * scale, pad + y * scale, scale, scale)
        }
    }
    return scaled
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
