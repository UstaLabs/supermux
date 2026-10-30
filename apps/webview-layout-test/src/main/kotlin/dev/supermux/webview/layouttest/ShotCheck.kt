package dev.supermux.webview.layouttest

import java.awt.image.BufferedImage
import java.util.Locale
import kotlin.math.roundToInt

/** What a screenshot pixel shows, told by its colour (see [Palette]). Thresholds are wide: the page is colour-managed. */
enum class Paint {
    Page, PageDimmed, Band, Marker, Menu, Dialog;

    companion object {
        /** The class of an `0xRRGGBB` pixel, or null for anything else (white, greys, text). */
        fun of(rgb: Int): Paint? {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return when {
                r >= 180 && b >= 180 && g <= 110 -> Band
                g >= 170 && b >= 170 && r <= 140 && g - r >= 60 -> Marker
                r >= 200 && g in 90..190 && b <= 100 -> Dialog
                g >= 150 && g - maxOf(r, b) >= 50 -> Menu
                b >= 215 && b - maxOf(r, g) >= 120 -> Page
                b in 110..205 && b - maxOf(r, g) >= 80 -> PageDimmed
                else -> null
            }
        }
    }
}

/** A named point in window px, and what it should show. */
data class ColorProbe(val name: String, val x: Float, val y: Float, val expect: Paint)

data class ProbeResult(val probe: ColorProbe, val saw: Paint?, val rgb: Int) {
    val pass: Boolean get() = saw == probe.expect

    fun line(): String = String.format(Locale.ROOT, "%s: expected %s, saw %s (#%06x)", probe.name, probe.expect, saw ?: "none", rgb and 0xFFFFFF)
}

/** Window px → screenshot px: the window content's top-left in the image (under the title bar), and image px per window px. */
data class ShotFrame(val originX: Int, val originY: Int, val scale: Double)

/** Which edge of the page an offset is measured at; its marker is outside the slot on that side. */
enum class Edge { Top, Left, Right }

object ShotCheck {
    /** The mean colour (`0xRRGGBB`) of the 5×5 patch around ([x], [y]) window px. */
    fun sample(image: BufferedImage, frame: ShotFrame, x: Float, y: Float): Int {
        val cx = (frame.originX + x * frame.scale).roundToInt()
        val cy = (frame.originY + y * frame.scale).roundToInt()
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (py in cy - 2..cy + 2) {
            for (px in cx - 2..cx + 2) {
                if (px !in 0 until image.width || py !in 0 until image.height) continue
                val rgb = image.getRGB(px, py)
                r += (rgb shr 16) and 0xFF
                g += (rgb shr 8) and 0xFF
                b += rgb and 0xFF
                n++
            }
        }
        if (n == 0) return 0
        return ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    fun check(image: BufferedImage, frame: ShotFrame, probes: List<ColorProbe>): List<ProbeResult> =
        probes.map { probe ->
            val rgb = sample(image, frame, probe.x, probe.y)
            ProbeResult(probe, Paint.of(rgb), rgb)
        }

    /**
     * Where the page's border band is against the Compose marker beside the slot, in image px: 0 in sync, positive for a
     * gap between them, negative when the page overshoots its slot. Null when the marker or the band isn't found.
     * - [line] is the column (Top) or row (Left, Right) to scan, and [from]..[to] the range along it, in window px.
     * - It walks inwards from the marker's outer side (down for Top, right for Left, left for Right), so a band that
     *   covers the marker's inner part (layout B) and a marker that covers the band's outer part (layout A) both count.
     */
    fun edgeOffset(
        image: BufferedImage,
        frame: ShotFrame,
        edge: Edge,
        line: Float,
        from: Float,
        to: Float,
        markerPx: Int,
        bandPx: Int,
    ): Int? {
        val vertical = edge == Edge.Top
        val fixed = if (vertical) (frame.originX + line * frame.scale).roundToInt() else (frame.originY + line * frame.scale).roundToInt()
        val origin = if (vertical) frame.originY else frame.originX
        val limit = if (vertical) image.height else image.width
        if (fixed !in 0 until (if (vertical) image.width else image.height)) return null
        val start = (origin + from * frame.scale).roundToInt().coerceIn(0, limit - 1)
        val end = (origin + to * frame.scale).roundToInt().coerceIn(0, limit - 1)
        fun paintAt(pos: Int): Paint? = Paint.of(if (vertical) image.getRGB(fixed, pos) else image.getRGB(pos, fixed))
        val step = if (edge == Edge.Right) -1 else 1
        val first = if (step > 0) start else end
        val last = if (step > 0) end else start
        fun walk(from: Int): Sequence<Int> = generateSequence(from) { it + step }.takeWhile { if (step > 0) it <= last else it >= last }
        val markerOuter = walk(first).firstOrNull { paintAt(it) == Paint.Marker } ?: return null
        val expected = markerOuter + step * markerPx
        val bandStart = walk(markerOuter).take(markerPx + bandPx + 40).firstOrNull { paintAt(it) == Paint.Band } ?: return null
        val bandLength = walk(bandStart).take(bandPx * 4).takeWhile { paintAt(it) == Paint.Band }.count()
        return (bandStart - expected) * step - (bandPx - bandLength).coerceAtLeast(0)
    }
}
