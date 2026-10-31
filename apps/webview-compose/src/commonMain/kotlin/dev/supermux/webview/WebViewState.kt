package dev.supermux.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.supermux.webview.bridge.BridgeShim
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.runtime.PageMemory
import dev.supermux.webview.runtime.SavedPageState
import dev.supermux.webview.runtime.SizeReport
import dev.supermux.webview.runtime.SnapshotKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One logical page (spec §4). It is cheap: it holds what the page shows and remembers, not an engine. A
 * [WebView] leases a live engine for it while it should be live. Show one state in at most one [WebView] at a
 * time. Main thread only.
 */
@Stable
public class WebViewState(initialContent: WebContent) {
    public var content: WebContent by mutableStateOf(initialContent)
        private set
    public var url: String? by mutableStateOf(null)
        internal set
    public var title: String? by mutableStateOf(null)
        internal set
    public var loading: LoadingState by mutableStateOf(LoadingState.Idle)
        internal set
    public var canGoBack: Boolean by mutableStateOf(false)
        internal set
    public var canGoForward: Boolean by mutableStateOf(false)
        internal set
    public var phase: WebViewPhase by mutableStateOf(WebViewPhase.Cold)
        internal set
    public var error: WebError? by mutableStateOf(null)
        internal set

    /**
     * The page's content size in CSS pixels (≈ dp), as the page reported it, each side at most 32,000: Compose can lay
     * that out at any density up to 8. Kept while cold; new content starts without one.
     */
    public var contentSize: DpSize? by mutableStateOf(null)
        internal set

    private val consoleFlow = MutableSharedFlow<ConsoleMessage>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Console output and uncaught errors from the page, while it is live. */
    public val console: SharedFlow<ConsoleMessage> = consoleFlow.asSharedFlow()

    public val bridge: WebBridge = WebBridge(::onReservedEvent)
    public val navigator: WebNavigator = WebNavigator(this)

    /**
     * The engine the owning view has bound, or null. Only the owner's: a view that lost the state, but still holds an
     * engine while it saves, is never reached through the state.
     */
    internal val engine: WebEngine? get() = owner?.engine

    /** The loop detector ([onSize]): the last report, how many growths in a row kept pace with the viewport, the verdict. */
    private var lastSizeReport: SizeReport? = null
    private var growthStreak = 0
    private var growsWithViewport = false

    internal var snapshotKey: SnapshotKey? = null
    internal var savedEngineState: ByteArray? = null

    /** The state the page saved, with the origin of the document that saved it. */
    internal var pageState: SavedPageState? = null
    internal var pageSavesState: Boolean = false
    internal var onNeedsEvaluation: (() -> Unit)? = null

    /**
     * Whose page the remembered fields hold (the snapshot, the engine and page state, the size and the loop verdict),
     * and the shown ones ([url], [title], [error], the history): the resolved profile they were saved under, and the
     * runtime's [dev.supermux.webview.runtime.WebViewRuntime.clear] count then. Null while unknown. A view that shows the
     * state in another profile, or after a clear, starts fresh, and shows nothing of that page meanwhile: profiles are
     * a boundary, and a clear is a logout.
     */
    internal var memoryProfile: WebProfile? = null
    internal var memoryEpoch: Int = 0

    /**
     * Whether a view has shown a page in this state. Page memory is recalled only into a state nothing has been shown in
     * yet: what a [load], a clear or another profile dropped from a state that had a page stays dropped.
     */
    internal var pageShown: Boolean = false

    /**
     * The view that owns this state: the only one that writes into it, uses its bridge or reaches its engine. A view
     * takes it before its first document; one that has lost it (the state moved to another view while it was still
     * saving) leaves the state alone.
     */
    internal var owner: StateOwner? = null

    /**
     * Counts [load]s. The WebView navigates whenever it changes, even to the content already shown: after in-page
     * navigation the engine shows another page, and loading the content must go back to it.
     */
    internal var loadGeneration: Int = 0
        private set

