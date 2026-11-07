package dev.supermux.ui.chat

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A frame grabbed off a playing clip's surface, as a bitmap whose pixels can be read and drawn onto
 * a plain canvas. Android hands the grab over as a HARDWARE bitmap, which software drawing rejects
 * ("Software rendering doesn't support hardware bitmaps"), so it is copied there; elsewhere the
 * grab already is one.
 */
internal expect fun softwareFrame(frame: ImageBitmap): ImageBitmap
