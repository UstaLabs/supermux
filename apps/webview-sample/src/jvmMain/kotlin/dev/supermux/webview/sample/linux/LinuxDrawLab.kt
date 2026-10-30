package dev.supermux.webview.sample.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.platformWebEngineFactory
import java.lang.management.ManagementFactory
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A spinner on a white page: a small ring turning in the middle, so each of its frames changes a small area only. */
private fun spinnerPage(n: Int): WebContent.Html = WebContent.Html(
    """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>spinner $n</title>
<style>html,body{margin:0;height:100%;background:#fff;font:16px system-ui}
.s{position:absolute;left:50%;top:50%;width:48px;height:48px;margin:-30px;border:6px solid #3366cc;
border-top-color:transparent;border-radius:50%;animation:t 1s linear infinite}
@keyframes t{to{transform:rotate(360deg)}}</style></head><body><p>view $n</p><div class="s"></div></body></html>""",
)

/**
 * The real draw path's cost (the review's I6), unattended (device-checks/linux-run.sh draw): [spec] is
 * `<views>x<width>x<height>`, the default `6x1600x1200`. That many WPE engines, a spinner page each, their surfaces
 * side by side at that size in pixels (density 1). Once every page has painted, for 10 s:
 * - the UI thread's CPU time per Compose frame (the AWT event thread, where Compose pulls, draws and renders);
 * - the frames and their intervals.
 *
 * Prints one `DRAW` line, then exits 0; `DRAW FAIL <why>` and 1 if a page didn't paint.
 */
fun linuxDrawLabMain(spec: String) {
    val (views, width, height) = spec.split('x').map { it.trim().toInt() }.let { Triple(it[0], it[1], it[2]) }
    val columns = minOf(views, 3)
    val rows = (views + columns - 1) / columns
    application {
        val engines = remember { mutableStateListOf<WebEngine>() }
        Window(
            onCloseRequest = ::exitApplication,
            title = "webview-sample · draw lab",
            undecorated = true,
            state = rememberWindowState(position = WindowPosition(0.dp, 0.dp), size = DpSize((columns * width).dp, (rows * height).dp)),
        ) {
            LaunchedEffect(Unit) {
                val failure = runDrawLab(views, width, height, engines)
                if (failure != null) println("DRAW FAIL $failure")
                System.out.flush()
                engines.forEach { runCatching { it.release() } }
                exitProcess(if (failure == null) 0 else 1)
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF202124))) {
                engines.forEachIndexed { i, engine ->
                    key(engine) {
                        engine.Surface(Modifier.offset(((i % columns) * width).dp, ((i / columns) * height).dp).size(width.dp, height.dp))
                    }
                }
            }
        }
    }
}

/** The run, on the UI thread; null when it measured, else why not. */
private suspend fun runDrawLab(views: Int, width: Int, height: Int, engines: MutableList<WebEngine>): String? {
    val factory = platformWebEngineFactory()
    for (i in 0 until views) {
        val engine = (factory.create(EngineConfig(WebProfile.Ephemeral)) as? WebEngineResult.Created)?.engine ?: return "no engine $i"
        val content = spinnerPage(i)
        engine.configure(setupFor(content))
        engine.load(content)
        engines += engine
    }
    for ((i, engine) in engines.withIndex()) {
        withTimeoutOrNull(30_000) { engine.events.first { it == EngineEvent.FirstPaint } } ?: return "view $i didn't paint in 30 s"
    }
    delay(2_000)
    val ui = Thread.currentThread().id
    val threads = ManagementFactory.getThreadMXBean()
    val process = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    val intervals = ArrayList<Long>()
    val cpuBefore = threads.getThreadCpuTime(ui)
    val processBefore = process.processCpuTime
    val start = System.nanoTime()
    var last = withFrameNanos { it }
    while (System.nanoTime() - start < 10_000_000_000L) {
        val now = withFrameNanos { it }
        intervals += now - last
        last = now
    }
    val elapsed = System.nanoTime() - start
    val cpu = threads.getThreadCpuTime(ui) - cpuBefore
    val processCpu = process.processCpuTime - processBefore
    intervals.sort()
    fun ms(nanos: Long) = "%.1f".format(nanos / 1e6)
    fun at(q: Double) = intervals[((intervals.size - 1) * q).toInt()]
    println(
        "DRAW views=$views size=${width}x$height frames=${intervals.size} fps=${"%.1f".format(intervals.size / (elapsed / 1e9))} " +
            "ui_cpu_ms_per_frame=${"%.2f".format(cpu / 1e6 / intervals.size)} ui_cpu_share=${"%.0f".format(100.0 * cpu / elapsed)}% " +
            "jvm_cpu_share=${"%.0f".format(100.0 * processCpu / elapsed)}% interval_p50=${ms(at(0.5))} p95=${ms(at(0.95))} " +
            "p99=${ms(at(0.99))} max=${ms(intervals.last())} ms",
    )
    return null
}
