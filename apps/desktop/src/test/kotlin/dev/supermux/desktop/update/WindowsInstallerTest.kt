package dev.supermux.desktop.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsInstallerTest {
    @Test fun aWindowsMsiRunsMsiexecSoTheAppCanQuitForTheUpgrade() {
        val msi = File("C:\\Users\\t\\Downloads\\supermux-windows.msi")
        assertEquals(
            listOf("C:\\Windows\\System32\\msiexec.exe", "/i", msi.absolutePath),
            DesktopUpdateSource.windowsInstallerArgv("Windows 11", msi, systemRoot = "C:\\Windows"),
        )
    }

    @Test fun msiexecIsNamedByItsSystem32Path() {
        val msi = File("C:\\x\\supermux.msi")
        assertEquals("D:\\WIN\\System32\\msiexec.exe", DesktopUpdateSource.windowsInstallerArgv("Windows 11", msi, systemRoot = "D:\\WIN\\")!!.first())
        assertEquals("C:\\Windows\\System32\\msiexec.exe", DesktopUpdateSource.windowsInstallerArgv("Windows 11", msi, systemRoot = null)!!.first())
    }

    @Test fun elsewhereTheOsOpensTheInstaller() {
        assertNull(DesktopUpdateSource.windowsInstallerArgv("Linux", File("/tmp/supermux.deb")))
        assertNull(DesktopUpdateSource.windowsInstallerArgv("Mac OS X", File("/tmp/supermux.dmg")))
        assertNull(DesktopUpdateSource.windowsInstallerArgv("Windows 11", File("C:\\x\\notes.txt")))
    }
}
