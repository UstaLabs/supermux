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
import dev.supermux.webview.runtime.OverlayCutouts
import dev.supermux.webview.schemeOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/** A document's origin when the engine can't vouch for it: the opaque origin, which no bridge list contains. */
private const val OPAQUE = "null"

/** The progress reported when a document starts. */
private const val PROGRESS_STARTED = 0.1f

/** What a page on the app's own origin shows instead. */
private const val APP_ORIGIN_MESSAGE = "This page is on the app's own origin, so it isn't shown inside the app."

/** The least time between two links a page's user opens: one click can't become many tabs. */
private const val OPEN_INTERVAL_MILLIS = 500.0

/** How long after the app asks for the page's focus, or the user presses in the page, the focus that follows is theirs. */
private const val FOCUS_INVITE_MILLIS = 1_000.0

/**
 * The longest a document waits to show, from its start, when it neither reports a paint nor loads: the contract's cap on
 * iOS. A slow request (an image, a hanging script) otherwise keeps its view covered until the network gives up.
 */
private const val PAINT_CAP_MILLIS = 3_000

/** How long after a Tab key press in the app a focus entering the page is the Tab's. It moves focus at once. */
private const val TAB_FOCUS_MILLIS = 100.0

/** How late a press may be told after the focus it caused was given back: the two come by different routes. */
private const val LATE_PRESS_MILLIS = 300.0

