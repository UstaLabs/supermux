package dev.supermux.editor.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.window.ComposeViewport
import dev.supermux.editor.syntax.WasmBackend
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * The web page. `?bench=1` runs the in-app benchmark and publishes its JSON as
 * `window.__editorBench`; every page reports its startup phases to the cold-start monitor in
 * index.html (`window.__coldPhase`), which web-bench/run.mjs reads.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    phase("app")
    val params = window.location.search
    val bench = params.contains("bench=1")
    ComposeViewport(document.body!!) {
        SampleApp(
            loadBackend = { WasmBackend.load() },
            bench = if (bench) { json -> publishBench(json) } else null,
            scrollDriver = {
                // web-bench/run.mjs sends trusted wheel input (untrusted DOM WheelEvents are ignored).
                setScrollReady()
                while (!scrollDone()) withFrameNanos { }
            },
            typeDriver = {
                // web-bench/run.mjs clicks into the text and types with trusted key events.
                setTypeReady()
                while (!typeDone()) withFrameNanos { }
            },
            onPhase = ::phase,
        )
    }
}

private fun phase(name: String) { js("if (window.__coldPhase) window.__coldPhase(name)") }

private fun publishBench(json: String) { js("window.__editorBench = JSON.parse(json)") }

private fun setScrollReady() { js("window.__editorScrollReady = true") }

private fun scrollDone(): Boolean = js("window.__editorScrollDone === true")

private fun setTypeReady() { js("window.__editorTypeReady = true") }

private fun typeDone(): Boolean = js("window.__editorTypeDone === true")
