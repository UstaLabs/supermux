// Cluster G4: every broker call the Display panel and the Displays management screen make, in one
// holder — the same `@Immutable XxxActions` + `rememberXxxActions(HostStore)`/`(FleetStore)` shape
// clusters E and F use.
//
// Shapes are desktop's (suspend mutations, typed transports), with Android's per-session start:
// `startDisplay` takes the session NAME because that is what `POST /displays` keys on, and the
// management screen starts a host-default display by passing an empty one.
package dev.supermux.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.supermux.net.DisplayStream
import dev.supermux.net.ScrcpyClient
import dev.supermux.net.VncClient
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The display surface's broker seam.
 *
 * Defaults are the empty answers, so a preview or a test can host the panel with no broker: no
 * streams, a start that does nothing. The two `connect*` factories THROW by default rather than
 * returning a dead client — they are only ever called once a running stream has been resolved, so
 * an unwired holder reaching them is a wiring bug, not an empty state.
 */
@Immutable
class DisplayActions(
    /** Live streams across this shell, kept current by `display_added`/`display_removed`. */
    val displays: StateFlow<List<DisplayStream>> = MutableStateFlow(emptyList()),
    /** `GET /displays` — seeds [displays] once on open; the flow stays live afterwards. */
    val listDisplays: suspend () -> List<DisplayStream> = { emptyList() },
    /** `POST /displays` for [sessionName]; empty name = the host's default display. */
    val startDisplay: suspend (sessionName: String) -> Unit = {},
    /** `DELETE /displays/<id>`. */
    val stopDisplay: suspend (id: String) -> Unit = {},
    val connectVnc: (streamId: String) -> VncClient = { error("no VNC transport bound") },
    val connectScrcpy: (streamId: String) -> ScrcpyClient = { error("no scrcpy transport bound") },
)

/** [DisplayActions] against ONE paired host — desktop's wiring. */
@Composable
fun rememberDisplayActions(app: HostStore): DisplayActions = remember(app) {
    DisplayActions(
        displays = app.displays,
        listDisplays = { app.listDisplays() },
        startDisplay = { name -> app.startDisplay(name) },
        stopDisplay = { id -> app.stopDisplay(id) },
        connectVnc = { app.connectVnc(it) },
        connectScrcpy = { app.connectScrcpy(it) },
    )
}

/** [DisplayActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberDisplayActions(fleet: FleetStore): DisplayActions = remember(fleet) {
    DisplayActions(
        displays = fleet.displays,
        listDisplays = { fleet.listDisplays() },
        startDisplay = { name -> fleet.startDisplay(name) },
        stopDisplay = { id -> fleet.stopDisplay(id) },
        connectVnc = { fleet.connectVnc(it) },
        connectScrcpy = { fleet.connectScrcpy(it) },
    )
}
