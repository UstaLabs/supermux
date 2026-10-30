package dev.supermux.webview.engine.linux

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.EngineEventQueue
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.RelayMessage
import dev.supermux.webview.engine.webkit.SavedWebKitState
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.normalizeBundlePath
import dev.supermux.webview.originOf
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The script world the relay runs in and holds the message handler in (F16): the page's own world can't reach it. */
internal object WebEngineWorld {
    const val WORLD: String = "webview"
}

/**
 * The Linux engine: one WPE WebKit web view on the glue's own WPEPlatform display, its frames drawn by Compose (spec §7
 * "Linux: frames"), following the [WebEngine] contract. It is the iOS engine's logic on WPE: the same WebKit scripts,
 * relay, URLs, saved state and navigation rules (`engine/webkit`), adapted where the feasibility test found WPE
 * differs: a navigation's frame is known at its response ([WpeNavigation], W4), and dark mode is the process's (W9).
 * Main thread only: the glue's events reach [onEvent] there through [WpeHost], in the order WebKit made them.
 *
 * - **Events** go through an [EngineEventQueue], with an epoch at every load, reload, restore and reset.
 *   - Until the new load starts, WebKit's load events are the last document's, and are dropped.
 *   - Title and URL changes count only for the document committed in the current epoch.
 *   - The relay's messages count only for the current document's token.
 * - **The bridge:** [WebKitScripts]. The relay runs in [WebEngineWorld.WORLD] and holds the message handler; the shim
 *   runs in the page's world.
 * - **Local content:**
 *   - `Html` loads with `load_html` on its `https://<hash>.invalid` origin;
 *   - `Bundle` is served on `webview-bundle://<host>.invalid` ([onScheme]);
 *   - the floor is served on `webview-floor://reset/`.
 */