    /**
     * Shows [newContent]: the WebView navigates to it, even when it is the content already shown, whose page may have
     * navigated elsewhere since.
     * - The bridge leaves the old document at once: what the app sends from here on waits for the new content's
     *   document, and never reaches the page being replaced ([WebBridge]'s **Loads**). Its handlers and listeners stay.
     * - What the old document showed goes (URL, title, history, progress, error), so a cold view's placeholder and back
     *   handling don't show the old page.
     * - So does what it saved for a new document to restore (engine and page state, and its save provider). A load
     *   starts afresh.
     * - Other content (another key) also loses the old page's snapshot, size and loop verdict. The same page keeps
     *   them, so its snapshot covers it at its height until the new document paints.
     */
    public fun load(newContent: WebContent) {
        val samePage = newContent.key == content.key
        bridge.detach()
        content = newContent
        loadGeneration++
        forgetShown()
        savedEngineState = null
        pageState = null
        pageSavesState = false
        if (!samePage) {
            contentSize = null
            snapshotKey = null
            lastSizeReport = null
            growthStreak = 0
            growsWithViewport = false
        }
        if (phase is WebViewPhase.Unavailable || phase == WebViewPhase.Crashed) phase = WebViewPhase.Cold
        // The WebView navigates: new content may need another engine config, and always a new setup.
        onNeedsEvaluation?.invoke()
    }

