package dev.supermux.webview.engine.mac

import dev.supermux.webview.runtime.OverlayCutouts

/**
 * What the glue's `hitTest:` lets the page take (D1), from the runtime's cut-outs, page-local in window pixels (y down,
 * the page's top-left at 0,0):
 * - [geometry]: the visible rect `l, t, r, b`; then 12 floats a hole (`l, t, r, b`, then the x and y radii of the
 *   top-left, top-right, bottom-right and bottom-left corners); then 4 floats a scrim (`l, t, r, b`);
 * - [holeCount] holes, and [scrimCount] scrims after them.
 *
 * A pointer is the page's inside the visible rect, outside every hole, and under no scrim. This is the layout test's
 * packing (`CutoutPacking.kt`) without the scrims' colours: under the canvas, Compose draws the scrims itself.
 */
internal class InputRegion private constructor(val geometry: FloatArray, val holeCount: Int) {
    val scrimCount: Int get() = (geometry.size - 4 - 12 * holeCount) / 4

    override fun equals(other: Any?): Boolean =
        other is InputRegion && other.holeCount == holeCount && other.geometry.contentEquals(geometry)

    override fun hashCode(): Int = 31 * geometry.contentHashCode() + holeCount

    companion object {
        /** Nothing visible: the page takes no pointer. */
        val None: InputRegion = InputRegion(FloatArray(4), 0)

        fun of(cutouts: OverlayCutouts): InputRegion {
            val dx = -cutouts.pageBounds.left
            val dy = -cutouts.pageBounds.top
            val visible = cutouts.visibleBounds
            if (visible.isEmpty || !visible.isFinite || !dx.isFinite() || !dy.isFinite()) return None
            val holes = cutouts.holes
            val scrims = cutouts.scrims
            val geometry = FloatArray(4 + 12 * holes.size + 4 * scrims.size)
            var next = 0
            fun put(value: Float) {
                geometry[next++] = value
            }
            put(visible.left + dx)
            put(visible.top + dy)
            put(visible.right + dx)
            put(visible.bottom + dy)
            for (hole in holes) {
                val s = hole.shape
                put(s.left + dx)
                put(s.top + dy)
                put(s.right + dx)
                put(s.bottom + dy)
                put(s.topLeftCornerRadius.x)
                put(s.topLeftCornerRadius.y)
                put(s.topRightCornerRadius.x)
                put(s.topRightCornerRadius.y)
                put(s.bottomRightCornerRadius.x)
                put(s.bottomRightCornerRadius.y)
                put(s.bottomLeftCornerRadius.x)
                put(s.bottomLeftCornerRadius.y)
            }
            for (scrim in scrims) {
                val b = scrim.bounds
                put(b.left + dx)
                put(b.top + dy)
                put(b.right + dx)
                put(b.bottom + dy)
            }
            return InputRegion(geometry, holes.size)
        }
    }
}
