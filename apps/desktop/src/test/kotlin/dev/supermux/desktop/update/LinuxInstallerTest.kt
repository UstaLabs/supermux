package dev.supermux.desktop.update

import dev.supermux.desktop.platform.DesktopOs
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #113 on Linux: "Restart to update" installs the downloaded .deb with one password prompt (pkexec dpkg) while the app
 * still runs, and only when that worked quits and lets a small helper start the app again. A cancelled prompt leaves
 * the app running and says so.
 */
class LinuxInstallerTest {
    private val deb = File(createTempDirectory().toFile(), "supermux-update.deb")
    private val app = "/opt/supermux/bin/supermux"

    @Test fun theDebInstallsThroughPkexecDpkg() {
        assertEquals(listOf("/usr/bin/pkexec", "/usr/bin/dpkg", "-i", deb.absolutePath), DesktopUpdateSource.linuxInstallArgv(deb))
    }

    @Test fun onlyAPackagedLinuxAppWithPkexecRestartsToUpdate() {
        assertTrue(DesktopUpdateSource.linuxRestarts(DesktopOs.of("Linux"), app, pkexecPresent = true))
        assertFalse(DesktopUpdateSource.linuxRestarts(DesktopOs.of("Linux"), app, pkexecPresent = false))
        assertFalse(DesktopUpdateSource.linuxRestarts(DesktopOs.of("Linux"), null, pkexecPresent = true), "a dev run has no app to start again")
        assertFalse(DesktopUpdateSource.linuxRestarts(DesktopOs.of("Mac OS X"), app, pkexecPresent = true))
    }

    @Test fun aConfirmedInstallStartsTheRelaunchHelperAndQuits() {
        val ran = mutableListOf<List<String>>()
        val started = mutableListOf<List<String>>()
        val quits = DesktopUpdateSource.installLinuxDeb(deb, app, appPid = 4242, run = { ran += it; 0 }, start = { started += it.argv; it })
        assertTrue(quits)
        assertEquals(listOf(DesktopUpdateSource.linuxInstallArgv(deb)), ran)
        assertEquals(listOf("/bin/sh", "-c", DesktopUpdateSource.LINUX_RELAUNCH_SCRIPT), started.single())
    }

    @Test fun aCancelledPasswordPromptLeavesTheAppRunningAndSaysSo() {
        val started = mutableListOf<List<String>>()
        val e = assertFailsWith<IllegalStateException> {
            DesktopUpdateSource.installLinuxDeb(deb, app, appPid = 4242, run = { 126 }, start = { started += it.argv; it })
        }
        assertEquals("The update was cancelled, so supermux wasn't updated.", e.message)
        assertTrue(started.isEmpty())
    }

    @Test fun aFailedDpkgSaysSo() {
        val e = assertFailsWith<IllegalStateException> {
            DesktopUpdateSource.installLinuxDeb(deb, app, appPid = 4242, run = { 1 }, start = { it })
        }
        assertEquals("The update couldn't be installed (dpkg exit 1).", e.message)
    }

    // The helper for real: it waits for the app's process to end, then starts the app again. Data only in its env.
    @Test fun theRelaunchHelperWaitsForTheAppToExitThenStartsIt() {
        if (DesktopOs.current == DesktopOs.WINDOWS) return // a POSIX shell script: Linux's helper, run where there is /bin/sh
        val dir = createTempDirectory().toFile()
        val marker = File(dir, "started")
        val fakeApp = File(dir, "supermux").apply {
            writeText("#!/bin/sh\necho started > '${marker.absolutePath}'\n")
            setExecutable(true)
        }
        val running = ProcessBuilder("sleep", "1").start()
        val cmd = DesktopUpdateSource.linuxRelaunchCommand(fakeApp.absolutePath, running.pid())
        assertFalse(cmd.argv.any { it.contains(fakeApp.absolutePath) }, "data never becomes code")
        val helper = ProcessBuilder(cmd.argv).also { it.environment().putAll(cmd.env) }.start()
        assertFalse(marker.exists(), "not while the app runs")
        assertTrue(helper.waitFor(10, TimeUnit.SECONDS))
        for (i in 1..50) { if (marker.exists()) break; Thread.sleep(100) }
        assertTrue(marker.exists(), "the app started again")
        assertTrue(!running.isAlive)
    }
}