    /** Applies what the engine reported. The WebView passes it each of its engine's events, in order. */
    internal fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.UrlChanged -> {
                url = event.url
                // An error is about the URL that failed: the failed page's own commit keeps it, any other clears it.
                val failed = error
                if (failed != null && failed.url != event.url) error = null
                bridge.documentCommitted(event.url)
            }
            is EngineEvent.TitleChanged -> title = event.title
            is EngineEvent.Progress -> {
                val progress = event.progress
                if (progress == null) {
                    loading = LoadingState.Idle
                } else if (!progress.isNaN()) {
                    loading = LoadingState.Loading(progress.coerceIn(0f, 1f))
                }
            }
            is EngineEvent.HistoryChanged -> {
                canGoBack = event.canGoBack
                canGoForward = event.canGoForward
            }
            is EngineEvent.Error -> error = event.error
            is EngineEvent.Console -> consoleFlow.tryEmit(event.message.capped())
            is EngineEvent.ContentSize -> onSize(event.widthCss, event.heightCss, event.viewportHeightCss)
            is EngineEvent.PageMessage -> bridge.receive(event.origin, event.json)
            EngineEvent.FirstPaint, EngineEvent.PaintLost, EngineEvent.Crashed, is EngineEvent.FocusChanged -> Unit // the WebView owns these
        }
    }

    /** What this page leaves behind for a new view of the same content. */
    internal fun memory(): PageMemory =
        PageMemory(snapshotKey, contentSize, savedEngineState, pageState, pageSavesState, lastSizeReport, growsWithViewport)

    /** Picks up where an earlier view of the same content left off. */
    internal fun restoreFrom(memory: PageMemory) {
        snapshotKey = memory.snapshotKey
        contentSize = memory.contentSize
        savedEngineState = memory.engineState
        pageState = memory.pageState
        pageSavesState = memory.pageSavesState
        lastSizeReport = memory.lastSizeReport
        growthStreak = 0
        growsWithViewport = memory.growsWithViewport
    }

    /**
     * Forgets everything of the page: what it showed ([forgetShown]), and what is remembered of it, its snapshot, engine
     * and page state, size and loop verdict. For a page the state may no longer show: the last user's, after a clear,
     * or another profile's.
     */
    internal fun forgetPage() {
        forgetShown()
        snapshotKey = null
        contentSize = null
        savedEngineState = null
        pageState = null
        pageSavesState = false
        lastSizeReport = null
        growthStreak = 0
        growsWithViewport = false
    }

    /**
     * Forgets what the last document showed: its URL, title, progress, history and error, which a cold view's
     * placeholder and the app's own chrome show. The content stays: it is the app's.
     */
    internal fun forgetShown() {
        url = null
        title = null
        loading = LoadingState.Idle
        canGoBack = false
        canGoForward = false
        error = null
    }

    private fun onReservedEvent(name: String, data: JsonElement) {
        when (name) {
            "\$console" -> (data as? JsonObject)?.let { consoleFlow.tryEmit(consoleMessageOf(it)) }
            "\$size" -> (data as? JsonObject)?.let { size ->
                val w = size.finiteFloat("w") ?: return
                val h = size.finiteFloat("h") ?: return
                onSize(w, h, size.finiteFloat("vh"))
            }
            "\$saveStateProvider" -> pageSavesState = true
        }
    }

    /**
     * A size the page reported through the shim, or the engine measured natively. Both are page-controlled:
     * - non-finite, negative and zero sizes are ignored, and both sides are clamped to [MAX_CONTENT_SIZE_CSS]. A zero
     *   height would collapse a WrapContentHeight view for good: at 0 px its page has no viewport to report from, and
     *   page memory would hand the 0 to every later view of the content;
     * - a viewport height that isn't a positive number is unknown.
     *
     * Then the loop detector. A page that grows with its own viewport (`100vh` plus margins, up to `400vh`) twice in a
     * row is caught, and the view stops growing with it. Otherwise WrapContentHeight would feed it until the view
     * reached its max.
     * - Growth faster than [MAX_VIEWPORT_MULTIPLE] times the viewport's, plus 1 px of rounding, is the page's own: it
     *   doesn't count, and it releases a caught page, so a fast-growing plain page isn't stranded.
     * - The viewport changing while the page doesn't follow releases it too: it isn't driven by its viewport.
     * - A change at a steady viewport is the page's own content (a log, an animation). A caught page keeps its verdict,
     *   and its cap moves by that change: releasing it would let the loop take another step each time.
     * - An identical report says nothing new, and leaves the streak alone.
     *
     * The last report and the verdict go into [memory], so a new view of a caught page stays caught.
     */
    private fun onSize(w: Float, h: Float, vh: Float?) {
        if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return
        val width = w.coerceAtMost(MAX_CONTENT_SIZE_CSS)
        val height = h.coerceAtMost(MAX_CONTENT_SIZE_CSS)
        val viewport = vh?.takeIf { it.isFinite() && it > 0f }
        val previous = lastSizeReport
        lastSizeReport = SizeReport(height, viewport)
        val current = contentSize?.height?.value
        val previousViewport = previous?.viewportHeight
        if (previous != null && viewport != null && previousViewport != null) {
            val dh = height - previous.height
            val dv = viewport - previousViewport
            val fastest = MAX_VIEWPORT_MULTIPLE * dv + 1f
            when {
                dh == 0f && dv == 0f -> Unit // the same report again, or only the width changed
                dv > 0f && height > viewport && dh >= dv && dh <= fastest ->
                    if (++growthStreak >= 2) growsWithViewport = true
                (dv != 0f && dh == 0f) || (dv > 0f && dh > fastest) -> {
                    growthStreak = 0
                    growsWithViewport = false
                }
                dv == 0f -> {
                    growthStreak = 0
                    if (growsWithViewport && current != null) {
                        // The cap moves with the page's own change; it never reaches 0, which would collapse the view.
                        val capped = current + dh
                        contentSize = DpSize(width.dp, (if (capped > 0f) capped.coerceAtMost(height) else height).dp)
                        return
                    }
                    growsWithViewport = false
                }
                else -> growthStreak = 0
            }
        }
        if (growsWithViewport && current != null && height > current) return
        contentSize = DpSize(width.dp, height.dp)
    }
}

/**
 * The largest content width or height a page may report, in CSS px. Compose can't represent a side over 262,142 px,
 * so this is safe at any density up to 8; the WebView caps its own height for denser screens.
 */
private const val MAX_CONTENT_SIZE_CSS = 32_000f

/** How many times as fast as its viewport a page that grows with its viewport may grow: `400vh`. */
private const val MAX_VIEWPORT_MULTIPLE = 4f

private fun JsonObject.finiteFloat(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }

/**
 * [text] cut as the shim cuts console text: its first [BridgeShim.CONSOLE_TEXT_MAX] characters, then `…`. The page may
 * bypass the shim, and engines capture console output natively, so the host cuts it too. Text the shim already cut
 * passes unchanged.
 */
