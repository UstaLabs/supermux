package dev.supermux.webview.sample.checks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * A window resized by a user's drag of its corner (#164: "the page jumps, as if the web view gets its new size late"), a
 * page filling it as an applet window's or a Browser tab's does. AppKit runs the drag as a live resize (its tracking
 * loop, `inLiveResize`), while a thread of its own copies the window as the window server composites it.
 *
 * The page lays itself out to its viewport: a red bar at its right edge, a yellow one at its bottom. In each copy, the
 * distance from the window's content edge to the bar is how far the page shown lags the window: 0 when the page is laid
 * out at the window's size; the width it grew by while the page (or a snapshot standing in for it) still has the old
 * one; a bar missing when the page is wider or taller than the window (it shrank). The same drag with the bars drawn by
 * Compose itself is the baseline: what the window can do at all.
 */
object ResizeChecks {
    private val PAGE = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>resize</title>
<style>html,body{margin:0;height:100%;background:#1e3a8a;overflow:hidden}
#r{position:fixed;right:0;top:120px;width:12px;height:80px;background:#ff0000}
#b{position:fixed;bottom:0;left:120px;height:12px;width:80px;background:#ffff00}</style></head>
<body><div id="r"></div><div id="b"></div></body></html>""",
    )

    /** One drag: the corner out by [DX] × [DY] points and back, every [PERIOD] seconds, for [SECONDS]. */
    private const val SECONDS = 3.0
    private const val PERIOD = 1.5
    private const val DX = 160.0
    private const val DY = 100.0

    /** What a copy may be off by, in points, and still count as in step. */
    private const val IN_STEP = 2

    enum class Mode(val label: String) { Compose("compose"), Page("page"), Unanchored("page-without-following-edges") }

    data class Seen(
        val mode: Mode,
        val copies: Int,
        val liveResize: Boolean,
        val rightGaps: List<Int>,
        val bottomGaps: List<Int>,
        val rightMissing: Int,
        val bottomMissing: Int,
        val backing: Int,
        val light: Int,
        val coverDraws: Int,
    ) {
        val behind: Int get() = rightGaps.count { it > IN_STEP } + bottomGaps.count { it > IN_STEP } + rightMissing + bottomMissing

        override fun toString(): String {
            fun q(list: List<Int>, p: Double) = if (list.isEmpty()) -1 else list.sorted()[((list.size - 1) * p).toInt()]
            return "${mode.label}: $copies copies (live resize $liveResize); right gap pt p50 ${q(rightGaps, 0.5)} p90 ${q(rightGaps, 0.9)} " +
                "max ${rightGaps.maxOrNull() ?: -1}, >$IN_STEP in ${rightGaps.count { it > IN_STEP }}, bar missing in $rightMissing; " +
                "bottom gap pt p50 ${q(bottomGaps, 0.5)} p90 ${q(bottomGaps, 0.9)} max ${bottomGaps.maxOrNull() ?: -1}, " +
                ">$IN_STEP in ${bottomGaps.count { it > IN_STEP }}, bar missing in $bottomMissing; copies behind $behind; " +
                "window backing in $backing, light in $light; cover drawn $coverDraws times"
        }
    }

    private class Stage {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(RuntimeConfig(maxLive = 1, maxIdle = 0, snapshotCacheBytes = 16L shl 20, scope = scope))
        val state = WebViewState(PAGE)
    }

    @Composable
    private fun Scene(stage: Stage, mode: Mode) {
        if (mode == Mode.Compose) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1E3A8A))) {
                Box(Modifier.align(Alignment.TopEnd).offset(y = 120.dp).size(12.dp, 80.dp).background(Color.Red))
                Box(Modifier.align(Alignment.BottomStart).offset(x = 120.dp).size(80.dp, 12.dp).background(Color.Yellow))
            }
            return
        }
        ProvideWebViewRuntime(stage.runtime) {
            WebView(stage.state, Modifier.fillMaxSize(), WebSettings(darkMode = DarkMode.Light), activation = Activation.Eager)
        }
    }

    /** The drag, on a thread of its own at 120 Hz, as a trackpad's; every copy judged on another. */
    private suspend fun drag(mac: MacCheckPlatform, mode: Mode): Seen = withContext(Dispatchers.IO) {
        val window = mac.window
        val draws = MacEngineTesting.motionCoverDraws
        var running = true
        val right = ArrayList<Int>()
        val bottom = ArrayList<Int>()
        var rightMissing = 0
        var bottomMissing = 0
        var copies = 0
        var backing = 0
        var light = 0
        var saved = 0
        // Two bands copied, a pixel a point: across the right edge at the red bar's height, and across the bottom at the
        // yellow bar's place, each wide enough for the whole drag. Small, so the copies come many times a refresh.
        val startWidth = window.contentPane.width
        val startHeight = window.contentPane.height
        val s = mac.density
        val rightBand = floatArrayOf((startWidth - 80f) * s, 0f, (DX.toFloat() + 160f) * s, 420f * s)
        val bottomBand = floatArrayOf(130f * s, (startHeight - 80f) * s, 60f * s, (DY.toFloat() + 160f) * s)
        val copier = thread(name = "resize-copier", isDaemon = true) {
            while (running) {
                val across = MacEngineTesting.copyPoints(window, rightBand[0], rightBand[1], rightBand[2], rightBand[3]) ?: continue
                val down = MacEngineTesting.copyPoints(window, bottomBand[0], bottomBand[1], bottomBand[2], bottomBand[3]) ?: continue
                fun alpha(c: dev.supermux.webview.testing.Copied, x: Int, y: Int) = c.rgba[(y * c.width + x) * 4 + 3].toInt() and 0xff
                fun isRed(c: dev.supermux.webview.testing.Copied, x: Int, y: Int): Boolean {
                    val i = (y * c.width + x) * 4
                    return (c.rgba[i].toInt() and 0xff) >= 200 && (c.rgba[i + 1].toInt() and 0xff) <= 70 && (c.rgba[i + 2].toInt() and 0xff) <= 70
                }
                fun isYellow(c: dev.supermux.webview.testing.Copied, x: Int, y: Int): Boolean {
                    val i = (y * c.width + x) * 4
                    return (c.rgba[i].toInt() and 0xff) >= 200 && (c.rgba[i + 1].toInt() and 0xff) >= 200 && (c.rgba[i + 2].toInt() and 0xff) <= 70
                }
                fun isGreen(c: dev.supermux.webview.testing.Copied, i: Int) =
                    (c.rgba[i + 1].toInt() and 0xff) >= 200 && (c.rgba[i].toInt() and 0xff) <= 80 && (c.rgba[i + 2].toInt() and 0xff) <= 80
                fun isLight(c: dev.supermux.webview.testing.Copied, i: Int) =
                    (c.rgba[i].toInt() and 0xff) >= 220 && (c.rgba[i + 1].toInt() and 0xff) >= 220 && (c.rgba[i + 2].toInt() and 0xff) >= 220
                // The bar's row: wherever the scene starts under the checks' own header.
                var row = -1
                for (y in 0 until across.height) {
                    if ((0 until across.width).any { isRed(across, it, y) }) { row = y + 6; break }
                }
                if (row < 0 || row >= across.height) row = across.height - 1
                var contentRight = -1
                for (x in across.width - 1 downTo 0) if (alpha(across, x, row) > 200) { contentRight = x; break }
                var redRight = -1
                for (x in 0..contentRight) if (isRed(across, x, row)) redRight = x
                val column = down.width / 2
                var contentBottom = -1
                for (y in down.height - 1 downTo 0) if (alpha(down, column, y) > 200) { contentBottom = y; break }
                var yellowBottom = -1
                for (y in 0..contentBottom) if (isYellow(down, column, y)) yellowBottom = y
                if (contentRight < 0 || contentBottom < 0) continue
                copies++
                if (redRight < 0) rightMissing++ else right += contentRight - redRight
                if (yellowBottom < 0) bottomMissing++ else bottom += contentBottom - yellowBottom
                var green = false
                var lightSeen = false
                for (c in listOf(across, down)) {
                    var i = 0
                    while (i < c.rgba.size) {
                        if ((c.rgba[i + 3].toInt() and 0xff) > 200) {
                            if (isGreen(c, i)) green = true
                            if (isLight(c, i)) lightSeen = true
                        }
                        i += 4
                    }
                }
                if (green) backing++
                if (lightSeen) light++
                val off = redRight < 0 || yellowBottom < 0 || contentRight - redRight > 8 || contentBottom - yellowBottom > 8
                if (off && saved < 4 && copies % 11 == 0) {
                    saved++
                    val pixels = Pixels(across.width, across.height, across.rgba)
                    mac.save(object : Captured { override fun pixels() = pixels }, "${mac.folder("resize-${mode.label}", empty = saved == 1)}/across-$copies.png")
                }
            }
        }
        var live = false
        try {
            MacEngineTesting.resizeDrag(window, 0, 0f, 0f)
            val start = System.nanoTime()
            while (true) {
                val t = (System.nanoTime() - start) / 1e9
                if (t >= SECONDS) break
                val wave = (1 - cos(2 * PI * t / PERIOD)) / 2
                if (MacEngineTesting.resizeDrag(window, 1, (DX * wave).toFloat(), (DY * wave).toFloat())) live = true
                LockSupport.parkNanos(8_333_333)
            }
            MacEngineTesting.resizeDrag(window, 2, 0f, 0f)
        } finally {
            running = false
            copier.join(2_000)
        }
        Seen(mode, copies, live, right, bottom, rightMissing, bottomMissing, backing, light, MacEngineTesting.motionCoverDraws - draws)
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(20_000) {
            while (!condition()) delay(20)
            true
        }
        if (met != true) throw AssertionError("no $what within 20 s")
    }

    /** One drag in [mode]; the window back at its size after. */
    private suspend fun CheckScope.run(mac: MacCheckPlatform, mode: Mode): Seen {
        val stage = Stage()
        val startSize = mac.windowState.size
        // Room on the screen to grow by the drag, both ways.
        mac.windowState.size = androidx.compose.ui.unit.DpSize(900.dp, 620.dp)
        compose { Scene(stage, mode) }
        try {
            if (mode != Mode.Compose) {
                waitFor("the page live") { stage.state.phase == WebViewPhase.Live }
                waitFor("the page's snapshot") { stage.runtime.snapshots.count > 0 }
            }
            MacEngineTesting.paintBacking(mac.window, MacCheckPlatform.BACKING)
            delay(800)
            MacEngineTesting.followEdges = mode != Mode.Unanchored
            val seen = try {
                drag(mac, mode)
            } finally {
                MacEngineTesting.followEdges = true
            }
            println("MEASURE live-resize $seen; frame sync ${MacEngineTesting.frameSyncState(mac.window)}")
            return seen
        } finally {
            mac.windowState.size = startSize
            compose(null)
            stage.runtime.clear()
            stage.runtime.onMemoryPressure()
            delay(500)
            stage.scope.cancel()
        }
    }

    /**
     * The real-window check: the same drag of a page filling the window as of one Compose draws itself. The page must
     * keep up with the window as well as Compose does (#164), give or take what a copy may see between two frames, and
     * never be covered by a still picture.
     */
    val all: List<Check> = listOf(
        Check("mac-a-live-resize-keeps-the-page-in-step-with-the-window", timeoutMillis = 120_000) {
            val mac = platform as MacCheckPlatform
            val compose = run(mac, Mode.Compose)
            val page = run(mac, Mode.Page)
            for (seen in listOf(compose, page)) {
                assertTrue(seen.liveResize, "AppKit didn't run the drag as a live resize: $seen")
                if (seen.copies < 60) throw AssertionError("too few copies to judge: $seen")
            }
            assertEquals(0, page.coverDraws, "times the motion cover was drawn: $page")
            fun p90(list: List<Int>) = list.sorted()[(list.size - 1) * 9 / 10]
            assertTrue(page.rightMissing + page.bottomMissing <= compose.rightMissing + compose.bottomMissing + 3, "the page wider or taller than the window in more copies than Compose's own scene: $page, against $compose")
            assertTrue(p90(page.rightGaps) <= p90(compose.rightGaps) + 6, "the page's right edge behind the window's: $page, against $compose")
            assertTrue(p90(page.bottomGaps) <= p90(compose.bottomGaps) + 6, "the page's bottom edge behind the window's: $page, against $compose")
        },
    )

    /** The three drags, measured: what the window does, the page as the engine shows it, the page with no cover. */
    val measure: List<Check> = listOf(
        Check("mac-live-resize-measure", timeoutMillis = 180_000) {
            val mac = platform as MacCheckPlatform
            repeat(2) { for (mode in Mode.entries) run(mac, mode) }
        },
    )
}
