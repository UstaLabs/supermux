package dev.supermux.terminal.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

/**
 * The one entry point every host of this sample calls — desktop, Android, iOS and the browser.
 *
 * Deliberately tiny: the sample's whole point is that the SAME composable runs on four platforms
 * with nothing per-platform in the terminal path, so each host's `main` is an entry point and a
 * window and nothing else.
 */
@Composable
fun SampleRoot(terminals: Int = 1) {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { SampleController(scope, count = terminals) }
    DisposableEffect(controller) {
        // The host owns the sessions; leaving the composition closes them. `Terminal` itself never
        // would — it owns what it draws, not what it draws FROM.
        onDispose { controller.disposeAll() }
    }
    SampleTheme {
        SampleApp(controller)
    }
}
