package dev.supermux.webview.engine

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import dev.supermux.webview.ConsoleMessage
import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebError
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.flow.Flow

/** How an engine reaches the screen (spec §7). */
enum class EmbeddingMode {
    /** The engine's own native view, positioned by Compose. */
    NativeView,
    /** The engine renders off screen; its [WebEngine.Surface] draws the frames and forwards input. */
    Frames,
}

/**
 * What an engine is created with: only what some engine can't change after construction (the profile everywhere,
 * WebKit's media playback policy). Engines are pooled per distinct config. Everything else is set per lease, through
 * [WebEngine.configure].
 */
data class EngineConfig(val profile: WebProfile, val mediaPlaybackRequiresGesture: Boolean = true)

/**
 * What one lease sets on its engine, through [WebEngine.configure]: resolved, per-lease values only. Nothing
 * construction-time ([EngineConfig]) is here, and no field can contradict another.
 * @property javaScriptEnabled whether the page runs scripts. Without them, no document gets the bridge shim.
 * @property inspectable exposes the page to the platform inspector (Safari, chrome://inspect, Edge DevTools).
 * @property dark the resolved theme: what `prefers-color-scheme` reports to the page.
 * @property transparentBackground whether the engine leaves the page's background transparent.
 * @property userAgentSuffix appended to the engine's user agent, or null.
 * @property bridgeOrigins the origins whose documents get the bridge shim.
 */
data class PageSetup(
    val javaScriptEnabled: Boolean,
    val inspectable: Boolean,
    val dark: Boolean,
    val transparentBackground: Boolean,
    val userAgentSuffix: String?,
    val bridgeOrigins: Set<String>,
)

/**
 * The per-lease part of these settings, with the values the runtime resolved: [dark] from [WebSettings.darkMode] and
 * the system theme, and [bridgeOrigins] from [WebSettings.bridgeOrigins] plus the content's local origin.
 */
internal fun WebSettings.pageSetup(dark: Boolean, bridgeOrigins: Set<String>): PageSetup = PageSetup(
    javaScriptEnabled = javaScriptEnabled,
    inspectable = inspectable,
    dark = dark,
    transparentBackground = transparentBackground,
    userAgentSuffix = userAgentSuffix,
    bridgeOrigins = bridgeOrigins,
)

sealed interface WebEngineResult {
    data class Created(val engine: WebEngine) : WebEngineResult

    /** No engine, because of [reason]. [detail] says more, for logs: a missing library, an exception's message… */
    data class Unavailable(val reason: UnavailableReason, val detail: String? = null) : WebEngineResult
}

/**
 * Creates engines for one platform. Called on the main thread.
 *
 * [create] may suspend, because WebView2 and WPE start asynchronously. It never throws: failures are
 * [WebEngineResult.Unavailable]. The pool calls it outside its lock, and guards it anyway.
 */
interface WebEngineFactory {
    suspend fun create(config: EngineConfig): WebEngineResult
}

/** A [WebEngineFactory] that runs [create], so call sites can write `WebEngineFactory { config -> … }`. */
fun WebEngineFactory(create: suspend (EngineConfig) -> WebEngineResult): WebEngineFactory = LambdaEngineFactory(create)

private class LambdaEngineFactory(private val block: suspend (EngineConfig) -> WebEngineResult) : WebEngineFactory {
    override suspend fun create(config: EngineConfig): WebEngineResult = block(config)
}

/**
 * The page-initiated requests an engine asks about. Supplied by the runtime while the engine is leased.
 * - [onNavigation] and [onNewWindow] answer synchronously, as Android and WKWebView need. They may be called on the
 *   engine's own UI thread (see [WebEngine]), so they answer fast, thread-safe and without blocking.
 * - The rest may suspend.
 */
interface EngineClient {
    fun onNavigation(request: NavigationRequest): NavigationDecision
    suspend fun onDialog(dialog: WebDialog): DialogResult
    suspend fun onFileChooser(request: FileChooserRequest): List<String>?
    suspend fun onPermission(request: PermissionRequest): Boolean
    suspend fun onDownload(request: DownloadRequest): Boolean
    fun onNewWindow(request: NewWindowRequest): NewWindowDecision
}

