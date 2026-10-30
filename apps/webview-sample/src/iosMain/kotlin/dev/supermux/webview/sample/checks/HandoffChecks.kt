package dev.supermux.webview.sample.checks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIView
import platform.WebKit.WKWebView
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Seamless handoff (the seamless-handoff plan; on Android, HandoffFlashTest): when a view goes from its cover to the
 * live page, no frame on screen differs from the page.
 * - A still page, light or dark, in a 360 × 300 pt `WebView` over a grey box (grey shows where nothing is drawn), with a
 *   sharp cover in the cache and a magenta placeholder, which should never show.
 * - The window is copied at every display tick from 3 ticks before the cover lifts to 10 after ([FrameRecorder]), and
 *   each frame with the view in its place is compared with the page as it ends up: a mean channel difference of at most
 *   2 and a 99th percentile of at most 16 pass.
 * - A frame that doesn't is saved as a PNG under the app's `Documents/handoff/`, and described: how much of it is
 *   white, black, the page's own background, grey or magenta.
 * - Three ways onto the screen: a cold activation from the cover, a parked page adopted, and a page hidden then shown
 *   again (hidden, not disposed).
 */
object HandoffChecks {
    enum class Scenario(val label: String, val hiddenReleaseMillis: Long) {
        Cold("a cold activation from the cover", hiddenReleaseMillis = 0),
        Adopt("adopting a parked page", hiddenReleaseMillis = 600_000),
        Shown("a page hidden, then shown again", hiddenReleaseMillis = 600_000),
    }

    enum class Look(val label: String, val background: Int, private val text: String, private val bars: String) {
        Light("light", 0xffffff, "#1d2433", "#4f7cff"),
        Dark("dark", 0x121212, "#e8eaed", "#8ab4f8"),
        ;

        val content: WebContent.Html get() = WebContent.Html(
            """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>$label</title>
<style>html,body{margin:0;background:#${background.toString(16).padStart(6, '0')};color:$text;font:15px/1.4 sans-serif}
body{padding:16px}h1{font-size:20px;margin:0 0 8px}.bars{display:flex;align-items:flex-end;gap:8px;height:90px;margin:12px 0}
.bars span{display:block;width:36px;background:$bars}</style></head><body><h1>Quarterly numbers ($label)</h1>
<p>The agent pulled the figures and drew them. Revenue rose in every month but one.</p>
<div class="bars"><span style="height:60px"></span><span style="height:90px"></span><span style="height:40px"></span>
<span style="height:75px"></span><span style="height:55px"></span></div>
<p>Numbers from the nightly run.</p></body></html>""",
        )
    }

    /** One round of each scenario, light and dark. */
    val all: List<Check> = listOf(
        Check("handoff-cold-activation-shows-no-flash") { check(Scenario.Cold) },
        Check("handoff-adoption-shows-no-flash") { check(Scenario.Adopt) },
        Check("handoff-hidden-then-shown-shows-no-flash") { check(Scenario.Shown) },
    )

    /** Every scenario, light and dark, five rounds in a row: run on its own (`-only handoff-five-runs-in-a-row`). */
    val fiveRuns: Check = Check("handoff-five-runs-in-a-row", timeoutMillis = 20 * 60_000L) {
        val failures = (1..5).flatMap { round ->
            Scenario.entries.flatMap { scenario -> Look.entries.flatMap { look -> runOnce(scenario, look, round) } }
        }
        assertTrue(failures.isEmpty(), "${failures.size} frames differ from the page:\n${failures.joinToString("\n")}")
    }

    private suspend fun CheckScope.check(scenario: Scenario) {
        val failures = Look.entries.flatMap { runOnce(scenario, it, round = 1) }
        assertTrue(failures.isEmpty(), "${scenario.label}: ${failures.size} frames differ from the page:\n${failures.joinToString("\n")}")
    }

