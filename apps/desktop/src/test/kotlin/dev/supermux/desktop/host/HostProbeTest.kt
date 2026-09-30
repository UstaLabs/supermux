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
    @Test fun parseWithoutHostIdIsForeign() = assertEquals(HostProbeResult.ForeignProcess, HostProber.parse("""{"name":"x"}"""))
    @Test fun parseGarbageIsForeign() = assertEquals(HostProbeResult.ForeignProcess, HostProber.parse("<html>"))

    @Test fun unknownModeAndDirIsReadOnly() = assertEquals(HostPlan.ReadOnly, decide(sm(mode = null, dir = null)))
    @Test fun binaryWithoutStateDirIsReadOnly() = assertEquals(HostPlan.ReadOnly, decide(sm(dir = null)))
    @Test fun managedWithUnknownBundledBuildIsUsed() =
        assertEquals(HostPlan.UseOwn, decide(sm(managedBy = "desktop", build = "1.4.0 (old)"), bundled = null))
    @Test fun managedSourceModeIsNeverUpdated() =
        assertEquals(HostPlan.UseOwn, decide(sm(managedBy = "desktop", mode = "source", build = "1.4.0 (old)")))
    @Test fun managedWithForeignStateDirIsReadOnly() =
        assertEquals(HostPlan.ReadOnly, decide(sm(managedBy = "desktop", dir = "/elsewhere/state")))
    @Test fun busyWaits() = assertEquals(HostPlan.Wait, decide(HostProbeResult.Busy))

    private fun withServer(status: Int, body: String, block: (Int) -> Unit) {
        val srv = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/host") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.start()
        try { block(srv.address.port) } finally { srv.stop(0) }
    }

    @Test fun probe503IsBusy() = withServer(503, "{}") { assertEquals(HostProbeResult.Busy, HostProber.probe(it)) }
    @Test fun probe404IsForeign() = withServer(404, "no") { assertEquals(HostProbeResult.ForeignProcess, HostProber.probe(it)) }
    @Test fun probeHtmlIsForeign() = withServer(200, "<html>") { assertEquals(HostProbeResult.ForeignProcess, HostProber.probe(it)) }
    @Test fun probeSupermuxJson() = withServer(200, """{"hostId":"h9","protocolVersion":1}""") {
        assertEquals(HostProbeResult.Supermux("h9", null, null, null, null), HostProber.probe(it))
    }
    @Test fun probeClosedPortIsFree() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(HostProbeResult.PortFree, HostProber.probe(port))
    }
}
