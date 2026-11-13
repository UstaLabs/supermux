package dev.supermux.ui.browser

import dev.supermux.webview.runtime.LocalWebViewOverlays
import dev.supermux.webview.runtime.ProvideWebViewOverlays
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.supermux.ui.keys.LocalShortcutDispatcher
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.browser.DeviceMode
import dev.supermux.net.BrowserCommentInput
import dev.supermux.net.BrowserCommentsBody
import dev.supermux.browser.PageAppearance
import dev.supermux.browser.AddressAction
import dev.supermux.browser.BrowserTabSync
import dev.supermux.browser.BrowserViewState
import dev.supermux.browser.DisplayAddress
import dev.supermux.browser.addressAction
import dev.supermux.browser.displayAddress
import dev.supermux.browser.nextZoom
import dev.supermux.net.PreviewCookieDto
import dev.supermux.net.ProxyDto
import dev.supermux.proto.ViewDto
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.RegisterCommand
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.webview.Activation
import dev.supermux.webview.InspectorResult
import dev.supermux.webview.LoadingState
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.WebClient
import dev.supermux.webview.WebContent
import dev.supermux.webview.DarkMode
import dev.supermux.webview.DeviceEmulation
import dev.supermux.webview.WebCookie
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.supermux.webview.webViewOverlay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** What a Browser tab shows under its bars. */
internal sealed interface BrowserBody {
    /** A new tab: the address bar waits, and no engine is made. */
    data object NewTab : BrowserBody

    data class Page(val url: String) : BrowserBody

    data class Said(val message: String) : BrowserBody
}

/** A blank page in the tabs' profile: what a tab holds before its first page, so its engine can take a cookie first. */
internal val BLANK = WebContent.Html("<!doctype html><title></title>")

/** The tabs' settings: their own profile, and inspectable (they're a developer's browser). */
private val BROWSER_SETTINGS = WebSettings(profile = BROWSER_PROFILE, inspectable = true)

/** The broker's cookie spec, as the engine's cookie. */
internal fun PreviewCookieDto.toWebCookie(): WebCookie = WebCookie(
    name = name, value = value, url = url, path = path, domain = domain,
    secure = secure, httpOnly = httpOnly, sameSite = sameSite, maxAgeSeconds = maxAge,
)

/** A tab's answers to its page: a new window asked for with the user's press opens as a link does. */
private class BrowserClient(private val newWindow: (String) -> Unit, private val unusedKey: (KeyEvent) -> Boolean = { false }) : WebClient {
    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision {
        if (request.isUserGesture) newWindow(request.url)
        return NewWindowDecision.Block
    }

    /** A key the page had first and left unused (Android, #122 page-first keys): the app's shortcuts, as after Compose. */
    override fun onUnusedKey(event: KeyEvent): Boolean = unusedKey(event)
}

/**
 * A Browser tab (#105): a web page under an address bar, in a workspace pane.
 *
 * Its page is the view's synced state. It opens the page stored there, writes each page it commits back with the page's
 * title (which names the tab on every client), and follows a page another client stores ([BrowserTabSync]). Every page
 * goes through [addressAction], so a `localhost` link opens the port's by-port preview on a device that isn't the host
 * (or a named one, or says why it can't). A preview loads after the host's preview cookie is in the tabs' profile.
 */
