package dev.supermux.webview.sample.checks

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.supermux.webview.Activation
import dev.supermux.webview.DarkMode
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.MacEngineTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A page in motion (M6, D2): in a list that scrolls, in a pane whose divider is dragged, in a window resized, each for
 * [MOTION_SECONDS] at the layout test's speeds, as the engine runs it. Every frame the window is copied around the
 * page's slot, and the checks judge where the page's content is, not only its colours (the review, C2):
 * - its marker, a red square the page draws at CSS (16, 16), must be where the slot puts it in every copy, at its true
 *   size: the live page, or the motion cover the engine draws while the page is redrawn at a new size, never a
 *   picture scaled to the slot. A copy without the marker counts as out of place, so a copy that sees nothing proves
 *   nothing;
 * - the window's backing (pure green, painted by Main.kt), which shows only where neither Compose nor a page draws,
 *   must show in none.
 *
 * The engine covers a page only while it is being resized (D2), so the motion cover must go up in the two resizes and
 * never while the list scrolls (a move keeps the live page).
 */
object MotionChecks {
    /** Deep blue, nothing green, and the red marker at CSS (16, 16), 24 × 24. */
    private val PAGE = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>motion</title>
<style>html,body{margin:0;height:100%;background:#1e3a8a}
#marker{position:absolute;left:16px;top:16px;width:24px;height:24px;background:#ff0000}</style></head>
<body><div id="marker"></div></body></html>""",
    )

    private const val MOTION_SECONDS = 3.0

    /** Window pixels the marker may be off by, against one of the last [RECENT] slots. */
    private const val TOLERANCE = 3f

    /**
     * The layouts a copy may be behind or ahead of: the window server composites Compose's frames and AppKit's views on a
     * loaded Mac (load 30 to 60 at these runs) up to a few frames apart.
     */
    private const val RECENT = 6

    /** Window pixels left out at each edge of the slot when looking for strips in it. */
    private const val SLOT_INSET = 6

    /** In the order the checks run. */
    enum class Motion(val label: String) {
        PaneDrag("pane-drag"),
        WindowResize("window-resize"),
        ListScroll("list-scroll"),
    }

    /**
     * What one motion run saw: [frames] stepped, [copies] judged; [misplaced] of them with the marker out of place or
     * not there, the [worst] of them; [gapFrames] that showed the window's backing, its [widest] band.
     */
    private data class Seen(
        val frames: Int,
        val copies: Int,
        val misplaced: Int,
        val worst: String,
        val gapFrames: Int,
        val widest: Int,
        val stripFrames: Int,
        val stripWidest: Int,
    ) {
        override fun toString(): String =
            "$misplaced of $copies copies show the page's marker out of its place${if (worst.isEmpty()) "" else " ($worst)"}, " +
                "$gapFrames show the window's backing (the widest band $widest px), $stripFrames show light strips in the " +
                "slot (the widest $stripWidest px), $frames frames"
    }

    private class Stage {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(config = RuntimeConfig(maxLive = 1, maxIdle = 0, snapshotCacheBytes = 16L shl 20), scope = scope)
        val state = WebViewState(PAGE)
        val scroll = ScrollState(0)
        var pane by mutableStateOf(0.dp)
        var slot: Rect? = null
    }

    @Composable
    private fun Scene(stage: Stage, motion: Motion) {
        val settings = WebSettings(darkMode = DarkMode.Light)
        val placed = Modifier.onGloballyPositioned { stage.slot = it.boundsInWindow() }
        ProvideWebViewRuntime(stage.runtime) {
            Box(Modifier.fillMaxSize().background(Color(0xFF202124))) {
                when (motion) {
                    Motion.ListScroll -> Column(Modifier.fillMaxSize().verticalScroll(stage.scroll)) {
                        Spacer(Modifier.height(240.dp))
                        WebView(stage.state, Modifier.padding(start = 120.dp).size(320.dp, 240.dp).then(placed), settings, activation = Activation.Eager)
                        Spacer(Modifier.height(1_200.dp))
                    }
                    Motion.PaneDrag -> Row(Modifier.padding(top = 240.dp, start = 120.dp)) {
                        Spacer(Modifier.width(stage.pane))
                        WebView(stage.state, Modifier.size(480.dp - stage.pane, 240.dp).then(placed), settings, activation = Activation.Eager)
                    }
                    Motion.WindowResize -> Box(Modifier.fillMaxSize().padding(top = 240.dp, start = 120.dp)) {
                        WebView(stage.state, Modifier.fillMaxWidth(0.5f).height(240.dp).then(placed), settings, activation = Activation.Eager)
                    }
                }
            }
        }
    }

    /**
     * One motion step at [t] seconds: the layout test's speeds, 0 → 160 dp → 0 every 1.5 s, starting from rest (a first
     * step of 80 dp at once would be a jump, not a motion: a native view can't move in Compose's frame, so a jump shows
     * the window's background where the page was for a frame, the README's limit).
     */
    private fun step(stage: Stage, motion: Motion, mac: MacCheckPlatform, t: Double, startSize: DpSize) {
        val wave = 80 * (1 - cos(2 * PI * t / 1.5))
        when (motion) {
            Motion.ListScroll -> stage.scroll.dispatchRawDelta((wave * mac.density).toFloat() - stage.scroll.value)
            Motion.PaneDrag -> stage.pane = wave.dp
            Motion.WindowResize -> mac.windowState.size = DpSize(startSize.width + (100 * (1 - cos(2 * PI * t / 1.5))).dp, startSize.height)
        }
    }

    /** The run of one motion, every frame copied around the slot and judged; then everything back where it was. */
    private suspend fun measure(stage: Stage, motion: Motion, mac: MacCheckPlatform): Seen {
        val startSize = mac.windowState.size
        val recent = ArrayDeque<Rect>()
        var frames = 0
        var copies = 0
        var misplaced = 0
        var worst = ""
        var worstOff = 0f
        var gapFrames = 0
        var widest = 0
        var stripFrames = 0
        var stripWidest = 0
        val s = mac.density
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = (now - start) / 1e9
            if (t >= MOTION_SECONDS) break
            step(stage, motion, mac, t, startSize)
            frames++
            val slot = stage.slot ?: continue
            recent.addLast(slot)
            if (recent.size > RECENT) recent.removeFirst()
            val around = recent.reduce { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
                .inflate(16 * s)
            val region = Rect(max(0f, around.left), max(0f, around.top), around.right, around.bottom)
            val pixels = mac.copy(region)?.pixels() ?: continue
            copies++
            val band = greenBand(pixels)
            if (band > 0) {
                gapFrames++
                widest = max(widest, band)
            }
            // What WebKit shows in the slot while it redraws the page at its new size: strips at its edges, which must be
            // the page's own background, never light (white or grey) on this deep blue page. 6 px in from the slot's edges,
            // where the hole and the view may differ by a pixel.
            val strip = lightBand(
                pixels,
                (slot.left - region.left).toInt() + SLOT_INSET,
                (slot.top - region.top).toInt() + SLOT_INSET,
                (slot.right - region.left).toInt() - SLOT_INSET,
                (slot.bottom - region.top).toInt() - SLOT_INSET,
            )
            if (strip > 0) {
                stripFrames++
                stripWidest = max(stripWidest, strip)
            }
            // The copy may be a frame behind the layout or ahead of it: the marker must be where one of the last slots
            // puts it, at its size.
            val marker = markerIn(pixels, region)
            val off = recent.minOf { place -> if (marker == null) Float.MAX_VALUE else distance(marker, expectedMarker(place, s)) }
            if (off > TOLERANCE) {
                misplaced++
                // The first few, kept for a person to see what the copy showed.
                if (misplaced <= 3) {
                    val copy = object : Captured {
                        override fun pixels(): Pixels = pixels
                    }
                    mac.save(copy, "${mac.folder("motion-${motion.label}", empty = misplaced == 1)}/misplaced-$frames.png")
                }
                if (off > worstOff) {
                    worstOff = off
                    worst = "the marker at $marker, its place ${expectedMarker(slot, s)}"
                }
            }
        }
        // Back where it was, and still, before the next run.
        when (motion) {
            Motion.ListScroll -> stage.scroll.dispatchRawDelta(-stage.scroll.value.toFloat())
            Motion.PaneDrag -> stage.pane = 0.dp
            Motion.WindowResize -> mac.windowState.size = startSize
        }
        delay(1_000)
        return Seen(frames, copies, misplaced, worst, gapFrames, widest, stripFrames, stripWidest)
    }

    /**
     * How thick light pixels (all three channels at least 200) show between ([x0], [y0]) and ([x1], [y1]) of [pixels]:
     * the smaller of their most in one row and in one column; 0 if none.
     */
    private fun lightBand(pixels: Pixels, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val left = max(0, x0)
        val top = max(0, y0)
        val right = min(pixels.width, x1)
        val bottom = min(pixels.height, y1)
        if (right <= left || bottom <= top) return 0
        val inRow = IntArray(bottom - top)
        val inColumn = IntArray(right - left)
        var any = false
        for (y in top until bottom) for (x in left until right) {
            val i = (y * pixels.width + x) * 4
            val r = pixels.rgba[i].toInt() and 0xff
            val g = pixels.rgba[i + 1].toInt() and 0xff
            val b = pixels.rgba[i + 2].toInt() and 0xff
            if (r >= 200 && g >= 200 && b >= 200) {
                inRow[y - top]++
                inColumn[x - left]++
                any = true
            }
        }
        if (!any) return 0
        return min(inRow.max(), inColumn.max())
    }

    private fun expectedMarker(slot: Rect, s: Float) = Rect(slot.left + 16 * s, slot.top + 16 * s, slot.left + 40 * s, slot.top + 40 * s)

    /** The most any edge of [a] is off [b]'s. */
    private fun distance(a: Rect, b: Rect): Float =
        maxOf(abs(a.left - b.left), abs(a.top - b.top), abs(a.right - b.right), abs(a.bottom - b.bottom))

    /** The red marker's box in [pixels], a copy of [region] (window px), or null. */
    private fun markerIn(pixels: Pixels, region: Rect): Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val i = (y * pixels.width + x) * 4
            val r = pixels.rgba[i].toInt() and 0xff
            val g = pixels.rgba[i + 1].toInt() and 0xff
            val b = pixels.rgba[i + 2].toInt() and 0xff
            if (r >= 200 && g <= 70 && b <= 70) {
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        if (right < 0) return null
        return Rect(region.left + left, region.top + top, region.left + right + 1, region.top + bottom + 1)
    }

    /** How thick the window's backing shows in [pixels]: the smaller of its most in one row and in one column; 0 if none. */
    private fun greenBand(pixels: Pixels): Int {
        val inRow = IntArray(pixels.height)
        val inColumn = IntArray(pixels.width)
        var any = false
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val i = (y * pixels.width + x) * 4
            val r = pixels.rgba[i].toInt() and 0xff
            val g = pixels.rgba[i + 1].toInt() and 0xff
            val b = pixels.rgba[i + 2].toInt() and 0xff
            if (g >= 200 && r <= 80 && b <= 80) {
                inRow[y]++
                inColumn[x]++
                any = true
            }
        }
        if (!any) return 0
        return min(inRow.max(), inColumn.max())
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(20_000) {
            while (!condition()) delay(20)
            true
        }
        if (met != true) throw AssertionError("no $what within 20 s")
    }

    val all: List<Check> = Motion.entries.map { motion ->
        Check("mac-a-${motion.label}-keeps-the-page-in-place-and-shows-no-gap", timeoutMillis = 120_000) {
            val mac = platform as MacCheckPlatform
            val stage = Stage()
            val startSize = mac.windowState.size
            compose { Scene(stage, motion) }
            try {
                waitFor("the page live") { stage.state.phase == WebViewPhase.Live }
                // The motion cover is the runtime's own snapshot, taken half a second after the first paint.
                waitFor("the page's snapshot") { stage.runtime.snapshots.count > 0 }
                // The backing again: a window resized or zoomed by an earlier check loses it, and then a gap shows the
                // window's own grey, which no count sees.
                MacEngineTesting.paintBacking(mac.window, MacCheckPlatform.BACKING)
                delay(500)
                if (motion != Motion.ListScroll) {
                    // The live page itself, uncovered, while it is redrawn at each new size: what the cover hides. Its
                    // edges trail in strips of WKWebView's own light background, whatever its under-page colour (white,
                    // WebKit's own, or the page's: tried on macOS 26). At least one copy must show them, or these copies
                    // can't see what the motion does, and the cover's run proves nothing.
                    MacEngineTesting.motionCover = false
                    val live = try {
                        measure(stage, motion, mac)
                    } finally {
                        MacEngineTesting.motionCover = true
                    }
                    println("MEASURE motion ${motion.label} without the cover: $live")
                    if (live.copies < 30) throw AssertionError("too few copies to judge: $live")
                    if (live.stripFrames == 0) {
                        throw AssertionError("INCONCLUSIVE: without the cover no copy showed the page's edges trailing, so these copies can't see what this motion does: $live")
                    }
                }
                val draws = MacEngineTesting.motionCoverDraws
                val seen = measure(stage, motion, mac)
                val coverDraws = MacEngineTesting.motionCoverDraws - draws
                println("MEASURE motion ${motion.label}: $seen; the cover drawn $coverDraws times")
                if (seen.copies < 30) throw AssertionError("too few copies to judge: $seen")
                assertEquals(0, seen.misplaced, "copies with the page's content out of its place ($seen)")
                assertEquals(0, seen.gapFrames, "copies that show the window's backing ($seen)")
                assertEquals(0, seen.stripFrames, "copies with light strips in the slot ($seen)")
                if (motion == Motion.ListScroll) {
                    assertEquals(0, coverDraws, "times the motion cover was drawn while the list scrolled: a move keeps the live page")
                } else {
                    assertTrue(coverDraws > 0, "the motion cover never went up while the page was resized")
                }
            } finally {
                mac.windowState.size = startSize
                compose(null)
                stage.runtime.clear()
                stage.runtime.onMemoryPressure()
                delay(300)
                stage.scope.cancel()
            }
        }
    }
}
