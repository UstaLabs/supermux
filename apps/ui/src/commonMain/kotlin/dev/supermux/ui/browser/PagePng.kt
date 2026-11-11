package dev.supermux.ui.browser

import androidx.compose.ui.graphics.ImageBitmap

/** A page's picture as PNG bytes, for the chat's composer (#122); null where it can't be encoded. */
internal expect fun pagePng(image: ImageBitmap): ByteArray?
