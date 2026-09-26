package dev.supermux.editor.compose

private fun appleNavigator(): Boolean =
    js("/Mac|iPhone|iPad|iPod/.test((navigator.platform || '') + ' ' + (navigator.userAgent || ''))")

internal actual fun detectApplePlatform(): Boolean = appleNavigator()


/**
 * Compose for the web queues DOM input and handles it at the next animation frame, after that
 * frame has drawn: a keystroke typed through it is painted two frames later (measured: key event
 * to paint p50 29 ms, p95 37 ms). Plain printable keys are therefore typed here, in the DOM event
 * itself (capture phase, before Compose's own listener), and the event is cancelled so Compose
 * never types it again. Everything else ([fastTypeKey] says what) goes the ordinary way.
 */
internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? {
    val handler = addKeydownCapture { e ->
        fastTypeKey(
            view, controller.composing, eventKey(e), eventCode(e), eventFlags(e),
        )
    }
    return { removeKeydownCapture(handler) }
}

private fun addKeydownCapture(cb: (JsAny) -> Boolean): JsAny =
    js("{ const h = (e) => { if (cb(e)) { e.preventDefault(); e.stopImmediatePropagation(); } }; window.addEventListener('keydown', h, true); return h; }")

private fun removeKeydownCapture(h: JsAny) { js("window.removeEventListener('keydown', h, true)") }

private fun eventKey(e: JsAny): String = js("e.key || ''")

private fun eventCode(e: JsAny): String = js("e.code || ''")

private fun eventFlags(e: JsAny): Int =
    js("(e.ctrlKey ? 1 : 0) | (e.metaKey ? 2 : 0) | (e.altKey ? 4 : 0) | (e.shiftKey ? 8 : 0) | ((e.isComposing || e.keyCode === 229) ? 16 : 0)")
