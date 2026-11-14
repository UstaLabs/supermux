package dev.supermux.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
    /** The host's preview key in the Browser tabs' cookie store (#155): one for the process, renewed before pages use it. */
    val preview: PreviewKeeper<PreviewCookieDto> = PreviewKeeper(mint = { null }),
    /** Whether this app runs on the host, so a `localhost` address is the host's own. */
    val onHost: Boolean = false,
    /** #122 phase 4: the name of the session whose process listens on [port] on the host, or null. */
    val portOwner: suspend (port: Int) -> String? = { null },
)

/** [BrowserActions] against one paired host. */
@Composable
fun rememberBrowserActions(app: HostStore): BrowserActions {
    val preview = remember(app) { PreviewKeeper.of(app) }
    // The key renews on start, reconnect and a timer while any screen holding these actions is up; one driver at a time.
    LaunchedEffect(preview) { while (true) preview.drive(this, app.resynced).join() }
    return remember(app) { BrowserActions(
        saveView = { workspaceId, viewId, state, title ->
            runCatching { app.api.patchView(workspaceId, viewId, PatchViewBody(title = title, state = state)) }.isSuccess
        },
        proxies = { app.proxies() },
        // By-port pages take the normal login (Ahmet accepted path mode's same-origin risk, 2026-11-08).
        byPort = { app.api.byPortTemplate() },
        preview = preview,
        // A broker reached over loopback runs on this machine: a desktop that is the host reaches its own that way.
        onHost = TransportPolicy.isLoopback(app.baseUrl),
        portOwner = { port -> runCatching { app.api.portOwner(port)?.sessionName }.getOrNull() },
    ) }
}
