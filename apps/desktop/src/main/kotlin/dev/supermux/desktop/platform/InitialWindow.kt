package dev.supermux.desktop.platform

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.math.roundToInt

/** The main window's first size (dp) and, when it had to shrink, its centred position (null: the OS places it). */
internal data class InitialWindow(val width: Int, val height: Int, val x: Int? = null, val y: Int? = null)

internal const val DEFAULT_WINDOW_WIDTH = 1440
internal const val DEFAULT_WINDOW_HEIGHT = 900

/**
 * Pure. 1440×900 when it fits [usable] (the screen minus the taskbar/dock); otherwise ~90% of the
 * usable area in each direction that doesn't fit, centred in it, so the title bar and its buttons
 * start on screen (a 1280×800 screen would otherwise hide the close button). Null [usable]: unknown,
 * keep the default.
 */
internal fun initialWindow(
    usable: Rectangle?,
    wantWidth: Int = DEFAULT_WINDOW_WIDTH,
    wantHeight: Int = DEFAULT_WINDOW_HEIGHT,
): InitialWindow {
    if (usable == null || usable.width <= 0 || usable.height <= 0) return InitialWindow(wantWidth, wantHeight)
    if (wantWidth <= usable.width && wantHeight <= usable.height) return InitialWindow(wantWidth, wantHeight)
    val w = minOf(wantWidth, (usable.width * 0.9).roundToInt())
    val h = minOf(wantHeight, (usable.height * 0.9).roundToInt())
    return InitialWindow(w, h, usable.x + (usable.width - w) / 2, usable.y + (usable.height - h) / 2)
}

/** The primary screen's usable bounds (AWT user space, which is dp on HiDPI JREs), or null when headless. */
internal fun usableScreenBounds(): Rectangle? =
    runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
