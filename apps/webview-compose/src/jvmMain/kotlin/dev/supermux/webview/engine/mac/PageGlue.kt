package dev.supermux.webview.engine.mac

import androidx.compose.ui.geometry.Rect
import dev.supermux.webview.WebCookie
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
    fun configure(dark: Boolean, transparent: Boolean, userAgent: String?, inspectable: Boolean, relay: String, shim: String?) // platform-ok: WebKit's user-agent setting; not an OS check

    fun loadUrl(epoch: Int, url: String, headers: Map<String, String>)
    fun loadHtml(epoch: Int, html: String, baseUrl: String)
    fun reload(epoch: Int)
    fun goBack()
    fun goForward()
    fun stop()

    /** Finds [query]; answered by a Result whose json is `true` or `false` (WebKit says only found or not). */
    fun find(request: Long, query: String, backwards: Boolean)

    /** Clears find's selection. */
    fun clearFind()

    /** The page's zoom, WebKit's `pageZoom`. */
    fun setPageZoom(factor: Double)

    /** Puts a cookie in the page's data store; answered by a Result, ok once it's stored. */
    fun setCookie(request: Long, cookie: WebCookie)

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

    /**
     * Puts the view at [slot], window pixels from the content's top-left, showing only its [visible] part (I2), at the
     * window's backing [scale] (I3); [hidden] while the slot isn't placed. In a [window] whose views move with skiko's
     * frames ([MacFrameSync]), it moves with the frame of the layout that placed it, else at AppKit's next commit.
     */
    fun place(window: Long, slot: Rect, visible: Rect, scale: Float, hidden: Boolean)

    /** What `hitTest:` gives the page (D1, D7). */
    fun setInput(region: InputRegion, density: Float, interactive: Boolean, shown: Boolean, wheelToPage: Boolean)

    /**
     * The page's own background, opaque ARGB, or 0 for none: while the slot moves, it lies under every page view where
     * this page showed in the last moments, so the band of its hole that Compose's frame still has where the view was
     * shows it, not the window's backing (the view moves at AppKit's next commit, the hole when skiko's frame is drawn).
     */
    fun setBackdrop(argb: Int)

    /** A `TICK` at the next display tick of the view's window, or at once if it is in none. */
    fun tick(request: Long)

    /** Makes the web view first responder. */
    fun focus()

    /** Answers what WebKit still waits on with its safe default, and frees the page. Nothing may be called after it. */
    fun release()
}

/**
 * The real [PageGlue]: [handle] is what `CREATED` gave; [token] is what the page's upcalls carry. Once [release]d it
 * refuses every call (Minor 2): the handle names freed memory then, and a stray call would use it.
 */
internal class NativePageGlue(override val token: Long, handle: Long) : PageGlue {
    /** The page's handle; 0 once released, which every call checks first. */
    @Volatile private var handle: Long = handle

    /** [block] with the handle, unless released. */
    private inline fun <T> live(otherwise: T, block: (Long) -> T): T {
        val page = handle
        return if (page == 0L) otherwise else block(page)
    }

    // platform-ok: WebKit's user-agent setting; not an OS check
    override fun configure(dark: Boolean, transparent: Boolean, userAgent: String?, inspectable: Boolean, relay: String, shim: String?) =
        // platform-ok: WebKit's user-agent setting; not an OS check
        live(Unit) { MacGlue.configure(it, dark, transparent, userAgent, inspectable, relay, shim) }

    override fun loadUrl(epoch: Int, url: String, headers: Map<String, String>) = live(Unit) {
        val (names, values) = headerArrays(headers)
        MacGlue.loadUrl(it, epoch, url, names, values)
    }

