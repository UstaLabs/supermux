package dev.supermux.webview.runtime

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A Compose overlay drawn over web views (a menu, a dialog, a bottom sheet), in window pixels. Every corner has its
 * own radius, so a bottom sheet keeps its square bottom corners.
 */
data class OverlayShape(val shape: RoundRect) {
    constructor(bounds: Rect, cornerRadius: CornerRadius = CornerRadius.Zero) : this(RoundRect(bounds, cornerRadius))

    val bounds: Rect get() = shape.boundingRect
}

/** A modal scrim over web views, in window pixels. The page dims under it instead of disappearing. */
data class ScrimShape(val bounds: Rect, val color: Color)

/**
 * What a page that sits above Compose does to itself so Compose overlays show (spec §6). Everything here is in window
 * pixels; only [toEvenOddPath] is in the page's own coordinates.
 * - It cuts away everything outside [visibleBounds]. When that's empty (a page not laid out yet, or scrolled away),
 *   the whole page hides.
 * - It cuts every hole. Holes are pairwise disjoint, so even-odd filling cuts each one. Together they cover the
 *   overlays over the visible area exactly, except at corners that turned square (on a clipped edge, or in a group of
 *   overlapping overlays cut as rects), where they cut a little more, never less.
 * - Holes can be drawn directly, without the path: the macOS engine builds its `CAShapeLayer` mask from [holes],
 *   since CoreGraphics has no SVG parser. Their coordinates are finite, each has area, and their radii are fitted:
 *   each corner is square ([CornerRadius.Zero]) or positive on both axes, and on every side the two radii add up to
 *   at most its length.
 * - It draws [scrims] over itself in order, each within its bounds, so stacked scrims compound as they do in
 *   Compose. Like the holes, scrims are in window pixels: subtract [pageBounds]' top-left to draw one on the page.
 */
data class OverlayCutouts(
    val pageBounds: Rect,
    val visibleBounds: Rect,
    val holes: List<OverlayShape>,
    val scrims: List<ScrimShape>,
) {
    /** Nothing to do: the page is fully visible, has area, and has nothing over it and no scrim. */
    val isNone: Boolean
        get() = holes.isEmpty() && scrims.isEmpty() && visibleBounds.isFinite && !visibleBounds.isEmpty &&
            visibleBounds == pageBounds

    /**
     * The visible area minus the holes, as an even-odd SVG path in the page's own coordinates (CSS `clip-path`).
     * When nothing is visible it is an empty rectangle, which hides the whole page.
     */
    fun toEvenOddPath(): String {
        val dx = -pageBounds.left
        val dy = -pageBounds.top
        val visible = visibleBounds
        if (visible.isEmpty || !visible.isFinite || !dx.isFinite() || !dy.isFinite()) return HIDE_ALL
        return buildString {
            appendRect(visible.left + dx, visible.top + dy, visible.right + dx, visible.bottom + dy)
            for (hole in holes) appendHole(hole.shape, dx, dy)
        }
    }

    /** The same cut-outs in another unit, e.g. CSS pixels: `scaled(1 / density)`. [factor] is finite and above 0. */
    fun scaled(factor: Float): OverlayCutouts {
        require(factor.isFinite() && factor > 0f) { "A scale factor is finite and above 0, not $factor" }
        return OverlayCutouts(
            pageBounds.scaledBy(factor),
            visibleBounds.scaledBy(factor),
            holes.map { OverlayShape(it.shape.scaledBy(factor)) },
            scrims.map { ScrimShape(it.bounds.scaledBy(factor), it.color) },
        )
    }

    companion object {
        /** No page and nothing visible: a page given these hides everything, as one not laid out yet should. */
        val Hidden: OverlayCutouts = OverlayCutouts(Rect.Zero, Rect.Zero, emptyList(), emptyList())
    }
}

/**
 * Cut-outs for a page at [pageBounds], visible within [visibleBounds], under [overlays] and [scrims] (in paint order),
 * all in window pixels:
 * - overlays and scrims that aren't finite or have no area are ignored;
 * - a page or visible rect that isn't finite leaves nothing visible, so the page hides instead of covering the app.
 *
 * Worst case, n overlays cost O(n²), and a group of k overlapping ones that has to be cut as rects O(k²), in time and
 * in holes. A window shows a handful of overlays, so a frame's recomputation stays in microseconds.
 */
