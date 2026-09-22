package dev.supermux.web.terminal

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toUint8Array
import org.w3c.dom.HTMLElement

/** The package's named `Terminal` export, reachable without spelling the module wrapper. */
typealias Terminal = Xterm.Terminal

/**
 * The slice of xterm.js 5.5 the web terminal uses.
 *
 * Shape note: the module is declared as an external OBJECT with the class nested inside, not as a
 * top-level `external class Terminal`. KGP's wasm interop compiles a `@JsModule` to a namespace
 * import (`import * as ns from '@xterm/xterm'`) and resolves a top-level class against
 * `ns.default` — xterm has no default export, so that shape dies at runtime with "…default is not
 * a constructor" (proved by XtermPredictionSinkTest before the wrapper went in). A nested member
 * resolves as `ns.Terminal`, which is the real named export. Same reason `:shared`'s `Pako` is
 * shaped this way. A [Terminal] typealias keeps call sites short.
 *
 * `write` takes `JsAny` rather than being overloaded: the pty feeds a `Uint8Array` (lossless — the
 * emulator does its own UTF-8 decoding, which matters for a multi-byte glyph split across two
 * websocket frames) while the prediction sink feeds escape STRINGS. Both are JsAny, and one
 * declaration keeps the external free of overload resolution.
 */
@JsModule("@xterm/xterm")
external object Xterm {
    class Terminal(options: JsAny? = definedExternally) : JsAny {
        fun open(parent: HTMLElement)
        fun write(data: JsAny)

        /** [callback] fires once the parser has consumed [data] — the only way to observe a write. */
        fun write(data: JsAny, callback: () -> Unit)
        fun loadAddon(addon: JsAny)
        fun focus()
        fun dispose()
        val cols: Int
        val rows: Int

        /** `buffer.active` is read through the [xtermCursorRow]/[xtermCursorCol]/[xtermReadCell]
         *  helpers rather than typed here — the nested `IBuffer`/`IBufferLine`/`IBufferCell` chain
         *  would be three more external classes for three property reads. */
        val buffer: JsAny
        val element: HTMLElement?
    }
}

/**
 * Terminal options, built in JS: an options bag is a plain object literal, and spelling it as an
 * external interface would buy nothing. `allowProposedApi` is what lets the WebGL addon load.
 */
internal fun terminalOptions(background: String, foreground: String, fontSize: Int): JsAny = js(
    """({
        allowProposedApi: true,
        convertEol: false,
        cursorBlink: true,
        fontSize: fontSize,
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace',
        scrollback: 5000,
        theme: { background: background, foreground: foreground }
    })"""
)

/** Keystrokes (and pasted text) the emulator produced. One registration per terminal. */
internal fun xtermOnData(term: Terminal, cb: (String) -> Unit): Unit = js("term.onData(cb)")

/** Grid geometry after a reflow — the surface is the only thing that resizes the remote pty. */
internal fun xtermOnResize(term: Terminal, cb: (Int, Int) -> Unit): Unit =
    js("term.onResize(function (d) { cb(d.cols, d.rows); })")

/** Caret row, viewport-relative and 0-based (the [dev.supermux.net.CursorPos] convention). */
internal fun xtermCursorRow(term: Terminal): Int = js("term.buffer.active.cursorY")

/** Caret column, viewport-relative and 0-based. */
internal fun xtermCursorCol(term: Terminal): Int = js("term.buffer.active.cursorX")

/**
 * The character in a viewport-relative cell, or a space.
 *
 * `getLine()` takes an ABSOLUTE buffer index, so the viewport row is offset by `baseY` — the same
 * correction the old `xterm-adapter.ts` made.
 */
internal fun xtermReadCell(term: Terminal, row: Int, col: Int): String = js(
    """{
        var b = term.buffer.active;
        var line = b.getLine(b.baseY + row);
        var cell = line ? line.getCell(col) : null;
        var s = cell ? cell.getChars() : '';
        return s === '' ? ' ' : s;
    }"""
)

/** Pty bytes → the emulator's parser. UTF-8 decoding is xterm's job, not ours. */
internal fun ByteArray.toJsBytes(): Uint8Array = asUByteArray().toUint8Array()

/** `ResizeObserver` has no kotlinx-browser binding; the handle is opaque and only disconnected. */
internal fun observeResize(el: HTMLElement, cb: () -> Unit): JsAny =
    js("{ var ro = new ResizeObserver(function () { cb(); }); ro.observe(el); return ro; }")

internal fun disconnectResizeObserver(observer: JsAny): Unit = js("observer.disconnect()")
