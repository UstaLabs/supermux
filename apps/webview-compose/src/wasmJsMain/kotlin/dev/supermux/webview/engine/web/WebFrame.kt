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
 *   background, the colour scheme, and `pointer-events: none` while a scrim, or the runtime's cover, is over the page.
 * - Only [cut] takes hits: [outer] is `pointer-events: none`, and [cut] `auto` or `none` (while [covered], or under a
 *   scrim), always written, never removed. The holes clip [cut] and what it holds, not [outer], and a transparent box
 *   still takes hits: an [outer] that took them would fill every hole, and a [cut] without its own value would inherit
 *   [outer]'s `none` and never take one (the feasibility test's G2).
 * - The frame enters a layer once and never leaves it, because an iframe that moves reloads (plan fact F4). A surface
 *   in another viewport, or a layer gone from the page, calls [onBroken].
 * - It is `display: none` until a surface first places it. From then on it stays laid out at its last place and size,
 *   and is only `visibility: hidden` while no surface shows it (a page parked for the next view of it), while the
 *   engine is told it is hidden ([shown]), while none of it is visible, or while [CutoutMode.HidePage] hides it under
 *   an overlay. A frame out of the layout would give its page a 0×0 viewport, and the page would reflow, losing what
 *   it showed. Hidden, it takes no clicks, and the browser doesn't render its page at all: a frame in a hidden box runs
 *   no animation frame (the feasibility test's hidden-frame probe), so its first frame shown again could be one it
 *   hasn't drawn.
 * - Hidden, it keeps the place and the clip it last showed with, while its size follows its view; it takes its new
 *   place in the style update that shows it again. Chrome (156) keeps what a hidden frame drew only while its box is
 *   in the viewport: one hidden out of it (a pager's page off screen, a view bound but scrolled away) came back with a
 *   blank first frame, the box's background, where one hidden in place comes back with the page as it was.
 * - While [covered] (the runtime's cover on the canvas must show) it is `opacity: 0` instead, and takes no clicks: the
 *   page draws in its place, unseen, so its frame is ready when the cover goes.
 * - Only the surface that attached last moves it, so a surface that is late to leave can't fight the new one.
 */
internal class WebFrame {
    val outer: HTMLElement = newElement("div")
    val cut: HTMLElement = newElement("div")

    /** Called when the frame can't be shown where its surface is: another viewport, or a page that is gone. */
    var onBroken: (() -> Unit)? = null

    /** Called each time the frame starts being displayed: the engine reports a first paint only then. */
    var onDisplayed: (() -> Unit)? = null

    /** Whether a surface is composed with the frame now, placed or not. */
    val hasSurface: Boolean get() = owner != null

    /** Whether the frame is displayed now: in a layer, placed by a surface, and not hidden by `setVisible(false)`. */
    var displayed: Boolean = false
        private set

    /** The density of the last placement, which turns window px into CSS px. */
    var density: Float = 1f
        private set

    /** While true, the frame draws in its place, unseen: a document the runtime started isn't shown in it yet. */
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

    /** Whether a surface has placed the frame: from then on it stays laid out, hidden when it isn't displayed. */
    private var laidOut = false
    private var visibleArea = false
    private var hiddenByOverlay = false
    private var hasOverlays = false
    private var removed = false
    private val scrims = ArrayList<HTMLElement>()

    /** Whether the frame shows now (`visibility: visible`): only then does it take its place and clip ([place]). */
    private var showing = false

    /** What the last placement asked for, in CSS px: left, top, width, height, the clip's top, right, bottom, left. */
    private val placement = FloatArray(8) { Float.NaN }

    /** What the styles say now, in [placement]'s order. */
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
        placement[0] = position.x / d
        placement[1] = position.y / d
        placement[2] = size.width / d
        placement[3] = size.height / d
        placement[4] = max(0f, visible.top - position.y) / d
        placement[5] = max(0f, position.x + size.width - visible.right) / d
        placement[6] = max(0f, position.y + size.height - visible.bottom) / d
        placement[7] = max(0f, visible.left - position.x) / d
        val area = visible.width >= 1f && visible.height >= 1f && size.width > 0 && size.height > 0
        if (!placed || !laidOut || area != visibleArea) {
            placed = true
            laidOut = true
            visibleArea = area
            refresh()
        } else {
            place()
        }
    }

    /**
     * Writes the last placement into the styles: the size always, so the page keeps its view's viewport, shown or not;
     * the place and the clip only while the frame shows, so a hidden frame stays where it last showed (the class's
     * KDoc), and takes them as it shows again.
     */
    private fun place() {
        if (placement[0].isNaN()) return
        if (placement[2] != last[2] || placement[3] != last[3]) {
            last[2] = placement[2]
            last[3] = placement[3]
            styleOf(outer, "width", px(placement[2]))
            styleOf(outer, "height", px(placement[3]))
        }
        if (!showing) return
        if (placement[0] != last[0] || placement[1] != last[1]) {
            last[0] = placement[0]
            last[1] = placement[1]
            styleOf(outer, "left", px(placement[0]))
            styleOf(outer, "top", px(placement[1]))
        }
        if (placement[4] != last[4] || placement[5] != last[5] || placement[6] != last[6] || placement[7] != last[7]) {
            last[4] = placement[4]
            last[5] = placement[5]
            last[6] = placement[6]
            last[7] = placement[7]
            val inset = "${px(placement[4])} ${px(placement[5])} ${px(placement[6])} ${px(placement[7])}"
            styleOf(outer, "clip-path", "inset($inset)")
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

    /**
     * One div per scrim. The divs there are stay and are restyled (a property set to the value it has changes nothing),
     * so a scrim that moves as cut-outs update is the same div: only a change in their number adds or removes one.
     */
    private fun showScrims(css: OverlayCutouts) {
        val wanted = css.scrims
        while (scrims.size > wanted.size) removeElement(scrims.removeAt(scrims.lastIndex))
        while (scrims.size < wanted.size) {
            val div = newElement("div")
            styleOf(div, "position", "absolute")
            styleOf(div, "pointer-events", "none")
            addChild(cut, div)
            scrims += div
        }
        for ((i, scrim) in wanted.withIndex()) {
            val div = scrims[i]
            styleOf(div, "left", px(scrim.bounds.left - css.pageBounds.left))
            styleOf(div, "top", px(scrim.bounds.top - css.pageBounds.top))
            styleOf(div, "width", px(scrim.bounds.width))
            styleOf(div, "height", px(scrim.bounds.height))
            styleOf(div, "background", rgba(scrim.color))
        }
    }

    /** No holes, no scrims. */
    fun clearOverlays() {
        unstyle(cut, "clip-path")
        hiddenByOverlay = false
        hasOverlays = false
        scrims.forEach(::removeElement)
        scrims.clear()
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
        styleOf(outer, "display", if (nowDisplayed || (layer != null && laidOut)) "block" else "none")
        val visible = nowDisplayed && visibleArea && !hiddenByOverlay
        // Shown again, it takes its new place in this same style update; hidden, it keeps its last.
        showing = visible
        place()
        styleOf(outer, "visibility", if (visible) "visible" else "hidden")
        // Covered, it draws in its place unseen, and takes no clicks: the cover on the canvas below shows.
        styleOf(outer, "opacity", if (covered) "0" else "1")
        styleOf(cut, "pointer-events", if (covered || scrims.isNotEmpty()) "none" else "auto")
        val became = nowDisplayed && !displayed
        displayed = nowDisplayed
        if (became) onDisplayed?.invoke()
    }

    private fun px(value: Float): String = n(value) + "px"

    private fun rgba(color: Color): String =
        "rgba(${(color.red * 255).roundToInt()}, ${(color.green * 255).roundToInt()}, ${(color.blue * 255).roundToInt()}, ${color.alpha})"
}
