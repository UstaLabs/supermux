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
    // This editor's own DOM state (its TEXTAREA once bound, the field it must show, composing, its
    // label): per editor, never a page global, so several editors (and plain inputs) share a page.
    val st = newWebInputState()
    controller.platformInput = st
    controller.fieldSync.platformAhead = { text -> domAhead(st, text) }
    val handle = installListeners(
        st = st,
        onKey = { e ->
            val facts = WebKeyFacts(aimedAtEditorField(e), eventKey(e), eventCode(e), eventFlags(e), lastPointerType(st), maxTouchPoints())
            webKeyPath(view, controller.composing, facts, keyboard)
        },
        onCopy = { cut -> webClipboardText(view, cut) },
        onPaste = { text -> if (!view.focused) false else { if (!view.readOnly) view.paste(text); true } },
        focused = { view.focused },
        onInsert = { start, end, data ->
            if (!view.focused || controller.composing) false
            else { controller.fieldSync.onDomInsert(start, end, data).let { u -> controller.fieldWriter?.invoke(u) }; true }
        },
        fieldText = { controller.fieldSync.current().text },
    )
    return {
        removeListeners(handle)
        if (controller.platformInput === st) controller.platformInput = null
        controller.fieldSync.platformAhead = null
    }
}

/**
 * This editor's TEXTAREA is composing, or holds text other than [text] (the field has not caught
 * up). Except right after a held caret move was released on its time limit (`st.trustCaret`): that
 * one caret report is the user's and is followed once.
 */
private fun domAhead(st: JsAny, text: String): Boolean = js(
    "(() => { const t = st.ta; if (!t || !t.isConnected || t.__editorState !== st) return false; if (st.trustCaret) { st.trustCaret = false; return false; } return st.composing || t.value !== text; })()"
)

/** The key-down is aimed at Compose's own text input: the TEXTAREA in the canvas's shadow root. */
private fun aimedAtEditorField(e: JsAny): Boolean = js(
    """(() => {
      const t = e.composedPath && e.composedPath()[0];
      if (!t || t.tagName !== 'TEXTAREA') return false;
      const root = t.getRootNode();
      return !!(root && root.querySelector && root.querySelector('canvas'));
    })()"""
)

private fun lastPointerType(st: JsAny): String = js("String(st.lastPointer || '')")

private fun maxTouchPoints(): Int = js("(navigator.maxTouchPoints || 0)")

/**
 * One editor's DOM state: `ta` its session's TEXTAREA (bound while it is focused: Compose's
 * backing TEXTAREA is the deep active element then), `field` what that TEXTAREA must show, and the
 * functions over them. The TEXTAREA's `value` setter is wrapped once per element and asks the
 * state of the editor that owns it now (`ta.__editorState`).
 */
private fun newWebInputState(): JsAny = js(
    """(() => {
      const proto = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
      const st = { ta: null, field: null, composing: false, trustCaret: false, label: '', lastPointer: '' };
      st.deepActive = () => {
        let a = document.activeElement;
        while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
        return a;
      };
      // Bind the focused editor's session TEXTAREA (called only while this editor has the focus).
      st.bind = () => {
        const a = st.deepActive();
        if (a && a.tagName === 'TEXTAREA') {
          const root = a.getRootNode();
          if (root && root.querySelector && root.querySelector('canvas')) { if (st.ta !== a) st.composing = false; st.ta = a; }
        }
        const ta = st.ta && st.ta.isConnected ? st.ta : null;
        if (!ta) return null;
        ta.__editorState = st;
        if (st.label) ta.setAttribute('aria-label', st.label);
        if (!ta.__editorHooked) {
          ta.__editorHooked = true;
          Object.defineProperty(ta, 'value', {
            configurable: true,
            get() { return proto.get.call(this); },
            set(v) {
              proto.set.call(this, v);
              const s = this.__editorState, f = s && s.field;
              if (f && f.text === v && !s.composing) this.setSelectionRange(f.start, f.end);
            },
          });
        }
        return ta;
      };
      st.resync = () => {
        const f = st.field, ta = st.bind();
        if (!f || !ta || st.composing) return;
        if (ta.value !== f.text) ta.value = f.text;
        if (ta.selectionStart !== f.start || ta.selectionEnd !== f.end) ta.setSelectionRange(f.start, f.end);
      };
      st.isMine = (e) => { const t = e.composedPath && e.composedPath()[0]; return !!t && t === st.ta; };
      return st;
    })()"""
)

