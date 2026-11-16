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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
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
fun AppletPage(fleet: FleetStore?, hostId: String, name: String, modifier: Modifier = Modifier, onOpenChat: (String) -> Unit = {}) {
    val views by (fleet?.hostViews ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<dev.supermux.host.HostView>()) }).collectAsState()
    val app = remember(fleet, views, hostId) { fleet?.appForRecord(hostId) }
    when {
        app != null -> AppletPage(app, name, modifier, onOpenChat)
        fleet != null && views.isNotEmpty() && views.none { it.recordId == hostId } -> AppletMessage(localized(Str.applets_window_no_host), modifier)
        else -> Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

/**
 * The applet [name] of host [app], filling its window. The page's bridge (its own origin only) answers the page API's
 * native calls: agent.ask after his click on a button, and openChat for the "Sent to …" link it shows; [onOpenChat]
 * brings that chat up in the main window.
 */
@Composable
fun AppletPage(app: HostStore, name: String, modifier: Modifier = Modifier, onOpenChat: (String) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val webViews = LocalAppWebViews.current ?: AppWebViews.app
    val state = remember(app, name) { WebViewState(BLANK) }
    val keeper = remember(app) { PreviewKeeper.of(app) }
    val sink = remember(state) { PreviewKeeper.Sink<PreviewCookieDto> { state.navigator.putPreviewKeys(it) } }
    var problem by remember(app, name) { mutableStateOf<String?>(null) }
    // The bridge talks only to the applet's own origin, known once the key says where the page loads from.
    var origin by remember(app, name) { mutableStateOf<String?>(null) }
    val settings = remember(origin) { origin?.let { APPLET_SETTINGS.copy(bridgeOrigins = setOf(it.lowercase())) } ?: APPLET_SETTINGS }
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
    val live = state.phase == WebViewPhase.Live
    LaunchedEffect(live, keeper) {
        if (!live) return@LaunchedEffect
        val detach = keeper.attach(sink)
        try {
            // The key, renewed first when due; put in again here: a key the store already had may predate /a/.
            val key = keeper.ready(sink)
            if (key == null) {
                problem = localized(Str.applets_window_no_key)
            } else {
                problem = null
                origin = originOf(key.url)
                sink.put(key)
                if (state.content !is WebContent.Url) state.load(WebContent.Url(appletPageUrl(key, name)))
            }
            awaitCancellation()
        } finally {
            detach()
        }
    }
    val platform = LocalPlatform.current
    val openExternal by rememberUpdatedState<(String) -> Unit>({ url -> platform.openUrl(url) })
    val client = remember { AppletClient { openExternal(it) } }
    Box(modifier.fillMaxSize().background(cs.background).testTag("applet_page_$name")) {
        ProvideWebViewRuntime(webViews.runtime) {
            WebView(state, Modifier.fillMaxSize(), settings = settings, client = client, activation = Activation.Eager)
        }
        problem?.let { AppletMessage(it, Modifier.background(cs.background)) }
        // No engine can show pages here (on Android: a System WebView too old for the engine's needs): say so, never a
        // blank window. Why is the log's.
        (state.phase as? WebViewPhase.Unavailable)?.let { unavailable ->
            LaunchedEffect(unavailable) {
                println("[AppletPage] no web engine: ${unavailable.reason}${unavailable.detail?.let { ": $it" }.orEmpty()}")
            }
            AppletMessage(localized(Str.applets_window_no_engine), Modifier.background(cs.background))
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
 * Where a host with one window (iOS) shows an applet: full screen over the app, the page and a Done to leave it — an
 * iPhone's idiom for a full-screen cover. [AppletCover] draws it; the host's [AppletOpener] fills [open].
 */
class AppletCoverState {
    var open by mutableStateOf<Pair<String, String>?>(null)
        private set

    val opener = dev.supermux.ui.session.AppletOpener { hostId, applet -> open = hostId to applet.name }

    /** Opens [name] of [hostId] from outside the app (a widget, a control, Spotlight). */
    fun show(hostId: String, name: String) { open = hostId to name }

    fun close() { open = null }
}

@Composable
fun AppletCover(state: AppletCoverState, fleet: FleetStore, onOpenChat: (String) -> Unit = {}) {
    val (hostId, name) = state.open ?: return
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("applet_cover"),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                localized(Str.applets_cover_done),
                color = cs.primary,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.clickable { state.close() }.padding(vertical = 6.dp).testTag("applet_cover_done"),
            )
        }
        AppletPage(fleet, hostId, name, Modifier.weight(1f), onOpenChat = { id -> state.close(); onOpenChat(id) })
    }
}