fun computeCutouts(
    pageBounds: Rect,
    visibleBounds: Rect,
    overlays: Collection<OverlayShape>,
    scrims: List<ScrimShape>,
): OverlayCutouts {
    val visible = (if (visibleBounds.isFinite) pageBounds.clippedTo(visibleBounds) else null) ?: Rect.Zero
    if (visible.isEmpty) return OverlayCutouts(pageBounds, Rect.Zero, emptyList(), emptyList())
    val clipped = ArrayList<OverlayShape>(overlays.size)
    for (overlay in overlays) {
        val shape = overlay.shape.clippedTo(visible) ?: continue
        clipped += if (shape === overlay.shape) overlay else OverlayShape(shape)
    }
    val shownScrims = ArrayList<ScrimShape>(scrims.size)
    for (scrim in scrims) {
        val bounds = scrim.bounds.clippedTo(visible) ?: continue
        shownScrims += if (bounds === scrim.bounds) scrim else ScrimShape(bounds, scrim.color)
    }
    return OverlayCutouts(pageBounds, visible, disjointHoles(clipped), shownScrims)
}

/**
 * Holes for [shapes] (already clipped to the visible area) that never overlap, so even-odd filling cuts every one, and
 * that cover exactly the shapes' union, except the rounded corners of a group merged into rects:
 * - shapes whose bounds overlap form groups (touching ones stay apart), in the order of each group's first shape;
 * - a group of one is its own hole;
 * - a group whose largest shape holds all the others clear of its rounded corners is that shape, radii kept (a copy of
 *   it, an overlay registered twice, doesn't count);
 * - any other group becomes the disjoint rects of its union.
 */
internal fun disjointHoles(shapes: List<OverlayShape>): List<OverlayShape> {
    val count = shapes.size
    if (count < 2) return shapes
    val group = IntArray(count) { -1 }
    val pending = IntArray(count)
    var groups = 0
    for (first in 0 until count) {
        if (group[first] >= 0) continue
        group[first] = groups
        var size = 0
        pending[size++] = first
        while (size > 0) {
            val shape = shapes[pending[--size]].shape
            for (other in 0 until count) {
                if (group[other] < 0 && shape.overlaps(shapes[other].shape)) {
                    group[other] = groups
                    pending[size++] = other
                }
            }
        }
        groups++
    }
    if (groups == count) return shapes
    val holes = ArrayList<OverlayShape>(count)
    val members = IntArray(count)
    for (g in 0 until groups) {
        var size = 0
        for (i in 0 until count) if (group[i] == g) members[size++] = i
        if (size == 1) {
            holes += shapes[members[0]]
            continue
        }
        val container = containerOf(shapes, members, size)
        if (container >= 0) holes += shapes[container] else addUnionAsRects(shapes, members, size, holes)
    }
    return holes
}

/**
 * The group member whose rect holds all the others, none of them reaching into its corner boxes; or -1. A copy of the
 * container (an overlay registered twice) doesn't count against it.
 */
private fun containerOf(shapes: List<OverlayShape>, members: IntArray, size: Int): Int {
    candidates@ for (i in 0 until size) {
        val container = shapes[members[i]].shape
        for (j in 0 until size) {
            if (j == i) continue
            val other = shapes[members[j]].shape
            if (other == container) continue
            val inside = other.left >= container.left && other.top >= container.top &&
                other.right <= container.right && other.bottom <= container.bottom
            if (!inside || other.reachesACornerOf(container)) continue@candidates
        }
        return members[i]
    }
    return -1
}

/** Whether this overlaps one of [container]'s corner boxes: the radius-sized squares its rounded corners cut into. */
private fun RoundRect.reachesACornerOf(container: RoundRect): Boolean {
    val c = container
    val tl = c.topLeftCornerRadius
    val tr = c.topRightCornerRadius
    val br = c.bottomRightCornerRadius
    val bl = c.bottomLeftCornerRadius
    return overlaps(c.left, c.top, c.left + tl.x, c.top + tl.y) ||
        overlaps(c.right - tr.x, c.top, c.right, c.top + tr.y) ||
        overlaps(c.right - br.x, c.bottom - br.y, c.right, c.bottom) ||
        overlaps(c.left, c.bottom - bl.y, c.left + bl.x, c.bottom)
}

