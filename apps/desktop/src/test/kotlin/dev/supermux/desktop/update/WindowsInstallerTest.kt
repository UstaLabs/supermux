package dev.supermux.desktop.update

import dev.supermux.desktop.platform.DesktopOs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #113: the in-app update ran `msiexec /i <msi>` with its full UI, so Windows showed the setup wizard (where to install,
 * then a files-in-use question) over an upgrade that needs none of it. It now runs a hidden helper that waits for the
 * app to exit, runs msiexec with a progress bar only into the folder the app runs from, then starts the app again.
 */
class WindowsInstallerTest {
    private val msi = File("C:\\Users\\t\\Downloads\\supermux-windows.msi")
    private val app = "C:\\Program Files\\supermux\\supermux.exe"

    private fun update(app: String? = this.app, systemRoot: String? = "C:\\Windows") =
        DesktopUpdateSource.windowsUpdateCommand(DesktopOs.of("Windows 11"), msi, app, appPid = 4242, systemRoot = systemRoot)!!

    @Test fun anUpdateRunsMsiexecQuietlyIntoTheFolderTheAppRunsFrom() {
        val env = update().env
        assertEquals("C:\\Windows\\System32\\msiexec.exe", env["SUPERMUX_UPDATE_MSIEXEC"])
        assertEquals(
            "/i \"${msi.absolutePath}\" /passive /norestart INSTALLDIR=\"C:\\Program Files\\supermux\" " +
                "/l*v \"${File(msi.parentFile, "supermux-windows.log").absolutePath}\"",
            env["SUPERMUX_UPDATE_MSIEXEC_ARGS"],
        )
        assertEquals(app, env["SUPERMUX_UPDATE_APP"])
        assertEquals("4242", env["SUPERMUX_UPDATE_APP_PID"])
    }

    @Test fun theHelperIsHiddenPowerShellRunningAConstantScriptWithTheDataInItsEnvironment() {
        val argv = update().argv
        assertEquals(listOf("C:\\Windows\\System32\\conhost.exe", "--headless", "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"), argv.take(3))
        assertEquals(DesktopUpdateSource.WINDOWS_UPDATE_SCRIPT, argv.last())
        // Data never becomes code, and Java can't escape a double quote inside a Windows argument.
        assertFalse(argv.any { it.contains("supermux-windows.msi") || it.contains("Program Files") })
        assertFalse(DesktopUpdateSource.WINDOWS_UPDATE_SCRIPT.contains('"'))
    }

    @Test fun theHelperWaitsForTheAppThenInstallsThenStartsTheAppAgain() {
        val script = DesktopUpdateSource.WINDOWS_UPDATE_SCRIPT
        val wait = script.indexOf("Wait-Process")
        val install = script.indexOf("Start-Process -FilePath \$env:SUPERMUX_UPDATE_MSIEXEC")
        val relaunch = script.indexOf("Start-Process -FilePath \$env:SUPERMUX_UPDATE_APP")
        assertTrue(wait in 0 until install && install < relaunch, script)
        assertTrue(script.substring(install, relaunch).contains("-Wait"), script)
    }

    @Test fun withoutAKnownAppFolderTheInstallKeepsItsOwnFolderAndNothingIsRelaunched() {
        val env = update(app = null).env
        assertFalse(env.getValue("SUPERMUX_UPDATE_MSIEXEC_ARGS").contains("INSTALLDIR"))
        assertEquals("", env["SUPERMUX_UPDATE_APP"])
    }

    @Test fun windowsToolsAreNamedByTheirSystem32Paths() {
        val cmd = update(systemRoot = "D:\\WIN\\")
        assertEquals("D:\\WIN\\System32\\conhost.exe", cmd.argv.first())
        assertEquals("D:\\WIN\\System32\\msiexec.exe", cmd.env["SUPERMUX_UPDATE_MSIEXEC"])
        assertEquals("C:\\Windows\\System32\\conhost.exe", update(systemRoot = null).argv.first())
    }

    @Test fun elsewhereTheOsOpensTheInstaller() {
        assertNull(DesktopUpdateSource.windowsUpdateCommand(DesktopOs.of("Linux"), File("/tmp/supermux.deb"), null, 1))
        assertNull(DesktopUpdateSource.windowsUpdateCommand(DesktopOs.of("Mac OS X"), File("/tmp/supermux.dmg"), null, 1))
        assertNull(DesktopUpdateSource.windowsUpdateCommand(DesktopOs.of("Windows 11"), File("C:\\x\\notes.txt"), app, 1))
    }
}
