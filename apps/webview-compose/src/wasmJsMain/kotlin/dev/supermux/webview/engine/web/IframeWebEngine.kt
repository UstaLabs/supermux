package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.EngineEventQueue
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.originOf
import dev.supermux.webview.runtime.OverlayCutouts
import dev.supermux.webview.schemeOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/** A document's origin when the engine can't vouch for it: the opaque origin, which no bridge list contains. */
private const val OPAQUE = "null"

/** The progress reported when a document starts. */
private const val PROGRESS_STARTED = 0.1f

/** The least time between two links a page's user opens: one click can't become many tabs. */
private const val OPEN_INTERVAL_MILLIS = 500.0

/**
 * How long a loaded document that may run the web shim has to hand over its port before it counts as one that never
 * speaks. Its handshake comes after its iframe's `load` (the feasibility test: 0 of 30 the other way round), within
 * milliseconds.
 */
private const val HANDSHAKE_GRACE_MILLIS = 500

/**
 * The web's engine (spec §7 "Web (wasmJs): iframe"), built the way its feasibility test decided ([WebEngineDecisions]).
 *
 * - **Where it shows.** Its [frame] lives in a layer of our own above Compose's canvas, placed by [Surface] in the same
 *   layout pass that moves the view, and never moved to another parent: an iframe that moves reloads.
 * - **Documents** (contract **Documents**, **Painting**). [load] and [reload] each start a fresh iframe with a random
 *   `name`: the engine adds no entries to the tab's history, and a Back can't bring an old entry into a new frame
 *   (G6). Events are pushed per navigation, so nothing of an earlier document is delivered after [load], [reload] or
 *   [reset] returns. A document the runtime starts keeps the frame hidden until it has loaded and the frame has been
 *   displayed for a frame, so the runtime's cover on the canvas shows meanwhile. That is [EngineEvent.FirstPaint].
 * - **Content.** `Html` is a `srcdoc` in `sandbox="allow-scripts"`: an opaque origin of its own. `Url` is `src` in
 *   `sandbox="allow-scripts allow-same-origin allow-forms"`: its real origin, no popups, no top navigation, no dialogs,
 *   no downloads. Scripts off drops `allow-scripts`. What the web can't show is refused with a message page and an
 *   [EngineEvent.Error]: `Bundle` (spec §7), `Url` with headers or in an `Ephemeral` engine (the browser can do neither),
 *   a `Url` on the app's own origin (it could script the app), and `http:` in an `https:` app.
 * - **Commits** (contract **Commits**). They are learned late: at a document's handshake, its first message, which
 *   carries its `$ready`, or else at its iframe's `load`. A commit is pushed before anything else of its document, so
 *   it never comes after the document's own `$ready`, and the first document's [EngineEvent.FirstPaint] waits for it.
 *   - The iframe's `load` comes before the handshake of the document that loaded (the feasibility test: 0 of 30 the
 *     other way round). So a load of a document that may run the web shim waits [HANDSHAKE_GRACE_MILLIS], or until the
 *     next load, for its handshake, and commits as silent only then. Other documents commit at their load.
 *   - A handshake that comes before this navigation's first load commits its document, and that load is no commit.
 *   - A handshake that comes after its document's grace commits that document again, still before its `$ready`.
 *   - An `Html` document that hands over the secret its shim was given commits as `content.origin.url` and speaks as
 *     `content.origin.origin`.
 *   - Anything else in that iframe, such as a page the demo navigated to, commits as `UrlChanged(null)` and speaks as
 *     `"null"`.
 *   - A `Url` page's first document commits as its URL. Later ones, which cross-origin rules hide, commit as null.
 * - **Messages** (contract **Messages**, **Delivery**). Only the current iframe's own window can hand over a port
 *   ([WebFrameRouter]). [postMessage] posts on the current document's port, only if that document's origin is the one
 *   asked for. The port dies with its document, so nothing can reach the next one.
 * - **Links.** A link the user clicks in a shimmed page reaches [client] as a user-gesture navigation (or a new
 *   window), and the runtime opens it outside when the policy says so. The web can't navigate a sandboxed page for the
 *   app, so `Allow` and `OpenHere` leave it where it is.
 * - **Scroll chaining.** A wheel or drag over the page goes to the page alone: the app's list under it never sees it.
 *   So what the user's input couldn't scroll in a shimmed page comes over its port ([WebShim]), and [Surface] passes it
 *   on through Compose's nested scroll, as a child that scrolled would: the list around the view scrolls, and flings on
 *   a drag's release. Only the current document's port is heard, and a frame's worth is capped at
 *   [MAX_SCROLL_PER_FRAME_CSS] (a fling at [MAX_FLING_CSS]), so a page can't throw the list around.
 * - **What the web can't do,** where the contract allows a no-op: no snapshots (cross-origin pixels can't be read), no
 *   `evaluate`, no saved engine state (pages keep theirs through `$saveState`), no back or forward (cross-origin
 *   history can't be reached), no stop. Dialogs, file choosers, permissions and downloads are denied by the sandbox
 *   and never reach [client].
 */
