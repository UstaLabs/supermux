package dev.supermux.webview.sample.chat

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import dev.supermux.webview.sample.FrameStats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * What the chat's layout does while it is read and scrolled (`-chat-probe`): where every row and every preview is at each
 * frame, and so how far the content under the reader's eyes moves on the screen by anything but the scroll (a layout
 * shift), which preview's height changed then, and how long each frame took, with what the engines did around the slow
 * ones. Prints `chat-probe:` lines: each shift, each preview's resize, each slow frame, and a summary per run
 * ([begin], [end]). Main thread only.
 *
 * The scroll is read from the layout itself: the chat is a reversed list, anchored at its first visible row (the newest
 * one on screen, at the bottom), so the bottom edge of the lowest row on screen in two frames moved by the scroll alone,
 * whatever grew above it. A row's top that moved by anything else moved by a resize at or below it. The reader's eyes are
 * at the middle of the list ([gaze]), where the trackpad runs hold the pointer.
 */
class ChatProbe(private val print: (String) -> Unit = ::println) {
    private val origin = TimeSource.Monotonic.markNow()

    /** Milliseconds since the probe was made. */
    fun now(): Double = origin.elapsedNow().inWholeMicroseconds / 1_000.0

    /** The window's px a dp, for the lines it prints. */
    var density: Float = 1f

    private var viewportTop = 0f
    private var viewportBottom = 0f

    /** Where the reader looks: the list's middle, in window px. */
    private val gaze: Float get() = (viewportTop + viewportBottom) / 2

    private class Place(val top: Float, val height: Int) {
        val bottom: Float get() = top + height
    }

    /** Each row's place in the window as last laid out, by its key; and as of the last frame. */
    private val places = HashMap<Any, Place>()
    private var before = HashMap<Any, Place>()

    /** A preview's web view, as laid out, and what its page and view told. */
    private class Card(val slug: String, val initialDp: Float) {
        var top = 0f
        var height = -1
        var sampled = -1
        var firstHeight = -1
        var shownAt: Double? = null
        var phase = "Cold"
        var reportedDp: Float? = null

        // The resize under way: its first height, when it began, its last change, where the card was then.
        var from = -1
        var began = 0.0
        var changed = 0.0
        var where = ""
        var resizes = 0
        var resizesInView = 0
        var shownWhenResized: Double? = null
    }

    private val cards = HashMap<String, Card>()

    /** What the engines and pages did lately, for the slow frames: (time, what). */
    private val events = ArrayDeque<Pair<Double, String>>()

    /** The scroll the list consumed from gestures since the last frame (screen px, the content's way), for a check. */
    private var consumed = 0f

    private var label: String? = null
    private val frames = FrameStats()
    private var lastFrameNanos = 0L
    private var lastFrameAt = 0.0
    private var slowFrames = 0
    private var shiftFrames = 0
    private var shiftPx = 0f
    private var maxFramePx = 0f
    private var episodes = 0
    private var maxEpisodePx = 0f
    private var runResizes = 0
    private var runResizesInView = 0
    private var runResizesMoving = 0
    private var checkedFrames = 0
    private var scrollMismatch = 0

    // The shift under way at the reader's eyes: its frames, its net and its largest distance, and the resizes in it.
    private var episodeFrames = 0
    private var episodeQuiet = 0
    private var episodeNet = 0f
    private var episodeMost = 0f
    private var episodeStart = 0.0
    private var episodeAt: Any? = null
    private val episodeCauses = LinkedHashSet<String>()

