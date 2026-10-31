package dev.supermux.webview.sample.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.platformWebEngineFactory
import dev.supermux.webview.sample.linux.LinuxChecks
import dev.supermux.webview.sample.linux.Recorder
import dev.supermux.webview.sample.linux.articlePage
import dev.supermux.webview.sample.linux.echoPage
import dev.supermux.webview.sample.linux.hostEvent
import dev.supermux.webview.sample.linux.isPong
import dev.supermux.webview.sample.linux.isReady
import dev.supermux.webview.sample.linux.nonceOf
import dev.supermux.webview.sample.linux.page
import java.awt.AWTEvent
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What the measures show and drive (the checks' CheckHost and CheckScope, for the lab): the engines they make, shown in
 * a grid, or a scene of their own; the window; a robot. Main thread only, but for the robot.
 */
internal class MeasureHost(private val scope: CoroutineScope) {
    /** The engines on screen, in the window's grid: rows of four, [CELL] each. */
    val engines = mutableStateListOf<WebEngine>()
    var scene: (@Composable () -> Unit)? by mutableStateOf(null)
    /** The engine the screen-sized window shows (LAB copy's 1080p), out of the grid; null: no such window. */
    var fullScreen: WebEngine? by mutableStateOf(null)
    var window: ComposeWindow? = null
    var fullScreenWindow: ComposeWindow? = null
    private val recorders = mutableMapOf<WebEngine, Recorder>()
    private val robot = Robot().apply { autoDelay = 0 }

    /** Where the window's content is on screen. */
    fun origin(): Point = checkNotNull(window) { "no window" }.contentPane.locationOnScreen

    /** A new engine of [profile], as the pool would get it, on screen in the grid; its events recorded unless a scene takes them. */
    suspend fun create(profile: WebProfile, record: Boolean = true): WebEngine {
        val result = platformWebEngineFactory().create(EngineConfig(profile))
        val engine = (result as? WebEngineResult.Created)?.engine ?: throw AssertionError("no engine: $result")
        if (record) {
            recorders[engine] = Recorder(engine, scope)
            engines += engine
        }
        return engine
    }

    /** [engine] configured and loaded with [content] as the runtime does it, on screen; returns at its first paint. */
    suspend fun paint(engine: WebEngine, content: WebContent) {
        val events = recorders.getValue(engine)
        val from = events.events.size
        LinuxChecks.start(engine, content)
        events.await("first paint", from = from, millis = 30_000) { it == EngineEvent.FirstPaint }
    }

    /** [paint], then the page's `$ready`, which it returns. */
    suspend fun live(engine: WebEngine, content: WebContent): EngineEvent.PageMessage {
        val events = recorders.getValue(engine)
        val from = events.events.size
        paint(engine, content)
        return events.await("\$ready", from = from, millis = 30_000, matches = ::isReady) as EngineEvent.PageMessage
    }

    /** Shows [focus] instead of the grid. */
    fun scene(focus: FocusScene) {
        scene = { focus.Content() }
    }

    /** A left click at [point] on screen, as a user's. */
    suspend fun clickOn(point: Point) = withContext(Dispatchers.IO) {
        robot.mouseMove(point.x, point.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        robot.waitForIdle()
    }

    /** The screen-sized window's content, in the screen's pixels: the frames' size. */
    fun fullScreenSize(): String {
        val window = checkNotNull(fullScreenWindow) { "no screen-sized window" }
        val scale = window.graphicsConfiguration.defaultTransform
        return "${(window.contentPane.width * scale.scaleX).roundToInt()}x${(window.contentPane.height * scale.scaleY).roundToInt()}"
    }

    /** [engine] out of the grid, alone in a window the size of the screen (undecorated, at its corner). */
    fun showFullScreen(engine: WebEngine) {
        engines -= engine
        fullScreen = engine
    }

    /** Every engine and scene gone. */
    fun releaseAll() {
        recorders.values.forEach { it.stop() }
        recorders.clear()
        scene = null
        fullScreen?.let { runCatching { it.release() } }
        fullScreen = null
        engines.toList().forEach { runCatching { it.release() } }
        engines.clear()
    }
}

/**
 * The Windows engine's measurements (Task 31): `LAB memory`, `LAB firstpaint`, `LAB bridge` and `LAB press` lines,
 * through the public engine SPI, the screen and the OS's process list only. Numbers are the VM's: WARP on
 * supermux-windows.
 */
internal class WindowsMeasures(private val host: MeasureHost, private val app: String, private val memoryScript: String) {
    data class Memory(val privateMb: Double, val workingSetMb: Double, val processes: Int, val gpuPrivateMb: Double, val gpuWorkingSetMb: Double)

    /** The app's WebView2 processes, as `webview2-memory.ps1` adds them up, and the GPU process's share of them. */
    suspend fun webView2Memory(): Memory = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", memoryScript, "-App", app)
            .redirectErrorStream(true).start()
        val line = process.inputStream.bufferedReader().readText().trim().lines().last()
        check(process.waitFor() == 0) { "webview2-memory.ps1: $line" }
        val columns = line.split(' ')
        val (privateBytes, workingSet, count) = columns
        Memory(
            privateBytes.toLong() / MB, workingSet.toLong() / MB, count.toInt(),
            columns.getOrNull(3)?.toLong()?.div(MB) ?: 0.0, columns.getOrNull(4)?.toLong()?.div(MB) ?: 0.0,
        )
    }

    /**
     * LAB memory: with 0, 1, 2, 4 and 8 live views (Default profile, each an article on its own origin, so each its own
     * renderer), settled 5 s. Per view is the slope from 1 to 8 (private bytes; the working set's too); the base is what
     * the first view brings besides its own share: the browser, GPU and utility processes (with 0 views none runs).
     */
    suspend fun memory() {
        val counts = listOf(0, 1, 2, 4, 8)
        val seen = mutableMapOf<Int, Memory>()
        for (target in counts) {
            while (host.engines.size < target) {
                val engine = host.create(WebProfile.Default)
                host.live(engine, WebContent.Html(articlePage.html, WebOrigin("memory${host.engines.size}")))
            }
            delay(5_000)
            val m = webView2Memory().also { seen[target] = it }
            println(
                "LAB memory views=$target private_mb=${"%.1f".format(m.privateMb)} working_set_mb=${"%.1f".format(m.workingSetMb)} " +
                    "processes=${m.processes} gpu_private_mb=${"%.1f".format(m.gpuPrivateMb)} gpu_working_set_mb=${"%.1f".format(m.gpuWorkingSetMb)}",
            )
        }
        val one = seen.getValue(1)
        val eight = seen.getValue(8)
        val perView = (eight.privateMb - one.privateMb) / 7
        val perViewWs = (eight.workingSetMb - one.workingSetMb) / 7
        // The GPU process's part of the slope: what a view adds there (its textures, its compositor's).
        val perViewGpu = (eight.gpuPrivateMb - one.gpuPrivateMb) / 7
        val perViewGpuWs = (eight.gpuWorkingSetMb - one.gpuWorkingSetMb) / 7
        println(
            "LAB memory per_view_mb=${"%.1f".format(perView)} base_mb=${"%.1f".format(one.privateMb - perView)} " +
                "per_view_ws_mb=${"%.1f".format(perViewWs)} base_ws_mb=${"%.1f".format(one.workingSetMb - perViewWs)} " +
                "per_view_gpu_mb=${"%.1f".format(perViewGpu)} per_view_gpu_ws_mb=${"%.1f".format(perViewGpuWs)}",
        )
        host.releaseAll()
    }

    /** LAB firstpaint: cold (create → FirstPaint) 10 times; warm (a made, shown, settled engine: load → FirstPaint) 20 times. */
    suspend fun firstPaint() {
        val cold = (1..10).map { i ->
            val t0 = System.nanoTime()
            val engine = host.create(WebProfile.Ephemeral)
            host.paint(engine, page("cold $i"))
            ((System.nanoTime() - t0) / 1e6).also { host.releaseAll() }
        }
        val warm = (1..20).map { i ->
            val engine = host.create(WebProfile.Ephemeral)
            delay(1_000)
            val t0 = System.nanoTime()
            host.paint(engine, page("warm $i"))
            ((System.nanoTime() - t0) / 1e6).also { host.releaseAll() }
        }
        println("LAB firstpaint cold n=10 p50=${cold.p(0.5)} p95=${cold.p(0.95)}")
        println("LAB firstpaint warm n=20 p50=${warm.p(0.5)} p95=${warm.p(0.95)}")
    }

    /** LAB bridge: 200 host → page → host round trips through the echo page, from postMessage to the pong's event. */
    suspend fun bridge() {
        val engine = host.create(WebProfile.Ephemeral, record = false)
        // This measure is the engine's one subscriber: a recorder would take the pongs from it.
        val content = echoPage
        LinuxChecks.start(engine, content)
        val ready = engine.events.first(::isReady) as EngineEvent.PageMessage
        val nonce = nonceOf(ready)
        val times = (1..200).map { i ->
            coroutineScope {
                val pong = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { isPong(it, i) } }
                val t0 = System.nanoTime()
                engine.postMessage(hostEvent("ping", i, nonce), ready.origin)
                pong.await()
                (System.nanoTime() - t0) / 1e6
            }
        }
        println("LAB bridge n=200 p50=${times.p(0.5)} p95=${times.p(0.95)}")
        engine.release()
    }

    /**
     * LAB copy: copy + upload on the engine's own chain (the GPU gate measured it on the test module's, W22): a page that
     * repaints all of itself every frame, alone in a window the size of the screen (1920 × 1080 on the GPU VM), three
     * runs of [COPY_RUN_MS]. Per frame, as the glue times them (`WinGlue.frameTimes`, reached by reflection: the lab's
     * only hook): the capture thread's copy (arrival → published: the GPU copy into a staging texture, waited for) and the
     * EDT's pull (locks, Map, the rows into the store's memory, Unmap). Copy + upload is the gate's: p95 + p95. The draw
     * call (an image over that memory, W17) and skiko's upload on its render thread are outside both, as in the gate.
     */
    suspend fun copy() {
        val glue = Class.forName("dev.supermux.webview.engine.windows.WinGlue")
            .getMethod("frameTimes", Int::class.javaPrimitiveType, LongArray::class.java)
        val buffer = LongArray(16_384)
        fun take(kind: Int): List<Double> {
            val n = glue.invoke(null, kind, buffer) as Int
            return (0 until n).map { buffer[it] / 1e6 }
        }
        val engine = host.create(WebProfile.Ephemeral)
        host.showFullScreen(engine)
        host.paint(engine, WebContent.Html(ANIMATION_PAGE, WebOrigin("copy")))
        delay(2_000)
        val size = host.fullScreenSize()
        repeat(3) { run ->
            take(0)
            take(1)   // the first calls turn the timing on; what came before isn't this run's
            delay(COPY_RUN_MS)
            val capture = take(0)
            val pull = take(1)
            if (capture.isEmpty() || pull.isEmpty()) throw AssertionError("no frame times in run $run: ${capture.size} copies, ${pull.size} pulls")
            println(
                "LAB copy run=${run + 1} size=$size seconds=${COPY_RUN_MS / 1000} copied=${capture.size} pulled=${pull.size} " +
                    "capture_p50=${capture.p2(0.5)} capture_p95=${capture.p2(0.95)} capture_max=${capture.p2(1.0)} " +
                    "pull_p50=${pull.p2(0.5)} pull_p95=${pull.p2(0.95)} pull_max=${pull.p2(1.0)} " +
                    "copy_upload_p95=${"%.2f".format(capture.at(0.95) + pull.at(0.95))}",
            )
        }
        host.releaseAll()
    }

    /**
     * LAB press: 40 presses on the page, each timed at five points on System.nanoTime's clock: the Robot's press; AWT
     * dispatching it (an AWTEventListener); Compose seeing it (the scene's Initial pass, which consumes nothing); the
     * page's `pointerdown` (its wall clock, mapped through the wall clock read at the press); and the page's change on
     * screen (polled). Their differences take apart the gate's ~23 ms to `pointerdown` and ~23 ms after it (W11).
     */
    suspend fun press() {
        val scene = FocusScene(host.create(WebProfile.Ephemeral, record = false), script = PRESS_SCRIPT, origin = host::origin)
        host.scene(scene)
        scene.awaitPainted()
        val robot = Robot().apply { autoDelay = 0 }
        val target = scene.pagePoint(160, 200)   // the body, under no input
        val probe = scene.pagePoint(200, 200)    // 40 px right of the press: never under the cursor
        val awtAt = AtomicLong(0)
        val awt = AWTEventListener { if (it.id == MouseEvent.MOUSE_PRESSED) awtAt.compareAndSet(0, System.nanoTime()) }
        Toolkit.getDefaultToolkit().addAWTEventListener(awt, AWTEvent.MOUSE_EVENT_MASK)
        val parts = List(5) { mutableListOf<Double>() }   // robot→AWT, AWT→Compose, Compose→pointerdown, pointerdown→screen, total
        try {
            host.clickOn(target)                 // the page has the keys once before the counted presses
            repeat(40) {
                scene.engine.evaluate("document.body.style.background = '#fff'; window.__down = 0; 1")
                awtAt.set(0)
                scene.pressedAt.set(0)
                // Robot and the screen off the EDT; the engine only on it (D2).
                val (wall0, t0, seen) = withContext(Dispatchers.IO) {
                    waitForColour(robot, probe, 0xFFFFFF)
                    robot.mouseMove(target.x, target.y)
                    val wall0 = Instant.now().let { it.epochSecond * 1000.0 + it.nano / 1e6 }
                    val t0 = System.nanoTime()
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                    val seen = waitForColour(robot, probe, 0x000000)
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    Triple(wall0, t0, seen)
                }
                val down = t0 + ((scene.engine.evaluate("window.__down")!!.toDouble() - wall0) * 1e6).toLong()
                val stamps = listOf(t0, awtAt.get(), scene.pressedAt.get(), down, seen)
                stamps.zipWithNext().forEachIndexed { i, (a, b) -> parts[i] += (b - a) / 1e6 }
                parts[4] += (seen - t0) / 1e6
                delay(300)
            }
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(awt)
        }
        val names = listOf("robot_awt", "awt_compose", "compose_pointerdown", "pointerdown_screen", "total")
        println("LAB press n=40 " + names.indices.joinToString(" ") { "${names[it]}_p50=${parts[it].p(0.5)} ${names[it]}_p95=${parts[it].p(0.95)}" })
        scene.engine.release()
        host.releaseAll()
    }

    /** Polls the screen at [at] until it is [rgb]; its time. Two seconds at most. */
    private fun waitForColour(robot: Robot, at: Point, rgb: Int): Long {
        val end = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < end) {
            if (robot.getPixelColor(at.x, at.y).rgb and 0xFFFFFF == rgb) return System.nanoTime()
        }
        throw AssertionError("the screen at $at never became ${"%06x".format(rgb)}")
    }

    private fun List<Double>.at(q: Double): Double = sorted()[((size - 1) * q).roundToInt()]
    private fun List<Double>.p(q: Double): String = "%.1f".format(at(q))
    private fun List<Double>.p2(q: Double): String = "%.2f".format(at(q))

    companion object {
        private const val MB = 1_048_576.0
        private const val COPY_RUN_MS = 10_000L
        /** All of the page changes every frame (a hue, and a line of text moving), as the gate's 1080p animation did. */
        private const val ANIMATION_PAGE = """<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;height:100%;overflow:hidden}</style></head>
<body><canvas id="c"></canvas><script>
var c = document.getElementById('c'), g = c.getContext('2d'), t = 0;
function size() { c.width = innerWidth; c.height = innerHeight; }
addEventListener('resize', size); size();
function frame() {
  t++;
  g.fillStyle = 'hsl(' + (t * 3 % 360) + ',60%,45%)'; g.fillRect(0, 0, c.width, c.height);
  g.fillStyle = '#fff'; g.font = '48px sans-serif'; g.fillText('frame ' + t, 40 + (t * 9) % Math.max(1, c.width - 400), 120 + (t * 5) % Math.max(1, c.height - 200));
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);
</script></body></html>"""
        private const val PRESS_SCRIPT =
            "addEventListener('pointerdown', function () { window.__down = performance.timeOrigin + performance.now(); " +
                "document.body.style.background = '#000'; }, true);"
    }
}

