package dev.supermux.webview.engine.windows

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** CDP's answer to `Runtime.evaluate("1")`: what a page that answers says. */
internal const val EVALUATED_ONE: String = "{\"result\":{\"type\":\"number\",\"value\":1,\"description\":\"1\"}}"

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

    /**
     * The newest frame WGC "captured": width, height, sequence; a pull writes [pattern]'s pixels over it, else [fill]
     * (0xAARRGGBB): an opaque grey unless a test says, as a page always paints something (a frame of nothing is the
     * engine's to refuse).
     */
    var frame: Triple<Int, Int, Long>? = null
    var fill: Int? = 0xFF808080.toInt()
    var pattern: ((x: Int, y: Int) -> Int)? = null

    /** When DWM composed [frame] (WGC's SystemRelativeTime, on System.nanoTime's clock); -1 unknown. */
    var composed: Long = -1L
    val pulls = mutableListOf<Boolean>()
    private var pulledSeq = -1L

    val epoch: Int get() = scripts.last().first
    val secret: String get() = scripts.last().second

    /** The request of the last DevTools call to [method]. */
    fun devtoolsRequest(method: String): Long = devtools.last { it.second == method }.first

    /**
     * The host's scripts as the engine runs them, CDP's `Runtime.evaluate`: request, expression, and the `userGesture`
     * it asked for (null if it didn't say). [executed] is `ExecuteScript`'s path, which runs as the user's gesture.
     */
    val evaluated: List<Triple<Long, String, Boolean?>>
        get() = devtools.filter { it.second == "Runtime.evaluate" }.map { (request, _, params) ->
            val json = Json.parseToJsonElement(params).jsonObject
            Triple(request, json.getValue("expression").jsonPrimitive.content, json["userGesture"]?.jsonPrimitive?.booleanOrNull)
        }

    /** The request of the last script the engine ran whose expression is [expression]. */
    fun evaluatedRequest(expression: String): Long = evaluated.last { it.second == expression }.first

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
        if (out.size > COMPOSED) out[COMPOSED] = composed
        if (pulledSeq == seq && !full) return 0
        if (width != w || height != h) return -1
        pulledSeq = seq
        val paint = pattern ?: fill?.let { color -> { _: Int, _: Int -> color } }
        paint?.let { for (y in 0 until h) for (x in 0 until w) unsafe.putInt(dst + y.toLong() * rowBytes + x * 4L, it(x, y)) }
        return 1
    }

    override fun composedNs(out: LongArray): Long = out.getOrElse(COMPOSED) { -1L }

    private companion object {
        /** Where the glue's pullFrame puts the copied frame's composition time (capture.cpp). */
        const val COMPOSED = 9
        val next = AtomicLong(1_000)
        val unsafe: sun.misc.Unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
    }
}
