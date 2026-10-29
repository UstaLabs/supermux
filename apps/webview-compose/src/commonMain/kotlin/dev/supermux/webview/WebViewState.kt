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
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.runtime.PageMemory
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
 * time.
 */
@Stable
class WebViewState(initialContent: WebContent) {
    var content: WebContent by mutableStateOf(initialContent)
        private set
    var url: String? by mutableStateOf(null)
        internal set
    var title: String? by mutableStateOf(null)
        internal set
    var loading: LoadingState by mutableStateOf(LoadingState.Idle)
        internal set
    var canGoBack: Boolean by mutableStateOf(false)
        internal set
    var canGoForward: Boolean by mutableStateOf(false)
        internal set
    var phase: WebViewPhase by mutableStateOf(WebViewPhase.Cold)
        internal set
    var error: WebError? by mutableStateOf(null)
        internal set

    /** The page's content size in CSS pixels (≈ dp), as the page reported it. Kept while cold. */
    var contentSize: DpSize? by mutableStateOf(null)
        internal set

    private val consoleFlow = MutableSharedFlow<ConsoleMessage>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Console output and uncaught errors from the page, while it is live. */
    val console: SharedFlow<ConsoleMessage> = consoleFlow.asSharedFlow()

    val bridge: WebBridge = WebBridge(::onReservedEvent)
    val navigator: WebNavigator = WebNavigator(this)

    internal var engine: WebEngine? = null
    private var lastSizeReport: Pair<Float, Float?>? = null
    private var growingWithViewport = 0
    private var sizeLoop = false
    internal var snapshotKey: SnapshotKey? = null
    internal var savedEngineState: ByteArray? = null
    internal var pageState: String? = null

    /** The origin of the document that saved [pageState]. The state goes back only to a document of that origin. */
    internal var pageStateOrigin: String? = null
    internal var pageSavesState: Boolean = false
    internal var onNeedsEvaluation: (() -> Unit)? = null

    /** Shows [newContent]. Saved engine state, page state and the snapshot all belonged to the old content. */
    fun load(newContent: WebContent) {
        content = newContent
        error = null
        savedEngineState = null
        pageState = null
        pageStateOrigin = null
        pageSavesState = false
        snapshotKey = null
        lastSizeReport = null
        growingWithViewport = 0
        sizeLoop = false
        if (phase is WebViewPhase.Unavailable || phase == WebViewPhase.Crashed) phase = WebViewPhase.Cold
        // The WebView switches the engine: new content may need another engine config, and always a new setup.
        onNeedsEvaluation?.invoke()
    }

    internal fun apply(event: EngineEvent) {
        when (event) {
            is EngineEvent.UrlChanged -> {
                url = event.url
                bridge.documentCommitted(event.url)
            }
            is EngineEvent.TitleChanged -> title = event.title
            is EngineEvent.Progress -> loading = event.progress?.let { LoadingState.Loading(it.coerceIn(0f, 1f)) } ?: LoadingState.Idle
            is EngineEvent.HistoryChanged -> {
                canGoBack = event.canGoBack
                canGoForward = event.canGoForward
            }
            is EngineEvent.Error -> error = event.error
            is EngineEvent.Console -> consoleFlow.tryEmit(event.message)
            is EngineEvent.ContentSize -> onSize(event.widthCss, event.heightCss, event.viewportHeightCss)
            is EngineEvent.PageMessage -> bridge.receive(event.origin, event.json)
            EngineEvent.FirstPaint, EngineEvent.Crashed, is EngineEvent.FocusChanged -> Unit // the WebView owns these
        }
    }

    internal fun memory(): PageMemory =
        PageMemory(snapshotKey, contentSize, savedEngineState, pageState, pageStateOrigin, pageSavesState)

    internal fun restoreFrom(memory: PageMemory) {
        snapshotKey = memory.snapshotKey
        contentSize = memory.contentSize
        savedEngineState = memory.engineState
        pageState = memory.pageState
        pageStateOrigin = memory.pageStateOrigin
        pageSavesState = memory.pageSavesState
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
     * A size the page reported through the shim, or the engine measured natively. Both are page-controlled, so:
     * - non-finite or negative sizes are ignored, and heights are clamped;
     * - a page that grows at least as fast as its own viewport, twice in a row (`100vh` plus margins, `150vh`…), is
     *   caught, and the view stops growing. Otherwise WrapContentHeight would feed it forever.
     * - the catch is undone as soon as the page shows it isn't one: it changes while the viewport doesn't (animated or
     *   streaming content), or the viewport changes and the page doesn't follow.
     */
    private fun onSize(w: Float, h: Float, vh: Float?) {
        if (!w.isFinite() || !h.isFinite() || w < 0f || h < 0f) return
        val height = h.coerceAtMost(MAX_CONTENT_HEIGHT_CSS)
        val previous = lastSizeReport
        lastSizeReport = height to vh
        if (vh != null && previous != null) {
            val (previousHeight, previousVh) = previous
            if (previousVh != null) {
                when {
                    vh > previousVh && height > vh && height - previousHeight >= vh - previousVh ->
                        if (++growingWithViewport >= 2) sizeLoop = true
                    vh != previousVh && height == previousHeight -> {
                        // The viewport changed and the page did not follow it: not a loop.
                        growingWithViewport = 0
                        sizeLoop = false
                    }
                    vh == previousVh && height != previousHeight -> {
                        // The page changed on its own (an animation, streaming content): not a loop.
                        growingWithViewport = 0
                        sizeLoop = false
                    }
                    else -> growingWithViewport = 0
                }
            }
        }
        val current = contentSize?.height?.value
        if (sizeLoop && current != null && height > current) return
        contentSize = DpSize(w.dp, height.dp)
    }
}

/** The tallest content height a page may report, in CSS px. */
private const val MAX_CONTENT_HEIGHT_CSS = 100_000f

/** The longest console text kept; the shim caps it too, but the page may bypass the shim. */
private const val MAX_CONSOLE_TEXT = 8192

private fun JsonObject.finiteFloat(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }

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
        message = text("text").orEmpty().take(MAX_CONSOLE_TEXT),
        source = text("source")?.take(MAX_CONSOLE_TEXT),
        line = (o["line"] as? JsonPrimitive)?.intOrNull,
        uncaught = (o["uncaught"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

/** Navigation for one [WebViewState]. Commands other than [load] and [reload] need a live engine. */
class WebNavigator internal constructor(private val state: WebViewState) {
    fun load(url: String) = state.load(WebContent.Url(url))

    fun back() {
        state.engine?.goBack()
    }

    fun forward() {
        state.engine?.goForward()
    }

    fun stop() {
        state.engine?.stop()
    }

    /** Reloads a live page. A crashed or cold view is asked to activate again. */
    fun reload() {
        val engine = state.engine
        if (engine != null) {
            engine.reload()
        } else {
            if (state.phase == WebViewPhase.Crashed) state.phase = WebViewPhase.Cold
            state.onNeedsEvaluation?.invoke()
        }
    }
}

/**
 * A [WebViewState] for [content].
 * - A new key means a new page, so the state is recreated.
 * - The same key with different content (a Url with new headers) is loaded into the existing state.
 * - The state isn't saved across configuration changes on purpose: the runtime's page memory (spec §4) brings the
 *   snapshot, size and engine state back for the same content.
 */
@Composable
fun rememberWebViewState(content: WebContent): WebViewState {
    val state = remember(content.key) { WebViewState(content) }
    SideEffect { if (state.content != content) state.load(content) }
    return state
}
