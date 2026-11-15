// #117: what an applet window shows — the applet's page and nothing else, like an installed web app. The same page on
// every platform: desktop puts it in its own OS window, Android in its own activity (task), iOS full screen.
//
// Auth: the page loads from the host's /a/<name>/ with the device's preview-only key in the cookie store, never the
// device token (an applet shares the app's origin until #114, and a page holding the device token would hold the
// owner's whole API). The key is the host's one PreviewKeeper's, shared with the Browser tabs (BROWSER_PROFILE), so
// a renewal through either reaches both.
package dev.supermux.ui.applets

import androidx.compose.foundation.background
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
fun AppletPage(fleet: FleetStore?, hostId: String, name: String, modifier: Modifier = Modifier) {
    val views by (fleet?.hostViews ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<dev.supermux.host.HostView>()) }).collectAsState()
    val app = remember(fleet, views, hostId) { fleet?.appForRecord(hostId) }
    when {
        app != null -> AppletPage(app, name, modifier)
        fleet != null && views.isNotEmpty() && views.none { it.recordId == hostId } -> AppletMessage(localized(Str.applets_window_no_host), modifier)
        else -> Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

/** The applet [name] of host [app], filling its window. */
@Composable
fun AppletPage(app: HostStore, name: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val webViews = LocalAppWebViews.current ?: AppWebViews.app
    val state = remember(app, name) { WebViewState(BLANK) }
    val keeper = remember(app) { PreviewKeeper.of(app) }
    val sink = remember(state) { PreviewKeeper.Sink<PreviewCookieDto> { state.navigator.putPreviewKeys(it) } }
    var problem by remember(app, name) { mutableStateOf<String?>(null) }
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
            WebView(state, Modifier.fillMaxSize(), settings = APPLET_SETTINGS, client = client, activation = Activation.Eager)
        }
        problem?.let { AppletMessage(it, Modifier.background(cs.background)) }
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