/**
 * A view in the measures' grid. Eight must be on screen at once, as a view off screen never paints: at 320 × 240 dp, three
 * to a row in the window the VM's 1280 × 800 screen leaves (about 1080 × 710 dp), the eighth, in a third row, never did.
 * Two rows of four 256 × 192 dp fit.
 */
private val CELL = DpSize(256.dp, 192.dp)

/**
 * `-Dwebview.sample.lab=<measures>` (winvm.sh lab): [steps] of `memory`, `firstpaint`, `bridge`, `press`, `copy`, in that
 * order, in a decorated window at the screen's corner (`copy`: in one the size of the screen); their `LAB` lines, then
 * `LAB DONE`, or `LAB FAIL <why>` and exit 1.
 */
internal fun windowsMeasuresMain(steps: List<String>) {
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val size = DpSize(minOf(1100, screen.width).dp, minOf(1000, screen.height).dp)
    application {
        val host = remember { MeasureHost(CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "webview-sample · WebView2 measures",
            state = rememberWindowState(position = WindowPosition(0.dp, 0.dp), size = size),
        ) {
            host.window = window
            LaunchedEffect(Unit) {
                val measures = WindowsMeasures(
                    host,
                    app = System.getProperty("dev.supermux.webview.app") ?: "wvc-sample",
                    memoryScript = System.getProperty("wvc.memoryScript") ?: "C:\\wvc\\vm\\webview2-memory.ps1",
                )
                val failure = try {
                    for (step in steps) {
                        when (step) {
                            "memory" -> measures.memory()
                            "firstpaint" -> measures.firstPaint()
                            "bridge" -> measures.bridge()
                            "press" -> measures.press()
                            "copy" -> measures.copy()
                            else -> throw IllegalArgumentException("no measure '$step'")
                        }
                        System.out.flush()
                    }
                    null
                } catch (e: Throwable) {
                    e.message ?: e.toString()
                }
                println(if (failure == null) "LAB DONE" else "LAB FAIL $failure")
                System.out.flush()
                exitProcess(if (failure == null) 0 else 1)
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF202124))) {
                val scene = host.scene
                if (scene != null) {
                    scene()
                } else {
                    FlowRow(
                        Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (engine in host.engines) key(engine) { engine.Surface(Modifier.size(CELL)) }
                    }
                }
            }
        }
        // LAB copy's: one engine alone, at the screen's size (1920 x 1080 on the GPU VM), over everything.
        host.fullScreen?.let { engine ->
            val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
            key(engine) {
                Window(
                    onCloseRequest = {},
                    title = "webview-sample · WebView2 screen-sized",
                    undecorated = true,
                    alwaysOnTop = true,
                    state = rememberWindowState(position = WindowPosition(0.dp, 0.dp), size = DpSize(bounds.width.dp, bounds.height.dp)),
                ) {
                    host.fullScreenWindow = window
                    engine.Surface(Modifier.fillMaxSize())
                }
            }
        }
    }
}