@OptIn(FlowPreview::class)
@Composable
fun BrowserPane(
    view: ViewDto,
    workspaceId: String,
    actions: BrowserActions,
    modifier: Modifier = Modifier,
    /** Desktop ([BrowserTabKeeper]): the page keeps running while its tab is in the back. */
    keepRunning: Boolean = false,
) {
    val platform = LocalPlatform.current
    val provided = LocalAppWebViews.current
    // The app's runtime is made on first use: a tab with no page makes no engine, nor loads one.
    val webViews by remember(provided) { derivedStateOf { provided ?: AppWebViews.app } }
    val links by rememberUpdatedState(LocalLinkOpener.current)
    // The tab's page outlives the pane: another tab in front leaves it alive in the pool ([AppWebViews.tab]).
    val tab = remember(view.id, webViews) { webViews.tab(view.id) }
    val sync = tab.sync
    val state = tab.state
    val address = remember { FocusRequester() }
    var body by tab::body
    var previews by remember { mutableStateOf<List<ProxyDto>?>(null) }
    var byPort by remember { mutableStateOf<String?>(null) }
    var requested by tab::requested
    var finding by remember(view.id) { mutableStateOf(false) }
    val find = remember(view.id) { FindState() }
    val findScope = rememberCoroutineScope()
    var zoom by tab::zoom
    var notice by remember(view.id) { mutableStateOf<String?>(null) }
    // Device mode (#122 phase 2) is this client's, per tab, never synced: an iPhone on the Mac means nothing on the
    // phone. Off, it keeps the device it had for next time. A phone has none (§3.2).
    val phone = phoneChrome()
    var deviceOn by remember(view.id) { mutableStateOf(false) }
    var device by remember(view.id) { mutableStateOf(DeviceMode.Default) }
    // Pick and comment (#122 phase 3): pick mode and its pins, per tab and per client. Desktop first (3c brings touch).
    val pick = remember(view.id) { PickState() }
    val pickOffered = platform.caps.pagePick
    // A phone's "Desktop site" (#122 2c), per tab and per client, where the engine can do it.
    var desktopSite by remember(view.id) { mutableStateOf(false) }

    // A port resolves through the by-port route or the previews, so the first page waits for them: three seconds at
    // most, then none.
    LaunchedEffect(Unit) {
        withTimeoutOrNull(3_000) {
            byPort = runCatching { actions.byPort() }.getOrNull()
            previews = runCatching { actions.proxies() }.getOrNull()
        }
        if (previews == null) previews = emptyList()
    }

    fun go(url: String) {
        body = when (val action = addressAction(url, previews.orEmpty(), actions.onHost, byPort)) {
            is AddressAction.Load -> BrowserBody.Page(action.url)
            is AddressAction.Say -> BrowserBody.Said(action.message)
        }
    }

    // The view's stored page: the first to open, then another client's (never this tab's own echo). A port waits for
    // its routes, which it resolves through.
    val stored = remember(view.state) { BrowserViewState.from(view.state) }
    // A new tab (⌘T, + → Browser) waits with its address bar focused, as Chrome's does (#122).
    LaunchedEffect(view.id) {
        if (stored !is BrowserViewState.Empty) return@LaunchedEffect
        withFrameNanos { }
        runCatching { address.requestFocus() }
    }
    val previewsKnown = previews != null
    LaunchedEffect(stored, previewsKnown) {
        if (!previewsKnown) return@LaunchedEffect
        val showing = (body as? BrowserBody.Page)?.url
        sync.toFollow(stored, showing)?.let(::go)
    }

    // Load the page: a preview after its cookie, which goes in through a live engine (the blank page brings one up).
    val page = (body as? BrowserBody.Page)?.url
    val live = state.phase == WebViewPhase.Live
    LaunchedEffect(page, live) {
        val url = page ?: return@LaunchedEffect
        if (url == requested) return@LaunchedEffect
        if (displayAddress(url, previews.orEmpty(), byPort) is DisplayAddress.Preview) {
            val cookie = actions.previewCookie()
            if (cookie != null && webViews.needsPreviewCookie(cookie)) {
                if (!live) return@LaunchedEffect
                if (state.navigator.setCookie(cookie.toWebCookie())) webViews.previewCookieSet(cookie)
            }
        }
        requested = url
        state.load(WebContent.Url(url))
    }
    // A zoom outlives the engine: a new lease gets it again.
    // Device mode's fit (#122 2b) zooms the page by the stage's scale on top of the user's zoom; leaving it zooms back.
    var stageScale by remember(view.id) { mutableFloatStateOf(1f) }
    // An engine that emulates devices itself (WebView2's DevTools protocol, #122 2b) gets the device whole: its CSS size,
    // pixel ratio, mobile viewport and touch, drawn at the stage's scale. Elsewhere the fit is the page's zoom.
    var emulates by remember(view.id) { mutableStateOf(false) }
    var emulated by remember(view.id) { mutableStateOf(false) }
    val emulation = if (deviceOn && !phone) {
        DeviceEmulation(device.width, device.height, device.deviceScaleFactor(), device.mobile, device.hasTouch(), stageScale)
    } else null
    LaunchedEffect(live, emulation) {
        if (!live) return@LaunchedEffect
        if (emulation != null) {
            emulates = state.navigator.emulateDevice(emulation)
            emulated = emulates
        } else if (emulated) {
            state.navigator.emulateDevice(null)
            emulated = false
        }
    }
    val fitting = deviceOn && !phone && (platform.caps.pageFit || emulates)
    val zoomFits = fitting && !emulates
    val pageZoom = zoom * (if (zoomFits) stageScale else 1f)
    var appliedZoom by remember(view.id) { mutableFloatStateOf(1f) }
    LaunchedEffect(live, pageZoom) {
        if (live && (pageZoom != 1f || appliedZoom != 1f)) {
            state.navigator.setZoom(pageZoom)
            appliedZoom = pageZoom
        }
    }

    // The page's icon for its tab, after each page it commits.
    val icons = LocalBrowserIcons.current
    LaunchedEffect(state.url, live) {
        if (!live || icons == null || state.content !is WebContent.Url) return@LaunchedEffect
        state.navigator.favicon()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { icons[view.id] = it }
    }

    // Each committed page, and its title, back to the view.
    LaunchedEffect(view.id) {
        snapshotFlow { state.url.takeIf { state.content is WebContent.Url } to state.title }
            .debounce(400)
            .collect { (url, title) ->
                val patch = sync.committed(url, title) ?: return@collect
                actions.saveView(workspaceId, view.id, patch.state, patch.title)
            }
    }

    // A new window the page opens on the user's press: the workspace's link rule, at most one a second (a page that
    // answers one press with a burst opens one).
    val windows = remember { Channel<String>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_LATEST) }
    val shortcuts by rememberUpdatedState(LocalShortcutDispatcher.current)
    val client = remember { BrowserClient(newWindow = { windows.trySend(it) }, unusedKey = { shortcuts?.onBubble(it) == true }) }
    LaunchedEffect(Unit) {
        var last: TimeSource.Monotonic.ValueTimeMark? = null
        for (url in windows) {
            if (last != null && last.elapsedNow().inWholeMilliseconds < 1000) continue
            last = TimeSource.Monotonic.markNow()
            links?.open(url, view.id, modified = false) ?: platform.openUrl(url)
        }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        val before = page
        go(text)
        if (before != null && (body as? BrowserBody.Page)?.url == before) state.navigator.reload()
    }
    fun zoomBy(direction: Int) {
        val next = if (direction == 0) 1f else nextZoom(zoom, direction)
        if (state.navigator.setZoom(next * (if (zoomFits) stageScale else 1f))) zoom = next else notice = "Zoom isn't available here yet"
    }
    fun inspect() {
        notice = when (val r = state.navigator.openInspector()) {
            InspectorResult.Opened -> null
            is InspectorResult.Elsewhere -> r.hint
            else -> "This engine has no inspector here"
        }
    }
    // The inspect key opens what the engine has, and leaves the key alone where it has none (#122: never a note that
    // something can't be done).
    fun inspectKey(): Boolean = when (val r = state.navigator.openInspector()) {
        InspectorResult.Opened -> { notice = null; true }
        is InspectorResult.Elsewhere -> { notice = r.hint; true }
        else -> false
    }
    // A picture of the page into the workspace's chat, unsent (#122): only where a chat can take it and the platform can
    // picture a page.
    val toChat = LocalBrowserToChat.current?.takeIf { platform.caps.pageScreenshots }
    fun sendScreenshot(): Boolean {
        val sink = toChat ?: return false
        findScope.launch {
            val png = state.navigator.screenshot()?.let(::pagePng) ?: return@launch
            sink.sendImage(png, "screenshot.png")
        }
        return true
    }
    fun findStep(backwards: Boolean) {
        val q = find.query
        findScope.launch { find.said = findText(q, if (q.isEmpty()) null else state.navigator.find(q, backwards)) }
    }

    val shown = state.url.takeIf { state.content is WebContent.Url } ?: page
    // Its comments go to the workspace's chat (3b): one message, each element's picture where the engine can picture
    // the page. Send posts it; Add to message leaves it in the composer.
    val chat = LocalBrowserToChat.current
    // Whose chat (4a-3): the one chosen in the bar for this tab, else the session that owns a named preview's port
    // (§5.6, first match), else the default chat.
    var chosenChat by remember(view.id) { mutableStateOf<String?>(null) }
    val chats = chat?.chats().orEmpty()
    val portOwner = (shown?.let { displayAddress(it, previews.orEmpty(), byPort) } as? DisplayAddress.Preview)
        ?.let { p -> previews.orEmpty().firstOrNull { it.port == p.port && it.sessionName.isNotEmpty() }?.sessionName }
        ?.let { name -> chats.firstOrNull { it.second == name }?.first }
    val recipientId = chosenChat?.takeIf { id -> chats.any { it.first == id } } ?: portOwner
    val recipientName = recipientId?.let { id -> chats.firstOrNull { it.first == id }?.second } ?: chat?.recipient()
    val commentStatesFlow = remember(chat) { chat?.commentStates() ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap()) }
    val commentStates by commentStatesFlow.collectAsState()
    val accent = dev.supermux.ui.settings.kit.kitColors().accent
    val sendScope = rememberCoroutineScope()
    fun sendComments(send: Boolean) {
        val sink = chat ?: return
        val comments = pick.commented
        if (comments.isEmpty()) return
        val pictures = platform.caps.pageScreenshots
        val address = shown?.let { displayAddress(it, previews.orEmpty(), byPort) }
        val url = when (address) {
            is DisplayAddress.Preview -> "http://localhost:${address.port}${address.path}"
            is DisplayAddress.Local -> "http://localhost:${address.port}${address.path}"
            else -> shown
        }
        val viewedAs = if (deviceOn && !phone) "${device.preset?.name ?: "Responsive"} · ${device.width}×${device.height}" else null
        sendScope.launch {
            val page = if (pictures) state.navigator.screenshot() else null
            val images = mutableListOf<Pair<String, ByteArray>>()
            val outs = comments.map { p ->
                val name = "comment-${p.number}.png"
                val png = page?.let { pagePng(cropWithPin(it, p.box, accent)) }
                if (png != null) images += name to png
                CommentOut(p.number, p.comment, p.element.selector, p.element.text, name.takeIf { png != null })
            }
            // Stored first (phase 4): the message names each by its id, and the pins stay to follow the agent's answer.
            val stored = sink.storeComments(
                BrowserCommentsBody(url, viewedAs, outs.map { BrowserCommentInput(it.number, it.comment, it.selector, it.text, it.picture) }),
                recipientId,
            )
            val ids = stored?.associate { it.number to it.id }
            if (sink.sendMessage(commentsMessage(url, viewedAs, outs, ids), images, send, recipientId)) {
                if (ids == null) {
                    pick.clear()
                } else {
                    for (p in comments) p.commentId = ids[p.number]
                    pick.editing = null
                    pick.hover = null
                }
                pick.on = false
            }
        }
    }
    val commentsAction: ((Boolean) -> Unit)? = if (chat != null) { send -> sendComments(send) } else null
    // The tab's keys are #96's registry's: handlers in the pane's scope (KeyContext.Browser), which the platform's
    // modifier reaches through the keymap.
    RegisterCommand(AppCommands.BrowserFocusAddress) { address.requestFocus(); true }
    RegisterCommand(AppCommands.BrowserReload) { state.navigator.reload(); true }
    RegisterCommand(AppCommands.BrowserBack) { state.navigator.back(); true }
    RegisterCommand(AppCommands.BrowserForward) { state.navigator.forward(); true }
    RegisterCommand(AppCommands.BrowserZoomIn) { zoomBy(+1); true }
    RegisterCommand(AppCommands.BrowserZoomOut) { zoomBy(-1); true }
    RegisterCommand(AppCommands.BrowserZoomReset) { zoomBy(0); true }
    RegisterCommand(AppCommands.BrowserOpenExternally) { (shown ?: page)?.let(platform::openUrl); true }
    RegisterCommand(AppCommands.Find) { finding = true; true }
    RegisterCommand(AppCommands.FindNext) { finding = true; findStep(backwards = false); true }
    RegisterCommand(AppCommands.FindPrevious) { finding = true; findStep(backwards = true); true }
    RegisterCommand(AppCommands.BrowserInspect) { inspectKey() }
    RegisterCommand(AppCommands.BrowserScreenshot, enabled = toChat != null) { sendScreenshot() }
    RegisterCommand(AppCommands.BrowserDeviceMode, enabled = !phone) { deviceOn = !deviceOn; true }
    RegisterCommand(AppCommands.BrowserPick, enabled = pickOffered) { pick.on = !pick.on; if (!pick.on) pick.hover = null; true }
    val screenshotAction: (() -> Unit)? = if (toChat != null) { { sendScreenshot() } } else null
    // Esc stops a loading page; on a loaded one it declines, so the page keeps it.
    RegisterCommand(AppCommands.BrowserStop) {
        (state.loading is LoadingState.Loading).also { if (it) state.navigator.stop() }
    }

    // One page, moved (not rebuilt) between the pane and device mode's stage, so turning it on keeps the live page. What
    // changes while it lives (its settings, keeping it running) are its parameters: a remembered lambda would keep the
    // values it was made with.
    val livePage = remember(view.id, state) {
        movableContentOf { settings: WebSettings, keep: Boolean ->
            ProvideWebViewRuntime(webViews.runtime) {
                WebView(state, Modifier.fillMaxSize(), settings = settings, client = client, activation = Activation.Eager, keepRunningWhileHidden = keep)
            }
        }
    }
    // Device mode's agent and appearance (#122 2a2), where the engine can change them.
    val emulating = deviceOn && !phone && platform.caps.pageEmulation
    val desktopSiteOffered = phone && platform.caps.pageEmulation
    val pageSettings = if (desktopSiteOffered && desktopSite) BROWSER_SETTINGS.copy(desktopSite = true) else if (!emulating) BROWSER_SETTINGS else BROWSER_SETTINGS.copy(
        userAgent = device.userAgent(),
        darkMode = when (device.appearance) {
            PageAppearance.System -> BROWSER_SETTINGS.darkMode
            PageAppearance.Light -> DarkMode.Light
            PageAppearance.Dark -> DarkMode.Dark
        },
    )

    // The page and what floats over it (the ⋯ menu, the find bar) share one overlay registry: the window's, or the
    // pane's own where none is provided, so the page never takes their clicks (on macOS a press goes to what is natively
    // under the pointer).
    OverlaysOfThisWindow {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("browser-${view.id}"),
    ) {
        val loading = state.loading is LoadingState.Loading
        BrowserToolbar(
            shown = shown?.let { displayAddress(it, previews.orEmpty(), byPort).text() }.orEmpty(),
            preview = shown?.let { displayAddress(it, previews.orEmpty(), byPort) } is DisplayAddress.Preview,
            full = shown,
            canGoBack = state.canGoBack,
            canGoForward = state.canGoForward,
            loading = loading,
            zoomPercent = (zoom * 100).roundToInt(),
            address = address,
            onBack = { state.navigator.back() },
            onForward = { state.navigator.forward() },
            onReload = { if (loading) state.navigator.stop() else state.navigator.reload() },
            onSubmit = ::submit,
            onOpenExternally = { shown?.let(platform::openUrl) },
            onFind = { finding = true },
            onZoom = ::zoomBy,
            onInspect = ::inspect,
            onScreenshot = screenshotAction,
            device = deviceOn.takeUnless { phone },
            onDevice = { deviceOn = !deviceOn },
            picking = pick.on.takeIf { pickOffered },
            onPick = { pick.on = !pick.on; if (!pick.on) pick.hover = null },
            desktopSite = desktopSite.takeIf { desktopSiteOffered },
            onDesktopSite = { desktopSite = !desktopSite },
        )
        if (deviceOn && !phone) DeviceBar(
            device, emulation = platform.caps.pageEmulation, scale = stageScale.takeIf { fitting }, ratioAndTouch = emulates,
            onChange = { device = it }, onClose = { deviceOn = false },
        )
        if (pick.on) PickStrip(touch = phone, onDone = { pick.on = false; pick.closeComment() })
        notice?.let { text ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("browser-notice"), verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                dev.supermux.ui.settings.kit.KitTextAction("OK", { notice = null })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val b = body) {
                BrowserBody.NewTab -> BrowserMessage("Type a site, or a port such as 5173.", "browser-new-tab")
                is BrowserBody.Said -> BrowserMessage(b.message, "browser-said")
                is BrowserBody.Page -> {
                    val pageAndPicks = @Composable {
                        livePage(pageSettings, keepRunning)
                        if (pickOffered) PickLayers(pick, state.navigator, touch = phone, states = commentStates)
                    }
                    if (deviceOn && !phone) DeviceStage(device, fit = fitting, onScale = { stageScale = it }) { pageAndPicks() }
                    else Box(Modifier.fillMaxSize().testTag("browser-viewport")) { pageAndPicks() }
                    (state.phase as? WebViewPhase.Unavailable)?.let { unavailable ->
                        BrowserMessage(
                            "This device can't show web pages in a tab" + (unavailable.detail?.let { ": $it" } ?: "."),
                            "browser-unavailable",
                            "Open in your browser" to { platform.openUrl(b.url) },
                        )
                    }
                }
            }
            // A phone's send floats over the page's bottom, above the home indicator (no bar under it).
            if (phone && (pick.commented.isNotEmpty() || pick.removed != null)) {
                PickSendPill(pick, recipientName, commentsAction, Modifier.align(Alignment.BottomCenter))
            }
            if (finding) {
                // Floats over the page's corner: an overlay the page leaves room for where it sits above Compose (#122).
                FindBar(
                    find = find,
                    onStep = ::findStep,
                    onClose = { finding = false; state.navigator.clearFind() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).webViewOverlay(RoundedCornerShape(8.dp)),
                )
            }
        }
        if (!phone && (pick.commented.isNotEmpty() || pick.removed != null)) {
            PickSendBar(pick, recipientName, commentsAction, chats = chats, onChooseChat = { chosenChat = it })
        }
    }
    }
}

/** [content] with the window's overlay registry, or one of its own where the window provides none. */
@Composable
private fun OverlaysOfThisWindow(content: @Composable () -> Unit) {
    if (LocalWebViewOverlays.current != null) content() else ProvideWebViewOverlays(content)
}
