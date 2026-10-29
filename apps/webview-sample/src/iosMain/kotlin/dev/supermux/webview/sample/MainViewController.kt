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
import platform.UIKit.UIViewController

/**
 * The iOS host's only screen (iosApp/Sources/App.swift): the sample on real WKWebView engines, inside the safe area (the
 * status bar and the Dynamic Island take the taps over them). The keyboard counts too: the pages shrink above it, so the
 * field being typed in stays in view.
 */
fun MainViewController(): UIViewController {
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