internal class LinuxWebEngine(
    val config: EngineConfig,
    internal val view: WpeView,
    private val scripts: WebKitScripts,
    private val scope: CoroutineScope,
) : WebEngine {
    private class Pending(val byDefault: () -> Unit) {
        var job: Job? = null
        private var answered = false

        fun answer(block: () -> Unit) {
            if (answered) return
            answered = true
            block()
        }

        fun answerByDefault() = answer(byDefault)
    }

    private val queue = EngineEventQueue()
    private val navigation = WpeNavigation()
    private val questions = mutableListOf<Pending>()
    private val evals = HashMap<Long, CompletableDeferred<String?>>()
    private var nextRequest = 1L
    private var lease: Job = SupervisorJob(scope.coroutineContext[Job])
    private var released = false
    private var crashed = false
    private var resetting = false
    private var resetFailed = false
    private var setup: PageSetup? = null
    private var content: WebContent? = null
    private var own: OwnNavigation? = null

    /** A load of ours was asked for and hasn't started: WebKit's load events until then are the last document's. */
    private var awaitingStart = false

    /** Between a load's start and its commit or failure: a URL change then is that load's, not the document's. */
    private var provisional = false
    private var committedEpoch = -1
    private var committedUrl: String? = null
    private var commits = 0
    private var reportedTitle: String? = null
    private var document: String? = null
    private var documentPainted = false
    private var httpError: WebError? = null
    private var focused: Boolean? = null
    private var history: EngineEvent.HistoryChanged? = null
    private var backList: List<String> = emptyList()
    private var forwardList: List<String> = emptyList()
    private var floorState: ByteArray? = null
    private var floorDone: CompletableDeferred<Boolean>? = null
    private var baseUserAgent: String? = null
    private var dark: Boolean? = null

    override val mode: EmbeddingMode get() = EmbeddingMode.Frames
    override val events: Flow<EngineEvent> get() = queue.events
    override val isUsable: Boolean get() = !released && !crashed
    override val needsCutouts: Boolean get() = false

    override var client: EngineClient? = null
        set(value) {
            if (!released) field = value
        }

    private val bundle: WebOrigin? get() = (content as? WebContent.Bundle)?.origin
    private val htmlUrl: String? get() = (content as? WebContent.Html)?.origin?.url

    init {
        WpeHost.listen(view.id, ::onEvent)
    }

    /** Loads the floor and keeps its state for [reset], and learns WebKit's own user agent. False if the engine can't. */
    internal suspend fun warmUp(): Boolean {
        val done = CompletableDeferred<Boolean>()
        floorDone = done
        own = OwnNavigation.Floor
        awaitingStart = true
        view.loadUri(WebKitUrls.FLOOR_URL, emptyMap())
        val loaded = withTimeoutOrNull(WARM_UP_MILLIS) { done.await() } ?: false
        floorDone = null
        if (!loaded || released || crashed) return false
        floorState = view.saveState()
        baseUserAgent = evaluateIn(WebEngineWorld.WORLD, "navigator.userAgent")
            ?.let { (Json.parseToJsonElement(it) as? JsonPrimitive)?.contentOrNull }
        // Nothing from before the first lease reaches it: as after a reset.
        queue.advance(reset = true)
        return floorState != null
    }

    // --- The lease ---

    override fun configure(setup: PageSetup) {
        if (!leaseCallAllowed()) return
        this.setup = setup
        val origins = WebKitUrls.pageOrigins(setup.bridgeOrigins)
        val background = when {
            setup.transparentBackground -> 0x00000000
            setup.dark -> 0xFF000000.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val agent = setup.userAgentSuffix?.let { suffix -> listOfNotNull(baseUserAgent, suffix).joinToString(" ") }
        view.configure(
            scripts.relay(origins), if (setup.javaScriptEnabled) scripts.shim(origins) else null, WebEngineWorld.WORLD,
            scripts.handlerName, setup.javaScriptEnabled, setup.inspectable, background, agent,
        )
        // WPE's dark mode is the process's (W9): the last lease to set its theme sets every page's.
        if (dark != setup.dark) {
            dark = setup.dark
            view.setDark(setup.dark)
        }
    }

    override fun load(content: WebContent) {
        if (!leaseCallAllowed()) return
        startDocument(content)
        when (content) {
            is WebContent.Url -> {
                own = OwnNavigation.Load(content.url)
                view.loadUri(content.url, content.headers)
            }
            is WebContent.Html -> {
                // WebKit keeps load_html's document out of session history, so back from a page it navigates to skips
                // it. load_alternate_html would add the entry, but going back to it refetches the origin, which doesn't
                // resolve (measured on WPE 2.54.2, 2026-10-30): no entry is the clean choice.
                own = OwnNavigation.Load(content.origin.url)
                view.loadHtml(content.html, content.origin.url)
            }
            is WebContent.Bundle -> {
                val url = WebKitUrls.bundleUrl(content.origin, escapePath(content.entry))
                own = OwnNavigation.Load(url)
                view.loadUri(url, emptyMap())
            }
        }
    }

    override fun reload() {
        if (released || resetting) return
        val shown = content ?: return
        // WebKit would fetch in-memory HTML from its origin, which doesn't resolve: load it again instead.
        if (shown is WebContent.Html) {
            load(shown)
            return
        }
        startDocument(shown)
        own = OwnNavigation.Reload
        view.navigate(WpeNative.GO_RELOAD)
    }

    override fun goBack() {
        if (released || resetting || !WebKitUrls.canGoBack(backList)) return
        own = OwnNavigation.History
        view.navigate(WpeNative.GO_BACK)
    }

    override fun goForward() {
        if (released || resetting || !WebKitUrls.canGoForward(forwardList)) return
        own = OwnNavigation.History
        view.navigate(WpeNative.GO_FORWARD)
    }

    override fun stop() {
        if (!released) view.navigate(WpeNative.GO_STOP)
    }

    override suspend fun evaluate(script: String): String? = if (released) null else evaluateIn(null, script)

    /**
     * Delivers [json] to the main frame's shim, if its document's origin is [targetOrigin] when it arrives: the delivery
     * body runs in the engine's script world, where `self.origin` is the document's and the page can't redefine it (B3).
     * A bundle's origin is given as its pages see it, `webview-bundle://<host>.invalid`. Nothing waits for an answer.
     */
    override fun postMessage(json: String, targetOrigin: String) {
        if (!leaseCallAllowed()) return
        val arguments = mapOf("json" to json, "target" to WebKitUrls.pageOrigin(targetOrigin, bundle), "name" to scripts.inEvent)
        view.callAsync(NO_ANSWER, scripts.deliveryBody, WebEngineWorld.WORLD, arguments)
    }

    /** Null until snapshots come from the last frame (Task 25). */
    override suspend fun snapshot(): ImageBitmap? = null

    override suspend fun saveState(): ByteArray? {
        if (released) return null
        val shown = content ?: return null
        // A document of in-memory HTML can't be restored: WebKit would fetch it from its origin, which doesn't resolve.
        if (shown is WebContent.Html) return null
        val state = view.saveState() ?: return null
        return SavedWebKitState.encode(shown.key, state)
    }

    override fun restoreState(state: ByteArray, content: WebContent): Boolean {
        if (!leaseCallAllowed() || content is WebContent.Html) return false
        val saved = SavedWebKitState.decode(state, content.key) ?: return false
        startDocument(content)
        own = OwnNavigation.Restore
        if (!view.restoreState(saved)) {
            own = null
            awaitingStart = false
            return false
        }
        return true
    }

    override fun setVisible(visible: Boolean) {
        if (released || crashed) return
        view.setVisible(visible)
    }

    /** Replays what the page is now; its first paint comes again at once until Task 25 waits for it in its new place. */
    override fun replayState() {
        if (!leaseCallAllowed() || crashed || content == null || committedEpoch != queue.epoch) return
        val url = committedUrl ?: return
        if (WebKitUrls.isFloor(url)) return
        val epoch = queue.epoch
        push(epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(url, bundle)))
        history = null
        pushHistory()
        push(epoch, EngineEvent.TitleChanged(reportedTitle))
        focused = null
        if (documentPainted) push(epoch, EngineEvent.FirstPaint)
    }

    override fun requestFocus() {
        if (!released) view.setFocus(true)
    }

    override fun applyOverlays(cutouts: OverlayCutouts) = Unit

    /** The frames' surface comes in Task 24; until then the runtime's cover stays over an empty one. */
    @Composable
    override fun Surface(modifier: Modifier) {
        Box(modifier)
    }

    /** Not reusable until Task 26's reset: false, so the pool destroys a used engine instead. */
    override suspend fun reset(): Boolean = false

    override fun release() {
        if (released) return
        answerAllByDefault()
        released = true
        client = null
        evals.values.forEach { it.complete(null) }
        evals.clear()
        scope.cancel()
        WpeHost.forget(view.id)
        view.destroy()
    }

    // --- The glue's events ---

    internal fun onEvent(event: WpeEvent) {
        if (released) return
        when (event) {
            is WpeEvent.Load -> onLoad(event)
            is WpeEvent.LoadFailed -> onFailed(event)
            is WpeEvent.Title -> if (!awaitingStart && committedEpoch == queue.epoch) reportTitle(event.title)
            is WpeEvent.Uri -> onUri(event.uri)
            is WpeEvent.Progress -> if (!awaitingStart && provisional) push(queue.epoch, EngineEvent.Progress(event.progress.coerceIn(START_PROGRESS, 0.99f)))
            is WpeEvent.History -> {
                backList = event.back
                forwardList = event.forward
                pushHistory()
            }
            is WpeEvent.Policy -> onPolicy(event)
            is WpeEvent.Response -> onResponse(event)
            is WpeEvent.Relay -> onRelay(event.body)
            is WpeEvent.Eval -> evals.remove(event.request)?.complete(if (event.ok) event.json else null)
            is WpeEvent.Crashed -> onCrashed()
            is WpeEvent.Scheme -> onScheme(event)
            is WpeEvent.Frame, is WpeEvent.ImeFocus, is WpeEvent.ImeCursor, is WpeEvent.Cursor, is WpeEvent.DataCleared -> Unit
        }
    }

    private fun onLoad(event: WpeEvent.Load) {
        when (event.event) {
            WpeNative.LOAD_STARTED -> {
                awaitingStart = false
                provisional = true
                push(queue.epoch, EngineEvent.Progress(START_PROGRESS))
            }
            WpeNative.LOAD_COMMITTED -> if (!awaitingStart) onCommitted(event.uri)
            WpeNative.LOAD_FINISHED -> if (!awaitingStart) onFinished(event.uri)
        }
    }

    private fun onCommitted(uri: String?) {
        val epoch = queue.epoch
        provisional = false
        committedEpoch = epoch
        own = null
        document = null
        documentPainted = false
        committedUrl = uri
        val commit = ++commits
        val url = uri?.let { WebKitUrls.toLibraryUrl(it, bundle) }
        if (httpError?.url != url) httpError = null
        push(epoch, EngineEvent.UrlChanged(url))
        reportedTitle = null
        push(epoch, EngineEvent.TitleChanged(null))
        push(epoch, EngineEvent.Progress(COMMIT_PROGRESS))
        pushHistory()
        // Not its commit: its content says when it can be shown. One that never paints anything is shown anyway, later.
        CoroutineScope(scope.coroutineContext + lease).launch {
            delay(CONTENT_WAIT_MILLIS)
            if (commits == commit) handOff(epoch)
        }
    }

    private fun onFinished(uri: String?) {
        provisional = false
        val epoch = queue.epoch
        push(epoch, EngineEvent.Progress(null))
        // A document without the relay (a PDF, an image) has no paint report: its load says it has its content.
        if (epoch == committedEpoch && document == null && !documentPainted) handOff(epoch)
        // Last: a reset waiting for the floor goes on from here, and nothing of the floor's load may follow it.
        if (WebKitUrls.isFloor(uri)) floorDone?.complete(true)
    }

    private fun onFailed(event: WpeEvent.LoadFailed) {
        own = null
        awaitingStart = false
        provisional = false
        push(queue.epoch, EngineEvent.Progress(null))
        // A floor that fails fails the warm-up or the reset waiting for it.
        floorDone?.complete(false)
        // A navigation replaced by another, or one refused or turned into a download, isn't an error.
        if (event.cancelled || event.policy) return
        val url = event.uri?.let { WebKitUrls.toLibraryUrl(it, bundle) }
        val error = when {
            event.tls -> WebError.Certificate(url, event.message ?: "TLS certificate error")
            event.cannotShow -> WebError.UnsupportedOnPlatform(url)
            else -> WebError.Network(url, event.message ?: "load failed (${event.code})")
        }
        push(queue.epoch, EngineEvent.Error(error))
    }

    /** A URL change outside a load: the document moved within itself (pushState, a fragment). WebKit tells it (W8). */
    private fun onUri(uri: String?) {
        if (awaitingStart || provisional || committedEpoch != queue.epoch || uri == null) return
        if (uri == committedUrl || WebKitUrls.isFloor(uri)) return
        committedUrl = uri
        push(queue.epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(uri, bundle)))
    }

    private fun onCrashed() {
        if (crashed) return
        crashed = true
        floorDone?.complete(false)
        push(queue.epoch, EngineEvent.Crashed)
    }

    // --- Navigation ---

    private fun onPolicy(event: WpeEvent.Policy) {
        when (val verdict = navigation.decideAction(event, own, bundle, htmlUrl)) {
            WpeVerdict.Own -> {
                own = null
                view.decide(event.decision, WpeNative.USE)
            }
            WpeVerdict.Use, WpeVerdict.AtResponse -> view.decide(event.decision, WpeNative.USE)
            WpeVerdict.Ignore -> view.decide(event.decision, WpeNative.IGNORE)
            WpeVerdict.ReloadHtml -> {
                view.decide(event.decision, WpeNative.IGNORE)
                val html = content as? WebContent.Html ?: return
                own = OwnNavigation.Load(html.origin.url)
                view.loadHtml(html.html, html.origin.url)
            }
            is WpeVerdict.Ask -> {
                // Synchronous, as WebKit's policy questions are answered here: the runtime's client answers at once.
                val decision = try {
                    client?.onNavigation(verdict.request)
                } catch (e: Exception) {
                    null
                }
                view.decide(event.decision, if (decision == NavigationDecision.Allow) WpeNative.USE else WpeNative.IGNORE)
            }
        }
    }

    private fun onResponse(event: WpeEvent.Response) {
        val url = event.url
        val asked = navigation.requestAtResponse(event, bundle)
        if (event.mainFrame && (event.attachment || !event.canShow)) {
            // A download. The client hears of it; the engine saves nothing, either way (as Android's DownloadListener).
            view.decide(event.decision, WpeNative.IGNORE)
            if (url != null) askAboutDownload(DownloadRequest(WebKitUrls.toLibraryUrl(url, bundle), event.filename, event.mime, null))
            return
        }
        if (asked != null) {
            val decision = try {
                client?.onNavigation(asked)
            } catch (e: Exception) {
                null
            }
            if (decision != NavigationDecision.Allow) {
                view.decide(event.decision, WpeNative.IGNORE)
                return
            }
        }
        if (event.mainFrame && event.status >= 400 && url != null) httpError = WebError.Http(WebKitUrls.toLibraryUrl(url, bundle), event.status)
        view.decide(event.decision, WpeNative.USE)
    }

    // --- The relay ---

    private fun onRelay(body: String) {
        val parsed = RelayMessage.parse(body) ?: return
        if (parsed is RelayMessage.Start) {
            document = parsed.document
            return
        }
        if (parsed.document != document) return
        val epoch = committedEpoch
        when (parsed) {
            is RelayMessage.Paint -> handOff(epoch)
            is RelayMessage.Focus -> setFocused(epoch, parsed.focused)
            // WebKit reports title and same-document URL changes itself on WPE (W8): the relay's hints aren't needed.
            is RelayMessage.Title, is RelayMessage.Url -> Unit
            // The document's own token instance: the queue collapses its repeated $ready by identity.
            is RelayMessage.Page -> push(epoch, EngineEvent.PageMessage(documentOrigin(), parsed.json), document)
            is RelayMessage.Start -> Unit
        }
    }

    /**
     * The origin of the document the relay speaks for, in the library's terms. WPE's script messages carry no origin
     * (F16), so it is the committed document's URL's: the relay runs only in the main frame, forwards only from a
     * document whose own origin (`self.origin`) is a bridge origin, and its token ties each message to that commit. A
     * bundle's `webview-bundle://` URL reports as its `https://<host>.invalid`; a URL without a host is `"null"`.
     */
    private fun documentOrigin(): String = committedUrl?.let { originOf(WebKitUrls.toLibraryUrl(it, bundle)) } ?: "null"

    /** The committed document has its content: its first paint (Task 25 waits for it to be drawn in its place first). */
    private fun handOff(epoch: Int) {
        if (released || documentPainted || epoch != committedEpoch) return
        markPainted(epoch)
    }

    private fun markPainted(epoch: Int) {
        if (documentPainted) return
        documentPainted = true
        push(epoch, EngineEvent.FirstPaint)
        httpError?.let {
            httpError = null
            push(epoch, EngineEvent.Error(it))
        }
    }

    private fun setFocused(epoch: Int, value: Boolean) {
        if (focused == value) return
        focused = value
        push(epoch, EngineEvent.FocusChanged(value))
    }

    private fun reportTitle(title: String?) {
        val shown = title?.takeIf { it.isNotEmpty() }
        if (shown == reportedTitle) return
        reportedTitle = shown
        push(queue.epoch, EngineEvent.TitleChanged(shown))
    }

    private fun pushHistory() {
        val next = EngineEvent.HistoryChanged(WebKitUrls.canGoBack(backList), WebKitUrls.canGoForward(forwardList))
        if (next == history) return
        history = next
        push(queue.epoch, next)
    }

    // --- Local content ---

    /**
     * Serves the floor, and the lease's bundle and nothing else, as iOS's scheme handlers do:
     * - another bundle's URL, or any URL between leases, fails as an unknown host does, so no document of another
     *   bundle can exist;
     * - a path [normalizeBundlePath] refuses, or a file the provider hasn't, is an HTTP 404;
     * - a file is a 200 with the provider's type and its own headers.
     */
    private fun onScheme(event: WpeEvent.Scheme) {
        val uri = event.uri
        if (WebKitUrls.isFloor(uri)) {
            view.finishScheme(event.request, 200, "text/html; charset=utf-8", FLOOR_HTML.encodeToByteArray())
            return
        }
        val shown = content as? WebContent.Bundle
        if (shown == null || !WebKitUrls.isBundleUrl(uri, shown.origin)) {
            view.finishScheme(event.request, SCHEME_FAILED, null, null)
            return
        }
        val path = normalizeBundlePath(WebKitUrls.rawPath(uri))
        if (path == null) {
            view.finishScheme(event.request, 404, "text/plain", null)
            return
        }
        val file = path.ifEmpty { shown.entry }
        CoroutineScope(scope.coroutineContext + lease).launch {
            val resource = try {
                shown.resources.load(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (resource == null) view.finishScheme(event.request, 404, "text/plain", null)
            else view.finishScheme(event.request, 200, typeAndHeaders(resource), resource.bytes)
        }
    }

    // --- Client requests ---

    /** Tells the client of a download, which the engine has already refused: nothing waits on the answer. */
    private fun askAboutDownload(request: DownloadRequest) {
        val asked = client ?: return
        CoroutineScope(scope.coroutineContext + lease).launch {
            try {
                asked.onDownload(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A client that throws has said no.
            }
        }
    }

    /**
     * Asks the client [question] on main, and hands its answer to [answer]. With no client, or one that fails, or when a
     * reset or release comes first, WebKit gets [byDefault], the safe answer. Exactly one of them runs.
     */
    private fun <T : Any> ask(byDefault: () -> Unit, question: suspend (EngineClient) -> T, answer: (T) -> Unit) {
        val pending = Pending(byDefault)
        val asked = client
        if (asked == null || released || resetting) return pending.answerByDefault()
        questions += pending
        pending.job = CoroutineScope(scope.coroutineContext + lease).launch {
            val result = try {
                question(asked)
            } catch (e: CancellationException) {
                null
            } catch (e: Exception) {
                null
            }
            questions -= pending
            if (result == null) pending.answerByDefault() else pending.answer { answer(result) }
        }
    }

    private fun answerAllByDefault() {
        for (pending in questions.toList()) {
            pending.job?.cancel()
            pending.answerByDefault()
        }
        questions.clear()
    }

    // --- Helpers ---

    private fun leaseCallAllowed(): Boolean = !released && !resetting && !resetFailed

    /**
     * Queues [event], caused in [epoch]. What an earlier epoch caused is dropped here: the queue only knows the epoch it
     * is in. A crash and focus changes pass whatever the epoch: they are the engine's and the view's.
     */
    private fun push(epoch: Int, event: EngineEvent, document: Any? = null) {
        if (epoch != queue.epoch && event != EngineEvent.Crashed && event !is EngineEvent.FocusChanged) return
        queue.offer(event, document)
    }

    /** A new document of [content] starts: nothing an earlier one caused is delivered from now on. */
    private fun startDocument(content: WebContent): Int {
        val epoch = queue.advance()
        this.content = content
        documentPainted = false
        httpError = null
        document = null
        history = null
        awaitingStart = true
        provisional = false
        navigation.clear()
        return epoch
    }

    private suspend fun evaluateIn(world: String?, script: String): String? {
        if (released) return null
        val request = nextRequest++
        val done = CompletableDeferred<String?>()
        evals[request] = done
        view.evaluate(request, script, world)
        return try {
            withTimeoutOrNull(EVAL_MILLIS) { done.await() }
        } finally {
            evals.remove(request)
        }
    }

    companion object {
        private const val START_PROGRESS = 0.1f
        private const val COMMIT_PROGRESS = 0.5f
        private const val WARM_UP_MILLIS = 10_000L
        private const val EVAL_MILLIS = 10_000L

        /** The glue's request id for a script whose result no one waits for. */
        private const val NO_ANSWER = 0L

        /** The glue's answer to a scheme request that fails as an unknown host does: not an HTTP response. */
        private const val SCHEME_FAILED = 0

        /**
         * [resource]'s type, then its own headers as `Name: value` lines: the glue's format. The type and the length
         * are the engine's to set, as on iOS; a header that can't be one HTTP line is left out.
         */
        fun typeAndHeaders(resource: WebResource): String = buildString {
            append(resource.mimeType)
            for ((name, value) in resource.headers) {
                if (name.equals("Content-Type", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) continue
                if (name.isEmpty() || name.any { it == ':' || it == '\n' || it == '\r' } || value.any { it == '\n' || it == '\r' }) continue
                append('\n').append(name).append(": ").append(value)
            }
        }

        /** How long a committed document may show nothing contentful before it is shown anyway (the iOS engine's 3 s). */
        private const val CONTENT_WAIT_MILLIS = 3_000L

        /** The floor: blank, in the theme the page is given. */
        private const val FLOOR_HTML = "<!doctype html><html><head><meta name=\"color-scheme\" content=\"light dark\">" +
            "<meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"

        /** Percent-escapes [path] for a URL, keeping its slashes. */
        fun escapePath(path: String): String = buildString {
            for (byte in path.encodeToByteArray()) {
                val c = byte.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._~/!\$&'()*+,;=:@") append(ch)
                else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
