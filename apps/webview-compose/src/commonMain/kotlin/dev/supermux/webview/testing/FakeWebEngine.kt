package dev.supermux.webview.testing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas as ImageCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.originOf as urlOrigin
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a [FakeWebEngine] behaves. */
data class FakeEngineBehavior(
    /** Emit [EngineEvent.FirstPaint] during every load, reload and restore. */
    val paintsOnLoad: Boolean = true,
    /** The page's content height in CSS px, reported after load (for WrapContentHeight); null = never reported. */
    val contentHeight: (WebContent) -> Float? = { null },
    /** What the page's save provider returns; null = the page registers none. */
    val pageState: JsonElement? = null,
    /** The page's colour once it has painted, on the surface and in snapshots. Until then the surface is white. */
    val color: Color = Color(0xFF4F7CFF),
    /**
     * What [FakeWebEngine.reset] returns until the engine is released. False models an engine that can't guarantee
     * isolation (see [WebEngine.reset]), which the pool must destroy rather than reuse.
     */
    val resetSucceeds: Boolean = true,
)

/**
 * A [WebEngine] with no browser behind it, for tests and the sample's perf lab. It follows the engine contract,
 * records every call in [calls], and draws the cut-outs it was given, so a person can see them. It doesn't hide the
 * runtime's mistakes:
 * - It installs the shim only where a real engine would. A document announces itself (`$ready`) and its save
 *   provider, and answers `$saveState` and `$hello`, only if the last [configure] before it started had JavaScript on
 *   and listed the document's origin in [PageSetup.bridgeOrigins]. As with a real engine's start-of-document script,
 *   that is decided when the document starts.
 * - A [load] or [restoreState] without a [configure] since creation or the last [reset] is recorded in [violations].
 *   Tests assert it stays empty.
 * - [restoreState] restores only the content whose state it is.
 * - Its [Surface] is white, and [snapshot] is null, until [EngineEvent.FirstPaint] for the current document, so a
 *   test can check that the runtime's cover never shows a white frame.
 * - [resetGate] holds a [reset] open, as [FakeWebEngineFactory.createGate] holds a creation, to test what happens
 *   meanwhile.
 */
