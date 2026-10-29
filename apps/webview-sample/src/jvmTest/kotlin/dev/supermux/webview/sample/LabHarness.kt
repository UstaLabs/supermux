package dev.supermux.webview.sample

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How long one scripted run may take on the wall clock before the test gives up on it. */
private const val RUN_TIMEOUT_NANOS = 8L * 60 * 1_000_000_000

/** How long a test waits for something the runtime does by itself. */
internal const val WAIT_MILLIS = 20_000L

/** A perf lab on fake engines that tests can inspect, and what its runtime caught. */
internal class TestLab(val lab: PerfLabState, val fakes: FakeWebEngineFactory, val errors: MutableList<Throwable>)

/**
 * A perf lab with [config], on the library's fake engines (every one kept, so its contract can be checked), on the
 * test's virtual clock. Its runtime's scope ends with the test.
 */
@OptIn(ExperimentalTestApi::class)
internal suspend fun ComposeUiTest.testLab(config: RuntimeConfig): TestLab {
    val fakes = FakeWebEngineFactory(behavior = SampleEngineBehavior)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    currentCoroutineContext().job.invokeOnCompletion { scope.cancel() }
    val engines = EngineCounter(fakes)
    val runtime = WebViewRuntime(engines, config, scope, clock = { mainClock.currentTime })
    val errors = mutableListOf<Throwable>()
    runtime.onError = { errors += it }
    return TestLab(PerfLabState(engines, runtime), fakes, errors)
}

/** Runs [block] on the UI thread, where the runtime changes what it reads. */
@OptIn(ExperimentalTestApi::class)
internal fun <T> ComposeUiTest.ui(block: () -> T): T = runOnUiThread(block)

/** Every fake engine was used as the engine contract says, and the runtime caught nothing. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertClean(test: TestLab) = ui {
    test.fakes.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}'s violations") }
    assertEquals(emptyList(), test.errors.map { it.toString() }, "what the runtime caught")
}

/** What the views on screen may hold as covers at most: [bytes] in all, by [count] views. */
internal class CoverLimits(val bytes: Long, val count: Int)

/**
 * Covers are held only by views on screen. The web views that meet the list's viewport span at most its height plus
 * one embed cut at each edge, at the viewport's width; and each is at least as tall as the shortest page.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.coverLimits(): CoverLimits {
    val viewport = onNodeWithTag(SampleTags.Viewport).fetchSemanticsNode().size
    val tallest = with(density) { MAX_EMBED_HEIGHT.roundToPx() }
    val shortest = with(density) { MIN_PAGE_HEIGHT.dp.roundToPx() }
    return CoverLimits(
        bytes = viewport.width.toLong() * (viewport.height + 2L * tallest) * 4,
        count = viewport.height / shortest + 2,
    )
}

/**
 * Checks the perf lab's invariants each time it is asked, and keeps their peaks. Call [check] on the UI thread.
 * - Live engines never exceed the budget, which never exceeds maxLive.
 * - No more engines exist than maxLive plus maxIdle (the fake never overruns a bound, the one exception).
 * - Covers hold no more than a screenful; the snapshot cache no more than its bound.
 */
internal class Guard(private val lab: PerfLabState, private val covers: CoverLimits) {
    private val config = lab.runtime.config

    var frames = 0
        private set
    var peakLive = 0
        private set
    var peakEngines = 0
        private set
    var peakCoverBytes = 0L
        private set
    var peakCoverCount = 0
        private set

    /** The most views that held a cover at once while the list went back up. */
    var peakCoverCountGoingUp = 0
        private set

    /** Pages started between two frames that both moved the list. */
    var loadsWhileMoving = 0
        private set

    private var last: LabStats? = null
    private var lastMoving = false

    fun check(): LabStats {
        val s = lab.sample()
        frames++
        val at = "frame $frames (${lab.running?.label} ${lab.phase})"
        assertTrue(s.live <= s.budget, "$at: ${s.live} live engines, over the budget of ${s.budget}")
        assertTrue(s.budget <= config.maxLive, "$at: a budget of ${s.budget}, over maxLive ${config.maxLive}")
        val most = config.maxLive + config.maxIdle
        assertTrue(s.engines <= most, "$at: ${s.engines} engines exist, over $most (maxLive + maxIdle)")
        assertTrue(s.coverCount in 0..covers.count, "$at: ${s.coverCount} views hold a cover; ${covers.count} fit on screen")
        assertTrue(s.coverBytes in 0..covers.bytes, "$at: covers hold ${s.coverBytes} bytes, over a screenful (${covers.bytes})")
        assertTrue(s.cacheBytes in 0..s.cacheMaxBytes, "$at: the snapshot cache holds ${s.cacheBytes} of ${s.cacheMaxBytes} bytes")
        val phase = lab.phase
        val moving = phase?.moving == true
        val previous = last
        if (moving && lastMoving && previous != null) loadsWhileMoving += s.loads - previous.loads
        last = s
        lastMoving = moving
        peakLive = max(peakLive, s.live)
        peakEngines = max(peakEngines, s.engines)
        peakCoverBytes = max(peakCoverBytes, s.coverBytes)
        peakCoverCount = max(peakCoverCount, s.coverCount)
        if (phase == RunPhase.Up) peakCoverCountGoingUp = max(peakCoverCountGoingUp, s.coverCount)
        return s
    }
}

/**
 * Starts [run] and steps the frames by hand until it ends. [guard] checks every frame. Each frame that moves the list
 * is timed on the wall clock: its scroll, composition, layout and drawing, and the runtime's work queued on the UI
 * thread before it. That is the test harness's cost per frame on this machine, not a display's frame pacing; the
 * frames of a rest, where nothing changes, aren't counted.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.drive(lab: PerfLabState, run: LabRun, guard: Guard): FrameStats {
    ui { lab.start(run) }
    val costs = FrameStats()
    val deadline = System.nanoTime() + RUN_TIMEOUT_NANOS
    var started = false
    mainClock.autoAdvance = false
    try {
        while (true) {
            val moving = lab.phase?.moving == true
            val begin = System.nanoTime()
            mainClock.advanceTimeByFrame()
            val cost = System.nanoTime() - begin
            if (moving || lab.phase?.moving == true) costs.add(cost)
            ui { guard.check() }
            if (lab.running == run) started = true else if (started) break
            check(System.nanoTime() < deadline) { "$run ${if (started) "didn't end" else "never started"} in time" }
        }
    } finally {
        mainClock.autoAdvance = true
    }
    waitForIdle()
    return costs
}

/**
 * The whole window as drawn now, popups and dialogs included, also written to build/sample-screens/[name].png. The
 * clock moves on first, so the numbers panel, read four times a second, shows the numbers of now.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.saveScreen(name: String): ImageBitmap {
    mainClock.advanceTimeBy(300)
    waitForIdle()
    val scene = this as? SkikoComposeUiTest ?: error("the desktop harness renders through Skiko")
    val image = scene.captureToImage()
    val png = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
    val dir = File("build/sample-screens").apply { mkdirs() }
    File(dir, "$name.png").writeBytes(png.bytes)
    return image
}

/** The share of [image]'s pixels that are exactly one of [colors]. */
internal fun share(image: ImageBitmap, colors: Set<Color>): Double {
    val pixels = image.toPixelMap()
    var n = 0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y] in colors) n++
    return n.toDouble() / (pixels.width * pixels.height)
}

/** "p50 2.1 · p95 4.0 · p99 6.2 · max 25.0 ms over 6,800 frames" */
internal fun FrameStats.costs(): String = summary()?.let { "${it.percentiles()} over ${it.frames} frames" } ?: "no frames"