    /** What one run shows, and what it changes to set the scene. */
    private class Stage(scenario: Scenario, look: Look) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            config = RuntimeConfig(
                maxLive = 2,
                maxIdle = 1,
                snapshotCacheBytes = 32L shl 20,
                hiddenReleaseMillis = scenario.hiddenReleaseMillis,
            ),
            scope = scope,
        )
        val state = WebViewState(look.content)
        var composed by mutableStateOf(true)
        var shown by mutableStateOf(true)

        /** An opaque box the canvas draws over the left half of the page, to see that the copies show the canvas. */
        var overlay by mutableStateOf(false)

        /** The view's place in the window, in the canvas's pixels, where it shows when shown; and pixels a point. */
        var place: Rect? = null
        var density = 1f

        /** Where the view is laid out now, in the window: its place, or below it while hidden. */
        var viewTop: Float? = null

        /** The view's place in window points. */
        fun region(): CValue<CGRect>? = place?.let {
            CGRectMake(it.left / density + 0.0, it.top / density + 0.0, it.width / density + 0.0, it.height / density + 0.0)
        }
    }

    /** One scenario with one page: the frames that differ from the page, as lines; none if it passed. */
    private suspend fun CheckScope.runOnce(scenario: Scenario, look: Look, round: Int): List<String> {
        val stage = Stage(scenario, look)
        compose { Scene(stage) }
        try {
            // Live, painted, and its settle snapshot taken: the cover in the cache.
            waitFor("${look.label}: live") { stage.state.phase == WebViewPhase.Live }
            waitFor("${look.label}: a snapshot") { stage.runtime.snapshots.count > 0 }
            // The copies must show what the canvas draws over a page, as it draws the cover: a blue box over its left half.
            stage.overlay = true
            delay(300)
            val window = UIApplication.sharedApplication.keyWindow ?: throw AssertionError("no window")
            val place = stage.region() ?: throw AssertionError("the view's place")
            val overImage = FrameRecorder.copy(window, place, window.screen.scale)
            val over = pixelsOf(overImage) ?: throw AssertionError("no copy")
            stage.overlay = false
            val blue = share(over, leftHalf = true) { r, g, b -> r <= 8 && g <= 8 && b >= 247 }
            if (blue < 95) throw AssertionError("a copy of the window doesn't show the canvas over the page: $blue % of the box")
            delay(800)
            when (scenario) {
                Scenario.Cold -> {
                    stage.shown = false
                    waitFor("${look.label}: cold") { stage.state.phase == WebViewPhase.Cold && stage.runtime.pool.liveCount == 0 }
                }
                Scenario.Adopt -> {
                    stage.composed = false
                    waitFor("${look.label}: parked") { stage.runtime.pool.parkedCount == 1 }
                }
                Scenario.Shown -> stage.shown = false
            }
            // Long enough for a hidden page to have its media suspended, and a parked one to be out of its window a while.
            delay(1_500)
            val region = stage.region() ?: throw AssertionError("the view's place")
            val scale = window.screen.scale
            // The copies must show the canvas, where the cover is drawn: with the view away, its place shows the grey box.
            if (scenario != Scenario.Shown) {
                val away = pixelsOf(FrameRecorder.copy(window, region, scale)) ?: throw AssertionError("no copy of the window")
                val grey = greyShare(away)
                if (grey < 95) {
                    throw AssertionError("a copy of the window doesn't show the canvas: its place shows $grey % of the grey box")
                }
            }
            val recorder = FrameRecorder(window, region, scale, before = 3, after = 10) { probe(stage) }
            recorder.start()
            delay(150)
            recorder.arm()
            when (scenario) {
                Scenario.Adopt -> stage.composed = true
                Scenario.Cold, Scenario.Shown -> stage.shown = true
            }
            val frames = withTimeoutOrNull(15_000) { recorder.frames() } ?: run {
                recorder.stop()
                recorder.frames()
            }
            val lift = recorder.liftIndex
            // The page as it ends up.
            delay(1_500)
            val final = FrameRecorder.copy(window, region, scale)
            val name = "$round-${scenario.name.lowercase()}-${look.name.lowercase()}"
            // The page as it was when its cover was taken, against the page as it ends up: the right half, beside the box.
            val firstLive = pixelsOf(overImage)
            val lastLive = pixelsOf(final)
            if (firstLive != null && lastLive != null) {
                println(
                    "MEASURE handoff-first-live $name: the page live at first against the page at the end, right half: " +
                        "${rightHalf(firstLive, lastLive)}",
                )
            }
            val flashes = judge(name, look, frames, lift, recorder, final)
            save(overImage, "${folder(name, empty = false)}/first-live.png")
            return flashes
        } finally {
            compose(null)
            stage.runtime.clear()
            stage.runtime.onMemoryPressure()
            delay(300)
            stage.scope.cancel()
        }
    }

    @Composable
    private fun Scene(stage: Stage) {
        stage.density = LocalDensity.current.density
        ProvideWebViewRuntime(stage.runtime) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier
                        .size(360.dp, 300.dp)
                        .background(BEHIND)
                        .clipToBounds()
                        .onGloballyPositioned { stage.place = it.boundsInWindow() },
                ) {
                    if (stage.composed) {
                        WebView(
                            stage.state,
                            Modifier
                                .size(360.dp, 300.dp)
                                .offset(y = if (stage.shown) 0.dp else 400.dp)
                                .onGloballyPositioned { stage.viewTop = it.boundsInWindow().top },
                            settings = WebSettings(darkMode = DarkMode.Light),
                            activation = Activation.WhenVisible(settleMillis = 0),
                            placeholder = { Box(Modifier.fillMaxSize().background(PLACEHOLDER)) },
                        )
                    }
                    if (stage.overlay) Box(Modifier.size(180.dp, 300.dp).background(OVERLAY))
                }
            }
        }
    }

    /** Whether the view is in its place and its page painted, and what its web view does, at a tick. */
    private fun probe(stage: Stage): FrameRecorder.Probe {
        val painted = stage.state.phase == WebViewPhase.Live
        val top = stage.viewTop
        val place = stage.place
        val inPlace = stage.composed && top != null && place != null && abs(top - place.top) <= 1f
        val says = "phase ${stage.state.phase}, web view ${describe(shownWebView())}"
        return FrameRecorder.Probe(inPlace = inPlace, lifted = inPlace && painted, says = says)
    }

    /** "in its window, shown", or what hides it: the web view itself or a view it is in. */
    private fun describe(view: WKWebView?): String {
        if (view == null) return "none in the window"
        var hiddenBy: String? = null
        var at: UIView? = view
        while (at != null && hiddenBy == null) {
            if (at.hidden) hiddenBy = "${at::class.simpleName} hidden"
            else if (at.alpha < 0.01) hiddenBy = "${at::class.simpleName} at alpha ${at.alpha}"
            at = at.superview
        }
        return if (hiddenBy == null) "shown" else "hidden ($hiddenBy)"
    }

    /** Each frame in place against [final]; the ones that differ are saved and described. */
    private fun judge(
        name: String,
        look: Look,
        frames: List<FrameRecorder.Frame>,
        lift: Int?,
        recorder: FrameRecorder,
        final: UIImage,
    ): List<String> {
        val dir = folder(name)
        save(final, "$dir/final.png")
        val page = pixelsOf(final) ?: return listOf("$name: no copy of the final page")
        val copies = recorder.copyMillis.sorted()
        val copyCost = if (copies.isEmpty()) "" else "; a copy took p50 ${copies[copies.size / 2].rounded()} ms, max ${copies.last().rounded()} ms"
        if (lift == null || frames.isEmpty()) {
            val line = "$name: the cover never lifted (${frames.size} frames, armed at ${recorder.armedAt})"
            println("MEASURE handoff $line")
            return listOf(line)
        }
        val flashes = ArrayList<String>()
        var passed = 0
        var away = 0
        for (frame in frames) {
            val at = frame.index - lift
            // Before the view is back in its place there is nothing of it to see there: not a frame of the handoff.
            if (!frame.inPlace) {
                away++
                continue
            }
            val pixels = pixelsOf(frame.image) ?: continue
            val difference = Difference.of(pixels, page)
            if (difference.mean <= 2.0 && difference.p99 <= 16) {
                passed++
                continue
            }
            val label = if (at >= 0) "+$at" else "$at"
            save(frame.image, "$dir/frame$label.png")
            val line = "$name frame $label (${frame.probe.says}; the tick before: ${frame.earlier.lastOrNull()?.says}): $difference; " +
                describe(pixels, page, look)
            println("MEASURE handoff-frame $line")
            flashes += line
        }
        println(
            "MEASURE handoff $name: ${frames.size} frames from ${frames.first().index - lift} to +${frames.last().index - lift} " +
                "around the lift (tick ${lift - (recorder.armedAt ?: lift)} after the trigger), ${flashes.size} differ from the " +
                "page, $passed look like it, $away before the view was in its place$copyCost; the tick before the lift: " +
                "${frames.firstOrNull { it.index == lift - 1 }?.probe?.says}",
        )
        return flashes
    }

    /** What a frame that differs from [page] shows: how much of it is white, black, the page's background, grey, magenta. */
    private fun describe(frame: Pixels, page: Pixels, look: Look): String {
        fun share(of: Pixels, test: (Int, Int, Int) -> Boolean): Int {
            var n = 0
            var i = 0
            while (i < of.rgba.size) {
                if (test(of.rgba[i].toInt() and 0xff, of.rgba[i + 1].toInt() and 0xff, of.rgba[i + 2].toInt() and 0xff)) n++
                i += 4
            }
            return n * 100 / (of.width * of.height)
        }
        fun near(r: Int, g: Int, b: Int, rgb: Int) =
            abs(r - (rgb shr 16 and 0xff)) <= 8 && abs(g - (rgb shr 8 and 0xff)) <= 8 && abs(b - (rgb and 0xff)) <= 8
        val white = { r: Int, g: Int, b: Int -> r >= 245 && g >= 245 && b >= 245 }
        val black = { r: Int, g: Int, b: Int -> r <= 12 && g <= 12 && b <= 12 }
        val background = { r: Int, g: Int, b: Int -> near(r, g, b, look.background) }
        val grey = { r: Int, g: Int, b: Int -> near(r, g, b, 0x808080) }
        val magenta = { r: Int, g: Int, b: Int -> near(r, g, b, 0xff00ff) }
        return "white ${share(frame, white)} % (the page ${share(page, white)} %), black ${share(frame, black)} %, " +
            "the page's background ${share(frame, background)} % (the page ${share(page, background)} %), " +
            "grey ${share(frame, grey)} %, magenta ${share(frame, magenta)} %"
    }

    /** How much of [pixels], or of its left half, is what [test] says, in percent. */
    private fun share(pixels: Pixels, leftHalf: Boolean, test: (Int, Int, Int) -> Boolean): Int {
        val columns = if (leftHalf) pixels.width / 2 else pixels.width
        var n = 0
        for (y in 0 until pixels.height) for (x in 0 until columns) {
            val i = (y * pixels.width + x) * 4
            if (test(pixels.rgba[i].toInt() and 0xff, pixels.rgba[i + 1].toInt() and 0xff, pixels.rgba[i + 2].toInt() and 0xff)) n++
        }
        return n * 100 / (columns * pixels.height)
    }

    /** How much of [pixels] is the grey box behind the view, in percent. */
    private fun greyShare(pixels: Pixels): Int {
        var n = 0
        var i = 0
        while (i < pixels.rgba.size) {
            val r = pixels.rgba[i].toInt() and 0xff
            val g = pixels.rgba[i + 1].toInt() and 0xff
            val b = pixels.rgba[i + 2].toInt() and 0xff
            if (abs(r - 0x80) <= 8 && abs(g - 0x80) <= 8 && abs(b - 0x80) <= 8) n++
            i += 4
        }
        return n * 100 / (pixels.width * pixels.height)
    }

    /** [a]'s right half against [b]'s. */
    private fun rightHalf(a: Pixels, b: Pixels): Difference {
        fun half(p: Pixels): Pixels {
            val columns = p.width - p.width / 2
            val out = ByteArray(columns * p.height * 4)
            for (y in 0 until p.height) p.rgba.copyInto(out, y * columns * 4, (y * p.width + p.width / 2) * 4, (y * p.width + p.width) * 4)
            return Pixels(columns, p.height, out)
        }
        return Difference.of(half(a), half(b))
    }

    /** The folder for [name]'s frames, emptied unless not [empty]. */
    private fun folder(name: String, empty: Boolean = true): String {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val dir = "$documents/handoff/$name"
        if (empty) NSFileManager.defaultManager.removeItemAtPath(dir, null)
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    private fun save(image: UIImage, path: String) {
        UIImagePNGRepresentation(image)?.writeToFile(path, atomically = true)
    }

    private fun Double.rounded(): Double = (this * 10).toLong() / 10.0

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(20_000) {
            while (!condition()) delay(20)
            true
        }
        if (met != true) throw AssertionError("no $what within 20 s")
    }

    /** Behind the view: where neither the cover nor the page is drawn, a frame shows this grey. */
    private val BEHIND = Color(0xFF808080)

    /** The box the canvas draws over the page, to see that the copies show the canvas. */
    private val OVERLAY = Color(0xFF0000FF)

    /** The placeholder, which should never show: the cover is in the cache. */
    private val PLACEHOLDER = Color(0xFFFF00FF)
}