    /** Counts the gesture scroll the list consumed, to check the scroll read from the layout. */
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            this@ChatProbe.consumed += consumed.y
            return Offset.Zero
        }
    }

    fun viewport(top: Float, bottom: Float) {
        viewportTop = top
        viewportBottom = bottom
    }

    fun placed(key: Any, top: Float, height: Int) {
        places[key] = Place(top, height)
    }

    fun gone(key: Any) {
        places.remove(key)
    }

    fun card(slug: String, initialDp: Float, top: Float, height: Int) {
        val card = cards.getOrPut(slug) { Card(slug, initialDp) }
        card.top = top
        card.height = height
        if (card.firstHeight < 0) card.firstHeight = height
    }

    fun cardGone(slug: String) {
        val card = cards[slug] ?: return
        card.shownAt = null
        card.height = -1
        card.sampled = -1
    }

    fun cardState(slug: String, phase: String, reportedDp: Float?) {
        val card = cards[slug] ?: return
        if (card.phase != phase) event("$slug $phase")
        if (card.reportedDp != reportedDp && reportedDp != null) event("$slug size ${dp1(reportedDp)} dp")
        card.phase = phase
        card.reportedDp = reportedDp
    }

    /** Something an engine or the pool did, kept for the slow frames around it. */
    fun event(what: String) {
        val at = now()
        events.addLast(at to what)
        while (events.size > 400) events.removeFirst()
    }

    /** A run starts: its frames, shifts and resizes count from here. */
    fun begin(name: String) {
        label = name
        frames.clear()
        lastFrameNanos = 0L
        slowFrames = 0
        shiftFrames = 0
        shiftPx = 0f
        maxFramePx = 0f
        episodes = 0
        maxEpisodePx = 0f
        runResizes = 0
        runResizesInView = 0
        runResizesMoving = 0
        checkedFrames = 0
        scrollMismatch = 0
        print("chat-probe: begin $name at ${s3(now())} s")
    }

    /** The run ends: its summary, and each preview's first height against its last. */
    fun end() {
        val name = label ?: return
        closeEpisode()
        for (card in cards.values) if (card.from >= 0) closeResize(card)
        val f = frames.summary()
        print(
            buildString {
                append("chat-probe: summary $name: ")
                if (f == null) {
                    append("no frames")
                } else {
                    append("${f.frames} frames, p50 ${ms(f.p50)} p95 ${ms(f.p95)} p99 ${ms(f.p99)} max ${ms(f.max)} ms, ")
                    append("${f.dropped} over 1.5x the median, $slowFrames over 25 ms; ")
                }
                append("shift at the reader's eyes: $shiftFrames frames, ${dp1(shiftPx / density)} dp in all, ")
                append("${dp1(maxFramePx / density)} dp at most in a frame, $episodes shifts (${dp1(maxEpisodePx / density)} dp the largest); ")
                append("preview resizes $runResizes, $runResizesInView in view, $runResizesMoving moving what the reader looks at; ")
                append("scroll check ${checkedFrames - scrollMismatch}/$checkedFrames frames")
            },
        )
        // The previews that follow their page's height (the others have a fixed one: NaN).
        val wrapped = cards.values.filter { !it.initialDp.isNaN() && it.reportedDp != null }.sortedBy { it.slug }
        val off = wrapped.count { abs(it.firstHeight / density - (it.reportedDp ?: 0f)) > 1f }
        print("chat-probe: first height against the page's ($name): $off of ${wrapped.size} wrap-content previews were laid out off their page's height first")
        for (card in wrapped) {
            val first = card.firstHeight / density
            print(
                "chat-probe:   ${card.slug}: initial ${dp1(card.initialDp)}, first ${dp1(first)}, page ${dp1(card.reportedDp ?: 0f)}, " +
                    "now ${dp1(max(card.height, 0) / density)} dp; resized ${card.resizes}x, ${card.resizesInView}x in view",
            )
        }
        label = null
    }

    /**
     * One frame, before it is laid out: what the last one showed against the one before. [frameNanos] is the frame
     * clock's time; [info] the list's layout.
     */
    fun frame(frameNanos: Long, info: LazyListLayoutInfo) {
        val at = now()
        val counting = label != null
        if (counting && lastFrameNanos != 0L) {
            val interval = frameNanos - lastFrameNanos
            frames.add(interval)
            if (interval > SLOW_NANOS) {
                slowFrames++
                val from = lastFrameAt
                val near = events.filter { it.first >= from - 20.0 && it.first <= at }.joinToString("; ") { "${s3(it.first)} ${it.second}" }
                print("chat-probe: slow frame at ${s3(at)} s: ${ms(interval)} ms; around it: ${near.ifEmpty { "nothing the engines did" }}")
            }
        }
        lastFrameNanos = frameNanos
        lastFrameAt = at

        val visible = info.visibleItemsInfo
        // The scroll: the bottom of the lowest row on screen in both frames (the list is anchored there).
        var scroll: Float? = null
        for (item in visible) {
            val now = places[item.key] ?: continue
            val then = before[item.key] ?: continue
            scroll = now.bottom - then.bottom
            break
        }
        // The rows whose top moved by more than the scroll, and the one under the reader's eyes.
        var jump = 0f
        var gazeKey: Any? = null
        if (scroll != null) {
            for (item in visible) {
                val now = places[item.key] ?: continue
                val then = before[item.key] ?: continue
                if (gaze >= now.top && gaze < now.bottom) {
                    jump = now.top - then.top - scroll
                    gazeKey = item.key
                }
            }
            if (consumed != 0f) {
                checkedFrames++
                if (abs(consumed - scroll) > 1.5f && abs(consumed + scroll) > 1.5f) scrollMismatch++
            }
        }
        consumed = 0f
        val next = HashMap<Any, Place>()
        for (item in visible) places[item.key]?.let { next[item.key] = it }
        before = next

        // The previews: which came into view, which changed height.
        val resizing = ArrayList<String>()
        for (card in cards.values) {
            if (card.height < 0) continue
            val inView = card.top + card.height > viewportTop && card.top < viewportBottom
            if (inView && card.shownAt == null) card.shownAt = at
            if (!inView) card.shownAt = null
            if (card.sampled >= 0 && card.height != card.sampled) {
                if (card.from < 0) {
                    card.from = card.sampled
                    card.began = at
                    card.shownWhenResized = card.shownAt
                    card.where = where(card, inView)
                }
                card.changed = at
                resizing += card.slug
            } else if (card.from >= 0 && at - card.changed > RESIZE_SETTLE_MS) {
                closeResize(card)
            }
            card.sampled = card.height
        }

        if (!counting) return
        if (abs(jump) >= MIN_SHIFT_PX) {
            if (episodeFrames == 0) {
                episodeStart = at
                episodeAt = gazeKey
            }
            episodeFrames++
            episodeQuiet = 0
            episodeNet += jump
            episodeMost = max(episodeMost, abs(episodeNet))
            episodeCauses += resizing
            shiftFrames++
            shiftPx += abs(jump)
            maxFramePx = max(maxFramePx, abs(jump))
        } else if (episodeFrames > 0 && ++episodeQuiet >= 3) {
            closeEpisode()
        }
    }

    /** Where [card] is against the list: above it, in view (above or under the reader's eyes), or below it. */
    private fun where(card: Card, inView: Boolean): String {
        if (!inView) return if (card.top + card.height <= viewportTop) "above the list" else "below the list"
        // A reversed list: a card that grows moves what is above its bottom edge.
        return if (card.top + card.height <= gaze) "in view, above the eyes" else "in view, under or below the eyes"
    }

    private fun closeResize(card: Card) {
        val inView = card.where.startsWith("in view")
        val moving = card.where == "in view, under or below the eyes"
        card.resizes++
        if (inView) card.resizesInView++
        if (label != null) {
            runResizes++
            if (inView) runResizesInView++
            if (moving) runResizesMoving++
            val shown = card.shownWhenResized?.let { " ${s3(card.began - it)} s after it came into view;" } ?: ""
            print(
                "chat-probe: resize at ${s3(card.began)} s: ${card.slug} ${dp1(card.from / density)} -> ${dp1(card.height / density)} dp " +
                    "in ${(card.changed - card.began).roundToInt()} ms, ${card.where};$shown phase ${card.phase}",
            )
        }
        card.from = -1
    }

    private fun closeEpisode() {
        if (episodeFrames == 0) return
        episodes++
        maxEpisodePx = max(maxEpisodePx, episodeMost)
        print(
            "chat-probe: shift at ${s3(episodeStart)} s: the content at the eyes ($episodeAt) moved ${dp1(episodeNet / density)} dp " +
                "(${dp1(episodeMost / density)} at most) over $episodeFrames frames; resizing then: " +
                episodeCauses.joinToString().ifEmpty { "none of the previews" },
        )
        episodeFrames = 0
        episodeQuiet = 0
        episodeNet = 0f
        episodeMost = 0f
        episodeAt = null
        episodeCauses.clear()
    }

    private companion object {
        /** A frame this long is slow at any refresh rate the chat runs at. */
        const val SLOW_NANOS = 25_000_000L

        /** Movement smaller than this isn't a shift: rounding to the pixel. */
        const val MIN_SHIFT_PX = 0.75f

        /** A preview whose height hasn't changed for this long has finished resizing. */
        const val RESIZE_SETTLE_MS = 120.0

        fun ms(nanos: Long): String = dp1(nanos / 1_000_000f)

        fun dp1(value: Float): String {
            val tenths = (value * 10).roundToInt()
            val sign = if (tenths < 0) "-" else ""
            val a = abs(tenths)
            return "$sign${a / 10}.${a % 10}"
        }

        /** [millis] as seconds, to the millisecond: "12.345". */
        fun s3(millis: Double): String {
            val thousandths = millis.roundToInt()
            return "${thousandths / 1000}.${(thousandths % 1000).toString().padStart(3, '0')}"
        }
    }
}
