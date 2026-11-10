// The active host's paired devices: list, mint a pairing link, revoke (#97: the machine page's Devices).
package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.DeviceDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore

/**
 * Every broker call the machine page's Devices make, in one holder.
 *
 * Shapes are desktop's: the load returns `null` for a transport/decode failure (an empty list means
 * "no devices"), and the revoke reports whether the broker actually accepted the DELETE.
 */
@Immutable
class DevicesSettingsActions(
    /** `null` = transport/decode failure; empty = legitimately no devices. */
    val devicesLoad: suspend () -> List<DeviceDto>? = { null },
    val deviceAdd: suspend (name: String) -> AddDeviceResponse? = { null },
    val deviceRevoke: suspend (name: String) -> Boolean = { false },
)

/** [DevicesSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberDevicesSettingsActions(app: HostStore): DevicesSettingsActions = remember(app) {
    DevicesSettingsActions(
        devicesLoad = { app.devices() },
        deviceAdd = { name -> app.addDevice(name) },
        deviceRevoke = { name -> app.revokeDevice(name) },
    )
}

/** [DevicesSettingsActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberDevicesSettingsActions(fleet: FleetStore): DevicesSettingsActions = remember(fleet) {
    DevicesSettingsActions(
        devicesLoad = { fleet.devices() },
        deviceAdd = { name -> fleet.addDevice(name) },
        deviceRevoke = { name -> fleet.revokeDevice(name) },
    )
}
