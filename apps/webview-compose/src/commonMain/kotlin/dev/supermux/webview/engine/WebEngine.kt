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
 * What one lease sets on its engine, through [WebEngine.configure].
 * @property dark the resolved theme: what `prefers-color-scheme` reports to the page.
 * @property bridgeOrigins the origins whose documents get the bridge shim.
 */
data class PageSetup(val settings: WebSettings, val dark: Boolean, val bridgeOrigins: Set<String>)

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
    /** A main-frame commit, in the terms the [WebEngine] contract sets. */
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
    /** A bridge message from the page: [json] in the bridge's JSON wire format, from [origin]. */
    data class PageMessage(val origin: String, val json: String) : EngineEvent
    /** The document's size in CSS pixels, if the engine measures it natively. */
    data class ContentSize(val widthCss: Float, val heightCss: Float) : EngineEvent
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
 * - The runtime subscribes to [events] before [load] or [restoreState]. Events emitted with no subscriber may be lost.
 * - [EngineEvent.UrlChanged] reports a main-frame commit, before the new document can post a message. It uses the
 *   same terms as message origins: a sandboxed srcdoc document commits as its content's local origin URL.
 * - The surface stays invisible until [EngineEvent.FirstPaint] for the current document, or a main-frame
 *   [EngineEvent.Error]. The runtime's cover hides it meanwhile; there must never be a white or blank frame.
 *   [EngineEvent.Error] is main-frame only.
 * - Only navigations the page starts go through [client]; [load], [restoreState], back, forward and reload never do.
 *   OpenExternally means Block for the engine, because the runtime opens the URL.
 * - Engines that inject the shim don't also forward native console messages for the same pages.
 * - Certificate errors are never bypassed.
 * - Nothing thrown inside a platform callback may reach the app: failures become events.
 * - [release] destroys the engine.
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

    /** Applies a lease's [setup]. The runtime calls it before every [load] or [restoreState] of a lease. */
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
     * document has origin [targetOrigin] when the message arrives; otherwise drops it. Check at delivery, never
     * against a URL read earlier: use a per-document reply proxy (Android), postMessage's targetOrigin (web), or a
     * `location.origin` test inside the delivering script (WebKit, WebView2, WPE).
     */
    fun postMessage(json: String, targetOrigin: String)

    /**
     * The page as it looks now. Valid only while [Surface] is in the composition and laid out with a non-zero size:
     * otherwise null, as it is where a snapshot is impossible (a cross-origin iframe).
     */
    suspend fun snapshot(): ImageBitmap?

    /** Opaque engine state (history, scroll) for [restoreState], or null where unsupported. */
    suspend fun saveState(): ByteArray?

    /**
     * Restores [state] from [saveState] and reloads the saved page. Returns false when it can't, because the state is
     * unknown or unsupported; the runtime then calls [load] instead.
     */
    fun restoreState(state: ByteArray): Boolean

    /** Whether the view is on screen. Engines may throttle a hidden page; frame engines stop capturing. */
    fun setVisible(visible: Boolean) {}

    /** Moves keyboard focus into the page. */
    fun requestFocus() {}

    /** Cuts overlays out of a page that sits above Compose. A no-op where Compose already draws on top. */
    fun applyOverlays(cutouts: OverlayCutouts)

    /** Hosts the engine in the composition: its native view, or its frames. */
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
