package dev.supermux.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.supermux.net.PatchViewBody
import dev.supermux.net.PreviewCookieDto
import dev.supermux.net.ProxyDto
import dev.supermux.state.HostStore
import dev.supermux.util.TransportPolicy
import kotlinx.serialization.json.JsonObject

/**
 * The broker calls a Browser tab makes (#105), in one holder like DisplayActions. Defaults are the empty answers, so a
 * test or a preview can host the pane without a broker.
 */
@Immutable
class BrowserActions(
    /** Write the tab's page (its `{url}` state) and title to its view. False when the broker refused. */
    val saveView: suspend (workspaceId: String, viewId: String, state: JsonObject, title: String?) -> Boolean =
        { _, _, _, _ -> false },
    /** The host's named port previews: the bar names one by its port, and a port with no by-port route opens one. */
    val proxies: suspend () -> List<ProxyDto> = { emptyList() },
    /** The host's by-port preview template (`https://…/p/{port}/`), or null from a broker without one. */
    val byPort: suspend () -> String? = { null },
    /** This device's preview-only cookie for the host, asked for once a run; null when the host gives none. */
    val previewCookie: suspend () -> PreviewCookieDto? = { null },
    /** Whether this app runs on the host, so a `localhost` address is the host's own. */
    val onHost: Boolean = false,
)

/** [BrowserActions] against one paired host. */
@Composable
fun rememberBrowserActions(app: HostStore): BrowserActions = remember(app) {
    var asked = false
    var cookie: PreviewCookieDto? = null
    // One token a run: the broker mints a new one each time it's asked. The web client, signed in by the app's own
    // cookie, is refused (the route takes a bearer only), and its iframe tab needs none.
    suspend fun previewCookie(): PreviewCookieDto? {
        if (!asked) {
            asked = true
            cookie = runCatching { app.api.previewToken().cookie }.getOrNull()
        }
        return cookie
    }
    BrowserActions(
        saveView = { workspaceId, viewId, state, title ->
            runCatching { app.api.patchView(workspaceId, viewId, PatchViewBody(title = title, state = state)) }.isSuccess
        },
        proxies = { app.proxies() },
        // By-port pages take the normal login (Ahmet accepted path mode's same-origin risk, 2026-11-08).
        byPort = { app.api.byPortTemplate() },
        previewCookie = { previewCookie() },
        // A broker reached over loopback runs on this machine: a desktop that is the host reaches its own that way.
        onHost = TransportPolicy.isLoopback(app.baseUrl),
    )
}
