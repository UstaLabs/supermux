// `JsAny`/`js(...)` interop is still behind the wasm opt-in in Kotlin 2.3.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.supermux.web.seams

import dev.supermux.terminal.compose.TerminalClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private fun nowMsJs(): Double = js("Date.now()")

/**
 * A document-level, capture-phase `paste` listener, installed once. While [isArmed] says the
 * terminal just asked for a paste, it takes the event's plain text, hands it to [onText] and
 * PREVENTS the default — otherwise the browser would also insert the text into Compose's hidden
 * input element, and the terminal's IME field would send it a second time as typed keys (newlines
 * as Enter, outside bracketed paste). Unarmed, it does nothing at all, so every other text field
 * pastes as it always did.
 */
@Suppress("UNUSED_PARAMETER")
private fun installTerminalPasteListenerJs(isArmed: () -> Boolean, onText: (String) -> Unit): Unit = js(
    """{
      if (window.__smxTerminalPasteHook) return;
      window.__smxTerminalPasteHook = true;
      document.addEventListener('paste', function (e) {
        if (!isArmed()) return;
        var dt = e.clipboardData;
        var text = dt ? dt.getData('text/plain') : '';
        e.preventDefault();
        onText(text || '');
      }, true);
    }""",
)

/** `navigator.clipboard.readText()`, for a paste that has no `paste` event (a menu, the key bar). */
@Suppress("UNUSED_PARAMETER")
private fun readClipboardTextJs(onDone: (String?) -> Unit): Unit = js(
    """{
      var p;
      try { p = navigator.clipboard.readText(); } catch (e) { onDone(null); return; }
      p.then(function (t) { onDone(t); }, function () { onDone(null); });
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun writeClipboardTextJs(text: String): Unit =
    js("{ if (navigator.clipboard) navigator.clipboard.writeText(text).catch(function () {}); }")

/**
 * The terminal's clipboard in the browser.
 *
 * **Reading.** A page may read clipboard text in exactly one place that works in every browser:
 * the `paste` event of the user's own Cmd/Ctrl+V. [willRead] runs inside the terminal's key
 * handler, before the browser performs the paste, and arms the listener for [ARM_WINDOW_MS]; the
 * listener stashes the text and [read] collects it. The terminal's `read()` runs a dispatcher hop
 * later, which may be BEFORE the event has fired, so it waits up to [ARM_WINDOW_MS] for the stash.
 * No event (Paste in the menu or the key bar, where there is no Cmd+V) falls back to
 * `navigator.clipboard.readText()` — Chrome asks for permission once; Safari refuses it outside a
 * paste event, so there a keyboard paste is the one that works, exactly as for pasted images
 * ([WebClipboard]).
 *
 * **Writing** is `navigator.clipboard.writeText`, which a copy made from a key press or a click is
 * allowed to do.
 */
object WebTerminalClipboard : TerminalClipboard {
    private var armedAtMs = 0.0
    private var stash: String? = null
    private var installed = false

    /** Install the `paste` hook; it has to be listening before the user's first paste. Idempotent. */
    fun install() {
        if (installed) return
        installed = true
        installTerminalPasteListenerJs(
            isArmed = { nowMsJs() - armedAtMs <= ARM_WINDOW_MS },
            onText = { text ->
                stash = text
                armedAtMs = 0.0
            },
        )
    }

    override fun willRead() {
        install()
        stash = null
        armedAtMs = nowMsJs()
    }

    override suspend fun read(): String? {
        var waited = 0L
        while (stash == null && nowMsJs() - armedAtMs <= ARM_WINDOW_MS && waited <= ARM_WINDOW_MS) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        stash?.let { text ->
            stash = null
            return text
        }
        return suspendCancellableCoroutine { continuation ->
            readClipboardTextJs { text -> if (continuation.isActive) continuation.resume(text) }
        }
    }

    override suspend fun write(text: String) {
        writeClipboardTextJs(text)
    }

    /** How long after a paste request the `paste` event may still arrive, and still be claimed. */
    private const val ARM_WINDOW_MS = 400.0
    private const val POLL_MS = 10L
}
