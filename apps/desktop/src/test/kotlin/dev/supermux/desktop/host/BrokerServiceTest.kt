package dev.supermux.desktop.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrokerServiceTest {
    private val spec = BrokerService.Spec(
        broker = Path.of("/Users/a/.mux/state/desktop-assets/bin/supermux-broker"),
        env = linkedMapOf(
            "MUX_WEB_PORT" to "9898",
            "MUX_MANAGED_BY" to "desktop",
            "MUX_HOST_NAME" to "Ahmet's Mac & Co",
            "PATH" to "/Users/a/.mux/state/desktop-assets/bin:/opt/homebrew/bin:/usr/bin:/bin",
        ),
        log = Path.of("/Users/a/.mux/state/desktop-broker.log"),
    )

    @Test fun plistRunsTheBrokerAsItsOwnArgvElement() {
        val xml = BrokerService.launchdPlist(spec)
        assertTrue("<string>/Users/a/.mux/state/desktop-assets/bin/supermux-broker</string>" in xml)
        assertFalse("supermux-broker </string>" in xml, "no trailing space in the program path")
        assertTrue("<key>MUX_MANAGED_BY</key>\n    <string>desktop</string>" in xml)
        assertTrue("Ahmet's Mac &amp; Co" in xml)
        assertTrue("<key>KeepAlive</key>\n  <true/>" in xml)
        assertTrue("<string>dev.supermux.host</string>" in xml)
    }

    @Test fun systemdUnitQuotesEnvAndExecsTheBroker() {
        val unit = BrokerService.systemdUnit(spec)
        assertTrue("ExecStart=\"/Users/a/.mux/state/desktop-assets/bin/supermux-broker\"" in unit)
        assertTrue("Environment=\"MUX_MANAGED_BY=desktop\"" in unit)
        assertTrue("Restart=always" in unit)
    }

    @Test fun macInstallWritesPlistAndBootstraps() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501)
        val r = BrokerService.install(spec, env)
        assertTrue(r is BrokerService.Result.Installed)
        assertTrue(Files.exists(home.resolve("Library/LaunchAgents/dev.supermux.host.plist")))
        assertEquals(listOf("launchctl", "bootout", "gui/501/dev.supermux.host"), env.ran[0])
        assertEquals(listOf("launchctl", "bootstrap", "gui/501", home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString()), env.ran[1])
    }

    @Test fun macRestartKickstarts() {
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = createTempDirectory(), uid = 501)
        BrokerService.restart(env)
        assertEquals(listOf("launchctl", "kickstart", "-k", "gui/501/dev.supermux.host"), env.ran.single())
    }

    @Test fun linuxInstallEnablesTheUserUnit() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        BrokerService.install(spec, env)
        assertTrue(Files.exists(home.resolve(".config/systemd/user/supermux-host.service")))
        assertTrue(listOf("systemctl", "--user", "enable", "--now", "supermux-host") in env.ran)
    }

    private val winSpec = BrokerService.Spec(
        broker = Path.of("C:\\Users\\a\\.mux\\state\\desktop-assets\\bin\\supermux-broker.exe"),
        env = linkedMapOf("MUX_HOST_NAME" to "Ahmet's & \"Win\"", "MUX_MANAGED_BY" to "desktop"),
        log = Path.of("C:\\Users\\a\\.mux\\state\\desktop-broker.log"),
    )

    @Test fun windowsTaskXmlIsPerUserLeastPrivilegeAndEscapes() {
        val xml = BrokerService.windowsTaskXml(winSpec)
        assertTrue("<LogonTrigger>" in xml)
        assertTrue("<LogonType>InteractiveToken</LogonType>" in xml)
        assertTrue("<RunLevel>LeastPrivilege</RunLevel>" in xml)
        assertTrue("<RestartOnFailure>" in xml)
        assertTrue("<Interval>PT1M</Interval>" in xml)
        assertTrue("<Count>255</Count>" in xml)
        assertTrue("<Command>powershell.exe</Command>" in xml)
        assertTrue("\$env:MUX_MANAGED_BY = 'desktop'" in xml)
        assertTrue("'C:\\Users\\a\\.mux\\state\\desktop-assets\\bin\\supermux-broker.exe'" in xml, "PowerShell-quotes the broker")
        assertTrue("*&gt;&gt; 'C:\\Users\\a\\.mux\\state\\desktop-broker.log'" in xml, "appends output to the log")
        assertTrue("Ahmet&apos;s" !in xml && "&amp;" in xml, "XML-escapes ampersands")
        assertTrue("'Ahmet''s" in xml, "doubles single quotes in PowerShell literals")
        assertTrue("\\\"Win\\\"" in xml, "escapes double quotes for the Windows command line")
        assertTrue("<WorkingDirectory>C:\\Users\\a\\.mux\\state\\desktop-assets\\bin</WorkingDirectory>" in xml)
    }

    @Test fun windowsInstallWritesXmlAndCreatesTheTaskElevated() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = home)
        val installed = assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec, env))
        val taskXml = env.localAppData.resolve("Supermux/supermux-host-task.xml")
        assertEquals(taskXml, installed.path)
        assertTrue("<LogonTrigger>" in Files.readString(taskXml, Charsets.UTF_16))
        assertTrue(env.ran.any {
            it.firstOrNull() == "powershell.exe" && it.last().contains("-Verb RunAs") &&
                it.last().contains("/Create") && it.last().contains(taskXml.toString())
        })
    }

    @Test fun windowsRemoveDeletesTheTaskAndXml() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = home)
        BrokerService.install(winSpec, env)
        val removed = assertIs<BrokerService.Result.Removed>(BrokerService.remove(env))
        assertFalse(Files.exists(removed.path!!))
        assertTrue(env.ran.any { it.firstOrNull() == "powershell.exe" && it.last().contains("/Delete") })
    }

    @Test fun removeOnMacBootsOutAndDeletes() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501)
        BrokerService.install(spec, env)
        BrokerService.remove(env)
        assertFalse(Files.exists(home.resolve("Library/LaunchAgents/dev.supermux.host.plist")))
    }
}

/** Records argv instead of running it. Shared by later tests. */
class FakeOsEnv(
    override val os: OsEnv.Os,
    override val home: Path,
    override val uid: Long? = null,
    override val xdgRuntimeDir: String? = null,
    override val localAppData: Path = home.resolve("AppData/Local"),
    private val commands: Set<String> = setOf("launchctl", "systemctl", "loginctl", "schtasks", "powershell.exe"),
    private val captures: Map<List<String>, String> = emptyMap(),
    private val failing: Set<List<String>> = emptySet(),
) : OsEnv {
    val ran = mutableListOf<List<String>>()
    override fun hasCommand(name: String) = name in commands
    override fun run(argv: List<String>): Boolean { ran += argv; return argv !in failing }
    override fun runCapture(argv: List<String>): String? { ran += argv; return captures[argv] }
}
