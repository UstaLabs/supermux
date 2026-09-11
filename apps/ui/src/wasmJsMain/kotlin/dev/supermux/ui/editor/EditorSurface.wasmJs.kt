// `HtmlElementView` — Compose-for-Web's DOM interop, the web twin of `SwingPanel` — is still
// experimental in CMP 1.11.1.
@file:OptIn(ExperimentalComposeUiApi::class)

package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.widgets.KeepAlivePanel
import kotlinx.browser.document
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement

/**
 * An [EditorEngine] that lives in the DOM. The web host's engine (plan 3) implements this: it
 * receives a container `<div>` positioned by Compose and mounts the CodeMirror iframe inside it.
 * The same split desktop has with `SwingEditorEngine`: the shared surface never names the browser.
 */
interface DomEditorEngine : EditorEngine {
    /** Called once, with the container Compose positions over its canvas. */
    fun attach(container: HTMLElement)

    /** Called when the surface leaves the composition; [attach] may be called again later. */
    fun detach()
}

@Composable
actual fun EditorEngineHost(engine: EditorEngine, visible: Boolean, modifier: Modifier) {
    val dom = engine as? DomEditorEngine ?: return
    KeepAlivePanel(visible = visible) {
        HtmlElementView<HTMLDivElement>(
            modifier = modifier,
            factory = {
                (document.createElement("div") as HTMLDivElement).also { div ->
                    div.style.width = "100%"
                    div.style.height = "100%"
                    dom.attach(div)
                }
            },
            onRelease = { dom.detach() },
        )
    }
}
