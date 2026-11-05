// Where the assistant overlay's head rests, remembered across restarts (#62).
package dev.supermux.desktop.overlay

import java.awt.Rectangle

/** The head's top-left ([x], [y]) on the usable part of the [screen] it was on, all in screen units. */
internal data class OverlaySpot(val x: Int, val y: Int, val screen: Rectangle) {
    fun encode(): String = listOf(x, y, screen.x, screen.y, screen.width, screen.height).joinToString(",")
}

/** The spot [OverlaySpot.encode] wrote down; null for none, or anything else. */
internal fun decodeOverlaySpot(stored: String?): OverlaySpot? {
    val n = stored?.split(",")?.map { it.trim().toIntOrNull() ?: return null } ?: return null
    if (n.size != 6) return null
    return OverlaySpot(n[0], n[1], Rectangle(n[2], n[3], n[4], n[5]))
}

/**
 * Where a head [head] units square goes now, given the [screens] there are: back on the screen it was on
 * (that same screen, or failing it whichever screen now holds the spot), kept whole on it. Null when no
 * screen holds it any more: it starts from its usual spot.
 */
internal fun restoreSpot(saved: OverlaySpot?, screens: List<Rectangle>, head: Int): Pair<Int, Int>? {
    saved ?: return null
    val screen = screens.firstOrNull { it == saved.screen } ?: screens.firstOrNull { it.contains(saved.x, saved.y) } ?: return null
    val x = saved.x.coerceIn(screen.x, maxOf(screen.x, screen.x + screen.width - head))
    val y = saved.y.coerceIn(screen.y, maxOf(screen.y, screen.y + screen.height - head))
    return x to y
}
