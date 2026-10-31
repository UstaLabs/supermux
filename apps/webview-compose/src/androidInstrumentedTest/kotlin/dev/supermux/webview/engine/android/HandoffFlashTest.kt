package dev.supermux.webview.engine.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.View
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermux.webview.Activation
import dev.supermux.webview.DarkMode
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewHost
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Seamless handoff, on the phone: when a view goes from its cover to the live page, no frame on screen differs from the
 * page.
 * - A still page, light or dark, in a 360 × 300 dp view over a grey box (grey shows where nothing is drawn), with a
 *   sharp cover in the cache.
 * - The window is copied every frame from 3 frames before the cover lifts to 10 after, and each frame is compared with
 *   the page as it ends up: a mean channel difference of at most 2 and a 99th percentile of at most 16 pass.
 * - A frame that doesn't is saved as a PNG (under the test APK's external files, `handoff/`, which
 *   `device-checks/android-instrumented.sh` copies off the phone) and described in logcat: how much of it is white,
 *   grey or the magenta placeholder, and the shift that would best match it to the page.
 */
@RunWith(AndroidJUnit4::class)
class HandoffFlashTest {
    private enum class Scenario(val label: String, val hiddenReleaseMillis: Long) {
        Cold("a cold activation from the cover", hiddenReleaseMillis = 0),
        Adopt("adopting a parked page", hiddenReleaseMillis = 600_000),
        Shown("a page hidden, then shown again", hiddenReleaseMillis = 600_000),
    }

    private enum class Look(val label: String, val background: String, val text: String, val bars: String) {
        Light("light", "#ffffff", "#1d2433", "#4f7cff"),
        Dark("dark", "#121212", "#e8eaed", "#8ab4f8"),
        ;

        val content: WebContent.Html = WebContent.Html(
            """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>$label</title>
<style>html,body{margin:0;background:$background;color:$text;font:15px/1.4 sans-serif}body{padding:16px}
h1{font-size:20px;margin:0 0 8px}.bars{display:flex;align-items:flex-end;gap:8px;height:90px;margin:12px 0}
.bars span{display:block;width:36px;background:$bars}</style></head><body><h1>Quarterly numbers ($label)</h1>
<p>The agent pulled the figures and drew them. Revenue rose in every month but one.</p>
<div class="bars"><span style="height:60px"></span><span style="height:90px"></span><span style="height:40px"></span>
<span style="height:75px"></span><span style="height:55px"></span></div>
<p>Numbers from the nightly run.</p></body></html>""",
        )
    }

    @Test fun aColdActivationFromTheCoverShowsNoFlash() = check(Scenario.Cold)

    @Test fun adoptingAParkedPageShowsNoFlash() = check(Scenario.Adopt)

    @Test fun aPageHiddenThenShownShowsNoFlash() = check(Scenario.Shown)

    private fun check(scenario: Scenario) {
        val failures = Look.entries.flatMap { run(scenario, it) }
        assertTrue(failures.isEmpty(), "${scenario.label}:\n${failures.joinToString("\n")}")
    }

