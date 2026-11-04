// "The thing under it" when the assistant overlay is dropped to look: the block of content around a point
// in a picture of the screen, bounded by the blank margins around it (between a photo, a price, a
// paragraph). Pure, on a small greyscale grid, so it's tested without a screen.
package dev.supermux.ui.overlay

/** A greyscale picture, [w] × [h] luminance values 0..255, row by row. */
class LumaGrid(val w: Int, val h: Int, val px: IntArray) {
    init { require(px.size == w * h) }

    operator fun get(x: Int, y: Int): Int = px[y * w + x]

    /**
     * Paints over a rectangle (inclusive) with what is just left of it on each row (or right, at the
     * left edge): the overlay's own head sits there, and it isn't part of the screen it looks at.
     */
    fun blankOut(left: Int, top: Int, right: Int, bottom: Int) {
        val l = left.coerceIn(0, w - 1)
        val r = right.coerceIn(0, w - 1)
        for (y in top.coerceIn(0, h - 1)..bottom.coerceIn(0, h - 1)) {
            val fill = when {
                l > 0 -> this[l - 1, y]
                r < w - 1 -> this[r + 1, y]
                else -> 255
            }
            for (x in l..r) px[y * w + x] = fill
        }
    }

    companion object {
        /** From packed ARGB pixels, averaged down by [step] in each direction (a 1080-wide screen at 4 → 270). */
        fun fromArgb(argb: IntArray, width: Int, height: Int, step: Int): LumaGrid {
            val w = width / step
            val h = height / step
            val out = IntArray(w * h)
            for (gy in 0 until h) for (gx in 0 until w) {
                var sum = 0
                for (dy in 0 until step) {
                    val row = (gy * step + dy) * width + gx * step
                    for (dx in 0 until step) {
                        val c = argb[row + dx]
                        sum += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                    }
                }
                out[gy * w + gx] = sum / (step * step)
            }
            return LumaGrid(w, h, out)
        }
    }
}

object LookBlock {
    /**
     * The block of content around ([x], [y]), in grid cells: grown from a small seed in each direction
     * while content keeps turning up within [minGap] rows or columns, so it crosses the gaps between
     * lines and words but stops at the margins around a block. Null when there's nothing at the point
     * (blank space), so the caller frames a default area instead. A row or column has content where its
     * lightest and darkest cells differ by more than [contrast]. [seed] is the half-size of the start
     * area: make it reach past anything painted over at the point ([LumaGrid.blankOut]), or there's
     * nothing to start from.
     */
    fun around(g: LumaGrid, x: Int, y: Int, minGap: Int, seed: Int = 4, contrast: Int = 12): LookRect? {
        if (x !in 0 until g.w || y !in 0 until g.h) return null
        var l = (x - seed).coerceAtLeast(0)
        var r = (x + seed).coerceAtMost(g.w - 1)
        var t = (y - seed).coerceAtLeast(0)
        var b = (y + seed).coerceAtMost(g.h - 1)
        if ((t..b).none { rowHas(g, it, l, r, contrast) }) return null
        // Each widening can reveal more above and below, and each heightening more to the sides.
        for (pass in 0 until 12) {
            val before = listOf(l, r, t, b)
            t = grow(t, -1, 0, minGap) { rowHas(g, it, l, r, contrast) }
            b = grow(b, +1, g.h - 1, minGap) { rowHas(g, it, l, r, contrast) }
            l = grow(l, -1, 0, minGap) { colHas(g, it, t, b, contrast) }
            r = grow(r, +1, g.w - 1, minGap) { colHas(g, it, t, b, contrast) }
            if (listOf(l, r, t, b) == before) break
        }
        // Drop the seed's blank margins. Columns are judged over the taller band, margins included, so a
        // glyph's solid stroke still shows against the background around it.
        val t0 = t
        val b0 = b
        while (t < b && !rowHas(g, t, l, r, contrast)) t++
        while (b > t && !rowHas(g, b, l, r, contrast)) b--
        while (l < r && !colHas(g, l, t0, b0, contrast)) l++
        while (r > l && !colHas(g, r, t0, b0, contrast)) r--
        return LookRect(l.toFloat(), t.toFloat(), (r + 1).toFloat(), (b + 1).toFloat())
    }

    /** From [edge], steps by [dir] while content turns up within [minGap]; the last line that had some. */
    private inline fun grow(edge: Int, dir: Int, limit: Int, minGap: Int, has: (Int) -> Boolean): Int {
        var last = edge
        var gap = 0
        var i = edge + dir
        while ((if (dir < 0) i >= limit else i <= limit) && gap < minGap) {
            if (has(i)) { last = i; gap = 0 } else gap++
            i += dir
        }
        return last
    }

    private fun rowHas(g: LumaGrid, y: Int, l: Int, r: Int, contrast: Int): Boolean {
        var lo = 255
        var hi = 0
        for (x in l..r) { val v = g[x, y]; if (v < lo) lo = v; if (v > hi) hi = v }
        return hi - lo > contrast
    }

    private fun colHas(g: LumaGrid, x: Int, t: Int, b: Int, contrast: Int): Boolean {
        var lo = 255
        var hi = 0
        for (y in t..b) { val v = g[x, y]; if (v < lo) lo = v; if (v > hi) hi = v }
        return hi - lo > contrast
    }
}
