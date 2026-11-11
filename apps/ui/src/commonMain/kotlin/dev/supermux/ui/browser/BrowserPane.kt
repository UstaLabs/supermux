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
private val BLANK = WebContent.Html("<!doctype html><title></title>")

/** The tabs' settings: their own profile, and inspectable (they're a developer's browser). */
private val BROWSER_SETTINGS = WebSettings(profile = BROWSER_PROFILE, inspectable = true)

/** The broker's cookie spec, as the engine's cookie. */
internal fun PreviewCookieDto.toWebCookie(): WebCookie = WebCookie(
    name = name, value = value, url = url, path = path, domain = domain,
    secure = secure, httpOnly = httpOnly, sameSite = sameSite, maxAgeSeconds = maxAge,
)

/** A tab's answers to its page: a new window asked for with the user's press opens as a link does. */
private class BrowserClient(private val newWindow: (String) -> Unit) : WebClient {
    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision {
        if (request.isUserGesture) newWindow(request.url)
        return NewWindowDecision.Block
    }
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
fun BrowserPane(view: ViewDto, workspaceId: String, actions: BrowserActions, modifier: Modifier = Modifier) {
    val platform = LocalPlatform.current
    val provided = LocalAppWebViews.current
    // The app's runtime is made on first use: a tab with no page makes no engine, nor loads one.
    val webViews by remember(provided) { derivedStateOf { provided ?: AppWebViews.app } }
    val links by rememberUpdatedState(LocalLinkOpener.current)
    val sync = remember(view.id) { BrowserTabSync() }
    val state = remember(view.id) { WebViewState(BLANK) }
    val address = remember { FocusRequester() }
    var body by remember(view.id) { mutableStateOf<BrowserBody>(BrowserBody.NewTab) }
    var previews by remember { mutableStateOf<List<ProxyDto>?>(null) }
    var byPort by remember { mutableStateOf<String?>(null) }
    var requested by remember(view.id) { mutableStateOf<String?>(null) }
    var finding by remember(view.id) { mutableStateOf(false) }
    val find = remember(view.id) { FindState() }
    val findScope = rememberCoroutineScope()
    var zoom by remember(view.id) { mutableFloatStateOf(1f) }
    var notice by remember(view.id) { mutableStateOf<String?>(null) }
    // Device mode (#122 phase 2) is this client's, per tab, never synced: an iPhone on the Mac means nothing on the
    // phone. Off, it keeps the device it had for next time. A phone has none (§3.2).
    val phone = phoneChrome()
    var deviceOn by remember(view.id) { mutableStateOf(false) }
    var device by remember(view.id) { mutableStateOf(DeviceMode.Default) }

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
    LaunchedEffect(live, zoom) { if (live && zoom != 1f) state.navigator.setZoom(zoom) }

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
    val client = remember { BrowserClient { windows.trySend(it) } }
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
        if (state.navigator.setZoom(next)) zoom = next else notice = "Zoom isn't available here yet"
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
    val screenshotAction: (() -> Unit)? = if (toChat != null) { { sendScreenshot() } } else null
    // Esc stops a loading page; on a loaded one it declines, so the page keeps it.
    RegisterCommand(AppCommands.BrowserStop) {
        (state.loading is LoadingState.Loading).also { if (it) state.navigator.stop() }
    }

    // One page, moved (not rebuilt) between the pane and device mode's stage, so turning it on keeps the live page.
    val livePage = remember(view.id) {
        movableContentOf {
            ProvideWebViewRuntime(webViews.runtime) {
                WebView(state, Modifier.fillMaxSize(), settings = BROWSER_SETTINGS, client = client, activation = Activation.Eager)
            }
        }
    }

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
        )
        if (deviceOn && !phone) DeviceBar(device, onChange = { device = it }, onClose = { deviceOn = false })
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
                    if (deviceOn && !phone) DeviceStage(device, livePage)
                    else Box(Modifier.fillMaxSize().testTag("browser-viewport")) { livePage() }
                    (state.phase as? WebViewPhase.Unavailable)?.let { unavailable ->
                        BrowserMessage(
                            "This device can't show web pages in a tab" + (unavailable.detail?.let { ": $it" } ?: "."),
                            "browser-unavailable",
                            "Open in your browser" to { platform.openUrl(b.url) },
                        )
                    }
                }
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
    }
    }
}

/** [content] with the window's overlay registry, or one of its own where the window provides none. */
@Composable
private fun OverlaysOfThisWindow(content: @Composable () -> Unit) {
    if (LocalWebViewOverlays.current != null) content() else ProvideWebViewOverlays(content)
}
