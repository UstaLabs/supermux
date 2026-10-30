package dev.supermux.webview.wintest

import java.awt.EventQueue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Every scripted scenario, by the name `-Dwv2.scenario` selects. Each later task adds its own line. */
val scenarios: Map<String, suspend (Harness) -> Unit> = mapOf(
    "smoke" to { h -> h.smoke() },
)

/** Runs the selected scenario off the UI thread, writes its report, then calls [exit] on the UI thread. */
fun runScenario(app: AppState, exit: (Int) -> Unit) {
    val name = app.options.scenario
    val scenario = scenarios[name]
    if (scenario == null) {
        System.err.println("wv2: unknown scenario '$name'; known: ${scenarios.keys.sorted()}")
        exit(2)
        return
    }
    CoroutineScope(Dispatchers.Default).launch {
        val report = Report(name, File(app.options.outDir))
        val harness = Harness(app, report)
        val code = try {
            withTimeout(14 * 60_000L) {
                harness.prepare()
                scenario(harness)
            }
            0
        } catch (t: Throwable) {
            report.line("ERROR", Verdict.FAIL, listOf("exception" to t.toString()))
            t.printStackTrace()
            1
        }
        report.finish()
        EventQueue.invokeLater { exit(code) }
    }
}
