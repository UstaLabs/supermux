// #117: what an applet window shows — the applet's page and nothing else, like an installed web app. The same page on
// every platform: desktop puts it in its own OS window, Android in its own activity (task), iOS full screen.
//
// Auth: the page loads from the host's /a/<name>/ with the device's preview-only key in the cookie store, never the
// device token (an applet shares the app's origin until #114, and a page holding the device token would hold the
// owner's whole API). The key is the host's one PreviewKeeper's, shared with the Browser tabs (BROWSER_PROFILE), so
// a renewal through either reaches both.
package dev.supermux.ui.applets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import dev.supermux.webview.webViewOverlay
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import dev.supermux.net.pinned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.PreviewCookieDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.browser.AppWebViews
import dev.supermux.ui.browser.BROWSER_PROFILE
import dev.supermux.ui.browser.LocalAppWebViews
import dev.supermux.ui.browser.PreviewKeeper
import dev.supermux.ui.browser.putPreviewKeys
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.webview.Activation
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.WebClient
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first

/** The scheme an applet opens by on every OS: `supermux://applet/<host record id>/<applet name>` (shortcuts, S1). */
const val APPLET_URI_PREFIX = "supermux://applet/"

fun appletUri(hostId: String, name: String): String = "$APPLET_URI_PREFIX$hostId/$name"

/** (host record id, applet name) from [appletUri]'s form; null for anything else. */
fun parseAppletUri(uri: String): Pair<String, String>? {
    if (!uri.startsWith(APPLET_URI_PREFIX)) return null
    val parts = uri.removePrefix(APPLET_URI_PREFIX).trimEnd('/').split('/') // platform-ok: url
    if (parts.size != 2 || parts.any { it.isBlank() }) return null
    return parts[0] to parts[1]
}

/** "https://relay.example" from "https://relay.example/p/": where the preview key is valid, which serves /a/ too. */
internal fun originOf(url: String): String {
    val scheme = url.indexOf("://")
    if (scheme < 0) return url.trimEnd('/') // platform-ok: url
    val slash = url.indexOf('/', scheme + 3)
    return if (slash < 0) url else url.substring(0, slash)
}

/** The applet's page on the origin the key was minted for, so the cookie and the page always agree. */
internal fun appletPageUrl(key: PreviewCookieDto, name: String): String = "${originOf(key.url)}/a/$name/"

/** An applet's answers to its page: a link that opens a new window, with his click, opens in the system browser. */
private class AppletClient(private val external: (String) -> Unit) : WebClient {
    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision {
        if (request.isUserGesture) external(request.url)
        return NewWindowDecision.Block
    }
}

private val BLANK = WebContent.Html("<!doctype html><title></title>")
private val APPLET_SETTINGS = WebSettings(profile = BROWSER_PROFILE, inspectable = true)

/**
 * The applet [name] of the host whose record is [hostId], filling its window. A window opened before the fleet has its
 * hosts (a cold start from a shortcut) waits for them; a host this device no longer has is said, never a blank window.
 */
