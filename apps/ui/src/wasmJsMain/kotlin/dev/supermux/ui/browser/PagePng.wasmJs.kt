package dev.supermux.ui.browser

import androidx.compose.ui.graphics.ImageBitmap

// The web app can't picture a page (its iframe is another origin), so it never offers a screenshot (Caps.pageScreenshots).
internal actual fun pagePng(image: ImageBitmap): ByteArray? = null
