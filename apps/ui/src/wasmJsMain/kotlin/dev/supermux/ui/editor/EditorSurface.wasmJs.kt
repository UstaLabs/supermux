// `HtmlElementView` — Compose-for-Web's DOM interop, the web twin of `SwingPanel` — is still
// experimental in CMP 1.11.1.
@file:OptIn(ExperimentalComposeUiApi::class)

package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
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

/** A plain (non-state) flag: `update` must not invalidate the composition merely to record that
 *  the engine has already been attached. */
private class AttachState {
    var attached = false
}

/**
 * Hosts the engine's DOM container over the Compose canvas.
 *
 * [DomEditorEngine.attach] fires from `update`, not `factory`, and only once the element is in the
 * document at a non-zero size — the same trap the desktop actual documents for JCEF: an iframe
 * mounted into a detached or 0×0 container can sit there loading forever.
 *
 * `update` runs once at attach time — BEFORE the wrapper has been sized — and thereafter only when
 * a snapshot state it reads changes, so `sized` (written from `onGloballyPositioned`) is what
 * brings it back for the real attach. A plain flag would leave the engine never attached.
 *
 * `visible = false` keeps the element (and the engine's document) alive and merely hides it, which
 * [KeepAlivePanel] reinforces by laying the pane out at 0×0.
 */
@Composable
actual fun EditorEngineHost(engine: EditorEngine, visible: Boolean, modifier: Modifier) {
    val dom = engine as? DomEditorEngine ?: return
    val state = remember(dom) { AttachState() }
    var sized by remember(dom) { mutableStateOf(false) }
    KeepAlivePanel(visible = visible) {
        HtmlElementView<HTMLDivElement>(
            modifier = modifier.onGloballyPositioned { sized = it.size.width > 0 },
            factory = {
                (document.createElement("div") as HTMLDivElement).also { div ->
                    div.style.width = "100%"
                    div.style.height = "100%"
                    // `overflow` has no typed accessor in kotlinx-browser's CSSStyleDeclaration.
                    div.style.setProperty("overflow", "hidden")
                }
            },
            update = {
                it.style.visibility = if (visible) "visible" else "hidden"
                if (sized && !state.attached && it.isConnected && it.clientWidth > 0) {
                    state.attached = true
                    dom.attach(it)
                }
            },
            onRelease = {
                state.attached = false
                dom.detach()
            },
        )
    }
}
