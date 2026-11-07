package dev.supermux.webview.sample.windows

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
import dev.supermux.webview.sample.PerfLabState
import dev.supermux.webview.sample.SampleApp
import dev.supermux.webview.sample.Screen
import dev.supermux.webview.sample.WINDOWS_ENGINE_LABEL
import dev.supermux.webview.sample.linux.runLab
import dev.supermux.webview.sample.linux.wpePerfLab
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.system.exitProcess

/** The perf lab on this platform's engines (WebView2 on Windows), counted, with this platform's pool and cache sizes. */
fun webview2PerfLab(): PerfLabState = wpePerfLab()

/**
 * The sample on WebView2 engines (`-Dwebview.sample.engine=webview2`): by hand without [out]; with it, unattended
 * (winvm.sh lab), as Linux's lab: the perf lab's scroll and reading runs (`perf-lab` lines), a screenshot of each screen
 * into [out] (`SHOT` lines), then `LAB DONE` and exit 0, or `LAB FAIL <why>` and exit 1. With
 * `-Dwebview.sample.lab=<measures>` (comma-separated: memory, firstpaint, bridge, press, copy), those measures instead
 * ([windowsMeasuresMain], Task 31); with `-Dwebview.sample.labImages=true`, the runs on the lab's images (its baseline,
 * what the web views' runs are judged against).
 */
fun windowsLabMain(out: File?) {
    System.getProperty("webview.sample.lab")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { steps ->
        return windowsMeasuresMain(steps)
    }
    val lab = webview2PerfLab().apply { baseline = System.getProperty("webview.sample.labImages") == "true" }
    // The lab's 1100 x 1000 dp, within the screen: the VM's virtio display is smaller.
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val size = DpSize(minOf(1100, screen.width).dp, minOf(1000, screen.height).dp)
    application {
        var shown by remember { mutableStateOf(windowsFirstScreen(out, System.getProperty("webview.sample.screen"))) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "webview-sample · $WINDOWS_ENGINE_LABEL",
            state = rememberWindowState(position = WindowPosition(0.dp, 0.dp), size = size),
        ) {
            if (out != null) {
                LaunchedEffect(Unit) {
                    out.mkdirs()
                    val failure = runLab(lab, out, window) { shown = it }
                    println(if (failure == null) "LAB DONE" else "LAB FAIL $failure")
                    System.out.flush()
                    exitProcess(if (failure == null) 0 else 1)
                }
            }
            key(shown) { SampleApp(lab, engineLabel = WINDOWS_ENGINE_LABEL, initialScreen = shown) }
        }
    }
}

/**
 * The screen the Windows sample opens on: the perf lab for an unattended lab run (an [out] folder), else the screen
 * `-Dwebview.sample.screen=<name>` names, else the chat, as on the other platforms.
 */
internal fun windowsFirstScreen(out: File?, screenProperty: String?): Screen = when {
    out != null -> Screen.PerfLab
    else -> screenProperty?.let { name -> Screen.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } } ?: Screen.Chat
}
