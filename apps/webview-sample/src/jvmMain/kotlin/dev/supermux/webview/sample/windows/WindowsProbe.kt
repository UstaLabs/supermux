package dev.supermux.webview.sample.windows

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.platformWebEngineFactory
import dev.supermux.webview.sample.linux.setupFor
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** The page both processes of the ephemeral check open: one origin, so one store if they ever shared a profile. */
internal val PROBE_PAGE: WebContent.Html =
    WebContent.Html("<!doctype html><html><head><meta charset='utf-8'><title>probe</title></head><body>probe</body></html>", WebOrigin("probe"))

/**
 * Another process of the app, for the check `two-processes-never-share-an-ephemeral-store`
 * (`-Dwebview.sample.probe=ephemeral`): an `Ephemeral` engine opens [PROBE_PAGE] and reads `localStorage.x` there. Prints
 * `PROBE Created x=<value as JSON>`, or `PROBE Unavailable <why>`, and exits.
 */
fun windowsProbeMain() {
    val line = runBlocking(Dispatchers.Main) {
        when (val made = platformWebEngineFactory().create(EngineConfig(WebProfile.Ephemeral))) {
            is WebEngineResult.Unavailable -> "Unavailable ${made.reason}: ${made.detail}"
            is WebEngineResult.Created -> {
                val engine = made.engine
                engine.configure(setupFor(PROBE_PAGE))
                engine.load(PROBE_PAGE)
                val committed = withTimeoutOrNull(30_000) { engine.events.first { it == EngineEvent.UrlChanged(PROBE_PAGE.origin.url) } }
                val x = if (committed == null) "no commit in 30 s" else engine.evaluate("localStorage.getItem('x')")
                engine.release()
                "Created x=$x"
            }
        }
    }
    println("PROBE $line")
    System.out.flush()
    exitProcess(0)
}
