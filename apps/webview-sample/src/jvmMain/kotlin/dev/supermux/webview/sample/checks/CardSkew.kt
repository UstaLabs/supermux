package dev.supermux.webview.sample.checks

import dev.supermux.webview.sample.chat.PreviewMarkers
import dev.supermux.webview.testing.Copied
import dev.supermux.webview.testing.MacEngineTesting
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.Window
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * How far each live page is from where its card puts it, in copies of the window a pixel a point, with [PreviewMarkers]
 * on: a card's caption line is the bottom of its page's slot in the frame Compose presented (cyan while the page itself
 * shows, blue while Compose draws a picture of it), and the page's magenta and yellow lines, [PreviewMarkers.MAGENTA] and
 * [PreviewMarkers.YELLOW] points above the page's own bottom, are where AppKit has the page. A page's skew is its bottom
 * less its slot's, in points: 0 in its card; while skiko presents Compose's frame after AppKit has moved the view, the
 * distance the page runs ahead of it (positive: lower).
 */
internal object CardSkew {
    /** One live card in a copy: its caption line's centre row, its columns, and its page's skew (points). */
    class Mark(val captionY: Float, val left: Int, val right: Int, val skew: Float)

    /** A line of one ink: rows [first]..[last], and its widest row's columns [left]..[right). */
    private class Line(val first: Int, var last: Int, var left: Int, var right: Int) {
        val centre: Float get() = (first + last) / 2f
    }

    private const val CYAN = 0
    private const val BLUE = 1
    private const val MAGENTA = 2
    private const val YELLOW = 3
    private const val INKS = 4

    /** Rows a line may span in a copy: its 3 points, and one more each side where it falls between pixels. */
    private const val MAX_ROWS = 6

    /** How far a page's line may be from its place and still be looked for: further, the card hides it anyway. */
    private const val SEARCH = 170f

    private fun inkOf(r: Int, g: Int, b: Int): Int {
        val rh = r >= 170
        val rl = r <= 90
        val gh = g >= 170
        val gl = g <= 90
        val bh = b >= 170
        val bl = b <= 90
        return when {
            rl && gh && bh -> CYAN
            rl && gl && bh -> BLUE
            rh && gl && bh -> MAGENTA
            rh && gh && bl -> YELLOW
            else -> -1
        }
    }

    /**
     * Every live card in [copy] whose page shows one of its lines or both. A line is a run of one ink at least [minRun]
     * px long in a row, a few rows thick; a page's lines count for the caption line below them, under the card above's.
     */
    fun marks(copy: Copied, minRun: Int): List<Mark> {
        val lines = Array(INKS) { ArrayList<Line>() }
        val open = arrayOfNulls<Line>(INKS)
        val width = copy.width
        val rgba = copy.rgba
        val start = IntArray(INKS)
        val run = IntArray(INKS)
        val bestStart = IntArray(INKS)
        val best = IntArray(INKS)
        for (y in 0 until copy.height) {
            run.fill(0)
            best.fill(0)
            var i = y * width * 4
            for (x in 0 until width) {
                val ink = inkOf(rgba[i].toInt() and 0xff, rgba[i + 1].toInt() and 0xff, rgba[i + 2].toInt() and 0xff)
                i += 4
                for (k in 0 until INKS) {
                    if (k != ink) {
                        run[k] = 0
                        continue
                    }
                    if (run[k] == 0) start[k] = x
                    run[k]++
                    if (run[k] > best[k]) {
                        best[k] = run[k]
                        bestStart[k] = start[k]
                    }
                }
            }
            for (k in 0 until INKS) {
                if (best[k] < minRun) {
                    open[k] = null
                    continue
                }
                val left = bestStart[k]
                val right = left + best[k]
                val line = open[k]
                if (line != null && min(right, line.right) > max(left, line.left)) {
                    line.last = y
                    if (right - left > line.right - line.left) {
                        line.left = left
                        line.right = right
                    }
                } else {
                    open[k] = Line(y, y, left, right).also { lines[k] += it }
                }
            }
        }
        // Thicker than a line: something else of that colour.
        for (k in 0 until INKS) lines[k].removeAll { it.last - it.first + 1 > MAX_ROWS }
        val captions = (lines[CYAN].map { it to true } + lines[BLUE].map { it to false }).sortedBy { it.first.centre }
        val marks = ArrayList<Mark>()
        for ((index, entry) in captions.withIndex()) {
            val (caption, live) = entry
            if (!live) continue
            // The page's lines show only in its slot, which is under the card above's caption.
            val above = captions.getOrNull(index - 1)?.first?.centre ?: Float.NEGATIVE_INFINITY
            val skews = ArrayList<Float>(2)
            for ((ink, fromBottom) in listOf(MAGENTA to PreviewMarkers.MAGENTA, YELLOW to PreviewMarkers.YELLOW)) {
                val expected = caption.centre - fromBottom
                val line = lines[ink]
                    .filter { it.centre < caption.centre && it.centre > above && abs(it.centre - expected) <= SEARCH }
                    .filter { min(it.right, caption.right) - max(it.left, caption.left) >= (caption.right - caption.left) / 2 }
                    .minByOrNull { abs(it.centre - expected) } ?: continue
                skews += line.centre - expected
            }
            if (skews.isNotEmpty()) marks += Mark(caption.centre, caption.left, caption.right, skews.average().toFloat())
        }
        return marks
    }
}

