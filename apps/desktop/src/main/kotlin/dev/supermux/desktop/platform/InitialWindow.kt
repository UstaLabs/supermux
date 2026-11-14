package dev.supermux.desktop.platform

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** The main window's first size (dp) and, when it had to shrink, its centred position (null: the OS places it). */
internal data class InitialWindow(val width: Int, val height: Int, val x: Int? = null, val y: Int? = null, val maximized: Boolean = false)

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

/** The main window as it was when the app last ran (dp, AWT user space), kept in ui-state.json (#134). */
@Serializable
data class SavedWindow(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean = false)

/**
 * Pure. Where the main window opens (#134, ADEMark: it forgot its size and place): [saved] as it was, when its title bar
 * lands on one of [screens] (the usable area of each), its size clamped to that screen; else [fallback]. So a window
 * last left on an unplugged display opens on one that is there.
 */
internal fun restoredWindow(saved: SavedWindow?, screens: List<Rectangle>, fallback: InitialWindow): InitialWindow {
    saved ?: return fallback
    if (saved.width <= 0 || saved.height <= 0) return fallback
    // The title bar: the top 40 dp of the window, at least 100 dp of it on a screen.
    val titleBar = Rectangle(saved.x, saved.y, saved.width, 40)
    val screen = screens.firstOrNull { s ->
        val hit = s.intersection(titleBar)
        !hit.isEmpty && hit.width >= minOf(100, saved.width) && s.contains(s.x.coerceAtLeast(saved.x), saved.y.coerceAtLeast(s.y))
    } ?: return fallback
    val w = minOf(saved.width, screen.width)
    val h = minOf(saved.height, screen.height)
    val x = saved.x.coerceIn(screen.x, screen.x + screen.width - w)
    val y = saved.y.coerceIn(screen.y, screen.y + screen.height - h)
    return InitialWindow(w, h, x, y, saved.maximized)
}

/** Every screen's usable bounds (AWT user space), or empty when headless. */
internal fun usableScreens(): List<Rectangle> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { d ->
        val c = d.defaultConfiguration
        val b = c.bounds
        val i = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(c)
        Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }
}.getOrDefault(emptyList())
