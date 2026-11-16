package dev.supermux.desktop.applets

import dev.supermux.desktop.host.OsEnv
import dev.supermux.desktop.platform.DesktopOs
import dev.supermux.net.AppletDto
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopAppletInstallerTest {
    private class FakeOs(override val os: DesktopOs, override val home: Path, private val env: Map<String, String> = emptyMap()) : OsEnv {
        override val localAppData: Path = home.resolve("AppData/Local")
        override val uid: Long? = null
        override val xdgRuntimeDir: String? = null
        val ran = mutableListOf<List<String>>()
        var answer: (List<String>) -> OsEnv.RunResult = { OsEnv.RunResult(0, "", "") }
        override fun getenv(name: String): String? = env[name]
        override fun hasCommand(name: String) = false
        override fun run(argv: List<String>) = runResult(argv).exit == 0
        override fun runResult(argv: List<String>): OsEnv.RunResult { ran += argv; return answer(argv) }
        override fun runCapture(argv: List<String>): String? = null
        override fun sleep(ms: Long) = Unit
    }

    private val applet = AppletDto(id = "1", name = "issues", title = "Issues", updatedAt = "2026-11-16T00:00:00Z")

    @Test fun linux_install_writes_a_desktop_entry_that_starts_the_app_with_the_applet() = runBlocking {
        val home = Files.createTempDirectory("applet-home")
        val sys = FakeOs(DesktopOs.LINUX, home, mapOf("XDG_DATA_HOME" to home.resolve("data").toString()))
        val i = DesktopAppletInstaller(sys, appPath = "/opt/supermux/bin/supermux")
        assertFalse(i.isInstalled("host-1", applet))
        assertNull(i.install("host-1", applet, null))
        assertTrue(i.isInstalled("host-1", applet))
        val entry = Files.list(home.resolve("data/applications")).use { it.toList() }.single()
        val text = Files.readString(entry)
        assertTrue("Name=Issues" in text, text)
        assertTrue("Exec=\"/opt/supermux/bin/supermux\" --applet=supermux://applet/host-1/issues" in text, text)
        val icon = Regex("Icon=(.*)").find(text)!!.groupValues[1]
        assertTrue(Files.size(Path.of(icon)) > 0)
    }

    @Test fun a_dev_build_says_it_cannot_install_instead_of_writing_a_broken_shortcut() = runBlocking {
        val home = Files.createTempDirectory("applet-home")
        val i = DesktopAppletInstaller(FakeOs(DesktopOs.LINUX, home), appPath = null)
        // A reason (its words come from the catalog), never null = "installed".
        assertNotNull(i.install("host-1", applet, null))
        assertFalse(i.isInstalled("host-1", applet))
    }

    @Test fun mac_install_makes_a_signed_stub_app_that_opens_the_applet_and_reports_a_signing_failure() = runBlocking {
        val home = Files.createTempDirectory("applet-home")
        val sys = FakeOs(DesktopOs.MACOS, home)
        val i = DesktopAppletInstaller(sys, appPath = null)
        assertNull(i.install("host-1", applet, null))
        val app = home.resolve("Applications/Supermux Applets/Issues.app")
        assertTrue(Files.readString(app.resolve("Contents/MacOS/applet")).contains("/usr/bin/open 'supermux://applet/host-1/issues'"))
        assertTrue(Files.readString(app.resolve("Contents/Info.plist")).contains("<string>Issues</string>"))
        assertTrue(sys.ran.any { it.first() == "/usr/bin/codesign" })
        assertTrue(i.isInstalled("host-1", applet))

        sys.answer = { argv -> if (argv.first() == "/usr/bin/codesign") OsEnv.RunResult(1, "", "no identity") else OsEnv.RunResult(0, "", "") }
        val why = i.install("host-1", applet.copy(name = "jobs", title = "Jobs"), null)
        assertNotNull(why)
        assertTrue("no identity" in why, why)
    }

    @Test fun windows_install_asks_powershell_for_a_start_menu_link_to_the_app() = runBlocking {
        val home = Files.createTempDirectory("applet-home")
        val sys = FakeOs(DesktopOs.WINDOWS, home, mapOf("APPDATA" to home.resolve("Roaming").toString()))
        val i = DesktopAppletInstaller(sys, appPath = "C:\\Program Files\\supermux\\supermux.exe")
        assertNull(i.install("host-1", applet, null))
        val script = sys.ran.single { it.first() == "powershell.exe" }.last()
        assertTrue("Supermux Applets" in script && "Issues.lnk" in script, script)
        assertTrue("--applet=supermux://applet/host-1/issues" in script, script)
        assertEquals(1, Files.list(home.resolve("AppData/Local/supermux/applet-icons")).use { it.count() })
    }
}