/**
 * The [CardSkew.Mark]s of a run's copies, card by card over time, and what they come to while the cards moved: how far
 * the pages were from their cards, in points, in frames of the display ([framePeriodNanos]), and in frames Compose
 * presented. Not thread-safe: one copier thread adds, and reads the summary when it is done.
 *
 * A copy of the window (`CGWindowListCreateImage`) composites what the window server has been sent: between a commit and
 * the next refresh it pairs the views' new places with the drawable before, which the display still shows. So in copies
 * a page can be one presented frame ahead of its card even where the display shows them together; [Summary.beyondOne]
 * counts the cards further ahead than that, which the display shows too.
 */
internal class SkewRecord(private val framePeriodNanos: Double = 1e9 / 60) {
    private class Sample(val nanos: Long, val y: Float, val skew: Float)

    private class Track(var lastNanos: Long, var lastY: Float, var left: Int, var right: Int) {
        val samples = ArrayList<Sample>()
    }

    private val tracks = ArrayList<Track>()
    private val open = ArrayList<Track>()

    /** Copies seen, and those with at least one live card measured. */
    var copies = 0
        private set
    var measured = 0
        private set

    /** The largest skew of any card in a copy, still or moving. */
    var worst = 0f
        private set

    fun add(nanos: Long, marks: List<CardSkew.Mark>) {
        copies++
        if (marks.isEmpty()) return
        measured++
        open.removeAll { nanos - it.lastNanos > TRACK_GAP_NANOS }
        val taken = HashSet<Track>()
        for (mark in marks.sortedBy { it.captionY }) {
            worst = max(worst, abs(mark.skew))
            val track = open
                .filter { it !in taken && min(it.right, mark.right) > max(it.left, mark.left) && abs(it.lastY - mark.captionY) <= MAX_STEP }
                .minByOrNull { abs(it.lastY - mark.captionY) }
                ?: Track(nanos, mark.captionY, mark.left, mark.right).also {
                    tracks += it
                    open += it
                }
            taken += track
            track.samples += Sample(nanos, mark.captionY, mark.skew)
            track.lastNanos = nanos
            track.lastY = mark.captionY
            track.left = mark.left
            track.right = mark.right
        }
    }

