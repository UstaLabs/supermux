package dev.supermux.webview.engine.mac

/**
 * One page's native side, as `MacWebEngine` sees it (D3): every call returns at once and runs later on AppKit's main
 * thread, in order, except [restoreState] and [showFloor], which wait for their answer. Calls that start a document
 * carry the engine's epoch for it (D4). Answers and results come back as [GlueEvent]s under the question, task or
 * request id they name. Lengths are Compose window pixels unless named otherwise. `NativePageGlue` (Task 8) is the
 * real one; tests use `FakePageGlue`.
 */
internal interface PageGlue {
    /** The page's token: what its upcalls name it by (D5). */
    val token: Long

    /** A lease's setup (D1): theme, background, user agent, inspector, the relay and (with JavaScript on) the shim. */
    fun configure(dark: Boolean, transparent: Boolean, userAgent: String?, inspectable: Boolean, relay: String, shim: String?)

    fun loadUrl(epoch: Int, url: String, headers: Map<String, String>)
    fun loadHtml(epoch: Int, html: String, baseUrl: String)
    fun reload(epoch: Int)
    fun goBack()
    fun goForward()
    fun stop()

    /** Runs [script] in the page's own world, or WebKit's default client world; a `RESULT` with its JSON. */
    fun evaluate(request: Long, script: String, pageWorld: Boolean)

    /** Runs [body] in the isolated world with the arguments `json`, `target` and `name` (`WebKitScripts.deliveryBody`). */
    fun deliver(body: String, json: String, target: String, eventName: String)

    /** Runs [body] (`WebKitScripts.REPAINT_BODY`) in the isolated world; a `RESULT` once it resolves. */
    fun repaint(request: Long, body: String)

    /** `takeSnapshot` at [widthPoints] wide; a `SNAPSHOT`. */
    fun snapshot(request: Long, widthPoints: Double)

    /** WebKit's `interactionState`; a `RESULT` with its bytes. */
    fun saveState(request: Long)

    /** Sets `interactionState` in a new document of [epoch]. Waits: true if WebKit took it. */
    fun restoreState(epoch: Int, state: ByteArray): Boolean

    /** Puts the floor's state back, in [epoch]. Waits: false if the glue holds no floor state. */
    fun showFloor(epoch: Int): Boolean

    fun setMediaSuspended(suspended: Boolean)

    /** Pauses all media and closes its presentations (a reset). */
    fun stopMedia()

    /** Removes the lease's user scripts. */
    fun removeScripts()

    /** Wipes the engine's own data store; a `RESULT`. */
    fun wipeData(request: Long)

    fun answerPolicy(question: Long, allow: Boolean, javaScript: Boolean)
    fun answerResponse(question: Long, allow: Boolean)

    /** Alert: any. Confirm: [confirmed]. Prompt: [text] if [confirmed], else nothing. */
    fun answerDialog(question: Long, confirmed: Boolean, text: String?)
    fun answerPermission(question: Long, grant: Boolean)

    /** Paths or `file:` URIs, or null to cancel. */
    fun answerOpenPanel(question: Long, paths: List<String>?)
    fun respond(task: Long, status: Int, mimeType: String, body: ByteArray, headers: Map<String, String>)

    /** Fails a scheme task as an unknown host does. */
    fun failTask(task: Long)

    /** Moves the page's view into [window]'s content view (an `NSWindow` handle), under the canvas. */
    fun attach(window: Long)

    /** Takes the view out of [window], if it is still there. */
    fun detach(window: Long)

    /** Puts the view at its slot, window pixels from the content's top-left; [hidden] while the slot isn't placed. */
    fun place(x: Float, y: Float, width: Float, height: Float, density: Float, hidden: Boolean)

    /** What `hitTest:` gives the page (D1, D7). */
    fun setInput(region: InputRegion, density: Float, interactive: Boolean, shown: Boolean, wheelToPage: Boolean)

    /** A `TICK` at the next display tick of the view's window, or at once if it is in none. */
    fun tick(request: Long)

    /** Makes the web view first responder. */
    fun focus()

    /** Answers what WebKit still waits on with its safe default, and frees the page. Nothing may be called after it. */
    fun release()
}
