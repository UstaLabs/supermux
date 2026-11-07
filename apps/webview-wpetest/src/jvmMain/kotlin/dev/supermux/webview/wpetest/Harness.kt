package dev.supermux.webview.wpetest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The view the window shows, and its frame buffer: Compose state, so swapping them recomposes the window. */
class Stage {
    var view: LabView? by mutableStateOf(null)
    var frames: FrameBuffer by mutableStateOf(FrameBuffer())
}

/** Result lines: `WPE …` on stdout, which in-container.sh collects into results/<scenario>-<stamp>.txt. */
class Report(private val options: LabOptions) {
    @Volatile var failed = false
        private set

    fun line(scenario: String, check: String, verdict: Verdict, details: List<Pair<String, Any?>>) {
        if (verdict == Verdict.FAIL) failed = true
        println(resultLine(scenario, check, verdict, options.host, details))
        System.out.flush()
    }

    fun line(scenario: String, check: String, verdict: Verdict, vararg details: Pair<String, Any?>) =
        line(scenario, check, verdict, details.toList())
}

/** What every scenario gets: the run's options, WebKit, the stage, the window, a robot and helpers. */
class Lab(val options: LabOptions, val host: LabHost, val stage: Stage, val report: Report, val window: ComposeWindow) {
    val robot: Robot = Robot().apply { autoDelay = 0 }

    /** Shows [view] in the window, with a fresh frame buffer. */
    suspend fun show(view: LabView) = withContext(Dispatchers.Main) {
        stage.frames = FrameBuffer()
        stage.view = view
    }

    suspend fun waitUntil(timeoutMs: Long, stepMs: Long = 10, condition: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(stepMs)
            true
        } ?: false

    fun click(x: Int, y: Int) {
        robot.mouseMove(x, y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    fun key(code: Int) {
        robot.keyPress(code)
        robot.keyRelease(code)
    }

    /** Types [text] through the X server, keysym by keysym: xdotool remaps a spare keycode for what the layout lacks. */
    fun type(text: String) {
        ProcessBuilder("xdotool", "type", "--delay", "40", "--", text).inheritIO().start().waitFor()
    }

    /** The screen's pixel, 0xRRGGBB. */
    fun pixel(x: Int, y: Int): Int = robot.getPixelColor(x, y).rgb and 0xffffff

    suspend fun counter(view: LabView): Int = view.eval("__labPage.counter()")?.toIntOrNull() ?: -1

    fun parse(text: String?): JsonElement? = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
}

/** A WebKit process of ours, with its PSS (proportional set size: shared pages split among their users), in kB. */
data class Proc(val pid: Long, val name: String, val pssKb: Long)

fun webkitProcesses(): List<Proc> = File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
    // platform-ok: a Linux process's argv[0] from /proc, in a Linux-only lab
    val name = runCatching { File(dir, "cmdline").readText().substringBefore('\u0000').substringAfterLast('/') }.getOrNull()
    if (name != "WPEWebProcess" && name != "WPENetworkProcess") null else Proc(dir.name.toLong(), name, pssKb(dir))
}

fun pssKb(dir: File): Long = runCatching {
    File(dir, "smaps_rollup").readLines().first { it.startsWith("Pss:") }.trim().split(Regex("\\s+"))[1].toLong()
}.getOrDefault(0L)

fun selfPssKb(): Long = pssKb(File("/proc/self"))

fun cpuModel(): String =
    runCatching { File("/proc/cpuinfo").readLines().first { it.startsWith("model name") }.substringAfter(':').trim() }.getOrDefault("?")

fun loadAverage(): String = runCatching { File("/proc/loadavg").readText().split(' ').take(3).joinToString("/") }.getOrDefault("?")