    /**
     * What the moving cards came to: [px] their skew (points), [frames] how long the card took to get where its page was
     * (display frames), [steps] how many frames Compose presented on the way; [off] cards more than [OFF] points off,
     * [ahead] of them with the page ahead; [beyondOne] more than one presented frame ahead (or behind).
     */
    class Summary(
        val copies: Int,
        val measured: Int,
        val moving: Int,
        val px: Percentiles,
        val frames: Percentiles,
        val steps: Percentiles,
        val off: Int,
        val ahead: Int,
        val beyondOne: Int,
        val worst: Float,
    ) {
        override fun toString(): String =
            "$copies copies, $measured with a live card measured; $moving cards measured moving: skew $px pt, $frames frames " +
                "of the display, $steps frames presented; $off of them more than $OFF pt off, $ahead of those with the page " +
                "ahead of its card, $beyondOne more than one presented frame off; the most off any card was ${"%.1f".format(worst)} pt"
    }

    /** p50, p95 and the largest of some values. */
    class Percentiles(val p50: Double, val p95: Double, val max: Double) {
        override fun toString(): String = "p50 ${"%.1f".format(p50)} / p95 ${"%.1f".format(p95)} / max ${"%.1f".format(max)}"

        companion object {
            fun of(values: List<Double>): Percentiles {
                if (values.isEmpty()) return Percentiles(0.0, 0.0, 0.0)
                val sorted = values.sorted()
                fun at(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
                return Percentiles(at(0.50), at(0.95), sorted.last())
            }
        }
    }

    fun summary(): Summary {
        val px = ArrayList<Double>()
        val frames = ArrayList<Double>()
        val steps = ArrayList<Double>()
        var off = 0
        var ahead = 0
        var beyondOne = 0
        for (track in tracks) {
            val samples = track.samples
            var from = 0
            var to = 0
            for ((index, sample) in samples.withIndex()) {
                // The card's speed: its caption over the copies about VELOCITY_HALF_NANOS either side.
                while (samples[from].nanos < sample.nanos - VELOCITY_HALF_NANOS) from++
                while (to + 1 < samples.size && samples[to + 1].nanos <= sample.nanos + VELOCITY_HALF_NANOS) to++
                val span = samples[to].nanos - samples[from].nanos
                if (span < VELOCITY_HALF_NANOS) continue
                val pxPerFrame = (samples[to].y - samples[from].y) / span * framePeriodNanos
                if (abs(pxPerFrame) < MOVING_PX_PER_FRAME) continue
                val skew = abs(sample.skew.toDouble())
                px += skew
                if (skew <= OFF) {
                    frames += skew / abs(pxPerFrame)
                    steps += 0.0
                    continue
                }
                off++
                val isAhead = sample.skew * pxPerFrame > 0
                if (isAhead) ahead++
                // In time: when the card gets to where its page is now (or got there, for a page behind it); the speed
                // alone overstates it as the card slows down. And in Compose's frames: how many it presented on the way.
                when (val reach = reach(samples, index, sample.y + sample.skew, if (isAhead) 1 else -1)) {
                    is Reach.At -> {
                        frames += abs(reach.nanos) / framePeriodNanos
                        steps += reach.presented.toDouble()
                        if (reach.presented > 1) beyondOne++
                    }
                    Reach.Never -> {
                        frames += skew / abs(pxPerFrame)
                        steps += Double.POSITIVE_INFINITY
                        beyondOne++
                    }
                    // The copies ended first: how far ahead in frames, unknown.
                    Reach.Unknown -> frames += skew / abs(pxPerFrame)
                }
            }
        }
        return Summary(copies, measured, px.size, Percentiles.of(px), Percentiles.of(frames), Percentiles.of(steps), off, ahead, beyondOne, worst)
    }

    private sealed interface Reach {
        /** The caption crossed the page's place [nanos] from the copy, after [presented] new places. */
        class At(val nanos: Long, val presented: Int) : Reach

        /** Not within [REACH_NANOS]. */
        data object Never : Reach

