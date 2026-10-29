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
import androidx.compose.ui.graphics.toArgb
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    /**
     * The page's content height in CSS px in a viewport of the given height in CSS px, for a page styled with viewport
     * units (`100vh`); null = not reported. When set, it replaces [contentHeight]: the page reports its height with its
     * viewport's after every load, and again whenever its surface's size changes, as the shim's ResizeObserver does.
     */
    val contentHeightInViewport: ((content: WebContent, viewportHeightCss: Float) -> Float?)? = null,
    /** What the page's save provider returns; null = the page registers none. */
    val pageState: JsonElement? = null,
    /**
     * The colour every page paints, on the surface and in snapshots. Null, the default, gives each content a colour of
     * its own ([colorOf]), so a screenshot shows which snapshot covers which view. The surface is white until the first
     * document after creation or a reset paints.
     */
    val color: Color? = null,
    /**
     * What [FakeWebEngine.reset] returns until the engine is released. False models an engine that can't guarantee
     * isolation (see [WebEngine.reset]), which the pool must destroy rather than reuse: lease calls to it are then
     * violations.
     */
    val resetSucceeds: Boolean = true,
    /**
     * [WebEngine.needsCutouts]: true models a page above Compose (the web, macOS), which is told its cut-outs; false
     * one that Compose draws over (Android, iOS), which the runtime computes none for.
     */
    val needsCutouts: Boolean = true,
) {
    /** The colour [content]'s pages paint: [color] if set, else one derived from [content]'s key. */
    fun colorOf(content: WebContent): Color = color ?: keyColor(content.key)
}

/**
 * A colour for a content key: the key's hash spread over hue, saturation and brightness, so keys that differ in one
 * character (`…/articles/1`, `…/articles/2`) still look different. Mid-bright and never white, and exact in 8 bits a
 * channel, so a screenshot reads it back as it is.
 */
private fun keyColor(key: String): Color {
    var h = key.hashCode()
    h = (h xor (h ushr 16)) * 0x7feb352d
    h = (h xor (h ushr 15)) * -0x7b935975
    h = h xor (h ushr 16)
    val hue = (h ushr 8) * (360f / (1 shl 24))
    val saturation = 0.45f + (h and 0xF) * (0.35f / 15)
    val value = 0.7f + ((h ushr 4) and 0xF) * (0.2f / 15)
    return Color(Color.hsv(hue, saturation, value).toArgb())
}

