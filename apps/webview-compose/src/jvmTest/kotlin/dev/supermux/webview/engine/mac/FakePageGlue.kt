package dev.supermux.webview.engine.mac

import androidx.compose.ui.geometry.Rect

/**
 * A [PageGlue] that records every call as a line (`"loadHtml 3 https://c1.invalid/"`) and answers the two calls that
 * wait from [restoreAccepted] and [floorKept]. Tests play WebKit by handing the engine [GlueEvent]s.
 */
internal class FakePageGlue(override val token: Long = 1) : PageGlue {
    val calls = mutableListOf<String>()
    var restoreAccepted = true
    var floorKept = true
    var lastRegion: InputRegion? = null
    var lastInteractive = false
    var lastShown = false
    var lastWheelToPage = false
    var released = false

    /** The window each placement named, in order: frame sync moves the view with that window's frames. */
    val placedIn = mutableListOf<Long>()

    /** The ids of the calls named [kind] that carry one, in order (`"evaluate"` → its requests). */
    fun ids(kind: String): List<Long> = calls.filter { it.startsWith("$kind ") }.map { it.split(' ')[1].toLong() }

    fun last(kind: String): String? = calls.lastOrNull { it == kind || it.startsWith("$kind ") }

    fun clear() = calls.clear()

    override fun configure(dark: Boolean, transparent: Boolean, userAgent: String?, inspectable: Boolean, relay: String, shim: String?) {
        calls += "configure dark=$dark transparent=$transparent ua=$userAgent inspectable=$inspectable shim=${shim != null}"
    }

    override fun loadUrl(epoch: Int, url: String, headers: Map<String, String>) {
        calls += "loadUrl $epoch $url"
    }

    override fun loadHtml(epoch: Int, html: String, baseUrl: String) {
        calls += "loadHtml $epoch $baseUrl"
    }

    override fun reload(epoch: Int) {
        calls += "reload $epoch"
    }

    override fun goBack() {
        calls += "goBack"
    }

    override fun goForward() {
        calls += "goForward"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun evaluate(request: Long, script: String, pageWorld: Boolean) {
        calls += "evaluate $request ${if (pageWorld) "page" else "client"} $script"
    }

    override fun deliver(body: String, json: String, target: String, eventName: String) {
        calls += "deliver $target $json"
    }

    override fun repaint(request: Long, body: String) {
        calls += "repaint $request"
    }

    override fun snapshot(request: Long, widthPoints: Double) {
        calls += "snapshot $request $widthPoints"
    }

    override fun saveState(request: Long) {
        calls += "saveState $request"
    }

    override fun restoreState(epoch: Int, state: ByteArray): Boolean {
        calls += "restoreState $epoch ${state.size}"
        return restoreAccepted
    }

    override fun showFloor(epoch: Int): Boolean {
        calls += "showFloor $epoch"
        return floorKept
    }

    override fun setMediaSuspended(suspended: Boolean) {
        calls += "setMediaSuspended $suspended"
    }

    override fun stopMedia() {
        calls += "stopMedia"
    }

    override fun removeScripts() {
        calls += "removeScripts"
    }

    override fun wipeData(request: Long) {
        calls += "wipeData $request"
    }

    override fun answerPolicy(question: Long, allow: Boolean, javaScript: Boolean) {
        calls += "answerPolicy $question $allow"
    }

    override fun answerResponse(question: Long, allow: Boolean) {
        calls += "answerResponse $question $allow"
    }

    override fun answerDialog(question: Long, confirmed: Boolean, text: String?) {
        calls += "answerDialog $question $confirmed $text"
    }

    override fun answerPermission(question: Long, grant: Boolean) {
        calls += "answerPermission $question $grant"
    }

    override fun answerOpenPanel(question: Long, paths: List<String>?) {
        calls += "answerOpenPanel $question $paths"
    }

    override fun respond(task: Long, status: Int, mimeType: String, body: ByteArray, headers: Map<String, String>) {
        calls += "respond $task $status $mimeType ${body.size}"
    }

    override fun failTask(task: Long) {
        calls += "failTask $task"
    }

    override fun attach(window: Long) {
        calls += "attach $window"
    }

    override fun detach(window: Long) {
        calls += "detach $window"
    }

    override fun place(window: Long, slot: Rect, visible: Rect, scale: Float, hidden: Boolean) {
        placedIn += window
        calls += "place ${slot.left} ${slot.top} ${slot.width} ${slot.height} in ${visible.left} ${visible.top} " +
            "${visible.width} ${visible.height} at $scale hidden=$hidden"
    }

    override fun setInput(region: InputRegion, density: Float, interactive: Boolean, shown: Boolean, wheelToPage: Boolean) {
        lastRegion = region
        lastInteractive = interactive
        lastShown = shown
        lastWheelToPage = wheelToPage
    }

    override fun setBackdrop(argb: Int) {
        calls += "setBackdrop %08x".format(argb)
    }

    override fun tick(request: Long) {
        calls += "tick $request"
    }

    override fun focus() {
        calls += "focus"
    }

    override fun release() {
        calls += "release"
        released = true
    }
}