/**
 * Adds the union of the members' rects to [out] as disjoint rects, by a slab sweep: between each two consecutive
 * distinct y edges, the x-intervals of the rects spanning that slab merge, and a slab whose intervals match the slab
 * above extends it. Coordinates are only compared and copied, never computed, so the rects tile the union exactly.
 */
private fun addUnionAsRects(shapes: List<OverlayShape>, members: IntArray, size: Int, out: MutableList<OverlayShape>) {
    // By left edge, so each slab's intervals merge in one pass. Groups are small: insertion sort.
    for (i in 1 until size) {
        val member = members[i]
        val left = shapes[member].shape.left
        var j = i - 1
        while (j >= 0 && shapes[members[j]].shape.left > left) {
            members[j + 1] = members[j]
            j--
        }
        members[j + 1] = member
    }
    val ys = FloatArray(2 * size)
    for (i in 0 until size) {
        val shape = shapes[members[i]].shape
        ys[2 * i] = shape.top
        ys[2 * i + 1] = shape.bottom
    }
    ys.sort()
    var edges = 0
    for (i in ys.indices) if (edges == 0 || ys[i] != ys[edges - 1]) ys[edges++] = ys[i]

    // Intervals as [left0, right0, left1, right1, …]: the open run of slabs ("above"), and the slab being swept.
    var above = FloatArray(2 * size)
    var aboveLength = 0
    var aboveTop = 0f
    var aboveBottom = 0f
    var slab = FloatArray(2 * size)
    for (e in 0 until edges - 1) {
        val top = ys[e]
        val bottom = ys[e + 1]
        var length = 0
        for (i in 0 until size) {
            val shape = shapes[members[i]].shape
            if (shape.top > top || shape.bottom < bottom) continue
            if (length > 0 && shape.left <= slab[length - 1]) {
                if (shape.right > slab[length - 1]) slab[length - 1] = shape.right
            } else {
                slab[length++] = shape.left
                slab[length++] = shape.right
            }
        }
        if (length == aboveLength && slab.sameIntervals(above, length)) {
            aboveBottom = bottom
            continue
        }
        addRects(above, aboveLength, aboveTop, aboveBottom, out)
        val swap = above
        above = slab
        slab = swap
        aboveLength = length
        aboveTop = top
        aboveBottom = bottom
    }
    addRects(above, aboveLength, aboveTop, aboveBottom, out)
}

private fun addRects(intervals: FloatArray, length: Int, top: Float, bottom: Float, out: MutableList<OverlayShape>) {
    for (i in 0 until length step 2) out += OverlayShape(RoundRect(intervals[i], top, intervals[i + 1], bottom))
}

private fun FloatArray.sameIntervals(other: FloatArray, length: Int): Boolean {
    for (i in 0 until length) if (this[i] != other[i]) return false
    return true
}

/** The part of this rect inside [area] (this one if it's all inside), or null when there's none or it isn't finite. */
private fun Rect.clippedTo(area: Rect): Rect? {
    if (!isFinite) return null
    val l = max(left, area.left)
    val t = max(top, area.top)
    val r = min(right, area.right)
    val b = min(bottom, area.bottom)
    return when {
        !(l < r && t < b) -> null
        l == left && t == top && r == right && b == bottom -> this
        else -> Rect(l, t, r, b)
    }
}

/**
 * The part of this shape inside [area] (as drawn; this one if that's what it already is), or null when there's none
 * or it isn't finite:
 * - radii start as Skia draws them ([drawnShape]);
 * - a corner keeps its radius while both of its edges survive; corners on a cut edge turn square;
 * - radii that no longer fit the clipped shape shrink again. A smaller radius only makes the hole cover more.
 */
private fun RoundRect.clippedTo(area: Rect): RoundRect? {
    if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())) return null
    val l = max(left, area.left)
    val t = max(top, area.top)
    val r = min(right, area.right)
    val b = min(bottom, area.bottom)
    if (!(l < r && t < b)) return null
    val drawn = asDrawn()
    if (l == left && t == top && r == right && b == bottom) return drawn
    val cutLeft = l > left
    val cutTop = t > top
    val cutRight = r < right
    val cutBottom = b < bottom
    return drawnShape(
        l, t, r, b,
        if (cutLeft || cutTop) CornerRadius.Zero else drawn.topLeftCornerRadius,
        if (cutRight || cutTop) CornerRadius.Zero else drawn.topRightCornerRadius,
        if (cutRight || cutBottom) CornerRadius.Zero else drawn.bottomRightCornerRadius,
        if (cutLeft || cutBottom) CornerRadius.Zero else drawn.bottomLeftCornerRadius,
    )
}

