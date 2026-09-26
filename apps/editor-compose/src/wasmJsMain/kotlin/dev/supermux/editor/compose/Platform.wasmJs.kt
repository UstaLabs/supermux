package dev.supermux.editor.compose

import kotlinx.coroutines.await

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
 * seen yet, means "not a hardware keyboard" ([isHardwareKey]; a heuristic by nature, so
 * [EditorView.webKeyboard] can force it, and [EditorView.onKeyPath] reports each key's path).
 */
internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? {
    val keyboard = WebKeyboardState()
    val handle = installListeners(
        onKey = { e ->
            val facts = WebKeyFacts(aimedAtEditorField(e), eventKey(e), eventCode(e), eventFlags(e), lastPointerType(), maxTouchPoints())
            webKeyPath(view, controller.composing, facts, keyboard)
        },
        onCopy = { cut -> webClipboardText(view, cut) },
        onPaste = { text -> if (!view.focused) false else { if (!view.readOnly) view.paste(text); true } },
        focused = { view.focused },
        onInsert = { start, end, data ->
            if (!view.focused || controller.composing) false
            else { controller.fieldSync.onDomInsert(start, end, data).let { u -> controller.fieldWriter?.invoke(u) }; true }
        },
    )
    return { removeListeners(handle) }
}

/** The key-down is aimed at Compose's own text input: the TEXTAREA in the canvas's shadow root. */
private fun aimedAtEditorField(e: JsAny): Boolean = js(
    """(() => {
      const t = e.composedPath && e.composedPath()[0];
      if (!t || t.tagName !== 'TEXTAREA') return false;
      const root = t.getRootNode();
      return !!(root && root.querySelector && root.querySelector('canvas'));
    })()"""
)

private fun lastPointerType(): String = js("String(window.__editorLastPointer || '')")

private fun maxTouchPoints(): Int = js("(navigator.maxTouchPoints || 0)")

private fun installListeners(onKey: (JsAny) -> Int, onCopy: (Boolean) -> String?, onPaste: (String) -> Boolean, focused: () -> Boolean, onInsert: (Int, Int, String) -> Boolean): JsAny = js(
    """(() => {
      // A mouse press focuses the CANVAS by default, after Compose moved the focus to its TEXTAREA
      // for the editor's input session: give it back, or the next keys and IME text go nowhere.
      const refocus = () => requestAnimationFrame(() => {
        if (!focused()) return;
        const ta = window.__editorFindTextArea && window.__editorFindTextArea();
        if (!ta) return;
        const root = ta.getRootNode();
        if (root.activeElement !== ta) ta.focus({ preventScroll: true });
      });
      // Right before the browser inserts text (IME, dictation, insertText) the TEXTAREA must hold the
      // editor's window and caret, and Compose's own model of it must too: it follows the DOM
      // selection through selectionchange, which may not have fired yet for our last change.
      const resync = () => { if (window.__editorResync) window.__editorResync(); };
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
      // Plain text insertion (dictation, a soft keyboard's word, CDP's insertText) goes straight to
      // the editor, at the TEXTAREA's selection, and the browser never edits the TEXTAREA itself:
      // left to the browser and Compose, the edit raced Compose's selectionchange echo and landed
      // off the caret. Composition (insertCompositionText) stays Compose's.
      const beforeInput = (e) => {
        resync();
        if (e.isComposing || window.__editorComposing) return;
        if (e.inputType !== 'insertText' && e.inputType !== 'insertReplacementText') return;
        const ta = window.__editorFindTextArea && window.__editorFindTextArea();
        const t = e.composedPath && e.composedPath()[0];
        if (!ta || t !== ta || e.data == null) return;
        if (onInsert(ta.selectionStart, ta.selectionEnd, e.data)) { e.preventDefault(); e.stopImmediatePropagation(); }
      };
      const cstart = () => { resync(); window.__editorComposing = true; };
      const cend = () => { window.__editorComposing = false; };
      window.addEventListener('compositionstart', cstart, true);
      window.addEventListener('compositionend', cend, true);
      window.addEventListener('beforeinput', beforeInput, true);
      window.addEventListener('pointerup', refocus, true);
      window.addEventListener('keydown', key, true);
      window.addEventListener('pointerdown', pointer, true);
      window.addEventListener('copy', onCopyEvent, true);
      window.addEventListener('cut', onCutEvent, true);
      window.addEventListener('paste', paste, true);
      return { key, pointer, onCopyEvent, onCutEvent, paste, cstart, cend, beforeInput, refocus };
    })()"""
)

private fun removeListeners(h: JsAny) {
    js(
        """{ window.removeEventListener('keydown', h.key, true); window.removeEventListener('pointerdown', h.pointer, true);
         window.removeEventListener('copy', h.onCopyEvent, true); window.removeEventListener('cut', h.onCutEvent, true);
         window.removeEventListener('paste', h.paste, true);
         window.removeEventListener('compositionstart', h.cstart, true); window.removeEventListener('compositionend', h.cend, true);
         window.removeEventListener('beforeinput', h.beforeInput, true); window.removeEventListener('pointerup', h.refocus, true); }"""
    )
}

private fun eventKey(e: JsAny): String = js("e.key || ''")

private fun eventCode(e: JsAny): String = js("e.code || ''")

private fun eventFlags(e: JsAny): Int =
    js("(e.ctrlKey ? 1 : 0) | (e.metaKey ? 2 : 0) | (e.altKey ? 4 : 0) | (e.shiftKey ? 8 : 0) | ((e.isComposing || e.keyCode === 229) ? 16 : 0)")

internal actual fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier = this

internal actual val platformTextToolbarPreferred: Boolean = false

internal actual val platformInputOnAnyFocus: Boolean = true

internal actual fun syncPlatformField(f: FieldText) = syncDomTextArea(f.text, f.selStart, f.selEnd)

/**
 * Compose's TEXTAREA (in the canvas's shadow root) as the field now is: its value and selection.
 *
 * Compose web (1.12, `DomInputStrategy.updateState`) writes the field's text into `textarea.value`
 * and sets the DOM selection only when the selection's NUMBERS changed; setting `value` puts the
 * DOM caret at the end, so after a re-window (new text, same caret offset) the DOM caret sat at the
 * end, the next `selectionchange` sent Compose that stale caret, and IME text and `insertText`
 * landed there (the device pass). So the TEXTAREA's `value` setter is wrapped to put the field's
 * selection back whenever the value written is the field's text, and the selection is applied here
 * and right before the browser inserts text (`beforeinput`, `compositionstart`). Never while the
 * browser composes.
 */
private fun syncDomTextArea(text: String, start: Int, end: Int) {
    js(
        """{
      if (!window.__editorFindTextArea) {
        window.__editorFindTextArea = () => {
          const cached = window.__editorTextArea;
          if (cached && cached.isConnected) return cached;
          const roots = [document];
          for (let i = 0; i < roots.length; i++) {
            for (const el of roots[i].querySelectorAll('*')) if (el.shadowRoot) roots.push(el.shadowRoot);
            const ta = roots[i].querySelector('textarea');
            if (ta) return (window.__editorTextArea = ta);
          }
          return null;
        };
        const proto = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
        window.__editorHook = (ta) => {
          if (ta.__editorHooked) return;
          ta.__editorHooked = true;
          // The editor's one text box for a screen reader (see platformSurfaceText).
          if (window.__editorLabel) ta.setAttribute('aria-label', window.__editorLabel);
          Object.defineProperty(ta, 'value', {
            configurable: true,
            get() { return proto.get.call(this); },
            set(v) {
              proto.set.call(this, v);
              const f = window.__editorField;
              if (f && f.text === v && !window.__editorComposing) this.setSelectionRange(f.start, f.end);
            },
          });
        };
        window.__editorResync = () => {
          const f = window.__editorField, ta = window.__editorFindTextArea();
          if (!f || !ta || window.__editorComposing) return;
          window.__editorHook(ta);
          if (ta.value !== f.text) ta.value = f.text;
          if (ta.selectionStart !== f.start || ta.selectionEnd !== f.end) ta.setSelectionRange(f.start, f.end);
        };
      }
      window.__editorField = { text: text, start: start, end: end };
      window.__editorResync();
    }"""
    )
}

/**
 * The web: Compose's ClipboardManager reads nothing in a browser, so the menu's Copy/Cut/Paste use
 * the async Clipboard API (a permission prompt on the first paste; outside a secure context it is
 * absent and Paste pastes nothing). The keys never come here: Mod-c/x/v are served by the
 * browser's own clipboard events (see [installFastTyping]).
 */
internal actual fun platformEditorClipboard(compose: EditorClipboard): EditorClipboard = WebClipboard

private object WebClipboard : EditorClipboard {
    override fun write(text: String) = writeClipboard(text)
    override suspend fun read(): String? = readClipboard().await<JsString?>()?.toString()?.takeIf { it.isNotEmpty() }
}

private fun writeClipboard(text: String) {
    js("{ if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).catch(() => {}); }")
}

private fun readClipboard(): kotlin.js.Promise<JsString?> =
    js("(navigator.clipboard && navigator.clipboard.readText) ? navigator.clipboard.readText().catch(() => null) : Promise.resolve(null)")

internal actual val platformSurfaceText: Boolean = false

internal actual fun platformFieldLabel(label: String) {
    js("{ window.__editorLabel = label; const ta = window.__editorFindTextArea && window.__editorFindTextArea(); if (ta) ta.setAttribute('aria-label', label); }")
}

internal actual val platformClearsFieldSemantics: Boolean = true

@androidx.compose.runtime.Composable
internal actual fun rememberPlatformKeyboardShow(): (() -> Unit)? = null
