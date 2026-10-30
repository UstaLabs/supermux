package dev.supermux.webview.layouttest

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File

/**
 * The macOS layout test (docs/superpowers/plans/2026-10-29-webview-macos-layout-test.md). mac/run.sh starts it as a
 * transient Aqua LaunchAgent, never Gradle: an SSH session has no window server.
 */
fun main() {
    val config = TestConfig.fromSystemProperties()
    Native.load()
    application {
        val windowState = rememberWindowState(width = 1200.dp, height = 860.dp, position = WindowPosition(Alignment.Center))
        val model = remember { TestModel() }
        Window(onCloseRequest = ::exitApplication, title = "webview layout test", state = windowState) {
            LaunchedEffect(Unit) {
                // windowHandle is the NSWindow once skiko's layer is in the window, from about the first frame.
                var handle = 0L
                while (handle == 0L) {
                    withFrameNanos { }
                    handle = window.windowHandle
                }
                model.window = handle
                model.page = Native.attach(handle, TestPage.html, model.listener)
                if (config.mode == Mode.Manual) {
                    // The user's checks run with both fixes on; the automated run measures them on and off.
                    model.keyRouting = true
                    model.accessibilityBridge = true
                    println("LAYOUT-TEST MANUAL ready")
                } else {
                    val shots = Screenshots(config.shots, File(config.outDir, "shots"))
                    Scenario(model, config, windowState, Report(config.outDir), shots).run()
                    val page = model.page
                    model.page = 0L
                    Native.detach(page)
                    exitApplication()
                }
            }
            LayoutTestApp(model)
        }
    }
}
