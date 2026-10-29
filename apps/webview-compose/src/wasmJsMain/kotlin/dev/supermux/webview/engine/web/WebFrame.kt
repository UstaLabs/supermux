package dev.supermux.webview.engine.web

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.supermux.webview.runtime.OverlayCutouts
import dev.supermux.webview.runtime.n
import org.w3c.dom.HTMLElement
import kotlin.math.max
import kotlin.math.roundToInt

/** The z-index of the last frame a surface showed: the newest surface goes on top. */
private var topZ = 0

/**
 * One engine's place on the page (spec §6, §7): [outer], a div in a viewport's layer, and [cut] inside it, which holds
 * the engine's iframe(s) and the scrims.
 * - [outer] has the position and size in CSS px, the `inset()` clip to what scroll containers show, `display` and
 *   `visibility`. It follows the page's layout in the same pass ([surfacePlaced]).
 * - [cut] has the overlays' holes as `clip-path: path(evenodd, …)` against the whole page ([applyCutouts]), the
 *   background, the colour scheme, and `pointer-events: none` while a scrim covers the page.
 * - Only [cut] takes hits: [outer] is `pointer-events: none`, and [cut] `auto` or `none`, always written, never
 *   removed. The holes clip [cut] and what it holds, not [outer], and a transparent box still takes hits: an [outer]
 *   that took them would fill every hole, and a [cut] without its own value would inherit [outer]'s `none` and never
 *   take one (the feasibility test's G2).
 * - The frame enters a layer once and never leaves it, because an iframe that moves reloads (plan fact F4). A surface
 *   in another viewport, or a layer gone from the page, calls [onBroken].
 * - It is `display: none` while no surface shows it, or while the engine is told it is hidden ([shown]). It is
 *   `visibility: hidden` while [covered] (the runtime's cover on the canvas must show), while none of it is visible, or
 *   while [CutoutMode.HidePage] hides it under an overlay.
 * - Only the surface that attached last moves it, so a surface that is late to leave can't fight the new one.
 */
internal class WebFrame {
    val outer: HTMLElement = newElement("div")
    val cut: HTMLElement = newElement("div")

    /** Called when the frame can't be shown where its surface is: another viewport, or a page that is gone. */
    var onBroken: (() -> Unit)? = null

    /** Called each time the frame starts being displayed: the engine reports a first paint only then. */
    var onDisplayed: (() -> Unit)? = null

    /** Whether the frame is displayed now: in a layer, placed by a surface, and not hidden by `setVisible(false)`. */
    var displayed: Boolean = false
        private set

    /** The density of the last placement, which turns window px into CSS px. */
    var density: Float = 1f
        private set

    /** While true, the frame is laid out but hidden: a document the runtime started hasn't painted yet. */
    var covered: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    /** What the engine was told by `setVisible`. */
    var shown: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    private var layer: HTMLElement? = null
    private var owner: Any? = null
    private var placed = false
    private var visibleArea = false
    private var hiddenByOverlay = false
    private var hasOverlays = false
    private var removed = false
    private val scrims = ArrayList<HTMLElement>()
    private val last = FloatArray(8) { Float.NaN }

    init {
        attribute(outer, "data-webview-frame", "")
        styleOf(outer, "position", "absolute")
        styleOf(outer, "left", "0px")
        styleOf(outer, "top", "0px")
        styleOf(outer, "width", "0px")
        styleOf(outer, "height", "0px")
        styleOf(outer, "display", "none")
        styleOf(outer, "pointer-events", "none")
        styleOf(cut, "pointer-events", "auto")
        styleOf(cut, "position", "absolute")
        styleOf(cut, "left", "0px")
        styleOf(cut, "top", "0px")
        styleOf(cut, "width", "100%")
        styleOf(cut, "height", "100%")
        styleOf(cut, "overflow", "hidden")
        addChild(outer, cut)
    }

    /** A surface entered the composition: it is the one that places the frame now. */
    fun surfaceAttached(node: Any) {
        if (removed) return
        owner = node
        placed = false
        styleOf(outer, "z-index", (++topZ).toString())
        refresh()
    }

    /** A surface left the composition. If it was the one placing the frame, the frame hides. */
    fun surfaceDetached(node: Any) {
        if (owner !== node) return
        owner = null
        placed = false
        refresh()
    }

    /** The surface is composed but not placed (a parent skips it). The frame hides, and stays where it is in the page. */
    fun surfaceUnplaced(node: Any) {
        if (owner !== node) return
        placed = false
        refresh()
    }

