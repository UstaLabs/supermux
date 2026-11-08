package dev.supermux.desktop.host

import dev.supermux.desktop.platform.DesktopOs
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #113: after an update a Windows user's new broker couldn't bind its port. The listener's owner (the old broker) was
// gone, but its leftover processes still held the socket, and the app fell back to running nothing, saying only that
// supermux didn't answer. The app now stops a gone broker's leftovers and names whatever still holds the port.
class WindowsPortHoldersTest {
    private val owners = BrokerService.portOwnersArgv(9899)
    private val listing = BrokerService.listAllProcessesArgv()

    /** The old broker 100 has exited; its keeper, that keeper's agent and the agent's shim, and its sessiond remain. */
    private val leftovers = listOf(
        "300\t100\tsupermux-broker.exe\t\"C:\\s\\supermux-broker.exe\" __core-keeper",
        "301\t300\tclaude.exe\t\"C:\\c\\claude.exe\"",
        "302\t301\tsupermux-broker.exe\t\"C:\\s\\supermux-broker.exe\" shim",
        "400\t100\tmux-sessiond.exe\t\"C:\\s\\mux-sessiond.exe\"",
        // Another live broker's agent shim: not the gone broker's.
        "500\t900\tsupermux-broker.exe\t\"C:\\s\\supermux-broker.exe\" shim",
        "900\t1\texplorer.exe\tC:\\Windows\\explorer.exe",
        "",
    ).joinToString("\r\n")

    @Test fun aGoneBrokersSupermuxLeftoversAreTheStaleHolders() {
        val holders = BrokerService.portHolders(listOf(100L), BrokerService.parseWindowsProcesses(leftovers))
        assertEquals(listOf(300L, 302L, 400L), holders.map { it.pid }.sorted())
        assertTrue(holders.all { it.stale })
    }

    @Test fun aLiveOwnerIsNamedAndNeverStale() {
        val procs = BrokerService.parseWindowsProcesses("100\t1\tnode.exe\t\"C:\\n\\node.exe\" server.js\r\n")
        assertEquals(
            listOf(BrokerService.PortHolder(100, "node.exe", "\"C:\\n\\node.exe\" server.js", stale = false)),
            BrokerService.portHolders(listOf(100L), procs),
        )
    }

    @Test fun aGoneOwnerWithNoLeftoverOfOursIsStillNamed() {
        val holders = BrokerService.portHolders(listOf(100L), emptyList())
        assertEquals(listOf(BrokerService.PortHolder(100, "", "", stale = false)), holders)
    }

    @Test fun freeingThePortStopsOnlyTheStaleHoldersThenSeesItFree() {
        var asked = 0
        val fake = FakeOsEnv(os = DesktopOs.WINDOWS, home = createTempDirectory(), captures = mapOf(listing to leftovers))
        val env = object : OsEnv by fake {
            override fun runCapture(argv: List<String>): String? =
                if (argv == owners) (if (asked++ == 0) "100\r\n" else "") else fake.runCapture(argv)
        }
        assertEquals(emptyList(), BrokerService.freeWindowsPort(env, 9899))
        val kills = fake.ran.filter { it.firstOrNull() == "taskkill" }
        assertEquals(listOf(listOf("taskkill", "/F", "/PID", "300", "/PID", "302", "/PID", "400")), kills)
    }

    @Test fun aPortALiveProcessHoldsIsLeftAloneAndNamed() {
        val fake = FakeOsEnv(
            os = DesktopOs.WINDOWS, home = createTempDirectory(),
            captures = mapOf(owners to "100\r\n", listing to "100\t1\tnode.exe\t\"C:\\n\\node.exe\" server.js\r\n"),
        )
        val left = BrokerService.freeWindowsPort(fake, 9899)
        assertTrue(fake.ran.none { it.firstOrNull() == "taskkill" }, "a live process is never stopped for the port")
        assertEquals(
            "Port 9899 is still held by node.exe (pid 100): \"C:\\n\\node.exe\" server.js.",
            BrokerService.describePortHolders(9899, left),
        )
    }

    @Test fun aGoneOwnerIsDescribedAsSuch() {
        assertEquals(
            "Port 9899 is still held by a process that has exited (pid 100), through a handle another process kept.",
            BrokerService.describePortHolders(9899, listOf(BrokerService.PortHolder(100, "", "", stale = false))),
        )
    }

    @Test fun elsewhereNothingRuns() {
        val fake = FakeOsEnv(os = DesktopOs.LINUX, home = createTempDirectory())
        assertEquals(emptyList(), BrokerService.freeWindowsPort(fake, 9899))
        assertTrue(fake.ran.isEmpty())
    }

    // The app starting while a gone broker's leftovers hold the port: they are stopped, then our broker starts.
    @Test fun aStartStopsTheGoneBrokersLeftoversHoldingThePort() = runTest {
        val h = Harness(
            this, os = DesktopOs.WINDOWS, failIf = { it.take(2) == listOf("schtasks", "/Query") },
            captures = mapOf(BrokerService.portOwnersArgv(9898) to "100\r\n", listing to leftovers),
        )
        val killed = { h.env.ran.any { it.firstOrNull() == "taskkill" } }
        h.probeFn = { if (h.liveChild != null) h.desktop() else if (killed()) HostProbeResult.PortFree else HostProbeResult.Busy }
        h.sup.ensure()
        assertEquals(listOf("taskkill", "/F", "/PID", "300", "/PID", "302", "/PID", "400"), h.env.ran.single { it.firstOrNull() == "taskkill" })
        assertEquals(HostingStatus.Running(9898, readOnly = false), h.sup.status.value)
    }

    // A port nothing of ours holds, and our broker can't bind it: the error names the holder and its command line.
    @Test fun theCantStartErrorNamesWhatHoldsThePort() = runTest {
        val node = "100\t1\tnode.exe\t\"C:\\n\\node.exe\" server.js\r\n"
        // No Supermux Host task registered (the fake's `schtasks /Query` succeeds by default: another copy's).
        val h = Harness(
            this, os = DesktopOs.WINDOWS, failIf = { it.take(2) == listOf("schtasks", "/Query") },
            captures = mapOf(BrokerService.portOwnersArgv(9898) to "100\r\n", listing to node),
        )
        h.probeFn = { if (h.children.isEmpty()) HostProbeResult.PortFree else HostProbeResult.Busy }
        h.sup.ensure()
        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue("Port 9898 is still held by node.exe (pid 100): \"C:\\n\\node.exe\" server.js." in s.reason, s.reason)
        assertTrue(h.env.ran.none { it.firstOrNull() == "taskkill" })
    }

    // The update stops the service; its broker is gone but its leftovers keep the port: they are stopped too.
    @Test fun anUpdatesServiceStopAlsoStopsTheLeftoversHoldingThePort() = runTest {
        val h = Harness(
            this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(background = true),
            captures = mapOf(
                BrokerService.listWindowsProcessesArgv() to "",
                BrokerService.portOwnersArgv(9898) to "100\r\n",
                listing to leftovers,
            ),
        )
        h.writeOurWindowsTask()
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        assertEquals(HostSupervisor.Mode.SERVICE, h.sup.mode)
        val killed = { h.env.ran.any { it.take(2) == listOf("taskkill", "/F") && "300" in it } }
        h.probeFn = { if (killed()) HostProbeResult.PortFree else HostProbeResult.Busy }
        assertTrue(h.sup.stopServiceForWindowsUpdate())
        assertTrue(listOf("taskkill", "/F", "/PID", "300", "/PID", "302", "/PID", "400") in h.env.ran)
    }
}
