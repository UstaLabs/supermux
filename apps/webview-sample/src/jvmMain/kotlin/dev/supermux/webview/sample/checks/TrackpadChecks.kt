package dev.supermux.webview.sample.checks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.supermux.webview.DarkMode
import dev.supermux.webview.LocalWebSurfaceHints
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebSurfaceHints
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.sample.chat.PreviewMarkers
import dev.supermux.webview.sample.chat.Previews
import dev.supermux.webview.testing.MacEngineTesting
import dev.supermux.webview.testing.ScrollPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The chat's previews scrolled by the trackpad (the user's reports, 2026-11-01: a white area in the previews while
 * scrolling the chat; then the page running ahead of its card and snapping back, a "wiggly woggle"). A chat-like list of
 * the sample's dark previews (three.js, Canvas 2D) in rounded cards, the WhenVisible views the chat has, is scrolled by
 * two-finger gestures posted as AppKit gets them from the trackpad, drags and flicks with their momentum, while a thread
 * of its own copies the list as the window server composites it.
 *
 * Where page views move at their layout (no frame sync), they move at AppKit's next commit, and their holes when the GPU
 * has drawn Compose's frame, which, while the WebGL pages keep the GPU busy, is two to four frames later:
 * - the light band: without the engine's backdrop, the band of each moving page's hole that its view has already left
 *   shows what is under the canvas, AWT's own view: Swing's light grey, on pages that are nearly black; with it, the
 *   page's own background. That check runs the gestures twice without frame sync, the backdrop's case: without the
 *   backdrop at least one copy must show a light band across the column (or it can't see what it guards: INCONCLUSIVE);
 *   with it, none may: no row across the column light, nor the window's backing (green, painted by Main.kt). Everything
 *   else in the scene is dark, and no preview has a light row across it, so a light row is the band.
 * - the skew: with frame sync, each view moves in the transaction that presents Compose's frame of its layout. That
 *   check draws [PreviewMarkers]' lines and measures each page against its card ([CardSkew]): without frame sync, at
 *   least a third of the moving cards must have their page more than one presented frame ahead (INCONCLUSIVE otherwise);
 *   with it, at most one in fifty, and the median skew within [SkewRecord.OFF]. One presented frame is what a copy can
 *   see that the display doesn't ([SkewRecord]).
 * - the frames: with frame sync each frame shows when its transaction commits, so two committed within one refresh show
 *   only the second, and the refresh before shows nothing new (a user's report, 2026-11-05: "a kind of freeze" while
 *   scrolling the chat). That check counts the frames Compose drew while the gestures ran that the display showed, and
 *   those it never did (their drawables' presentedTime): at most one in ten never shown. In this scene's 12 WebGL pages
 *   (2026-11-05): committed at AppKit's flush, 52 of 481 never showed; committed at once, 29 and 30 of about 480; spaced
 *   as now, 18 and 24. One a refresh at a display link's, none closer than 15 ms, still lost 11 to 14 of about 480: the
 *   window server's own misses, where a frame shown a refresh late is replaced by the next (CAMetalLayer's own presents
 *   would show late instead).
 */
object TrackpadChecks {
    private val PREVIEWS = listOf(
        Previews.galaxyClassic, Previews.knotGold, Previews.waveNeon, Previews.galaxyRose, Previews.flowOcean,
        Previews.knotNeon, Previews.galaxyEmerald, Previews.waveSunset, Previews.terrainDusk, Previews.flowNeon,
        Previews.lifeMint, Previews.fireworksNight,
    )

    /** The cards' column: in from the window's sides, and the pointer in the margin left of it, over the list. */
    private val SIDE = 160.dp

    /** A row is light, or the backing, when this share of the column is: the band spans a card, a preview's light never does. */
    private const val ROW_SHARE = 0.6

    /** A two-finger gesture: [peak] points a second (natural: positive moves the content down), with momentum or not. */
    internal class Swipe(val label: String, val peak: Float, val fingerSeconds: Double, val momentum: Boolean)

    /** Each after a rest long enough for the previews on screen to go live (WhenVisible): live pages are what moves. */
    private val GESTURES = listOf(
        Swipe("drag-on", -700f, 0.6, momentum = false),
        Swipe("flick-on", -2000f, 0.15, momentum = true),
        Swipe("flick-back", 1500f, 0.15, momentum = true),
        Swipe("drag-back", 600f, 0.5, momentum = true),
    )

    private const val REST_SECONDS = 2.5

    private class Stage {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(RuntimeConfig(maxLive = 6, maxIdle = 2, snapshotCacheBytes = 64L shl 20, scope = scope))
        val states = PREVIEWS.map { WebViewState(it.content) }
        val list = LazyListState()
    }

    /** The scene; [marked]: each caption's place draws its line along the page's bottom, as the chat's do ([PreviewMarkers]). */
    @Composable
    private fun Scene(stage: Stage, marked: Boolean = false) {
        val settings = WebSettings(darkMode = DarkMode.Dark)
        ProvideWebViewRuntime(stage.runtime) {
            LazyColumn(state = stage.list, modifier = Modifier.fillMaxSize().background(Color(0xFF131318))) {
                items(PREVIEWS.size) { i ->
                    Column(Modifier.padding(start = SIDE, end = SIDE, top = 16.dp)) {
                        // The agent's line before its preview, as the chat has it: dark, no text.
                        Box(Modifier.size(280.dp, 44.dp).background(Color(0xFF2A292F), RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)))
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1B1B21))) {
                            CompositionLocalProvider(LocalWebSurfaceHints provides WebSurfaceHints(inScrollingList = true)) {
                                WebView(stage.states[i], Modifier.fillMaxWidth().height(PREVIEWS[i].height ?: 240.dp), settings)
                            }
                            // The caption's place: cyan while the page itself shows, blue while a picture of it does.
                            val line = if (!marked) {
                                Modifier
                            } else {
                                Modifier.drawBehind {
                                    val ink = if (stage.states[i].phase == WebViewPhase.Live) Color(0xFF00FFFF) else Color(0xFF0000FF)
                                    drawRect(ink, size = Size(size.width, PreviewMarkers.THICKNESS.dp.toPx()))
                                }
                            }
                            Spacer(line.fillMaxWidth().height(36.dp))
                        }
                    }
                }
            }
        }
    }

    /** What one run of the gestures saw. */
    private class Seen(
        val copies: Int,
        val lightCopies: Int,
        val thickest: Int,
        val backingCopies: Int,
        val livePerGesture: List<Int>,
        val momentumPx: List<Float>,
    ) {
        override fun toString(): String =
            "$lightCopies of $copies copies with a light band across the column (the thickest $thickest pt), " +
                "$backingCopies with the window's backing; live previews as each gesture began $livePerGesture; " +
                "the list moved by momentum ${momentumPx.joinToString { "%.0f".format(it) }} px"
    }

    val all: List<Check> = listOf(
        Check("mac-a-chat-scrolled-by-trackpad-shows-no-light-band-at-its-dark-previews", timeoutMillis = 240_000) {
            val mac = platform as MacCheckPlatform
            val stage = Stage()
            compose { Scene(stage) }
            try {
                MacEngineTesting.paintBacking(mac.window, MacCheckPlatform.BACKING)
                // The backdrop guards views that move at their layout, where they can't move with skiko's frames.
                MacEngineTesting.frameSync = false
                val baseline = try {
                    MacEngineTesting.backdrop = false
                    run(stage, mac, "baseline")
                } finally {
                    MacEngineTesting.backdrop = true
                }
                println("MEASURE trackpad chat without the backdrop: $baseline")
                val seen = run(stage, mac, "backdrop")
                println("MEASURE trackpad chat: $seen")
                if (baseline.copies < 100 || seen.copies < 100) throw AssertionError("too few copies to judge: $baseline; $seen")
                for (result in listOf(baseline, seen)) {
                    if (result.livePerGesture.any { it < 2 }) throw AssertionError("too few live previews moved: $result")
                    if (result.momentumPx.any { it < 50f }) throw AssertionError("a gesture's momentum didn't move the list: $result")
                }
                if (baseline.lightCopies == 0) {
                    throw AssertionError("INCONCLUSIVE: without the backdrop no copy showed the band, so these copies can't see what the backdrop does: $baseline")
                }
                assertEquals(0, seen.lightCopies, "copies with a light band across the column ($seen)")
                assertEquals(0, seen.backingCopies, "copies with the window's backing across the column ($seen)")
            } finally {
                MacEngineTesting.frameSync = true
                compose(null)
                delay(300)
                stage.runtime.clear()
                stage.runtime.onMemoryPressure()
                delay(300)
                stage.scope.cancel()
            }
        },
        Check("mac-a-chat-scrolled-by-trackpad-moves-each-page-with-its-card", timeoutMillis = 240_000) {
            val mac = platform as MacCheckPlatform
            // The pages load with their lines: set before the scene makes its engines.
            PreviewMarkers.enabled = true
            val stage = Stage()
            compose { Scene(stage, marked = true) }
            try {
                val baseline = try {
                    MacEngineTesting.frameSync = false
                    runSkew(stage, mac, "baseline")
                } finally {
                    MacEngineTesting.frameSync = true
                }
                println("MEASURE trackpad skew without frame sync: $baseline")
                val seen = runSkew(stage, mac, "synced")
                val state = MacEngineTesting.frameSyncState(mac.window)
                println("MEASURE trackpad skew: $seen; frame sync $state")
                for (result in listOf(baseline, seen)) {
                    val summary = result.summary
                    if (summary.copies < 100 || summary.moving < 50) throw AssertionError("too few copies to judge: $result")
                    if (result.gestured.livePerGesture.any { it < 2 }) throw AssertionError("too few live previews moved: $result")
                    if (result.gestured.momentumPx.any { it < 50f }) throw AssertionError("a gesture's momentum didn't move the list: $result")
                }
                if (baseline.summary.beyondOne * 3 < baseline.summary.moving) {
                    throw AssertionError("INCONCLUSIVE: without frame sync few pages ran more than a frame ahead, so these copies can't see what it does: $baseline")
                }
                assertTrue(state.startsWith("on "), "the window's frame sync: $state")
                val summary = seen.summary
                assertTrue(summary.px.p50 <= SkewRecord.OFF, "the pages' skew against their cards while they moved, p50 ${summary.px.p50} pt ($seen)")
                assertTrue(
                    summary.beyondOne * 50 <= summary.moving,
                    "moving cards whose page was more than one presented frame off: ${summary.beyondOne} of ${summary.moving} ($seen)",
                )
            } finally {
                compose(null)
                delay(300)
                stage.runtime.clear()
                stage.runtime.onMemoryPressure()
                delay(300)
                stage.scope.cancel()
                PreviewMarkers.enabled = false
            }
        },
        Check("mac-a-chat-scrolled-by-trackpad-shows-every-frame-it-draws", timeoutMillis = 120_000) {
            val mac = platform as MacCheckPlatform
            val stage = Stage()
            compose { Scene(stage) }
            try {
                stage.list.scrollToItem(0)
                delay(3_000)
                val before = MacEngineTesting.frameSyncState(mac.window)
                val s = mac.density
                val height = mac.window.contentPane.height * s
                val top = 80 * s
                val gestured = gestures(stage, mac, SIDE.value * s / 2, top + (height - top) / 2)
                val state = MacEngineTesting.frameSyncState(mac.window)
                val shown = count(state, "shown") - count(before, "shown")
                val unshown = count(state, "unshown") - count(before, "unshown")
                println(
                    "MEASURE trackpad frames: $shown shown, $unshown drawn and never shown; live previews as each gesture began " +
                        "${gestured.livePerGesture}; frame sync $state",
                )
                assertTrue(state.startsWith("on "), "the window's frame sync: $state")
                if (shown + unshown < 150) throw AssertionError("too few frames to judge: $shown shown, $unshown not ($state)")
                if (gestured.livePerGesture.any { it < 2 }) throw AssertionError("too few live previews moved: ${gestured.livePerGesture}")
                assertTrue(
                    unshown * 10 <= shown + unshown,
                    "frames the display never showed, a later one replacing each within a refresh: $unshown of ${shown + unshown} ($state)",
                )
            } finally {
                compose(null)
                delay(300)
                stage.runtime.clear()
                stage.runtime.onMemoryPressure()
                delay(300)
                stage.scope.cancel()
            }
        },
    )

    /** A count the frame sync's state names (`name=<count>`), 0 if it names none. */
    private fun count(state: String, name: String): Long =
        Regex("(?:^| )$name=(\\d+)").find(state)?.groupValues?.get(1)?.toLong() ?: 0L

    /** From the list's start: the gestures, each after a rest, every copy of the column judged. */
    private suspend fun run(stage: Stage, mac: MacCheckPlatform, label: String): Seen {
        stage.list.scrollToItem(0)
        delay(3_000)
        val s = mac.density
        val width = mac.window.contentPane.width * s
        val height = mac.window.contentPane.height * s
        // The cards' column, 8 px in from its sides, under the checks' 80 dp to the window's bottom.
        val left = SIDE.value * s + 8 * s
        val top = 80 * s
        val columnWidth = width - 2 * left
        val pointerX = SIDE.value * s / 2
        val pointerY = top + (height - top) / 2
        var running = true
        var copies = 0
        var lightCopies = 0
        var backingCopies = 0
        var thickest = 0
        var saved = 0
        val copier = thread(name = "trackpad-copier", isDaemon = true) {
            while (running) {
                val copied = MacEngineTesting.copyPoints(mac.window, left, top, columnWidth, height - top) ?: continue
                copies++
                var lightRows = 0
                var run = 0
                var backing = false
                for (y in 0 until copied.height) {
                    var light = 0
                    var green = 0
                    for (x in 0 until copied.width) {
                        val i = (y * copied.width + x) * 4
                        val r = copied.rgba[i].toInt() and 0xff
                        val g = copied.rgba[i + 1].toInt() and 0xff
                        val b = copied.rgba[i + 2].toInt() and 0xff
                        if (r >= 200 && g >= 200 && b >= 200) light++
                        if (g >= 200 && r <= 80 && b <= 80) green++
                    }
                    if (light >= copied.width * ROW_SHARE) {
                        lightRows++
                        run++
                        thickest = max(thickest, run)
                    } else {
                        run = 0
                    }
                    if (green >= copied.width * ROW_SHARE) backing = true
                }
                if (lightRows > 0) lightCopies++
                if (backing) backingCopies++
                if ((lightRows > 0 || backing) && saved < 3) {
                    saved++
                    val pixels = Pixels(copied.width, copied.height, copied.rgba)
                    mac.save(object : Captured { override fun pixels() = pixels }, "${mac.folder("trackpad-$label", empty = saved == 1)}/band-$copies.png")
                }
            }
        }
        val gestured = try {
            gestures(stage, mac, pointerX, pointerY)
        } finally {
            running = false
            copier.join(2_000)
        }
        return Seen(copies, lightCopies, thickest, backingCopies, gestured.livePerGesture, gestured.momentumPx)
    }

    /** What the gestures did: the live previews as each began, and how far each one's momentum moved the list. */
    private class Gestured(val livePerGesture: List<Int>, val momentumPx: List<Float>)

    /** [GESTURES] at ([pointerX], [pointerY]), each after a rest. */
    private suspend fun gestures(stage: Stage, mac: MacCheckPlatform, pointerX: Float, pointerY: Float): Gestured {
        val livePerGesture = ArrayList<Int>()
        val momentumPx = ArrayList<Float>()
        for (gesture in GESTURES) {
            delay((REST_SECONDS * 1000).toLong())
            livePerGesture += stage.states.count { it.phase == WebViewPhase.Live }
            val lifted = AtomicReference(0f)
            val driver = thread(name = "trackpad-gesture", isDaemon = true) {
                swipe(mac.window, pointerX, pointerY, gesture, onLifted = { lifted.set(position(stage.list)) })
            }
            withTimeoutOrNull(10_000) { while (driver.isAlive) delay(20) }
            // Compose has the momentum's last events by the next frames.
            delay(200)
            if (gesture.momentum) momentumPx += abs(position(stage.list) - lifted.get())
        }
        return Gestured(livePerGesture, momentumPx)
    }

    /** What one run of the gestures saw of the pages against their cards. */
    private class Skewed(val summary: SkewRecord.Summary, val gestured: Gestured) {
        override fun toString(): String =
            "$summary; live previews as each gesture began ${gestured.livePerGesture}; the list moved by momentum " +
                gestured.momentumPx.joinToString { "%.0f".format(it) } + " px"
    }

    /** From the list's start: the gestures, each after a rest, every copy of the column's cards judged ([CardSkew]). */
    private suspend fun runSkew(stage: Stage, mac: MacCheckPlatform, label: String): Skewed {
        stage.list.scrollToItem(0)
        delay(3_000)
        // When the gestures start, by the wall clock: what a recording of the screen is matched to.
        println("MEASURE trackpad skew $label from ${System.currentTimeMillis()} ms, the window at ${mac.window.bounds}")
        val s = mac.density
        val width = mac.window.contentPane.width * s
        val height = mac.window.contentPane.height * s
        // The cards' column, 8 px in from its sides, under the checks' 80 dp to the window's bottom.
        val left = SIDE.value * s + 8 * s
        val top = 80 * s
        val copier = SkewCopier(
            mac.window, left, top, width - 2 * left, height - top,
            minRun = ((width - 2 * left) / s * 0.6f).toInt(),
            folder = File(mac.folder("trackpad-skew-$label")),
            framePeriodNanos = framePeriodNanosOf(mac.window),
        )
        val gestured = try {
            gestures(stage, mac, SIDE.value * s / 2, top + (height - top) / 2)
        } catch (e: Throwable) {
            copier.stop()
            throw e
        }
        return Skewed(copier.stop(), gestured)
    }

    /** The list's position as one number: item index and offset (px), close enough to tell that it moved. */
    private fun position(list: LazyListState): Float = list.firstVisibleItemIndex * 100_000f + list.firstVisibleItemScrollOffset

    internal fun sleepNanos(nanos: Long) {
        val end = System.nanoTime() + nanos
        while (true) {
            val left = end - System.nanoTime()
            if (left <= 0) return
            LockSupport.parkNanos(left)
        }
    }

    /**
     * [gesture] as a trackpad sends it: events every 8 ms while the fingers move, speeding up to its peak; then, with
     * momentum, AppKit's momentum events at 60 Hz from that speed, decaying as macOS's do (about e^(-t / 0.5 s)) until
     * under 20 points a second. [onLifted] runs as the fingers lift.
     */
    internal fun swipe(window: java.awt.Window, x: Float, y: Float, gesture: Swipe, onLifted: () -> Unit) {
        val steps = max(2, (gesture.fingerSeconds / 0.008).toInt())
        MacEngineTesting.scroll(window, x, y, 0f, ScrollPhase.BEGAN, ScrollPhase.NONE)
        sleepNanos(8_000_000)
        for (i in 1..steps) {
            val ramp = min(1.0, 0.3 + 0.7 * i / (steps * 0.6))
            MacEngineTesting.scroll(window, x, y, (gesture.peak * ramp * 0.008).toFloat(), ScrollPhase.CHANGED, ScrollPhase.NONE)
            sleepNanos(8_000_000)
        }
        MacEngineTesting.scroll(window, x, y, 0f, ScrollPhase.ENDED, ScrollPhase.NONE)
        sleepNanos(30_000_000)
        onLifted()
        if (!gesture.momentum) return
        var speed = gesture.peak.toDouble()
        val dt = 1.0 / 60
        MacEngineTesting.scroll(window, x, y, (speed * dt).toFloat(), ScrollPhase.NONE, ScrollPhase.MOMENTUM_BEGIN)
        while (true) {
            sleepNanos(16_666_667)
            speed *= exp(-dt / 0.5)
            if (abs(speed) < 20) break
            MacEngineTesting.scroll(window, x, y, (speed * dt).toFloat(), ScrollPhase.NONE, ScrollPhase.MOMENTUM_CONTINUE)
        }
        MacEngineTesting.scroll(window, x, y, 0f, ScrollPhase.NONE, ScrollPhase.MOMENTUM_END)
    }
}
