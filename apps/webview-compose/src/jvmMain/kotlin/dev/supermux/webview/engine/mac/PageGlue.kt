package dev.supermux.webview.engine.mac

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.engine.webkit.profileUuidString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

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

/** The real [PageGlue]: [handle] is what `CREATED` gave; [token] is what the page's upcalls carry. */
internal class NativePageGlue(override val token: Long, private val handle: Long) : PageGlue {
    override fun configure(dark: Boolean, transparent: Boolean, userAgent: String?, inspectable: Boolean, relay: String, shim: String?) =
        MacGlue.configure(handle, dark, transparent, userAgent, inspectable, relay, shim)

    override fun loadUrl(epoch: Int, url: String, headers: Map<String, String>) {
        val (names, values) = headerArrays(headers)
        MacGlue.loadUrl(handle, epoch, url, names, values)
    }

    override fun loadHtml(epoch: Int, html: String, baseUrl: String) = MacGlue.loadHtml(handle, epoch, html, baseUrl)
    override fun reload(epoch: Int) = MacGlue.reload(handle, epoch)
    override fun goBack() = MacGlue.goBack(handle)
    override fun goForward() = MacGlue.goForward(handle)
    override fun stop() = MacGlue.stop(handle)
    override fun evaluate(request: Long, script: String, pageWorld: Boolean) = MacGlue.evaluate(handle, request, script, pageWorld)
    override fun deliver(body: String, json: String, target: String, eventName: String) = MacGlue.deliver(handle, body, json, target, eventName)
    override fun repaint(request: Long, body: String) = MacGlue.repaint(handle, request, body)
    override fun snapshot(request: Long, widthPoints: Double) = MacGlue.snapshot(handle, request, widthPoints)
    override fun saveState(request: Long) = MacGlue.saveState(handle, request)
    override fun restoreState(epoch: Int, state: ByteArray): Boolean = MacGlue.restoreState(handle, epoch, state)
    override fun showFloor(epoch: Int): Boolean = MacGlue.showFloor(handle, epoch)
    override fun setMediaSuspended(suspended: Boolean) = MacGlue.setMediaSuspended(handle, suspended)
    override fun stopMedia() = MacGlue.stopMedia(handle)
    override fun removeScripts() = MacGlue.removeScripts(handle)
    override fun wipeData(request: Long) = MacGlue.wipeData(handle, request)
    override fun answerPolicy(question: Long, allow: Boolean, javaScript: Boolean) = MacGlue.answerPolicy(handle, question, allow, javaScript)
    override fun answerResponse(question: Long, allow: Boolean) = MacGlue.answerResponse(handle, question, allow)
    override fun answerDialog(question: Long, confirmed: Boolean, text: String?) = MacGlue.answerDialog(handle, question, confirmed, text)
    override fun answerPermission(question: Long, grant: Boolean) = MacGlue.answerPermission(handle, question, grant)
    override fun answerOpenPanel(question: Long, paths: List<String>?) = MacGlue.answerOpenPanel(handle, question, paths?.toTypedArray())

    override fun respond(task: Long, status: Int, mimeType: String, body: ByteArray, headers: Map<String, String>) {
        val (names, values) = headerArrays(headers)
        MacGlue.respond(handle, task, status, mimeType, body, names, values)
    }

    override fun failTask(task: Long) = MacGlue.failTask(handle, task)
    override fun attach(window: Long) = MacGlue.attach(handle, window)
    override fun detach(window: Long) = MacGlue.detach(handle, window)

    override fun place(x: Float, y: Float, width: Float, height: Float, density: Float, hidden: Boolean) =
        MacGlue.place(handle, x, y, width, height, density, hidden)

    override fun setInput(region: InputRegion, density: Float, interactive: Boolean, shown: Boolean, wheelToPage: Boolean) =
        MacGlue.setInput(handle, region.geometry, region.holeCount, density, inputFlags(interactive, shown, wheelToPage))

    override fun tick(request: Long) = MacGlue.tick(handle, request)
    override fun focus() = MacGlue.focus(handle)
    override fun release() = MacGlue.release(handle)

    companion object {
        /** The isolated world the relay runs in (iOS's name). */
        const val WORLD_NAME: String = "webview"

        /** How long WebKit may take to make a page before the factory gives up on it. */
        private const val CREATE_MILLIS = 10_000L

        /** `setInput`'s flags: interactive 1, shown 2, wheel to the page 4. */
        fun inputFlags(interactive: Boolean, shown: Boolean, wheelToPage: Boolean): Int =
            (if (interactive) 1 else 0) or (if (shown) 2 else 0) or (if (wheelToPage) 4 else 0)

        /** Headers as the glue takes them: names and values, in one order. */
        fun headerArrays(headers: Map<String, String>): Pair<Array<String>, Array<String>> {
            val entries = headers.entries.toList()
            return entries.map { it.key }.toTypedArray() to entries.map { it.value }.toTypedArray()
        }

        /** The data store a profile gets (D9): 0 the default, 1 a non-persistent one of the engine's own, 2 a named one. */
        fun storeOf(profile: WebProfile): Pair<Int, String?> = when (profile) {
            WebProfile.Default -> 0 to null
            WebProfile.Ephemeral -> 1 to null
            is WebProfile.Named -> 2 to profileUuidString(profile.id)
        }

        /**
         * A page for [config], made on AppKit's thread: null if WebKit refused, or took more than 10 s (a page made after
         * that is freed when it comes). Main thread.
         */
        suspend fun create(config: EngineConfig, scripts: WebKitScripts): NativePageGlue? {
            val (token, created) = MacPages.newToken()
            val (profile, store) = storeOf(config.profile)
            MacGlue.createPage(
                token, profile, store, config.mediaPlaybackRequiresGesture, WORLD_NAME, scripts.handlerName,
                WebKitUrls.BUNDLE_SCHEME, WebKitUrls.FLOOR_SCHEME, WebKitUrls.FLOOR_HTML,
            )
            val handle = try {
                withTimeoutOrNull(CREATE_MILLIS) { created.await() }
            } catch (e: CancellationException) {
                MacPages.abandon(token, MacGlue::release)
                throw e
            }
            if (handle == null) {
                MacPages.abandon(token, MacGlue::release)
                return null
            }
            return if (handle == 0L) null else NativePageGlue(token, handle)
        }
    }
}
