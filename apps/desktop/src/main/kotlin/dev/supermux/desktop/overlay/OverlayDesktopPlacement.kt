// Where the desktop overlay's chat card opens (#62). The head's own rest and snap rules are the shared
// CompanionPlacement's, fed each screen's usable area.
package dev.supermux.desktop.overlay

import dev.supermux.ui.overlay.OverlayScreen
import dev.supermux.ui.overlay.Point

/**
 * The chat card beside the head, toward the middle of the screen, its bottom level with the head's;
 * kept clear of the menu bar and the Dock, and below the menu bar when it can't fit at all. Screen-local px.
 */
fun chatCardSpot(headX: Float, headY: Float, headSize: Float, cardW: Float, cardH: Float, screen: OverlayScreen, gap: Float): Point {
    val left = headX + headSize / 2 < screen.width / 2
    val x = if (left) headX + headSize + gap else headX - gap - cardW
    val top = screen.topInset + gap
    val bottom = screen.height - screen.bottomInset - cardH - gap
    val y = maxOf(top, minOf(headY + headSize - cardH, bottom))
    return Point(maxOf(gap, minOf(x, screen.width - cardW - gap)), y)
}
