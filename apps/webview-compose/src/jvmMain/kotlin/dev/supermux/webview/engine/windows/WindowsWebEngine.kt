package dev.supermux.webview.engine.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
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
import dev.supermux.webview.engine.frames.FrameLook
import dev.supermux.webview.engine.frames.FrameStore
import dev.supermux.webview.engine.frames.over
import dev.supermux.webview.engine.frames.sameColour
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
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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

    private val queue = EngineEventQueue()

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

    /**
     * Whether the page is closed: released, or closed at its crash ([onCrashed]). A closed page takes nothing but its
     * release, and answers nothing; the surface still copies its last frame.
     */
    private val pageClosed: Boolean get() = released || crashed

    override var client: EngineClient? = null
        set(value) {
            if (released) return
            field = value
            publish()
        }

    /**
     * Whom to tell when the page's renderer or browser dies, its device is lost, or it is ended as hung ([onCrashed]): the
     * pool, while the page is parked. Called on the main thread, once, after the crash is queued and the page closed;
     * never for a [release].
     */
    override var onUnusable: (() -> Unit)? = null

    init {
        WinPages.listen(page.token, ::onEvent)
        WinPages.decide(page.token) { _, flags, url -> Wv2Navigation.decide(flags, url, snapshot, own) }
        WinPages.keys(page.token) { vk, scan, flags -> keyRouter.offer(vk, scan, flags) }
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
        if (content != null && (before == null || before.bridgeOrigins != setup.bridgeOrigins || before.javaScriptEnabled != setup.javaScriptEnabled)) {
            page.setScript(queue.epoch, secret, documentScript())
        }
    }

    override fun load(content: WebContent) {
        if (!leaseCallAllowed()) return
        if (content is WebContent.Url && keepsDocument(content.url)) return loadInDocument(content)
        val epoch = startDocument(content)
        val url = when (content) {
            is WebContent.Url -> content.url
            is WebContent.Html -> content.origin.url
            is WebContent.Bundle -> content.origin.url + escapeUrlPath(content.entry)
        }
        own.set(OwnNavigation.Load(url))
        serveFor(content)
        page.navigate(epoch, url, (content as? WebContent.Url)?.let { headersOf(it.headers) })
    }

    override fun reload() {
        if (!leaseCallAllowed()) return
        val shown = content ?: return
        // L3: nothing of this epoch committed (a failed load): WebView2's reload would reload what failed, or the floor.
        if (committedEpoch != queue.epoch) {
            load(shown)
            return
        }
        val epoch = startDocument(shown)
        own.set(OwnNavigation.Reload)
        page.go(epoch, WinGlue.GO_RELOAD)
    }

    /** The glue serves [content]'s local origin: the HTML itself, or a bundle's requests to the provider (D7). */
    private fun serveFor(content: WebContent) {
        when (content) {
            is WebContent.Url -> page.serve(null, null, null)
            is WebContent.Html -> page.serve(hostOf(content.origin.url), content.html.encodeToByteArray(), null)
            is WebContent.Bundle -> page.serve(hostOf(content.origin.url), null, null)
        }
    }

    /** Whether loading [url] keeps the painted document: the same URL with another fragment. */
    private fun keepsDocument(url: String): Boolean {
        val shown = committedUrl ?: return false
        return documentPainted && committedEpoch == queue.epoch && '#' in url && shown.substringBefore('#') == url.substringBefore('#')
    }

    /**
     * A load that only changes the fragment (V5; the Android review's fix 2): the document stays. A new epoch, but the
     * same secret, so the document still signs; its URL reported; a first paint once it is drawn, which it already is.
     * The glue goes on stamping the document's own events with the epoch it committed in ([ofDocument]).
     */
    private fun loadInDocument(content: WebContent.Url) {
        val epoch = queue.advance()
        this.content = content
        page.setScript(epoch, secret, documentScript())
        committedEpoch = epoch
        keptDocument = true
        awaitingStart = false
        ourNavigation = null
        own.set(OwnNavigation.Load(content.url))
        page.navigate(epoch, content.url, headersOf(content.headers))
        committedUrl = content.url
        push(epoch, EngineEvent.UrlChanged(content.url))
        history = null
        pushHistory(nativeHistory.first, nativeHistory.second)
        push(epoch, EngineEvent.TitleChanged(reportedTitle))
        waitForDrawing(Handoff(epoch, commits, page.frameSeq().coerceAtLeast(0), replay = true))
    }

    /** The epoch the current document committed in: the glue stamps the document's own events with it (wv2.h). */
    private var documentEpoch = -1

    /** Whether a fragment-only load ([loadInDocument]) kept the document of [documentEpoch] into the current epoch. */
    private var keptDocument = false

    /**
     * Whether an event the glue stamped with [epoch] (a document's: its title, history, dialogs, requests) is the current
     * document's: the current epoch's, or, after a fragment-only load kept the document, the epoch it committed in.
     */
    private fun ofDocument(epoch: Int): Boolean = epoch == queue.epoch || (keptDocument && epoch == documentEpoch)

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
        if (!pageClosed) page.go(0, WinGlue.GO_STOP)
    }

    /** The app's script, run as [runScript] runs every host script: never as the user's gesture. */
    override suspend fun evaluate(script: String): String? {
        if (released) return null
        val result = call(EVAL_MILLIS) { runScript(it, script) } ?: return null
        return if (result.ok) evaluatedJson(result.json) else null
    }

    /**
     * Runs [expression] in the main frame's page world, as CDP's `Runtime.evaluate` with no user gesture, and answers
     * [request] with CDP's result. Never through `ExecuteScript` ([WinPage.execute]): WebView2 runs that as a user gesture
     * (the page sees `navigator.userActivation.isActive`, and a navigation, a timer or a permission request it starts
     * counts as the user's). So the app's scripts, the bridge's deliveries, the heartbeat and the restored scroll all
     * come this way, which costs the same (p50 0.47 against 0.48 ms on 156).
     */
    private fun runScript(request: Long, expression: String) {
        page.devtools(request, "Runtime.evaluate", evaluateParams(expression))
    }

    /**
     * A document's words heard before its commit, in order (V6), each with when the glue had it: heard right after the
     * commit. At most [HELD_LIMIT].
     */
    private val held = ArrayList<Pair<String, Long>>()

    /**
     * Delivers [json] to the main frame's shim if its document's origin is [targetOrigin] when it arrives: by evaluating
     * a call to the receiver the document script installed at document creation, which compares the origin it captured
     * then (D5; the contract's **Delivery** for WebView2), with no user gesture ([runScript]). Nothing waits for an
     * answer.
     */
    override fun postMessage(json: String, targetOrigin: String) {
        if (!leaseCallAllowed()) return
        runScript(NO_ANSWER, scripts.deliveryCall(json, targetOrigin))
    }

    /** A failed load's error page is shown: it has no bridge, and its words reach no one (L3). */
    private var errorShown = false

    private fun onMessage(event: WinEvent.Message) {
        if (!ofDocument(event.epoch) || errorShown) return
        // Before this epoch's commit, or while a navigation commits, WebView2 may tell the next document's first words
        // before its commit: they wait for it.
        if (committedEpoch != queue.epoch || provisional) {
            if (held.size < HELD_LIMIT) held += event.body to event.arrivedNs
            return
        }
        hear(event.body, event.arrivedNs)
    }

    /** What the held words were waiting for came: a commit, or a navigation that ended without one. */
    private fun releaseHeld() {
        if (held.isEmpty()) return
        val words = held.toList()
        held.clear()
        words.forEach { (body, arrivedNs) -> hear(body, arrivedNs) }
    }

    /** One of the document script's words, [arrivedNs] when the glue had it (System.nanoTime's clock; 0 unknown). */
    private fun hear(body: String, arrivedNs: Long) {
        val parsed = RelayMessage.parse(body) ?: return
        if (parsed is RelayMessage.Start) {
            document = parsed.document
            return
        }
        if (parsed.document != document) return
        val epoch = committedEpoch
        when (parsed) {
            is RelayMessage.Paint -> handOff(epoch, presentedAt(parsed, arrivedNs), parsed.background)
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
        if (shown == null || !ofDocument(event.epoch) || resetting || !shown.origin.owns(event.url)) {
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
        if (!ofDocument(event.epoch)) return if (event.kind == DIALOG_BEFORE_UNLOAD) leave() else dismiss()
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
        if (!ofDocument(event.epoch)) return deny()
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
        if (!ofDocument(event.epoch)) return
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
        if (!ofDocument(event.epoch)) return
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
        if (!ofDocument(event.epoch) || event.name != "Page.fileChooserOpened") return
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

    /**
     * The page's last frame, copied: at the window's pixels (the surface's), in full colour, its alpha kept for a
     * transparent page, and never what the app draws over it. Null before the document's first paint (L25), and with no
     * surface composed at a size.
     */
    override suspend fun snapshot(): ImageBitmap? {
        if (released || !documentPainted || surfaces == 0 || placedSize == null) return null
        // A frame of nothing after the page's (its renderer gone, WebView2's GPU process starting again) is no cover.
        if (!mayShowThePage(frames.look, background = null)) return null
        return frames.copy()
    }

    /** A restored page's scroll, applied once its load completes. */
    private var restoringScroll: Pair<Double, Double>? = null

    /** The page's URL and scroll (D11); nothing for in-memory HTML, the floor, or a load that never committed (L3). */
    override suspend fun saveState(): ByteArray? {
        if (released) return null
        val shown = content ?: return null
        if (shown is WebContent.Html || committedEpoch != queue.epoch) return null
        val url = committedUrl?.takeUnless(Wv2Navigation::isFloor) ?: return null
        val scroll = evaluate("[scrollX, scrollY]")?.let(::scrollOf) ?: (0.0 to 0.0)
        return Wv2SavedState.encode(shown.key, url, scroll)
    }

    /**
     * Loads the saved page as the engine's own `Restore` (never the client's to decide), serving [content]'s local files.
     * False for another content's state, the floor, a scheme other than http(s), or in-memory HTML. WebView2 can't
     * restore a back list: the restored page has none (the README says so).
     */
    override fun restoreState(state: ByteArray, content: WebContent): Boolean {
        if (!leaseCallAllowed() || content is WebContent.Html) return false
        val saved = Wv2SavedState.decode(state, content.key) ?: return false
        val scheme = schemeOf(saved.url)
        if (Wv2Navigation.isFloor(saved.url) || (scheme != "http" && scheme != "https")) return false
        val epoch = startDocument(content)
        own.set(OwnNavigation.Restore)
        serveFor(content)
        page.navigate(epoch, saved.url, null)
        restoringScroll = saved.scroll.takeIf { it != 0.0 to 0.0 }
        return true
    }

    /** A hidden page (D21): not drawn, muted, asked to keep little memory; its document stays as it is. */
    override fun setVisible(visible: Boolean) {
        if (released || crashed || this.visible == visible) return
        this.visible = visible
        page.setVisible(visible)
    }

    /**
     * Tells a new subscriber what the page is now ([WebEngine.replayState]): its URL, history and title, and, if it had
     * painted, its first paint again once a surface has drawn it in its new place (L8). The size is the shim's to tell.
     */
    override fun replayState() {
        if (!leaseCallAllowed() || content == null || committedEpoch != queue.epoch) return
        val url = committedUrl ?: return
        if (Wv2Navigation.isFloor(url)) return
        val epoch = queue.epoch
        push(epoch, EngineEvent.UrlChanged(url))
        history = null
        pushHistory(nativeHistory.first, nativeHistory.second)
        push(epoch, EngineEvent.TitleChanged(reportedTitle))
        focused = null
        if (documentPainted) waitForDrawing(Handoff(epoch, commits, page.frameSeq().coerceAtLeast(0), replay = true))
    }

    /** Moves Compose's focus into the composed surface, and so WebView2's; with none composed, tells WebView2 alone. */
    override fun requestFocus() {
        if (pageClosed) return
        val requester = focusRequester
        if (requester == null || runCatching { requester.requestFocus() }.isFailure) page.moveFocus(WinGlue.FOCUS_PROGRAMMATIC)
    }

    override fun applyOverlays(cutouts: OverlayCutouts) = Unit

    /** The page's frames, drawn, placed and fed the mouse by [Wv2Surface]. */
    @Composable
    override fun Surface(modifier: Modifier) {
        if (released) return
        Wv2Surface(this, modifier)
    }

    /** The floor's load while a reset waits for it: completed with whether it loaded. */
    private var floorDone: CompletableDeferred<Boolean>? = null

    /** WebView2's last word on the history (once the floor is cleared), for a replay. */
    private var nativeHistory = false to false

    /**
     * Whether the page has left a question ([pageAnswers]) unanswered for [SILENT_MILLIS]: its release ends its renderer
     * first (V10). Timed on the engine's own dispatcher, as every wait of the engine is.
     */
    private var silent = false

    /**
     * Readies the engine for another lease, with the contract's postconditions (spec §3):
     * - the floor loads in an epoch of its own with a script that has no shim, and CDP clears the history to it alone
     *   (D10, V1); a WebView2 that can't clear it fails the reset now and every time after;
     * - the client's questions get their defaults, the bundle's open requests a 404;
     * - the lease's script goes (the floor's replaces it) and its grants were never saved (V7);
     * - an ephemeral engine's own profile is wiped (L23);
     * - a hung page is ended, and the engine with it (L6);
     * - nothing from before or during it is heard after it: a second epoch advance at its end.
     * False if any of it failed or didn't happen in time; the pool then destroys the engine.
     */
    override suspend fun reset(): Boolean {
        if (released || crashed || resetting || !historyResettable) return false
        resetting = true
        resetFailed = true
        // The last lease's frame never shows again: not under the next lease's cover, nor in its snapshot (L16).
        frames.hideUntil(Long.MAX_VALUE)
        queue.advance(reset = true)
        client = null
        // L25 (reset's unfocus): the keyboard goes back to the app; the next lease's page never inherits it.
        if (surfaceFocused) windows.canvasComponent(surfaceWindow)?.requestFocusInWindow()
        setup = null
        content = null
        handoff = null
        answerAllByDefault()
        finishResources()
        lease.cancel()
        lease = SupervisorJob(scope.coroutineContext[Job])
        page.go(0, WinGlue.GO_STOP)
        if (!pageAnswers(ANSWER_MILLIS)) {
            endHungPage()
            resetting = false
            return false
        }
        // L5: a release while this waited ends it here.
        if (released) return false
        page.configure(0, -1, null)
        val floorShown = showFloor()
        val wiped = !released && (config.profile != WebProfile.Ephemeral || wipeData())
        resetting = false
        if (released || crashed || !floorShown || !wiped) return false
        queue.advance(reset = true)
        lease.cancel()
        lease = SupervisorJob(scope.coroutineContext[Job])
        own.set(null)
        document = null
        committedUrl = null
        committedEpoch = -1
        documentEpoch = -1
        keptDocument = false
        errorShown = false
        reportedTitle = null
        documentPainted = false
        handoff = null
        httpError = null
        focused = null
        history = null
        held.clear()
        nativeHistory = false to false
        // D10: the floor stays the history's only entry until the next lease's first commit clears it.
        historyCleared = false
        resetFailed = false
        publish()
        return true
    }

    /** The floor in an epoch of its own, then the history cleared to it alone and checked (D10, V1). */
    private suspend fun showFloor(): Boolean {
        val epoch = queue.advance(reset = true)
        secret = Wv2Scripts.newSecret()
        page.setScript(epoch, secret, scripts.document(secret, emptySet(), shim = false))
        awaitingStart = true
        ourNavigation = null
        val done = CompletableDeferred<Boolean>()
        floorDone = done
        own.set(OwnNavigation.Floor)
        page.serve(null, null, null)
        page.navigate(epoch, Wv2Navigation.FLOOR_URL, null)
        val loaded = withTimeoutOrNull(FLOOR_MILLIS) { done.await() } ?: false
        floorDone = null
        if (!loaded || released || crashed) return false
        val cleared = call(HISTORY_MILLIS) { page.devtools(it, "Page.resetNavigationHistory", "{}") }?.ok == true
        if (!cleared) {
            historyResettable = false
            return false
        }
        val entries = call(HISTORY_MILLIS) { page.devtools(it, "Page.getNavigationHistory", "{}") }?.takeIf { it.ok }?.json ?: return false
        return onlyTheFloor(entries)
    }

    /** Whether CDP's `Page.getNavigationHistory` answer lists the floor and nothing else. */
    private fun onlyTheFloor(json: String): Boolean {
        val entries = runCatching { Json.parseToJsonElement(json).jsonObject["entries"]?.jsonArray }.getOrNull() ?: return false
        return entries.size == 1 && Wv2Navigation.isFloor(entries[0].jsonObject["url"]?.jsonPrimitive?.contentOrNull)
    }

    /** Whether the page's renderer answers a script within [millis], with a result or an error. */
    private suspend fun pageAnswers(millis: Long): Boolean {
        if (pageClosed) return false
        val silence = scope.launch {
            delay(SILENT_MILLIS)
            silent = true
        }
        val answered = try {
            call(millis) { runScript(it, "1") } != null
        } finally {
            silence.cancel()
        }
        if (answered) silent = false
        // A crash meanwhile answered the question for a page that is closed: it answers nothing.
        return answered && !pageClosed
    }

    /**
     * Ends the page's renderer, which no longer answers, and the engine: CDP's `Page.crash` (from the browser side, L6:
     * it ends a renderer another page shares too), then the crash, whose close ends the renderer at once ([onCrashed]).
     */
    private fun endHungPage() {
        if (released || crashed) return
        page.devtools(NO_ANSWER, "Page.crash", "{}")
        onCrashed()
    }

    private var heartbeat: Job? = null
    private var unresponsive: Job? = null

    /**
     * While a surface shows the page: asked every [HEARTBEAT_MILLIS] whether it answers, ended after [HUNG_MILLIS] of
     * silence (the Linux review's I2: an input-driven check misses a page nobody types into). Not while a dialog holds it.
     */
    private fun startHeartbeat() {
        if (heartbeat != null || released || crashed) return
        heartbeat = scope.launch {
            while (true) {
                delay(HEARTBEAT_MILLIS)
                if (!visible || questions.any { it.holdsPage }) continue
                if (pageAnswers(HUNG_MILLIS) || !visible || questions.any { it.holdsPage }) continue
                heartbeat = null
                endHungPage()
                return@launch
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }

    /** WebView2 found the renderer unresponsive to input: it gets [UNRESPONSIVE_GRACE_MILLIS] to answer a script. */
    private fun onUnresponsive() {
        if (released || crashed || unresponsive != null) return
        // Started once it is held here, so its end never runs before the hold.
        val check = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!pageAnswers(UNRESPONSIVE_GRACE_MILLIS)) endHungPage()
            } finally {
                unresponsive = null
            }
        }
        unresponsive = check
        check.start()
    }

    override fun release() {
        if (released) return
        // Read before anything completes the pending requests, which ends their silence: a page that left one unanswered
        // for a second has its renderer ended first (V10). A page closed at its crash has none left.
        val endRenderer = silent && !crashed
        answerAllByDefault()
        client = null
        released = true
        publish()
        answerRequests()
        floorDone?.complete(false)
        stopHeartbeat()
        finishResources()
        // AWT's listeners are process-wide: nothing of them keeps a released engine.
        focusRequester?.let(::unbindSurface)
        scope.cancel()
        WinPages.forget(page.token)
        if (endRenderer) page.devtools(NO_ANSWER, "Page.crash", "{}")
        page.release()
        frames.close()
        onReleased()
    }

    // --- Frames and the handoff (Linux's, Task 24 adds the surface around them) ---

    /**
     * The page's frames: what every surface of this engine draws, and what a snapshot copies (D4). Guarded: skiko's
     * Direct3D renderer draws Compose's recordings on a thread of its own, after the EDT moved on.
     */
    internal val frames = FrameStore(page, guardDraws = true, looks = true).apply { hideUntil(Long.MAX_VALUE) }

    /** A frame was captured, or a handoff ticks: the surface's next Compose frame has work. */
    private val frameSignal = Channel<Unit>(Channel.CONFLATED)

    /** Surfaces in the composition now. */
    private var surfaces = 0

    /** The size the page was last placed at, in pixels: what a frame drawn in place has. */
    private var placedSize: Pair<Int, Int>? = null

    /** The page's cursor over the surface (W8). */
    internal var cursor: PointerIcon by mutableStateOf(PointerIcon.Default)
        private set

    /** Where surfaces are, for WebView2 (SurfaceWindows); tests set a fake. */
    internal var windows: SurfaceWindows = AwtSurfaceWindows

    private var placement = BoundsDebouncer(WinDefaults.SIZE_SETTLE_NANOS)
    private var placedAt: SurfaceWindows.Placement? = null

    internal val placementPending: Boolean get() = placement.hasPending

    internal fun onSurfaceAttached(attached: Boolean) {
        val before = surfaces
        surfaces = (surfaces + if (attached) 1 else -1).coerceAtLeast(0)
        if (before == 0 && surfaces > 0) frames.forgetDrawn()
        if (surfaces == 0) {
            handoff?.framesInPlace = -1
            // No surface shows the page: the parking window is its parent until one does.
            if (!pageClosed) page.unplace()
            placedAt = null
            placement = BoundsDebouncer(WinDefaults.SIZE_SETTLE_NANOS)
        }
        if (surfaces > 0) startHeartbeat() else stopHeartbeat()
    }

    /**
     * The surface is at ([x], [y]) in its window's Compose root, [width] × [height] pixels: moves go to WebView2 at once,
     * sizes once they settle (C9), and a new window or scale at once (another monitor, L26).
     */
    internal fun onSurfacePlaced(window: java.awt.Window?, x: Int, y: Int, width: Int, height: Int, now: Long = System.nanoTime()) {
        if (pageClosed || width <= 0 || height <= 0) return
        val where = windows.of(window) ?: return
        val moved = placedAt?.let { it.frame != where.frame || it.canvas != where.canvas || it.scale != where.scale } ?: true
        placedAt = where
        if (moved) placement = BoundsDebouncer(WinDefaults.SIZE_SETTLE_NANOS)
        placement.update(BoundsDebouncer.Rect(x, y, width, height), now)?.let(::applyPlacement)
    }

    internal fun settlePlacement(now: Long) {
        if (!pageClosed) placement.poll(now)?.let(::applyPlacement)
    }

    private fun applyPlacement(rect: BoundsDebouncer.Rect) {
        val where = placedAt ?: return
        page.place(where.frame, where.canvas, rect.x, rect.y, rect.width, rect.height, where.scale)
        onSurfacePlacedForTests(rect.width, rect.height)
    }

    /** The window moved: WebView2's popups and IME window follow, and the capture its monitor's adapter (D24). */
    internal fun onWindowMoved() {
        if (!pageClosed) page.parentMoved()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    internal fun pointer(event: PointerEvent) {
        if (released || crashed) return
        val change = event.changes.firstOrNull() ?: return
        val mouse = event.nativeEvent as? java.awt.event.MouseEvent
        val kind = when (event.type) {
            PointerEventType.Enter -> MouseInput.Kind.Enter
            PointerEventType.Move -> MouseInput.Kind.Move
            PointerEventType.Exit -> MouseInput.Kind.Exit
            PointerEventType.Press -> MouseInput.Kind.Press
            PointerEventType.Release -> MouseInput.Kind.Release
            PointerEventType.Scroll -> MouseInput.Kind.Scroll
            else -> return
        }
        // Compose's own button (as the Linux surface reads it); AWT's where Compose names none. AWT's click count.
        val button = when (event.button) {
            PointerButton.Secondary -> 3
            PointerButton.Tertiary -> 2
            PointerButton.Primary -> 1
            else -> mouse?.button?.takeIf { it in 1..3 } ?: 1
        }
        val k = event.keyboardModifiers
        val send = MouseInput.of(
            kind, button, mouse?.clickCount ?: 1, change.position.x, change.position.y,
            change.scrollDelta.x, change.scrollDelta.y, event.buttons.isPrimaryPressed, event.buttons.isSecondaryPressed,
            event.buttons.isTertiaryPressed, k.isShiftPressed, k.isCtrlPressed,
        )
        page.mouse(send.kind, send.keys, send.data, send.x, send.y)
    }

    /** WebView2's cursor as an AWT one (W8): AWT's own type, or the pixels the glue sent. */
    private fun cursorOf(event: WinEvent.Cursor): PointerIcon {
        val custom = event.custom
        if (custom == null) return PointerIcon(java.awt.Cursor.getPredefinedCursor(CursorMapping.awtType(event.systemId) ?: java.awt.Cursor.DEFAULT_CURSOR))
        return runCatching {
            val image = java.awt.image.BufferedImage(custom.width, custom.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, custom.width, custom.height, custom.argb, 0, custom.width)
            PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(image, java.awt.Point(custom.hotX, custom.hotY), "webview"))
        }.getOrDefault(PointerIcon.Default)
    }

    // --- Focus and keys (D15, W4): FocusBroker decides, KeyRouter offers accelerators to the app first ---

    private var focusRequester: androidx.compose.ui.focus.FocusRequester? = null
    private var focusManager: androidx.compose.ui.focus.FocusManager? = null
    private var surfaceWindow: java.awt.Window? = null

    private val broker = FocusBroker(
        moveFocus = { if (!pageClosed) page.moveFocus(it) },
        returnToAwt = { windows.canvasComponent(surfaceWindow)?.requestFocusInWindow() },
    )

    /** The app's shortcuts while the page has the keyboard: offered to Compose first (D15). */
    private val keyRouter = KeyRouter(target = { if (surfaceFocused) canvasForKeys else null })

    @Volatile private var surfaceFocused = false
    @Volatile private var canvasForKeys: java.awt.Component? = null

    private val activation = object : java.awt.event.WindowAdapter() {
        override fun windowActivated(e: java.awt.event.WindowEvent) = broker.onWindowActivated()
    }
    private val ownerWatch = java.beans.PropertyChangeListener { e ->
        broker.onAwtFocusOwner(e.newValue != null && e.newValue === windows.canvasComponent(surfaceWindow))
    }

    internal fun bindSurface(focus: androidx.compose.ui.focus.FocusRequester, manager: androidx.compose.ui.focus.FocusManager, window: java.awt.Window?) {
        if (released) return
        focusRequester = focus
        focusManager = manager
        if (window !== surfaceWindow) {
            surfaceWindow?.removeWindowListener(activation)
            surfaceWindow = window
            window?.addWindowListener(activation)
        }
        canvasForKeys = windows.canvasComponent(window)
        TabWatcher.install()
        // Process-wide: held once at most, whatever binds overlap (a surface composed in its new place before the old goes).
        val focusOwners = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusOwners.removePropertyChangeListener("focusOwner", ownerWatch)
        focusOwners.addPropertyChangeListener("focusOwner", ownerWatch)
    }

    internal fun unbindSurface(focus: androidx.compose.ui.focus.FocusRequester) {
        if (focusRequester !== focus) return
        focusRequester = null
        focusManager = null
        surfaceWindow?.removeWindowListener(activation)
        surfaceWindow = null
        canvasForKeys = null
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", ownerWatch)
    }

    internal fun onSurfacePress(focus: androidx.compose.ui.focus.FocusRequester) {
        if (released || crashed) return
        broker.onPress()
        runCatching { focus.requestFocus() }
    }

    internal fun onSurfaceFocus(focused: Boolean) {
        if (released) return
        surfaceFocused = focused
        broker.lastTab(TabWatcher.shift, TabWatcher.at)
        broker.onComposeFocus(focused)
        if (!focused) setFocused(queue.epoch, false)
    }

    /** WebView2's keyboard focus (D6): reported as the page's, with a deactivated window keeping it (FocusBroker). */
    private fun onPageFocus(focused: Boolean) {
        broker.onPageFocus(focused)
        val active = surfaceWindow?.isActive ?: true
        setFocused(queue.epoch, broker.reportedFocus(active, focused))
        // A page focused without Compose knowing (a reactivation, a click that raced): Compose follows.
        if (focused && !surfaceFocused) runCatching { focusRequester?.requestFocus() }
    }

    /** The page tabbed out of its last (or first) element: Compose moves on (W4: F4b, F4d). */
    private fun onMoveFocusRequested(reason: Int) {
        focusManager?.moveFocus(if (reason == WinGlue.FOCUS_PREVIOUS) androidx.compose.ui.focus.FocusDirection.Previous else androidx.compose.ui.focus.FocusDirection.Next)
    }

    private fun setFocused(epoch: Int, value: Boolean) {
        if (focused == value) return
        focused = value
        push(epoch, EngineEvent.FocusChanged(value))
    }

    internal fun onSurfaceFrame(full: Boolean) {
        if (released) return
        frames.pull(full)
        // Every block still held by skiko's recordings: the surface pulls again at its next frame, as WebView2 sends no
        // other for a page that stopped changing.
        if (frames.waiting) frameSignal.trySend(Unit)
        advanceHandoff()
    }

    internal fun onSurfaceDrawn() {
        frames.onDrawn()
        val waiting = handoff ?: return
        if (waiting.framesInPlace < 0 && inPlace(waiting)) {
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

    /**
     * A first paint waiting to be drawn in place (Linux's Handoff): the capture's frame [target] or a newer one, composed
     * at [notBefore] or later (when the document's paint was presented: Long.MIN_VALUE, any).
     */
    private class Handoff(
        val epoch: Int,
        val commit: Int,
        val target: Long,
        val replay: Boolean,
        var notBefore: Long = Long.MIN_VALUE,
        var background: Int? = null,
    ) {
        var framesInPlace = -1
    }

    /** Whether a surface has drawn what [waiting] waits for, at the placed size. */
    private fun inPlace(waiting: Handoff): Boolean =
        frames.drawnSeq >= waiting.target && frames.drawnComposedNs >= waiting.notBefore && drawnAtSurfaceSize() &&
            mayShowThePage(frames.drawnLook, waiting.background)

    /**
     * Whether a frame that looks like [look] may be the page's, whose own background is [background] (null: unknown):
     * never WebView2's blank frames. On WARP they were composed up to 17 ms after the page's paint was presented, a
     * composition or two before its content (its empty visual, every point clear, gave a first paint, and the snapshot
     * after it showed nothing). A page with a transparent background may show nothing; a page without a background of
     * its own, or one WebView2's shows through, shows WebView2's.
     */
    private fun mayShowThePage(look: FrameLook?, background: Int?): Boolean {
        val default = backgroundOf(setup ?: return true)
        if (default ushr 24 == 0) return true
        return when (look) {
            null, FrameLook.Varied -> true
            FrameLook.Clear -> false
            is FrameLook.Uniform -> background == null || !sameColour(look.argb, default) || sameColour(over(background, default), default)
        }
    }

    /**
     * When the paint the document script waited for was presented (System.nanoTime's clock), less what its message's way
     * to the glue may add ([PRESENTED_MARGIN_NANOS]); Long.MIN_VALUE when the script couldn't say (a load that painted
     * nothing) or the glue didn't (no time of arrival).
     */
    private fun presentedAt(paint: RelayMessage.Paint, arrivedNs: Long): Long {
        val ago = paint.presentedAgoMillis ?: return Long.MIN_VALUE
        if (arrivedNs <= 0L) return Long.MIN_VALUE
        return arrivedNs - (ago * 1_000_000).toLong() - PRESENTED_MARGIN_NANOS
    }

    private var handoff: Handoff? = null

    /**
     * The committed document has content to show: the script's `paint`, a document without the script's load, or
     * [CONTENT_WAIT_MILLIS] of nothing since its commit. [EngineEvent.FirstPaint] then waits until a surface has drawn the
     * capture's newest frame of that moment, at the placed size, and [FRAMES_IN_PLACE] Compose frames have passed. A
     * paint that says when it was presented ([presentedAt]) waits for a frame composed since: WGC delivers a frame a
     * composition or two after DWM made it, behind the page's own signal (on WARP the screen was still WebView2's white
     * blank at the first paint). If none comes in [PRESENTED_WAIT_MILLIS] (the frame of the content came before the
     * signal, and a still page sends no other), the newest frame is enough.
     */
    private fun handOff(epoch: Int, notBefore: Long = Long.MIN_VALUE, background: Int? = null) {
        if (released || documentPainted || epoch != committedEpoch || Wv2Navigation.isFloor(committedUrl)) return
        if (handoff?.let { it.commit == commits && !it.replay } == true) return
        val next = Handoff(epoch, commits, page.frameSeq().coerceAtLeast(0), replay = false, notBefore = notBefore, background = background)
        waitForDrawing(next)
        if (notBefore == Long.MIN_VALUE && background == null) return
        CoroutineScope(scope.coroutineContext + lease).launch {
            delay(PRESENTED_WAIT_MILLIS)
            if (handoff !== next || next.framesInPlace >= 0) return@launch
            // A page whose own background never shows (covered all over by WebView2's colour) waits no longer; a frame
            // of nothing is never the page.
            next.notBefore = Long.MIN_VALUE
            next.background = null
            // A still page draws nothing new: what it drew is checked now.
            if (surfaces > 0 && inPlace(next)) next.framesInPlace = 0
            frameSignal.trySend(Unit)
        }
    }

    private fun waitForDrawing(next: Handoff) {
        if (surfaces > 0 && inPlace(next)) next.framesInPlace = 0
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
        // What a closed page said before its close: the glue answered its questions as it closed, and nothing is reported.
        if (pageClosed) return
        when (event) {
            is WinEvent.NavStarting -> onNavStarting(event)
            is WinEvent.Committed -> onCommitted(event)
            is WinEvent.SourceChanged -> onSourceChanged(event)
            is WinEvent.History -> if (ofDocument(event.epoch) && !awaitingStart && historyCleared) {
                nativeHistory = event.canGoBack to event.canGoForward
                pushHistory(event.canGoBack, event.canGoForward)
            }
            is WinEvent.Title -> if (ofDocument(event.epoch) && !awaitingStart && committedEpoch == queue.epoch && !errorShown) reportTitle(pageTitle(event.title, committedUrl))
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
            is WinEvent.Cursor -> cursor = cursorOf(event)
            is WinEvent.Focus -> onPageFocus(event.focused)
            is WinEvent.MoveFocus -> onMoveFocusRequested(event.reason)
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
        // The reset's floor: no lease's document, nothing to report.
        if (Wv2Navigation.isFloor(event.url)) {
            committedUrl = event.url
            provisional = false
            return
        }
        // A failed load commits an error page, which runs the document script: never the content (L3). No commit is
        // reported, and nothing it says is heard until a document of the content's commits.
        if (event.errorPage) {
            errorShown = true
            held.clear()
            document = null
            return
        }
        errorShown = false
        // The last document's questions get their safe defaults, and the client's dialogs for them go (the glue answers
        // its side at ContentLoading too; each question is answered once, whichever side runs first).
        answerAllByDefault()
        val ours = event.id == ourNavigation
        provisional = false
        own.set(null)
        // Frames from now on are this document's: paint holding showed the last one until now.
        if (frames.hidesAll) frames.hideUntil(event.frameSeq + 1)
        committedEpoch = queue.epoch
        documentEpoch = queue.epoch
        keptDocument = false
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
        if (event.newDocument || !ofDocument(event.epoch) || awaitingStart || provisional || committedEpoch != queue.epoch) return
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
        // The reset waiting for its floor goes on from here.
        if (Wv2Navigation.isFloor(event.url) && event.id == ourNavigation) {
            provisional = false
            floorDone?.complete(event.success)
            return
        }
        val ours = event.id == ourNavigation
        if (!event.success) {
            provisional = false
            push(queue.epoch, EngineEvent.Progress(null))
            // A cancelled navigation is no error (L4). One the page started that failed is one only where WebView2 put an
            // error page in its document's place (156); without one, the document stays as it was.
            if (event.webError == WinGlue.ERROR_CANCELED || (!ours && !errorShown)) return
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
        // A same-document navigation of the engine's own (a fragment, history within the document) completes without a
        // commit: its marker goes here, or a later redirect would pass for its own.
        own.get().let { if (it is OwnNavigation.Redirecting) own.compareAndSet(it, null) }
        if (ours) {
            restoringScroll?.let { (x, y) -> runScript(NO_ANSWER, "scrollTo($x, $y)") }
            restoringScroll = null
        }
        if (event.httpStatus >= 400 && committedEpoch == queue.epoch) {
            val error = WebError.Http(event.url, event.httpStatus)
            if (documentPainted) push(queue.epoch, EngineEvent.Error(error)) else httpError = error
        }
        // A document the script never reached (scripts off before V2 is known, a PDF): its load says it has content.
        if (committedEpoch == queue.epoch && document == null && !documentPainted) handOff(queue.epoch)
    }

    private fun onProcessFailed(kind: Int) {
        when (kind) {
            WinGlue.FAILED_BROWSER, WinGlue.FAILED_RENDERER, WinGlue.FAILED_NO_DEVICE -> onCrashed()
            WinGlue.FAILED_UNRESPONSIVE -> onUnresponsive()
            else -> Unit   // a subframe's renderer, a utility process, WebView2's own GPU process: WebView2 carries on
        }
    }

    /**
     * The page died, can't be drawn, or was ended as hung ([endHungPage]): the engine is never used again, and its page is
     * closed now, before anyone hears of it. What waits on the page is answered first, while it can take the answers (the
     * client's questions their defaults, the bundle's requests a 404), the lease's work stops, and the calls waiting on
     * it fail at once. Then the glue closes the controller, and the browser ends the renderer at once: WebView2's crash
     * reporter kept a renderer `Page.crash` froze for over 2 s while it wrote its report, and a crashed page outlived its
     * crash until the pool released the engine. From then on nothing reaches the page but its release, and the surface's
     * copies of its last frame (the snapshot's).
     */
    private fun onCrashed() {
        if (crashed) return
        crashed = true
        publish()
        stopHeartbeat()
        floorDone?.complete(false)
        answerAllByDefault()
        finishResources()
        lease.cancel()
        answerRequests()
        page.close()
        push(queue.epoch, EngineEvent.Crashed)
        // Last: a pool that parked the page releases the engine for it.
        onUnusable?.invoke()
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
            nativeHistory = false to false
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
        keptDocument = false
        errorShown = false
        restoringScroll = null
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

    /** One request to the glue and its answer, or null after [millis] (or a release); none of a closed page. */
    private suspend fun call(millis: Long, send: (Long) -> Unit): WinEvent.Result? {
        if (pageClosed) return null
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

    /** Every call waiting on the page gets a failed answer: it will never get one, its page closed (or released). */
    private fun answerRequests() {
        // A copy: a call resumed in place by its answer removes itself from the map.
        requests.values.toList().forEach { it.complete(WinEvent.Result(0, false, null)) }
        requests.clear()
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
        private const val FLOOR_MILLIS = 10_000L

        /** How long a reset waits for the page to answer before it takes it for hung (Linux's). */
        private const val ANSWER_MILLIS = 1_500L

        /** How often a page shown on a surface is asked whether it answers, and how long it may stay silent (Linux's). */
        private const val HEARTBEAT_MILLIS = 3_000L
        private const val HUNG_MILLIS = 5_000L

        /** WebView2's own "unresponsive" plus this: about Android's 5 s in all. */
        private const val UNRESPONSIVE_GRACE_MILLIS = 2_000L

        /** A page silent this long at its release has its renderer ended first (V10). */
        private const val SILENT_MILLIS = 1_000L

        /** How long a committed document may show nothing contentful before it is shown anyway (the iOS and Linux 3 s). */
        private const val CONTENT_WAIT_MILLIS = 3_000L

        /** Compose frames a first paint waits after a surface drew the page (the iOS engine's two display ticks). */
        private const val FRAMES_IN_PLACE = 2

        /**
         * What a paint's presentation time may be late by: its message's way from the page to the glue's UI thread. A
         * frame of the content is composed at DWM's next composition after the presentation, or later; WebView2's blank,
         * at one before it.
         */
        private const val PRESENTED_MARGIN_NANOS = 4_000_000L

        /** How long a first paint waits for a frame composed since its content was presented: then the newest frame. */
        private const val PRESENTED_WAIT_MILLIS = 500L

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

        /** `Runtime.evaluate`'s parameters for [expression]: its value returned as JSON, and never as a user gesture. */
        fun evaluateParams(expression: String): String = buildJsonObject {
            put("expression", expression)
            put("returnByValue", true)
            put("userGesture", false)
        }.toString()

        /**
         * `Runtime.evaluate`'s answer as `ExecuteScript`'s was: the value, JSON-encoded, or `null` for `undefined` and a
         * value JSON can't hold; null (no answer) for an exception, or an answer that isn't one.
         */
        fun evaluatedJson(json: String?): String? {
            val answer = json?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
            if (answer["exceptionDetails"] != null) return null
            val result = answer["result"] as? JsonObject ?: return null
            return result["value"]?.toString() ?: "null"
        }

        /** `[scrollX, scrollY]`'s JSON as a pair; null when it isn't two numbers. */
        fun scrollOf(json: String): Pair<Double, Double>? {
            val values = runCatching { Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.double } }.getOrNull() ?: return null
            return if (values.size == 2) values[0] to values[1] else null
        }

        /** A chosen file as WebView2 takes it: a Windows path, from a `file:` URI or as given. Null for anything else. */
        fun localPath(uri: String): String? {
            val path = when {
                uri.startsWith("file:", ignoreCase = true) -> runCatching { java.net.URI(uri).path }.getOrNull() ?: return null
                else -> uri
            // platform-ok: url (a file: URI's path)
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
