package dev.supermux.ui.chat

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun softwareFrame(frame: ImageBitmap): ImageBitmap {
    val bitmap = frame.asAndroidBitmap()
    return if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap() else frame
}
