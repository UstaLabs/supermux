package dev.supermux.editor.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    if (params.contains("two=1")) { twoEditorsPage(); return }
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
            onView = { v ->
                // Test hooks (web-bench/input-check.mjs): the document, the main selection, the file.
                publishHooks(
                    doc = { v.state.doc.toString() },
                    sel = { v.state.selection.main.let { "${it.anchor},${it.head}" } },
                    open = { name -> SampleFile.entries.firstOrNull { it.name == name }?.let { sampleFileOpener?.invoke(it) }; Unit },
                )
            },
        )
    }
}

private fun phase(name: String) { js("if (window.__coldPhase) window.__coldPhase(name)") }

private fun publishBench(json: String) { js("window.__editorBench = JSON.parse(json)") }

private fun setScrollReady() { js("window.__editorScrollReady = true") }

private fun scrollDone(): Boolean = js("window.__editorScrollDone === true")

private fun setTypeReady() { js("window.__editorTypeReady = true") }

private fun typeDone(): Boolean = js("window.__editorTypeDone === true")

private fun publishHooks(doc: () -> String, sel: () -> String, open: (String) -> Unit) {
    js("{ window.__editorDoc = doc; window.__editorSel = sel; window.__editorOpen = open; }")
}

/**
 * `?two=1`: two editors on one page and a plain DOM `<input>` (web-bench/run.mjs `two`): typing
 * and IME land in the focused editor, and an unfocused editor never touches another text input.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun twoEditorsPage() {
    val views = listOf(
        dev.supermux.editor.compose.EditorView(dev.supermux.editor.core.EditorState.create("alpha one\nalpha two")),
        dev.supermux.editor.compose.EditorView(dev.supermux.editor.core.EditorState.create("beta one\nbeta two")),
    )
    publishTwoHooks(
        doc = { i -> views[i].state.doc.toString() },
        edit = { i, text -> views[i].dispatch(dev.supermux.editor.core.TransactionSpec(changes = listOf(dev.supermux.editor.core.ChangeSpec(0, 0, text)))); Unit },
    )
    ComposeViewport(document.body!!) {
        val font = dev.supermux.editor.compose.packagedEditorFontFamily()
        val theme = androidx.compose.runtime.remember(font) { dev.supermux.editor.compose.EditorTheme.dark(font) }
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
            for ((i, v) in views.withIndex()) {
                dev.supermux.editor.compose.Editor(
                    v,
                    androidx.compose.ui.Modifier.weight(1f).fillMaxWidth(),
                    theme = theme,
                    label = "Editor ${i + 1}",
                )
            }
        }
    }
    // After the viewport (it takes the body over): a plain text input of the page's own.
    addPlainInput()
}

private fun publishTwoHooks(doc: (Int) -> String, edit: (Int, String) -> Unit) {
    js("{ window.__twoDoc = doc; window.__twoEdit = edit; }")
}

private fun addPlainInput() {
    js("{ const add = () => { if (document.getElementById('plain')) return; const i = document.createElement('input'); i.id = 'plain'; i.style.cssText = 'position:fixed;right:8px;top:8px;z-index:2147483647;width:120px'; document.documentElement.appendChild(i); }; add(); setTimeout(add, 1000); }")
}
