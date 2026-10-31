package dev.supermux.webview.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import dev.supermux.webview.sample.checks.CheckScreen
import dev.supermux.webview.sample.checks.EngineChecks
import dev.supermux.webview.sample.checks.IosCheckPlatform
import dev.supermux.webview.sample.checks.IosChecks
import dev.supermux.webview.sample.checks.IosMeasurements
import dev.supermux.webview.sample.checks.Measurements
import androidx.compose.runtime.LaunchedEffect
import dev.supermux.webview.sample.chat.ChatRun
import dev.supermux.webview.sample.chat.ChatState
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/**
 * The iOS host's only screen (iosApp/Sources/App.swift): the sample on real WKWebView engines. It opens on the chat demo,
 * whose bars reach under the status bar and the home indicator and whose content keeps clear of them and of the
 * keyboard; the lab's screens sit inside the safe area (the status bar and the Dynamic Island take the taps over them),
 * keyboard included, so the field being typed in stays in view.
 * - `-screen chat|perflab|overlays|checks` opens that screen (device-checks/ios-sim.sh opens the perf lab).
 * - `-chat-run read|scroll|fling` starts that run of the chat 3 s after launch; it prints a `chat-perf:` line.
 *   `-chat-baseline` shows the previews as still pictures, the baseline the web views are measured against;
 *   `-chat-cache` composes rows ahead of the screen (`ChatState.cacheWindow`).
 * - `-chat-shots` copies the window at a few places in the chat into `Documents/shots/` ([ChatShots]).
 * - `-engine-checks` (device-checks/ios-engine-checks.sh) runs the engine's conformance checks instead (those it shares
 *   with macOS, then the iOS engine's own, `IosChecks`), prints a `CHECK` line for each, and exits; `-only <name>` runs
 *   one check, also one too long for every run (`EngineChecks.onDemand`).
 * - `-engine-measure` runs the measurements the same way, which print `MEASURE` lines too.
 * - `-gesture-probe` shows the user-gesture probe instead ([GestureProbe]), which device-checks/ios-sim.sh drives with
 *   Maestro.
 */
fun MainViewController(): UIViewController {
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val only = argument(arguments, "-only")
    if ("-engine-checks" in arguments) {
        val checks = EngineChecks.all + IosChecks.all + if (only == null) emptyList() else EngineChecks.onDemand
        return ComposeUIViewController { CheckScreen(checks, only, IosCheckPlatform) }
    }
    if ("-engine-measure" in arguments) {
        return ComposeUIViewController { CheckScreen(Measurements.all + IosMeasurements.all, only, IosCheckPlatform) }
    }
    if ("-gesture-probe" in arguments) {
        return ComposeUIViewController { Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { GestureProbe() } }
    }
    val screen = argument(arguments, "-screen")?.let { name -> Screen.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    val chatRun = argument(arguments, "-chat-run")?.let { name -> ChatRun.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    val shots = "-chat-shots" in arguments
    val lab = platformPerfLab()
    val chat = ChatState(lab, cacheWindow = "-chat-cache" in arguments).apply { baseline = "-chat-baseline" in arguments }
    return ComposeUIViewController {
        SampleApp(
            lab,
            engineLabel = IOS_ENGINE_LABEL,
            initialScreen = screen ?: Screen.Chat,
            safeArea = WindowInsets.safeDrawing,
            chatRun = chatRun,
            chatState = chat,
        )
        if (shots) LaunchedEffect(Unit) { ChatShots.take(chat) }
    }
}

private fun argument(arguments: List<String>, name: String): String? =
    arguments.indexOf(name).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
