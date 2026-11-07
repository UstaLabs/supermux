package dev.supermux.webview.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

internal actual fun encodeImage(image: ImageBitmap, format: EncodedFormat): ByteArray? = try {
    val bitmap = image.asAndroidBitmap()
    val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 8)
    val ok = when (format) {
        EncodedFormat.Jpeg -> bitmap.compress(Bitmap.CompressFormat.JPEG, SNAPSHOT_JPEG_QUALITY, out)
        EncodedFormat.Png -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    if (ok) out.toByteArray() else null
} catch (e: OutOfMemoryError) {
    null
}

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? = try {
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
} catch (e: OutOfMemoryError) {
    null
}

/** Android's canvas draws a bitmap as it is, without copying a mutable one: nothing to mark. */
internal actual fun ImageBitmap.markedImmutable(): ImageBitmap = this
