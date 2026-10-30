package dev.supermux.webview.layouttest

import androidx.compose.ui.graphics.toArgb
import dev.supermux.webview.runtime.OverlayCutouts

/**
 * What `Native.setCutouts` takes, in page-local window pixels (y down, the page's top-left at 0,0):
 * - [geometry]: the visible rect `l, t, r, b`; then 12 floats a hole (`l, t, r, b`, then x and y radii of the top-left,
 *   top-right, bottom-right and bottom-left corners); then 4 floats a scrim (`l, t, r, b`).
 * - [holeCount] holes, and one ARGB colour a scrim in [scrimColors].
 */
class PackedCutouts(val geometry: FloatArray, val holeCount: Int, val scrimColors: IntArray)

/** [cutouts] for the glue. Without [includeHoles] only the visible rect goes: the control run, where nothing is cut. */
fun packCutouts(cutouts: OverlayCutouts, includeHoles: Boolean = true): PackedCutouts {
    val dx = -cutouts.pageBounds.left
    val dy = -cutouts.pageBounds.top
    val holes = if (includeHoles) cutouts.holes else emptyList()
    val scrims = if (includeHoles) cutouts.scrims else emptyList()
    val geometry = FloatArray(4 + 12 * holes.size + 4 * scrims.size)
    var next = 0
    fun put(value: Float) {
        geometry[next++] = value
    }
    val visible = cutouts.visibleBounds
    if (visible.isEmpty || !visible.isFinite || !dx.isFinite() || !dy.isFinite()) {
        repeat(4) { put(0f) }
    } else {
        put(visible.left + dx); put(visible.top + dy); put(visible.right + dx); put(visible.bottom + dy)
    }
    for (hole in holes) {
        val s = hole.shape
        put(s.left + dx); put(s.top + dy); put(s.right + dx); put(s.bottom + dy)
        put(s.topLeftCornerRadius.x); put(s.topLeftCornerRadius.y)
        put(s.topRightCornerRadius.x); put(s.topRightCornerRadius.y)
        put(s.bottomRightCornerRadius.x); put(s.bottomRightCornerRadius.y)
        put(s.bottomLeftCornerRadius.x); put(s.bottomLeftCornerRadius.y)
    }
    for (scrim in scrims) {
        val b = scrim.bounds
        put(b.left + dx); put(b.top + dy); put(b.right + dx); put(b.bottom + dy)
    }
    return PackedCutouts(geometry, holes.size, IntArray(scrims.size) { scrims[it].color.toArgb() })
}