    /** What one test shows, and what it changes to set the scene. */
    private class Stage(scenario: Scenario, look: Look) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(
                maxLive = 2,
                maxIdle = 1,
                snapshotCacheBytes = 32L shl 20,
                hiddenReleaseMillis = scenario.hiddenReleaseMillis,
                scope = scope,
            ),
        )
        val state = WebViewState(look.content)
        var composed by mutableStateOf(true)
        var shown by mutableStateOf(true)

        /** The view's place in the window, where it shows when shown. */
        @Volatile var region: Rect? = null

        /** Where the view is laid out now, in the window: its place, or below it while hidden. Main thread. */
        var viewTop: Int? = null
    }

    /** One scenario with one page: the frames that flashed, as lines; none if it passed. */
    private fun run(scenario: Scenario, look: Look): List<String> {
        val stage = Stage(scenario, look)
        val launched = ActivityScenario.launch(EngineTestActivity::class.java)
        try {
            var activity: EngineTestActivity? = null
            launched.onActivity { shown ->
                activity = shown
                shown.setContent { Scene(stage) }
            }
            val window = assertNotNull(activity).window
            return runBlocking {
                // Live, painted, and its settle snapshot taken: the cover in the cache.
                waitFor("${look.label}: live") { stage.state.phase == WebViewPhase.Live }
                waitFor("${look.label}: a snapshot") { main { stage.state.snapshotKey != null } }
                delay(800)
                when (scenario) {
                    Scenario.Cold -> {
                        main { stage.shown = false }
                        waitFor("${look.label}: cold") {
                            main { stage.state.phase == WebViewPhase.Cold && stage.runtime.pool.liveCount == 0 }
                        }
                    }
                    Scenario.Adopt -> {
                        main { stage.composed = false }
                        waitFor("${look.label}: parked") { main { stage.runtime.pool.parkedCount == 1 } }
                    }
                    Scenario.Shown -> main { stage.shown = false }
                }
                // Long enough for a hidden page to be paused, and a parked one to be out of its window for a while.
                delay(1_500)
                val region = assertNotNull(stage.region, "the view's place")
                val recorder = FrameRecorder(window, region, before = 3, after = 10) { probe(stage) }
                main { recorder.start() }
                delay(150)
                main {
                    recorder.arm()
                    when (scenario) {
                        Scenario.Adopt -> stage.composed = true
                        Scenario.Cold, Scenario.Shown -> stage.shown = true
                    }
                }
                val frames = withTimeoutOrNull(15_000) { recorder.frames() } ?: emptyList()
                val lift = recorder.liftIndex
                // The page as it ends up.
                delay(1_500)
                val final = assertNotNull(main { FrameRecorder.copy(window, region) }, "a copy of the final page")
                judge(scenario, look, frames, lift, recorder.armedAt, final)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { stage.scope.cancel() }
            launched.close()
        }
    }

    @Composable
    private fun Scene(stage: Stage) {
        ProvideWebViewRuntime(stage.runtime) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(360.dp, 300.dp)
                        .background(BEHIND)
                        .clipToBounds()
                        .onGloballyPositioned { bounds ->
                            val r = bounds.boundsInWindow()
                            val (left, top) = r.left.roundToInt() to r.top.roundToInt()
                            stage.region = Rect(left, top, r.right.roundToInt(), r.bottom.roundToInt())
                        },
                ) {
                    if (stage.composed) {
                        WebView(
                            stage.state,
                            Modifier
                                .size(360.dp, 300.dp)
                                .offset(y = if (stage.shown) 0.dp else 400.dp)
                                .onGloballyPositioned { stage.viewTop = it.boundsInWindow().top.roundToInt() },
                            settings = WebSettings(darkMode = DarkMode.Light),
                            activation = Activation.WhenVisible(settleMillis = 0),
                            placeholder = { Box(Modifier.fillMaxSize().background(PLACEHOLDER)) },
                        )
                    }
                }
            }
        }
    }

    /** Whether the view is in its place and its cover lifted, and what its host and WebView do, as a frame draws. */
    private fun probe(stage: Stage): FrameRecorder.Probe {
        val host = stage.state.owner as? WebViewHost
        val view = (stage.state.engine as? AndroidWebEngine)?.webView
        val cover = host?.coverToDraw(false) != null
        val painted = host?.painted == true
        val visibility = when (view?.visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            View.GONE -> "gone"
            else -> "none"
        }
        val says = "cover ${if (cover) "on" else "off"}, painted $painted, WebView $visibility" +
            view.describe() +
            ", ${stage.state.phase::class.simpleName}"
        val top = stage.viewTop
        val place = stage.region
        val inPlace = stage.composed && top != null && place != null && abs(top - place.top) <= 1
        val lifted = inPlace && host != null && painted && !cover
        return FrameRecorder.Probe(inPlace = inPlace, lifted = lifted, says = says)
    }

    /** Each frame against [final]; the ones that differ are saved and described. */
    private fun judge(
        scenario: Scenario,
        look: Look,
        frames: List<FrameRecorder.Frame>,
        lift: Int?,
        armedAt: Int?,
        final: Bitmap,
    ): List<String> {
        val name = "${scenario.name.lowercase()}-${look.name.lowercase()}"
        val files = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val dir = File(files, "handoff/$name")
        dir.deleteRecursively()
        dir.mkdirs()
        save(final, File(dir, "final.png"))
        if (lift == null || frames.isEmpty()) {
            val line = "$name: the cover never lifted (${frames.size} frames, armed at $armedAt)"
            Log.i(TAG, "handoff $line")
            return listOf(line)
        }
        val flashes = ArrayList<String>()
        var passed = 0
        var away = 0
        for (frame in frames) {
            val at = frame.index - lift
            // Before the view is back in its place there is nothing of it to see there: not a frame of the handoff.
            if (!frame.probe.inPlace) {
                away++
                continue
            }
            val difference = PictureDifference.of(frame.bitmap, final)
            val label = "${if (at >= 0) "+" else ""}$at"
            if (difference.looksTheSame) {
                passed++
                continue
            }
            val file = File(dir, "frame${"%+03d".format(at)}.png")
            save(frame.bitmap, file)
            val line = "$name frame $label (${frame.probe.says}): $difference; ${describe(frame.bitmap, final)}"
            Log.i(TAG, "handoff $line")
            flashes += line
        }
        Log.i(
            TAG,
            "handoff $name: ${frames.size} frames from ${frames.first().index - lift} to " +
                "+${frames.last().index - lift} around the lift " +
                "(frame ${lift - (armedAt ?: lift)} after the trigger), " +
                "${flashes.size} differ from the page, " +
                "$passed look like it, $away before the view was in its place",
        )
        return flashes
    }

    /**
     * What a frame that differs from [final] shows: how much of it is white, grey (nothing drawn) or magenta (the
     * placeholder), and the shift that best matches it.
     */
    private fun describe(frame: Bitmap, final: Bitmap): String {
        val pixels = PictureDifference.pixels(frame)
        val page = PictureDifference.pixels(final)
        fun share(of: IntArray, test: (Int) -> Boolean) = of.count(test) * 100 / of.size
        val white = { p: Int -> (p shr 16 and 0xFF) >= 245 && (p shr 8 and 0xFF) >= 245 && (p and 0xFF) >= 245 }
        val grey = { p: Int -> near(p, 0x80, 0x80, 0x80) }
        val magenta = { p: Int -> near(p, 0xFF, 0x00, 0xFF) }
        val shift = bestShift(pixels, page, frame.width, frame.height)
        return "white ${share(pixels, white)} % (the page ${share(page, white)} %), grey ${share(pixels, grey)} %, " +
            "magenta ${share(pixels, magenta)} %; best match shifted by (${shift.first}, ${shift.second}): mean " +
            "${"%.2f".format(shift.third)}"
    }

    /** ", attached 945x788" for a WebView; nothing without one. */
    private fun View?.describe(): String =
        if (this == null) "" else ", ${if (isAttachedToWindow) "attached" else "detached"} ${width}x$height"

    private fun near(p: Int, r: Int, g: Int, b: Int): Boolean =
        abs((p shr 16 and 0xFF) - r) <= 8 && abs((p shr 8 and 0xFF) - g) <= 8 && abs((p and 0xFF) - b) <= 8

    /** The shift of [frame] against [page], within 12 px each way, with the least mean difference: every 4th pixel. */
    private fun bestShift(frame: IntArray, page: IntArray, width: Int, height: Int): Triple<Int, Int, Double> {
        var best = Triple(0, 0, Double.MAX_VALUE)
        for (dy in -12..12) {
            for (dx in -12..12) {
                var sum = 0L
                var n = 0L
                var y = 12
                while (y < height - 12) {
                    var x = 12
                    while (x < width - 12) {
                        val a = frame[y * width + x]
                        val b = page[(y + dy) * width + (x + dx)]
                        sum += abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
                            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
                            abs((a and 0xFF) - (b and 0xFF))
                        n += 3
                        x += 4
                    }
                    y += 4
                }
                val mean = sum.toDouble() / n
                if (mean < best.third) best = Triple(dx, dy, mean)
            }
        }
        return best
    }

    private fun save(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun waitFor(what: String, condition: suspend () -> Boolean) {
        val met = withTimeoutOrNull(20_000) {
            while (!condition()) delay(20)
            true
        }
        assertTrue(met == true, "no $what within 20 s")
    }

    private companion object {
        /** Behind the view: where neither the cover nor the page is drawn, a frame shows this grey. */
        val BEHIND = Color(0xFF808080)

        /** The placeholder, which should never show: the cover is in the cache. */
        val PLACEHOLDER = Color(0xFFFF00FF)
    }
}