    /**
     * The surface [node] was laid out at [position] with [size], of which [visible] shows, all in window px, in the
     * viewport whose layer is [layer] (null until found).
     */
    fun surfacePlaced(node: Any, layer: HTMLElement?, position: Offset, size: IntSize, visible: Rect, density: Float) {
        if (owner !== node || removed) return
        if (layer != null && !attach(layer)) {
            onBroken?.invoke()
            return
        }
        if (this.layer == null) return
        if (!connected(outer)) {
            onBroken?.invoke()
            return
        }
        this.density = density
        val d = density
        val left = position.x / d
        val top = position.y / d
        val width = size.width / d
        val height = size.height / d
        val clipTop = max(0f, visible.top - position.y) / d
        val clipRight = max(0f, position.x + size.width - visible.right) / d
        val clipBottom = max(0f, position.y + size.height - visible.bottom) / d
        val clipLeft = max(0f, visible.left - position.x) / d
        if (left != last[0] || top != last[1] || width != last[2] || height != last[3] ||
            clipTop != last[4] || clipRight != last[5] || clipBottom != last[6] || clipLeft != last[7]
        ) {
            last[0] = left
            last[1] = top
            last[2] = width
            last[3] = height
            last[4] = clipTop
            last[5] = clipRight
            last[6] = clipBottom
            last[7] = clipLeft
            styleOf(outer, "left", px(left))
            styleOf(outer, "top", px(top))
            styleOf(outer, "width", px(width))
            styleOf(outer, "height", px(height))
            styleOf(outer, "clip-path", "inset(${px(clipTop)} ${px(clipRight)} ${px(clipBottom)} ${px(clipLeft)})")
        }
        val area = visible.width >= 1f && visible.height >= 1f && size.width > 0 && size.height > 0
        if (!placed || area != visibleArea) {
            placed = true
            visibleArea = area
            refresh()
        }
    }

    /** Puts the frame in [layer], once. False when it already lives in another one, which it can never leave. */
    private fun attach(layer: HTMLElement): Boolean {
        val current = this.layer
        if (current != null) return same(current, layer)
        this.layer = layer
        addChild(layer, outer)
        return true
    }

    /** What `prefers-color-scheme` the page's frame asks for, and whether its background shows through. */
    fun setTheme(dark: Boolean, transparent: Boolean) {
        styleOf(cut, "color-scheme", if (dark) "dark" else "light")
        styleOf(cut, "background", if (transparent) "transparent" else "Canvas")
    }

    /** Adds [iframe] under the scrims, and under [below] (an older iframe still holding paint) when given. */
    fun addIframe(iframe: HTMLElement, below: HTMLElement?) {
        insertChild(cut, iframe, below ?: scrims.firstOrNull())
    }

    /**
     * Cuts the overlays of [cutouts] (window px) out of the page, and shows its scrims.
     * - [CutoutMode.ClipPath]: an even-odd path against the whole page. The visible rect is already clipped on
     *   [outer], so a hole that lags a frame never reveals or hides the page's edges.
     * - [CutoutMode.HidePage]: a page any overlay touches hides.
     * - Under a scrim, a div of its colour dims the page, and the page takes no input.
     */
    fun applyCutouts(cutouts: OverlayCutouts, mode: CutoutMode) {
        if (removed) return
        val css = cutouts.scaled(1f / density)
        // A page with no overlay over it, as it scrolls: the runtime sends new cut-outs each time its visible part
        // changes, and there is nothing to write.
        val any = css.holes.isNotEmpty() || css.scrims.isNotEmpty()
        if (!any && !hasOverlays) return
        hasOverlays = any
        when (mode) {
            CutoutMode.ClipPath -> {
                hiddenByOverlay = false
                if (css.holes.isEmpty()) {
                    unstyle(cut, "clip-path")
                } else {
                    val path = OverlayCutouts(css.pageBounds, css.pageBounds, css.holes, emptyList()).toEvenOddPath()
                    styleOf(cut, "clip-path", "path(evenodd, '$path')")
                }
            }
            CutoutMode.HidePage -> {
                unstyle(cut, "clip-path")
                hiddenByOverlay = css.holes.isNotEmpty()
            }
        }
        showScrims(css)
        refresh()
    }

    private fun showScrims(css: OverlayCutouts) {
        scrims.forEach(::removeElement)
        scrims.clear()
        for (scrim in css.scrims) {
            val div = newElement("div")
            styleOf(div, "position", "absolute")
            styleOf(div, "left", px(scrim.bounds.left - css.pageBounds.left))
            styleOf(div, "top", px(scrim.bounds.top - css.pageBounds.top))
            styleOf(div, "width", px(scrim.bounds.width))
            styleOf(div, "height", px(scrim.bounds.height))
            styleOf(div, "pointer-events", "none")
            styleOf(div, "background", rgba(scrim.color))
            addChild(cut, div)
            scrims += div
        }
        styleOf(cut, "pointer-events", if (scrims.isEmpty()) "auto" else "none")
    }

    /** No holes, no scrims. */
    fun clearOverlays() {
        unstyle(cut, "clip-path")
        hiddenByOverlay = false
        hasOverlays = false
        scrims.forEach(::removeElement)
        scrims.clear()
        styleOf(cut, "pointer-events", "auto")
        refresh()
    }

    /** Back to how a new engine's frame is, minus its place in the page, which it keeps. */
    fun reset() {
        clearOverlays()
        covered = true
        shown = true
    }

    /** Leaves the page for good. */
    fun remove() {
        removed = true
        owner = null
        scrims.clear()
        removeElement(outer)
    }

    private fun refresh() {
        if (removed) return
        val nowDisplayed = layer != null && owner != null && placed && shown
        styleOf(outer, "display", if (nowDisplayed) "block" else "none")
        val visible = nowDisplayed && visibleArea && !covered && !hiddenByOverlay
        styleOf(outer, "visibility", if (visible) "visible" else "hidden")
        val became = nowDisplayed && !displayed
        displayed = nowDisplayed
        if (became) onDisplayed?.invoke()
    }

    private fun px(value: Float): String = n(value) + "px"

    private fun rgba(color: Color): String =
        "rgba(${(color.red * 255).roundToInt()}, ${(color.green * 255).roundToInt()}, ${(color.blue * 255).roundToInt()}, ${color.alpha})"
}
