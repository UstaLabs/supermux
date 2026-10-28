package dev.supermux.webview.testing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebContent
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a [FakeWebEngine] behaves. */
data class FakeEngineBehavior(
    /** Emit [EngineEvent.FirstPaint] right after every load, reload and restore. */
    val paintsOnLoad: Boolean = true,
    /** The page's content height in CSS px, reported after load (for WrapContentHeight); null = never reported. */
    val contentHeight: (WebContent) -> Float? = { null },
    /** What the page's save provider returns; null = the page registers none. */
    val pageState: JsonElement? = null,
    val color: Color = Color(0xFF4F7CFF),
)

/**
 * A [WebEngine] with no browser behind it, for tests and the sample's perf lab. It records every call in [calls]
 * and draws the cut-outs it was given, so a person can see them.
 */
class FakeWebEngine(
    val id: Int,
    override val mode: EmbeddingMode = EmbeddingMode.NativeView,
    var behavior: FakeEngineBehavior = FakeEngineBehavior(),
) : WebEngine {
    val calls: MutableList<String> = mutableListOf()
    private val eventFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: Flow<EngineEvent> = eventFlow
    override var client: EngineClient? = null

    var released: Boolean = false
        private set
    var cutouts: OverlayCutouts? by mutableStateOf(null)
        private set
    var surfaceSize: IntSize = IntSize(100, 100)
        private set
    private var current: WebContent? = null
    private var documents = 0

    /** Emits [event] as if the page did something. */
    fun emit(event: EngineEvent) {
        check(eventFlow.tryEmit(event)) { "FakeWebEngine $id: event buffer full" }
    }

    override fun load(content: WebContent) {
        calls += "load:${content.key}"
        current = content
        documentLoaded(content)
    }

    override fun goBack() { calls += "back" }
    override fun goForward() { calls += "forward" }
    override fun stop() { calls += "stop" }

    override fun reload() {
        calls += "reload"
        current?.let(::documentLoaded)
    }

    override suspend fun evaluate(script: String): String? {
        calls += "eval:$script"
        return null
    }

    override fun postMessage(json: String) {
        calls += "post:$json"
        val call = BridgeCodec.decode(json) as? BridgeMessage.Call ?: return
        val content = current ?: return
        if (call.method == "\$saveState") {
            val reply = BridgeMessage.Reply(call.id, behavior.pageState ?: JsonNull)
            emit(EngineEvent.PageMessage(originOf(content), BridgeCodec.encode(reply)))
        }
    }

    override suspend fun snapshot(): ImageBitmap? {
        calls += "snapshot"
        return ImageBitmap(surfaceSize.width.coerceAtLeast(1), surfaceSize.height.coerceAtLeast(1))
    }

    override suspend fun saveState(): ByteArray? {
        calls += "save"
        return "fake:${current?.key}".encodeToByteArray()
    }

    override fun restoreState(state: ByteArray) {
        calls += "restore:${state.decodeToString()}"
        emit(EngineEvent.UrlChanged("restored:${state.decodeToString()}"))
        emit(EngineEvent.Progress(null))
        if (behavior.paintsOnLoad) emit(EngineEvent.FirstPaint)
    }

    override fun applyOverlays(cutouts: OverlayCutouts) {
        calls += "overlays:${cutouts.holes.size}"
        this.cutouts = cutouts
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        Box(modifier.testTag("fake-engine-$id").background(behavior.color).onSizeChanged { surfaceSize = it }) {
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

    override fun reset() {
        calls += "reset"
        current = null
        client = null
        cutouts = null
    }

    override fun release() {
        calls += "release"
        released = true
    }

    private fun documentLoaded(content: WebContent) {
        emit(EngineEvent.UrlChanged(urlOf(content)))
        emit(EngineEvent.Progress(null))
        emit(EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false))
        val ready = BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", "fake-$id-${++documents}") })
        emit(EngineEvent.PageMessage(originOf(content), BridgeCodec.encode(ready)))
        behavior.contentHeight(content)?.let { emit(EngineEvent.ContentSize(surfaceSize.width.toFloat(), it)) }
        if (behavior.pageState != null) {
            val announce = BridgeMessage.Event("\$saveStateProvider", JsonPrimitive(true))
            emit(EngineEvent.PageMessage(originOf(content), BridgeCodec.encode(announce)))
        }
        if (behavior.paintsOnLoad) emit(EngineEvent.FirstPaint)
    }

    private fun urlOf(content: WebContent): String = when (content) {
        is WebContent.Url -> content.url
        is WebContent.Html -> content.origin.url
        is WebContent.Bundle -> content.origin.url + content.entry
    }

    private fun originOf(content: WebContent): String = when (content) {
        is WebContent.Url -> content.url.substringBefore("://") + "://" + content.url.substringAfter("://").substringBefore('/')
        is WebContent.Html -> content.origin.origin
        is WebContent.Bundle -> content.origin.origin
    }
}

/** Creates [FakeWebEngine]s numbered 1, 2, 3… and keeps them all in [created]. */
class FakeWebEngineFactory(
    private val mode: EmbeddingMode = EmbeddingMode.NativeView,
    var behavior: FakeEngineBehavior = FakeEngineBehavior(),
) : WebEngineFactory {
    val created: MutableList<FakeWebEngine> = mutableListOf()

    /** When set, every [create] reports this reason. */
    var unavailable: UnavailableReason? = null

    override fun create(config: EngineConfig): WebEngineResult {
        unavailable?.let { return WebEngineResult.Unavailable(it) }
        val engine = FakeWebEngine(created.size + 1, mode, behavior)
        created += engine
        return WebEngineResult.Created(engine)
    }
}
