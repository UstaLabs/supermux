// The active host's shared links (reverse proxies): list, share a port, public/private, remove (#97: the machine
// page's Shared links).
package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.supermux.net.CreateProxyResponse
import dev.supermux.net.ProxyDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Every broker call the machine page's Shared links make, in one holder.
 *
 * Shapes are desktop's: the load returns `null` for a transport/decode failure, create returns the
 * created proxy (or null), and the two mutations report whether the broker accepted them. Android's
 * `FleetStore` wrappers were fire-and-forget `launch`es — a rejected PATCH looked exactly like a
 * successful one — and are retyped to these.
 */
@Immutable
class ProxiesSettingsActions(
    /** `null` = transport/decode failure; empty = legitimately no proxies. */
    val proxiesLoad: suspend () -> List<ProxyDto>? = { null },
    /**
     * Session names available for the expose-port form, on the ACTIVE host.
     *
     * A Flow, not a snapshot getter: the form is opened long after the holder is built, and on
     * Android the active host (and its session list) changes underneath it — a `() -> List` read
     * during composition handed the dialog whatever was true when the screen first composed.
     */
    val sessionNames: Flow<List<String>> = flowOf(emptyList()),
    val proxyCreate: suspend (sessionName: String, port: Int, domain: String?) -> CreateProxyResponse? =
        { _, _, _ -> null },
    val proxySetPublic: suspend (domain: String, isPublic: Boolean) -> Boolean = { _, _ -> false },
    val proxyRemove: suspend (domain: String) -> Boolean = { false },
)

/** [ProxiesSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberProxiesSettingsActions(app: HostStore): ProxiesSettingsActions = remember(app) {
    ProxiesSettingsActions(
        proxiesLoad = { app.proxiesForSettings() },
        sessionNames = app.sessions.map { list -> list.map { it.name } },
        proxyCreate = { session, port, domain -> app.createProxy(session, port, domain) },
        proxySetPublic = { domain, isPublic -> app.setProxyPublic(domain, isPublic) },
        proxyRemove = { domain -> app.removeProxy(domain) },
    )
}

/** [ProxiesSettingsActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberProxiesSettingsActions(fleet: FleetStore): ProxiesSettingsActions = remember(fleet) {
    ProxiesSettingsActions(
        proxiesLoad = { fleet.proxiesForSettings() },
        sessionNames = fleet.activeHostSessionNames,
        proxyCreate = { session, port, domain -> fleet.createProxy(session, port, domain) },
        proxySetPublic = { domain, isPublic -> fleet.setProxyPublic(domain, isPublic) },
        proxyRemove = { domain -> fleet.removeProxy(domain) },
    )
}