internal class IframeWebEngine(
    val config: EngineConfig,
    private val cutoutMode: CutoutMode = WebEngineDecisions.cutoutMode,
    /** For tests only, which can't make trusted input: see [WebShim.source]. The factory never sets it. */
    private val trustSyntheticInput: Boolean = false,
) : WebEngine, FrameRoutee {
    /** One document the engine started, in its own iframe, and what that iframe went on to show. */
    private inner class Navigation(val id: Int, val content: WebContent, val setup: PageSetup, val refusal: Refusal?) {
        val iframe: HTMLElement = newElement("iframe")
        val secret: String = randomHex()
        var loads = 0
        var loaded = false

        /** The load whose document may still speak: its commit waits for its handshake, or the grace. 0 when none. */
        var awaiting = 0

        /** A handshake came before this navigation's first load, so that load is its document's, committed already. */
        var spokeBeforeLoad = false

        /** Whether a commit of this navigation was pushed: its first paint waits for one. */
        var committed = false
        var painted = false
        var documents = 0
        var page: Page? = null
        var gone = false

        fun end() {
            gone = true
            page?.let { closePort(it.port) }
            page = null
            removeElement(iframe)
        }
    }

    /** A document that spoke: its port, the origin the engine vouches for, and its number in its navigation. */
    private class Page(val port: JsAny, val origin: String, val number: Int)

    /** Why the web can't show some content, and what the page says instead. */
    private class Refusal(val error: WebError, val message: String)

    override val mode: EmbeddingMode get() = EmbeddingMode.NativeView

    /**
     * The common buffer every engine uses (contract **Buffering**). Each navigation is an epoch of it: [start] and
     * [reset] begin one, and [push] drops what an earlier navigation's callbacks still report.
     */
    private val queue = EngineEventQueue()
    override val events: Flow<EngineEvent> = queue.events

    override var client: EngineClient? = null

    private var released = false
    private var crashed = false
    override val isUsable: Boolean get() = !released && !crashed

    override val needsCutouts: Boolean get() = true

    /** Where the engine shows. */
    internal val frame = WebFrame()

    private var setup: PageSetup? = null
    private var current: Navigation? = null

    /** The previous navigation, kept on screen during a reload until the new one paints (paint holding). */
    private var holding: Navigation? = null

    private var focused = false
    private var lastOpenAt = Double.NEGATIVE_INFINITY

    /** The nested scroll of the surface that shows the engine now: where the page's left-over scroll goes. */
    private var scrollDispatcher: NestedScrollDispatcher? = null

    /** Left-over scroll from the page since the last frame, in CSS px, and whether a frame will pass it on. */
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private var scrollQueued = false

    init {
        frame.onBroken = ::crash
        frame.onDisplayed = { current?.let { navigation -> afterNextFrame { painted(navigation) } } }
        WebFrameRouter.register(this)
    }

    private fun alive(): Boolean = !released && !crashed

    /** Queues [event] of [navigation], from its [page] if a page sent it, unless a later navigation or a reset began. */
    private fun push(navigation: Navigation, event: EngineEvent, page: Page? = null) {
        if (navigation.id == queue.epoch) queue.offer(event, page)
    }

    override fun configure(setup: PageSetup) {
        if (!alive()) return
        this.setup = setup
        frame.setTheme(setup.dark, setup.transparentBackground)
        current?.let { styleIframe(it.iframe, setup.dark) }
    }

    override fun load(content: WebContent) {
        if (!alive()) return
        start(content, setup ?: return, holdPaint = false)
    }

    /**
     * Reloads the content as it was loaded, since a cross-origin page's own navigations can't be read. The page on
     * screen stays there until the reloaded one paints (contract **Painting**), because the runtime covers only the
     * documents it starts.
     */
    override fun reload() {
        if (!alive()) return
        val navigation = current ?: return
        start(navigation.content, setup ?: navigation.setup, holdPaint = true)
    }

    /** Cross-origin history can't be reached from the app (spec §7): a no-op, and `canGoBack` stays false. */
    override fun goBack() = Unit

    /** As [goBack]. */
    override fun goForward() = Unit

    /** A cross-origin load can't be stopped from the app: a no-op. */
    override fun stop() = Unit

    /** Scripts can't be run in a cross-origin page: always null. */
    override suspend fun evaluate(script: String): String? = null

    override fun postMessage(json: String, targetOrigin: String) {
        if (!alive()) return
        val navigation = current ?: return
        val page = navigation.page ?: return
        if (navigation.gone || page.origin == OPAQUE || page.origin != targetOrigin) return
        postToPort(page.port, json)
    }

    /** Cross-origin pixels can't be read (spec §4: "Web: none for cross-origin content"): always null. */
    override suspend fun snapshot(): ImageBitmap? = null

    /** The engine has no state of its own to save; pages keep theirs through `$saveState`. Always null. */
    override suspend fun saveState(): ByteArray? = null

    /** Never: [saveState] gives nothing to restore. The runtime loads the content instead. */
    override fun restoreState(state: ByteArray, content: WebContent): Boolean = false

    override fun setVisible(visible: Boolean) {
        if (alive()) frame.shown = visible
    }

    override fun requestFocus() {
        if (!alive()) return
        current?.takeIf { !it.gone }?.let { focusElement(it.iframe) }
    }

    override fun applyOverlays(cutouts: OverlayCutouts) {
        if (alive()) frame.applyCutouts(cutouts, cutoutMode)
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        if (released) return
        val layer = rememberWebLayer()
        val dispatcher = remember { NestedScrollDispatcher() }
        DisposableEffect(dispatcher) {
            scrollDispatcher = dispatcher
            onDispose { if (scrollDispatcher === dispatcher) scrollDispatcher = null }
        }
        Box(modifier.nestedScroll(NoNestedScroll, dispatcher).then(WebSurfaceElement(frame, layer)))
    }

    /**
     * Ends the lease: the page's iframe is removed, nothing pushed before is delivered after, and the frame hides
     * until a new lease's first document paints. True when [WebEngineDecisions.RESET_ISOLATES_HISTORY] says no old
     * history entry can reach a new frame (G6).
     */
    override suspend fun reset(): Boolean {
        if (released) return false
        // A reset's epoch: the last lease's focus goes too (contract **After reset**, EngineEventQueue).
        queue.advance(reset = true)
        current?.end()
        current = null
        endHolding()
        setup = null
        client = null
        focused = false
        lastOpenAt = Double.NEGATIVE_INFINITY
        pendingScrollX = 0f
        pendingScrollY = 0f
        frame.reset()
        return WebEngineDecisions.RESET_ISOLATES_HISTORY && !crashed
    }

    override fun release() {
        if (released) return
        released = true
        queue.advance(reset = true)
        current?.end()
        current = null
        endHolding()
        client = null
        frame.remove()
        WebFrameRouter.unregister(this)
    }

    // FrameRoutee

    override fun owns(source: JsAny?): Boolean {
        val navigation = current ?: return false
        return !navigation.gone && same(source, windowOf(navigation.iframe))
    }

    override fun onHandshake(origin: String, secret: String, text: String, port: JsAny) {
        val navigation = current
        if (navigation == null || navigation.gone || navigation.refusal != null || !alive()) {
            closePort(port)
            return
        }
        when {
            // Chrome's order: the document that loaded last hands over its port, within its grace.
            navigation.awaiting != 0 -> navigation.awaiting = 0
            // Before the navigation's first load: that load will be this document's, and no commit.
            navigation.loads == 0 -> navigation.spokeBeforeLoad = true
            // Else its grace ran out first, and it committed as silent: it commits again now, before its ready.
        }
        val content = navigation.content
        val documentOrigin = when {
            origin != OPAQUE -> origin
            content is WebContent.Html && secret == navigation.secret -> content.origin.origin
            else -> OPAQUE
        }
        navigation.page?.let { closePort(it.port) }
        navigation.documents++
        val page = Page(port, documentOrigin, navigation.documents)
        navigation.page = page
        commit(navigation, commitUrl(navigation, documentOrigin))
        listenOnPort(
            port,
            onText = { fromPage(navigation, page, it) },
            onOpen = { url, newWindow -> openRequested(navigation, page, url, newWindow) },
            onScroll = { dx, dy -> scrolledInPage(navigation, page, dx, dy) },
            onFling = { vx, vy -> flungInPage(navigation, page, vx, vy) },
        )
        fromPage(navigation, page, text)
    }

    override fun checkFocus() {
        if (!alive()) return
        val now = current?.let { !it.gone && isActiveElement(it.iframe) } ?: false
        if (now == focused) return
        focused = now
        queue.offer(EngineEvent.FocusChanged(now))
    }

    // Documents

    private fun start(content: WebContent, setup: PageSetup, holdPaint: Boolean) {
        val previous = current
        val id = queue.advance()
        endHolding()
        if (holdPaint && previous != null && previous.painted && !previous.gone) {
            // Paint holding (contract **Painting**): the old page stays on screen, deaf, until the new one paints.
            previous.page?.let { closePort(it.port) }
            previous.page = null
            holding = previous
        } else {
            previous?.end()
            frame.covered = true
        }
        val navigation = Navigation(id, content, setup, refusalFor(content))
        current = navigation
        push(navigation, EngineEvent.Progress(PROGRESS_STARTED))
        show(navigation)
    }

    private fun show(navigation: Navigation) {
        val iframe = navigation.iframe
        val setup = navigation.setup
        attribute(iframe, "name", "webview-" + randomHex())
        styleIframe(iframe, setup.dark)
        onFrameLoad(iframe) { onLoad(navigation) }
        val refusal = navigation.refusal
        if (refusal != null) {
            attribute(iframe, "sandbox", "")
            setSrcdoc(iframe, WebDocuments.errorPage(refusal.message, setup.dark))
        } else {
            when (val content = navigation.content) {
                is WebContent.Html -> {
                    attribute(iframe, "sandbox", if (setup.javaScriptEnabled) "allow-scripts" else "")
                    setSrcdoc(iframe, documentOf(navigation, content))
                }
                is WebContent.Url -> {
                    val scripts = if (setup.javaScriptEnabled) "allow-scripts " else ""
                    attribute(iframe, "sandbox", scripts + "allow-same-origin allow-forms")
                    attribute(iframe, "referrerpolicy", "strict-origin-when-cross-origin")
                    setSrc(iframe, content.url)
                }
                // refusalFor refuses every bundle; this branch is never reached.
                is WebContent.Bundle -> Unit
            }
        }
        frame.addIframe(iframe, below = holding?.iframe)
    }

    /** The HTML a demo's iframe gets: the web shim first, when the page runs scripts and its origin may talk. */
    private fun documentOf(navigation: Navigation, content: WebContent.Html): String {
        val setup = navigation.setup
        val bridged = setup.javaScriptEnabled && content.origin.origin in setup.bridgeOrigins
        if (!bridged) return content.html
        return WebDocuments.withScript(content.html, WebShim.source(navigation.secret, pageOrigin(), trustSyntheticInput = trustSyntheticInput))
    }

    private fun styleIframe(iframe: HTMLElement, dark: Boolean) {
        styleOf(iframe, "position", "absolute")
        styleOf(iframe, "left", "0px")
        styleOf(iframe, "top", "0px")
        styleOf(iframe, "width", "100%")
        styleOf(iframe, "height", "100%")
        styleOf(iframe, "border", "0")
        styleOf(iframe, "display", "block")
        styleOf(iframe, "color-scheme", if (dark) "dark" else "light")
    }

    private fun onLoad(navigation: Navigation) {
        if (current !== navigation || navigation.gone || !alive()) return
        navigation.loads++
        navigation.loaded = true
        if (navigation.refusal == null) {
            if (navigation.loads == 1 && navigation.spokeBeforeLoad) {
                // The first document spoke before its own load (not seen in Chrome): it committed at its handshake.
                navigation.spokeBeforeLoad = false
            } else {
                // A new document. The one before is gone, with its port, and if it was still due to speak, it didn't.
                settleSilent(navigation)
                navigation.page?.let { closePort(it.port) }
                navigation.page = null
                val load = navigation.loads
                if (mayRunShim(navigation)) {
                    navigation.awaiting = load
                    afterMillis(HANDSHAKE_GRACE_MILLIS) { if (navigation.awaiting == load) settleSilent(navigation) }
                } else {
                    commit(navigation, silentUrl(navigation, load))
                }
            }
            push(navigation, EngineEvent.Progress(null))
        }
        if (!navigation.painted) afterNextFrame { painted(navigation) }
    }

    /** Whether documents of [navigation] may run the web shim, and so hand over a port after their load. */
    private fun mayRunShim(navigation: Navigation): Boolean {
        val content = navigation.content as? WebContent.Html ?: return false
        return navigation.setup.javaScriptEnabled && content.origin.origin in navigation.setup.bridgeOrigins
    }

    /** The document that loaded [Navigation.awaiting]th never spoke: it commits as one that can't be read, but the first. */
    private fun settleSilent(navigation: Navigation) {
        val load = navigation.awaiting
        if (load == 0 || current !== navigation || navigation.gone || !alive()) return
        navigation.awaiting = 0
        commit(navigation, silentUrl(navigation, load))
    }

    /** A document that never spoke commits as the content's URL if it is the navigation's first, else as unreadable. */
    private fun silentUrl(navigation: Navigation, load: Int): String? = if (load == 1) urlOf(navigation.content) else null

    /**
     * The document loaded, committed, and the frame is displayed: it is on screen now, or its refusal is. A refusal has
     * no commit.
     */
    private fun painted(navigation: Navigation) {
        if (current !== navigation || navigation.gone || navigation.painted || !navigation.loaded || !frame.displayed || !alive()) return
        val refusal = navigation.refusal
        if (refusal == null && !navigation.committed) return
        navigation.painted = true
        endHolding()
        frame.covered = false
        if (refusal == null) {
            push(navigation, EngineEvent.FirstPaint)
        } else {
            push(navigation, EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false))
            push(navigation, EngineEvent.Error(refusal.error))
            push(navigation, EngineEvent.Progress(null))
        }
    }

    private fun commit(navigation: Navigation, url: String?) {
        push(navigation, EngineEvent.UrlChanged(url))
        push(navigation, EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false))
        if (!navigation.committed) {
            navigation.committed = true
            // The first paint waited for this commit.
            if (navigation.loaded && !navigation.painted) afterNextFrame { painted(navigation) }
        }
    }

    /**
     * What a document that spoke with [documentOrigin] commits as. A `Url` page's first document, the one that loaded
     * first or hasn't loaded yet, commits as its URL when it speaks as that URL's origin.
     */
    private fun commitUrl(navigation: Navigation, documentOrigin: String): String? = when (val content = navigation.content) {
        is WebContent.Html -> if (documentOrigin == content.origin.origin) content.origin.url else null
        is WebContent.Url ->
            if (navigation.documents == 1 && navigation.loads <= 1 && documentOrigin == originOf(content.url)) content.url else null
        is WebContent.Bundle -> null
    }

    private fun urlOf(content: WebContent): String = when (content) {
        is WebContent.Html -> content.origin.url
        is WebContent.Url -> content.url
        is WebContent.Bundle -> content.origin.url + content.entry
    }

    private fun fromPage(navigation: Navigation, page: Page, text: String) {
        if (current !== navigation || navigation.page !== page || !alive()) return
        push(navigation, EngineEvent.PageMessage(page.origin, text), page)
    }

    private fun openRequested(navigation: Navigation, page: Page, url: String, newWindow: Boolean) {
        if (current !== navigation || navigation.page !== page || page.origin == OPAQUE || !alive()) return
        val now = nowMillis()
        if (now - lastOpenAt < OPEN_INTERVAL_MILLIS) return
        lastOpenAt = now
        val client = client ?: return
        try {
            if (newWindow) {
                client.onNewWindow(NewWindowRequest(url, isUserGesture = true))
            } else {
                client.onNavigation(NavigationRequest(url, isMainFrame = true, isUserGesture = true))
            }
        } catch (e: Exception) {
            // Nothing a client throws reaches the app (contract): the link just doesn't open.
        }
    }

    // Scroll chaining

    /** What the user's input left over in the current document, in CSS px: gathered, and passed on once a frame. */
    private fun scrolledInPage(navigation: Navigation, page: Page, dx: Double, dy: Double) {
        if (current !== navigation || navigation.page !== page || !alive()) return
        pendingScrollX += dx.toFloat()
        pendingScrollY += dy.toFloat()
        if (scrollQueued) return
        scrollQueued = true
        afterNextFrame(::passOnScroll)
    }

    /**
     * Hands a frame's left-over scroll, capped, to the list around the view through nested scroll, as a child that
     * scrolled hands on the rest: ancestors that pre-scroll first, then the scrollable that takes it after. The DOM's
     * "scroll down" is Compose's negative delta.
     */
    private fun passOnScroll() {
        scrollQueued = false
        val dx = pendingScrollX.coerceIn(-MAX_SCROLL_PER_FRAME_CSS, MAX_SCROLL_PER_FRAME_CSS)
        val dy = pendingScrollY.coerceIn(-MAX_SCROLL_PER_FRAME_CSS, MAX_SCROLL_PER_FRAME_CSS)
        pendingScrollX = 0f
        pendingScrollY = 0f
        val dispatcher = scrollDispatcher
        if (dispatcher == null || !alive() || (dx == 0f && dy == 0f)) return
        val delta = Offset(-dx * frame.density, -dy * frame.density)
        val before = dispatcher.dispatchPreScroll(delta, NestedScrollSource.UserInput)
        dispatcher.dispatchPostScroll(Offset.Zero, delta - before, NestedScrollSource.UserInput)
    }

    /** A drag that was chaining ended at this velocity (CSS px per second): the list flings, as after its own drag. */
    private fun flungInPage(navigation: Navigation, page: Page, vx: Double, vy: Double) {
        if (current !== navigation || navigation.page !== page || !alive()) return
        val dispatcher = scrollDispatcher ?: return
        val d = frame.density
        val velocity = Velocity(
            -vx.toFloat().coerceIn(-MAX_FLING_CSS, MAX_FLING_CSS) * d,
            -vy.toFloat().coerceIn(-MAX_FLING_CSS, MAX_FLING_CSS) * d,
        )
        // Only an attached dispatcher has a scope to fling in; a surface that just left has none.
        val scope = try {
            dispatcher.coroutineScope
        } catch (e: IllegalStateException) {
            return
        }
        scope.launch {
            val before = dispatcher.dispatchPreFling(velocity)
            dispatcher.dispatchPostFling(Velocity.Zero, velocity - before)
        }
    }

    private fun refusalFor(content: WebContent): Refusal? = when (content) {
        is WebContent.Html -> null
        is WebContent.Bundle -> Refusal(WebError.UnsupportedOnPlatform(urlOf(content)), "Plugins (bundles) can't be shown on the web yet.")
        is WebContent.Url -> when {
            content.headers.isNotEmpty() ->
                Refusal(WebError.UnsupportedOnPlatform(content.url), "A page loaded with extra headers can't be shown on the web.")
            config.profile == WebProfile.Ephemeral ->
                Refusal(WebError.UnsupportedOnPlatform(content.url), "A page in a private (ephemeral) profile can't be shown on the web.")
            originOf(content.url) == pageOrigin() ->
                Refusal(WebError.BlockedByPolicy(content.url), "This page is on the app's own origin, so it isn't shown inside the app.")
            schemeOf(content.url) == "http" && pageOrigin().startsWith("https:") ->
                Refusal(WebError.BlockedByPolicy(content.url), "An insecure (http) page isn't shown in a secure app.")
            else -> null
        }
    }

    private fun endHolding() {
        holding?.end()
        holding = null
    }

    /** The frame can't be shown any more (its viewport is gone, or a surface wants it in another): crashed, for good. */
    private fun crash() {
        if (crashed || released) return
        crashed = true
        current?.end()
        current = null
        endHolding()
        queue.offer(EngineEvent.Crashed)
    }

    companion object {
        /** The most a page's left-over input scrolls the list in one frame, per axis, in CSS px. */
        const val MAX_SCROLL_PER_FRAME_CSS: Float = 400f

        /** The fastest a page's left-over drag flings the list, per axis, in CSS px per second. */
        const val MAX_FLING_CSS: Float = 8_000f
    }
}

/** The surface's own part in nested scroll: it scrolls nothing itself, it only passes the page's left-over on. */
private object NoNestedScroll : NestedScrollConnection