private fun capConsoleText(text: String): String =
    if (text.length > BridgeShim.CONSOLE_TEXT_MAX) text.take(BridgeShim.CONSOLE_TEXT_MAX) + "…" else text

/** This message, with its text and source cut as the shim cuts them. */
private fun ConsoleMessage.capped(): ConsoleMessage {
    val text = capConsoleText(message)
    val cappedSource = source?.let(::capConsoleText)
    return if (text === message && cappedSource === source) this else ConsoleMessage(level, text, cappedSource, line, uncaught)
}

private fun consoleMessageOf(o: JsonObject): ConsoleMessage {
    fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val level = when (text("level")) {
        "debug" -> ConsoleLevel.Debug
        "info" -> ConsoleLevel.Info
        "warn" -> ConsoleLevel.Warn
        "error" -> ConsoleLevel.Error
        else -> ConsoleLevel.Log
    }
    return ConsoleMessage(
        level = level,
        message = capConsoleText(text("text").orEmpty()),
        source = text("source")?.let(::capConsoleText),
        line = (o["line"] as? JsonPrimitive)?.intOrNull,
        uncaught = (o["uncaught"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

/**
 * The view that owns a [WebViewState] ([WebViewState.owner]): the only way into its engine. Main thread only.
 */
internal interface StateOwner {
    /** The engine the view has bound now, or null. */
    val engine: WebEngine?

    /**
     * Runs [command] on [engine], guarded: an engine that throws is treated as crashed, and nothing reaches the caller.
     * False when there is no engine.
     */
    fun onEngine(command: (WebEngine) -> Unit): Boolean
}

/**
 * Navigation for one [WebViewState]. Commands other than [load] and [reload] need a live engine. Main thread only.
 *
 * Commands reach only the engine of the view that owns the state, guarded: an engine that throws is treated as
 * crashed (the view shows [WebViewPhase.Crashed]), and the exception never reaches the caller.
 */
public class WebNavigator internal constructor(private val state: WebViewState) {
    /**
     * Shows [url], as [WebViewState.load] does: it navigates there, even when it is the content already shown.
     * @throws IllegalArgumentException if [url] isn't an http(s) URL with a host. Parse what a person typed with
     *   [WebContent.Url.parse] first.
     */
    public fun load(url: String): Unit = state.load(WebContent.Url(url))

    public fun back() {
        state.owner?.onEngine { it.goBack() }
    }

    public fun forward() {
        state.owner?.onEngine { it.goForward() }
    }

    public fun stop() {
        state.owner?.onEngine { it.stop() }
    }

    /**
     * Reloads the page, and clears its error. A live page reloads in its engine. A cold, crashed or unavailable view is
     * asked to activate again, so a retry button works.
     */
    public fun reload() {
        state.error = null
        if (state.owner?.onEngine { it.reload() } == true) return
        val phase = state.phase
        if (phase == WebViewPhase.Crashed || phase is WebViewPhase.Unavailable) state.phase = WebViewPhase.Cold
        state.onNeedsEvaluation?.invoke()
    }
}

/**
 * A [WebViewState] for [content], remembered by its key.
 * - A new key means a new page, so the state is recreated.
 * - While the key stays, the state is the app's: what it loads into it ([WebViewState.load], [WebNavigator]) stays
 *   loaded. The state loads [content] again only when [content] itself changes from one composition to the next, and
 *   content with the same key is equal content, so in practice it never does.
 * - The state isn't saved across configuration changes on purpose: the runtime's page memory (spec §4) brings the
 *   snapshot, size and engine state back for the same content.
 */
@Composable
public fun rememberWebViewState(content: WebContent): WebViewState {
    val state = remember(content.key) { WebViewState(content) }
    val shown = remember(state) { LastArgument(content) }
    SideEffect {
        if (shown.content != content) {
            shown.content = content
            state.load(content)
        }
    }
    return state
}

/** What [rememberWebViewState] was last called with. */
private class LastArgument(var content: WebContent)
