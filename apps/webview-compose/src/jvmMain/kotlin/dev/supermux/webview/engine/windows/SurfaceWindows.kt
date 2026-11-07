package dev.supermux.webview.engine.windows

import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.util.WeakHashMap

/**
 * Where a surface is, for WebView2: its window's frame (WebView2's parent, D1), the Compose root's heavyweight canvas
 * (the surface's pixels are its client pixels: W3), and the window's scale (never `LocalDensity`, L26). Supports
 * `ComposeWindow` and `ComposeDialog`, whose content is one skiko canvas; Compose inside a Swing frame (`ComposePanel`)
 * is a README limit.
 */
internal interface SurfaceWindows {
    class Placement(val frame: Long, val canvas: Long, val scale: Double)

    fun of(window: Window?): Placement?
    fun canvasComponent(window: Window?): Component?
}

internal object AwtSurfaceWindows : SurfaceWindows {
    private val placements = WeakHashMap<Window, Pair<Long, Long>>()

    override fun of(window: Window?): SurfaceWindows.Placement? {
        if (window == null || !window.isDisplayable) return null
        val (frame, canvas) = placements.getOrPut(window) {
            val component = canvasComponent(window) ?: return null
            WinGlue.hwndOf(window) to WinGlue.hwndOf(component)
        }
        if (frame == 0L || canvas == 0L) return null
        val scale = window.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0.0 } ?: 1.0
        return SurfaceWindows.Placement(frame, canvas, scale)
    }

    /** The skiko canvas under the window: the first heavyweight `Canvas` in its tree (the test's findCanvas). */
    override fun canvasComponent(window: Window?): Component? = window?.let(::findCanvas)

    private fun findCanvas(root: Component): Component? {
        if (root is Canvas) return root
        if (root is Container) for (child in root.components) findCanvas(child)?.let { return it }
        return null
    }
}

/** A Compose pointer event as `SendMouseInput`'s (D16), in the surface's pixels (the WebView's own, W1). Pure. */
internal object MouseInput {
    enum class Kind { Enter, Move, Exit, Press, Release, Scroll }

    data class Send(val kind: Int, val keys: Int, val data: Int, val x: Int, val y: Int)

    /** [button]: AWT's (1 left, 2 middle, 3 right); [clicks]: AWT's click count; [dx]/[dy]: Compose's scroll delta. */
    fun of(kind: Kind, button: Int, clicks: Int, x: Float, y: Float, dx: Float, dy: Float,
           primary: Boolean, secondary: Boolean, middle: Boolean, shift: Boolean, ctrl: Boolean): Send {
        val keys = (if (primary) WinGlue.MK_LBUTTON else 0) or (if (secondary) WinGlue.MK_RBUTTON else 0) or
            (if (middle) WinGlue.MK_MBUTTON else 0) or (if (shift) WinGlue.MK_SHIFT else 0) or (if (ctrl) WinGlue.MK_CONTROL else 0)
        val px = kotlin.math.round(x).toInt()
        val py = kotlin.math.round(y).toInt()
        return when (kind) {
            Kind.Enter, Kind.Move -> Send(WinGlue.MOUSE_MOVE, keys, 0, px, py)
            Kind.Exit -> Send(WinGlue.MOUSE_LEAVE, 0, 0, 0, 0)
            Kind.Press -> Send(when (button) {
                3 -> if (clicks == 2) WinGlue.RIGHT_DOUBLE else WinGlue.RIGHT_DOWN
                2 -> if (clicks == 2) WinGlue.MIDDLE_DOUBLE else WinGlue.MIDDLE_DOWN
                else -> if (clicks == 2) WinGlue.LEFT_DOUBLE else WinGlue.LEFT_DOWN
            }, keys, 0, px, py)
            Kind.Release -> Send(when (button) {
                3 -> WinGlue.RIGHT_UP
                2 -> WinGlue.MIDDLE_UP
                else -> WinGlue.LEFT_UP
            }, keys, 0, px, py)
            Kind.Scroll -> if (dx != 0f && dy == 0f) Send(WinGlue.HORIZONTAL_WHEEL, keys, kotlin.math.round(dx * 120).toInt(), px, py)
                else Send(WinGlue.WHEEL, keys, kotlin.math.round(-dy * 120).toInt(), px, py)
        }
    }
}
