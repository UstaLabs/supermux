package dev.supermux.desktop.host

import kotlin.test.Test
import kotlin.test.assertEquals

class HostProbeTest {
    private val stateDir = "/Users/a/.mux/state"
    private val prefs = HostingPrefs(port = 9898)
    private fun sm(
        managedBy: String? = null, build: String? = "1.5.0 (abc)", mode: String? = "binary",
        dir: String? = stateDir, id: String = "h1",
    ) = HostProbeResult.Supermux(hostId = id, build = build, mode = mode, managedBy = managedBy, stateDir = dir)

    private fun decide(p: HostProbeResult, prefs: HostingPrefs = this.prefs, bundled: String? = "1.5.0 (abc)") =
        decideHost(p, prefs, bundledBuild = bundled, appStateDir = stateDir)

    @Test fun hostingOffDoesNothing() =
        assertEquals(HostPlan.NotHosting, decide(HostProbeResult.PortFree, prefs.copy(hosting = false)))

    @Test fun freePortStarts() = assertEquals(HostPlan.Start, decide(HostProbeResult.PortFree))

    @Test fun foreignProcessMovesPort() = assertEquals(HostPlan.MovePort, decide(HostProbeResult.ForeignProcess))

    @Test fun ownSameBuildIsUsed() = assertEquals(HostPlan.UseOwn, decide(sm(managedBy = "desktop")))

    @Test fun ownDifferentBuildIsUpdated() =
        assertEquals(HostPlan.UpdateOwn, decide(sm(managedBy = "desktop", build = "1.4.0 (old)")))

    @Test fun outsideBinaryInOurStateDirAsksToTakeOver() =
        assertEquals(HostPlan.AskTakeover("h1"), decide(sm()))

    @Test fun legacyWithoutHostIsTreatedAsOutsideAndAsked() =
        assertEquals(HostPlan.AskTakeover(null), decide(HostProbeResult.LegacySupermux))

    @Test fun leftAloneIsReadOnly() =
        assertEquals(HostPlan.ReadOnly, decide(sm(), prefs.copy(leftAloneHostIds = setOf("h1"))))

    @Test fun sourceOrDockerOrForeignStateDirIsReadOnly() {
        assertEquals(HostPlan.ReadOnly, decide(sm(mode = "source")))
        assertEquals(HostPlan.ReadOnly, decide(sm(mode = "docker")))
        assertEquals(HostPlan.ReadOnly, decide(sm(dir = "/elsewhere/state")))
    }

    @Test fun newerOutsideBrokerAsksAboutDowngrade() =
        assertEquals(HostPlan.AskDowngrade("h1"), decide(sm(build = "1.6.0 (new)")))

    @Test fun anAppManagedButNewerBrokerIsStillUpdatedToTheBundledBuild() =
        assertEquals(HostPlan.UpdateOwn, decide(sm(managedBy = "desktop", build = "1.6.0 (new)")))

    @Test fun parseReadsLocalFields() = assertEquals(
        HostProbeResult.Supermux("h1", "1.5.0 (abc)", "binary", "desktop", "/s"),
        HostProber.parse("""{"hostId":"h1","name":"n","protocolVersion":1,"build":"1.5.0 (abc)","mode":"binary","managedBy":"desktop","stateDir":"/s"}"""),
    )
    @Test fun parseWithoutHostIdIsLegacy() = assertEquals(HostProbeResult.LegacySupermux, HostProber.parse("""{"name":"x"}"""))
    @Test fun parseGarbageIsForeign() = assertEquals(HostProbeResult.ForeignProcess, HostProber.parse("<html>"))
}
