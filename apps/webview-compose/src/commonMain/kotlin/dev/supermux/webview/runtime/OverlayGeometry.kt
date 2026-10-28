package dev.supermux.webview.runtime

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A Compose overlay drawn over web views (a menu, a dialog), in window pixels. */
data class OverlayShape(val bounds: Rect, val cornerRadius: CornerRadius = CornerRadius.Zero)

/**
 * What a page that sits above Compose has to cut out of itself (spec §6), in window pixels:
 * - everything outside [visibleBounds];
 * - every hole (holes never overlap);
 * - and it dims when [scrim] is set.
 */
data class OverlayCutouts(
    val pageBounds: Rect,
    val visibleBounds: Rect,
    val holes: List<OverlayShape>,
    val scrim: Color?,
) {
    /** Nothing to cut: fully visible, nothing over it, no scrim. */
    val isNone: Boolean get() = holes.isEmpty() && scrim == null && visibleBounds == pageBounds

    /** The visible area minus the holes, as an even-odd SVG path in the page's own coordinates (CSS `clip-path`). */
    fun toEvenOddPath(): String = buildString {
        val dx = -pageBounds.left
        val dy = -pageBounds.top
        appendRect(visibleBounds.translate(dx, dy), CornerRadius.Zero)
        holes.forEach { appendRect(it.bounds.translate(dx, dy), it.cornerRadius) }
    }

    /** The same cut-outs in another unit, e.g. CSS pixels: `scaled(1 / density)`. */
    fun scaled(factor: Float): OverlayCutouts = OverlayCutouts(
        pageBounds.scaleBy(factor),
        visibleBounds.scaleBy(factor),
        holes.map { OverlayShape(it.bounds.scaleBy(factor), CornerRadius(it.cornerRadius.x * factor, it.cornerRadius.y * factor)) },
        scrim,
    )

    companion object {
        val None: OverlayCutouts = OverlayCutouts(Rect.Zero, Rect.Zero, emptyList(), null)
    }
}

/** Cut-outs for a page at [pageBounds], visible within [visibleBounds], under [overlays] and [scrims] (last wins). */
fun computeCutouts(
    pageBounds: Rect,
    visibleBounds: Rect,
    overlays: Collection<OverlayShape>,
    scrims: Collection<Color>,
): OverlayCutouts {
    val visible = if (visibleBounds.overlaps(pageBounds)) visibleBounds.intersect(pageBounds) else Rect.Zero
    val hits = overlays.filter { visible.width > 0f && it.bounds.overlaps(visible) }.map { shape ->
        val clipped = shape.bounds.intersect(visible)
        OverlayShape(clipped, if (clipped == shape.bounds) shape.cornerRadius else CornerRadius.Zero)
    }
    return OverlayCutouts(pageBounds, visible, mergeOverlapping(hits), scrims.lastOrNull())
}

/** Overlapping holes would cancel each other out under even-odd filling, so they merge into their bounding box. */
internal fun mergeOverlapping(shapes: List<OverlayShape>): List<OverlayShape> {
    val out = shapes.toMutableList()
    var merged = true
    while (merged) {
        merged = false
        search@ for (i in out.indices) {
            for (j in i + 1 until out.size) {
                if (out[i].bounds.overlaps(out[j].bounds)) {
                    val a = out[i].bounds
                    val b = out[j].bounds
                    out[i] = OverlayShape(Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)))
                    out.removeAt(j)
                    merged = true
                    break@search
                }
            }
        }
    }
    return out
}

private fun Rect.scaleBy(f: Float) = Rect(left * f, top * f, right * f, bottom * f)

private fun StringBuilder.appendRect(r: Rect, c: CornerRadius) {
    val rx = min(c.x, r.width / 2)
    val ry = min(c.y, r.height / 2)
    if (rx <= 0f || ry <= 0f) {
        append("M${n(r.left)} ${n(r.top)}H${n(r.right)}V${n(r.bottom)}H${n(r.left)}Z")
        return
    }
    val arc = "A${n(rx)} ${n(ry)} 0 0 1"
    append("M${n(r.left + rx)} ${n(r.top)}H${n(r.right - rx)}")
    append("$arc ${n(r.right)} ${n(r.top + ry)}V${n(r.bottom - ry)}")
    append("$arc ${n(r.right - rx)} ${n(r.bottom)}H${n(r.left + rx)}")
    append("$arc ${n(r.left)} ${n(r.bottom - ry)}V${n(r.top + ry)}")
    append("$arc ${n(r.left + rx)} ${n(r.top)}Z")
}

/** At most two decimals, never an exponent, never "-0". */
private fun n(v: Float): String {
    val hundredths = (v.toDouble() * 100).roundToLong()
    if (hundredths == 0L) return "0"
    val sign = if (hundredths < 0) "-" else ""
    val whole = abs(hundredths) / 100
    val frac = abs(hundredths) % 100
    return when {
        frac == 0L -> "$sign$whole"
        frac % 10 == 0L -> "$sign$whole.${frac / 10}"
        else -> "$sign$whole.${frac.toString().padStart(2, '0')}"
    }
}