sealed interface EngineEvent {
    /**
     * A main-frame commit or a same-document URL change, in the terms the [WebEngine] contract sets. [url] is null
     * only for a commit whose URL the engine can't read, which is opaque; a same-document change always has its URL.
     */
    data class UrlChanged(val url: String?) : EngineEvent
    data class TitleChanged(val title: String?) : EngineEvent
    /** Load progress in 0..1, or null when idle. */
    data class Progress(val progress: Float?) : EngineEvent
    data class HistoryChanged(val canGoBack: Boolean, val canGoForward: Boolean) : EngineEvent
    /** The current document's first frame is on screen. */
    data object FirstPaint : EngineEvent
    /** The main frame failed to load. Subframe and subresource failures are not reported. */
    data class Error(val error: WebError) : EngineEvent
    /** A console message the engine captured natively. Pages running the shim report through [PageMessage]. */
    data class Console(val message: ConsoleMessage) : EngineEvent
    /**
     * A bridge message from the main frame's document: [json] in the bridge's JSON wire format, from [origin], the
     * engine's report of that document's origin (see **Origins** in the [WebEngine] contract).
     */
    data class PageMessage(val origin: String, val json: String) : EngineEvent
    /**
     * The document's size in CSS pixels, if the engine measures it natively. [viewportHeightCss] is the height of the
     * engine's viewport in CSS pixels, where the engine knows it. With it, the runtime catches a page that grows with
     * its own viewport (`100vh` plus a margin), which would otherwise grow a WrapContentHeight view to its max.
     *
     * Engines that inject the shim don't also report sizes natively: the shim reports them, and two sources that
     * measure the same page a little differently look like a page that keeps changing on its own.
     */
    data class ContentSize(val widthCss: Float, val heightCss: Float, val viewportHeightCss: Float? = null) : EngineEvent
    /**
     * Keyboard focus entered ([focused]) or left the page. Engines report it, because Compose never sees pointer
     * events over native views or iframes.
     */
    data class FocusChanged(val focused: Boolean) : EngineEvent
    /** The renderer died. The engine is unusable and will be destroyed, not recycled. */
    data object Crashed : EngineEvent
}

