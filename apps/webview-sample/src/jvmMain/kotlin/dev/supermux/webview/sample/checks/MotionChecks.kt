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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The motion lag (M6) and the motion cover that hides it (D2): a live page in a list that scrolls, a pane whose
 * divider is dragged, and a window resized, each for [MOTION_SECONDS] at the layout test's speeds, without the cover and
 * then with it. Every frame the window is copied around the page's slot, and its backing (pure green, painted by
 * Main.kt) counted: green shows only where neither Compose nor a page draws.
 */
object MotionChecks {
    /** A page with nothing green or white: deep blue inside an amber border. */
    private val PAGE = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>motion</title>
<style>html,body{margin:0;height:100%}body{box-sizing:border-box;background:#1e3a8a;border:6px solid #f59e0b}</style></head>
<body></body></html>""",
    )

    private const val MOTION_SECONDS = 3.0

    enum class Motion(val label: String) {
        ListScroll("list-scroll"),
        PaneDrag("pane-drag"),
        WindowResize("window-resize"),
    }

    /** What one motion run saw: [frames] stepped, [copies] judged, [gapFrames] that showed the backing, its [widest] band. */
    private data class Gaps(val frames: Int, val copies: Int, val gapFrames: Int, val widest: Int) {
        override fun toString(): String = "$gapFrames of $copies copies show the window's backing (the widest band $widest px), $frames frames"
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

    /** One motion step at [t] seconds: the layout test's sinusoids, 0 → 160 dp → 0 every 1.5 s. */
    private fun step(stage: Stage, motion: Motion, mac: MacCheckPlatform, t: Double, startSize: DpSize) {
        val wave = 80 + 80 * sin(2 * PI * t / 1.5)
        when (motion) {
            Motion.ListScroll -> stage.scroll.dispatchRawDelta((wave * mac.density).toFloat() - stage.scroll.value)
            Motion.PaneDrag -> stage.pane = wave.dp
            Motion.WindowResize -> mac.windowState.size = DpSize(startSize.width + (100 * (1 - cos(2 * PI * t / 1.5))).dp, startSize.height)
        }
    }

    /** The run of one motion, every frame copied around the slot; then everything back where it was. */
    private suspend fun measure(stage: Stage, motion: Motion, mac: MacCheckPlatform, coverOn: Boolean): Gaps {
        MacEngineTesting.motionCover = coverOn
        val startSize = mac.windowState.size
        val recent = ArrayDeque<Rect>()
        var frames = 0
        var copies = 0
        var gapFrames = 0
        var widest = 0
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = (now - start) / 1e9
            if (t >= MOTION_SECONDS) break
            step(stage, motion, mac, t, startSize)
            frames++
            val slot = stage.slot ?: continue
            recent.addLast(slot)
            if (recent.size > 3) recent.removeFirst()
            val around = recent.reduce { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
                .inflate(16 * mac.density)
            val region = Rect(max(0f, around.left), max(0f, around.top), around.right, around.bottom)
            val pixels = mac.copy(region)?.pixels() ?: continue
            copies++
            val band = greenBand(pixels)
            if (band > 0) {
                gapFrames++
                widest = max(widest, band)
            }
        }
        // Back where it was, and still, before the next run.
        when (motion) {
            Motion.ListScroll -> stage.scroll.dispatchRawDelta(-stage.scroll.value.toFloat())
            Motion.PaneDrag -> stage.pane = 0.dp
            Motion.WindowResize -> mac.windowState.size = startSize
        }
        delay(1_000)
        MacEngineTesting.motionCover = true
        return Gaps(frames, copies, gapFrames, widest)
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
        Check("mac-a-${motion.label}-never-shows-a-gap-at-a-live-page", timeoutMillis = 120_000) {
            val mac = platform as MacCheckPlatform
            val stage = Stage()
            val startSize = mac.windowState.size
            compose { Scene(stage, motion) }
            try {
                waitFor("the page live") { stage.state.phase == WebViewPhase.Live }
                // The motion cover is the runtime's own snapshot, taken half a second after the first paint.
                waitFor("the page's snapshot") { stage.runtime.snapshots.count > 0 }
                delay(500)
                val without = measure(stage, motion, mac, coverOn = false)
                println("MEASURE motion ${motion.label} without the cover: $without")
                val with = measure(stage, motion, mac, coverOn = true)
                println("MEASURE motion ${motion.label} with the cover: $with")
                if (without.copies < 30 || with.copies < 30) throw AssertionError("too few copies to judge: $without; $with")
                if (without.gapFrames == 0) {
                    throw AssertionError("without the cover no copy showed a gap, so these copies can't see the lag and the cover's run proves nothing: $without")
                }
                assertEquals(0, with.gapFrames, "copies that show the window's backing with the cover ($with)")
            } finally {
                MacEngineTesting.motionCover = true
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
