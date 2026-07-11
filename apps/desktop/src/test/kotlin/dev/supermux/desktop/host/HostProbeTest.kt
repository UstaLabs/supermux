package dev.supermux.desktop.host

import kotlin.test.Test
import kotlin.test.assertEquals

class HostProbeTest {
    @Test fun validHostAdopts() =
        assertEquals(HostDecision.AdoptExternal, decideHost(HostProbeResult.SupermuxHost(hostId = "habc")))

    @Test fun legacyNeedsUpgrade() =
        assertEquals(HostDecision.UpgradeRequired, decideHost(HostProbeResult.LegacySupermux))

    @Test fun foreignProcessConflicts() =
        assertEquals(HostDecision.PortConflict, decideHost(HostProbeResult.ForeignProcess))

    @Test fun nothingSpawnsManaged() =
        assertEquals(HostDecision.SpawnManaged, decideHost(HostProbeResult.PortFree))
}