/**
 * One live browser engine (spec §3). Engines are pooled and reused.
 *
 * Contract:
 * - "Main" is the thread of the runtime's `Dispatchers.Main`. The runtime calls every member there. Events may be
 *   emitted from any thread.
 * - [EngineClient.onNavigation] and [EngineClient.onNewWindow] answer synchronously and may be called on the engine's
 *   own UI thread (on desktop, AppKit's or WebView2's, not Compose's). Answers must be fast, thread-safe and
 *   non-blocking; side effects hop to main.
 * - The runtime subscribes to [events] before [load], [restoreState] or [replayState]. Events emitted with no subscriber
 *   may be lost.
 * - **Documents.** Once [load], [reload] or [restoreState] returns, no event caused by an earlier document is
 *   delivered, even one already buffered: the runtime may show other content now, and another party's page must not
 *   write into its title, size or bridge. Engines filter by navigation (a navigation id, or the document each message
 *   came from), not by URL, which two documents can share. [EngineEvent.Crashed] is the engine's, not a document's.
 * - **Buffering.** A subscriber may fall behind, so engines buffer events:
 *   - [EngineEvent.UrlChanged], [EngineEvent.FirstPaint], [EngineEvent.Error], [EngineEvent.Crashed],
 *     [EngineEvent.FocusChanged], [EngineEvent.HistoryChanged] and a page's `$ready` are never dropped.
 *   - Repeated `$ready`s from one document, and latest-value events ([EngineEvent.TitleChanged],
 *     [EngineEvent.Progress], [EngineEvent.ContentSize]), may be coalesced to the latest, but never dropped entirely.
 *     A page flooding its own `$ready`s can't then grow the buffer.
 *   - Engines detect a `$ready` the way the bridge does, by its prefix, without parsing it: a page message of at most
 *     [dev.supermux.webview.bridge.WebBridge.READY_MAX_CHARS] characters that starts with
 *     [dev.supermux.webview.bridge.WebBridge.READY_PREFIX].
 *   - Under a flood, engines may drop or coalesce other page messages and console output before they buffer them, so
 *     the buffer stays bounded.
 * - [EngineEvent.UrlChanged] reports a main-frame commit, before the new document can post a message. It uses the
 *   same terms as message origins: a sandboxed srcdoc document commits as its content's local origin URL.
 * - **Painting.** There must never be a white or blank frame.
 *   - After creation or a reset, the surface stays invisible until [EngineEvent.FirstPaint] for the first document, or
 *     a main-frame [EngineEvent.Error]. The runtime's cover hides it meanwhile.
 *   - Between documents (page-initiated, reload or history navigations) the engine keeps showing the previous document
 *     until the next one paints, as Android WebView and WKWebView already do ("paint holding"). The runtime covers
 *     only the documents it starts itself.
 *   - [EngineEvent.Error] is main-frame only.
 * - Only navigations the page starts go through [client]; [load], [restoreState], back, forward and reload never do.
 *   OpenExternally means Block for the engine, because the runtime opens the URL.
 * - Engines that inject the shim don't also forward native console messages for the same pages.
 * - Certificate errors are never bypassed.
 * - Nothing thrown inside a platform callback may reach the app: failures become events.
 * - [release] destroys the engine.
 * - **Messages.** [EngineEvent.PageMessage] comes only from the main frame's document. Engines drop messages from
 *   subframes (Android `isMainFrame`, WebKit `frameInfo.isMainFrame`). WPE has no `frameInfo`: it uses its script
 *   world and frame APIs.
 * - **Origins.** "Origin" always means the document's origin (`self.origin`), not the URL's (`location.origin`).
 *   - Origins are the engine's report of the document's origin, never the page's: a srcdoc document maps to its
 *     content's local origin.
 *   - An inherited `about:blank` commits as opaque.
 * - **Delivery.**
 *   - [postMessage] checks the document's origin in the delivering script. That check only holds where the script
 *     runs in an isolated world (WebKit content worlds, WPE script worlds): in the page's own world, the page can
 *     redefine `self.origin`.
 *   - Where it runs in the page's own world (WebView2), deliver by executing a call to a receiver installed at
 *     document creation, before any page script, on every document. The receiver can't be replaced, and compares the
 *     origin it captured then, using built-ins captured then. Never deliver through a channel page scripts can listen
 *     to, such as `chrome.webview` message events. Inbound messages are authenticated the same way.
 *   - On the web, delivery uses a per-document `MessagePort`, never `'*'`. The port belongs to the web engine's own
 *     shim variant (Plan 2), which creates it and transfers it with its `$ready`; the common
 *     [dev.supermux.webview.bridge.BridgeShim] creates none.
 *   - Android uses the per-document reply proxy of that document's last message.
 * - **Commits.**
 *   - `UrlChanged(null)` is a main-frame commit whose URL can't be read; the bridge treats it as opaque.
 *   - [EngineEvent.UrlChanged] also reports same-document URL changes (pushState, fragments) where the engine can see
 *     them, and never as `UrlChanged(null)`. Inside a cross-origin iframe they can't be seen.
 *   - An engine that learns of commits late (the web engine learns from the iframe's `load` event, not from
 *     [WebEngine.load]) must not report a commit after that document's own `$ready`.
 * - **After reset.**
 *   - [EngineEvent.HistoryChanged] and back/forward behave as if history began with the next load.
 *   - Ephemeral data is per engine: one engine's reset never touches another live engine's data.
 *   - The runtime never cancels [reset].
 * - **Threads.** Engines call the suspend [EngineClient] members on main. [client] and [isUsable] are main-thread
 *   state. An engine that answers [EngineClient.onNavigation] off main reads [client] through a copy it publishes
 *   safely for that thread.
 * - **Mid-lease configure.** [configure] may be called again mid-lease, for a theme or settings change, and applies
 *   to the current page where the platform allows.
 * - **After release.** Every member is a no-op after [release], because JNI engines must never touch freed native
 *   objects.
 * - **Parking.** When a live view leaves the composition, the runtime may keep its page live for the next view of the
 *   same page (a list item scrolled out and back): it unbinds, sets [client] to null, calls `setVisible(false)`, and its
 *   [Surface] leaves the composition. The engine keeps the document and its state meanwhile, with no subscriber; it may
 *   throttle the page as for any hidden one. Then either a view adopts it, subscribing anew and calling [replayState]
 *   (no [configure] unless the setup changed, no [load]), or the pool resets or releases it as usual.
 */
interface WebEngine {
    val mode: EmbeddingMode
    val events: Flow<EngineEvent>

    /**
     * Answers the page's requests while the engine is leased. With none, every request gets its safe default:
     * navigations and new windows are blocked, dialogs dismissed (beforeunload leaves the page), file choosers
     * cancelled, and permissions and downloads denied.
     */
    var client: EngineClient?

    /** False, for good, once the renderer crashed or the engine was released. The pool checks it before reuse. */
    val isUsable: Boolean

    /**
     * Applies a lease's [setup]. The runtime calls it before every [load] or [restoreState] of a lease, and may call
     * it again mid-lease (a theme or settings change); it applies to the current page where the platform allows.
     */
    fun configure(setup: PageSetup)

    fun load(content: WebContent)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()

    /** Runs [script] in the page and returns its result JSON-encoded, or null. */
    suspend fun evaluate(script: String): String?

