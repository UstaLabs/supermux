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

/** What an engine is created with. Engines are pooled per distinct config. */
data class EngineConfig(val settings: WebSettings, val profile: WebProfile)

sealed interface WebEngineResult {
    data class Created(val engine: WebEngine) : WebEngineResult
    data class Unavailable(val reason: UnavailableReason) : WebEngineResult
}

/** Creates engines for one platform. Called on the main thread. */
fun interface WebEngineFactory {
    fun create(config: EngineConfig): WebEngineResult
}

/**
 * The page-initiated requests an engine asks about. Supplied by the runtime while the engine is leased.
 * [onNavigation] and [onNewWindow] answer synchronously, as Android and WKWebView need. The rest may suspend.
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
    data class UrlChanged(val url: String?) : EngineEvent
    data class TitleChanged(val title: String?) : EngineEvent
    /** Load progress in 0..1, or null when idle. */
    data class Progress(val progress: Float?) : EngineEvent
    data class HistoryChanged(val canGoBack: Boolean, val canGoForward: Boolean) : EngineEvent
    /** The current document's first frame is on screen. */
    data object FirstPaint : EngineEvent
    data class Error(val error: WebError) : EngineEvent
    /** A console message the engine captured natively. Pages running the shim report through [PageMessage]. */
    data class Console(val message: ConsoleMessage) : EngineEvent
    /** A bridge message from the page: [json] in [dev.supermux.webview.bridge.BridgeCodec] format, from [origin]. */
    data class PageMessage(val origin: String, val json: String) : EngineEvent
    /** The document's size in CSS pixels, if the engine measures it natively. */
    data class ContentSize(val widthCss: Float, val heightCss: Float) : EngineEvent
    /** The renderer died. The engine is unusable and will be destroyed, not recycled. */
    data object Crashed : EngineEvent
}

/**
 * One live browser engine (spec §3). Engines are pooled and reused.
 *
 * Contract:
 * - Every member is called on the main thread, and events are emitted on it.
 * - The runtime subscribes to [events] BEFORE calling [load] or [restoreState]; events emitted with no subscriber
 *   may be lost.
 * - A commit ([EngineEvent.UrlChanged]) is reported in the same terms as message origins: a sandboxed srcdoc document
 *   commits as its content's local origin URL.
 * - The surface stays invisible until the engine emits [EngineEvent.FirstPaint] for the current document. The
 *   runtime's snapshot covers it meanwhile; there must never be a white or blank frame.
 * - Only navigations the page starts go through [client]; [load] and [restoreState] never do. OpenExternally means
 *   Block for the engine (the runtime opens the URL).
 * - Nothing thrown inside a platform callback may reach the app: failures become events.
 * - [reset] returns the engine to a blank, client-less state ready for another page. [release] destroys it.
 */
interface WebEngine {
    val mode: EmbeddingMode
    val events: Flow<EngineEvent>
    var client: EngineClient?

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

    /** The page as it looks now, or null where impossible (a cross-origin iframe). */
    suspend fun snapshot(): ImageBitmap?

    /** Opaque engine state (history, scroll) for [restoreState], or null where unsupported. */
    suspend fun saveState(): ByteArray?

    fun restoreState(state: ByteArray)

    /** Cuts overlays out of a page that sits above Compose. A no-op where Compose already draws on top. */
    fun applyOverlays(cutouts: OverlayCutouts)

    /** Hosts the engine in the composition: its native view, or its frames. */
    @Composable
    fun Surface(modifier: Modifier)

    fun reset()

    fun release()
}
