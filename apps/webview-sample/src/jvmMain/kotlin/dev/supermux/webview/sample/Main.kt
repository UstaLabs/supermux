package dev.supermux.webview.sample

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * The desktop sample: `./gradlew :webview-sample:run`. Desktop has no real engine until the macOS, Windows and Linux
 * plans land, so it runs the library's fake engine: the pool, snapshots, page memory and overlays are the real
 * runtime's, the pages are solid colour. Each perf-lab run prints a `perf-lab` line.
 */
fun main() = application {
    val lab = remember { samplePerfLab() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "webview-sample · fake engine",
        state = rememberWindowState(width = 1100.dp, height = 1000.dp),
    ) {
        SampleApp(lab)
    }
}
