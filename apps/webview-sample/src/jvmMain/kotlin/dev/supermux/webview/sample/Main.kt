package dev.supermux.webview.sample

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.webview.sample.checks.CheckScreen
import dev.supermux.webview.sample.checks.EngineChecks
import dev.supermux.webview.sample.checks.MacCheckPlatform
import dev.supermux.webview.sample.checks.MacChecks
import dev.supermux.webview.sample.checks.Measurements
import dev.supermux.webview.sample.checks.MotionChecks
import dev.supermux.webview.sample.linux.linuxChecksMain
import dev.supermux.webview.sample.linux.linuxDrawLabMain
import dev.supermux.webview.sample.linux.linuxProfileProbeMain
import dev.supermux.webview.sample.linux.linuxLabMain
import dev.supermux.webview.sample.linux.wpePerfLab
import dev.supermux.webview.testing.MacEngineTesting
import java.io.File

/**
 * The desktop sample: `./gradlew :webview-sample:run`, or on the Mac through `device-checks/mac-*.sh`. With the macOS
 * engine's glue (a Mac) its pages run on WKWebView; elsewhere on the library's fake engine (solid colours, each content
 * its own). Each perf-lab run prints a `perf-lab` line. On Linux, `-Dwebview.sample.engine=wpe` runs the pages on WPE
 * WebKit instead, with its runtime installed (the library README's Linux section); for device-checks/linux-run.sh,
 * `-Dwebview.sample.checks=true` runs the Linux engine's checks and exits, and with `-Dwebview.sample.out=<dir>` too
 * the WPE sample runs unattended and leaves its screenshots there. On a Mac:
 * - `-engine-checks` runs the engine's conformance checks instead, prints a `CHECK` line for each, and exits; `-only
 *   <name>` runs that one, also one too long for every run.
 * - `-engine-measure` runs the measurements, which print `MEASURE` lines too.
 * - `-out <dir>`: where the checks save their frames.
 * - `-perf-lab` runs the perf lab's Reading and Scroll tests, with web views and then still images, and exits.
 * - `-screen perflab|overlays` opens that screen; with `-shots <dir>` the app asks for one screenshot of it and exits.
 */
fun main(args: Array<String>) {
    if (System.getProperty("webview.sample.checks") == "true") return linuxChecksMain(System.getProperty("webview.sample.only"))
    System.getProperty("webview.sample.draw")?.let { return linuxDrawLabMain(it) }
    System.getProperty("webview.sample.probe")?.let { return linuxProfileProbeMain(it) }
    if (System.getProperty("webview.sample.engine") == "wpe") return wpeMain(System.getProperty("webview.sample.out")?.let(::File))

    val checks = "-engine-checks" in args
    val measure = "-engine-measure" in args
    val perfLab = "-perf-lab" in args
    val only = argument(args, "-only")
    val out = File(argument(args, "-out") ?: System.getProperty("java.io.tmpdir"))
    val real = MacEngineTesting.isAvailable
    val shots = argument(args, "-shots")?.let(::File)
    val screen = when (argument(args, "-screen")) {
        "overlays" -> Screen.Overlays
        else -> Screen.PerfLab
    }
    val label = if (real) MACOS_ENGINE_LABEL else FAKE_ENGINE_LABEL
    application {
        val state = rememberWindowState(width = 1100.dp, height = 1000.dp)
        Window(onCloseRequest = ::exitApplication, title = "webview-sample · $label", state = state) {
            if (checks || measure) {
                val platform = remember { MacCheckPlatform(window, out, state) }
                LaunchedEffect(Unit) {
                    // skiko's layer joins the window about the first frame (M8); then the backing can be painted.
                    while (window.windowHandle == 0L) withFrameNanos { }
                    MacEngineTesting.paintBacking(window, MacCheckPlatform.BACKING)
                }
                val list = when {
                    measure -> Measurements.all
                    only == null -> EngineChecks.all + MacChecks.all + MotionChecks.all
                    else -> EngineChecks.all + EngineChecks.onDemand + MacChecks.all + MotionChecks.all
                }
                CheckScreen(list, only, platform)
            } else if (perfLab) {
                val lab = remember { if (real) platformPerfLab() else samplePerfLab() }
                SampleApp(lab, engineLabel = label)
                LabRuns(lab)
            } else {
                val lab = remember { if (real) platformPerfLab() else samplePerfLab() }
                SampleApp(lab, engineLabel = label, initialScreen = screen, pickFiles = { request -> pickFiles(window, request) })
                if (shots != null) ShotRequest(window, shots, screen.name.lowercase())
            }
        }
    }
}

private fun argument(args: Array<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

/** On Linux with `-Dwebview.sample.engine=wpe`: the sample on WPE WebKit, by hand, or unattended into [out]. */
private fun wpeMain(out: File?) {
    if (out != null) return linuxLabMain(out)
    application {
        val lab = remember { wpePerfLab() }
        Window(
            onCloseRequest = ::exitApplication,
            title = "webview-sample · $LINUX_ENGINE_LABEL",
            state = rememberWindowState(width = 1100.dp, height = 1000.dp),
        ) {
            SampleApp(lab, engineLabel = LINUX_ENGINE_LABEL)
        }
    }
}

/** AWT's file dialog (on macOS, the system's open panel), as the checks page's picker: paths, or null if cancelled. */
private fun pickFiles(window: java.awt.Frame, request: dev.supermux.webview.FileChooserRequest): List<String>? {
    val dialog = java.awt.FileDialog(window, "Choose files", java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = request.allowMultiple
    dialog.isVisible = true
    return dialog.files.map { it.absolutePath }.takeIf { it.isNotEmpty() }
}
