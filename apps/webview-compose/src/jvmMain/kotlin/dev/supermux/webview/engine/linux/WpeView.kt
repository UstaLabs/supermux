package dev.supermux.webview.engine.linux

import dev.supermux.webview.engine.frames.FrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the engine asks of its native web view: the glue's natives for one view. An interface, so tests drive the engine
 * without WebKit (Task 22's FakeWpeView). Main thread, except [saveState], which waits for the WebKit thread off main.
 * Its frames are a [FrameSource]'s: `pullFrame`, and `frameSeq`, the newest frame WebKit has delivered.
 */
internal interface WpeView : FrameSource {
    val id: Long
    fun configure(relay: String?, shim: String?, world: String, handler: String, pageScripts: Boolean, inspectable: Boolean, background: Int, userAgent: String?)
    /** Process-wide on WPE (W9). */
    fun setDark(dark: Boolean)
    fun loadUri(url: String, headers: Map<String, String>)
    fun loadHtml(html: String, baseUrl: String)
    fun navigate(what: Int)
    fun evaluate(request: Long, script: String, world: String?)
    fun callAsync(request: Long, body: String, world: String?, args: Map<String, String>)
    fun decide(decision: Long, verdict: Int)
    /** WebKit's session state (history and scroll), opaque; null if it has none. */
    suspend fun saveState(): ByteArray?
    /** Replaces the history with [state] and loads its current item; false if WebKit can't read it. A brief wait. */
    fun restoreState(state: ByteArray): Boolean
    fun setSize(width: Int, height: Int, scale: Double)
    fun setVisible(visible: Boolean)
    fun setFocus(focused: Boolean)
    fun pointer(type: Int, x: Double, y: Double, button: Int, modifiers: Int)
    fun scroll(dx: Double, dy: Double, x: Double, y: Double, modifiers: Int)
    fun key(down: Boolean, keyval: Int, keycode: Int, modifiers: Int)
    fun ime(kind: Int, text: String?, cursor: Int)
    fun frameMode(damageScale: Double)
    fun finishScheme(request: Long, status: Int, mime: String?, data: ByteArray?)
    fun clearData(request: Long)
    /** Answers the page's request [request] (a dialog, a permission, a file chooser): see `wv_client.c`. */
    fun answer(request: Long, allow: Boolean, text: String?, files: List<String>?)
    fun terminate()
    fun destroy()
}

internal class NativeWpeView(override val id: Long) : WpeView {
    override fun configure(relay: String?, shim: String?, world: String, handler: String, pageScripts: Boolean, inspectable: Boolean, background: Int, userAgent: String?) =
        WpeNative.configure(id, relay, shim, world, handler,
            (if (pageScripts) WpeNative.CONF_PAGE_SCRIPTS else 0) or (if (inspectable) WpeNative.CONF_INSPECTABLE else 0), background, userAgent)

    override fun setDark(dark: Boolean) = WpeNative.setDark(dark)

    override fun loadUri(url: String, headers: Map<String, String>) =
        WpeNative.load(id, WpeNative.LOAD_URI, url, null, headers.flatMap { (k, v) -> listOf(k, v) }.toTypedArray().takeIf { it.isNotEmpty() })

    override fun loadHtml(html: String, baseUrl: String) = WpeNative.load(id, WpeNative.LOAD_HTML, html, baseUrl, null)
    override fun navigate(what: Int) = WpeNative.navigate(id, what)
    override fun evaluate(request: Long, script: String, world: String?) = WpeNative.evaluate(id, request.toInt(), script, world)

    override fun callAsync(request: Long, body: String, world: String?, args: Map<String, String>) =
        WpeNative.callAsync(id, request.toInt(), body, world, args.flatMap { (k, v) -> listOf(k, v) }.toTypedArray())

    override fun decide(decision: Long, verdict: Int) = WpeNative.decide(id, decision, verdict)
    override suspend fun saveState(): ByteArray? = withContext(Dispatchers.IO) { WpeNative.saveState(id) }
    override fun restoreState(state: ByteArray): Boolean = WpeNative.restoreState(id, state)
    override fun setSize(width: Int, height: Int, scale: Double) = WpeNative.setSize(id, width, height, scale)
    override fun setVisible(visible: Boolean) = WpeNative.setVisible(id, visible)
    override fun setFocus(focused: Boolean) = WpeNative.setFocus(id, focused)
    override fun pointer(type: Int, x: Double, y: Double, button: Int, modifiers: Int) = WpeNative.pointer(id, type, x, y, button, modifiers, 0, 0)
    override fun scroll(dx: Double, dy: Double, x: Double, y: Double, modifiers: Int) = WpeNative.scroll(id, dx, dy, x, y, false, modifiers, 0)
    override fun key(down: Boolean, keyval: Int, keycode: Int, modifiers: Int) = WpeNative.key(id, down, keyval, keycode, modifiers, 0)
    override fun ime(kind: Int, text: String?, cursor: Int) = WpeNative.ime(id, kind, text, cursor)
    override fun pullFrame(dst: Long, rowBytes: Int, width: Int, height: Int, full: Boolean, out: LongArray): Int =
        WpeNative.pullFrame(id, dst, rowBytes, width, height, full, out)
    override fun frameSeq(): Long = WpeNative.frameSeq(id)
    override fun frameMode(damageScale: Double) = WpeNative.frameMode(id, false, false, damageScale)
    override fun finishScheme(request: Long, status: Int, mime: String?, data: ByteArray?) = WpeNative.finishScheme(request, status, mime, data)
    override fun clearData(request: Long) = WpeNative.clearData(id, request)
    override fun answer(request: Long, allow: Boolean, text: String?, files: List<String>?) =
        WpeNative.answer(id, request, allow, text, files?.toTypedArray())
    override fun terminate() = WpeNative.terminate(id)
    override fun destroy() = WpeNative.destroyView(id)
}
