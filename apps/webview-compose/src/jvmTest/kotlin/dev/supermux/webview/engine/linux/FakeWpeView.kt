package dev.supermux.webview.engine.linux

/** A WpeView that records what the engine asks of it, and hands over frames a test says WebKit delivered. */
internal class FakeWpeView(override val id: Long = 1) : WpeView {
    val calls = mutableListOf<String>()
    val decisions = mutableMapOf<Long, Int>()
    val delivered = mutableListOf<Map<String, String>>()
    val finished = mutableMapOf<Long, Pair<Int, ByteArray?>>()
    val evaluations = mutableListOf<Triple<Long, String, String?>>()
    var configured: List<Any?>? = null
    var dark: Boolean? = null
    var visible: Boolean? = null
    var destroyed = false
    var savedState: ByteArray? = "floor-state".encodeToByteArray()
    var restoreAccepts = true

    /** The newest frame WebKit "delivered": width, height, sequence. */
    var frame: Triple<Int, Int, Long>? = null
    private var pulledSeq = -1L

    override fun configure(relay: String?, shim: String?, world: String, handler: String, pageScripts: Boolean, inspectable: Boolean, background: Int, userAgent: String?) {
        configured = listOf(relay, shim, world, handler, pageScripts, inspectable, background, userAgent)
        calls += "configure"
    }

    override fun setDark(dark: Boolean) {
        this.dark = dark
    }

    override fun loadUri(url: String, headers: Map<String, String>) {
        calls += "loadUri $url"
    }

    override fun loadHtml(html: String, baseUrl: String) {
        calls += "loadHtml $baseUrl"
    }

    override fun navigate(what: Int) {
        calls += "navigate $what"
    }

    override fun evaluate(request: Long, script: String, world: String?) {
        evaluations += Triple(request, script, world)
    }

    override fun callAsync(request: Long, body: String, world: String?, args: Map<String, String>) {
        calls += "callAsync $world"
        delivered += args
    }

    override fun decide(decision: Long, verdict: Int) {
        decisions[decision] = verdict
    }

    override suspend fun saveState(): ByteArray? = savedState

    override fun restoreState(state: ByteArray): Boolean {
        calls += "restoreState ${state.decodeToString()}"
        return restoreAccepts
    }

    override fun setSize(width: Int, height: Int, scale: Double) {
        calls += "setSize $width $height $scale"
    }

    override fun setVisible(visible: Boolean) {
        this.visible = visible
    }

    override fun setFocus(focused: Boolean) {
        calls += "setFocus $focused"
    }

    override fun pointer(type: Int, x: Double, y: Double, button: Int, modifiers: Int) {
        calls += "pointer $type ${x.toInt()} ${y.toInt()} $button"
    }

    override fun scroll(dx: Double, dy: Double, x: Double, y: Double, modifiers: Int) {
        calls += "scroll $dx $dy"
    }

    override fun key(down: Boolean, keyval: Int, keycode: Int, modifiers: Int) {
        calls += "key $down $keyval $keycode"
    }

    override fun ime(kind: Int, text: String?, cursor: Int) {
        calls += "ime $kind $text"
    }

    override fun pullFrame(dst: Long, rowBytes: Int, width: Int, height: Int, full: Boolean, out: LongArray): Int {
        val (w, h, seq) = frame ?: return 0
        out.fill(0)
        out[0] = seq
        out[1] = w.toLong()
        out[2] = h.toLong()
        out[6] = 0x00005aL
        if (pulledSeq == seq && !full) return 0
        if (width != w || height != h) return -1
        pulledSeq = seq
        return 1
    }

    override fun frameSeq(): Long = frame?.third ?: -1L

    override fun frameMode(damageScale: Double) {
        calls += "frameMode $damageScale"
    }

    override fun finishScheme(request: Long, status: Int, mime: String?, data: ByteArray?) {
        finished[request] = status to data
    }

    override fun clearData(request: Long) {
        calls += "clearData $request"
    }

    override fun terminate() {
        calls += "terminate"
    }

    override fun destroy() {
        destroyed = true
    }
}