        /** The card's copies ended before. */
        data object Unknown : Reach
    }

    /**
     * When, going [step] (1: after [index]'s copy, -1: before it), the card's caption crossed [target], interpolated
     * between the two copies either side, and how many presented frames (new places of the caption) it took to.
     */
    private fun reach(samples: List<Sample>, index: Int, target: Float, step: Int): Reach {
        val at = samples[index]
        var i = index
        var presented = 0
        var place = at.y
        while (true) {
            val next = i + step
            if (next !in samples.indices) return Reach.Unknown
            if (abs(samples[next].nanos - at.nanos) > REACH_NANOS) return Reach.Never
            val a = samples[i]
            val b = samples[next]
            if (abs(b.y - place) >= NEW_PLACE) {
                presented++
                place = b.y
            }
            if ((a.y - target) * (b.y - target) <= 0f && a.y != b.y) {
                val f = (target - a.y) / (b.y - a.y)
                return Reach.At((a.nanos + f * (b.nanos - a.nanos)).toLong() - at.nanos, presented)
            }
            i = next
        }
    }

    companion object {
        /** Points a page may be off its card and still count as in it: a copy's rounding of half points, and its blending. */
        const val OFF = 2f

        /** A card moving less than this many points a frame counts as still: its skew can't say how far ahead in time. */
        const val MOVING_PX_PER_FRAME = 2.0

        /** A caption this far from its last place is in a new presented frame. */
        private const val NEW_PLACE = 0.5f

        /** The farthest a card's caption moves between two copies of one card, and the longest it may go unseen. */
        private const val MAX_STEP = 150f
        private const val TRACK_GAP_NANOS = 200_000_000L

        private const val VELOCITY_HALF_NANOS = 40_000_000L

        /** How far from a copy the card's way to its page is looked for. */
        private const val REACH_NANOS = 400_000_000L
    }
}

/**
 * Copies [left], [top], [width] × [height] (window px) of [window] a pixel a point on a thread of its own, as fast as the
 * window server gives them, into a [SkewRecord]; the first few copies with a page more than 8 points off its card go to
 * [folder] (`skew-<copy>.png`), when there is one.
 */
internal class SkewCopier(
    private val window: Window,
    private val left: Float,
    private val top: Float,
    private val width: Float,
    private val height: Float,
    private val minRun: Int,
    private val folder: File?,
    framePeriodNanos: Double,
) {
    val record = SkewRecord(framePeriodNanos)

    @Volatile private var running = true
    private var saved = 0
    private val thread = thread(name = "skew-copier", isDaemon = true) {
        while (running) {
            val copied = MacEngineTesting.copyPoints(window, left, top, width, height) ?: continue
            val nanos = System.nanoTime()
            val marks = CardSkew.marks(copied, minRun)
            record.add(nanos, marks)
            if (folder != null && saved < SAVED && marks.any { abs(it.skew) > 8f }) {
                saved++
                folder.mkdirs()
                savePng(copied, File(folder, "skew-${record.copies}.png"))
            }
        }
    }

    /** Stops copying: what the copies came to. */
    fun stop(): SkewRecord.Summary {
        running = false
        thread.join(3_000)
        return record.summary()
    }

    private companion object {
        const val SAVED = 4
    }
}

/** [copied] as a PNG at [file]. */
internal fun savePng(copied: Copied, file: File) {
    val bitmap = Bitmap()
    if (!bitmap.allocPixels(ImageInfo(copied.width, copied.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) return
    if (!bitmap.installPixels(copied.rgba)) return
    val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG) ?: return
    file.writeBytes(png.bytes)
}

/** The display's frame period for [window]'s screen, from AWT's display mode; 60 Hz when it doesn't say. */
internal fun framePeriodNanosOf(window: Window): Double {
    val hz = window.graphicsConfiguration?.device?.displayMode?.refreshRate ?: 0
    return 1e9 / (if (hz in 24..240) hz else 60)
}
