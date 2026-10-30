package dev.supermux.webview.wpetest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The scenarios `lab.sh run` can name; each later task adds its line. */
val scenarios: Map<String, suspend (Lab) -> Unit> = mapOf(
    "smoke" to ::smoke,
)

/**
 * The lab app: one undecorated window at the screen's top-left corner, its surface filling it, and one scenario run
 * against it. It exits 0 with no FAIL line, 1 with one, 2 when the scenario itself broke.
 */
fun main() {
    val options = LabOptions.fromSystem()
    val report = Report(options)
    val host = try {
        LabHost.start(options)
    } catch (t: Throwable) {
        report.line("smoke", "S1-init", Verdict.FAIL, "error" to (t.message ?: t.toString()))
        exitProcess(2)
    }
    val stage = Stage()
    application(exitProcessOnExit = true) {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(position = WindowPosition(0.dp, 0.dp), size = DpSize(options.width.dp, options.height.dp)),
            undecorated = true,
            resizable = false,
            title = "wpelab",
        ) {
            // Dark, so a frame that isn't the page's shows up as one.
            Box(Modifier.fillMaxSize().background(Color(0xFF202020))) {
                stage.view?.let { view -> LabSurface(view, stage.frames, options, Modifier.fillMaxSize()) }
            }
            val composeWindow = window
            LaunchedEffect(Unit) {
                val lab = Lab(options, host, stage, report, composeWindow)
                val code = try {
                    val scenario = scenarios[options.scenario] ?: error("no scenario '${options.scenario}'")
                    withContext(Dispatchers.Default) { scenario(lab) }
                    if (report.failed) 1 else 0
                } catch (t: Throwable) {
                    report.line(options.scenario, "error", Verdict.FAIL, "error" to t.toString())
                    t.printStackTrace()
                    2
                }
                exitProcess(code)
            }
        }
    }
}