    /**
     * Delivers a bridge message to the main frame's shim (`window.__hostBridgeReceive`), only if the main frame's
     * document has origin [targetOrigin] (its `self.origin`) when the message arrives; otherwise drops it. Check at
     * delivery, never against a URL read earlier: use the reply proxy of that document's last message (Android), a
     * per-document `MessagePort` (web), an origin test inside the delivering script in an isolated world (WebKit,
     * WPE), or a receiver installed at document creation (WebView2). See **Delivery** in the contract above.
     */
    fun postMessage(json: String, targetOrigin: String)

    /**
     * The page as it looks now.
     * - Null until [EngineEvent.FirstPaint] for the current document: a page that hasn't painted would give a white
     *   image.
     * - Valid only while [Surface] is in the composition and laid out with a non-zero size: otherwise null, as it is
     *   where a snapshot is impossible (a cross-origin iframe).
     */
    suspend fun snapshot(): ImageBitmap?

    /** Opaque engine state (history, scroll) for [restoreState], or null where unsupported. */
    suspend fun saveState(): ByteArray?

    /**
     * Restores [state] from [saveState] and reloads the saved page, which is [content]'s. While it restores, the
     * engine serves local content ([WebContent.Html], [WebContent.Bundle]) from [content]. Returns false when it
     * can't (the state is unknown or unsupported, or isn't [content]'s); the runtime then loads [content] instead.
     */
    fun restoreState(state: ByteArray, content: WebContent): Boolean

    /**
     * Whether the view is on screen. Engines may throttle a hidden page; frame engines stop capturing. A parked page
     * (see **Parking**) is hidden too, with no [Surface] in the composition: engines keep its document as it is, ready
     * to show again, never reloading it.
     */
    fun setVisible(visible: Boolean) {}

    /**
     * Tells the subscriber what the page is now, as the events it would have heard: [EngineEvent.UrlChanged] for the
     * current document, then [EngineEvent.HistoryChanged], [EngineEvent.TitleChanged] and the latest
     * [EngineEvent.ContentSize], where the engine knows them, and [EngineEvent.FirstPaint] if the current document has
     * painted: at once, or, where the page must be drawn again in its new place first, once it has been. Nothing when
     * there is no document.
     *
     * The runtime calls it after subscribing to [events], when a view adopts a parked page (see **Parking**): the
     * page's events went to the view before it, or to no one, and the replay brings the new view up to date. Its
     * [EngineEvent.FirstPaint] lifts the view's cover. The page isn't navigated or reloaded. The replayed events belong
     * to the current document, and are buffered like any others.
     *
     * Every engine implements it. The default replays nothing, so an adopted page would keep its view's cover.
     */
    fun replayState() {}

    /** Moves keyboard focus into the page. */
    fun requestFocus() {}

    /**
     * Whether the page sits above Compose, so overlays must be cut out of it ([applyOverlays]): the web's iframe, and
     * macOS where its view is above the canvas. Engines that Compose draws over (Android, iOS, frame-mode desktops)
     * return false, and the runtime then computes no cut-outs for them: a live page that moves costs a cut-out
     * computation a frame otherwise. Read once per lease.
     */
    val needsCutouts: Boolean get() = true

    /** Cuts overlays out of a page that sits above Compose. A no-op where Compose already draws on top. */
    fun applyOverlays(cutouts: OverlayCutouts)

    /**
     * Hosts the engine in the composition: its native view, or its frames.
     *
     * When an engine changes hands, the runtime waits for the view that had it to let go of its surface before the
     * next view composes it, so a native view isn't added to a new parent while it still has the old one. The wait is
     * bounded, at 500 ms: a composition that doesn't let go by then (one that stopped recomposing) doesn't keep the
     * engine from the next view, and the Surface may then be composed in its new place before it leaves the old one.
     */
    @Composable
    fun Surface(modifier: Modifier)

    /**
     * Readies the engine for another lease. Returns true only if, once it returns:
     * - no navigation can reach a page from before the reset: the back-forward list is empty, or entries from before
     *   the reset are refused, including the page's own `history.back()`;
     * - no event caused by anything before or during the reset is ever delivered;
     * - client requests still pending were answered with their safe default;
     * - per-lease scripts, message listeners and permission grants are gone;
     * - a [WebProfile.Ephemeral] engine's site data is wiped, without touching any other profile's.
     *
     * Returns false when it can't guarantee all of that; the pool then destroys the engine. Never throws.
     */
    suspend fun reset(): Boolean

    /** Destroys the engine. */
    fun release()
}
