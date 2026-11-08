package dev.supermux.webview.testing

import dev.supermux.webview.FindResult
import dev.supermux.webview.InspectorResult
import dev.supermux.webview.WebCookie
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
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.EngineEventQueue
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.WebEngineSpi
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a [FakeWebEngine] behaves. */
public data class FakeEngineBehavior(
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
    /**
     * Whether [FakeWebEngine.replayState] repeats a painted page's first paint at once. False holds it back, as an engine
     * that must draw the page in its new place first does (iOS, the web), until the test emits [EngineEvent.FirstPaint].
     */
    val replayPaintsAtOnce: Boolean = true,
) {
    /** The colour [content]'s pages paint: [color] if set, else one derived from [content]'s key. */
    public fun colorOf(content: WebContent): Color = color ?: keyColor(content.key)
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
 *   provider, and answers `$saveState` and `$hello` (with its `$ready` and, as the shim does, its save provider again),
 *   only if the last [configure] before it started had JavaScript on and listed the document's origin in
 *   [PageSetup.bridgeOrigins]. As with a real engine's start-of-document script, that is decided when the document
 *   starts. [FakeEngineBehavior.pageState] set is a save provider registered, at any time.
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
 * - Its events go through the real engines' own buffer ([EngineEventQueue]), so it buffers as they do. What the page
 *   is while nobody subscribes (a parked page) waits for the next subscriber, and what it says then is dropped. Once
 *   [load], [reload], [restoreState] or [reset] has returned, nothing an earlier document emitted is delivered, even if
 *   it is still buffered, as the contract asks. [emit] speaks for the current document, and [postFromPage] for its
 *   page's `hostBridge.post`, both on the main thread. A crash it emits tells [onUnusable], as the contract asks.
 * - [replayState] repeats what the current document reported last: its commit, history, title and size, and its first
 *   paint once it has painted. [documentsStarted] counts its documents, so a test can tell a page that was kept alive
 *   from one that was started again.
 */
public class FakeWebEngine(
    public val id: Int,
    override val mode: EmbeddingMode = EmbeddingMode.NativeView,
    public var behavior: FakeEngineBehavior = FakeEngineBehavior(),
    /** What the engine was created with. [FakeWebEngineFactory] passes the config it was asked for. */
    public val config: EngineConfig = EngineConfig(WebProfile.Default),
) : WebEngine {
    private val log = ArrayDeque<String>()

    /**
     * Every call, oldest first, with payloads cut to 200 characters. Only the last 2,000 are kept, so a long perf-lab
     * run doesn't grow without bound.
     */
    public val calls: List<String> get() = log

    private val violationLog = mutableListOf<String>()

    /**
     * How the caller broke the engine contract: `"load without configure"`, `"<member> during reset"`,
     * `"<member> after a failed reset"` or `"<member> after release"`. Tests assert it stays empty.
     */
    public val violations: List<String> get() = violationLog

    /**
     * The real engines' own buffer: what the page is while nobody subscribes (a parked page) waits for the next
     * subscriber, and what it says then is dropped, as on the real engines; each document start or reset is an epoch, so
     * what an earlier document left buffered is dropped.
     */
    private val queue = EngineEventQueue()

    override val events: Flow<EngineEvent> get() = queue.events

    override var client: EngineClient? = null

    override val needsCutouts: Boolean get() = behavior.needsCutouts

    /** Whether [release] was called. */
    public var released: Boolean = false
        private set
    private var crashed = false
    override val isUsable: Boolean get() = !released && !crashed

    /** Told when the page's renderer dies ([emit] of [EngineEvent.Crashed]) while the engine was usable, as the contract asks. */
    override var onUnusable: (() -> Unit)? = null

    /** What [configure] set for the current lease; null before it, and again from the moment a [reset] starts. */
    public var lastSetup: PageSetup? = null
        private set

    /** Whether [configure] was called since creation or since the last [reset] started. */
    private var configured = false

    /** Whether a [reset] is running: [resetGate] may be holding it open. */
    private var resetting = false

    /** Whether the last [reset] failed. The engine may only be released then. */
    private var resetFailed = false

    /** While set, [reset] waits for it to complete. */
    public var resetGate: CompletableDeferred<Unit>? = null

    /** While set, [saveState] waits for it to complete, as a real engine's asynchronous save may take a while. */
    public var saveStateGate: CompletableDeferred<Unit>? = null

    public var cutouts: OverlayCutouts? by mutableStateOf(null)
        private set

    /** The size [Surface] was last laid out at, in pixels. */
    public var surfaceSize: IntSize = IntSize(100, 100)
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
    public val painted: Boolean get() = paintedColor != null

    /** Whether the current document has painted: [snapshot] gives nothing until then. */
    private var documentPainted = false

    private var current: WebContent? = null
    private var documents = 0

    /** The current document's shim nonce, or null when the document runs no shim. */
    private var nonce: String? = null

    /** What the current document reported last, for [replayState]. */
    private var lastUrl: EngineEvent.UrlChanged? = null
    private var lastHistory: EngineEvent.HistoryChanged? = null
    private var lastTitle: EngineEvent.TitleChanged? = null
    private var latestSize: EngineEvent.ContentSize? = null

    /**
     * Emits [event] as if the page did something. [EngineEvent.FirstPaint] shows the current document, and
     * [EngineEvent.Crashed] makes the engine unusable for good. Main thread only, as the engines' queue is.
     */
    public fun emit(event: EngineEvent): Unit = emit(event, document = null)

    /** [emit], for a page message from [document] (its nonce), so the queue knows which document's `$ready` it is. */
    private fun emit(event: EngineEvent, document: String?) {
        val dies = event == EngineEvent.Crashed && isUsable
        when (event) {
            EngineEvent.FirstPaint -> {
                // A paint with no document (only a test emits one) has no content to take a colour from.
                paintedColor = current?.let(behavior::colorOf) ?: behavior.color ?: Color.Gray
                documentPainted = true
            }
            EngineEvent.Crashed -> crashed = true
            is EngineEvent.UrlChanged -> lastUrl = event
            is EngineEvent.HistoryChanged -> lastHistory = event
            is EngineEvent.TitleChanged -> lastTitle = event
            is EngineEvent.ContentSize -> latestSize = event
            else -> Unit
        }
        queue.offer(event, document)
        if (dies) onUnusable?.invoke()
    }

    /**
     * The page calls `hostBridge.post(name, data)`: an event from the current document, signed with its nonce, as the
     * shim sends it. False, and nothing is sent, when the document runs no shim or the engine was released.
     * @throws IllegalArgumentException if [data] isn't strict JSON.
     */
    public fun postFromPage(name: String, data: JsonElement = JsonNull): Boolean {
        val content = current ?: return false
        val document = nonce ?: return false
        if (released) return false
        emit(EngineEvent.PageMessage(originOf(content), FakePageWire.event(name, data, from = document)), document)
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

    /** What [find] answers; null (the default) is an engine that can't. */
    public var findAnswer: ((query: String, backwards: Boolean) -> FindResult?)? = null

    /** What [openInspector] answers. */
    public var inspectorAnswer: InspectorResult = InspectorResult.Opened

    private val cookieJar = mutableListOf<WebCookie>()

    /** Every cookie [setCookie] put in this engine's profile, in order. */
    public val cookies: List<WebCookie> get() = cookieJar

    override suspend fun find(query: String, backwards: Boolean): FindResult? {
        record("find:$query:$backwards")
        if (!allowed("find")) return null
        return findAnswer?.invoke(query, backwards)
    }

    override fun clearFind() {
        record("clearFind")
        allowed("clearFind")
    }

    override fun setZoom(factor: Float): Boolean {
        record("zoom:$factor")
        return allowed("setZoom")
    }

    override suspend fun setCookie(cookie: WebCookie): Boolean {
        record("cookie:${cookie.name}")
        if (!allowed("setCookie")) return false
        cookieJar += cookie
        return true
    }

    override fun openInspector(): InspectorResult {
        record("inspector")
        return if (allowed("openInspector")) inspectorAnswer else InspectorResult.Unsupported
    }

    override fun reload() {
        record("reload")
        if (!allowed("reload")) return
        current?.let(::documentLoaded)
    }

    /** What [evaluate] answers for a script: its JSON result, or null. */
    public var evaluateAnswer: (String) -> String? = { null }

    override suspend fun evaluate(script: String): String? {
        record("eval:", script)
        if (!allowed("evaluate")) return null
        return evaluateAnswer(script)
    }

    override fun postMessage(json: String, targetOrigin: String) {
        val content = current
        if (!allowed("postMessage", lease = true) || content == null || originOf(content) != targetOrigin) {
            record("post-dropped:", json)
            return
        }
        // As the shim does: a $hello needs no address, and anything else for another document is dropped.
        val message = FakePageWire.read(json)
        val hello = message?.event == "\$hello"
        val document = nonce
        if (document != null && !hello && message?.to != document) {
            record("post-misaddressed:", json)
            return
        }
        record("post:", json)
        // A document without the shim has nothing that receives bridge messages.
        if (document == null) return
        if (hello) {
            // As the shim does: a new connection hears again of a provider an earlier one, or nobody, heard of.
            announce(content)
            if (behavior.pageState != null) tellSaveProvider(content)
        } else if (message != null && message.call == "\$saveState") {
            val id = message.callId ?: return
            emit(EngineEvent.PageMessage(targetOrigin, FakePageWire.reply(id, behavior.pageState ?: JsonNull, from = document)), document)
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

    /**
     * Repeats what the current document reported last ([WebEngine.replayState]): its commit, history, title and size,
     * and its first paint once it has painted, unless [FakeEngineBehavior.replayPaintsAtOnce] holds that back. The page
     * notices nothing: no document starts.
     */
    override fun replayState() {
        record("replay")
        if (!allowed("replayState", lease = true) || current == null) return
        val paint = EngineEvent.FirstPaint.takeIf { documentPainted && behavior.replayPaintsAtOnce }
        listOfNotNull(lastUrl, lastHistory, lastTitle, latestSize, paint).forEach(::emit)
    }

    /** How many documents the engine has started: loads, reloads and restores. */
    public val documentsStarted: Int get() = documents

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
        // Nothing from before the reset, or from during it, is delivered after it: not even the last lease's focus.
        queue.advance(reset = true)
        // A reset that doesn't finish (one cancelled against the contract) leaves the engine as a failed one does.
        resetFailed = true
        try {
            resetGate?.await()
        } finally {
            resetting = false
        }
        if (released || !behavior.resetSucceeds) return false
        resetFailed = false
        queue.advance(reset = true)
        current = null
        forgetReported()
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
        // A new document: what the previous one left buffered is no longer delivered, nor replayed.
        queue.advance()
        forgetReported()
        // The surface keeps the previous document until this one paints (paint holding); a snapshot waits for it.
        documentPainted = false
        emit(EngineEvent.UrlChanged(urlOf(content)))
        emit(EngineEvent.HistoryChanged(canGoBack = false, canGoForward = false))
        emit(EngineEvent.Progress(0.5f))
        documents++
        nonce = if (runsShim(content)) "fake-$id-$documents" else null
        if (nonce != null) {
            announce(content)
            if (behavior.pageState != null) tellSaveProvider(content)
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

    private fun forgetReported() {
        lastUrl = null
        lastHistory = null
        lastTitle = null
        latestSize = null
    }

    /** Whether a document of [content] starting now gets the shim: JavaScript on, and its origin a bridge origin. */
    private fun runsShim(content: WebContent): Boolean {
        val setup = lastSetup ?: return false
        return setup.javaScriptEnabled && originOf(content) in setup.bridgeOrigins
    }

    /** The shim's `$ready`: its nonce in `d.nonce`, and, like everything the page sends, in `from`. */
    private fun announce(content: WebContent) {
        val ready = FakePageWire.event("\$ready", buildJsonObject { put("nonce", nonce) }, from = nonce)
        emit(EngineEvent.PageMessage(originOf(content), ready), nonce)
    }

    /** The shim's word that the page has a save provider, which [FakeEngineBehavior.pageState] says it has. */
    private fun tellSaveProvider(content: WebContent) {
        val provider = FakePageWire.event("\$saveStateProvider", JsonPrimitive(true), from = nonce)
        emit(EngineEvent.PageMessage(originOf(content), provider), nonce)
    }

    private fun urlOf(content: WebContent): String = when (content) {
        is WebContent.Url -> content.url
        is WebContent.Html -> content.origin.url
        is WebContent.Bundle -> content.origin.url + content.entry
    }

    private fun originOf(content: WebContent): String = when (content) {
        is WebContent.Url -> fakePageOrigin(content.url)
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
 *
 * Giving it to a runtime, shaping its pages ([behavior]) and checking what it made needs no opt-in: an app's test of its
 * web views uses no engine SPI. Its engines say they are native views ([EmbeddingMode.NativeView]) unless it is made
 * with a mode, which is the SPI's (`@OptIn(WebEngineSpi::class)`).
 */
public class FakeWebEngineFactory(
    public var behavior: FakeEngineBehavior = FakeEngineBehavior(),
) : WebEngineFactory {
    private var mode: EmbeddingMode = EmbeddingMode.NativeView

    /** A factory whose engines say they are [mode] ([WebEngine.mode]): [EmbeddingMode.Frames] stands for a frame engine. */
    @WebEngineSpi
    public constructor(mode: EmbeddingMode, behavior: FakeEngineBehavior = FakeEngineBehavior()) : this(behavior) {
        this.mode = mode
    }

    public val created: MutableList<FakeWebEngine> = mutableListOf()

    /** When set, every [create] reports this reason, with the detail `"fake"`. */
    public var unavailable: UnavailableReason? = null

    /** While set, [create] waits for it to complete, as WebView2 and WPE take a while to start. */
    public var createGate: CompletableDeferred<Unit>? = null

    /** When true, [create] throws: a factory that breaks its contract, to test the pool's guard. */
    public var failCreate: Boolean = false

    override suspend fun create(config: EngineConfig): WebEngineResult {
        createGate?.await()
        if (failCreate) throw IllegalStateException("fake create failed")
        unavailable?.let { return WebEngineResult.Unavailable(it, "fake") }
        val engine = FakeWebEngine(created.size + 1, mode, behavior, config)
        created += engine
        return WebEngineResult.Created(engine)
    }
}