private fun installListeners(st: JsAny, onKey: (JsAny) -> Int, onCopy: (Boolean) -> String?, onPaste: (String) -> Boolean, focused: () -> Boolean, onInsert: (Int, Int, String) -> Boolean, fieldText: () -> String): JsAny = js(
    """(() => {
      // A mouse press focuses the CANVAS by default, after Compose moved the focus to its TEXTAREA
      // for the editor's input session: give it back, or the next keys and IME text go nowhere.
      const refocus = (e) => {
        // Only a press on Compose's canvas: a click on another element of the page is its own.
        const path = e.composedPath ? e.composedPath() : [];
        if (!path.some((n) => n && n.tagName === 'CANVAS')) return;
        requestAnimationFrame(() => refocusNow());
      };
      const refocusNow = () => {
        if (!focused()) return;
        const ta = st.ta && st.ta.isConnected ? st.ta : null;
        if (!ta) { st.bind(); return; }
        const root = ta.getRootNode();
        if (root.activeElement !== ta) ta.focus({ preventScroll: true });
      };
      const key = (e) => {
        const r = onKey(e);
        if (r === 1) { e.preventDefault(); e.stopImmediatePropagation(); }
        else if (r === 2) { e.stopImmediatePropagation(); }
      };
      const pointer = (e) => { st.lastPointer = e.pointerType; };
      // Only this editor's own TEXTAREA, and only while it has the focus.
      const mine = (e) => focused() && st.isMine(e);
      const copy = (cut) => (e) => {
        if (!mine(e)) return;
        const text = onCopy(cut);
        if (text === null || text === undefined) return;
        if (e.clipboardData) e.clipboardData.setData('text/plain', text);
        e.preventDefault(); e.stopImmediatePropagation();
      };
      const onCopyEvent = copy(false), onCutEvent = copy(true);
      const paste = (e) => {
        if (!mine(e)) return;
        const text = e.clipboardData ? e.clipboardData.getData('text/plain') : '';
        if (onPaste(text)) { e.preventDefault(); e.stopImmediatePropagation(); }
      };
      // Plain text insertion (dictation, a soft keyboard's word, CDP's insertText) goes straight to
      // the editor, at the TEXTAREA's selection READ FIRST (a spell-check replacement selects the
      // word it replaces), and the browser never edits the TEXTAREA itself: left to the browser and
      // Compose, the edit raced Compose's selectionchange echo and landed off the caret. Anything
      // else (composition) is Compose's, after a resync of the TEXTAREA.
      const beforeInput = (e) => {
        if (!mine(e)) return;
        const ta = st.ta;
        const start = ta.selectionStart, end = ta.selectionEnd;
        const plain = !e.isComposing && !st.composing && e.data != null && (e.inputType === 'insertText' || e.inputType === 'insertReplacementText');
        if (plain && onInsert(start, end, e.data)) { e.preventDefault(); e.stopImmediatePropagation(); return; }
        if (e.inputType !== 'insertReplacementText') st.resync();
      };
      const cstart = (e) => { if (!mine(e)) return; st.resync(); st.composing = true; };
      // Compose web turns every document 'selectionchange' into a SetSelectionCommand from the
      // TEXTAREA's caret. After an IME edit the browser moves that caret (にk: 114 -> 115) BEFORE
      // Compose has processed the edit (it does at its next frame): taken first, the new caret was
      // applied over the OLD text and the edit then landed at it, one unit right (webInputTest
      // "IME composition lands at the caret", about 1 run in 30, a real IME the same). While the
      // TEXTAREA's value is ahead of the field Compose reported, the caret move is stale: Compose
      // never sees it, applies the edit at its own caret, and the caret is synced from that.
      // Held only until the editor has caught up (the TEXTAREA's value is the field's text again) or
      // for HOLD_FRAMES animation frames at most: then the TEXTAREA's caret, read again, is handed to
      // Compose ONCE (a synthetic selectionchange), so a caret the user moved meanwhile is never lost
      // for good. Counted in FRAMES, not milliseconds: Compose catches up at its next frame, so a long
      // task in between (which delays that frame) never lets a stale caret through.
      const selChange = () => {
        const ta = st.ta;
        if (!ta || !ta.isConnected || ta.__editorState !== st || st.deepActive() !== ta) return false;
        return ta.value !== fieldText();
      };
      const HOLD_FRAMES = 6;
      let held = false, frames = 0, releasing = false;
      const release = () => {
        if (!held) return;
        held = false;
        const ta = st.ta;
        if (!ta || !ta.isConnected || ta.__editorState !== st) return;
        // Still ahead (the frame limit): the editor must follow this one caret report anyway, and
        // only this one: the trust ends two frames on (Compose applies it at its next frame).
        if (ta.value !== fieldText()) {
          st.trustCaret = true;
          requestAnimationFrame(() => requestAnimationFrame(() => { st.trustCaret = false; }));
        }
        releasing = true;
        try { document.dispatchEvent(new Event('selectionchange')); } finally { releasing = false; }
      };
      const watch = () => {
        if (!held) return;
        frames++;
        if (!selChange() || frames >= HOLD_FRAMES) release();
        else requestAnimationFrame(watch);
      };
      const onSelChange = (e) => {
        if (releasing) return;
        if (!selChange()) { held = false; return; }
        if (!held) { held = true; frames = 0; requestAnimationFrame(watch); }
        e.stopImmediatePropagation();
      };
      window.addEventListener('selectionchange', onSelChange, true);
      const cend = (e) => { if (st.isMine(e)) st.composing = false; };
      // A compositionend that never came (the focus moved mid-composition) must not leave the
      // editor "composing", which ignores the field's caret moves: a blur of its TEXTAREA ends it.
      const blur = (e) => { if (st.isMine(e)) st.composing = false; };
      window.addEventListener('focusout', blur, true);
      window.addEventListener('compositionstart', cstart, true);
      window.addEventListener('compositionend', cend, true);
      window.addEventListener('beforeinput', beforeInput, true);
      window.addEventListener('pointerup', refocus, true);
      window.addEventListener('keydown', key, true);
      window.addEventListener('pointerdown', pointer, true);
      window.addEventListener('copy', onCopyEvent, true);
      window.addEventListener('cut', onCutEvent, true);
      window.addEventListener('paste', paste, true);
      return { key, pointer, onCopyEvent, onCutEvent, paste, cstart, cend, beforeInput, refocus, onSelChange, blur, stop: () => { held = false; } };
    })()"""
)

