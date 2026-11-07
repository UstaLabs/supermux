// Where the assistant overlay may sit on a phone's screen: on either edge, anywhere between the status
// bar and the line where keyboards open. Above that line it never covers a keyboard, an app's bottom
// bar or the navigation bar of whatever app is in front. Pure math, in px, shared by every platform.
package dev.supermux.ui.overlay

import kotlin.math.hypot

/** The screen the overlay lives on, in px. [keyboardHeight] is how high keyboards usually reach. */
data class OverlayScreen(val width: Float, val height: Float, val topInset: Float, val bottomInset: Float, val keyboardHeight: Float)

enum class Edge { Left, Right }

/** Where the user left it: an edge and the companion's top. */
data class Spot(val edge: Edge, val y: Float)

data class Point(val x: Float, val y: Float)

object CompanionPlacement {
    /** The companion's top-left corner; with no [spot] it rests on the left edge, just above the keyboard line. */
    fun rest(screen: OverlayScreen, size: Float, margin: Float, spot: Spot?): Point {
        val (top, bottom) = band(screen, size, margin)
        val edge = spot?.edge ?: Edge.Left
        val y = (spot?.y ?: bottom).coerceIn(top, bottom)
        return Point(if (edge == Edge.Left) margin else screen.width - size - margin, y)
    }

    /** Where a dragged companion lands: the nearest edge, at the height it was dropped. */
    fun snap(screen: OverlayScreen, size: Float, margin: Float, x: Float, y: Float): Spot {
        val (top, bottom) = band(screen, size, margin)
        val edge = if (x + size / 2 < screen.width / 2) Edge.Left else Edge.Right
        return Spot(edge, y.coerceIn(top, bottom))
    }

    /** The ✕ that hides it while you drag: in the middle, [offset] above the navigation bar. */
    fun dismissCenter(screen: OverlayScreen, offset: Float): Pair<Float, Float> =
        screen.width / 2 to screen.height - screen.bottomInset - offset

    fun overDismiss(screen: OverlayScreen, cx: Float, cy: Float, offset: Float, radius: Float): Boolean {
        val (tx, ty) = dismissCenter(screen, offset)
        return hypot(cx - tx, cy - ty) <= radius
    }

    private fun band(screen: OverlayScreen, size: Float, margin: Float): Pair<Float, Float> {
        val top = screen.topInset + margin
        val bottom = screen.height - screen.bottomInset - screen.keyboardHeight - size - margin
        return top to maxOf(top, bottom)
    }
}
