package dev.supermux.desktop.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        log = createTempDirectory().resolve("desktop-broker.log"),
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

    @Test fun everyDefinitionIsMarkedManagedEvenWhenTheSpecEnvLacksIt() {
        val bare = spec.copy(env = mapOf("MUX_WEB_PORT" to "9898"))
        val marker = "supermux-managed: desktop"
        val plist = BrokerService.launchdPlist(bare)
        assertTrue("<!-- $marker -->" in plist && "<key>MUX_MANAGED_BY</key>\n    <string>desktop</string>" in plist)
        val unit = BrokerService.systemdUnit(bare)
        assertTrue("Description=supermux broker ($marker)" in unit && "Environment=\"MUX_MANAGED_BY=desktop\"" in unit)
        val xdg = BrokerService.xdgAutostart(bare)
        assertTrue("# $marker" in xdg && "MUX_MANAGED_BY=desktop" in xdg)
        val task = BrokerService.windowsTaskXml(bare)
        assertTrue("<!-- $marker -->" in task && "MUX_MANAGED_BY" in task)
    }

    @Test fun specEnvCannotOverrideManagedBy() {
        val plist = BrokerService.launchdPlist(spec.copy(env = mapOf("MUX_MANAGED_BY" to "someone")))
        assertTrue("<key>MUX_MANAGED_BY</key>\n    <string>desktop</string>" in plist)
    }

    @Test fun systemdUnitQuotesEnvAndExecsTheBroker() {
        val unit = BrokerService.systemdUnit(spec)
        assertTrue("ExecStart=\"/Users/a/.mux/state/desktop-assets/bin/supermux-broker\"" in unit)
        assertTrue("Environment=\"MUX_MANAGED_BY=desktop\"" in unit)
        assertTrue("Restart=always" in unit)
    }

    private val printHost = listOf("launchctl", "print", "gui/501/dev.supermux.host")

    @Test fun macInstallWritesPlistAndBootstraps() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501, failing = setOf(printHost))
        val r = BrokerService.install(spec, env)
        assertTrue(r is BrokerService.Result.Installed)
        assertTrue(Files.exists(home.resolve("Library/LaunchAgents/dev.supermux.host.plist")))
        assertEquals(listOf("launchctl", "bootout", "gui/501/dev.supermux.host"), env.ran[0])
        assertEquals(printHost, env.ran[1]) // gone: launchd dropped the old job
        assertEquals(listOf("launchctl", "enable", "gui/501/dev.supermux.host"), env.ran[2])
        assertEquals(listOf("launchctl", "bootstrap", "gui/501", home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString()), env.ran[3])
    }

    @Test fun macInstallWaitsUntilLaunchdDropsTheOldJob() {
        val home = createTempDirectory()
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString()
        val alive = OsEnv.RunResult(0, "state = running", "")
        val gone = OsEnv.RunResult(113, "", "Could not find service")
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501, scripted = mapOf(printHost to listOf(alive, alive, alive, gone)))
        assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env))
        val bootout = env.ran.indexOf(listOf("launchctl", "bootout", "gui/501/dev.supermux.host"))
        val enable = env.ran.indexOf(listOf("launchctl", "enable", "gui/501/dev.supermux.host"))
        assertEquals(List(4) { printHost }, env.ran.subList(bootout + 1, enable))
        assertEquals(1, env.ran.count { it == listOf("launchctl", "bootstrap", "gui/501", plist) })
        assertEquals(listOf(500L, 500L, 500L), env.sleeps)
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
        assertTrue("Out-File -Append -FilePath 'C:\\Users\\a\\.mux\\state\\desktop-broker.log'" in xml, "appends output to the log")
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

    private fun parse(xml: String) {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        f.isValidating = false
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    }

    private val nasty = spec.copy(env = linkedMapOf("MUX_HOST_NAME" to "A & <b> \"q\" \u2019s"))

    @Test fun plistAndTaskXmlAreWellFormedWithNastyValues() {
        parse(BrokerService.launchdPlist(nasty))
        parse(BrokerService.windowsTaskXml(nasty))
    }

    @Test fun windowsTaskXmlHasHiddenSupervisionLoopAndCurlyQuoteDoubled() {
        val xml = BrokerService.windowsTaskXml(winSpec.copy(env = linkedMapOf("MUX_HOST_NAME" to "Ahmet\u2019s")))
        parse(xml)
        assertTrue("-WindowStyle Hidden" in xml)
        assertTrue("while (\$true)" in xml)
        assertTrue("Out-File -Append" in xml)
        assertTrue("'Ahmet\u2019\u2019s'" in xml, "curly apostrophe doubled in the PowerShell literal")
    }

    @Test fun systemdEscapesDollarInExecStartAndPercentInLog() {
        val unit = BrokerService.systemdUnit(spec.copy(broker = Path.of("/opt/a\$b/broker"), log = Path.of("/tmp/100%/x.log")))
        assertTrue("ExecStart=\"/opt/a\$\$b/broker\"" in unit)
        assertTrue("StandardOutput=append:/tmp/100%%/x.log" in unit)
        assertTrue("StandardError=append:/tmp/100%%/x.log" in unit)
    }

    @Test fun macBootstrapFailingTenTimesFails() {
        val home = createTempDirectory()
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString()
        val bs = listOf("launchctl", "bootstrap", "gui/501", plist)
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501, failing = setOf(printHost),
            scripted = mapOf(bs to List(10) { OsEnv.RunResult(5, "", " Bootstrap failed: 5 \n") }))
        val r = BrokerService.install(spec, env)
        val f = assertIs<BrokerService.Result.Failed>(r)
        assertTrue("Bootstrap failed: 5" in f.message)
        assertEquals(List(9) { 1_000L }, env.sleeps)
        assertEquals(10, env.ran.count { it == bs })
    }

    @Test fun macBootstrapFailingThreeTimesThenSucceeds() {
        val home = createTempDirectory()
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString()
        val bs = listOf("launchctl", "bootstrap", "gui/501", plist)
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501, failing = setOf(printHost),
            scripted = mapOf(bs to List(3) { OsEnv.RunResult(5, "", "Bootstrap failed: 5: Input/output error") }))
        assertTrue(assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env)).enabled)
        assertEquals(4, env.ran.count { it == bs })
        assertEquals(List(3) { 1_000L }, env.sleeps)
    }

    @Test fun macBootstrapRetriesThenSucceeds() {
        val home = createTempDirectory()
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString()
        val bs = listOf("launchctl", "bootstrap", "gui/501", plist)
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501, failing = setOf(printHost),
            scripted = mapOf(bs to listOf(OsEnv.RunResult(5, "", "busy"))))
        val r = assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env))
        assertTrue(r.enabled)
        assertEquals(1, env.sleeps.size)
    }

    @Test fun linuxInstallEnablesThenRestarts() {
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = createTempDirectory(), uid = 1000, xdgRuntimeDir = "/run/user/1000")
        BrokerService.install(spec, env)
        val enable = env.ran.indexOf(listOf("systemctl", "--user", "enable", "--now", "supermux-host"))
        val restart = env.ran.indexOf(listOf("systemctl", "--user", "restart", "supermux-host"))
        assertTrue(enable >= 0 && restart > enable)
    }

    @Test fun linuxXdgFallbackIsNotEnabledAndRemoveCleansUp() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = home, uid = 1000, xdgRuntimeDir = null)
        val r = assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env))
        assertFalse(r.enabled)
        val desktop = home.resolve(".config/autostart/supermux-host.desktop")
        assertTrue(Files.exists(desktop))
        val unit = home.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent); Files.writeString(unit, "x")
        BrokerService.remove(env)
        assertFalse(Files.exists(desktop))
        assertFalse(Files.exists(unit))
    }

    @Test fun xdgExecQuotesReservedCharacters() {
        val d = BrokerService.xdgAutostart(spec.copy(env = linkedMapOf("A" to "x\$y%z")))
        assertTrue("\"A=x\\\\\$y%%z\"" in d, d)
    }

    @Test fun windowsInstallIsOneElevatedCallWithCreateAndRun() {
        val env = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = createTempDirectory())
        BrokerService.install(winSpec, env)
        val elevated = env.ran.filter { it.firstOrNull() == "powershell.exe" && it.last().contains("-Verb RunAs") }
        assertEquals(1, elevated.size)
        assertTrue("/Create" in elevated[0].last() && "/Run" in elevated[0].last())
    }

    @Test fun windowsRemoveEndsTheTaskBeforeDeletingThenKillsTheBroker() {
        val env = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = createTempDirectory())
        BrokerService.install(winSpec, env)
        env.ran.clear()
        BrokerService.remove(env)
        val elevated = env.ran.filter { it.firstOrNull() == "powershell.exe" && it.last().contains("-Verb RunAs") }
        assertEquals(1, elevated.size)
        val script = elevated[0].last()
        assertTrue("/End" in script && "/Delete" in script && script.indexOf("/End") < script.indexOf("/Delete"), script)
        val kill = listOf("taskkill", "/F", "/IM", "supermux-broker.exe")
        assertTrue(env.ran.indexOf(kill) > env.ran.indexOf(elevated[0]))
    }

    @Test fun windowsRemoveFailsWhenTheElevatedBatchFails() {
        val home = createTempDirectory()
        val installEnv = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = home)
        BrokerService.install(winSpec, installEnv)
        val taskXml = installEnv.localAppData.resolve("Supermux/supermux-host-task.xml")
        val env = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = home,
            failIf = { it.firstOrNull() == "powershell.exe" && it.last().contains("-Verb RunAs") })
        assertIs<BrokerService.Result.Failed>(BrokerService.remove(env))
        assertTrue(env.ran.none { it.firstOrNull() == "taskkill" })
        assertTrue(Files.exists(taskXml))
    }

    @Test fun windowsRestartIsTaskkillWithoutElevation() {
        val env = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = createTempDirectory())
        assertTrue(BrokerService.restart(env))
        assertEquals(listOf(listOf("taskkill", "/F", "/IM", "supermux-broker.exe")), env.ran)
    }

    @Test fun windowsRemoveWhenNotInstalledDoesNotElevate() {
        val q = listOf("schtasks", "/Query", "/TN", "Supermux Host")
        val env = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = createTempDirectory(),
            scripted = mapOf(q to listOf(OsEnv.RunResult(1, "", "not found"))))
        val r = assertIs<BrokerService.Result.Removed>(BrokerService.remove(env))
        assertEquals(null, r.path)
        assertTrue(env.ran.none { it.firstOrNull() == "powershell.exe" })
    }

    @Test fun specRejectsLineBreaks() {
        assertFailsWith<IllegalArgumentException> { spec.copy(env = mapOf("A" to "x\ny")) }
        assertFailsWith<IllegalArgumentException> { spec.copy(env = mapOf("A\r" to "x")) }
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
    /** Per-argv scripted results, consumed in order; once empty (or absent) the default applies. */
    private val scripted: Map<List<String>, List<OsEnv.RunResult>> = emptyMap(),
    private val failIf: (List<String>) -> Boolean = { false },
) : OsEnv {
    val ran = mutableListOf<List<String>>()
    val sleeps = mutableListOf<Long>()
    private val calls = mutableMapOf<List<String>, Int>()
    override fun hasCommand(name: String) = name in commands
    override fun run(argv: List<String>): Boolean = runResult(argv).exit == 0
    override fun runResult(argv: List<String>): OsEnv.RunResult {
        ran += argv
        val n = calls.merge(argv, 1, Int::plus)!! - 1
        scripted[argv]?.getOrNull(n)?.let { return it }
        return if (argv in failing || failIf(argv)) OsEnv.RunResult(1, "", "failed") else OsEnv.RunResult(0, "", "")
    }
    override fun runCapture(argv: List<String>): String? { ran += argv; return captures[argv] }
    override fun sleep(ms: Long) { sleeps += ms }
}
