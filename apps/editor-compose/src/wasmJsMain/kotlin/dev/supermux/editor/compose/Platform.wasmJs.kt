package dev.supermux.editor.compose

private fun appleNavigator(): Boolean =
    js("/Mac|iPhone|iPad|iPod/.test((navigator.platform || '') + ' ' + (navigator.userAgent || ''))")

internal actual fun detectApplePlatform(): Boolean = appleNavigator()


/**
 * Compose for the web queues DOM input and handles it at the next animation frame, after that
 * frame has drawn: a key handled through it is painted two frames later (measured: key event to
 * paint p50 29 ms, p95 37 ms). So, while an editor is composed, a capture-phase listener on the
 * window serves hardware keys inside the DOM event itself ([webKeyDown]: bound chords, plain
 * characters) and cancels them, so Compose never handles them again; Mod-c/x/v are hidden from
 * Compose and served by the browser's own copy/cut/paste events (synchronous clipboardData, the
 * whole selection, no permission prompt).
 *
 * Only for a HARDWARE keyboard, and only for keys aimed at Compose's own text input (the TEXTAREA
 * in its shadow root): a soft keyboard (iOS Safari's sends real key values) must keep going
 * through the field, or autocorrect and predictions break. The last pointer being a finger or a
 * pen, or a touch-capable device on which no physical-key keydown (a non-empty `code`) has been
 * seen yet, means "not a hardware keyboard". A heuristic by nature: documented, not certain.
 */
internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? {
    val handle = installListeners(
        onKey = { e -> if (!isHardwareKeyFor(e)) WebKey.PASS else webKeyDown(view, controller.composing, eventKey(e), eventCode(e), eventFlags(e)) },
        onCopy = { cut ->
            if (!view.focused) null else {
                val text = DefaultCommands.selectedText(view.state)
                if (text != null && cut) DefaultCommands.deleteSelection.run(view)
                text ?: ""
            }
        },
        onPaste = { text -> if (!view.focused) false else { if (!view.readOnly) view.paste(text); true } },
    )
    return { removeListeners(handle) }
}

private fun isHardwareKeyFor(e: JsAny): Boolean = js(
    """(() => {
      const t = e.composedPath && e.composedPath()[0];
      if (!t || t.tagName !== 'TEXTAREA') return false;
      const root = t.getRootNode();
      if (!root || !root.querySelector || !root.querySelector('canvas')) return false;
      if (e.code) window.__editorPhysicalKey = true;
      if (window.__editorLastPointer === 'touch' || window.__editorLastPointer === 'pen') return false;
      if (navigator.maxTouchPoints > 0 && !window.__editorPhysicalKey) return false;
      return true;
    })()"""
)

private fun installListeners(onKey: (JsAny) -> Int, onCopy: (Boolean) -> String?, onPaste: (String) -> Boolean): JsAny = js(
    """(() => {
      const key = (e) => {
        const r = onKey(e);
        if (r === 1) { e.preventDefault(); e.stopImmediatePropagation(); }
        else if (r === 2) { e.stopImmediatePropagation(); }
      };
      const pointer = (e) => { window.__editorLastPointer = e.pointerType; };
      const copy = (cut) => (e) => {
        const text = onCopy(cut);
        if (text === null || text === undefined) return;
        if (e.clipboardData) e.clipboardData.setData('text/plain', text);
        e.preventDefault(); e.stopImmediatePropagation();
      };
      const onCopyEvent = copy(false), onCutEvent = copy(true);
      const paste = (e) => {
        const text = e.clipboardData ? e.clipboardData.getData('text/plain') : '';
        if (onPaste(text)) { e.preventDefault(); e.stopImmediatePropagation(); }
      };
      window.addEventListener('keydown', key, true);
      window.addEventListener('pointerdown', pointer, true);
      window.addEventListener('copy', onCopyEvent, true);
      window.addEventListener('cut', onCutEvent, true);
      window.addEventListener('paste', paste, true);
      return { key, pointer, onCopyEvent, onCutEvent, paste };
    })()"""
)

private fun removeListeners(h: JsAny) {
    js(
        """{ window.removeEventListener('keydown', h.key, true); window.removeEventListener('pointerdown', h.pointer, true);
         window.removeEventListener('copy', h.onCopyEvent, true); window.removeEventListener('cut', h.onCutEvent, true);
         window.removeEventListener('paste', h.paste, true); }"""
    )
}

private fun eventKey(e: JsAny): String = js("e.key || ''")

private fun eventCode(e: JsAny): String = js("e.code || ''")

private fun eventFlags(e: JsAny): Int =
    js("(e.ctrlKey ? 1 : 0) | (e.metaKey ? 2 : 0) | (e.altKey ? 4 : 0) | (e.shiftKey ? 8 : 0) | ((e.isComposing || e.keyCode === 229) ? 16 : 0)")