/** This shape as Skia draws it ([drawnShape]); this one if it already is. */
private fun RoundRect.asDrawn(): RoundRect = drawnShape(
    left, top, right, bottom,
    topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius,
    reuse = this,
)

/**
 * A shape with these bounds and radii as Skia draws it (`SkRRect::setRectRadii`), which is what a hole must cover:
 * - a radius that isn't finite, on any corner, makes it a plain rect;
 * - a corner without a positive radius on both axes is square;
 * - radii too big for their sides shrink together, by the CSS rule ([fitFactor]). Floats can then leave two radii
 *   an ulp longer than their side, so the larger one steps down until they fit, as Skia's do.
 *
 * Returns [reuse] when it's already that shape.
 */
private fun drawnShape(
    l: Float,
    t: Float,
    r: Float,
    b: Float,
    topLeft: CornerRadius,
    topRight: CornerRadius,
    bottomRight: CornerRadius,
    bottomLeft: CornerRadius,
    reuse: RoundRect? = null,
): RoundRect {
    var tl = CornerRadius.Zero
    var tr = CornerRadius.Zero
    var br = CornerRadius.Zero
    var bl = CornerRadius.Zero
    if (topLeft.isFinite() && topRight.isFinite() && bottomRight.isFinite() && bottomLeft.isFinite()) {
        tl = topLeft.orSquare()
        tr = topRight.orSquare()
        br = bottomRight.orSquare()
        bl = bottomLeft.orSquare()
        val width = r - l
        val height = b - t
        val f = fitFactor(width, height, tl, tr, br, bl)
        if (f < 1f) {
            tl *= f
            tr *= f
            br *= f
            bl *= f
            if (tl.x + tr.x > width) {
                if (tl.x >= tr.x) tl = tl.copy(x = shortened(tl.x, tr.x, width)) else tr = tr.copy(x = shortened(tr.x, tl.x, width))
            }
            if (bl.x + br.x > width) {
                if (bl.x >= br.x) bl = bl.copy(x = shortened(bl.x, br.x, width)) else br = br.copy(x = shortened(br.x, bl.x, width))
            }
            if (tl.y + bl.y > height) {
                if (tl.y >= bl.y) tl = tl.copy(y = shortened(tl.y, bl.y, height)) else bl = bl.copy(y = shortened(bl.y, tl.y, height))
            }
            if (tr.y + br.y > height) {
                if (tr.y >= br.y) tr = tr.copy(y = shortened(tr.y, br.y, height)) else br = br.copy(y = shortened(br.y, tr.y, height))
            }
            // Scaling or stepping can take an axis to 0 (underflow), which makes that corner square.
            tl = tl.orSquare()
            tr = tr.orSquare()
            br = br.orSquare()
            bl = bl.orSquare()
        }
    }
    if (reuse != null && reuse.left == l && reuse.top == t && reuse.right == r && reuse.bottom == b &&
        reuse.topLeftCornerRadius == tl && reuse.topRightCornerRadius == tr &&
        reuse.bottomRightCornerRadius == br && reuse.bottomLeftCornerRadius == bl
    ) {
        return reuse
    }
    return RoundRect(l, t, r, b, tl, tr, br, bl)
}

private fun CornerRadius.isFinite(): Boolean = x.isFinite() && y.isFinite()

/** A corner is round only with a positive radius on both axes. */
private fun CornerRadius.orSquare(): CornerRadius = if (x > 0f && y > 0f) this else CornerRadius.Zero

/**
 * CSS's rule for radii that don't fit (css-backgrounds-3 §5.5), computed as Compose computes it: when two radii on one
 * side add up to more than that side, every radius shrinks by the same factor, the smallest side length over radius
 * sum.
 */
