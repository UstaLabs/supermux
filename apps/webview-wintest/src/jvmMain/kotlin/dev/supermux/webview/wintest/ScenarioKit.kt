package dev.supermux.webview.wintest

import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One run's result lines: printed as they come, and written to `<out>/<scenario>-<stamp>.txt` by [finish]. */
class Report(private val scenario: String, outDir: File) {
    private val lines = ArrayList<String>()
    val base: File = File(outDir.apply { mkdirs() }, "$scenario-${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())}")

    fun line(check: String, verdict: Verdict, details: List<Pair<String, Any?>>) {
        val text = resultLine(scenario, check, verdict, details)
        synchronized(lines) { lines += text }
        println(text)
    }

    fun check(check: String, pass: Boolean, vararg details: Pair<String, Any?>) = line(check, verdict(pass), details.toList())

    fun info(check: String, vararg details: Pair<String, Any?>) = line(check, Verdict.INFO, details.toList())

    /** A side file next to the report (window trees, focus logs). */
    fun note(name: String, text: String): File = File("${base.path}-$name.txt").also { it.writeText(text) }

    fun finish() {
        File("${base.path}.txt").writeText(synchronized(lines) { lines.joinToString("\n", postfix = "\n") })
    }
}

fun verdict(pass: Boolean): Verdict = if (pass) Verdict.PASS else Verdict.FAIL

/** One input event as the page recorded it (page.html's `events`). */
data class PageEvent(
    val type: String,
    val t: Double,
    val id: String,
    val x: Double,
    val y: Double,
    val pointerType: String,
    val button: Int,
    val detail: Int,
    val key: String,
    val data: String,
    val ctrl: Boolean,
)

data class TopWindow(val hwnd: String, val className: String, val pid: Long, val rect: Rectangle)

/** Set-1 scan codes of the keys the scenarios type, by their US-layout character; the active layout maps them. */
val US_SCAN: Map<Char, Int> = mapOf(
    'a' to 0x1E, 'b' to 0x30, 'c' to 0x2E, 'd' to 0x20, 'e' to 0x12, 'f' to 0x21, 'g' to 0x22, 'h' to 0x23,
    'i' to 0x17, 'j' to 0x24, 'k' to 0x25, 'l' to 0x26, 'm' to 0x32, 'n' to 0x31, 'o' to 0x18, 'p' to 0x19,
    'q' to 0x10, 'r' to 0x13, 's' to 0x1F, 't' to 0x14, 'u' to 0x16, 'v' to 0x2F, 'w' to 0x11, 'x' to 0x2D,
    'y' to 0x15, 'z' to 0x2C, '1' to 0x02, '2' to 0x03, '3' to 0x04, '4' to 0x05, '5' to 0x06, '6' to 0x07,
    '7' to 0x08, '8' to 0x09, '9' to 0x0A, '0' to 0x0B, ' ' to 0x39,
)

/** Virtual-key codes the scenarios press. */
object Vk {
    const val BACK = 0x08
    const val TAB = 0x09
    const val RETURN = 0x0D
    const val SHIFT = 0x10
    const val CONTROL = 0x11
    const val ESCAPE = 0x1B
    const val SPACE = 0x20
    const val DOWN = 0x28
    const val A = 0x41
    const val C = 0x43
    const val K = 0x4B
    const val V = 0x56
    const val IME_ON = 0x16
    const val KANJI = 0x19
    const val IME_OFF = 0x1A
}

/** What a scenario works with: the app, its report, real OS input, and the page through the receiver. */
class Harness(val app: AppState, val report: Report) {
    val web: WinWebView get() = app.web
    val robot = Robot().apply { autoDelay = 0 }
    private val arrivalBuffer = LongArray(7 * 4096)

    /** Waits for WebView2, the page and the window, then puts the environment at the top of the report. */
    suspend fun prepare() {
        check(waitUntil(45_000) { web.readyInfo != null || web.failure != null }) { "WebView2 never reported ready" }
        web.failure?.let { error(it) }
        check(waitUntil(20_000) { web.pageReady.isCompleted }) { "the page never reported ready" }
        activateWindow()
        val ready = web.readyInfo.orEmpty().split('|') + List(5) { "" }
        val screen = Toolkit.getDefaultToolkit().screenSize
        report.info(
            "environment",
            "windows" to System.getProperty("os.version"),
            "arch" to System.getProperty("os.arch"),
            "java" to System.getProperty("java.runtime.version"),
            "webview2" to ready[0],
            "d3d" to ready[1],
            "parent_class" to ready[3],
            "raster_scale" to ready[4],
            "render_api" to onUi { app.window.renderApi.name },
            "canvas" to app.canvas?.javaClass?.name,
            "screen" to "${screen.width}x${screen.height}",
            "dpi" to Toolkit.getDefaultToolkit().screenResolution,
            "options" to app.options.label,
        )
    }

    /** Waits up to 10 s for a frame. Without one, the input and focus checks still run, on an undrawn surface. */
    suspend fun requireFrames() {
        if (!waitUntil(10_000) { web.buffer.seq > 0 }) {
            report.info("no-frames", "note" to "no captured frame; input and focus checks run on an undrawn surface")
        }
    }

