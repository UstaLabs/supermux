package dev.supermux.desktop.host

import dev.supermux.desktop.platform.DesktopOs
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two copies of the app on one computer (card #77): a dev build next to the installed app, or a
 * reinstall with fresh app data. Every copy uses the same service name, and the same `~/.mux/state`,
 * so the second copy finds the first one's working background broker under "its" name. It must
 * never end, remove, replace or restart it.
 */
class HostOwnershipTest {
    /** The other copy's install id, in the definition it wrote. */
    private val other = "0123456789abcdef0123456789abcdef"

    private val elevated = { argv: List<String> -> argv.firstOrNull()?.endsWith("powershell.exe") == true && argv.last().contains("-Verb RunAs") }
    private val noProcesses = mapOf(BrokerService.listWindowsProcessesArgv() to "")

    /** What the other copy installed: the bundled broker in the shared state dir, stamped with its id. */
    private fun otherSpec(h: Harness) =
        BrokerService.Spec(h.bins.brokerPath!!, mapOf(BrokerService.INSTALL_ENV to other), h.state.resolve("desktop-broker.log"))

    /** The other copy's task loop env (the registered task itself answers `schtasks /Query`). */
    private fun otherWindowsTask(h: Harness): Path =
        BrokerService.windowsEnvPath(h.env).also { BrokerService.writePrivate(it, "\uFEFF" + BrokerService.windowsEnvFile(otherSpec(h))) }

    @Test fun windowsConnectingElsewhereNeverEndsOrDeletesAnotherInstallsTask() = runTest {
        // #29 on the Windows VM: the test build's hosting was off; its wizard's "Connect to a
        // different broker instead" asked UAC to /End + /Delete the installed app's task.
        val h = Harness(this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(hosting = false), captures = noProcesses)
        val envFile = otherWindowsTask(h)
        h.sup.setHosting(false)
        assertEquals(0, h.env.ran.count(elevated), "no UAC prompt to end and delete the other copy's task: ${h.env.ran}")
        assertTrue(h.env.ran.none { it.firstOrNull() == "taskkill" }, h.env.ran.toString())
        assertTrue(Files.exists(envFile))
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
    }

    @Test fun windowsTrayBackgroundOffNeverEndsOrDeletesAnotherInstallsTask() = runTest {
        val h = Harness(this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(background = true), captures = noProcesses)
        val envFile = otherWindowsTask(h)
        h.probeFn = { h.desktop("h-other") } // the other copy's broker answers on 9898
        h.sup.ensure()
        h.sup.setBackground(false)
        assertEquals(0, h.env.ran.count(elevated), h.env.ran.toString())
        assertTrue(h.env.ran.none { it.firstOrNull() == "taskkill" || it.take(2) == listOf("schtasks", "/Run") }, h.env.ran.toString())
        assertTrue(Files.exists(envFile))
        assertTrue(h.launches.isEmpty(), "no second broker next to it")
    }

    @Test fun macConnectingElsewhereNeverRemovesAnotherInstallsService() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        Files.createDirectories(h.ourPlist.parent)
        val theirs = BrokerService.launchdPlist(otherSpec(h)).also { Files.writeString(h.ourPlist, it) }
        h.probeFn = { h.desktop("h-other") }
        val wizard = launch { h.sup.ensure() }
        runCurrent()
        h.sup.setHosting(false) // the wizard's "Connect to a different broker instead"
        wizard.join()
        assertEquals(theirs, Files.readString(h.ourPlist))
        assertTrue(h.env.ran.none { it.getOrNull(1) in setOf("bootout", "bootstrap", "kickstart", "disable") }, h.env.ran.toString())
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
    }

    @Test fun macLaunchNeverUpdatesAnotherInstallsServiceAndAsksFirst() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        Files.createDirectories(h.ourPlist.parent)
        val theirs = BrokerService.launchdPlist(otherSpec(h)).also { Files.writeString(h.ourPlist, it) }
        h.probeFn = { h.desktop("h-other", build = "1.4.0 (old)") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(theirs, Files.readString(h.ourPlist))
        assertFalse(h.bootstrapped())
        assertFalse("materialize" in h.events, "its broker binary is not swapped either")
        assertEquals(HostingStatus.AskTakeover("h-other"), h.sup.status.value)
        h.sup.answerTakeover(false)
        job.join()
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
        assertEquals(theirs, Files.readString(h.ourPlist))
    }

    @Test fun windowsConnectingElsewhereNeverEndsOrDeletesAnOlderInstallsTask() = runTest {
        // The VM's installed 0.17.3 predates install ids: its env file names no copy at all.
        val h = Harness(this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(hosting = false), captures = noProcesses)
        val unstamped = BrokerService.Spec(h.bins.brokerPath!!, emptyMap(), h.state.resolve("desktop-broker.log"))
        val envFile = BrokerService.windowsEnvPath(h.env).also { BrokerService.writePrivate(it, "\uFEFF" + BrokerService.windowsEnvFile(unstamped)) }
        h.sup.setHosting(false)
        assertEquals(0, h.env.ran.count(elevated), h.env.ran.toString())
        assertTrue(Files.exists(envFile))
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
    }

    @Test fun macAnotherInstallsStoppedServiceIsNeitherCopiedOverNorRunBeside() = runTest {
        // Its broker isn't answering (the port is free): it may be restarting, so wait for it. This copy
        // never copies its own broker over the binary that service runs, nor runs a broker beside it.
        val h = Harness(this, prefs = HostingPrefs(background = true))
        Files.createDirectories(h.ourPlist.parent)
        val theirs = BrokerService.launchdPlist(otherSpec(h)).also { Files.writeString(h.ourPlist, it) }
        h.probeFn = { HostProbeResult.PortFree }
        h.sup.ensure()
        assertEquals(theirs, Files.readString(h.ourPlist))
        assertTrue(h.env.ran.none { it.getOrNull(1) in setOf("bootout", "bootstrap", "kickstart", "disable") }, h.env.ran.toString())
        assertFalse("materialize" in h.events, "its broker binary is not replaced")
        assertTrue(h.launches.isEmpty(), "no broker of this copy beside its service")
        val s = h.sup.status.value
        // Not "it isn't running": it may run on another port. What is true is that nothing answers on ours.
        assertTrue(s is HostingStatus.CantStart && "another copy" in s.reason && "port 9898" in s.reason, s.toString())
        h.sup.setHosting(false)
        assertEquals(theirs, Files.readString(h.ourPlist))
    }

    @Test fun macAnotherCopysServiceOnAnotherPortIsNamedWithoutWaiting() = runTest {
        // Its definition says where its broker runs: nothing will ever answer on ours, so say where it is.
        val h = Harness(this, prefs = HostingPrefs(background = true))
        Files.createDirectories(h.ourPlist.parent)
        val spec = otherSpec(h).let { it.copy(env = it.env + ("MUX_WEB_PORT" to "9899")) }
        val theirs = BrokerService.launchdPlist(spec).also { Files.writeString(h.ourPlist, it) }
        h.probeFn = { HostProbeResult.PortFree }
        h.sup.ensure()
        val s = h.sup.status.value
        assertTrue(s is HostingStatus.CantStart && "another copy" in s.reason && "port 9899" in s.reason, s.toString())
        assertTrue(h.events.none { "waiting up to" in it }, h.events.toString())
        assertEquals(theirs, Files.readString(h.ourPlist))
        assertTrue(h.launches.isEmpty() && "materialize" !in h.events)
    }

    @Test fun macTheirBrokerComingBackWhileWaitingIsAskedAbout() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        Files.createDirectories(h.ourPlist.parent)
        BrokerService.launchdPlist(otherSpec(h)).also { Files.writeString(h.ourPlist, it) }
        var probes = 0
        h.probeFn = { if (probes++ < 3) HostProbeResult.PortFree else h.desktop("h-other") } // its service restarts it
        val job = launch { h.sup.ensure() }
        advanceTimeBy(20_000)
        assertEquals(HostingStatus.AskTakeover("h-other"), h.sup.status.value)
        assertTrue(h.launches.isEmpty() && "materialize" !in h.events)
        h.sup.answerTakeover(false)
        job.join()
    }

    @Test fun aRelaunchNeverAdoptsAnotherCopysBrokerThatFinishedStarting() = runTest {
        val h = Harness(this) // child mode
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertEquals(1, h.launches.size)
        // Meanwhile another copy's service starts its broker on the same state dir and port.
        Files.createDirectories(h.ourPlist.parent)
        Files.writeString(h.ourPlist, BrokerService.launchdPlist(otherSpec(h)))
        val theirBroker = FakeChild(999, startMillis = 0)
        Files.writeString(h.state.resolve("broker.pid"), "999")
        h.table.procs[999] = ProcInfo(0, h.bins.brokerPath.toString()) to theirBroker
        var n = 0
        h.probeFn = { if (n++ == 0) HostProbeResult.PortFree else h.desktop("h-other") }
        h.sup.setRelay(false) // relaunches our child: launchChildLocked meets their booting broker
        assertTrue(h.sup.mode != HostSupervisor.Mode.ORPHAN, "not adopted as ours")
        h.sup.setBackground(false) // in orphan mode this would stop the broker by its pid
        assertEquals(0, theirBroker.destroyed)
        assertEquals(1, h.launches.size, "no second broker next to it")
    }

    @Test fun windowsAnotherCopysNewerBrokerIsUsedReadOnlyWithoutAQuestion() = runTest {
        // "Use this app's version" could only fail on Windows (no takeover there): don't ask.
        val h = Harness(this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(background = true), captures = noProcesses)
        otherWindowsTask(h)
        h.probeFn = { h.desktop("h-other", build = "9.0.0 (new)") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
        job.join()
    }

    @Test fun linuxHostingOffNeverRemovesAnotherInstallsXdgAutostart() = runTest {
        val h = Harness(this, os = DesktopOs.LINUX, prefs = HostingPrefs(background = true), commands = emptySet())
        val entry = h.home.resolve(".config/autostart/supermux-host.desktop")
        val envFile = h.home.resolve(".config/supermux/broker.env")
        BrokerService.writePrivate(envFile, BrokerService.xdgEnvFile(otherSpec(h)))
        val theirs = BrokerService.xdgAutostart(otherSpec(h), envFile).also { BrokerService.writePrivate(entry, it) }
        h.probeFn = { h.desktop("h-other") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        h.sup.setHosting(false)
        job.join()
        assertEquals(theirs, Files.readString(entry))
        assertTrue(Files.exists(envFile))
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
    }

    @Test fun macManageItTakesOverAnotherInstallsServiceWithABackup() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true), failing = setOf(listOf("launchctl", "print", "gui/501/dev.supermux.host")))
        Files.createDirectories(h.ourPlist.parent)
        val theirs = BrokerService.launchdPlist(otherSpec(h)).also { Files.writeString(h.ourPlist, it) }
        h.probeFn = { h.desktop("h-other") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(HostingStatus.AskTakeover("h-other"), h.sup.status.value)
        h.sup.answerTakeover(true)
        job.join()
        val backups = Files.list(h.state.resolve("takeover-backup")).use { s -> s.toList() }
        assertEquals(listOf(theirs), backups.filter { it.fileName.toString().startsWith("dev.supermux.host.plist.") }.map { Files.readString(it) })
        val ours = Files.readString(h.ourPlist)
        assertTrue(other !in ours && OUR_ID in ours, ours)
        assertTrue(h.bootstrapped())
        assertEquals(HostingStatus.Running(9898, readOnly = false), h.sup.status.value)
    }

    @Test fun linuxLaunchWithBackgroundOffNeverRemovesAnotherInstallsUnit() = runTest {
        val h = Harness(this, os = DesktopOs.LINUX, prefs = HostingPrefs(background = false), xdg = "/run/user/501")
        val unit = h.home.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent)
        val theirs = BrokerService.systemdUnit(otherSpec(h)).also { Files.writeString(unit, it) }
        h.probeFn = { h.desktop("h-other") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        h.sup.setHosting(false)
        job.join()
        assertEquals(theirs, Files.readString(unit))
        assertTrue(h.env.ran.none { it.getOrNull(2) in setOf("disable", "restart", "stop") }, h.env.ran.toString())
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
    }
    // ── the first launch since install ids ──

    /** What an earlier version of the app wrote: marked, no install id, the bundled broker in [h]'s state dir. */
    private fun unstampedPlist(h: Harness, state: Path = h.state): String =
        BrokerService.launchdPlist(BrokerService.Spec(state.resolve("desktop-assets/bin/supermux-broker"), emptyMap(), state.resolve("desktop-broker.log")))
            .also { Files.createDirectories(h.ourPlist.parent); Files.writeString(h.ourPlist, it) }

    /** The app's data from a version before install ids: its paired-hosts store is there (whatever it holds). */
    private fun Harness.earlierAppData() = Files.writeString(appData.resolve("hosts.json"), "[]")

    @Test fun theFirstIdAwareLaunchKeepsManagingTheServiceThisAppSetUp() = runTest {
        // An update from a version without install ids: this app's data predates them.
        val h = Harness(this, prefs = HostingPrefs(background = true), ourId = null)
        h.earlierAppData()
        unstampedPlist(h)
        h.probeFn = { if (listOf("launchctl", "bootout", "gui/501/dev.supermux.host") in h.env.ran) HostProbeResult.PortFree else h.desktop() }
        h.sup.ensure()
        assertEquals(HostSupervisor.Mode.SERVICE, h.sup.mode)
        // Adopted for good: the definition carries this app's id now, so no other copy can adopt it too.
        // Stamped in place: the running job was neither booted out nor bootstrapped for it.
        val id = h.identity.load()!!
        assertTrue(id.id in Files.readString(h.ourPlist), Files.readString(h.ourPlist))
        assertTrue(h.env.ran.none { it.getOrNull(1) in setOf("bootout", "bootstrap", "kickstart") }, h.env.ran.toString())
        h.sup.setHosting(false)
        assertFalse(Files.exists(h.ourPlist), "it is this app's, so hosting off removes it")
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
    }

    @Test fun aDevBuildNeverAdoptsAnOlderService() = runTest {
        // An unpackaged run has no broker of its own to run as a service, so it never wrote one.
        val h = Harness(this, prefs = HostingPrefs(background = true), ourId = null)
        h.packaged = false
        h.earlierAppData()
        val theirs = unstampedPlist(h)
        h.probeFn = { h.desktop("h-other") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertFalse(h.identity.load()!!.ownsUnstamped)
        assertEquals(HostingStatus.AskTakeover("h-other"), h.sup.status.value)
        h.sup.setHosting(false)
        job.join()
        assertEquals(theirs, Files.readString(h.ourPlist))
    }

    @Test fun linuxAdoptionStampsTheUnitInPlaceWithoutRestartingIt() = runTest {
        val h = Harness(this, os = DesktopOs.LINUX, prefs = HostingPrefs(background = true), xdg = "/run/user/501", ourId = null)
        h.earlierAppData()
        val unit = h.home.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent)
        Files.writeString(unit, BrokerService.systemdUnit(BrokerService.Spec(h.bins.brokerPath!!, emptyMap(), h.state.resolve("desktop-broker.log"))))
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        assertEquals(HostSupervisor.Mode.SERVICE, h.sup.mode)
        assertTrue(h.identity.load()!!.id in Files.readString(unit), Files.readString(unit))
        assertTrue(h.env.ran.none { it.getOrNull(2) in setOf("restart", "stop", "disable") || "--now" in it }, h.env.ran.toString())
    }

    @Test fun windowsAdoptionStampsTheTaskEnvWithoutAPromptOrARestart() = runTest {
        val home = kotlin.io.path.createTempDirectory("sup-home")
        val state = kotlin.io.path.createTempDirectory("sup-state")
        val envFile = home.resolve("AppData/Local").resolve(BrokerService.WINDOWS_ENV_FILE)
        val spec = BrokerService.Spec(state.resolve("desktop-assets/bin/supermux-broker"), emptyMap(), state.resolve("desktop-broker.log"))
        // What 0.17.x registered: a task running this state dir's broker, its env without an install id.
        val h = Harness(
            this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(background = true), ourId = null, home = home, state = state,
            captures = noProcesses + (listOf("schtasks", "/Query", "/TN", "Supermux Host", "/XML") to BrokerService.windowsTaskXml(spec, envFile)),
        )
        h.earlierAppData()
        BrokerService.writePrivate(envFile, "\uFEFF" + BrokerService.windowsEnvFile(spec))
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        assertTrue(h.identity.load()!!.id in Files.readString(envFile), Files.readString(envFile))
        assertEquals(0, h.env.ran.count(elevated), h.env.ran.toString())
        assertTrue(h.env.ran.none { it.firstOrNull() == "taskkill" || it.take(2) == listOf("schtasks", "/Run") }, h.env.ran.toString())
    }

    // ── this copy's install id ──

    @Test fun anUnreadableInstallIdIsNeverReplacedAndNothingIsTouched() = runTest {
        // Our own service carries our id; install.json can't be parsed (a broken write, a bad disk).
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        Files.writeString(h.appData.resolve("install.json"), "{not json")
        h.probeFn = { h.desktop() }
        val job = launch { h.sup.ensure() }
        advanceTimeBy(60_000)
        h.sup.setHosting(false)
        job.join()
        assertEquals("{not json", Files.readString(h.appData.resolve("install.json")), "never overwritten with a new id")
        assertTrue(Files.exists(h.ourPlist), "and nothing of the service is touched meanwhile")
        assertTrue(h.env.ran.none { it.getOrNull(1) in setOf("bootout", "bootstrap", "kickstart", "disable") }, h.env.ran.toString())
    }

    @Test fun anInstallIdThatCannotBeSavedSetsUpNoService() = runTest {
        assumePosixHost()
        val h = Harness(this, prefs = HostingPrefs(background = true), ourId = null)
        h.appData.toFile().setWritable(false) // install.json can't be written
        try {
            h.probeFn = h.healthyIfChild()
            h.sup.ensure()
        } finally {
            h.appData.toFile().setWritable(true)
        }
        assertFalse(Files.exists(h.ourPlist), "no definition stamped with an id nobody will know at the next launch")
        assertEquals(1, h.launches.size, "it runs as the app's child instead")
        assertEquals(HostingStatus.Running(9898, readOnly = false), h.sup.status.value)
    }

    @Test fun anUnreadableInstallIdNeverTakesOver() = runTest {
        // Taking over would stop that service, then fail to set up one carrying an id nobody keeps.
        val h = Harness(this, prefs = HostingPrefs(background = true))
        Files.createDirectories(h.ourPlist.parent)
        val theirs = BrokerService.launchdPlist(otherSpec(h)).also { Files.writeString(h.ourPlist, it) }
        Files.writeString(h.appData.resolve("install.json"), "{not json")
        h.probeFn = { h.desktop("h-other") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(HostingStatus.AskTakeover("h-other"), h.sup.status.value)
        h.sup.answerTakeover(true)
        job.join()
        assertEquals(theirs, Files.readString(h.ourPlist))
        assertTrue(h.env.ran.none { it.getOrNull(1) in setOf("bootout", "bootstrap", "kickstart", "disable") }, h.env.ran.toString())
        val s = h.sup.status.value
        assertTrue(s is HostingStatus.CantStart && "install id" in s.reason, s.toString())
    }

    @Test fun twoFirstLaunchesEndUpWithOneId() {
        val dir = kotlin.io.path.createTempDirectory("id")
        val a = InstallIdentity(dir.resolve("install.json"))
        val first = a.create(BrokerService.Owner("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        val second = InstallIdentity(dir.resolve("install.json")).create(BrokerService.Owner("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        assertEquals(first, second, "the id saved first wins")
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", a.load()!!.id)
    }

    // An update from 0.17.x where the user later unpaired "This computer": the service it set up is
    // still its own to restart and update.

    @Test fun macAnUpdateKeepsUpdatingTheServiceEvenWithThisComputerUnpaired() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true), ourId = null)
        h.earlierAppData()
        unstampedPlist(h)
        h.probeFn = { if (h.bootstrapped()) h.desktop() else h.desktop(build = "1.4.0 (old)") }
        val job = launch { h.sup.ensure() }
        advanceTimeBy(60_000)
        assertTrue(h.bootstrapped(), "updated to the bundled build, like before install ids")
        val id = h.identity.load()!!.id
        assertTrue(id in Files.readString(h.ourPlist), "and now it carries this app's id")
        assertEquals(HostingStatus.Running(9898, readOnly = false), h.sup.status.value)
        job.join()
    }

    @Test fun windowsAnUpdateKeepsUpdatingTheTaskWithoutAUacPrompt() = runTest {
        val home = kotlin.io.path.createTempDirectory("sup-home")
        val state = kotlin.io.path.createTempDirectory("sup-state")
        val envFile = home.resolve("AppData/Local").resolve(BrokerService.WINDOWS_ENV_FILE)
        // The registered task runs exactly what this version would register: no change, no prompt.
        val registered = BrokerService.windowsTaskXml(
            BrokerService.Spec(state.resolve("desktop-assets/bin/supermux-broker"), emptyMap(), state.resolve("desktop-broker.log")), envFile,
        )
        val h = Harness(
            this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(background = true), ourId = null, home = home, state = state,
            captures = noProcesses + (listOf("schtasks", "/Query", "/TN", "Supermux Host", "/XML") to registered),
        )
        h.earlierAppData()
        val unstamped = BrokerService.Spec(h.bins.brokerPath!!, emptyMap(), h.state.resolve("desktop-broker.log"))
        BrokerService.writePrivate(envFile, "\uFEFF" + BrokerService.windowsEnvFile(unstamped))
        h.probeFn = { if (listOf("schtasks", "/Run", "/TN", "Supermux Host") in h.env.ran) h.desktop() else h.desktop(build = "1.4.0 (old)") }
        val job = launch { h.sup.ensure() }
        advanceTimeBy(60_000)
        assertEquals(0, h.env.ran.count(elevated), h.env.ran.toString())
        assertTrue(listOf("schtasks", "/Run", "/TN", "Supermux Host") in h.env.ran, "restarted on the new build")
        assertTrue(h.identity.load()!!.id in Files.readString(envFile), "and its env now names this app")
        assertEquals(HostingStatus.Running(9898, readOnly = false), h.sup.status.value)
        job.join()
    }

    @Test fun linuxAnUpdateKeepsUpdatingTheUnit() = runTest {
        val h = Harness(this, os = DesktopOs.LINUX, prefs = HostingPrefs(background = true), xdg = "/run/user/501", ourId = null)
        h.earlierAppData()
        val unit = h.home.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent)
        Files.writeString(unit, BrokerService.systemdUnit(BrokerService.Spec(h.bins.brokerPath!!, emptyMap(), h.state.resolve("desktop-broker.log"))))
        val restart = listOf("systemctl", "--user", "restart", "supermux-host")
        h.probeFn = { if (restart in h.env.ran) h.desktop() else h.desktop(build = "1.4.0 (old)") }
        val job = launch { h.sup.ensure() }
        advanceTimeBy(60_000)
        assertTrue(restart in h.env.ran, "restarted on the new build")
        assertTrue(h.identity.load()!!.id in Files.readString(unit))
        assertEquals(HostingStatus.Running(9898, readOnly = false), h.sup.status.value)
        job.join()
    }

    @Test fun aCopyWithFreshAppDataNeverAdoptsAnOlderCopysService() = runTest {
        // A reinstall with fresh app data, or a dev build with its own: no data from before install ids.
        val h = Harness(this, prefs = HostingPrefs(background = true), ourId = null)
        val theirs = unstampedPlist(h)
        h.probeFn = { h.desktop("h-other") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(HostingStatus.AskTakeover("h-other"), h.sup.status.value)
        assertFalse(h.identity.load()!!.ownsUnstamped)
        h.sup.setHosting(false)
        job.join()
        assertEquals(theirs, Files.readString(h.ourPlist))
    }

    @Test fun windowsAFirstRunThatQuitBeforePairingKeepsItsTaskRunning() = runTest {
        // 0.17.x installed the task at its first launch, and the app quit before its wizard paired
        // "This computer" (it does that by itself within seconds): no hosts.json. That is a second copy's
        // situation too, so not adopted: read-only, and the task and its broker stay up.
        val h = Harness(this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(background = true), ourId = null, captures = noProcesses)
        val unstamped = BrokerService.Spec(h.bins.brokerPath!!, emptyMap(), h.state.resolve("desktop-broker.log"))
        val envFile = BrokerService.windowsEnvPath(h.env).also { BrokerService.writePrivate(it, "\uFEFF" + BrokerService.windowsEnvFile(unstamped)) }
        h.probeFn = { h.desktop(build = "1.4.0 (old)") }
        h.sup.ensure()
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
        h.sup.setHosting(false)
        assertEquals(0, h.env.ran.count(elevated), h.env.ran.toString())
        assertTrue(h.env.ran.none { it.firstOrNull() == "taskkill" || it.take(2) == listOf("schtasks", "/Run") }, h.env.ran.toString())
        assertTrue(Files.exists(envFile))
    }

    @Test fun anOlderServiceOnAnotherStateDirIsNotAdopted() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true), ourId = null)
        h.earlierAppData()
        val theirs = unstampedPlist(h, state = h.home.resolve("elsewhere/state"))
        h.sup.setHosting(false)
        assertFalse(h.identity.load()!!.ownsUnstamped)
        assertEquals(theirs, Files.readString(h.ourPlist))
    }

    // ── the broker child, on a state dir both copies share ──

    @Test fun anotherCopysChildIsNeitherReparentedNorStopped() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("desktop-broker.pid"), "777:1000:$other")
        val theirChild = FakeChild(777, startMillis = 1_000)
        h.table.procs[777] = ProcInfo(1_000, h.bins.brokerPath.toString()) to theirChild
        h.probeFn = { h.desktop("h-other") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(HostingStatus.AskTakeover("h-other"), h.sup.status.value)
        h.sup.answerTakeover(false)
        job.join()
        h.sup.setHosting(false)
        h.sup.quit()
        assertEquals(0, theirChild.destroyed)
    }

    @Test fun ourChildFromBeforeInstallIdsIsStillReparented() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("desktop-broker.pid"), "777:1000")
        val ours = FakeChild(777, startMillis = 1_000)
        h.table.procs[777] = ProcInfo(1_000, h.bins.brokerPath.toString()) to ours
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        assertEquals(HostSupervisor.Mode.CHILD, h.sup.mode)
        h.sup.quit()
        assertEquals(1, ours.destroyed)
    }

    @Test fun windowsAnotherCopysBrokerIsUsedReadOnlyWithoutAQuestion() = runTest {
        val h = Harness(this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(background = true), captures = noProcesses)
        otherWindowsTask(h)
        h.probeFn = { h.desktop("h-other", build = "1.4.0 (old)") }
        h.sup.ensure()
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
        assertTrue(h.env.ran.none { it.firstOrNull() == "taskkill" || it.take(2) == listOf("schtasks", "/Run") || elevated(it) }, h.env.ran.toString())
        assertFalse("materialize" in h.events, "its broker binary is not swapped")
    }
    @Test fun thePidFileNamesTheCopyThatStartedTheChild() {
        val f = ChildPidFile(kotlin.io.path.createTempDirectory("pid").resolve("desktop-broker.pid"))
        f.write(777, 1_000, OUR_ID)
        assertEquals(ChildPidFile.Record(777, 1_000, OUR_ID), f.read())
        f.write(777, null, OUR_ID)
        assertEquals(ChildPidFile.Record(777, null, OUR_ID), f.read())
        f.write(777, 1_000)
        assertEquals(ChildPidFile.Record(777, 1_000, null), f.read(), "a record from before install ids")
    }
}
