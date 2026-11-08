package dev.supermux.webview.sample.linux

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.runtime.platformWebEngineFactory
import dev.supermux.webview.sample.EngineCounter
import dev.supermux.webview.sample.LINUX_ENGINE_LABEL
import dev.supermux.webview.sample.LabRun
import dev.supermux.webview.sample.PerfLabState
import dev.supermux.webview.sample.SampleApp
import dev.supermux.webview.sample.Screen
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window as AwtWindow
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The perf lab on this platform's engines (WPE WebKit on Linux), counted, with this platform's pool and cache sizes. */
fun wpePerfLab(): PerfLabState {
    val engines = EngineCounter(platformWebEngineFactory())
    val runtime = WebViewRuntime(RuntimeConfig.platformDefault().copy(factory = engines))
    runtime.onError = { println("webview-sample: the runtime caught $it") }
    return PerfLabState(engines, runtime)
}

/**
 * The sample on WPE engines, unattended (device-checks/linux-run.sh lab):
 * - the perf lab's scroll and reading runs, which print their `perf-lab` lines;
 * - then a screenshot of each screen into [out], with a `SHOT` line each;
 * - then `LAB DONE`, and exit 0.
 *
 * A step that doesn't finish in its time is `LAB FAIL <why>`, and exit 1.
 */
fun linuxLabMain(out: File) {
    out.mkdirs()
    val lab = wpePerfLab()
    application {
        var screen by remember { mutableStateOf(Screen.PerfLab) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "webview-sample · $LINUX_ENGINE_LABEL",
            undecorated = true,
            state = rememberWindowState(position = WindowPosition(0.dp, 0.dp), size = DpSize(1100.dp, 1000.dp)),
        ) {
            LaunchedEffect(Unit) {
                val failure = runLab(lab, out, window) { screen = it }
                println(if (failure == null) "LAB DONE" else "LAB FAIL $failure")
                System.out.flush()
                exitProcess(if (failure == null) 0 else 1)
            }
            key(screen) { SampleApp(lab, engineLabel = LINUX_ENGINE_LABEL, initialScreen = screen) }
        }
    }
}

/** The runs, then the screenshots; null when all of it happened, else why not. (Windows' lab runs it too.) */
internal suspend fun runLab(lab: PerfLabState, out: File, window: AwtWindow, show: (Screen) -> Unit): String? {
    // The first screen of messages live: pages loaded, painted, snapshotted (the images baseline has none to wait for).
    if (!lab.baseline) withTimeoutOrNull(60_000) { while (lab.sample().live == 0) delay(200) } ?: return "no page went live in 60 s"
    delay(3_000)
    for (run in listOf(LabRun.Scroll, LabRun.Reading)) {
        val before = lab.lastReport
        lab.start(run)
        withTimeoutOrNull(300_000) { while (lab.lastReport === before || lab.running != null) delay(200) }
            ?: return "${run.label} didn't finish in 5 minutes"
    }
    shot(window, out, "perf-lab")
    for (next in listOf(Screen.Overlays, Screen.Checks)) {
        show(next)
        delay(5_000)
        shot(window, out, next.name.lowercase())
    }
    return null
}

/** The window as it is on screen, into [out]/[name].png. */
private suspend fun shot(window: AwtWindow, out: File, name: String) {
    val bounds = Rectangle(window.locationOnScreen, window.size)
    val file = File(out, "$name.png")
    withContext(Dispatchers.IO) { ImageIO.write(Robot().createScreenCapture(bounds), "png", file) }
    println("SHOT $name ${file.path}")
}
