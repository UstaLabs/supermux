package dev.supermux.webview.engine.windows

import java.util.concurrent.atomic.AtomicLong

/** A WinPage that records what the engine asks, and hands over frames a test says WGC captured (FakeWpeView's way). */
internal class FakeWinPage(override val token: Long = next.getAndIncrement()) : WinPage {
    val calls = mutableListOf<String>()
    val scripts = mutableListOf<Triple<Int, String, String>>()          // epoch, secret, script
    val navigations = mutableListOf<Triple<Int, String, String?>>()     // epoch, url, headers
    val executed = mutableListOf<Pair<Long, String>>()                  // request, script
    val devtools = mutableListOf<Triple<Long, String, String>>()        // request, method, params
    val answers = mutableMapOf<Long, Pair<Int, String?>>()
    val responses = mutableMapOf<Long, Triple<Int, String?, ByteArray?>>()
    var configured: Triple<Int, Int, String?>? = null
    var served: Triple<String?, ByteArray?, String?>? = null
    val placed = mutableListOf<List<Any>>()
    var visible: Boolean? = null
    var memoryLow: Boolean? = null
    var released = false

    /** The newest frame WGC "captured": width, height, sequence; a pull writes [fill] (0xAARRGGBB) over it, if set. */
    var frame: Triple<Int, Int, Long>? = null
    var fill: Int? = null
    val pulls = mutableListOf<Boolean>()
    private var pulledSeq = -1L

    val epoch: Int get() = scripts.last().first
    val secret: String get() = scripts.last().second

    /** The request of the last DevTools call to [method]. */
    fun devtoolsRequest(method: String): Long = devtools.last { it.second == method }.first

    override fun configure(flags: Int, background: Int, userAgentSuffix: String?) {
        configured = Triple(flags, background, userAgentSuffix)
        calls += "configure $flags"
    }
    override fun setScript(epoch: Int, secret: String, script: String) {
        scripts += Triple(epoch, secret, script)
        calls += "setScript $epoch"
    }
    override fun navigate(epoch: Int, url: String, headers: String?) {
        navigations += Triple(epoch, url, headers)
        calls += "navigate $epoch $url"
    }
    override fun go(epoch: Int, what: Int) { calls += "go $epoch $what" }
    override fun execute(request: Long, script: String) { executed += request to script }
    override fun devtools(request: Long, method: String, params: String) {
        devtools += Triple(request, method, params)
        calls += "devtools $method"
    }
    override fun subscribe(event: String) { calls += "subscribe $event" }
    override fun openDevTools() { calls += "openDevTools" }
    override fun clearData(request: Long) { calls += "clearData $request" }
    override fun serve(host: String?, html: ByteArray?, floor: String?) {
        served = Triple(host, html, floor)
        calls += "serve $host"
    }
    override fun respond(request: Long, status: Int, headers: String?, body: ByteArray?) { responses[request] = Triple(status, headers, body) }
    override fun answer(question: Long, verdict: Int, text: String?) { answers[question] = verdict to text }
    override fun place(frame: Long, canvas: Long, x: Int, y: Int, width: Int, height: Int, scale: Double) {
        placed += listOf(frame, canvas, x, y, width, height, scale)
    }
    override fun unplace() { calls += "unplace" }
    override fun parentMoved() { calls += "parentMoved" }
    override fun setVisible(visible: Boolean) {
        this.visible = visible
        calls += "setVisible $visible"
    }
    override fun setMemoryTarget(low: Boolean) { memoryLow = low }
    override fun mouse(kind: Int, keys: Int, data: Int, x: Int, y: Int) { calls += "mouse $kind $keys $data $x $y" }
    override fun moveFocus(reason: Int) { calls += "moveFocus $reason" }
    override fun release() {
        released = true
        calls += "release"
    }
    override fun frameSeq(): Long = frame?.third ?: -1L
    override fun pullFrame(dst: Long, rowBytes: Int, width: Int, height: Int, full: Boolean, out: LongArray): Int {
        pulls += full
        val (w, h, seq) = frame ?: return 0
        out.fill(0)
        out[0] = seq
        out[1] = w.toLong()
        out[2] = h.toLong()
        if (pulledSeq == seq && !full) return 0
        if (width != w || height != h) return -1
        pulledSeq = seq
        fill?.let { color -> for (y in 0 until h) for (x in 0 until w) unsafe.putInt(dst + y.toLong() * rowBytes + x * 4L, color) }
        return 1
    }

    private companion object {
        val next = AtomicLong(1_000)
        val unsafe: sun.misc.Unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
    }
}
