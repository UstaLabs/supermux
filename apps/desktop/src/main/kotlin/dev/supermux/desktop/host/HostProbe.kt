package dev.supermux.desktop.host

/** Result of probing :9898 before the desktop starts/adopts a broker (spec §6). */
sealed interface HostProbeResult {
    data class SupermuxHost(val hostId: String) : HostProbeResult
    object LegacySupermux : HostProbeResult   // responds but no GET /host (pre-Plan-1)
    object ForeignProcess : HostProbeResult   // :9898 held by something else
    object PortFree : HostProbeResult
}

enum class HostDecision { AdoptExternal, UpgradeRequired, PortConflict, SpawnManaged }

/** Pure adopt-don't-duplicate policy — spec §6. Never returns "kill": the app
 *  must not stop or reconfigure a broker it did not start. */
fun decideHost(probe: HostProbeResult): HostDecision = when (probe) {
    is HostProbeResult.SupermuxHost -> HostDecision.AdoptExternal
    HostProbeResult.LegacySupermux -> HostDecision.UpgradeRequired
    HostProbeResult.ForeignProcess -> HostDecision.PortConflict
    HostProbeResult.PortFree -> HostDecision.SpawnManaged
}