private fun fitFactor(width: Float, height: Float, tl: CornerRadius, tr: CornerRadius, br: CornerRadius, bl: CornerRadius): Float {
    var f = 1f
    f = shrunk(f, width, tl.x + tr.x)
    f = shrunk(f, width, bl.x + br.x)
    f = shrunk(f, height, tl.y + bl.y)
    f = shrunk(f, height, tr.y + br.y)
    return f
}

private fun shrunk(f: Float, side: Float, radii: Float): Float = if (radii > side) min(f, side / radii) else f

/** [larger], an ulp at a time, down to where it and [smaller] fit in [side]. Rounding leaves it a step or two away. */
private fun shortened(larger: Float, smaller: Float, side: Float): Float {
    var radius = larger
    repeat(64) {
        if (radius + smaller <= side || radius <= 0f) return radius
        radius = Float.fromBits(radius.toRawBits() - 1)
    }
    return 0f
}

/** Strict: rects that only touch don't overlap. */
private fun RoundRect.overlaps(other: RoundRect): Boolean = overlaps(other.left, other.top, other.right, other.bottom)

private fun RoundRect.overlaps(l: Float, t: Float, r: Float, b: Float): Boolean =
    left < r && l < right && top < b && t < bottom

private fun Rect.scaledBy(f: Float) = Rect(left * f, top * f, right * f, bottom * f)

private fun RoundRect.scaledBy(f: Float) = RoundRect(
    left * f, top * f, right * f, bottom * f,
    topLeftCornerRadius * f, topRightCornerRadius * f, bottomRightCornerRadius * f, bottomLeftCornerRadius * f,
)

/** An empty rectangle: a clip that hides everything. */
private const val HIDE_ALL = "M0 0H0V0H0Z"

private fun StringBuilder.appendRect(l: Float, t: Float, r: Float, b: Float) {
    append('M').num(l).append(' ').num(t).append('H').num(r).append('V').num(b).append('H').num(l).append('Z')
}

/** One hole, moved by ([dx], [dy]), every corner with its own radius (as drawn, in case it was built by hand). */
private fun StringBuilder.appendHole(shape: RoundRect, dx: Float, dy: Float) {
    val drawn = shape.asDrawn()
    val tl = drawn.topLeftCornerRadius.writable()
    val tr = drawn.topRightCornerRadius.writable()
    val br = drawn.bottomRightCornerRadius.writable()
    val bl = drawn.bottomLeftCornerRadius.writable()
    val l = shape.left + dx
    val t = shape.top + dy
    val r = shape.right + dx
    val b = shape.bottom + dy
    append('M').num(l + tl.x).append(' ').num(t)
    append('H').num(r - tr.x)
    arc(tr, r, t + tr.y)
    append('V').num(b - br.y)
    arc(br, r - br.x, b)
    append('H').num(l + bl.x)
    arc(bl, l, b - bl.y)
    if (tl != CornerRadius.Zero) {
        append('V').num(t + tl.y)
        arc(tl, l + tl.x, t)
    }
    append('Z')
}

/** Square when the radius is below 0.005 on either axis: written with two decimals, it would be an `A0 0` arc. */
private fun CornerRadius.writable(): CornerRadius =
    if (x.toDouble() < 0.005 || y.toDouble() < 0.005) CornerRadius.Zero else this

/** A clockwise quarter arc of [radius] to ([x], [y]); nothing for a square corner. */
private fun StringBuilder.arc(radius: CornerRadius, x: Float, y: Float) {
    if (radius == CornerRadius.Zero) return
    append('A').num(radius.x).append(' ').num(radius.y).append(" 0 0 1 ").num(x).append(' ').num(y)
}

/** At most two decimals, never an exponent, never "-0". */
internal fun n(v: Float): String = StringBuilder().num(v).toString()

private fun StringBuilder.num(v: Float): StringBuilder {
    val hundredths = if (v.isNaN()) 0L else (v.toDouble() * 100).roundToLong()
    if (hundredths == 0L) return append('0')
    if (hundredths < 0L) append('-')
    val magnitude = if (hundredths == Long.MIN_VALUE) Long.MAX_VALUE else abs(hundredths)
    append(magnitude / 100)
    val cents = (magnitude % 100).toInt()
    if (cents != 0) {
        append('.').append('0' + cents / 10)
        if (cents % 10 != 0) append('0' + cents % 10)
    }
    return this
}