    suspend fun waitUntil(timeoutMs: Long, pollMs: Long = 10, condition: suspend () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return true
            delay(pollMs)
        }
        return condition()
    }

    suspend fun <T> onUi(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    // ---- geometry: page CSS pixels and window pixels → screen points for Robot ----

    private suspend fun canvasOrigin(): Point = onUi { checkNotNull(app.canvas).locationOnScreen }

    suspend fun cssToScreen(x: Double, y: Double, w: Double, h: Double): Rectangle {
        val dpr = web.call("viewport").list().getOrNull(2)?.double()?.takeIf { it > 0 } ?: 1.0
        val origin = canvasOrigin()
        val surface = app.surfaceInWindow
        val d = app.density
        return Rectangle(
            origin.x + ((surface.x + x * dpr) / d).roundToInt(),
            origin.y + ((surface.y + y * dpr) / d).roundToInt(),
            (w * dpr / d).roundToInt(),
            (h * dpr / d).roundToInt(),
        )
    }

    suspend fun pageRectOnScreen(id: String): Rectangle {
        val r = web.call("rect", id).list().map { it.double() }
        return cssToScreen(r[0], r[1], r[2], r[3])
    }

    suspend fun windowRectOnScreen(r: BoundsDebouncer.Rect): Rectangle {
        val origin = canvasOrigin()
        val d = app.density
        return Rectangle(origin.x + (r.x / d).roundToInt(), origin.y + (r.y / d).roundToInt(), (r.width / d).roundToInt(), (r.height / d).roundToInt())
    }

    /** A point in Compose just above the surface: moving there leaves the page. */
    suspend fun composeGapPoint(): Point {
        val s = windowRectOnScreen(app.surfaceInWindow)
        return Point(s.x + s.width / 2, s.y - 3)
    }

    suspend fun titleBarPoint(): Point = onUi {
        val at = app.window.locationOnScreen
        Point(at.x + app.window.width / 2, at.y + 12)
    }

    // ---- input ----

    suspend fun moveTo(x: Int, y: Int) {
        robot.mouseMove(x, y)
        delay(40)
    }

    suspend fun click(x: Int, y: Int, buttonMask: Int = InputEvent.BUTTON1_DOWN_MASK, count: Int = 1) {
        moveTo(x, y)
        repeat(count) {
            robot.mousePress(buttonMask)
            delay(30)
            robot.mouseRelease(buttonMask)
            delay(60)
        }
    }

    suspend fun click(r: Rectangle) {
        click(r.x + r.width / 2, r.y + r.height / 2)
    }

    suspend fun activateWindow() {
        val p = titleBarPoint()
        click(p.x, p.y)
        delay(300)
    }

    suspend fun scan(code: Int) {
        WinNative.sendScan(code, true, false)
        delay(15)
        WinNative.sendScan(code, false, false)
        delay(30)
    }

    suspend fun typeAscii(text: String) {
        for (c in text) scan(checkNotNull(US_SCAN[c]) { "no scan code for '$c'" })
    }

    suspend fun key(vk: Int, vararg modifiers: Int) {
        for (m in modifiers) WinNative.sendVk(m, true)
        WinNative.sendVk(vk, true)
        delay(15)
        WinNative.sendVk(vk, false)
        for (m in modifiers.reversed()) WinNative.sendVk(m, false)
        delay(40)
    }

    // ---- what happened ----

    suspend fun pageClock(): Double = web.call("clock").double()

    suspend fun pageEvents(since: Double): List<PageEvent> = web.call("eventsSince", since).list().map { e ->
        PageEvent(
            e.field("type").str(), e.field("t").double(), e.field("id").str(), e.field("x").double(),
            e.field("y").double(), e.field("pointerType").str(), e.field("button").int(), e.field("detail").int(),
            e.field("key").str(), e.field("data").str(), e.field("ctrl").str() == "true",
        )
    }

    suspend fun takeMarks(): List<PageMark> = web.call("takeMarks").list().map { m ->
        val pair = m.list()
        PageMark(pair[0].int(), pair[1].double())
    }

    fun drainArrivals(): List<Arrival> {
        val out = ArrayList<Arrival>()
        while (true) {
            val n = WinNative.drainArrivals(arrivalBuffer)
            for (i in 0 until n) {
                val o = i * 7
                out += Arrival(
                    arrivalBuffer[o].toInt(), arrivalBuffer[o + 1], arrivalBuffer[o + 2], arrivalBuffer[o + 3],
                    arrivalBuffer[o + 4], arrivalBuffer[o + 5].toInt(), arrivalBuffer[o + 6].toInt(),
                )
            }
            if (n < 4096) return out
        }
    }

    /** Native capture counters: arrived, copied, emptyDirty, blank, recreates, rects, dirtyPixels, fullCopies, takes. */
    fun captureStats(): LongArray = LongArray(9).also { WinNative.captureStats(it) }

    suspend fun resetTimelines() {
        drainArrivals()
        web.buffer.takeDraws()
        takeMarks()
    }

    fun screenshot(name: String): File {
        val image = robot.createScreenCapture(Rectangle(Toolkit.getDefaultToolkit().screenSize))
        return File("${report.base.path}-$name.png").also { ImageIO.write(image, "png", it) }
    }

    /** Visible top-level windows (all processes when pid is 0). */
    fun topWindows(pid: Long): List<TopWindow> = WinNative.topLevelWindows(pid).lines().filter { it.isNotBlank() }.map { line ->
        val parts = line.split('|')
        val r = parts[3].split(',').map { it.toInt() }
        TopWindow(parts[0], parts[1], parts[2].toLong(), Rectangle(r[0], r[1], r[2] - r[0], r[3] - r[1]))
    }
}