private fun removeListeners(h: JsAny) {
    js(
        """{ window.removeEventListener('keydown', h.key, true); window.removeEventListener('pointerdown', h.pointer, true);
         window.removeEventListener('copy', h.onCopyEvent, true); window.removeEventListener('cut', h.onCutEvent, true);
         window.removeEventListener('paste', h.paste, true);
         window.removeEventListener('compositionstart', h.cstart, true); window.removeEventListener('compositionend', h.cend, true);
         window.removeEventListener('beforeinput', h.beforeInput, true); window.removeEventListener('pointerup', h.refocus, true);
         window.removeEventListener('selectionchange', h.onSelChange, true); window.removeEventListener('focusout', h.blur, true); h.stop(); }"""
    )
}

private fun eventKey(e: JsAny): String = js("e.key || ''")

private fun eventCode(e: JsAny): String = js("e.code || ''")

private fun eventFlags(e: JsAny): Int =
    js("(e.ctrlKey ? 1 : 0) | (e.metaKey ? 2 : 0) | (e.altKey ? 4 : 0) | (e.shiftKey ? 8 : 0) | ((e.isComposing || e.keyCode === 229) ? 16 : 0)")

internal actual fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier = this

internal actual val platformTextToolbarPreferred: Boolean = false

internal actual val platformInputOnAnyFocus: Boolean = true

