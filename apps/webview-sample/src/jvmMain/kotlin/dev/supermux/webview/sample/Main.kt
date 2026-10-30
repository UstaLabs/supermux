package dev.supermux.webview.sample

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.webview.sample.linux.linuxChecksMain
import dev.supermux.webview.sample.linux.linuxLabMain
import dev.supermux.webview.sample.linux.wpePerfLab
import java.io.File

/**
 * The desktop sample: `./gradlew :webview-sample:run`. It runs the library's fake engine: the pool, snapshots, page
 * memory and overlays are the real runtime's, and the pages are solid colours, each content its own. Each perf-lab run
 * prints a `perf-lab` line.
 * - **On Linux,** `-Dwebview.sample.engine=wpe` runs the pages on WPE WebKit instead, with its runtime installed (the
 *   library README's Linux section).
 * - **For device-checks/linux-run.sh,** `-Dwebview.sample.checks=true` runs the Linux engine's checks and exits; with
 *   `-Dwebview.sample.out=<dir>` too, the WPE sample runs unattended and leaves its screenshots there.
 */
fun main() {
    if (System.getProperty("webview.sample.checks") == "true") return linuxChecksMain(System.getProperty("webview.sample.only"))
    val wpe = System.getProperty("webview.sample.engine") == "wpe"
    val out = System.getProperty("webview.sample.out")
    if (wpe && out != null) return linuxLabMain(File(out))
    application {
        val lab = remember { if (wpe) wpePerfLab() else samplePerfLab() }
        val label = if (wpe) LINUX_ENGINE_LABEL else FAKE_ENGINE_LABEL
        Window(
            onCloseRequest = ::exitApplication,
            title = "webview-sample · $label",
            state = rememberWindowState(width = 1100.dp, height = 1000.dp),
        ) {
            SampleApp(lab, engineLabel = label)
        }
    }
}
