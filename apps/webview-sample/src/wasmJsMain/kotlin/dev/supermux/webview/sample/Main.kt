package dev.supermux.webview.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The browser sample: the perf lab and the overlay demo on the web's real engine, an iframe per live page. Run it with
 * `./gradlew :webview-sample:wasmJsBrowserDevelopmentRun` (localhost only). `web-check/run.mjs` drives its production
 * build in a headless Chrome through these URL parameters, and reads `window.__webviewSample`:
 * - `?screen=overlays` opens the overlay demo; `?screen=probe&case=…` one of the probes ([ProbeScreen]); any other value
 *   opens the perf lab;
 * - `?run=scroll|reading|fling` starts that run once the first screen has had 3 s to go live;
 * - `window.__webviewSample` has `ready`, `report` (the run's `perf-lab` line once it ends), `stats()`, `errors()`
 *   (what the runtime caught) and `overlayLive()` (a live page is on screen); on a probe, `probe` too.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val caught = ArrayList<String>()
    // The platform's engines, counted as the desktop lab counts its fakes.
    val engines = EngineCounter(WebViewRuntime.Default.factory)
    val runtime = WebViewRuntime(RuntimeConfig.platformDefault().copy(factory = engines))
    runtime.onError = { caught += it.toString() }
    val lab = PerfLabState(engines, runtime)
    val screen = if (queryParam("screen") == "overlays") Screen.Overlays else Screen.PerfLab
    val probe = if (queryParam("screen") == "probe") queryParam("case") ?: "" else null
    val run = LabRun.entries.firstOrNull { it.name.equals(queryParam("run"), ignoreCase = true) }
    installHooks(
        stats = { lab.sample().let { it.enginesLine() + " | " + it.memoryLine() } },
        errors = { caught.joinToString(" | ") },
    )
    ComposeViewport(document.body!!) {
        if (probe != null) {
            ProvideWebViewRuntime(runtime) { ProbeScreen(probe) }
            LaunchedEffect(Unit) { markReady() }
        } else {
            SampleApp(lab, engineLabel = WEB_ENGINE_LABEL, initialScreen = screen)
            LabAutomation(lab, run)
        }
    }
}

/** Marks the page ready once its first screen has had time to go live, then runs [run] and publishes its report. */
@Composable
private fun LabAutomation(lab: PerfLabState, run: LabRun?) {
    LaunchedEffect(lab, run) {
        delay(3_000)
        markReady()
        if (run != null) {
            lab.start(run)
            publishReport(snapshotFlow { lab.lastReport }.filterNotNull().first().line())
        }
    }
}

private fun queryParam(name: String): String? = js("new URLSearchParams(window.location.search).get(name)")

private fun installHooks(stats: () -> String, errors: () -> String): Unit = js("""{
  window.__webviewSample = {
    ready: false,
    report: null,
    stats: function () { return stats(); },
    errors: function () { return errors(); },
    overlayLive: function () {
      var frames = document.querySelectorAll('[data-webview-frame]');
      for (var i = 0; i < frames.length; i++) {
        if (frames[i].style.display === 'block' && frames[i].style.visibility === 'visible') return true;
      }
      return false;
    }
  };
}""")

private fun markReady(): Unit = js("{ window.__webviewSample.ready = true; }")

private fun publishReport(line: String): Unit = js("{ window.__webviewSample.report = line; }")
