package dev.supermux.webview.engine.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebError
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebResource
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.EngineEventQueue
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.android.LocalAnswer
import dev.supermux.webview.engine.android.answerFor
import dev.supermux.webview.engine.android.pageTitle
import dev.supermux.webview.engine.frames.FrameStore
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.RelayMessage
import dev.supermux.webview.engine.webkit.escapeUrlPath
import dev.supermux.webview.hostOf
import dev.supermux.webview.originOf
import dev.supermux.webview.runtime.OverlayCutouts
import dev.supermux.webview.schemeOf
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The Windows engine: one WebView2 in visual-hosting mode, its frames captured and drawn by Compose (spec §7 "Windows:
 * frames"), following the [WebEngine] contract. It is the Linux engine's logic on WebView2's own events (D6): the same
 * handoff, queue, questions and heartbeat. Main thread only, but for [Wv2Navigation]'s decisions, which the glue's UI
 * thread asks for from [snapshot] and [own] (D9).
 *
 * - **Epochs:** every load, reload, restore and reset starts one ([EngineEventQueue.advance]); the glue stamps every
 *   event with the epoch it belongs to (a navigation's, its document's, the secret's), and nothing of an earlier epoch
 *   is heard. A new epoch has a new secret, so an earlier document can't even sign a message (D5).
 * - **Our navigation:** until the epoch's own navigation starts ([awaitingStart]), what WebView2 reports is the last
 *   document's. A failure that isn't ours (a cancelled earlier navigation, L4) is ignored.
 * - **Local content:** `Html` and the floor are served by the glue; a `Bundle` by the provider (Task 20).
 */
internal class WindowsWebEngine(
    val config: EngineConfig,
    internal val page: WinPage,
    private val scripts: Wv2Scripts,
    private val scope: CoroutineScope,
    private val onReleased: () -> Unit = {},
) : WebEngine {
    /** A question the client was asked and hasn't answered: answered exactly once (Linux's Pending). */
    private class Pending(val byDefault: () -> Unit, val holdsPage: Boolean) {
        var job: Job? = null
        private var answered = false

        fun answer(block: () -> Unit) {
            if (answered) return
            answered = true
            block()
        }

        fun answerByDefault() = answer(byDefault)
    }

    /** A page that floods its bridge while parked goes the way of a crash (L7). */
    private val queue = EngineEventQueue(onOverflow = ::onCrashed)

    /** The engine's own navigation, claimed on the glue's UI thread (D9). */
    private val own = AtomicReference<OwnNavigation?>(null)

    /** What the glue's UI thread reads; replaced at every change (D9). */
    @Volatile
    private var snapshot = NavigationSnapshot.Closed

    private val questions = mutableListOf<Pending>()
    private val requests = HashMap<Long, CompletableDeferred<WinEvent.Result>>()
    private var nextRequest = 1L
    private var lease: Job = SupervisorJob(scope.coroutineContext[Job])
    private var released = false
    private var crashed = false
    private var resetting = false
    private var resetFailed = false
    private var setup: PageSetup? = null
    private var content: WebContent? = null

    /** This epoch's secret (D5). */
    private var secret: String = ""

    /** The epoch's own navigation hasn't started: what WebView2 reports until it does is the last document's. */
    private var awaitingStart = false

    /** The epoch's own navigation's id, once it started. */
    private var ourNavigation: Long? = null

    /** Between a main-frame navigation's start and its commit or its end: a URL change then is that navigation's. */
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

    /** False from a reset until the lease's first commit has cleared the floor from the history (D10). */
    private var historyCleared = true

    /** Whether WebView2 clears history here (V1): false once `Page.resetNavigationHistory` failed. */
    private var historyResettable = true

    /** Whether the page is shown (setVisible). */
    private var visible = true

    override val mode: EmbeddingMode get() = EmbeddingMode.Frames
    override val events: Flow<EngineEvent> get() = queue.events
    override val isUsable: Boolean get() = !released && !crashed
    override val needsCutouts: Boolean get() = false

    override var client: EngineClient? = null
        set(value) {
            if (released) return
            field = value
            publish()
        }

    init {
        WinPages.listen(page.token, ::onEvent)
        WinPages.decide(page.token) { _, flags, url -> Wv2Navigation.decide(flags, url, snapshot, own) }
        publish()
    }

    /** What the glue's UI thread may read now (D9). */
    private fun publish() {
        snapshot = if (released || crashed) NavigationSnapshot.Closed else NavigationSnapshot(released = false, client = client)
    }

    /**
     * Readies a new engine: the floor's page given to the glue, and an ephemeral slot wiped of whatever a crashed run left
     * (D8). No navigation: a new WebView2 has no history, so the first lease's begins with its own load (D10).
     */
    internal suspend fun warmUp(): Boolean {
        page.serve(null, null, Wv2Navigation.floorHtml(dark = false))
        // The file chooser goes through CDP (V8): the OS dialog never opens, the client is asked instead.
        page.devtools(NO_ANSWER, "Page.enable", "{}")
        page.devtools(NO_ANSWER, "Page.setInterceptFileChooserDialog", "{\"enabled\":true}")
        page.subscribe("Page.fileChooserOpened")
        if (config.profile == WebProfile.Ephemeral && !wipeData()) return false
        queue.advance(reset = true)
        return !released && !crashed
    }

    // --- The lease ---

    override fun configure(setup: PageSetup) {
        if (!leaseCallAllowed()) return
        val before = this.setup
        this.setup = setup
        page.configure(flagsOf(setup), backgroundOf(setup), setup.userAgentSuffix)
        // Origins or scripts changed mid-lease: documents from now on get them, in the same epoch, with the same secret.
        if (content != null && (before?.bridgeOrigins != setup.bridgeOrigins || before?.javaScriptEnabled != setup.javaScriptEnabled)) {
            page.setScript(queue.epoch, secret, documentScript())
        }
    }

    override fun load(content: WebContent) {
        if (!leaseCallAllowed()) return
        val epoch = startDocument(content)
        when (content) {
            is WebContent.Url -> {
                own.set(OwnNavigation.Load(content.url))
                page.serve(null, null, null)
                page.navigate(epoch, content.url, headersOf(content.headers))
            }
            is WebContent.Html -> {
                val url = content.origin.url
                own.set(OwnNavigation.Load(url))
                page.serve(hostOf(url), content.html.encodeToByteArray(), null)
                page.navigate(epoch, url, null)
            }
            is WebContent.Bundle -> {
                val url = content.origin.url + escapeUrlPath(content.entry)
                own.set(OwnNavigation.Load(url))
                page.serve(hostOf(url), null, null)
                page.navigate(epoch, url, null)
            }
        }
    }

    /** Task 23 adds L3's recovery: a content that never committed loads again. */
    override fun reload() {
        if (!leaseCallAllowed()) return
        val shown = content ?: return
        val epoch = startDocument(shown)
        own.set(OwnNavigation.Reload)
        page.go(epoch, WinGlue.GO_RELOAD)
    }

    override fun goBack() {
        if (!leaseCallAllowed() || history?.canGoBack != true) return
        own.set(OwnNavigation.History)
        page.go(0, WinGlue.GO_BACK)
    }

    override fun goForward() {
        if (!leaseCallAllowed() || history?.canGoForward != true) return
        own.set(OwnNavigation.History)
        page.go(0, WinGlue.GO_FORWARD)
    }

    override fun stop() {
        if (!released) page.go(0, WinGlue.GO_STOP)
    }

    override suspend fun evaluate(script: String): String? {
        if (released) return null
        val result = call(EVAL_MILLIS) { page.execute(it, script) } ?: return null
        return if (result.ok) result.json else null
    }

    /** A document's words heard before its commit, in order (V6): heard right after the commit. At most [HELD_LIMIT]. */
    private val held = ArrayList<String>()

    /**
     * Delivers [json] to the main frame's shim if its document's origin is [targetOrigin] when it arrives: by executing a
     * call to the receiver the document script installed at document creation, which compares the origin it captured
     * then (D5; the contract's **Delivery** for WebView2). Nothing waits for an answer.
     */
    override fun postMessage(json: String, targetOrigin: String) {
        if (!leaseCallAllowed()) return
        page.execute(NO_ANSWER, scripts.deliveryCall(json, targetOrigin))
    }

    private fun onMessage(event: WinEvent.Message) {
        if (event.epoch != queue.epoch) return
        // Before this epoch's commit, or while a navigation commits, WebView2 may tell the next document's first words
        // before its commit: they wait for it.
        if (committedEpoch != queue.epoch || provisional) {
            if (held.size < HELD_LIMIT) held += event.body
            return
        }
        hear(event.body)
    }

    /** What the held words were waiting for came: a commit, or a navigation that ended without one. */
    private fun releaseHeld() {
        if (held.isEmpty()) return
        val words = held.toList()
        held.clear()
        words.forEach(::hear)
    }

    private fun hear(body: String) {
        val parsed = RelayMessage.parse(body) ?: return
        if (parsed is RelayMessage.Start) {
            document = parsed.document
            return
        }
        if (parsed.document != document) return
        val epoch = committedEpoch
        when (parsed) {
            is RelayMessage.Paint -> handOff(epoch)
            // The document's token instance: the queue collapses its repeated $ready by identity.
            is RelayMessage.Page -> push(epoch, EngineEvent.PageMessage(documentOrigin(), parsed.json), document)
            // Focus, titles, URLs and gestures are WebView2's own events (D6); the script never reports them.
            else -> Unit
        }
    }

    /** Bundle requests the provider hasn't answered: a reset or a release answers them 404, so WebView2 lets them go. */
    private val resources = mutableSetOf<Long>()

    /**
     * A bundle's request (D7): only the lease's bundle, by the common rule ([answerFor]); the provider's file with its own
     * headers, or a 404 or 405. A request of an earlier document or of another host (the glue forwards only the served
     * one's) gets no response: it fails as an unknown host's would.
     */
    private fun onResource(event: WinEvent.Resource) {
        val shown = content as? WebContent.Bundle
        if (shown == null || event.epoch != queue.epoch || resetting || !shown.origin.owns(event.url)) {
            page.respond(event.request, 0, null, null)
            return
        }
        when (val answer = answerFor(event.url, event.method, shown)) {
            LocalAnswer.Network -> page.respond(event.request, 0, null, null)
            LocalAnswer.NotFound, is LocalAnswer.Html -> page.respond(event.request, 404, PLAIN, null)
            LocalAnswer.MethodNotAllowed -> page.respond(event.request, 405, PLAIN, null)
            is LocalAnswer.BundleFile -> {
                val request = event.request
                resources += request
                CoroutineScope(scope.coroutineContext + lease).launch {
                    val resource = try {
                        answer.resources.load(answer.path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (!resources.remove(request)) return@launch
                    if (resource == null) page.respond(request, 404, PLAIN, null)
                    else page.respond(request, 200, responseHeaders(resource), resource.bytes)
                }
            }
        }
    }

    private fun finishResources() {
        for (request in resources) page.respond(request, 404, PLAIN, null)
        resources.clear()
    }

    // --- The page's requests (D18) ---

    /**
     * Asks the client [question] on main, and hands its answer to [answer]. With no client, one that fails, or a new
     * document, a reset or a release first, the page gets [byDefault], the safe answer. Exactly one of them runs.
     * (LinuxWebEngine.ask.)
     */
    private fun <T : Any> ask(byDefault: () -> Unit, question: suspend (EngineClient) -> T, holdsPage: Boolean = false, answer: (T) -> Unit) {
        val pending = Pending(byDefault, holdsPage)
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

    private fun onDialog(event: WinEvent.Dialog) {
        val q = event.question
        val leave = { page.answer(q, 1, null) }
        val dismiss = { page.answer(q, 0, null) }
        if (event.epoch != queue.epoch) return if (event.kind == DIALOG_BEFORE_UNLOAD) leave() else dismiss()
        val message = event.message.orEmpty()
        val url = event.uri ?: committedUrl
        when (event.kind) {
            DIALOG_ALERT -> ask(dismiss, { it.onDialog(WebDialog.Alert(message, url)) }, holdsPage = true) { dismiss() }
            DIALOG_CONFIRM -> ask(dismiss, { it.onDialog(WebDialog.Confirm(message, url)) }, holdsPage = true) {
                page.answer(q, if (it == DialogResult.Confirmed) 1 else 0, null)
            }
            DIALOG_PROMPT -> {
                val default = event.defaultText.orEmpty()
                ask(dismiss, { it.onDialog(WebDialog.Prompt(message, default, url)) }, holdsPage = true) {
                    when (it) {
                        is DialogResult.Text -> page.answer(q, 1, it.value)
                        DialogResult.Confirmed -> page.answer(q, 1, default)
                        DialogResult.Dismissed -> dismiss()
                    }
                }
            }
            // beforeunload: "stay" only if the client says so; by default the page can't trap the user.
            else -> ask(leave, { it.onDialog(WebDialog.BeforeUnload(message, url)) }, holdsPage = true) {
                page.answer(q, if (it != DialogResult.Dismissed) 1 else 0, null)
            }
        }
    }

    /** A permission: the client's answer, or deny. Nothing outlives it (`SavesInProfile` FALSE in the glue, V7). */
    private fun onPermission(event: WinEvent.Permission) {
        val deny = { page.answer(event.question, 0, null) }
        if (event.epoch != queue.epoch) return deny()
        val kind = when (event.kind) {
            PERMISSION_MICROPHONE -> PermissionKind.Microphone
            PERMISSION_CAMERA -> PermissionKind.Camera
            PERMISSION_GEOLOCATION -> PermissionKind.Geolocation
            PERMISSION_NOTIFICATIONS -> PermissionKind.Notifications
            PERMISSION_CLIPBOARD_READ -> PermissionKind.ClipboardRead
            else -> PermissionKind.Other
        }
        val request = PermissionRequest(event.uri?.let(::originOf) ?: documentOrigin(), setOf(kind))
        ask(deny, { it.onPermission(request) }) { granted -> page.answer(event.question, if (granted) 1 else 0, null) }
    }

    /** A download: WebView2 saved nothing (the glue cancelled it), and the client hears of it. */
    private fun onDownload(event: WinEvent.Download) {
        if (event.epoch != queue.epoch) return
        val url = event.url ?: return
        // The lease's own load turned into a download: no document comes, so the load ends here.
        if (provisional && committedEpoch != queue.epoch) {
            provisional = false
            own.set(null)
            push(queue.epoch, EngineEvent.Progress(null))
            push(queue.epoch, EngineEvent.Error(WebError.BlockedByPolicy(url)))
        }
        val asked = client ?: return
        val request = DownloadRequest(url, event.fileName, event.mime, null)
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

    /** The page asked for a window, and got none (the glue handled it, L22): the client decides what becomes of its URL. */
    private fun onNewWindow(event: WinEvent.NewWindow) {
        if (event.epoch != queue.epoch) return
        val url = event.url
        if (url.isNullOrEmpty() || Wv2Navigation.isFloor(url) || schemeOf(url) == "about" && url != "about:blank") return
        val decision = try {
            client?.onNewWindow(NewWindowRequest(url, event.user))
        } catch (e: Exception) {
            null
        }
        if (decision != NewWindowDecision.OpenHere || !leaseCallAllowed()) return
        own.set(OwnNavigation.Load(url))
        page.navigate(queue.epoch, url, null)
    }

    /** CDP's file chooser (V8): the element's `accept`, the client's files, `DOM.setFileInputFiles`; nothing to cancel. */
    private fun onDevToolsEvent(event: WinEvent.DevToolsEvent) {
        if (event.epoch != queue.epoch || event.name != "Page.fileChooserOpened") return
        val params = event.json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return
        val node = params["backendNodeId"]?.jsonPrimitive?.longOrNull ?: return
        val multiple = params["mode"]?.jsonPrimitive?.contentOrNull == "selectMultiple"
        CoroutineScope(scope.coroutineContext + lease).launch {
            val described = call(EVAL_MILLIS) { page.devtools(it, "DOM.describeNode", "{\"backendNodeId\":$node}") }
            val accept = described?.takeIf { it.ok }?.json?.let(::acceptOf).orEmpty()
            ask({}, { it.onFileChooser(FileChooserRequest(accept, multiple)) ?: emptyList() }) { chosen ->
                val paths = chosen.mapNotNull(::localPath).let { if (multiple) it else it.take(1) }
                if (paths.isEmpty()) return@ask
                val files = JsonArray(paths.map(::JsonPrimitive)).toString()
                page.devtools(NO_ANSWER, "DOM.setFileInputFiles", "{\"files\":$files,\"backendNodeId\":$node}")
            }
        }
    }

    /**
     * The origin of the document the script speaks for: its committed URL's. The script runs only in the main frame,
     * the shim only where `self.origin` (read at document creation) is a bridge origin, and its token and the epoch's
     * secret tie each message to that commit.
     */
    private fun documentOrigin(): String = committedUrl?.let(::originOf) ?: "null"

    /** Task 26 copies the last frame. */
    override suspend fun snapshot(): ImageBitmap? = null

    /** Task 23 saves the page's URL and scroll. */
    override suspend fun saveState(): ByteArray? = null

    /** Task 23 restores. */
    override fun restoreState(state: ByteArray, content: WebContent): Boolean = false

    /** Task 22 hides and throttles. */
    override fun setVisible(visible: Boolean) {
        this.visible = visible
    }

    /** Task 22 replays the page. */
    override fun replayState() = Unit

    /** Task 25 moves the keyboard in. */
    override fun requestFocus() = Unit

    override fun applyOverlays(cutouts: OverlayCutouts) = Unit

    /** Task 24 composes the frames. */
    @Composable
    override fun Surface(modifier: Modifier) = Unit

    /** Task 22 resets. */
    override suspend fun reset(): Boolean = false

    override fun release() {
        if (released) return
        answerAllByDefault()
        client = null
        released = true
        publish()
        // A copy: a call resumed in place by its answer removes itself from the map.
        requests.values.toList().forEach { it.complete(WinEvent.Result(0, false, null)) }
        requests.clear()
        finishResources()
        scope.cancel()
        WinPages.forget(page.token)
        page.release()
        frames.close()
        onReleased()
    }

    // --- Frames and the handoff (Linux's, Task 24 adds the surface around them) ---

    /** The page's frames: what every surface of this engine draws, and what a snapshot copies (D4). */
    internal val frames = FrameStore(page).apply { hideUntil(Long.MAX_VALUE) }

    /** A frame was captured, or a handoff ticks: the surface's next Compose frame has work. */
    private val frameSignal = Channel<Unit>(Channel.CONFLATED)

    /** Surfaces in the composition now. */
    private var surfaces = 0

    /** The size the page was last placed at, in pixels: what a frame drawn in place has. */
    private var placedSize: Pair<Int, Int>? = null

    /** The page's cursor over the surface (Task 24). */
    internal var cursor: PointerIcon by mutableStateOf(PointerIcon.Default)

    internal fun onSurfaceAttached(attached: Boolean) {
        val before = surfaces
        surfaces = (surfaces + if (attached) 1 else -1).coerceAtLeast(0)
        if (before == 0 && surfaces > 0) frames.forgetDrawn()
        if (surfaces == 0) handoff?.framesInPlace = -1
    }

    internal fun onSurfaceFrame(full: Boolean) {
        if (released) return
        frames.pull(full)
        advanceHandoff()
    }

    internal fun onSurfaceDrawn() {
        frames.onDrawn()
        val waiting = handoff ?: return
        if (waiting.framesInPlace < 0 && frames.drawnSeq >= waiting.target && drawnAtSurfaceSize()) {
            waiting.framesInPlace = 0
            frameSignal.trySend(Unit)
        }
    }

    internal suspend fun awaitSurfaceWork() {
        frameSignal.receive()
    }

    /** The size the surface placed the page at (Task 24's placement calls it; tests call it directly). */
    internal fun onSurfacePlacedForTests(width: Int, height: Int) {
        placedSize = width to height
    }

    private fun drawnAtSurfaceSize(): Boolean {
        val (width, height) = placedSize ?: return true
        return abs(frames.width - width) <= 1 && abs(frames.height - height) <= 1
    }

    /** A first paint waiting to be drawn in place (Linux's Handoff). */
    private class Handoff(val epoch: Int, val commit: Int, val target: Long, val replay: Boolean) {
        var framesInPlace = -1
    }

    private var handoff: Handoff? = null

    /**
     * The committed document has content to show: the script's `paint`, a document without the script's load, or
     * [CONTENT_WAIT_MILLIS] of nothing since its commit. [EngineEvent.FirstPaint] then waits until a surface has drawn the
     * capture's newest frame of that moment, at the placed size, and [FRAMES_IN_PLACE] Compose frames have passed.
     */
    private fun handOff(epoch: Int) {
        if (released || documentPainted || epoch != committedEpoch || Wv2Navigation.isFloor(committedUrl)) return
        if (handoff?.let { it.commit == commits && !it.replay } == true) return
        waitForDrawing(Handoff(epoch, commits, page.frameSeq().coerceAtLeast(0), replay = false))
    }

    private fun waitForDrawing(next: Handoff) {
        if (surfaces > 0 && frames.drawnSeq >= next.target && drawnAtSurfaceSize()) next.framesInPlace = 0
        handoff = next
        frameSignal.trySend(Unit)
    }

    private fun advanceHandoff() {
        val waiting = handoff ?: return
        if (waiting.framesInPlace < 0) return
        if (!drawnAtSurfaceSize()) {
            waiting.framesInPlace = -1
            return
        }
        waiting.framesInPlace++
        if (waiting.framesInPlace < FRAMES_IN_PLACE) {
            frameSignal.trySend(Unit)
            return
        }
        handoff = null
        if (waiting.epoch != queue.epoch || waiting.commit != commits) return
        if (waiting.replay) push(waiting.epoch, EngineEvent.FirstPaint) else markPainted(waiting.epoch)
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

    // --- WebView2's events ---

    internal fun onEvent(event: WinEvent) {
        if (released) return
        when (event) {
            is WinEvent.NavStarting -> onNavStarting(event)
            is WinEvent.Committed -> onCommitted(event)
            is WinEvent.SourceChanged -> onSourceChanged(event)
            is WinEvent.History -> if (event.epoch == queue.epoch && !awaitingStart && historyCleared) pushHistory(event.canGoBack, event.canGoForward)
            is WinEvent.Title -> if (event.epoch == queue.epoch && !awaitingStart && committedEpoch == queue.epoch) reportTitle(pageTitle(event.title, committedUrl))
            is WinEvent.DomLoaded -> if (event.epoch == queue.epoch && event.id == ourNavigation) push(queue.epoch, EngineEvent.Progress(DOM_PROGRESS))
            is WinEvent.Completed -> onCompleted(event)
            is WinEvent.Result -> requests.remove(event.request)?.complete(event)
            is WinEvent.Frame -> frameSignal.trySend(Unit)
            is WinEvent.ProcessFailed -> onProcessFailed(event.kind)
            is WinEvent.ScriptReady -> if (event.hresult != 0L && event.epoch == queue.epoch) onCrashed()
            is WinEvent.Message -> onMessage(event)
            is WinEvent.Resource -> onResource(event)
            is WinEvent.Dialog -> onDialog(event)
            is WinEvent.Permission -> onPermission(event)
            is WinEvent.Download -> onDownload(event)
            is WinEvent.NewWindow -> onNewWindow(event)
            is WinEvent.DevToolsEvent -> onDevToolsEvent(event)
            // Tasks 24–25 handle the rest: Focus, MoveFocus, Cursor.
            else -> Unit
        }
    }

    private fun onNavStarting(event: WinEvent.NavStarting) {
        if (event.epoch != queue.epoch || !event.mainFrame) return
        when (event.verdict) {
            WinGlue.OWN -> {
                if (awaitingStart) {
                    awaitingStart = false
                    ourNavigation = event.id
                    push(queue.epoch, EngineEvent.Progress(START_PROGRESS))
                }
                // The epoch's own load, a history navigation, a redirect, a new window opened here: a document is on its
                // way, and its first words wait for its commit (as Linux's LOAD_STARTED marks every load).
                provisional = true
            }
            // The engine's own navigation went somewhere it may not (a redirect to another scheme): an error, never silence.
            WinGlue.REFUSE_OWN -> {
                awaitingStart = false
                provisional = false
                own.set(null)
                push(queue.epoch, EngineEvent.Progress(null))
                push(queue.epoch, EngineEvent.Error(WebError.BlockedByPolicy(event.url)))
            }
            WinGlue.ALLOW -> if (!awaitingStart) provisional = true
        }
    }

    private fun onCommitted(event: WinEvent.Committed) {
        if (event.epoch != queue.epoch || awaitingStart) return
        // WebView2 commits a blank error document when its own error page is off: not the content (L3).
        if (event.errorPage) return
        // The last document's questions get their safe defaults, and the client's dialogs for them go (the glue answers
        // its side at ContentLoading too; each question is answered once, whichever side runs first).
        answerAllByDefault()
        val ours = event.id == ourNavigation
        provisional = false
        own.set(null)
        // Frames from now on are this document's: paint holding showed the last one until now.
        if (frames.hidesAll) frames.hideUntil(event.frameSeq + 1)
        committedEpoch = queue.epoch
        committedUrl = event.url
        val commit = ++commits
        document = null
        documentPainted = false
        if (httpError?.url != event.url) httpError = null
        push(queue.epoch, EngineEvent.UrlChanged(event.url))
        reportedTitle = null
        push(queue.epoch, EngineEvent.TitleChanged(null))
        push(queue.epoch, EngineEvent.Progress(COMMIT_PROGRESS))
        if (ours && !historyCleared) clearHistory()
        pushHistory(history?.canGoBack ?: false, history?.canGoForward ?: false)
        releaseHeld()
        val epoch = queue.epoch
        CoroutineScope(scope.coroutineContext + lease).launch {
            delay(CONTENT_WAIT_MILLIS)
            if (commits == commit) handOff(epoch)
        }
    }

    /** A URL change within the document (pushState, a fragment), as WebView2 reports it (D6). */
    private fun onSourceChanged(event: WinEvent.SourceChanged) {
        if (event.newDocument || event.epoch != queue.epoch || awaitingStart || provisional || committedEpoch != queue.epoch) return
        val url = event.url ?: return
        if (url == committedUrl || Wv2Navigation.isFloor(url)) return
        committedUrl = url
        push(queue.epoch, EngineEvent.UrlChanged(url))
    }

    private fun onCompleted(event: WinEvent.Completed) {
        if (event.epoch != queue.epoch || awaitingStart) return
        // Whichever way it ended, a navigation that didn't commit kept its document, whose words were held meanwhile.
        try {
            completed(event)
        } finally {
            releaseHeld()
        }
    }

    private fun completed(event: WinEvent.Completed) {
        val ours = event.id == ourNavigation
        if (!event.success) {
            provisional = false
            push(queue.epoch, EngineEvent.Progress(null))
            // A page's own navigation that failed leaves its document as it was; and a cancelled one is no error (L4).
            if (!ours || event.webError == WinGlue.ERROR_CANCELED) return
            own.set(null)
            val url = event.url
            val error = if (event.webError in WinGlue.ERROR_CERTIFICATE_FIRST..WinGlue.ERROR_CERTIFICATE_LAST) {
                WebError.Certificate(url, "certificate error ${event.webError}")
            } else {
                WebError.Network(url, "load failed (WebView2 status ${event.webError})")
            }
            push(queue.epoch, EngineEvent.Error(error))
            return
        }
        provisional = false
        push(queue.epoch, EngineEvent.Progress(null))
        if (event.httpStatus >= 400 && committedEpoch == queue.epoch) {
            val error = WebError.Http(event.url, event.httpStatus)
            if (documentPainted) push(queue.epoch, EngineEvent.Error(error)) else httpError = error
        }
        // A document the script never reached (scripts off before V2 is known, a PDF): its load says it has content.
        if (committedEpoch == queue.epoch && document == null && !documentPainted) handOff(queue.epoch)
    }

    /** Task 22 adds the unresponsive renderer's check. */
    private fun onProcessFailed(kind: Int) {
        when (kind) {
            WinGlue.FAILED_BROWSER, WinGlue.FAILED_RENDERER, WinGlue.FAILED_NO_DEVICE -> onCrashed()
            else -> Unit   // a subframe's renderer, a utility process, WebView2's own GPU process: WebView2 carries on
        }
    }

    private fun onCrashed() {
        if (crashed) return
        crashed = true
        publish()
        push(queue.epoch, EngineEvent.Crashed)
    }

    /** D10: the floor goes from the history once the lease's own document is in. */
    private fun clearHistory() {
        val epoch = queue.epoch
        CoroutineScope(scope.coroutineContext + lease).launch {
            val result = call(HISTORY_MILLIS) { page.devtools(it, "Page.resetNavigationHistory", "{}") }
            if (epoch != queue.epoch) return@launch
            historyResettable = result?.ok == true
            historyCleared = true
            history = null
            pushHistory(false, false)
        }
    }

    private fun reportTitle(title: String?) {
        if (title == reportedTitle) return
        reportedTitle = title
        push(queue.epoch, EngineEvent.TitleChanged(title))
    }

    private fun pushHistory(canGoBack: Boolean, canGoForward: Boolean) {
        val next = EngineEvent.HistoryChanged(canGoBack && historyCleared, canGoForward && historyCleared)
        if (next == history) return
        history = next
        push(queue.epoch, next)
    }

    // --- Helpers ---

    private fun leaseCallAllowed(): Boolean = !released && !crashed && !resetting && !resetFailed

    /** Queues [event] of [epoch]; an earlier epoch's is dropped, but a crash and focus changes (the engine's and the view's). */
    private fun push(epoch: Int, event: EngineEvent, document: Any? = null) {
        if (epoch != queue.epoch && event != EngineEvent.Crashed && event !is EngineEvent.FocusChanged) return
        queue.offer(event, document)
    }

    /** A new document of [content] starts: a new epoch, a new secret and its script; nothing of an earlier one is heard. */
    private fun startDocument(content: WebContent): Int {
        val epoch = queue.advance()
        this.content = content
        secret = Wv2Scripts.newSecret()
        page.setScript(epoch, secret, documentScript())
        frames.hideUntil(Long.MAX_VALUE)
        documentPainted = false
        handoff = null
        httpError = null
        document = null
        held.clear()
        history = null
        awaitingStart = true
        ourNavigation = null
        provisional = false
        return epoch
    }

    private fun documentScript(): String =
        scripts.document(secret, setup?.bridgeOrigins.orEmpty(), shim = setup?.javaScriptEnabled == true)

    private fun flagsOf(setup: PageSetup): Int =
        (if (setup.javaScriptEnabled) WinGlue.CONF_SCRIPTS else 0) or
            (if (setup.inspectable) WinGlue.CONF_INSPECTABLE else 0) or
            (if (setup.dark) WinGlue.CONF_DARK else 0) or
            (if (!config.mediaPlaybackRequiresGesture) WinGlue.CONF_AUTOPLAY else 0)

    private fun backgroundOf(setup: PageSetup): Int = when {
        setup.transparentBackground -> 0x00000000
        setup.dark -> 0xFF000000.toInt()
        else -> -1
    }

    /** One request to the glue and its answer, or null after [millis] (or a release). */
    private suspend fun call(millis: Long, send: (Long) -> Unit): WinEvent.Result? {
        if (released) return null
        val request = nextRequest++
        val done = CompletableDeferred<WinEvent.Result>()
        requests[request] = done
        send(request)
        return try {
            withTimeoutOrNull(millis) { done.await() }
        } finally {
            requests.remove(request)
        }
    }

    /** Wipes this engine's own profile (D8, L23). True once WebView2 says it did. */
    private suspend fun wipeData(): Boolean = call(WIPE_MILLIS) { page.clearData(it) }?.ok == true

    private fun answerAllByDefault() {
        for (pending in questions.toList()) {
            pending.job?.cancel()
            pending.answerByDefault()
        }
        questions.clear()
    }

    companion object {
        private const val START_PROGRESS = 0.1f
        private const val COMMIT_PROGRESS = 0.5f
        private const val DOM_PROGRESS = 0.8f
        private const val EVAL_MILLIS = 10_000L
        private const val WIPE_MILLIS = 10_000L
        private const val HISTORY_MILLIS = 5_000L

        /** How long a committed document may show nothing contentful before it is shown anyway (the iOS and Linux 3 s). */
        private const val CONTENT_WAIT_MILLIS = 3_000L

        /** Compose frames a first paint waits after a surface drew the page (the iOS engine's two display ticks). */
        private const val FRAMES_IN_PLACE = 2

        /** The glue's request id for a call whose result nobody waits for. */
        private const val NO_ANSWER = 0L

        /** A document's words that may wait for its commit: a `start`, a `$ready` and some. */
        private const val HELD_LIMIT = 64

        private const val PLAIN = "Content-Type: text/plain"

        // COREWEBVIEW2_SCRIPT_DIALOG_KIND and COREWEBVIEW2_PERMISSION_KIND (W16).
        private const val DIALOG_ALERT = 0
        private const val DIALOG_CONFIRM = 1
        private const val DIALOG_PROMPT = 2
        private const val DIALOG_BEFORE_UNLOAD = 3
        private const val PERMISSION_MICROPHONE = 1
        private const val PERMISSION_CAMERA = 2
        private const val PERMISSION_GEOLOCATION = 3
        private const val PERMISSION_NOTIFICATIONS = 4
        private const val PERMISSION_CLIPBOARD_READ = 6

        /** `DOM.describeNode`'s answer: the input's `accept`, as the client's MIME types and extensions. */
        fun acceptOf(json: String): List<String> {
            val attributes = runCatching { Json.parseToJsonElement(json).jsonObject["node"]?.jsonObject?.get("attributes")?.jsonArray }.getOrNull() ?: return emptyList()
            val values = attributes.map { it.jsonPrimitive.contentOrNull.orEmpty() }
            val at = values.withIndex().firstOrNull { (i, name) -> i % 2 == 0 && name.equals("accept", ignoreCase = true) }?.index ?: return emptyList()
            return values.getOrNull(at + 1).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        /** A chosen file as WebView2 takes it: a Windows path, from a `file:` URI or as given. Null for anything else. */
        fun localPath(uri: String): String? {
            val path = when {
                uri.startsWith("file:", ignoreCase = true) -> runCatching { java.net.URI(uri).path }.getOrNull() ?: return null
                else -> uri
            }.removePrefix("/").takeIf { it.length > 2 && it[1] == ':' } ?: return null
            return path.replace('/', '\\')
        }

        /** [resource]'s type, then its own headers, `Name: value` lines; the type and length are the engine's to set. */
        fun responseHeaders(resource: WebResource): String = buildString {
            append("Content-Type: ").append(resource.mimeType)
            for ((name, value) in resource.headers) {
                if (name.equals("Content-Type", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) continue
                if (name.isEmpty() || name.any { it == ':' || it == '\n' || it == '\r' } || value.any { it == '\n' || it == '\r' }) continue
                append("\r\n").append(name).append(": ").append(value)
            }
        }

        /** `Name: value` lines for WebView2's request, without any a line break could smuggle in. */
        fun headersOf(headers: Map<String, String>): String? = headers.entries
            .filter { (name, value) -> name.isNotEmpty() && name.none { it == ':' || it == '\r' || it == '\n' } && value.none { it == '\r' || it == '\n' } }
            .joinToString("\r\n") { (name, value) -> "$name: $value" }
            .ifEmpty { null }
    }
}