/** How long after a give-back the page's shim must have said the keyboard left it, and how often it is given back again. */
private const val GIVE_BACK_CHECK_MILLIS = 30
private const val GIVE_BACK_TRIES = 3

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
 *   [reset] returns. A document the runtime starts keeps the frame covered ([WebFrame.covered]: drawing in its place,
 *   unseen) until it has something drawn to show, the frame has been displayed for a frame, and one more frame has
 *   passed, so that the frame the cover gives way to is one the page has drawn; the runtime's cover on the canvas shows
 *   meanwhile. That is [EngineEvent.FirstPaint]. Something drawn: a shimmed page's first contentful paint, or its DOM and
 *   two frames, as its shim reports; or the iframe's `load`, which waits for every subresource; or [PAINT_CAP_MILLIS]
 *   since the document started, as on iOS.
 * - **Content.** `Html` is a `srcdoc` in `sandbox="allow-scripts"`: an opaque origin of its own. `Url` is `src` in
 *   `sandbox="allow-scripts allow-same-origin allow-forms"`: its real origin, no popups, no top navigation, no dialogs,
 *   no downloads. Scripts off drops `allow-scripts`. What the web can't show is refused with a message page and an
 *   [EngineEvent.Error]: `Bundle` (spec §7), `Url` with headers or in an `Ephemeral` engine (the browser can do neither),
 *   a `Url` on the app's own origin (it could script the app), as the browser's own URL parser reads it, and `http:`
 *   in an `https:` app. A `Url` page that reaches the app's origin anyway (a redirect, a navigation of its own) loses
 *   its frame at its load, for the same message: defence in depth only, as its scripts have run by then. An app that
 *   shows `Url` pages must keep its own origin from being framed (README).
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
 *   - Anything else in that iframe, such as a page the demo navigated to, has no shim and hands over no port: its
 *     handshake is closed, and it commits at its load, as `UrlChanged(null)`.
 *   - A `Url` page's first document commits as its URL. Later ones, which cross-origin rules hide, commit as null.
 * - **Messages** (contract **Messages**, **Delivery**). Only the current iframe's own window can hand over a port
 *   ([WebFrameRouter]), one a document: in a demo's iframe, only a document with the secret, which its shim holds. A
 *   `Url` page runs no shim, so a port one of its own documents hands over speaks as the origin the browser names, and
 *   carries bridge messages only. Input, the keyboard, a paint, a link and a document's leaving count only as a shim
 *   with the secret tells of them ([fromShim]). [postMessage] posts on the current document's port, only if that
 *   document's origin is the one asked for. The port dies with its document, so nothing can reach the next one.
 * - **Links.** A link the user clicks in a shimmed page reaches [client] as a user-gesture navigation (or a new
 *   window), and the runtime opens it outside when the policy says so. Only while the app's window has transient
 *   activation, which the click in the frame gives it: the shim's port is private ([WebShim]), and this holds even if
 *   it weren't. The web can't navigate a sandboxed page for the app, so `Allow` and `OpenHere` leave it where it is.
 * - **Pages of another site** run in a renderer process each, so only [WebEngineDecisions.MAX_LIVE_URL] engines show
 *   one at a time ([UrlPageSlots]). Over that, a page off screen gives its slot up and loads afresh when shown again,
 *   and a page with no slot waits for one behind the runtime's cover.
 * - **Scroll chaining.** A wheel or drag over the page goes to the page alone: the app's list under it never sees it.
 *   So what the user's input couldn't scroll in a shimmed page comes over its port ([WebShim]), and [Surface] passes it
 *   on through Compose's nested scroll, as a child that scrolled would: the list around the view scrolls, and flings on
 *   a drag's release. Only the port of the current document's shim is heard, and only the shim can send on it: it
 *   captured the built-ins it uses before any page script ran, so a page can't take the port or inflate the user's
 *   deltas. A frame's worth is capped at [MAX_SCROLL_PER_FRAME_CSS] (a fling at [MAX_FLING_CSS]) all the same.
 * - **Parking** (contract **Parking**). Hidden, or with no surface while its page is parked for the next view of it, the
 *   frame stays in its layer, laid out where it last was, with `visibility: hidden` ([WebFrame]): the iframe never
 *   leaves, so its document stays, and its viewport keeps its size, so the page doesn't reflow. Adopted, the engine
 *   replays the commit, and the first paint once the frame, displayed again and covered, has drawn for two frames
 *   ([replayState]): a hidden frame draws nothing. A page of another site that gave its slot up off screen has
 *   nothing to replay: it loads afresh when it gets one back.
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
    /**
     * Whether the app's window has transient activation now: a click or a key press in the app, or in one of its
     * frames, in the last few seconds. Tests, which can't make one, pass their own; the factory never does.
     */
    private val userActivation: () -> Boolean = ::hasTransientActivation,
) : WebEngine, FrameRoutee, UrlSlotUser {
    /** One document the engine started, in its own iframe, and what that iframe went on to show. */
    private inner class Navigation(val id: Int, val content: WebContent, val setup: PageSetup, val refusal: Refusal?) {
        val iframe: HTMLElement = newElement("iframe")
        val secret: String = randomHex()
        var loads = 0

        /**
         * The document has something drawn to show: its first contentful paint, or its DOM and two frames, as its shim
         * reports; or its load; or [PAINT_CAP_MILLIS] since it started. Its first paint waits for this.
         */
        var ready = false

        /**
         * The documents of this navigation seen so far, by their handshake or their load. The first is the content's;
         * one that never speaks commits as its URL only if it is the first ([silentUrl]).
         */
        var seen = 0

        /** The document that loaded last, numbered as [seen], which may still speak: its commit waits for it. 0 when none. */
        var awaiting = 0

        /**
         * The document that spoke last did so before its own load, which is still to come: that load is its own, and no
         * new document's. It ends when the document leaves ([pageGone]), so a load after that is a new document's.
         */
        var ownLoadPending = false

        /**
         * The current document handed over its port: one handshake per document, so a page can't flood the buffer with
         * commits. A new document (its load, or the leaving of the one before, as its shim tells) may hand over one again.
         */
        var spoke = false

        /** Whether a commit of this navigation was pushed: its first paint waits for one. */
        var committed = false
        var painted = false

        /** Its frame shows a frame from now ([painted]): it has drawn in its place, unseen, and that drawing shows. */
        var revealing = false

        /** What the last commit of this navigation reported, for [replayState]. */
        var committedUrl: String? = null

        /** A replay's first paint waits for the frame to be displayed again ([replayState]). */
        var replayPaint = false

        /** A page of another site, which needs a slot of [UrlPageSlots] to show. */
        val crossSite: Boolean get() = content is WebContent.Url && refusal == null
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

    /**
     * A document that spoke: its port, the origin the engine vouches for, its number in its navigation, and whether it
     * presented the secret, so that its port is the shim's ([fromShim]).
     */
    private class Page(val port: JsAny, val origin: String, val number: Int, val shim: Boolean)

    /** Why the web can't show some content, and what the page says instead. */
    private class Refusal(val error: WebError, val message: String)

    override val mode: EmbeddingMode get() = EmbeddingMode.NativeView

    /**
     * The common buffer every engine uses (contract **Buffering**). Each navigation is an epoch of it: [start] and
     * [reset] begin one, and [push] drops what an earlier navigation's callbacks still report. A page that floods its
     * bridge while parked goes the way of a crash: discarded, never adopted with less said.
     */
    private val queue = EngineEventQueue(onOverflow = ::crash)
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

    /**
     * A page of another site without a slot of [UrlPageSlots]: one that waits for its first, or one that gave its slot
     * up off screen, which loads afresh when it gets one back.
     */
    private var parked: Navigation? = null

    /**
     * Off screen until the runtime says otherwise ([setVisible]): a spare, a prewarming engine or a view not shown yet
     * never takes the slot of a page of another site from an engine that has one ([UrlPageSlots]).
     */
    override var visible: Boolean = false
        private set

    override val hasSurface: Boolean get() = frame.hasSurface

    private var focused = false
    private var lastOpenAt = Double.NEGATIVE_INFINITY

    /** When the app last asked for the page's focus ([requestFocus]), and when the user last pressed in the page. */
    private var focusRequestedAt = Double.NEGATIVE_INFINITY
    private var pressedAt = Double.NEGATIVE_INFINITY

    /** When a focus the page took was last given back ([checkFocus]): a press told just after it is the focus's cause. */
    private var refusedAt = Double.NEGATIVE_INFINITY

    /** The page's shim said the keyboard left it since it was last given back ([makeSureItLeft]). */
    private var keyboardLeft = true

    /** The nested scroll of the surface that shows the engine now: where the page's left-over scroll goes. */
    private var scrollDispatcher: NestedScrollDispatcher? = null

    /** Left-over scroll from the page since the last frame, in CSS px, and whether a frame will pass it on. */
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private var scrollQueued = false

    init {
        frame.onBroken = ::crash
        frame.onDisplayed = {
            current?.let { navigation ->
                afterNextFrame {
                    painted(navigation)
                    repainted(navigation)
                }
            }
        }
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

    /**
     * Hidden (off screen, or parked for the next view of it), the frame stays laid out in its layer with
     * `visibility: hidden`: the page keeps its document and its viewport, takes no clicks, and the browser throttles it.
     * A page of another site may give its slot up then, to a page on screen ([UrlPageSlots]).
     */
    override fun setVisible(visible: Boolean) {
        if (!alive()) return
        frame.shown = visible
        this.visible = visible
        // Off screen, a page of another site may give its slot to one on screen; back on, a parked one asks again.
        UrlPageSlots.visibilityChanged(this)
        if (visible && parked != null && UrlPageSlots.take(this)) slotGranted()
    }

    /**
     * Tells a new subscriber what the page is now ([WebEngine.replayState]): its last commit and history, and its first
     * paint, as for a document the runtime starts: the frame, displayed again, draws covered for a frame and shows the
     * next ([repainted]). A frame that was hidden drew nothing meanwhile. Titles and sizes aren't the engine's to tell: a
     * cross-origin page's can't be read, and the shim reports sizes, again when the page is asked to announce itself.
     * Nothing for a page that never committed, or gave its slot up and will load afresh.
     */
    override fun replayState() {
        if (!alive()) return
        val navigation = current ?: return
        if (navigation.gone || !navigation.committed) return
        push(navigation, EngineEvent.UrlChanged(navigation.committedUrl))
        push(navigation, EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false))
        if (!navigation.painted || navigation.refusal != null) return
        navigation.replayPaint = true
        frame.covered = true
        if (frame.displayed) afterNextFrame { repainted(navigation) }
    }

    /**
     * A replayed page's frame has been displayed, covered, for a frame: it shows the next one, which the page has drawn
     * in its place, and says so ([replayState]).
     */
    private fun repainted(navigation: Navigation) {
        if (current !== navigation || navigation.gone || !navigation.replayPaint || !frame.displayed || !alive()) return
        navigation.replayPaint = false
        afterNextFrame {
            if (current !== navigation || navigation.gone || !alive()) return@afterNextFrame
            if (!frame.displayed) {
                // Hidden again meanwhile: shown again, the frame repaints ([WebFrame.onDisplayed]).
                navigation.replayPaint = true
                return@afterNextFrame
            }
            frame.covered = false
            push(navigation, EngineEvent.FirstPaint)
        }
    }

    override fun requestFocus() {
        if (!alive()) return
        val navigation = current?.takeIf { !it.gone } ?: return
        focusRequestedAt = nowMillis()
        focusElement(navigation.iframe)
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
        parked = null
        UrlPageSlots.give(this)
        setup = null
        client = null
        // Back in the pool, off screen until a new lease's view says otherwise.
        visible = false
        focused = false
        lastOpenAt = Double.NEGATIVE_INFINITY
        focusRequestedAt = Double.NEGATIVE_INFINITY
        pressedAt = Double.NEGATIVE_INFINITY
        refusedAt = Double.NEGATIVE_INFINITY
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
        parked = null
        UrlPageSlots.give(this)
        client = null
        frame.remove()
        WebFrameRouter.unregister(this)
    }

    // FrameRoutee

    override fun owns(source: JsAny?): Boolean {
        val navigation = current ?: return false
        if (navigation.gone) return false
        // A frame not in the page yet, or whose page waits for a slot, has no window: a source that is null owns nothing.
        val window = windowOf(navigation.iframe) ?: return false
        return same(source, window)
    }

    override fun onHandshake(origin: String, secret: String, text: String, port: JsAny) {
        val navigation = current
        if (navigation == null || navigation.gone || navigation.refusal != null || !alive()) {
            closePort(port)
            return
        }
        val content = navigation.content
        // The secret is only in the copies of the shim the engine wrote, where no page script can read it, and each copy
        // hands over one port: this is a new document, even if the one before hasn't said yet that it left (the two come
        // by different routes).
        val shim = content is WebContent.Html && secret == navigation.secret
        // A demo's documents speak through that shim only. Any other handshake from its frame is a document the demo
        // navigated to, which has no shim: its port is closed, and nothing is queued for it, so it can neither tell of
        // input nor flood the buffer with commits. A `Url` page runs no shim: a document of its own may hand over one
        // port, which carries bridge messages only ([fromShim]); more get their ports closed.
        if (!shim && (content is WebContent.Html || navigation.spoke)) {
            closePort(port)
            return
        }
        if (navigation.spoke) left(navigation)
        navigation.spoke = true
        if (navigation.awaiting != 0) {
            // Chrome's order for a small page: the document that loaded last hands over its port, within its grace.
            navigation.awaiting = 0
        } else {
            // Before its own load, as a page with a script, font or image to fetch does, at any load of the navigation:
            // a document of its own, whose load is still to come. (Or after its grace ran out, and it committed as
            // silent: it commits again now, before its ready, and its leaving ends the wait for a load.)
            navigation.seen++
            navigation.ownLoadPending = true
        }
        val documentOrigin = when {
            origin != OPAQUE -> origin
            content is WebContent.Html && shim -> content.origin.origin
            else -> OPAQUE
        }
        navigation.page?.let { closePort(it.port) }
        navigation.documents++
        val page = Page(port, documentOrigin, navigation.documents, shim)
        navigation.page = page
        commit(navigation, commitUrl(navigation, documentOrigin))
        listenOnPort(
            port,
            onText = { fromPage(navigation, page, it) },
            onOpen = { url, newWindow -> openRequested(navigation, page, url, newWindow) },
            onScroll = { dx, dy -> scrolledInPage(navigation, page, dx, dy) },
            onFling = { vx, vy -> flungInPage(navigation, page, vx, vy) },
            onPress = { pressedInPage(navigation, page) },
            onFocus = { has -> pageFocus(navigation, page, has) },
            onGone = { pageGone(navigation, page) },
            onPaint = { paintedInPage(navigation, page) },
        )
        fromPage(navigation, page, text)
    }

    /**
     * Whether a notice on [page]'s port is the current document's shim's, the one the engine wrote, which presented the
     * secret. Only the shim tells of the user's input (presses, links, what a wheel or drag left over, a fling), of the
     * keyboard, of a first paint and of its document leaving: it tells only of what the browser made, and no page script
     * can send on its port. A port that a `Url` page's own document handed over carries bridge messages only.
     */
    private fun fromShim(navigation: Navigation, page: Page): Boolean =
        page.shim && current === navigation && navigation.page === page && !navigation.gone && alive()

    /**
     * The document that spoke is leaving (its shim's `pagehide`): its port goes, a new document may hand over one, and a
     * load still due to it will never come, so the next is a new document's.
     */
    private fun pageGone(navigation: Navigation, page: Page) {
        if (!fromShim(navigation, page)) return
        left(navigation)
    }

    /** The document that spoke is gone, with its port. */
    private fun left(navigation: Navigation) {
        navigation.page?.let { closePort(it.port) }
        navigation.page = null
        navigation.spoke = false
        navigation.ownLoadPending = false
    }

    /**
     * The app's document says keyboard focus moved. Into the page, it counts only if the user gave it ([gained]). Out of
     * it, only for a page that runs no shim, even one whose document handed over a port of its own: one that does says
     * so itself ([pageFocus]), and the app's `activeElement` can lag behind a move between frames, or into one while the
     * app's window isn't focused.
     */
    override fun checkFocus() {
        if (!alive()) return
        val navigation = current?.takeIf { !it.gone }
        if (navigation != null && isActiveElement(navigation.iframe)) gained(navigation) else if (navigation?.page?.shim != true) setFocused(false)
    }

    /** The page's shim says the keyboard came to it ([has]) or left it. */
    private fun pageFocus(navigation: Navigation, page: Page, has: Boolean) {
        if (!fromShim(navigation, page)) return
        if (has) {
            gained(navigation)
        } else {
            keyboardLeft = true
            setFocused(false)
            // Maybe to another frame, which the app's document shows only a little later, and says nothing of.
            WebFrameRouter.lookAgainSoon()
        }
    }

    /**
     * The keyboard is in the page. It counts if the user gave it ([focusInvited]); a page that took it on its own gives
     * it back at once, and nothing is reported: keys the user types in the app, or in another page, would land in it.
     */
    private fun gained(navigation: Navigation) {
        if (focused) return
        if (focusInvited(navigation)) {
            WebFrameRouter.focusedIn(navigation.iframe)
            setFocused(true)
        } else {
            refusedAt = nowMillis()
            keyboardLeft = false
            WebFrameRouter.giveFocusBack(navigation.iframe)
            if (navigation.page?.shim == true) makeSureItLeft(navigation, GIVE_BACK_TRIES)
        }
    }

    /**
     * Chrome doesn't always act on a give-back while the frame's process has the keyboard (web-check's focus mode: a demo
     * taking it every 150 ms kept it after one such miss), and the page's later `focus()` calls, on what it has focused
     * already, tell nobody anything. Its shim says when the keyboard leaves it ([keyboardLeft]): until it does, the
     * keyboard is given back again, outside any event, [tries] times more.
     */
    private fun makeSureItLeft(navigation: Navigation, tries: Int) {
        afterMillis(GIVE_BACK_CHECK_MILLIS) {
            if (keyboardLeft || focused || current !== navigation || navigation.gone || !alive()) return@afterMillis
            WebFrameRouter.giveFocusBack(navigation.iframe)
            if (tries > 1) makeSureItLeft(navigation, tries - 1)
        }
    }

    private fun setFocused(now: Boolean) {
        if (now == focused) return
        focused = now
        queue.offer(EngineEvent.FocusChanged(now))
    }

    /**
     * Whether the user gave the page the focus it has now: the app asked for it ([requestFocus]), the user pressed Tab
     * in the app just now, or pressed in the page more recently than anywhere in the app; or another page took it, and
     * it was given back. The app's window has transient activation while the user types in it (a key press activates),
     * so that can't tell the user's focus from a page's; only a page of a kind that runs no shim, which can't tell of
     * presses, is judged by it. A page without scripts can't take focus at all.
     */
    private fun focusInvited(navigation: Navigation): Boolean {
        val now = nowMillis()
        if (WebFrameRouter.gaveFocusBackTo(navigation.iframe)) return true
        if (now - focusRequestedAt < FOCUS_INVITE_MILLIS) return true
        if (now - WebFrameRouter.tabAt < TAB_FOCUS_MILLIS) return true
        if (pressedAt > WebFrameRouter.ownInputAt && now - pressedAt < FOCUS_INVITE_MILLIS) return true
        if (mayRunShim(navigation)) return false
        return !navigation.setup.javaScriptEnabled || userActivation()
    }

    /**
     * The user pressed in the current document, as its shim saw. A focus the page took just before, given back for want
     * of a press, was the user's after all (the press can come after the focus): the page gets it again.
     */
    private fun pressedInPage(navigation: Navigation, page: Page) {
        if (!fromShim(navigation, page)) return
        pressedAt = nowMillis()
        if (pressedAt - refusedAt < LATE_PRESS_MILLIS) {
            refusedAt = Double.NEGATIVE_INFINITY
            // Still in the page if it couldn't be taken out: the press decides now.
            if (isActiveElement(navigation.iframe)) gained(navigation) else focusElement(navigation.iframe)
        }
    }

    // Documents

    private fun start(content: WebContent, setup: PageSetup, holdPaint: Boolean, refusal: Refusal? = refusalFor(content)) {
        val previous = current
        // The page that had the keyboard goes with its port, before it could say so.
        setFocused(false)
        val id = queue.advance()
        endHolding()
        parked = null
        if (holdPaint && previous != null && previous.painted && !previous.gone) {
            // Paint holding (contract **Painting**): the old page stays on screen, deaf, until the new one paints.
            previous.page?.let { closePort(it.port) }
            previous.page = null
            holding = previous
        } else {
            previous?.end()
            frame.covered = true
        }
        val navigation = Navigation(id, content, setup, refusal)
        current = navigation
        push(navigation, EngineEvent.Progress(PROGRESS_STARTED))
        if (!navigation.crossSite) {
            UrlPageSlots.give(this)
        } else if (!UrlPageSlots.take(this)) {
            // Over the budget for pages of another site: it waits, covered, for a slot (slotGranted).
            parked = navigation
            return
        }
        show(navigation)
    }

    // UrlSlotUser

    override fun slotGranted() {
        val navigation = parked ?: return
        parked = null
        if (current !== navigation || !alive()) return
        // One that gave its page up off screen loads afresh, under its view's cover (told again in this navigation, as
        // the PaintLost of the last may have gone with its epoch); one that waited for its first shows now.
        if (navigation.gone) {
            start(navigation.content, setup ?: navigation.setup, holdPaint = false)
            current?.let { push(it, EngineEvent.PaintLost) }
        } else {
            show(navigation)
        }
    }

    /**
     * Its slot goes to a page on screen, as its own view is off screen: the page goes, and its view is covered again
     * ([EngineEvent.PaintLost]), so back on screen it shows its placeholder until the page loads afresh.
     */
    override fun yieldSlot() {
        val navigation = current ?: return
        setFocused(false)
        endHolding()
        navigation.end()
        parked = navigation
        frame.covered = true
        push(navigation, EngineEvent.PaintLost)
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
        if (refusal == null) afterMillis(PAINT_CAP_MILLIS) { paintCapReached(navigation) }
    }

    /**
     * [PAINT_CAP_MILLIS] since the document started, and it hasn't shown: it shows now, as on iOS, rather than keep its
     * view covered while a request hangs. A page of another site can't be seen to commit before its load: one that hasn't
     * loaded commits now as its first document, as yet unread, whose load is then its own.
     */
    private fun paintCapReached(navigation: Navigation) {
        if (current !== navigation || navigation.gone || navigation.ready || !alive()) return
        if (!navigation.committed) {
            navigation.seen++
            navigation.ownLoadPending = true
            commit(navigation, silentUrl(navigation, navigation.seen))
        }
        readyToPaint(navigation)
    }

    /** The current document has something drawn to show: its first paint follows once its frame shows ([painted]). */
    private fun readyToPaint(navigation: Navigation) {
        if (navigation.ready) return
        navigation.ready = true
        if (!navigation.painted) afterNextFrame { painted(navigation) }
    }

    /** The page's shim says it has painted its content, or its DOM is there and two frames have passed. */
    private fun paintedInPage(navigation: Navigation, page: Page) {
        if (!fromShim(navigation, page)) return
        readyToPaint(navigation)
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
        if (navigation.crossSite) {
            // Defence in depth: a page of another site that reached the app's own origin (a redirect, or a navigation of
            // its own) could script the app, and its scripts have run by its load. Its frame goes, and the view says why.
            val inside = sameOriginDocumentUrl(navigation.iframe)
            if (inside != null) {
                start(navigation.content, navigation.setup, holdPaint = false, Refusal(WebError.BlockedByPolicy(inside), APP_ORIGIN_MESSAGE))
                return
            }
        }
        navigation.loads++
        navigation.ready = true
        if (navigation.refusal == null) {
            if (navigation.ownLoadPending) {
                // The document that spoke before its own load: it committed at its handshake.
                navigation.ownLoadPending = false
            } else {
                // A new document. The one before is gone, with its port, and if it was still due to speak, it didn't.
                settleSilent(navigation)
                left(navigation)
                val number = ++navigation.seen
                if (mayRunShim(navigation)) {
                    navigation.awaiting = number
                    afterMillis(HANDSHAKE_GRACE_MILLIS) { if (navigation.awaiting == number) settleSilent(navigation) }
                } else {
                    commit(navigation, silentUrl(navigation, number))
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

    /** The document [Navigation.awaiting] never spoke: it commits as one that can't be read, but the first. */
    private fun settleSilent(navigation: Navigation) {
        val number = navigation.awaiting
        if (number == 0 || current !== navigation || navigation.gone || !alive()) return
        navigation.awaiting = 0
        commit(navigation, silentUrl(navigation, number))
    }

    /**
     * What the [number]th document of [navigation], one that never spoke, commits as: the content's URL if it is the
     * first, else unreadable.
     */
    private fun silentUrl(navigation: Navigation, number: Int): String? = if (number == 1) urlOf(navigation.content) else null

    /**
     * The document loaded, committed, and the frame has been displayed, covered, for a frame: the page has drawn in its
     * place, unseen. It shows the next frame ([revealed]), or its refusal does. A refusal has no commit.
     */
    private fun painted(navigation: Navigation) {
        if (current !== navigation || navigation.gone || navigation.painted || navigation.revealing) return
        if (!navigation.ready || !frame.displayed || !alive()) return
        if (navigation.refusal == null && !navigation.committed) return
        navigation.revealing = true
        afterNextFrame { revealed(navigation) }
    }

    /** A frame after [painted]: the page, or its refusal, shows, and says so. */
    private fun revealed(navigation: Navigation) {
        navigation.revealing = false
        if (current !== navigation || navigation.gone || navigation.painted || !frame.displayed || !alive()) return
        val refusal = navigation.refusal
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
        navigation.committedUrl = url
        push(navigation, EngineEvent.UrlChanged(url))
        push(navigation, EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false))
        if (!navigation.committed) {
            navigation.committed = true
            // The first paint waited for this commit.
            if (navigation.ready && !navigation.painted) afterNextFrame { painted(navigation) }
        }
    }

    /**
     * What a document that spoke with [documentOrigin] commits as. A `Url` page's first document, the one that loaded
     * first or hasn't loaded yet, commits as its URL when it speaks as that URL's origin.
     */
    private fun commitUrl(navigation: Navigation, documentOrigin: String): String? = when (val content = navigation.content) {
        is WebContent.Html -> if (documentOrigin == content.origin.origin) content.origin.url else null
        is WebContent.Url ->
            if (navigation.documents == 1 && navigation.loads <= 1 && documentOrigin == browserOriginOf(content.url)) content.url else null
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
        // A shim's document speaks as its content's origin, never as the opaque one.
        if (!fromShim(navigation, page)) return
        // A user's click in the frame gives the app's window transient activation too (its ancestors are activated
        // with it). A request that comes with none isn't a user's, however it reached the port.
        if (!userActivation()) return
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
        if (!fromShim(navigation, page)) return
        // Capped as they come: past a Float's range, two opposite deltas would add up to NaN, which the cap lets by.
        pendingScrollX += dx.coerceIn(-MAX_SCROLL_PER_FRAME_CSS.toDouble(), MAX_SCROLL_PER_FRAME_CSS.toDouble()).toFloat()
        pendingScrollY += dy.coerceIn(-MAX_SCROLL_PER_FRAME_CSS.toDouble(), MAX_SCROLL_PER_FRAME_CSS.toDouble()).toFloat()
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
        // Only finite deltas reach Compose, whatever they added up to.
        val dx = if (pendingScrollX.isFinite()) pendingScrollX.coerceIn(-MAX_SCROLL_PER_FRAME_CSS, MAX_SCROLL_PER_FRAME_CSS) else 0f
        val dy = if (pendingScrollY.isFinite()) pendingScrollY.coerceIn(-MAX_SCROLL_PER_FRAME_CSS, MAX_SCROLL_PER_FRAME_CSS) else 0f
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
        if (!fromShim(navigation, page)) return
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
            // The browser's own parser decides: `:0443` or `%61pp` is the app's origin all the same.
            browserOriginOf(content.url) == pageOrigin() ->
                Refusal(WebError.BlockedByPolicy(content.url), APP_ORIGIN_MESSAGE)
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
        parked = null
        UrlPageSlots.give(this)
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
