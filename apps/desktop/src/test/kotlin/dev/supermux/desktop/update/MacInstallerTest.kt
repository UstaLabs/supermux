package dev.supermux.desktop.update

import dev.supermux.desktop.platform.DesktopOs
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #113 on macOS: "Restart to update" copies the new app out of the downloaded .dmg next to the installed one while
 * the app still runs, then quits; a small helper waits for it to exit, swaps the two bundles and opens the new one.
 * Offered only where the installed bundle's folder is writable without a password (an admin's /Applications).
 */
class MacInstallerTest {
    private val launcher = "/Applications/Supermux Desktop.app/Contents/MacOS/Supermux Desktop"

    @Test fun theBundleIsFoundFromJpackagesLauncher() {
        assertEquals(File("/Applications/Supermux Desktop.app"), DesktopUpdateSource.macBundle(launcher))
        assertNull(DesktopUpdateSource.macBundle("/usr/local/bin/supermux"), "not inside an .app: a dev run")
        assertNull(DesktopUpdateSource.macBundle(null))
    }

    @Test fun onlyAWritableInstalledBundleRestartsToUpdate() {
        assertTrue(DesktopUpdateSource.macRestarts(DesktopOs.of("Mac OS X"), launcher, writable = { true }))
        assertFalse(DesktopUpdateSource.macRestarts(DesktopOs.of("Mac OS X"), launcher, writable = { false }))
        assertFalse(DesktopUpdateSource.macRestarts(DesktopOs.of("Mac OS X"), null, writable = { true }))
        assertFalse(DesktopUpdateSource.macRestarts(DesktopOs.of("Linux"), launcher, writable = { true }))
    }

    @Test fun theNewAppIsCopiedOutOfTheDmgNextToTheOldOneThenTheHelperStarts() {
        val ran = mutableListOf<List<String>>()
        val started = mutableListOf<List<String>>()
        val dmg = File("/tmp/u/supermux-update.dmg")
        val bundle = File("/Applications/Supermux Desktop.app")
        val quits = DesktopUpdateSource.installMacDmg(
            dmg, bundle, appPid = 4242, mount = File("/tmp/u/mnt"),
            run = { ran += it; 0 }, findApp = { File(it, "Supermux Desktop.app") }, start = { started += it.argv; it },
        )
        assertTrue(quits)
        assertEquals(
            listOf(
                listOf("/usr/bin/hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", "/tmp/u/mnt", dmg.absolutePath),
                listOf("/usr/bin/ditto", "/tmp/u/mnt/Supermux Desktop.app", "/Applications/.Supermux Desktop.app.update"),
                listOf("/usr/bin/hdiutil", "detach", "/tmp/u/mnt", "-quiet"),
            ),
            ran,
        )
        assertEquals(listOf("/bin/sh", "-c", DesktopUpdateSource.MAC_SWAP_SCRIPT), started.single())
    }

    @Test fun aDmgThatWontMountLeavesTheAppRunningAndSaysSo() {
        val started = mutableListOf<List<String>>()
        val e = assertFailsWith<IllegalStateException> {
            DesktopUpdateSource.installMacDmg(
                File("/tmp/u.dmg"), File("/Applications/Supermux Desktop.app"), 4242, File("/tmp/mnt"),
                run = { if (it[1] == "attach") 1 else 0 }, findApp = { null }, start = { started += it.argv; it },
            )
        }
        assertEquals("The update couldn't be opened (hdiutil exit 1).", e.message)
        assertTrue(started.isEmpty())
    }

    // The helper for real (a POSIX sh; `open` stood in by a script): waits for the app to exit, swaps, opens the new one.
    @Test fun theSwapHelperWaitsThenSwapsTheBundlesAndOpensTheNewOne() {
        if (DesktopOs.current == DesktopOs.WINDOWS) return
        val apps = createTempDirectory().toFile()
        val bundle = File(apps, "Supermux Desktop.app").apply { mkdirs(); File(this, "version").writeText("old") }
        File(apps, ".Supermux Desktop.app.update").apply { mkdirs(); File(this, "version").writeText("new") }
        val opened = File(apps, "opened")
        val opener = File(createTempDirectory().toFile(), "open").apply {
            writeText("#!/bin/sh\necho \"$@\" > '${opened.absolutePath}'\n")
            setExecutable(true)
        }
        val running = ProcessBuilder("sleep", "1").start()
        val cmd = DesktopUpdateSource.macSwapCommand(bundle, running.pid(), opener = opener.absolutePath)
        assertFalse(cmd.argv.any { it.contains(apps.absolutePath) }, "data never becomes code")
        val helper = ProcessBuilder(cmd.argv).also { it.environment().putAll(cmd.env) }.start()
        assertTrue(helper.waitFor(10, TimeUnit.SECONDS))
        for (i in 1..50) { if (opened.exists()) break; Thread.sleep(100) }
        assertEquals("new", File(bundle, "version").readText())
        assertEquals(listOf("Supermux Desktop.app", "opened"), apps.list()!!.sorted(), "no copy left behind")
        assertEquals(bundle.absolutePath, opened.readText().trim())
    }
}
