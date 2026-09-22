package dev.supermux.terminal.sample

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * The desktop sample.
 *
 * ```
 * ./gradlew :terminal-sample:run           # debug-ish: assertions on, no JIT restrictions removed
 * ./gradlew :terminal-sample:runRelease    # the build a measurement may be quoted from
 * ```
 *
 * `-Dsupermux.sample.terminals=4` mounts four terminals at start (the benchmark's shape); the UI
 * can switch between one and four at any time.
 */
fun main() {
    val terminals = System.getProperty("supermux.sample.terminals")?.toIntOrNull()?.coerceIn(1, 4) ?: 1
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "supermux terminal-sample" + if (isReleaseRun()) " · release" else "",
            state = rememberWindowState(width = 1100.dp, height = 820.dp),
        ) {
            SampleRoot(terminals = terminals)
        }
    }
}

/**
 * True when this JVM was started by `:terminal-sample:runRelease`.
 *
 * The flag exists so a screenshot or a pasted number can be traced back to the build it came from:
 * `runRelease` sets it, `run` does not, and the window title says which. See the module README for
 * exactly what the two runs differ in.
 */
fun isReleaseRun(): Boolean = System.getProperty("supermux.sample.buildType") == "release"
