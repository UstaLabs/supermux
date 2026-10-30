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
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/**
 * The iOS host's only screen (iosApp/Sources/App.swift): the sample on real WKWebView engines, inside the safe area (the
 * status bar and the Dynamic Island take the taps over them). The keyboard counts too: the pages shrink above it, so the
 * field being typed in stays in view. Launched with `-engine-checks` (device-checks/ios-engine-checks.sh) it runs the
 * engine's conformance checks instead (those it shares with macOS, then the iOS engine's own, `IosChecks`), prints a
 * `CHECK` line for each, and exits; `-only <name>` runs one check, also one too long for every run
 * (`EngineChecks.onDemand`).
 * `-engine-measure` runs the measurements the same way, which print `MEASURE` lines too. `-gesture-probe` shows the
 * user-gesture probe instead ([GestureProbe]), which device-checks/ios-sim.sh drives with Maestro.
 */
fun MainViewController(): UIViewController {
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val only = arguments.indexOf("-only").takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
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
    val lab = platformPerfLab()
    return ComposeUIViewController {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SampleApp(lab, engineLabel = IOS_ENGINE_LABEL)
        }
    }
}
