package dev.supermux.webview.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.system.exitProcess

/** How long the list rests before each run: its new content composes and its first screen goes live. */
private const val LAB_SETTLE_MILLIS = 5_000L

/**
 * The perf lab's scripted runs on the real engine (plan Task 17, spec §10), in the window's [PerfLab]: the Reading and
 * Scroll tests over its 200 messages with web views, then the same with still images, the baseline they are measured
 * against. Each run prints its own `perf-lab:` line; then this prints `CHECKS DONE passed=<runs finished>
 * failed=<runs stopped>`, which `mac-engine-checks.sh` waits for, and the app exits.
 */
@Composable
fun LabRuns(lab: PerfLabState) {
    LaunchedEffect(lab) {
        var finished = 0
        var stopped = 0
        for (images in listOf(false, true)) {
            for (run in listOf(LabRun.Reading, LabRun.Scroll)) {
                lab.baseline = images
                delay(LAB_SETTLE_MILLIS)
                val before = lab.lastReport
                lab.start(run)
                val report = snapshotFlow { lab.lastReport }.filterNotNull().first { it !== before }
                if (report.name == run.label) finished++ else stopped++
            }
        }
        println("CHECKS DONE passed=$finished failed=$stopped")
        exitProcess(if (stopped == 0) 0 else 1)
    }
}
