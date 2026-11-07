package dev.supermux.webview.sample.linux

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.platformWebEngineFactory
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Another process of the app, for the checks (`-Dwebview.sample.probe=<profile>`): asks this platform's factory for an
 * engine of the named profile, prints `PROBE Created` or `PROBE Unavailable <why>`, and exits.
 */
fun linuxProfileProbeMain(profile: String) {
    val result = runBlocking(Dispatchers.Main) { platformWebEngineFactory().create(EngineConfig(WebProfile.Named(profile))) }
    println(
        "PROBE " + when (result) {
            is WebEngineResult.Created -> "Created"
            is WebEngineResult.Unavailable -> "Unavailable ${result.detail}"
        },
    )
    System.out.flush()
    exitProcess(0)
}
