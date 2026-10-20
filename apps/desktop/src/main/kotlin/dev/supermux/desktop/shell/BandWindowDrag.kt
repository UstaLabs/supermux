// The top band moves the window on every screen, on every OS with our own chrome.
//
// The registered drag regions (empty chrome: the sidebar band, tab-strip tails, the onboarding
// band) get the platform's NATIVE move — JBR's title-bar hit test on macOS and Windows, a WM move
// on Linux — with double-click zoom / maximise. Everywhere else in the band that is not a hole
// (a tab row, a splitter, a button: see [MacChromeRegions]) is a SOFT handle: the press still goes
// to whatever Compose content is there, so a click is a click, and only once the pointer has
// travelled past [BAND_DRAG_SLOP] does the window follow it. That is what lets a screen with no
// registered chrome at all (Settings, the onboarding wizard, an empty workspace) drag from its
// top edge like any title bar.
package dev.supermux.desktop.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import java.awt.Frame
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseEvent

/** What a press in the band grabs. */
enum class BandGrab { None, Native, Soft }

/**
 * The band's response to a primary press at [p] (Compose px) given the band [bandHeight] at this
 * monitor's [scale]: [BandGrab.Native] on a registered drag region, [BandGrab.Soft] on the rest of
 * the band unless it is a hole, else [BandGrab.None].
 */
fun bandGrab(button: Int, p: Offset, bandHeight: Dp, scale: Double, regions: MacChromeRegions): BandGrab = when {
    button != MouseEvent.BUTTON1 -> BandGrab.None
    p.y < 0f || p.y >= bandHeight.value * scale -> BandGrab.None
    regions.allowsNativeDrag(p) -> BandGrab.Native
    regions.isHole(p) -> BandGrab.None
    else -> BandGrab.Soft
}

/**
 * A soft band move by hand: the window follows the pointer's screen travel since the press. Used
 * where the platform has no "start a move now" call (macOS and Windows under JBR's title bar);
 * Linux hands the move to the WM instead (see LinuxWindowChrome.kt).
 *
 * Every step is computed from absolute screen positions, so feeding the same event twice (the
 * Windows listener sees its own re-dispatch) is harmless.
 */
class SoftWindowMove(private val window: Window) {
    private var pressScreen: Point? = null
    private var windowAtPress: Point? = null
    private var moving = false

    /** A soft-grab press at [screen] (AWT screen points). */
    fun press(screen: Point) {
        pressScreen = screen
        windowAtPress = window.location
        moving = false
    }

    /** Pointer at [screen] with the button down. True while this drag is moving the window. */
    fun drag(screen: Point): Boolean {
        val from = pressScreen ?: return false
        if (!moving) {
            if (!dragPastSlop(from.x, from.y, screen.x, screen.y)) return false
            moving = true
            unmaximise(from)
        }
        val at = windowAtPress ?: return false
        window.setLocation(at.x + screen.x - from.x, at.y + screen.y - from.y)
        return true
    }

    fun release() {
        pressScreen = null
        windowAtPress = null
        moving = false
    }

    /**
     * Dragging a maximised window restores it first, like a title bar: the restored window keeps
     * the pointer at the same fraction of its width, and the move continues from there.
     */
    private fun unmaximise(press: Point) {
        val frame = window as? Frame ?: return
        if (frame.extendedState and Frame.MAXIMIZED_BOTH == 0) return
        val before = window.bounds
        frame.extendedState = frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
        val restored = window.size
        val fx = if (before.width > 0) (press.x - before.x).toDouble() / before.width else 0.5
        val x = press.x - (restored.width * fx).toInt()
        val y = before.y
        window.setLocation(x, y)
        windowAtPress = Point(x, y)
    }
}