internal actual fun syncPlatformField(c: EditorController, f: FieldText) {
    // Only the focused editor owns the session TEXTAREA: another editor's programmatic edit never
    // touches it (nor any other text input on the page).
    val st = c.platformInput as? JsAny ?: return
    if (!c.view.focused) return
    syncDomTextArea(st, f.text, f.selStart, f.selEnd)
}

/**
 * The editor's session TEXTAREA as the field now is: its value and selection.
 *
 * Compose web (1.12, `DomInputStrategy.updateState`) writes the field's text into `textarea.value`
 * and sets the DOM selection only when the selection's NUMBERS changed; setting `value` puts the
 * DOM caret at the end, so after a re-window (new text, same caret offset) the DOM caret sat at the
 * end, the next `selectionchange` sent Compose that stale caret, and IME text and `insertText`
 * landed there (the device pass). So the TEXTAREA's `value` setter is wrapped to put the field's
 * selection back whenever the value written is the field's text, and the selection is applied here
 * and right before the browser composes. Never while it composes.
 */
private fun syncDomTextArea(st: JsAny, text: String, start: Int, end: Int) {
    js("{ st.field = { text: text, start: start, end: end }; st.resync(); }")
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

internal actual fun platformFieldLabel(c: EditorController, label: String) {
    val st = c.platformInput as? JsAny ?: return
    setLabel(st, label)
}

private fun setLabel(st: JsAny, label: String) {
    js("{ st.label = label; if (st.ta && st.ta.isConnected && st.ta.__editorState === st) st.ta.setAttribute('aria-label', label); }")
}

internal actual val platformClearsFieldSemantics: Boolean = true

@androidx.compose.runtime.Composable
internal actual fun rememberPlatformKeyboardShow(): (() -> Unit)? = null

internal actual fun platformClipboardHasText(): Boolean? = null

internal actual fun platformAfterKeyboardShown() {}

internal actual fun platformFocusChanged(c: EditorController, focused: Boolean) {
    val st = c.platformInput as? JsAny ?: return
    if (!focused) { unbind(st); return }
    // Compose creates and focuses the session TEXTAREA a frame or two after the focus change.
    val f = c.fieldSync.current()
    bindSoon(st, f.text, f.selStart, f.selEnd)
}

// Compose may reuse the element for another field's session (a widget's text field): it must not
// keep answering to this editor's state.
private fun unbind(st: JsAny) { js("{ if (st.ta && st.ta.__editorState === st) st.ta.__editorState = null; st.ta = null; st.composing = false; }") }

private fun bindSoon(st: JsAny, text: String, start: Int, end: Int) {
    // The field as it is now; a write in between (typing) replaces it before the resyncs run.
    js("{ st.field = { text: text, start: start, end: end }; requestAnimationFrame(() => { st.resync(); requestAnimationFrame(() => st.resync()); }); }")
}

internal actual fun platformCodeInputFocus(owner: Any, focused: Boolean) {}

internal actual fun detectTouchFirst(): Boolean = coarsePointer()

private fun coarsePointer(): Boolean = js("(typeof matchMedia === 'function') && matchMedia('(pointer: coarse)').matches")

internal actual fun webKeyboardInsetDp(): Float? = visualKeyboardInset().let { if (it < 0) null else it.toFloat() }

private fun visualKeyboardInset(): Double = js("(window.visualViewport ? Math.max(0, window.innerHeight - window.visualViewport.height - window.visualViewport.offsetTop) : -1)")

private object PageThread

/** The browser has one thread. */
internal actual fun currentThreadKey(): Any = PageThread
