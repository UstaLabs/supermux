package dev.supermux.webview.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.runtime.platformWebEngineFactory
import dev.supermux.webview.sample.checks.CheckScreen
import dev.supermux.webview.sample.checks.EngineChecks
import dev.supermux.webview.sample.checks.Measurements
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/**
 * The iOS host's only screen (iosApp/Sources/App.swift): the sample on real WKWebView engines, inside the safe area (the
 * status bar and the Dynamic Island take the taps over them). The keyboard counts too: the pages shrink above it, so the
 * field being typed in stays in view. Launched with `-engine-checks` (device-checks/ios-engine-checks.sh) it runs the
 * engine's conformance checks instead, prints a `CHECK` line for each, and exits; `-only <name>` runs one check, also one
 * too long for every run (`EngineChecks.onDemand`).
 * `-engine-measure` runs the measurements the same way, which print `MEASURE` lines too.
 */
fun MainViewController(): UIViewController {
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val only = arguments.indexOf("-only").takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
    if ("-engine-checks" in arguments) {
        val checks = if (only == null) EngineChecks.all else EngineChecks.all + EngineChecks.onDemand
        return ComposeUIViewController { CheckScreen(checks, only) }
    }
    if ("-engine-measure" in arguments) return ComposeUIViewController { CheckScreen(Measurements.all, only) }
    val lab = nativePerfLab()
    return ComposeUIViewController {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SampleApp(lab, engineLabel = IOS_ENGINE_LABEL)
        }
    }
}

/** The perf lab on this platform's engines, counted, with this platform's pool and cache sizes. */
private fun nativePerfLab(): PerfLabState {
    val engines = EngineCounter(platformWebEngineFactory())
    val runtime = WebViewRuntime(factory = engines, config = RuntimeConfig.platformDefault())
    runtime.onError = { println("webview-sample: the runtime caught $it") }
    return PerfLabState(engines, runtime)
}
