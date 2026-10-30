package dev.supermux.webview.engine.windows

import dev.supermux.webview.engine.frames.FrameSource

/**
 * What the engine asks of its WebView2 page: the glue's natives for one page (WinGlue's KDoc says on which thread each
 * runs). An interface, so the engine's tests run on FakeWinPage. Every call returns at once; answers come back as
 * WinEvents under the request, question or epoch they name.
 */
internal interface WinPage : FrameSource {
    val token: Long
    fun configure(flags: Int, background: Int, userAgentSuffix: String?)
    fun setScript(epoch: Int, secret: String, script: String)
    fun navigate(epoch: Int, url: String, headers: String?)
    fun go(epoch: Int, what: Int)

    /**
     * `ExecuteScript`. Never used by the engine: WebView2 runs it as a user gesture, so every host script goes through
     * [devtools]' `Runtime.evaluate` with `userGesture: false` instead (`WindowsWebEngine.runScript`).
     */
    fun execute(request: Long, script: String)
    fun devtools(request: Long, method: String, params: String)
    fun subscribe(event: String)
    fun openDevTools()
    fun clearData(request: Long)
    fun serve(host: String?, html: ByteArray?, floor: String?)
    fun respond(request: Long, status: Int, headers: String?, body: ByteArray?)
    fun answer(question: Long, verdict: Int, text: String?)
    fun place(frame: Long, canvas: Long, x: Int, y: Int, width: Int, height: Int, scale: Double)
    fun unplace()
    fun parentMoved()
    fun setVisible(visible: Boolean)
    fun setMemoryTarget(low: Boolean)
    fun mouse(kind: Int, keys: Int, data: Int, x: Int, y: Int)
    fun moveFocus(reason: Int)
    fun release()
}

internal class NativeWinPage(override val token: Long) : WinPage {
    override fun configure(flags: Int, background: Int, userAgentSuffix: String?) = WinGlue.configure(token, flags, background, userAgentSuffix)
    override fun setScript(epoch: Int, secret: String, script: String) = WinGlue.setScript(token, epoch, secret, script)
    override fun navigate(epoch: Int, url: String, headers: String?) = WinGlue.navigate(token, epoch, url, headers)
    override fun go(epoch: Int, what: Int) = WinGlue.go(token, epoch, what)
    override fun execute(request: Long, script: String) = WinGlue.execute(token, request, script)
    override fun devtools(request: Long, method: String, params: String) = WinGlue.devtools(token, request, method, params)
    override fun subscribe(event: String) = WinGlue.subscribe(token, event)
    override fun openDevTools() = WinGlue.openDevTools(token)
    override fun clearData(request: Long) = WinGlue.clearData(token, request)
    override fun serve(host: String?, html: ByteArray?, floor: String?) = WinGlue.serve(token, host, html, floor)
    override fun respond(request: Long, status: Int, headers: String?, body: ByteArray?) = WinGlue.respond(token, request, status, headers, body)
    override fun answer(question: Long, verdict: Int, text: String?) = WinGlue.answer(token, question, verdict, text)
    override fun place(frame: Long, canvas: Long, x: Int, y: Int, width: Int, height: Int, scale: Double) =
        WinGlue.place(token, frame, canvas, x, y, width, height, scale)
    override fun unplace() = WinGlue.unplace(token)
    override fun parentMoved() = WinGlue.parentMoved(token)
    override fun setVisible(visible: Boolean) = WinGlue.setVisible(token, visible)
    override fun setMemoryTarget(low: Boolean) = WinGlue.setMemoryTarget(token, low)
    override fun mouse(kind: Int, keys: Int, data: Int, x: Int, y: Int) = WinGlue.mouse(token, kind, keys, data, x, y)
    override fun moveFocus(reason: Int) = WinGlue.moveFocus(token, reason)
    override fun release() = WinGlue.release(token)
    override fun frameSeq(): Long = WinGlue.frameSeq(token)
    override fun pullFrame(dst: Long, rowBytes: Int, width: Int, height: Int, full: Boolean, out: LongArray): Int =
        WinGlue.pullFrame(token, dst, rowBytes, width, height, full, out)

    /** The glue puts the copied frame's composition (WGC's SystemRelativeTime, QPC nanoseconds) at `out[9]`. */
    override fun composedNs(out: LongArray): Long = out.getOrElse(9) { -1L }
}
