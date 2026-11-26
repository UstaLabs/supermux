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
import androidx.compose.runtime.setValue
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

fun appletUri(hostId: String, name: String, at: String? = null): String =
    "$APPLET_URI_PREFIX$hostId/$name" + (appletAt(at)?.let { "?at=" + pctEncode(it) } ?: "")

/** (host record id, applet name) from [appletUri]'s form; null for anything else. */
fun parseAppletUri(uri: String): Pair<String, String>? {
    if (!uri.startsWith(APPLET_URI_PREFIX)) return null
    val parts = uri.removePrefix(APPLET_URI_PREFIX).substringBefore('?').trimEnd('/').split('/') // platform-ok: url
    if (parts.size != 2 || parts.any { it.isBlank() }) return null
    return parts[0] to parts[1]
}

/**
 * #165: where in the applet a link opens ("/#164", "/board?x=1"): its widget row's href. Only a place inside the applet
 * ("/…" or "#…"), never another site.
 */
fun appletAt(at: String?): String? =
    at?.trim()?.takeIf { (it.startsWith("/") || it.startsWith("#")) && !it.startsWith("//") && it.length <= 300 } // platform-ok: url

/** The place an [appletUri] opens at ([appletAt]), or null. */
fun appletUriAt(uri: String): String? =
    uri.substringAfter('?', "").split('&').firstOrNull { it.startsWith("at=") }?.removePrefix("at=")
        ?.let(::pctDecode)?.let(::appletAt)

private fun pctEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~/") append(c.toChar()) else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
}

private fun pctDecode(s: String): String? {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') { if (i + 2 >= s.length) return null; out += (s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null).toByte(); i += 3 }
        else { out += c.toString().encodeToByteArray().toList(); i++ }
    }
    return out.toByteArray().decodeToString()
}

/**
 * #165: places to open applets at, left by whatever opened them (a widget row's link) for the page to take on its
 * first load. Keyed by host and applet; taken once.
 */
object AppletDeepLinks {
    private val pending = mutableMapOf<String, String>()
    fun note(uri: String) { val at = appletUriAt(uri) ?: return; parseAppletUri(uri)?.let { (h, n) -> pending["$h/$n"] = at } }
    fun take(hostId: String, name: String): String? = pending.remove("$hostId/$name")
}

/** "https://relay.example" from "https://relay.example/p/": where the preview key is valid, which serves /a/ too. */
internal fun originOf(url: String): String {
    val scheme = url.indexOf("://")
    if (scheme < 0) return url.trimEnd('/') // platform-ok: url
    val slash = url.indexOf('/', scheme + 3)
    return if (slash < 0) url else url.substring(0, slash)
}