class FakeWebEngine(
    val id: Int,
    override val mode: EmbeddingMode = EmbeddingMode.NativeView,
    var behavior: FakeEngineBehavior = FakeEngineBehavior(),
    /** What the engine was created with. [FakeWebEngineFactory] passes the config it was asked for. */
    val config: EngineConfig = EngineConfig(WebProfile.Default),
) : WebEngine {
    private val log = ArrayDeque<String>()

    /**
     * Every call, oldest first, with payloads cut to 200 characters. Only the last 2,000 are kept, so a long perf-lab
     * run doesn't grow without bound.
     */
    val calls: List<String> get() = log

    private val violationLog = mutableListOf<String>()

    /** How the caller broke the engine contract, such as `"load without configure"`. Tests assert it stays empty. */
    val violations: List<String> get() = violationLog

    private val eventFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: Flow<EngineEvent> = eventFlow
    override var client: EngineClient? = null

    /** Whether [release] was called. */
    var released: Boolean = false
        private set
    private var crashed = false
    override val isUsable: Boolean get() = !released && !crashed

    /** What [configure] set for the current lease; null before it, and again after a successful [reset]. */
    var lastSetup: PageSetup? = null
        private set

    /** Whether [configure] was called since creation or the last [reset]. */
    private var configured = false

    /** While set, [reset] waits for it to complete. */
    var resetGate: CompletableDeferred<Unit>? = null

    var cutouts: OverlayCutouts? by mutableStateOf(null)
        private set

    /** The size [Surface] was last laid out at, in pixels. */
    var surfaceSize: IntSize = IntSize(100, 100)
        private set

    /** The density [Surface] last saw, which turns its width into CSS pixels. */
    private var surfaceDensity = 1f

    /** How many [Surface]s are in the composition. */
    private var surfaces = 0

    /** Whether [Surface] was laid out since it entered the composition. */
    private var laidOut = false

    /**
     * Whether the current document has painted. [EngineEvent.FirstPaint] sets it; a new document or a successful
     * [reset] clears it.
     */
    var painted: Boolean by mutableStateOf(false)
        private set

    private var current: WebContent? = null
    private var documents = 0

    /** The current document's shim nonce, or null when the document runs no shim. */
    private var nonce: String? = null

    /**
     * Emits [event] as if the page did something. [EngineEvent.FirstPaint] shows the current document, and
     * [EngineEvent.Crashed] makes the engine unusable for good.
     */
    fun emit(event: EngineEvent) {
        when (event) {
            EngineEvent.FirstPaint -> painted = true
            EngineEvent.Crashed -> crashed = true
            else -> Unit
        }
        check(eventFlow.tryEmit(event)) { "FakeWebEngine $id: event buffer full" }
    }

    override fun configure(setup: PageSetup) {
        record("configure")
        lastSetup = setup
        configured = true
    }

    override fun load(content: WebContent) {
        record("load:", content.key)
        expectConfigured()
        current = content
        documentLoaded(content)
    }

    override fun goBack() = record("back")

    override fun goForward() = record("forward")

    override fun stop() = record("stop")

    override fun reload() {
        record("reload")
        current?.let(::documentLoaded)
    }

    override suspend fun evaluate(script: String): String? {
        record("eval:", script)
        return null
    }

    override fun postMessage(json: String, targetOrigin: String) {
        val content = current
        if (content == null || originOf(content) != targetOrigin) {
            record("post-dropped:", json)
            return
        }
        record("post:", json)
        // A document without the shim has nothing that receives bridge messages.
        if (nonce == null) return
        when (val message = BridgeCodec.decode(json)) {
            is BridgeMessage.Call -> if (message.method == "\$saveState") {
                val reply = BridgeMessage.Reply(message.id, behavior.pageState ?: JsonNull)
                emit(EngineEvent.PageMessage(targetOrigin, BridgeCodec.encode(reply)))
            }
            is BridgeMessage.Event -> if (message.name == "\$hello") announce(content)
            else -> Unit
        }
    }

    /**
     * Null until [EngineEvent.FirstPaint] for the current document, and unless [Surface] is composed and laid out
     * with a non-zero size; then the page's colour at that size.
     */
    override suspend fun snapshot(): ImageBitmap? {
        record("snapshot")
        val size = surfaceSize
        if (!painted || surfaces == 0 || !laidOut || size.width <= 0 || size.height <= 0) return null
        val image = ImageBitmap(size.width, size.height)
        val paint = Paint().apply { color = behavior.color }
        ImageCanvas(image).drawRect(0f, 0f, size.width.toFloat(), size.height.toFloat(), paint)
        return image
    }

    override suspend fun saveState(): ByteArray? {
        record("save")
        return current?.let { "$STATE_PREFIX${it.key}".encodeToByteArray() }
    }

    /** Restores only [content]'s own state: the page reloads, and its shim, if it gets one, announces itself again. */
    override fun restoreState(state: ByteArray, content: WebContent): Boolean {
        val text = state.decodeToString()
        record("restore:", text)
        expectConfigured()
        if (text != STATE_PREFIX + content.key) return false
        current = content
        documentLoaded(content)
        return true
    }

    override fun setVisible(visible: Boolean) = record("visible:$visible")

    override fun requestFocus() = record("focus")

    override fun applyOverlays(cutouts: OverlayCutouts) {
        record("overlays:${cutouts.holes.size}")
        this.cutouts = cutouts
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        DisposableEffect(Unit) {
            surfaces++
            onDispose {
                if (--surfaces == 0) laidOut = false
            }
        }
        val density = LocalDensity.current.density
        SideEffect { surfaceDensity = density }
        Box(
            modifier
                .testTag("fake-engine-$id")
                .background(if (painted) behavior.color else Color.White)
                .onSizeChanged {
                    surfaceSize = it
                    laidOut = true
                },
        ) {
            val shown = cutouts
            if (shown != null && shown.holes.isNotEmpty()) {
                Canvas(Modifier.matchParentSize()) {
                    shown.holes.forEach { hole ->
                        val r = hole.bounds.translate(-shown.pageBounds.left, -shown.pageBounds.top)
                        drawRect(Color.White, topLeft = r.topLeft, size = r.size, style = Stroke(width = 2f))
                    }
                }
            }
        }
    }

    /** Fails once the engine is released, even if that happened while [resetGate] held the reset open. */
    override suspend fun reset(): Boolean {
        record("reset")
        configured = false
        if (released) return false
        resetGate?.await()
        if (released || !behavior.resetSucceeds) return false
        current = null
        nonce = null
        client = null
        cutouts = null
        lastSetup = null
        painted = false
        return true
    }

    override fun release() {
        record("release")
        released = true
    }

    private fun record(call: String, payload: String) = record(call + payload.take(MAX_PAYLOAD))

    private fun record(call: String) {
        if (log.size >= MAX_CALLS) log.removeFirst()
        log.addLast(call)
    }

    /** A lease configures the engine before its first document; a document started without that is a violation. */
    private fun expectConfigured() {
        if (!configured && violationLog.size < MAX_VIOLATIONS) violationLog += "load without configure"
    }

    /**
     * A new document commits, announces itself if it runs the shim, finishes loading, paints, and then reports its
     * size.
     */
    private fun documentLoaded(content: WebContent) {
        painted = false
        emit(EngineEvent.UrlChanged(urlOf(content)))
        emit(EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false))
        emit(EngineEvent.Progress(0.5f))
        documents++
        nonce = if (runsShim(content)) "fake-$id-$documents" else null
        if (nonce != null) {
            announce(content)
            if (behavior.pageState != null) {
                val provider = BridgeMessage.Event("\$saveStateProvider", JsonPrimitive(true))
                emit(EngineEvent.PageMessage(originOf(content), BridgeCodec.encode(provider)))
            }
        }
        emit(EngineEvent.Progress(null))
        if (behavior.paintsOnLoad) emit(EngineEvent.FirstPaint)
        behavior.contentHeight(content)?.let { emit(EngineEvent.ContentSize(surfaceSize.width / surfaceDensity, it)) }
    }

    /** Whether a document of [content] starting now gets the shim: JavaScript on, and its origin a bridge origin. */
    private fun runsShim(content: WebContent): Boolean {
        val setup = lastSetup ?: return false
        return setup.javaScriptEnabled && originOf(content) in setup.bridgeOrigins
    }

    private fun announce(content: WebContent) {
        val ready = BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", nonce) })
        emit(EngineEvent.PageMessage(originOf(content), BridgeCodec.encode(ready)))
    }

    private fun urlOf(content: WebContent): String = when (content) {
        is WebContent.Url -> content.url
        is WebContent.Html -> content.origin.url
        is WebContent.Bundle -> content.origin.url + content.entry
    }

    private fun originOf(content: WebContent): String = when (content) {
        is WebContent.Url -> urlOrigin(content.url)
        is WebContent.Html -> content.origin.origin
        is WebContent.Bundle -> content.origin.origin
    }

    private companion object {
        const val MAX_CALLS = 2_000
        const val MAX_PAYLOAD = 200

        /** The first violations are the ones that explain a failure; more only add memory. */
        const val MAX_VIOLATIONS = 100
        const val STATE_PREFIX = "fake:"
    }
}

/**
 * Creates [FakeWebEngine]s numbered 1, 2, 3…, each with the config it was created for, and keeps them all in
 * [created].
 */
class FakeWebEngineFactory(
    private val mode: EmbeddingMode = EmbeddingMode.NativeView,
    var behavior: FakeEngineBehavior = FakeEngineBehavior(),
) : WebEngineFactory {
    val created: MutableList<FakeWebEngine> = mutableListOf()

    /** When set, every [create] reports this reason, with the detail `"fake"`. */
    var unavailable: UnavailableReason? = null

    /** While set, [create] waits for it to complete, as WebView2 and WPE take a while to start. */
    var createGate: CompletableDeferred<Unit>? = null

    /** When true, [create] throws: a factory that breaks its contract, to test the pool's guard. */
    var failCreate: Boolean = false

    override suspend fun create(config: EngineConfig): WebEngineResult {
        createGate?.await()
        if (failCreate) throw IllegalStateException("fake create failed")
        unavailable?.let { return WebEngineResult.Unavailable(it, "fake") }
        val engine = FakeWebEngine(created.size + 1, mode, behavior, config)
        created += engine
        return WebEngineResult.Created(engine)
    }
}