    override fun loadHtml(epoch: Int, html: String, baseUrl: String) = live(Unit) { MacGlue.loadHtml(it, epoch, html, baseUrl) }
    override fun reload(epoch: Int) = live(Unit) { MacGlue.reload(it, epoch) }
    override fun goBack() = live(Unit) { MacGlue.goBack(it) }
    override fun goForward() = live(Unit) { MacGlue.goForward(it) }
    override fun stop() = live(Unit) { MacGlue.stop(it) }
    override fun find(request: Long, query: String, backwards: Boolean) = live(Unit) { MacGlue.find(it, request, query, backwards) }
    override fun clearFind() = live(Unit) { MacGlue.clearFind(it) }
    override fun setPageZoom(factor: Double) = live(Unit) { MacGlue.setPageZoom(it, factor) }
    override fun setCookie(request: Long, cookie: WebCookie) = live(Unit) {
        MacGlue.setCookie(
            it, request, cookie.name, cookie.value, cookie.url, cookie.path, cookie.domain,
            cookie.secure, cookie.httpOnly, cookie.sameSite, cookie.maxAgeSeconds ?: -1L,
        )
    }
    override fun evaluate(request: Long, script: String, pageWorld: Boolean) = live(Unit) { MacGlue.evaluate(it, request, script, pageWorld) }
    override fun deliver(body: String, json: String, target: String, eventName: String) =
        live(Unit) { MacGlue.deliver(it, body, json, target, eventName) }
    override fun repaint(request: Long, body: String) = live(Unit) { MacGlue.repaint(it, request, body) }
    override fun snapshot(request: Long, widthPoints: Double) = live(Unit) { MacGlue.snapshot(it, request, widthPoints) }
    override fun saveState(request: Long) = live(Unit) { MacGlue.saveState(it, request) }
    override fun restoreState(epoch: Int, state: ByteArray): Boolean = live(false) { MacGlue.restoreState(it, epoch, state) }
    override fun showFloor(epoch: Int): Boolean = live(false) { MacGlue.showFloor(it, epoch) }
    override fun setMediaSuspended(suspended: Boolean) = live(Unit) { MacGlue.setMediaSuspended(it, suspended) }
    override fun stopMedia() = live(Unit) { MacGlue.stopMedia(it) }
    override fun removeScripts() = live(Unit) { MacGlue.removeScripts(it) }
    override fun wipeData(request: Long) = live(Unit) { MacGlue.wipeData(it, request) }
    override fun answerPolicy(question: Long, allow: Boolean, javaScript: Boolean) =
        live(Unit) { MacGlue.answerPolicy(it, question, allow, javaScript) }
    override fun answerResponse(question: Long, allow: Boolean) = live(Unit) { MacGlue.answerResponse(it, question, allow) }
    override fun answerDialog(question: Long, confirmed: Boolean, text: String?) =
        live(Unit) { MacGlue.answerDialog(it, question, confirmed, text) }
    override fun answerPermission(question: Long, grant: Boolean) = live(Unit) { MacGlue.answerPermission(it, question, grant) }
    override fun answerOpenPanel(question: Long, paths: List<String>?) =
        live(Unit) { MacGlue.answerOpenPanel(it, question, paths?.toTypedArray()) }

    override fun respond(task: Long, status: Int, mimeType: String, body: ByteArray, headers: Map<String, String>) = live(Unit) {
        val (names, values) = headerArrays(headers)
        MacGlue.respond(it, task, status, mimeType, body, names, values)
    }

    override fun failTask(task: Long) = live(Unit) { MacGlue.failTask(it, task) }
    override fun attach(window: Long) = live(Unit) { MacGlue.attach(it, window) }
    override fun detach(window: Long) = live(Unit) { MacGlue.detach(it, window) }

    override fun place(window: Long, slot: Rect, visible: Rect, scale: Float, hidden: Boolean) = live(Unit) {
        MacGlue.place(it, window, slot.left, slot.top, slot.width, slot.height, visible.left, visible.top, visible.width, visible.height, scale, hidden)
    }

    override fun setInput(region: InputRegion, density: Float, interactive: Boolean, shown: Boolean, wheelToPage: Boolean) =
        live(Unit) { MacGlue.setInput(it, region.geometry, region.holeCount, density, inputFlags(interactive, shown, wheelToPage)) }

    override fun setBackdrop(argb: Int) = live(Unit) { MacGlue.setBackdrop(it, argb) }

    override fun tick(request: Long) = live(Unit) { MacGlue.tick(it, request) }
    override fun focus() = live(Unit) { MacGlue.focus(it) }

    override fun release() {
        val page = handle
        if (page == 0L) return
        handle = 0L
        MacGlue.release(page)
    }

    /** For the tests: the page's WebContent process id, -1 if WebKit names none (or released). Waits. */
    fun webProcess(): Int = live(-1) { MacGlue.testWebProcess(it) }

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
                WebKitUrls.BUNDLE_SCHEME, WebKitUrls.FLOOR_HOST, WebKitUrls.FLOOR_HTML,
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