/**
 * A [WebEngine] with no browser behind it, for tests and the sample's perf lab. It follows the engine contract,
 * records every call in [calls], and draws the cut-outs it was given, so a person can see them. It doesn't hide the
 * runtime's mistakes:
 * - It installs the shim only where a real engine would. A document announces itself (`$ready`) and its save
 *   provider, and answers `$saveState` and `$hello`, only if the last [configure] before it started had JavaScript on
 *   and listed the document's origin in [PageSetup.bridgeOrigins]. As with a real engine's start-of-document script,
 *   that is decided when the document starts.
 * - It honours addressing as the shim does. A host message other than `$hello` that isn't addressed (`to`) to the
 *   current document's nonce is dropped, and recorded as `post-misaddressed:`. What the page sends names the document
 *   that sent it (`from`).
 * - It records in [violations], which tests assert stay empty:
 *   - a [load] or [restoreState] without a [configure] since creation or the last [reset];
 *   - [configure], [load], [restoreState] or [postMessage] while a [reset] runs or after one failed. The lease ends
 *     when the reset starts, and an engine whose reset failed may only be released. The call does nothing;
 *   - any member called after [release], which does nothing, as a real engine must never touch what it freed.
 * - [restoreState] restores only the content whose state it is.
 * - Its [Surface] is white after creation or a reset until [EngineEvent.FirstPaint], so a test can check that the
 *   runtime's cover never shows a white frame. Then it shows the page's colour, each content its own unless
 *   [FakeEngineBehavior.color] is set. Between documents (a reload, a navigation) it keeps the previous one's colour
 *   until the new one paints, as engines hold paint. [snapshot] is null until the current document paints.
 * - Its [Surface] composed in two places at once is a violation: a native view has one parent.
 * - [resetGate] holds a [reset] open, as [FakeWebEngineFactory.createGate] holds a creation and [saveStateGate] a
 *   [saveState], to test what happens meanwhile.
 * - It filters its events by navigation, as the contract asks: once [load], [reload], [restoreState] or [reset] has
 *   returned, nothing an earlier document emitted is delivered, even if it is still buffered. [emit] speaks for the
 *   current document, and [postFromPage] for its page's `hostBridge.post`.
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

    /**
     * How the caller broke the engine contract: `"load without configure"`, `"<member> during reset"`,
     * `"<member> after a failed reset"` or `"<member> after release"`. Tests assert it stays empty.
     */
    val violations: List<String> get() = violationLog

    /** An event, and the navigation that was current when it was emitted. */
    private class Tagged(val navigation: Int, val event: EngineEvent)

    private val eventFlow = MutableSharedFlow<Tagged>(extraBufferCapacity = 256)

    /** Counts navigations: each document start, and each reset. */
    private var navigation = 0

    /** The current navigation's events: what an earlier document left buffered is dropped. A crash is the engine's. */
    override val events: Flow<EngineEvent> =
        eventFlow.filter { it.navigation == navigation || it.event == EngineEvent.Crashed }.map { it.event }

    override var client: EngineClient? = null

    override val needsCutouts: Boolean get() = behavior.needsCutouts

    /** Whether [release] was called. */
    var released: Boolean = false
        private set
    private var crashed = false
    override val isUsable: Boolean get() = !released && !crashed

    /** What [configure] set for the current lease; null before it, and again from the moment a [reset] starts. */
    var lastSetup: PageSetup? = null
        private set

    /** Whether [configure] was called since creation or since the last [reset] started. */
    private var configured = false

    /** Whether a [reset] is running: [resetGate] may be holding it open. */
    private var resetting = false

    /** Whether the last [reset] failed. The engine may only be released then. */
    private var resetFailed = false

    /** While set, [reset] waits for it to complete. */
    var resetGate: CompletableDeferred<Unit>? = null

    /** While set, [saveState] waits for it to complete, as a real engine's asynchronous save may take a while. */
    var saveStateGate: CompletableDeferred<Unit>? = null

    var cutouts: OverlayCutouts? by mutableStateOf(null)
        private set

    /** The size [Surface] was last laid out at, in pixels. */
    var surfaceSize: IntSize = IntSize(100, 100)
        private set

    /** The density [Surface] last saw, which turns its width into CSS pixels. */
    private var surfaceDensity = 1f

    /** The last size the current document reported, so a viewport that didn't change reports nothing. */
    private var lastSize: EngineEvent.ContentSize? = null

    /** How many [Surface]s are in the composition. */
    private var surfaces = 0

    /** Whether [Surface] was laid out since it entered the composition. */
    private var laidOut = false

    /**
     * The colour of the page the surface shows: the current document's, or, while it loads, the previous one's (paint
     * holding). [EngineEvent.FirstPaint] sets it; only a successful [reset] clears it, and the surface is white then.
     */
    private var paintedColor: Color? by mutableStateOf(null)

    /** Whether the surface shows a painted page ([paintedColor]). */
    val painted: Boolean get() = paintedColor != null

    /** Whether the current document has painted: [snapshot] gives nothing until then. */
    private var documentPainted = false

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
            EngineEvent.FirstPaint -> {
                // A paint with no document (only a test emits one) has no content to take a colour from.
                paintedColor = current?.let(behavior::colorOf) ?: behavior.color ?: Color.Gray
                documentPainted = true
            }
            EngineEvent.Crashed -> crashed = true
            else -> Unit
        }
        check(eventFlow.tryEmit(Tagged(navigation, event))) { "FakeWebEngine $id: event buffer full" }
    }

    /**
     * The page calls `hostBridge.post(name, data)`: an event from the current document, signed with its nonce, as the
     * shim sends it. False, and nothing is sent, when the document runs no shim or the engine was released.
     * @throws IllegalArgumentException if [data] isn't strict JSON.
     */
    fun postFromPage(name: String, data: JsonElement = JsonNull): Boolean {
        val content = current ?: return false
        val document = nonce ?: return false
        if (released) return false
        emit(EngineEvent.PageMessage(originOf(content), BridgeCodec.encode(BridgeMessage.Event(name, data), from = document)))
        return true
    }

    override fun configure(setup: PageSetup) {
        record("configure")
        if (!allowed("configure", lease = true)) return
        lastSetup = setup
        configured = true
    }

    override fun load(content: WebContent) {
        record("load:", content.key)
        if (!allowed("load", lease = true)) return
        expectConfigured()
        current = content
        documentLoaded(content)
    }

    override fun goBack() {
        record("back")
        allowed("goBack")
    }

    override fun goForward() {
        record("forward")
        allowed("goForward")
    }

    override fun stop() {
        record("stop")
        allowed("stop")
    }

    override fun reload() {
        record("reload")
        if (!allowed("reload")) return
        current?.let(::documentLoaded)
    }

    override suspend fun evaluate(script: String): String? {
        record("eval:", script)
        allowed("evaluate")
        return null
    }

    override fun postMessage(json: String, targetOrigin: String) {
        val content = current
        if (!allowed("postMessage", lease = true) || content == null || originOf(content) != targetOrigin) {
            record("post-dropped:", json)
            return
        }
        // As the shim does: a $hello needs no address, and anything else for another document is dropped.
        val envelope = BridgeCodec.decodeEnvelope(json)
        val message = envelope?.message
        val hello = message is BridgeMessage.Event && message.name == "\$hello"
        val document = nonce
        if (document != null && !hello && envelope?.to != document) {
            record("post-misaddressed:", json)
            return
        }
        record("post:", json)
        // A document without the shim has nothing that receives bridge messages.
        if (document == null) return
        if (hello) {
            announce(content)
        } else if (message is BridgeMessage.Call && message.method == "\$saveState") {
            val reply = BridgeMessage.Reply(message.id, behavior.pageState ?: JsonNull)
            emit(EngineEvent.PageMessage(targetOrigin, BridgeCodec.encode(reply, from = document)))
        }
    }

    /**
     * Null until [EngineEvent.FirstPaint] for the current document, and unless [Surface] is composed and laid out
     * with a non-zero size; then the page's colour at that size.
     */
    override suspend fun snapshot(): ImageBitmap? {
        record("snapshot")
        if (!allowed("snapshot")) return null
        val size = surfaceSize
        val color = paintedColor
        if (!documentPainted || color == null || surfaces == 0 || !laidOut || size.width <= 0 || size.height <= 0) {
            return null
        }
        val image = ImageBitmap(size.width, size.height)
        val paint = Paint().apply { this.color = color }
        ImageCanvas(image).drawRect(0f, 0f, size.width.toFloat(), size.height.toFloat(), paint)
        return image
    }

    override suspend fun saveState(): ByteArray? {
        record("save")
        if (!allowed("saveState")) return null
        val saved = current?.let { "$STATE_PREFIX${it.key}".encodeToByteArray() }
        saveStateGate?.await()
        return saved
    }

    /** Restores only [content]'s own state: the page reloads, and its shim, if it gets one, announces itself again. */
    override fun restoreState(state: ByteArray, content: WebContent): Boolean {
        val text = state.decodeToString()
        record("restore:", text)
        if (!allowed("restoreState", lease = true)) return false
        expectConfigured()
        if (text != STATE_PREFIX + content.key) return false
        current = content
        documentLoaded(content)
        return true
    }

    override fun setVisible(visible: Boolean) {
        record("visible:$visible")
        allowed("setVisible")
    }

    override fun requestFocus() {
        record("focus")
        allowed("requestFocus")
    }

    override fun applyOverlays(cutouts: OverlayCutouts) {
        record("overlays:${cutouts.holes.size}")
        if (!allowed("applyOverlays")) return
        this.cutouts = cutouts
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        // A released engine has nothing left to show.
        if (!allowed("Surface")) return
        DisposableEffect(Unit) {
            if (++surfaces > 1) violation("Surface composed twice")
            onDispose {
                if (--surfaces == 0) laidOut = false
            }
        }
        val density = LocalDensity.current.density
        SideEffect { surfaceDensity = density }
        Box(
            modifier
                .testTag("fake-engine-$id")
                .background(paintedColor ?: Color.White)
                .onSizeChanged {
                    surfaceSize = it
                    laidOut = true
                    // A page sized by its viewport reports again, as the shim's ResizeObserver would.
                    val content = current
                    if (content != null && behavior.contentHeightInViewport != null && !resetting) reportSize(content)
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

    /**
     * Ends the lease at once: what [configure] set is gone, and lease calls are violations until the reset returns
     * true. Fails once the engine is released, even if that happened while [resetGate] held the reset open.
     */
    override suspend fun reset(): Boolean {
        record("reset")
        if (!allowed("reset")) return false
        configured = false
        lastSetup = null
        resetting = true
        // Nothing from before the reset, or from during it, is delivered after it.
        navigation++
        // A reset that doesn't finish (one cancelled against the contract) leaves the engine as a failed one does.
        resetFailed = true
        try {
            resetGate?.await()
        } finally {
            resetting = false
        }
        if (released || !behavior.resetSucceeds) return false
        resetFailed = false
        navigation++
        current = null
        lastSize = null
        nonce = null
        client = null
        cutouts = null
        paintedColor = null
        documentPainted = false
        return true
    }

    override fun release() {
        record("release")
        if (!allowed("release")) return
        released = true
    }

    private fun record(call: String, payload: String) = record(call + payload.take(MAX_PAYLOAD))

    private fun record(call: String) {
        if (log.size >= MAX_CALLS) log.removeFirst()
        log.addLast(call)
    }

    /**
     * Whether [member] may run now. Nothing may after [release]. A [lease] member, one that a lease calls on its page,
     * may not while a [reset] runs or after one failed either. When it may not, the call is a violation and does
     * nothing.
     */
    private fun allowed(member: String, lease: Boolean = false): Boolean {
        val broken = when {
            released -> "after release"
            lease && resetting -> "during reset"
            lease && resetFailed -> "after a failed reset"
            else -> return true
        }
        violation("$member $broken")
        return false
    }

    /** A lease configures the engine before its first document; a document started without that is a violation. */
    private fun expectConfigured() {
        if (!configured) violation("load without configure")
    }

    private fun violation(what: String) {
        // The first violations are the ones that explain a failure; more only add memory.
        if (violationLog.size < MAX_VIOLATIONS) violationLog += what
    }

    /**
     * A new document commits, announces itself if it runs the shim, finishes loading, paints, and then reports its
     * size. Until it paints, the surface keeps showing the previous one, if any.
     */
    private fun documentLoaded(content: WebContent) {
        // A new document: what the previous one left buffered is no longer delivered.
        navigation++
        // The surface keeps the previous document until this one paints (paint holding); a snapshot waits for it.
        documentPainted = false
        emit(EngineEvent.UrlChanged(urlOf(content)))
        emit(EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false))
        emit(EngineEvent.Progress(0.5f))
        documents++
        nonce = if (runsShim(content)) "fake-$id-$documents" else null
        if (nonce != null) {
            announce(content)
            if (behavior.pageState != null) {
                val provider = BridgeMessage.Event("\$saveStateProvider", JsonPrimitive(true))
                emit(EngineEvent.PageMessage(originOf(content), BridgeCodec.encode(provider, from = nonce)))
            }
        }
        emit(EngineEvent.Progress(null))
        if (behavior.paintsOnLoad) emit(EngineEvent.FirstPaint)
        lastSize = null
        reportSize(content)
    }

    /**
     * Reports [content]'s size, unless it is the last one reported: its height at the surface's viewport if
     * [FakeEngineBehavior.contentHeightInViewport] is set, else its fixed [FakeEngineBehavior.contentHeight].
     */
    private fun reportSize(content: WebContent) {
        val width = surfaceSize.width / surfaceDensity
        val inViewport = behavior.contentHeightInViewport
        val size = if (inViewport != null) {
            val viewport = surfaceSize.height / surfaceDensity
            inViewport(content, viewport)?.let { EngineEvent.ContentSize(width, it, viewport) }
        } else {
            behavior.contentHeight(content)?.let { EngineEvent.ContentSize(width, it) }
        } ?: return
        if (size == lastSize) return
        lastSize = size
        emit(size)
    }

    /** Whether a document of [content] starting now gets the shim: JavaScript on, and its origin a bridge origin. */
    private fun runsShim(content: WebContent): Boolean {
        val setup = lastSetup ?: return false
        return setup.javaScriptEnabled && originOf(content) in setup.bridgeOrigins
    }

    /** The shim's `$ready`: its nonce in `d.nonce`, and, like everything the page sends, in `from`. */
    private fun announce(content: WebContent) {
        val ready = BridgeMessage.Event("\$ready", buildJsonObject { put("nonce", nonce) })
        emit(EngineEvent.PageMessage(originOf(content), BridgeCodec.encode(ready, from = nonce)))
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
