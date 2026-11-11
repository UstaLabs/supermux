// The active host's broker calls for its status, updates, restart and telemetry (#97: the machine page's header and
// Privacy & labs). This is the ACTIVE HOST'S BROKER, never the app itself: the app's own updater is General ▸ This app.
package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.supermux.net.BrokerRestart
import dev.supermux.net.RunUpdateResult
import dev.supermux.net.TelemetryRecent
import dev.supermux.net.TelemetryState
import dev.supermux.net.UpdateStatus
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore

/**
 * Every broker call the machine page's header and Privacy & labs' telemetry make, in one holder.
 *
 * `restartBroker` is suspend→Boolean (desktop's shape): a 5xx or an unreachable broker surfaces as
 * an error instead of a spinner that ends on a timer. Android's `FleetStore` wrapper was
 * fire-and-forget and is retyped in `:shared` rather than dumbing this down.
 */
@Immutable
class SystemSettingsActions(
    /** Cached broker update status; `null` = transport/decode failure. */
    val updateStatus: suspend () -> UpdateStatus? = { null },
    /** Force a fresh upstream check (the UPDATES "Recheck" button). */
    val checkUpdate: suspend () -> UpdateStatus? = { null },
    /** Start the broker's self-updater. */
    val runUpdate: suspend () -> RunUpdateResult? = { null },
    /** True when the restart POST was accepted. */
    val restartBroker: suspend () -> Boolean = { false },
    /**
     * The machine page's Restart: tells a broker that can't restart itself (409, no service manager) from one that
     * failed. Defaults to [restartBroker]'s yes/no.
     */
    val restart: suspend () -> BrokerRestart = { if (restartBroker()) BrokerRestart.Accepted else BrokerRestart.Failed },
    /** Telemetry state on the host; `null` = failure or an older broker (section hidden). */
    val getTelemetry: suspend () -> TelemetryState? = { null },
    val setTelemetry: suspend (Boolean) -> TelemetryState? = { null },
    val getTelemetryRecent: suspend () -> TelemetryRecent? = { null },
)

/** [SystemSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberSystemSettingsActions(app: HostStore): SystemSettingsActions = remember(app) {
    SystemSettingsActions(
        updateStatus = { app.updateStatus() },
        checkUpdate = { app.checkUpdate() },
        runUpdate = { app.runUpdate() },
        restartBroker = { app.restartBroker() },
        restart = { app.restartBrokerResult() },
        getTelemetry = { app.getTelemetry() },
        setTelemetry = { app.setTelemetry(it) },
        getTelemetryRecent = { app.getTelemetryRecent() },
    )
}

/** [SystemSettingsActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberSystemSettingsActions(fleet: FleetStore): SystemSettingsActions = remember(fleet) {
    SystemSettingsActions(
        updateStatus = { fleet.updateStatus() },
        checkUpdate = { fleet.checkUpdate() },
        runUpdate = { fleet.runUpdate() },
        restartBroker = { fleet.restartBroker() },
        restart = { fleet.restartBrokerResult() },
        getTelemetry = { fleet.getTelemetry() },
        setTelemetry = { fleet.setTelemetry(it) },
        getTelemetryRecent = { fleet.getTelemetryRecent() },
    )
}

internal fun isRunningState(state: String): Boolean =
    // "starting" is the client-only pseudo-state (see stateLabel) — counted as running so the row
    // gets a spinner rather than bare text. The broker never sends it, so broker-state callers
    // (the Update-button gate, the poll loop) are unaffected.
    state == "starting" || state == "checking" || state == "downloading" || state == "swapping"