/** The applet's page on the origin the key was minted for, so the cookie and the page always agree. */
internal fun appletPageUrl(key: PreviewCookieDto, name: String, at: String? = null): String =
    "${originOf(key.url)}/a/$name/" + (appletAt(at)?.removePrefix("/") ?: "")

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
fun AppletPage(
    fleet: FleetStore?,
    hostId: String,
    name: String,
    modifier: Modifier = Modifier,
    onOpenChat: (String) -> Unit = {},
    noEngine: String? = null,
    reload: kotlinx.coroutines.flow.Flow<Unit>? = null,
) {
    val at = remember(hostId, name) { AppletDeepLinks.take(hostId, name) }
    val views by (fleet?.hostViews ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<dev.supermux.host.HostView>()) }).collectAsState()
    val app = remember(fleet, views, hostId) { fleet?.appForRecord(hostId) }
    when {
        app != null -> AppletPage(app, name, modifier, onOpenChat, noEngine, reload, at)
        fleet != null && views.isNotEmpty() && views.none { it.recordId == hostId } -> AppletMessage(localized(Str.applets_window_no_host), modifier)
        else -> Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

/**
 * One applet page, the window's own: its web view state and where it loads from. Every open is a fresh navigation from
 * the server, as a page in the Browser tab (Ahmet): nothing of an earlier open is reused. Main thread only.
 */
internal class AppletPageModel(val app: HostStore, val name: String, val at: String? = null) {
    val state = WebViewState(BLANK)
    /** The applet's own origin, known once the key says where the page loads from: the bridge talks only to it. */
    var origin by mutableStateOf<String?>(null)
    var problem by mutableStateOf<String?>(null)
    val settings: WebSettings get() = origin?.let { APPLET_SETTINGS.copy(bridgeOrigins = setOf(it.lowercase())) } ?: APPLET_SETTINGS
}

/**
 * The applet [name] of host [app], filling its window. The page's bridge (its own origin only) answers the page API's
 * native calls: agent.ask after his click on a button, and openChat for the "Sent to …" link it shows; [onOpenChat]
 * brings that chat up in the main window. Each [reload] signal loads the page again from the server (⌘R).
 *
 * The page is the window's alone and goes with it: closing the window forgets it in the runtime, so the next open
 * neither takes a parked page over nor restores its history (WebKit loads a restored history entry from its cache,
 * however stale). Only a warm, empty engine is shared ([AppletWarmEngine]).
 */
@Composable
fun AppletPage(
    app: HostStore,
    name: String,
    modifier: Modifier = Modifier,
    onOpenChat: (String) -> Unit = {},
    noEngine: String? = null,
    reload: kotlinx.coroutines.flow.Flow<Unit>? = null,
    at: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val webViews = LocalAppWebViews.current ?: AppWebViews.app
    val model = remember(app, name) { AppletPageModel(app, name, at) }
    val state = model.state
    androidx.compose.runtime.DisposableEffect(model, webViews) {
        onDispose { (state.content as? WebContent.Url)?.let { webViews.runtime.forget(it) } }
    }
    LaunchedEffect(reload, state) { reload?.collect { if (state.content is WebContent.Url) state.navigator.reload() } }
    AppletPageLoader(model, onOpenChat)
    // How long opening took, from this window to a live, loaded page: the log's.
    LaunchedEffect(model) {
        val opened = kotlin.time.TimeSource.Monotonic.markNow()
        androidx.compose.runtime.snapshotFlow { state.phase == WebViewPhase.Live && state.content is WebContent.Url && state.url?.startsWith("http") == true && state.loading == dev.supermux.webview.LoadingState.Idle }
            .first { it }
        println("[AppletPage] $name shown in ${opened.elapsedNow().inWholeMilliseconds} ms")
    }
    val platform = LocalPlatform.current
    val openExternal by rememberUpdatedState<(String) -> Unit>({ url -> platform.openUrl(url) })
    val client = remember { AppletClient { openExternal(it) } }
    Box(modifier.fillMaxSize().background(cs.background).testTag("applet_page_$name")) {
        ProvideWebViewRuntime(webViews.runtime) {
            WebView(state, Modifier.fillMaxSize(), settings = model.settings, client = client, activation = Activation.Eager)
        }
        model.problem?.let { AppletMessage(it, Modifier.background(cs.background)) }
        // A page that couldn't load says why, never a blank window (#117: a direct-http host the platform refused).
        appletLoadFailure(state.error)?.let { reason ->
            AppletMessage(localized(Str.applets_window_load_failed, reason), Modifier.background(cs.background))
        }
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
                if (state.content !is WebContent.Url) state.load(WebContent.Url(appletPageUrl(key, name, model.at)))
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
            val cleared = webViews.runtime.clearSiteData(BROWSER_PROFILE, origins)
            println("[AppletPage] forgot host ${host.recordId}: ${origins.size} origin(s), cleared=$cleared") // i18n-ok: a log line, never shown
        }
    }
}

/**
 * A warm, empty engine of the applets' profile, kept by the pool so that opening an applet skips the engine's start: no
 * page in it, so every open is still a fresh navigation from the server (Ahmet).
 */
@Composable
fun AppletWarmEngine() {
    val webViews = LocalAppWebViews.current ?: AppWebViews.app
    LaunchedEffect(webViews) { webViews.runtime.prewarm(BROWSER_PROFILE) }
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

/**
 * Why the page couldn't load, in the engine's words, or null when it loaded or the server answered (an HTTP error
 * status is a page of the broker's own, shown as it is).
 */
internal fun appletLoadFailure(error: dev.supermux.webview.WebError?): String? = when (error) {
    is dev.supermux.webview.WebError.Network -> error.description.trim().trimEnd('.') + "."
    is dev.supermux.webview.WebError.Certificate -> error.description.trim().trimEnd('.') + "."
    else -> null
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