@Composable
fun AppletPage(fleet: FleetStore?, hostId: String, name: String, modifier: Modifier = Modifier, onOpenChat: (String) -> Unit = {}, noEngine: String? = null) {
    val views by (fleet?.hostViews ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<dev.supermux.host.HostView>()) }).collectAsState()
    val app = remember(fleet, views, hostId) { fleet?.appForRecord(hostId) }
    when {
        app != null -> AppletPage(app, name, modifier, onOpenChat, noEngine)
        fleet != null && views.isNotEmpty() && views.none { it.recordId == hostId } -> AppletMessage(localized(Str.applets_window_no_host), modifier)
        else -> Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

/**
 * One applet page: its web view state and where it loads from, shared by its preload ([AppletPreloads]) and the window
 * that shows it, so the window takes over a preloaded page as it is, already painted (#117: applets open at once).
 * Main thread only.
 */
internal class AppletPageModel(val app: HostStore, val name: String) {
    val state = WebViewState(BLANK)
    /** The applet's own origin, known once the key says where the page loads from: the bridge talks only to it. */
    var origin by mutableStateOf<String?>(null)
    var problem by mutableStateOf<String?>(null)
    val settings: WebSettings get() = origin?.let { APPLET_SETTINGS.copy(bridgeOrigins = setOf(it.lowercase())) } ?: APPLET_SETTINGS
}

/** The applet pages this process has, by host and name, and which of them a window shows now. Main thread only. */
internal object AppletPageModels {
    private val models = mutableMapOf<Pair<HostStore, String>, AppletPageModel>()
    /** The pages a window shows, and in how many: a preload leaves those alone. */
    val showing = mutableStateMapOf<AppletPageModel, Int>()

    fun of(app: HostStore, name: String): AppletPageModel = models.getOrPut(app to name) { AppletPageModel(app, name) }

    /** Drops [app]'s pages: its host was forgotten. */
    fun forgetHost(app: HostStore) {
        models.keys.removeAll { it.first === app }
    }

    /** Drops the pages of hosts this device no longer has (unpaired): nothing of theirs stays reachable. */
    fun keepOnly(apps: Set<HostStore>) {
        models.keys.removeAll { it.first !in apps }
    }
}

/**
 * The applet [name] of host [app], filling its window. The page's bridge (its own origin only) answers the page API's
 * native calls: agent.ask after his click on a button, and openChat for the "Sent to …" link it shows; [onOpenChat]
 * brings that chat up in the main window. It shows the page its preload loaded when there is one; a second window of the
 * same applet has a page of its own.
 */
@Composable
fun AppletPage(app: HostStore, name: String, modifier: Modifier = Modifier, onOpenChat: (String) -> Unit = {}, noEngine: String? = null) {
    val cs = MaterialTheme.colorScheme
    val webViews = LocalAppWebViews.current ?: AppWebViews.app
    val model = remember(app, name) {
        AppletPageModels.of(app, name).takeIf { it !in AppletPageModels.showing } ?: AppletPageModel(app, name)
    }
    androidx.compose.runtime.DisposableEffect(model) {
        AppletPageModels.showing[model] = (AppletPageModels.showing[model] ?: 0) + 1
        onDispose {
            val n = (AppletPageModels.showing[model] ?: 1) - 1
            if (n <= 0) AppletPageModels.showing.remove(model) else AppletPageModels.showing[model] = n
        }
    }
    AppletPageLoader(model, onOpenChat)
    val state = model.state
    // How long opening took, from this window to a live, loaded page, and whether a preload had it ready: the log's.
    LaunchedEffect(model) {
        val opened = kotlin.time.TimeSource.Monotonic.markNow()
        // A preload that loaded already holds the page (it never painted, hidden, so it is still activating).
        val preloaded = state.content is WebContent.Url
        androidx.compose.runtime.snapshotFlow { state.phase == WebViewPhase.Live && state.content is WebContent.Url && state.url?.startsWith("http") == true && state.loading == dev.supermux.webview.LoadingState.Idle }
            .first { it }
        println("[AppletPage] $name shown in ${opened.elapsedNow().inWholeMilliseconds} ms (${if (preloaded) "preloaded" else "cold"})")
    }
    val platform = LocalPlatform.current
    val openExternal by rememberUpdatedState<(String) -> Unit>({ url -> platform.openUrl(url) })
    val client = remember { AppletClient { openExternal(it) } }
    Box(modifier.fillMaxSize().background(cs.background).testTag("applet_page_$name")) {
        ProvideWebViewRuntime(webViews.runtime) {
            WebView(state, Modifier.fillMaxSize(), settings = model.settings, client = client, activation = Activation.Eager)
        }
        model.problem?.let { AppletMessage(it, Modifier.background(cs.background)) }
        // No engine can show pages here (on Android: a System WebView too old for the engine's needs): say so, never a
        // blank window. Why is the log's.
        (state.phase as? WebViewPhase.Unavailable)?.let { unavailable ->
            LaunchedEffect(unavailable) {
                println("[AppletPage] no web engine: ${unavailable.reason}${unavailable.detail?.let { ": $it" }.orEmpty()}")
            }
            AppletMessage(noEngine ?: localized(Str.applets_window_no_engine), Modifier.background(cs.background))
        }
    }
}

/**
 * What a page needs while a view of it is composed, a window's or its preload's: the bridge's native calls, and once an
 * engine is live, the key put in (renewed first when due) and the page loaded.
 */
@Composable
private fun AppletPageLoader(model: AppletPageModel, onOpenChat: (String) -> Unit) {
    val app = model.app
    val name = model.name
    val state = model.state
    val keeper = remember(app) { PreviewKeeper.of(app) }
    val sink = remember(state) { PreviewKeeper.Sink<PreviewCookieDto> { state.navigator.putPreviewKeys(it) } }
    val openChat by rememberUpdatedState(onOpenChat)
    androidx.compose.runtime.DisposableEffect(state, app, name) {
        val offAsk = state.bridge.handleAfterButtonClick("ask") { params, clicked -> askAnswer(app, name, params, clicked) }
        val offOpen = state.bridge.handle("openChat") { params ->
            val id = ((params as? kotlinx.serialization.json.JsonObject)?.get("sessionId") as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (!id.isNullOrBlank()) openChat(id)
            kotlinx.serialization.json.JsonPrimitive(id != null)
        }
        val offSave = state.bridge.handleAfterButtonClick("save") { _, _ -> refusal(localized(Str.applets_save_not_yet)) }
        onDispose { offAsk(); offOpen(); offSave() }
    }
    // An engine is bound (live, or activating: a hidden preload never paints, as WebKit draws no hidden view, but loads).
    val bound = state.phase == WebViewPhase.Live || state.phase == WebViewPhase.Activating
    LaunchedEffect(bound, keeper) {
        if (!bound) return@LaunchedEffect
        val detach = keeper.attach(sink)
        try {
            // The key, renewed first when due; put in again here: a key the store already had may predate /a/.
            val key = keeper.ready(sink)
            if (key == null) {
                model.problem = localized(Str.applets_window_no_key)
            } else {
                model.problem = null
                model.origin = originOf(key.url)
                sink.put(key)
                if (state.content !is WebContent.Url) state.load(WebContent.Url(appletPageUrl(key, name)))
            }
            awaitCancellation()
        } finally {
            detach()
        }
    }
}

/**
 * As each host is forgotten (unpaired), what the app's web views keep of it goes (#117): its applets' pages and the
 * runtime's memory of them, and from the shared Browser profile its cookies (the applets' key among them), storage and
 * cache, at its relay's and direct URL's origins and its last key's. Other sites' logins in Browser tabs stay.
 */
@Composable
fun ForgetUnpairedSites(fleet: FleetStore) {
    val webViews = LocalAppWebViews.current ?: AppWebViews.app
    LaunchedEffect(fleet, webViews) {
        fleet.forgotten.collect { host ->
            val origins = host.origins + listOfNotNull(host.app?.let { PreviewKeeper.forget(it) })
            host.app?.let(AppletPageModels::forgetHost)
            val cleared = webViews.runtime.clearSiteData(BROWSER_PROFILE, origins)
            println("[AppletPage] forgot host ${host.recordId}: ${origins.size} origin(s), cleared=$cleared") // i18n-ok: a log line, never shown
        }
    }
}

/** How many pinned applets are kept loaded ahead: the pool's spare slots, on a phone's four as on a desktop's eight. */
private const val PRELOADS = 2

/**
 * The first pinned applets of host [hostId], loaded ahead in hidden web views, so opening one is a switch to a painted
 * page rather than an engine start and a load (#117). They rank lowest in the pool, so any view that needs a slot takes
 * theirs first, and each lets its engine go after the pool's hidden time; one a window shows is left to the window, and
 * picks its page up again when the window closes. Laid out the size of the window, far outside it: live, never seen.
 */
@Composable
fun AppletPreloads(fleet: FleetStore, hostId: String?) {
    val views by fleet.hostViews.collectAsState()
    LaunchedEffect(views) { AppletPageModels.keepOnly(views.mapNotNull { fleet.appForRecord(it.recordId) }.toSet()) }
    val applets by fleet.activeApplets.collectAsState()
    val app = remember(views, hostId) { fleet.appForRecord(hostId) } ?: return
    val names = applets.orEmpty().pinned().filter { it.status == "ok" && it.url.isNotEmpty() }.take(PRELOADS).map { it.name }
    if (names.isEmpty()) return
    val webViews = LocalAppWebViews.current ?: AppWebViews.app
    val client = remember { AppletClient { } }
    Box(Modifier.size(0.dp)) {
        val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        val density = androidx.compose.ui.platform.LocalDensity.current
        val w = with(density) { window.width.coerceAtLeast(1).toDp() }
        val h = with(density) { window.height.coerceAtLeast(1).toDp() }
        ProvideWebViewRuntime(webViews.runtime) {
            for (name in names) {
                androidx.compose.runtime.key(name) {
                    val model = AppletPageModels.of(app, name)
                    if (model !in AppletPageModels.showing) {
                        AppletPageLoader(model) {}
                        WebView(
                            model.state,
                            Modifier.offset { androidx.compose.ui.unit.IntOffset(-4 * window.width - 10_000, 0) }.requiredSize(w, h),
                            settings = model.settings,
                            client = client,
                            activation = Activation.Preload,
                        )
                    }
                }
            }
        }
    }
}

private fun refusal(reason: String) = kotlinx.serialization.json.JsonObject(mapOf("ok" to kotlinx.serialization.json.JsonPrimitive(false), "reason" to kotlinx.serialization.json.JsonPrimitive(reason)))

/** supermux.agent.ask: only right after his click on a button in the page, 1 to 4,000 characters; answers which chat took it. */
private suspend fun askAnswer(app: HostStore, name: String, params: kotlinx.serialization.json.JsonElement, clicked: Boolean): kotlinx.serialization.json.JsonElement {
    val obj = params as? kotlinx.serialization.json.JsonObject
    val text = (obj?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    val agent = (obj?.get("agent") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    return when {
        !clicked -> refusal("no click on a button in the page")
        text.isNullOrBlank() -> refusal("the message is empty")
        text.length > 4000 -> refusal("the message is over 4000 characters")
        else -> app.appletAction { api -> api.askApplet(name, text, agent) }.fold(
            onSuccess = { r -> kotlinx.serialization.json.JsonObject(mapOf("ok" to kotlinx.serialization.json.JsonPrimitive(true), "session" to kotlinx.serialization.json.JsonPrimitive(r.session), "sessionId" to kotlinx.serialization.json.JsonPrimitive(r.sessionId))) },
            onFailure = { e -> refusal(e.message ?: "the message didn't go") },
        )
    }
}

/** A sentence in the middle of the window: why the applet can't show. */
@Composable
private fun AppletMessage(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().padding(24.dp).testTag("applet_page_message"), contentAlignment = Alignment.Center) {
        Text(text, color = cs.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
    }
}

/**
 * An applet as a screen of the app, where a host has one window (iPhone, iPad): only the applet's page, full screen
 * inside the safe area — no bar, no Done, like its own window on a Mac or its own activity on Android (Ahmet). It is
 * left by the app's left-edge swipe (the route's layer) or the system's own gestures.
 */
@Composable
fun AppletScreen(fleet: FleetStore, hostId: String, name: String, onBack: () -> Unit, openChat: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(cs.background).testTag("applet_screen_$name")) {
        AppletPage(fleet, hostId, name, Modifier.fillMaxSize(), onOpenChat = openChat)
        if (dev.supermux.ui.widgets.iosStyleBackSwipe) {
            // The edge swipe is the only way out, so the page must never take the edge's touches. A thin strip over
            // the page's left edge keeps them in Compose (the app's edge-back gesture), whatever the page does there;
            // it consumes nothing, so a tap or a swipe that isn't the back gesture still reaches nothing harmful.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(EDGE_STRIP)
                    .webViewOverlay()
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .testTag("applet_screen_edge"),
            )
        }
    }
}

/** How wide the left edge is kept for the back swipe, over the applet's page (iOS's own edge is about this). */
private val EDGE_STRIP = 18.dp
