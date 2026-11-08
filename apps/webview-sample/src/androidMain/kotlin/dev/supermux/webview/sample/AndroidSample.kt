package dev.supermux.webview.sample

import android.util.Log
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.runtime.platformWebEngineFactory

/**
 * The perf lab on a phone: the system WebView unless [fake], every engine counted, with this platform's pool and cache
 * sizes. What the runtime's guards catch goes to logcat (tag webview-sample).
 */
fun androidPerfLab(fake: Boolean): PerfLabState {
    val engines = EngineCounter(if (fake) fakeEngines() else platformWebEngineFactory())
    val runtime = WebViewRuntime(RuntimeConfig.platformDefault().copy(factory = engines))
    runtime.onError = { Log.w("webview-sample", "the runtime caught", it) }
    return PerfLabState(engines, runtime)
}
