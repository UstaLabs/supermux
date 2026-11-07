package dev.supermux.desktop.host

import dev.supermux.desktop.platform.DesktopOs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
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
            "MUX_HOST_NAME" to "Alex's Mac & Co",
            "PATH" to "/Users/a/.mux/state/desktop-assets/bin:/opt/homebrew/bin:/usr/bin:/bin",
        ),
        log = createTempDirectory().resolve("desktop-broker.log"),
    )

    /** The copy of the app these tests act as: every unstamped definition they set up is its own. */
    private val me = BrokerService.Owner("1234abcd1234abcd1234abcd1234abcd", ownsUnstamped = true)

    @Test fun plistRunsTheBrokerAsItsOwnArgvElement() {
        assumePosixHost()
        val xml = BrokerService.launchdPlist(spec)
        assertTrue("<string>/Users/a/.mux/state/desktop-assets/bin/supermux-broker</string>" in xml)
        assertFalse("supermux-broker </string>" in xml, "no trailing space in the program path")
        assertTrue("<key>MUX_MANAGED_BY</key>\n    <string>desktop</string>" in xml)
        assertTrue("Alex's Mac &amp; Co" in xml)
        assertTrue("<key>KeepAlive</key>\n  <true/>" in xml)
        assertTrue("<string>dev.supermux.host</string>" in xml)
    }

    @Test fun plistIsAttributedToTheAppInBackgroundActivity() {
        // Without it, Login Items ▸ App Background Activity lists the bare broker under the signing
        // certificate's name (a person's name) with a blank icon.
        val xml = BrokerService.launchdPlist(spec)
        assertTrue("<key>AssociatedBundleIdentifiers</key>\n  <array>\n    <string>dev.supermux.desktop</string>\n  </array>" in xml)
        val doc = parse(xml)
        val keys = doc.getElementsByTagName("key")
        val k = (0 until keys.length).map { keys.item(it) }.single { it.textContent == "AssociatedBundleIdentifiers" }
        var v = k.nextSibling
        while (v != null && v !is org.w3c.dom.Element) v = v.nextSibling
        assertEquals("array", (v as org.w3c.dom.Element).tagName)
        // Takeover/our own parser still reads the broker argv, not the bundle id array.
        assertEquals(listOf(spec.broker.toString()), Takeover.parsePlist(xml)?.args)
    }

    @Test fun everyDefinitionIsMarkedManagedEvenWhenTheSpecEnvLacksIt() {
        val bare = spec.copy(env = mapOf("MUX_WEB_PORT" to "9898"))
        val marker = "supermux-managed: desktop"
        val plist = BrokerService.launchdPlist(bare)
        assertTrue("<!-- $marker -->" in plist && "<key>MUX_MANAGED_BY</key>\n    <string>desktop</string>" in plist)
        val unit = BrokerService.systemdUnit(bare)
        assertTrue("Description=supermux broker ($marker)" in unit && "Environment=\"MUX_MANAGED_BY=desktop\"" in unit)
        val xdg = BrokerService.xdgAutostart(bare, Path.of("/h/.config/supermux/broker.env"))
        assertTrue("# $marker" in xdg && "export MUX_MANAGED_BY='desktop'" in BrokerService.xdgEnvFile(bare))
        val task = BrokerService.windowsTaskXml(bare, winEnvFile)
        assertTrue("<!-- $marker -->" in task && "\$env:MUX_MANAGED_BY = 'desktop'\r\n" in BrokerService.windowsEnvFile(bare))
    }

    @Test fun specEnvCannotOverrideManagedBy() {
        val plist = BrokerService.launchdPlist(spec.copy(env = mapOf("MUX_MANAGED_BY" to "someone")))
        assertTrue("<key>MUX_MANAGED_BY</key>\n    <string>desktop</string>" in plist)
    }

    @Test fun systemdUnitQuotesEnvAndExecsTheBroker() {
        assumePosixHost()
        val unit = BrokerService.systemdUnit(spec)
        assertTrue("ExecStart=\"/Users/a/.mux/state/desktop-assets/bin/supermux-broker\"" in unit)
        assertTrue("Environment=\"MUX_MANAGED_BY=desktop\"" in unit)
        assertTrue("Restart=always" in unit)
    }

    private val printHost = listOf("launchctl", "print", "gui/501/dev.supermux.host")

    private fun mode(p: Path) = java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

    @Test fun macInstallWritesPlistAndBootstraps() {
        assumePosixHost()
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501, failing = setOf(printHost))
        val r = BrokerService.install(spec, env, me)
        assertTrue(r is BrokerService.Result.Installed)
        assertTrue(Files.exists(home.resolve("Library/LaunchAgents/dev.supermux.host.plist")))
        assertEquals("rw-------", mode(home.resolve("Library/LaunchAgents/dev.supermux.host.plist")), "the env may hold tokens")
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
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501, scripted = mapOf(printHost to listOf(alive, alive, alive, gone)))
        assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env, me))
        val bootout = env.ran.indexOf(listOf("launchctl", "bootout", "gui/501/dev.supermux.host"))
        val enable = env.ran.indexOf(listOf("launchctl", "enable", "gui/501/dev.supermux.host"))
        assertEquals(List(4) { printHost }, env.ran.subList(bootout + 1, enable))
        assertEquals(1, env.ran.count { it == listOf("launchctl", "bootstrap", "gui/501", plist) })
        assertEquals(listOf(500L, 500L, 500L), env.sleeps)
    }

    @Test fun macRestartKickstarts() {
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = createTempDirectory(), uid = 501)
        val plist = env.home.resolve("Library/LaunchAgents/dev.supermux.host.plist")
        Files.createDirectories(plist.parent); Files.writeString(plist, BrokerService.launchdPlist(spec))
        BrokerService.restart(env, me)
        assertEquals(listOf("launchctl", "kickstart", "-k", "gui/501/dev.supermux.host"), env.ran.single())
    }

    @Test fun linuxInstallEnablesTheUserUnit() {
        assumePosixHost()
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        BrokerService.install(spec, env, me)
        assertTrue(Files.exists(home.resolve(".config/systemd/user/supermux-host.service")))
        assertEquals("rw-------", mode(home.resolve(".config/systemd/user/supermux-host.service")), "the env may hold tokens")
        assertTrue(listOf("systemctl", "--user", "enable", "--now", "supermux-host") in env.ran)
    }

    private val winSpec = BrokerService.Spec(
        broker = Path.of("C:\\Users\\a\\.mux\\state\\desktop-assets\\bin\\supermux-broker.exe"),
        env = linkedMapOf("MUX_HOST_NAME" to "Alex's & \"Win\"", "MUX_MANAGED_BY" to "desktop", "MUX_TELEGRAM_BOT_TOKEN" to "123:secret"),
        log = Path.of("C:\\Users\\a\\.mux\\state\\desktop-broker.log"),
    )
    private val winEnvFile = Path.of("C:\\Users\\O'Neil & Co\\AppData\\Local\\Supermux\\broker-env.ps1")
    /** The task XML a [winEnv] over [home] registers. */
    private fun winXml(home: Path, s: BrokerService.Spec = winSpec) =
        BrokerService.windowsTaskXml(s, home.resolve("AppData/Local").resolve(BrokerService.WINDOWS_ENV_FILE))

    @Test fun windowsTaskXmlIsPerUserLeastPrivilegeAndEscapes() {
        val xml = BrokerService.windowsTaskXml(winSpec, winEnvFile)
        assertTrue("<LogonTrigger>" in xml)
        assertTrue("<LogonType>InteractiveToken</LogonType>" in xml)
        assertTrue("<RunLevel>LeastPrivilege</RunLevel>" in xml)
        assertTrue("<RestartOnFailure>" in xml)
        assertTrue("<Interval>PT1M</Interval>" in xml)
        assertTrue("<Count>255</Count>" in xml)
        assertTrue("<Command>conhost.exe</Command>" in xml, "a headless conhost: no Windows Terminal window")
        assertTrue("<Arguments>--headless powershell.exe -NoLogo" in xml)
        assertTrue("\$env:MUX_WINDOWS_TASK = '1'" in xml, "the broker knows it runs under the loop")
        assertTrue("'C:\\Users\\a\\.mux\\state\\desktop-assets\\bin\\supermux-broker.exe'" in xml, "PowerShell-quotes the broker")
        assertTrue("Out-File -Append -FilePath 'C:\\Users\\a\\.mux\\state\\desktop-broker.log'" in xml, "appends output to the log")
        assertTrue("if (Test-Path -LiteralPath 'C:\\Users\\O''Neil &amp; Co\\AppData\\Local\\Supermux\\broker-env.ps1') { . 'C:\\Users\\O''Neil &amp; Co\\AppData\\Local\\Supermux\\broker-env.ps1' }" in xml,
            "dot-sources the env file (PowerShell-quoted, XML-escaped) before every start")
        assertTrue("IndexOf" !in xml && "Get-Content" !in xml, "Windows refuses to launch a task command that parses a KEY=value file")
        assertTrue("O&apos;Neil" !in xml && "&amp;" in xml, "XML-escapes ampersands")
        for (v in listOf("123:secret", "Alex", "MUX_TELEGRAM_BOT_TOKEN", "MUX_HOST_NAME")) assertTrue(v !in xml, "no env in the definition: $v")
        assertTrue("<WorkingDirectory>C:\\Users\\a\\.mux\\state\\desktop-assets\\bin</WorkingDirectory>" in xml)
    }

    /** The XML an elevated register script carries (base64 inside the command line). */
    private fun carriedXml(script: String): String {
        val b64 = Regex("FromBase64String\\('+([A-Za-z0-9+/=]+)'+\\)").find(script)!!.groupValues[1]
        return String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
    }

    @Test fun windowsInstallRegistersTheTaskElevatedFromXmlInsideTheCommand() {
        val home = createTempDirectory()
        val env = winEnv(home)
        val stale = env.localAppData.resolve("Supermux/supermux-host-task.xml")
        Files.createDirectories(stale.parent); Files.writeString(stale, "an older version's copy, env included")
        val installed = assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec, env, me))
        assertEquals(BrokerService.WINDOWS_TASK_PATH, installed.path)
        assertFalse(Files.exists(stale), "no definition file left in the user's profile")
        val script = elevatedCalls(env).single().last()
        assertTrue("RegisterTask(''Supermux Host'', \$xml, 6, \$null, \$null, 3)" in script, script)
        assertTrue("Register-ScheduledTask" !in script && "Import-Module" !in script, "no cmdlet: no module auto-load")
        assertTrue("\$env:PSModulePath = \$PSHOME + ''\\Modules''" in script, "only the system's modules")
        assertTrue("-FilePath (\$env:SystemRoot + '\\System32\\WindowsPowerShell\\v1.0\\powershell.exe')" in script, script)
        assertTrue(elevatedCalls(env).single().first().endsWith("\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"))
        assertEquals(winXml(home), carriedXml(script), "the elevated process registers exactly our definition")
        assertTrue("/XML" !in script && "AppData" !in script.substringBefore("FromBase64String"),
            "no user-writable file between the prompt and the registration")
        val envFile = env.localAppData.resolve("Supermux/broker-env.ps1")
        assertEquals(
            "\uFEFF\$env:MUX_HOST_NAME = 'Alex''s & \"Win\"'\r\n\$env:MUX_MANAGED_BY = 'desktop'\r\n\$env:MUX_TELEGRAM_BOT_TOKEN = '123:secret'\r\n" +
                "\$env:MUX_DESKTOP_INSTALL = '${me.id}'\r\n",
            Files.readString(envFile),
        )
        if (posixHost) assertEquals("rw-------", mode(envFile)) // no POSIX modes on a real Windows host
    }

    @Test fun windowsRemoveDeletesTheTaskAndItsEnvFile() {
        val home = createTempDirectory()
        val env = winEnv(home)
        BrokerService.install(winSpec, env, me)
        val removed = assertIs<BrokerService.Result.Removed>(BrokerService.remove(env, me))
        assertEquals(BrokerService.WINDOWS_TASK_PATH, removed.path)
        assertFalse(Files.exists(env.localAppData.resolve("Supermux/broker-env.ps1")))
        assertTrue(env.ran.any { it.firstOrNull()?.endsWith("powershell.exe") == true && it.last().contains("/Delete") })
    }

    private fun parse(xml: String): org.w3c.dom.Document {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        f.isValidating = false
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    }

    private val nasty = spec.copy(env = linkedMapOf("MUX_HOST_NAME" to "A & <b> \"q\" \u2019s"))

    @Test fun plistAndTaskXmlAreWellFormedWithNastyValues() {
        parse(BrokerService.launchdPlist(nasty))
        parse(BrokerService.windowsTaskXml(nasty, winEnvFile))
    }

    @Test fun windowsTaskXmlHasHiddenSupervisionLoopAndCurlyQuoteDoubled() {
        val xml = BrokerService.windowsTaskXml(winSpec, Path.of("C:\\Users\\Alex\u2019s\\broker.env"))
        parse(xml)
        assertTrue("-WindowStyle Hidden" in xml)
        assertTrue("while (\$true)" in xml)
        assertTrue("\$supermuxHost.HasExited) { break }" in xml, "an ended task's loop stops instead of respawning")
        assertTrue("-Filter ('ProcessId=' + \$PID)" in xml && "\$_.ToString()" in xml, "no double quotes of its own in the script")
        assertTrue("\$null = \$supermuxHost.Handle" in xml, "the parent's handle is pinned against pid reuse")
        assertTrue("Out-File -Append" in xml)
        assertTrue("'C:\\Users\\Alex\u2019\u2019s\\broker.env'" in xml, "curly apostrophe doubled in the PowerShell literal")
    }

    @Test fun systemdEscapesDollarInExecStartAndPercentInLog() {
        assumePosixHost()
        val unit = BrokerService.systemdUnit(spec.copy(broker = Path.of("/opt/a\$b/broker"), log = Path.of("/tmp/100%/x.log")))
        assertTrue("ExecStart=\"/opt/a\$\$b/broker\"" in unit)
        assertTrue("StandardOutput=append:/tmp/100%%/x.log" in unit)
        assertTrue("StandardError=append:/tmp/100%%/x.log" in unit)
    }

    @Test fun macBootstrapFailingTenTimesFails() {
        val home = createTempDirectory()
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString()
        val bs = listOf("launchctl", "bootstrap", "gui/501", plist)
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501, failing = setOf(printHost),
            scripted = mapOf(bs to List(10) { OsEnv.RunResult(5, "", " Bootstrap failed: 5 \n") }))
        val r = BrokerService.install(spec, env, me)
        val f = assertIs<BrokerService.Result.Failed>(r)
        assertTrue("Bootstrap failed: 5" in f.message)
        assertEquals(List(9) { 1_000L }, env.sleeps)
        assertEquals(10, env.ran.count { it == bs })
    }

    @Test fun macBootstrapFailingThreeTimesThenSucceeds() {
        val home = createTempDirectory()
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString()
        val bs = listOf("launchctl", "bootstrap", "gui/501", plist)
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501, failing = setOf(printHost),
            scripted = mapOf(bs to List(3) { OsEnv.RunResult(5, "", "Bootstrap failed: 5: Input/output error") }))
        assertTrue(assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env, me)).enabled)
        assertEquals(4, env.ran.count { it == bs })
        assertEquals(List(3) { 1_000L }, env.sleeps)
    }

    @Test fun macBootstrapRetriesThenSucceeds() {
        val home = createTempDirectory()
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString()
        val bs = listOf("launchctl", "bootstrap", "gui/501", plist)
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501, failing = setOf(printHost),
            scripted = mapOf(bs to listOf(OsEnv.RunResult(5, "", "busy"))))
        val r = assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env, me))
        assertTrue(r.enabled)
        assertEquals(1, env.sleeps.size)
    }

    @Test fun linuxInstallEnablesThenRestarts() {
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = createTempDirectory(), uid = 1000, xdgRuntimeDir = "/run/user/1000")
        BrokerService.install(spec, env, me)
        val enable = env.ran.indexOf(listOf("systemctl", "--user", "enable", "--now", "supermux-host"))
        val restart = env.ran.indexOf(listOf("systemctl", "--user", "restart", "supermux-host"))
        assertTrue(enable >= 0 && restart > enable)
    }

    // #29 step 9: systemd --user and the XDG autostart read $XDG_CONFIG_HOME when it is set, so a unit written to
    // ~/.config never loaded there while the app believed it installed.
    @Test fun linuxInstallWritesTheUnitWhereXdgConfigHomeSays() {
        assumePosixHost()
        val home = createTempDirectory()
        val config = home.resolve("cfg")
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000",
            envVars = mapOf("XDG_CONFIG_HOME" to config.toString()))
        BrokerService.install(spec, env, me)
        assertTrue(Files.exists(config.resolve("systemd/user/supermux-host.service")))
        assertFalse(Files.exists(home.resolve(".config/systemd/user/supermux-host.service")))
        assertTrue(BrokerService.isInstalled(env))
        assertEquals(config.resolve("autostart/supermux-host.desktop"), BrokerService.xdgAutostartPath(env))
    }

    @Test fun linuxXdgFallbackKeepsItsEntryAndEnvFileUnderXdgConfigHome() {
        assumePosixHost()
        val home = createTempDirectory()
        val config = home.resolve("cfg")
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = null,
            envVars = mapOf("XDG_CONFIG_HOME" to config.toString()))
        BrokerService.install(spec, env, me)
        val entry = config.resolve("autostart/supermux-host.desktop")
        val envFile = config.resolve("supermux/broker.env")
        assertTrue(Files.exists(entry))
        assertTrue(envFile.toString() in Files.readString(entry), "the entry sources the env file where it is")
        BrokerService.remove(env, me)
        assertFalse(Files.exists(entry))
        assertFalse(Files.exists(envFile))
    }

    @Test fun aRelativeXdgConfigHomeIsIgnored() {
        // The XDG spec: a relative value is invalid and must be ignored.
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, envVars = mapOf("XDG_CONFIG_HOME" to "cfg"))
        assertEquals(home.resolve(".config/autostart/supermux-host.desktop"), BrokerService.xdgAutostartPath(env))
        assertEquals(home.resolve(".config/supermux/broker.env"), BrokerService.xdgEnvPath(env))
    }

    @Test fun linuxXdgFallbackIsNotEnabledAndRemoveCleansUp() {
        assumePosixHost()
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = null)
        val r = assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env, me))
        assertFalse(r.enabled)
        val desktop = home.resolve(".config/autostart/supermux-host.desktop")
        assertTrue(Files.exists(desktop))
        val envFile = home.resolve(".config/supermux/broker.env")
        assertEquals("rw-------", mode(envFile), "the env may hold tokens")
        assertTrue("export MUX_HOST_NAME='Alex'\\''s Mac & Co'" in Files.readString(envFile))
        val entry = Files.readString(desktop)
        assertTrue("Alex" !in entry && "9898" !in entry, "no env value in the autostart's argv")
        val unit = home.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent); Files.writeString(unit, BrokerService.systemdUnit(spec))
        BrokerService.remove(env, me)
        assertFalse(Files.exists(desktop))
        assertFalse(Files.exists(unit))
        assertFalse(Files.exists(envFile))
    }

    @Test fun xdgExecSourcesTheEnvFileAndQuotesForBothLayers() {
        assumePosixHost()
        val d = BrokerService.xdgAutostart(spec.copy(broker = Path.of("/opt/it's \$x%/broker")), Path.of("/h/.config/supermux/broker.env"))
        val exec = d.lines().single { it.startsWith("Exec=") }
        // Desktop Entry: inside "…", `$` and `\` are backslashed, then every `\` doubled and `%` doubled.
        assertEquals(
            "Exec=/bin/sh -c \". '/h/.config/supermux/broker.env' && exec '/opt/it'\\\\\\\\''s \\\\\$x%%/broker'\"",
            exec,
        )
        assertEquals("export A='x\$y%z'\n", BrokerService.xdgEnvFile(spec.copy(env = linkedMapOf("A" to "x\$y%z"))).lines().first() + "\n")
    }

    @Test fun aDeclinedUacPromptIsAFailureNotASuccess() {
        val env = winEnv()
        BrokerService.install(winSpec, env, me)
        val script = elevatedCalls(env).single().last()
        // Start-Process's error is non-terminating: without -ErrorAction Stop + a null check,
        // `exit $process.ExitCode` would exit 0 after a "No".
        assertTrue("-ErrorAction Stop" in script && "catch { exit 1223 }" in script && "if (-not \$process) { exit 1223 }" in script, script)
    }

    @Test fun windowsInstallIsOneElevatedCallWithRegisterAndRun() {
        val env = winEnv()
        BrokerService.install(winSpec, env, me)
        val elevated = elevatedCalls(env)
        assertEquals(1, elevated.size)
        val s = elevated[0].last()
        assertTrue("RegisterTask(" in s && ".Run(" in s && s.indexOf("RegisterTask(") < s.indexOf(".Run("))
    }

    @Test fun theRegisterScriptFailsOnARegistrationErrorAndCanSkipTheStart() {
        val s = BrokerService.registerTaskScript("<Task/>", start = false)
        assertTrue("\$ErrorActionPreference = 'Stop'" in s && "catch { exit 1 }" in s, s)
        assertTrue(".Run(" !in s && s.endsWith("exit 0"))
        assertEquals("<Task/>", carriedXml(s))
    }

    // ── Windows: stopping the task's broker by pid, never the agents' shims ──────────────────

    private val listing = BrokerService.listWindowsProcessesArgv()
    private val loopCmd = "powershell.exe -NoLogo -Command \"\$env:MUX_MANAGED_BY = 'desktop'; \$env:MUX_WINDOWS_TASK = '1'; " +
        "while (\$true) { & 'C:\\s\\supermux-broker.exe' 2>&1 | ForEach-Object { \$_.ToString() } }\""
    /** conhost 100 → loop 200 → broker 300 (+ its keep-awake powershell 500); two agents' shim/credential 400/401. */
    private val taskProcs = listOf(
        "100\t4\tconhost.exe\tconhost.exe --headless powershell.exe -NoLogo",
        "200\t100\tpowershell.exe\t$loopCmd",
        "300\t200\tsupermux-broker.exe\t\"C:\\s\\supermux-broker.exe\"",
        "400\t900\tsupermux-broker.exe\t\"C:\\s\\supermux-broker.exe\" shim",
        "401\t901\tsupermux-broker.exe\tC:\\s\\supermux-broker.exe credential get",
        "500\t300\tpowershell.exe\tpowershell.exe -NoProfile -EncodedCommand JABFAHIA",
        "",
    ).joinToString("\r\n")
    private val killLoop = listOf("taskkill", "/F", "/PID", "200")
    private val killBroker = listOf("taskkill", "/F", "/PID", "300")
    private val run = listOf("schtasks", "/Run", "/TN", "Supermux Host")
    private val queryXml = listOf("schtasks", "/Query", "/TN", "Supermux Host", "/XML")
    /** [registered]: the task definition `schtasks /Query /XML` reports (null: none). */
    private fun winEnv(
        home: Path = createTempDirectory(),
        procs: String? = taskProcs,
        failIf: (List<String>) -> Boolean = { false },
        registered: String? = null,
    ) = FakeOsEnv(
        os = DesktopOs.WINDOWS, home = home,
        captures = buildMap {
            procs?.let { put(listing, it) }
            registered?.let { put(queryXml, it) }
        },
        failIf = failIf,
    )
    private fun FakeOsEnv.neverKilledByImage() = assertTrue(ran.none { it.firstOrNull() == "taskkill" && "/IM" in it }, "a /IM kill takes the agents' shims too")
    private fun FakeOsEnv.neverKilled(pid: Long) = assertTrue(ran.none { it.firstOrNull() == "taskkill" && pid.toString() in it })

    @Test fun windowsTaskTargetsSpareTheAgentsShimsAndCredentialHelpers() {
        val t = BrokerService.windowsTaskTargets(BrokerService.parseWindowsProcesses(taskProcs))
        assertEquals(listOf(200L), t.loops)
        assertEquals(listOf(300L), t.brokers)
    }

    @Test fun windowsTaskTargetsFindOrphanedAndOldLoopsButNeverTheAppsChildBroker() {
        fun p(pid: Long, ppid: Long, name: String, cmd: String) = BrokerService.WinProcess(pid, ppid, name, cmd)
        val procs = listOf(
            // conhost already killed by schtasks /End: the orphan still counts
            p(10, 1, "powershell.exe", loopCmd),
            // an older app version's loop: no MUX_WINDOWS_TASK, under the task engine
            p(11, 2, "svchost.exe", "svchost.exe -k netsvcs"),
            p(12, 11, "powershell.exe", loopCmd.replace("\$env:MUX_WINDOWS_TASK = '1'; ", "")),
            // someone's own shell that merely mentions the variable, under explorer: not ours
            p(13, 14, "explorer.exe", "explorer.exe"),
            p(15, 13, "powershell.exe", loopCmd),
            // the app's child-mode broker (bare, parent java): background off, never the task's
            p(20, 30, "supermux-broker.exe", "C:\\s\\supermux-broker.exe"),
            p(21, 15, "supermux-broker.exe", "\"C:\\s\\supermux-broker.exe\" shim"),
            // the old-style loop's broker
            p(22, 12, "supermux-broker.exe", "\"C:\\s\\supermux-broker.exe\""),
        )
        val t = BrokerService.windowsTaskTargets(procs)
        assertEquals(listOf(10L, 12L), t.loops)
        assertEquals(listOf(22L), t.brokers)
    }

    @Test fun windowsInstallAbortsWhenTheRunningBrokerWontStop() {
        val home = createTempDirectory()
        val env = winEnv(home, procs = null, registered = winXml(home, oldBroker))
        val r = assertIs<BrokerService.Result.Failed>(BrokerService.install(winSpec, env, me))
        assertTrue(r.previousStillRunning)
        assertEquals(BrokerService.WINDOWS_STOP_FAILED, r.message)
        assertEquals(emptyList(), elevatedCalls(env), "no re-registration")
        assertTrue(run !in env.ran, "no second loop")
    }

    @Test fun windowsRestartDoesNotRunASecondLoopWhenTheBrokerWontStop() {
        val env = winEnv(procs = null)
        assertFalse(BrokerService.restart(env, me))
        assertTrue(run !in env.ran)
    }

    @Test fun windowsRemoveFailsWhenTheBrokerOutlivesTheTask() {
        val env = winEnv()
        val tasklist = listOf("tasklist", "/FI", "PID eq 300", "/NH")
        val stuck = object : OsEnv by env {
            override fun runCapture(argv: List<String>): String? =
                if (argv == tasklist) "supermux-broker.exe   300 Console   1  150,000 K" else env.runCapture(argv)
        }
        assertIs<BrokerService.Result.Failed>(BrokerService.remove(stuck, me))
    }

    @Test fun theProcessListerHasNoDoubleQuotes() {
        assertTrue('"' !in listing.last(), "Java does not escape double quotes on Windows")
    }

    @Test fun windowsRemoveEndsAndDeletesElevatedThenStopsLoopAndBrokerByPid() {
        val env = winEnv()
        BrokerService.install(winSpec, env, me)
        env.ran.clear()
        val tasklist = listOf("tasklist", "/FI", "PID eq 300", "/NH")
        var polls = 0
        val waiting = object : OsEnv by env {
            override fun runCapture(argv: List<String>): String? {
                if (argv == tasklist) { env.ran += argv; return if (polls++ < 2) "supermux-broker.exe   300 Console   1  150,000 K" else "INFO: No tasks" }
                return env.runCapture(argv)
            }
        }
        assertIs<BrokerService.Result.Removed>(BrokerService.remove(waiting, me))
        val elevated = elevatedCalls(env).single()
        assertTrue("/End" in elevated.last() && elevated.last().indexOf("/End") < elevated.last().indexOf("/Delete"))
        assertTrue(env.ran.indexOf(elevated) < env.ran.indexOf(killLoop))
        assertTrue(env.ran.indexOf(killLoop) < env.ran.indexOf(killBroker), "the loop goes first, or it respawns the broker")
        assertEquals(3, env.ran.count { it == tasklist }, "polls the broker's pid until it's gone")
        env.neverKilledByImage()
        env.neverKilled(400); env.neverKilled(401)
    }

    /** A changed definition: a broker at another path. */
    private val oldBroker = winSpec.copy(broker = Path.of("C:\\Program Files\\supermux\\supermux-broker.exe"))

    private fun elevatedCalls(env: FakeOsEnv) = env.ran.filter { it.firstOrNull()?.endsWith("powershell.exe") == true && it.last().contains("-Verb RunAs") }

    @Test fun windowsReinstallOfTheSameDefinitionRestartsWithoutUac() {
        val home = createTempDirectory()
        val env = winEnv(home, registered = winXml(home))
        assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec, env, me))
        env.ran.clear()
        val again = assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec, env, me))
        assertTrue(again.enabled)
        assertEquals(emptyList(), elevatedCalls(env), "an app update with the same task definition needs no UAC prompt")
        assertTrue(env.ran.indexOf(killLoop) in 0 until env.ran.indexOf(killBroker))
        assertTrue(env.ran.indexOf(killBroker) < env.ran.indexOf(run), "the old broker is gone before the task starts again")
        env.neverKilledByImage()
    }

    @Test fun windowsInstallAfterTheCallerStoppedTheServiceDoesNotStopItAgain() {
        val home = createTempDirectory()
        val env = winEnv(home, registered = winXml(home))
        BrokerService.install(winSpec, env, me)
        env.ran.clear()
        assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec, env, me, alreadyStopped = true))
        assertTrue(listing !in env.ran && env.ran.none { it.firstOrNull() == "taskkill" })
        assertTrue(run in env.ran)
    }

    @Test fun theRegisteredDefinitionDecidesNotOurFile() {
        // Our file says the new definition (a prompt was declined after it was written); the task
        // still runs the old one: that is a change, so it must ask again.
        val home = createTempDirectory()
        BrokerService.install(winSpec, winEnv(home, registered = null), me)
        val old = winXml(home, oldBroker)
        val env = winEnv(home, registered = old)
        BrokerService.install(winSpec, env, me)
        assertEquals(1, elevatedCalls(env).size)
        // schtasks escapes differently from us: the comparison is on the unescaped values.
        val ours = winXml(home)
        assertTrue(BrokerService.sameTaskAction(ours.replace("\"", "&quot;").replace("'", "&apos;"), ours))
        assertFalse(BrokerService.sameTaskAction(old, ours))
        assertFalse(BrokerService.sameTaskAction(null, ours))
    }

    @Test fun windowsChangedDefinitionStopsTheOldBrokerThenElevatesOnce() {
        val env = winEnv()
        BrokerService.install(winSpec, env, me)
        env.ran.clear()
        assertIs<BrokerService.Result.Installed>(BrokerService.install(oldBroker, env, me))
        val elevated = elevatedCalls(env)
        assertEquals(1, elevated.size)
        assertTrue(env.ran.indexOf(killBroker) in 0 until env.ran.indexOf(elevated[0]), "re-registering must not leave the old loop running")
    }

    @Test fun windowsDeclinedUacOnAChangedDefinitionStartsThePreviousOneAgain() {
        val home = createTempDirectory()
        BrokerService.install(winSpec, winEnv(home), me)
        val env = winEnv(home, failIf = { it.firstOrNull()?.endsWith("powershell.exe") == true && it.last().contains("-Verb RunAs") })
        val r = assertIs<BrokerService.Result.Failed>(BrokerService.install(oldBroker, env, me))
        assertTrue(r.previousStillRunning)
        assertEquals(BrokerService.WINDOWS_UPDATE_DECLINED, r.message)
        assertEquals(1, elevatedCalls(env).size)
        assertTrue(env.ran.indexOf(elevatedCalls(env)[0]) < env.ran.lastIndexOf(run), "the old definition's broker runs again")
    }

    @Test fun windowsDeclinedUacOnAFirstInstallIsAPlainFailure() {
        val q = listOf("schtasks", "/Query", "/TN", "Supermux Host")
        // Nothing is registered: every query says so.
        val env = FakeOsEnv(os = DesktopOs.WINDOWS, home = createTempDirectory(), failing = setOf(q),
            failIf = { it.firstOrNull()?.endsWith("powershell.exe") == true && it.last().contains("-Verb RunAs") })
        val r = assertIs<BrokerService.Result.Failed>(BrokerService.install(winSpec, env, me))
        assertFalse(r.previousStillRunning)
        assertTrue(run !in env.ran)
    }

    // ── Windows: a failed `schtasks /Query` is not "no task" (#29, Ahmet) ─────────────────────────────────────────
    // `/Query /TN` failing used to read as "not registered": install wrote over a task it could not see, and remove
    // said "removed" and deleted the task loop's env file. Its error text is in the user's language, so the whole task
    // list (`/Query /FO CSV /NH`, whose names are data) tells "not there" from "no answer"; no answer fails closed.

    private val queryTask = listOf("schtasks", "/Query", "/TN", "Supermux Host")
    private val listTasks = listOf("schtasks", "/Query", "/FO", "CSV", "/NH")
    private fun listing(csv: String) = mapOf(listTasks to List(4) { OsEnv.RunResult(0, csv, "") })

    @Test fun windowsInstallWritesNothingWhenSchtasksCannotSayWhatIsRegistered() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.WINDOWS, home = home, failing = setOf(queryTask, listTasks))
        val r = assertIs<BrokerService.Result.Failed>(BrokerService.install(winSpec, env, me))
        assertTrue("Supermux Host" in r.message, r.message)
        assertTrue(env.ran.none { it.firstOrNull()?.endsWith("powershell.exe") == true }, "nothing registered: ${env.ran}")
        assertTrue(run !in env.ran)
        assertFalse(Files.exists(home.resolve("AppData/Local").resolve(BrokerService.WINDOWS_ENV_FILE)), "no env file written")
    }

    @Test fun windowsInstallWritesNothingWhenTheAnswerTurnsUnknownBeforeItWrites() {
        // install() reads the task again (what is registered, which is what runs) before it acts: an answer that turns
        // into "no answer" in between must still write nothing. The third listing is the one install reads last.
        val home = createTempDirectory()
        val absent = OsEnv.RunResult(0, "", "")
        val env = FakeOsEnv(os = DesktopOs.WINDOWS, home = home, failing = setOf(queryTask),
            scripted = mapOf(listTasks to listOf(absent, absent, OsEnv.RunResult(1, "", "failed"))))
        assertIs<BrokerService.Result.Failed>(BrokerService.install(winSpec, env, me))
        assertFalse(Files.exists(home.resolve("AppData/Local").resolve(BrokerService.WINDOWS_ENV_FILE)), "no env file written")
        assertTrue(env.ran.none { it.firstOrNull()?.endsWith("powershell.exe") == true }, "nothing registered: ${env.ran}")
    }

    @Test fun windowsRemoveKeepsEverythingWhenSchtasksCannotSayWhatIsRegistered() {
        val home = createTempDirectory()
        val envFile = home.resolve("AppData/Local").resolve(BrokerService.WINDOWS_ENV_FILE)
        Files.createDirectories(envFile.parent)
        envFile.writeText("the task loop's env")
        val env = FakeOsEnv(os = DesktopOs.WINDOWS, home = home, failing = setOf(queryTask, listTasks))
        assertIs<BrokerService.Result.Failed>(BrokerService.remove(env, me))
        assertTrue(Files.exists(envFile), "the task loop's env file stays")
        assertTrue(env.ran.none { it.firstOrNull()?.endsWith("powershell.exe") == true }, "nothing ended or deleted: ${env.ran}")
    }

    @Test fun aTaskTheListNamesIsRegisteredThoughItsOwnQueryFailed() {
        val env = FakeOsEnv(os = DesktopOs.WINDOWS, home = createTempDirectory(), failing = setOf(queryTask),
            scripted = listing("\"\\Microsoft\\Other\",\"N/A\",\"Ready\"\r\n\"\\Supermux Host\",\"N/A\",\"Running\"\r\n"))
        assertTrue(BrokerService.isInstalled(env))
    }

    @Test fun aTaskTheListDoesNotNameIsNotRegistered() {
        // Not a longer name that starts with ours, nor our name in another folder: the task we register is at the root.
        val env = FakeOsEnv(os = DesktopOs.WINDOWS, home = createTempDirectory(), failing = setOf(queryTask),
            scripted = listing("\"\\Supermux Host 2\",\"N/A\",\"Ready\"\r\n\"\\Folder\\Supermux Host\",\"N/A\",\"Ready\"\r\n"))
        assertFalse(BrokerService.isInstalled(env))
    }

    @Test fun theTaskListIsReadByItsNamesNotItsWords() {
        // The other fields are in the user's language; the first is the task's path.
        assertTrue(BrokerService.taskListed("\"\\Supermux Host\",\"Nicht zutreffend\",\"Wird ausgeführt\"\r\n", "Supermux Host"))
        assertTrue(BrokerService.taskListed("\"\\SUPERMUX HOST\",\"N/A\",\"Ready\"", "Supermux Host"), "task names ignore case")
        assertFalse(BrokerService.taskListed("\"\\Supermux Host 2\",\"N/A\",\"Ready\"", "Supermux Host"))
        assertFalse(BrokerService.taskListed("\"\\Folder\\Supermux Host\",\"N/A\",\"Ready\"", "Supermux Host"))
        assertFalse(BrokerService.taskListed("", "Supermux Host"))
    }

    @Test fun stopIsWindowsOnlyAndKillsByPid() {
        val mac = FakeOsEnv(os = DesktopOs.MACOS, home = createTempDirectory(), uid = 501)
        assertFalse(BrokerService.stop(mac))
        assertEquals(emptyList(), mac.ran)
        val win = winEnv()
        assertTrue(BrokerService.stop(win))
        assertTrue(killLoop in win.ran && killBroker in win.ran)
        win.neverKilledByImage()
        win.neverKilled(400); win.neverKilled(401)
        assertTrue(win.ran.none { it.firstOrNull() == "schtasks" }, "stop leaves the task registered and not started")
    }

    // ── Windows: the broker is asked to stop first (Q8); success only once no broker holds the state (#36) ───────

    @Test fun brokerLockPipeIsTheOneTheBrokerHolds() {
        // packages/platform/src/lock.ts pipeName("broker", …) for the same state directories.
        val home = "\\\\.\\pipe\\supermux-broker-ab9f3a6f357ab1a27153f6d5f88cab2d-lock"
        assertEquals(home, BrokerService.brokerLockPipe("C:\\Users\\me\\.mux\\state"))
        assertEquals(home, BrokerService.brokerLockPipe("c:/users/ME/.mux/state"))
        assertEquals(home, BrokerService.brokerLockPipe("C:\\Users\\me\\.mux\\x\\..\\state"))
        assertEquals("\\\\.\\pipe\\supermux-broker-5a0032cf7500104195a8a7915bb63630-lock", BrokerService.brokerLockPipe("C:\\Users\\me\\.mux\\state\\"))
        assertEquals("\\\\.\\pipe\\supermux-broker-755457bddb79c460bca4ba0a000a7adc-lock", BrokerService.brokerLockPipe("D:\\Data\\supermux state"))
        assertEquals("\\\\.\\pipe\\supermux-broker-f95d372bcec0ccb87387cf132ad04ac4-lock", BrokerService.brokerLockPipe("\\\\server\\share\\mux\\state"))
        assertEquals("\\\\.\\pipe\\supermux-broker-307856bc1d1342561f45e18801c47209-lock", BrokerService.brokerLockPipe("C:\\Users\\Ömer\\.mux\\state"))
    }

    /**
     * [fake] whose broker lock pipe for [state] is listed while [held]; [onPost] answers the stop request (and may let
     * go of the lock); running [releasedBy] lets go of it too. [events] is what happened, in order.
     */
    private class LockedHost(
        val fake: FakeOsEnv,
        private val state: String,
        var held: Boolean = true,
        private val releasedBy: List<String>? = null,
        private val onPost: (LockedHost) -> Int?,
    ) : OsEnv by fake {
        val events = mutableListOf<String>()
        val posts = mutableListOf<Pair<String, Map<String, String>>>()
        override fun run(argv: List<String>): Boolean {
            events += argv.joinToString(" ")
            if (argv == releasedBy) held = false
            return fake.run(argv)
        }
        override fun runCapture(argv: List<String>): String? =
            if (argv == BrokerService.listPipesArgv()) {
                (if (held) "\\\\.\\pipe\\other\r\n" + BrokerService.brokerLockPipe(state) + "\r\n" else "\\\\.\\pipe\\other\r\n")
            } else fake.runCapture(argv)
        override fun postLocal(url: String, headers: Map<String, String>): Int? {
            events += "POST $url"
            posts += url to headers
            return onPost(this)
        }
    }

    @Test fun windowsStopAsksTheBrokerToStopAndSucceedsWhenItsLockIsGone() {
        val state = createTempDirectory()
        state.resolve("stop-token").writeText("tok")
        val env = LockedHost(winEnv(), state.toString()) { it.held = false; 202 }
        assertTrue(BrokerService.stop(env, stateDir = state, port = 9898))
        assertEquals(listOf("http://127.0.0.1:9898/internal/stop" to mapOf("x-supermux-stop-token" to "tok")), env.posts)
        val loop = env.events.indexOf(killLoop.joinToString(" "))
        assertTrue(loop in 0 until env.events.indexOf("POST http://127.0.0.1:9898/internal/stop"), "the loop first, or it starts the broker again")
        assertTrue(killBroker !in env.fake.ran, "it stopped on its own: no TerminateProcess")
    }

    // No loop is its parent (the app's old child, or a loop that crashed): the stop used to skip it and report success.
    @Test fun windowsStopKillsTheOrphanBrokerThatBrokerPidNamesWhenItWillNotStop() {
        val state = createTempDirectory()
        state.resolve("broker.pid").writeText("777")
        val orphan = listOf(
            "777\t4321\tsupermux-broker.exe\t\"C:\\s\\supermux-broker.exe\"",
            "778\t777\tsupermux-broker.exe\t\"C:\\s\\supermux-broker.exe\" shim",
            "",
        ).joinToString("\r\n")
        val killOrphan = listOf("taskkill", "/F", "/PID", "777")
        val env = LockedHost(winEnv(procs = orphan), state.toString(), releasedBy = killOrphan) { null }
        assertTrue(BrokerService.stop(env, stateDir = state, port = 9898))
        assertTrue(killOrphan in env.fake.ran)
        env.fake.neverKilled(778)
    }

    @Test fun windowsStopFailsWhileABrokerStillHoldsTheState() {
        val state = createTempDirectory()
        val env = LockedHost(winEnv(), state.toString()) { null }
        assertFalse(BrokerService.stop(env, stateDir = state, port = 9898), "a broker still holds the state: not stopped")
    }

    // PowerShell could not list the pipes: the pids decide, as before the lock existed (an update must not stall on it).
    @Test fun windowsStopLetsThePidsDecideWhenThePipesCannotBeListed() {
        val env = winEnv()
        assertTrue(BrokerService.stop(env, stateDir = createTempDirectory(), port = 9898))
        assertTrue(killLoop in env.ran && killBroker in env.ran)
    }

    @Test fun stopKillsNothingWhenItCannotListProcesses() {
        val win = winEnv(procs = null)
        assertFalse(BrokerService.stop(win))
        assertTrue(win.ran.none { it.firstOrNull() == "taskkill" })
    }

    @Test fun windowsTaskReplacesALingeringInstanceOnRun() {
        assertTrue("<MultipleInstancesPolicy>StopExisting</MultipleInstancesPolicy>" in BrokerService.windowsTaskXml(winSpec, winEnvFile))
    }

    @Test fun windowsRemoveFailsWhenTheElevatedBatchFails() {
        val home = createTempDirectory()
        val installEnv = winEnv(home)
        BrokerService.install(winSpec, installEnv, me)
        val envFile = installEnv.localAppData.resolve("Supermux/broker-env.ps1")
        val env = winEnv(home, failIf = { it.firstOrNull()?.endsWith("powershell.exe") == true && it.last().contains("-Verb RunAs") })
        assertIs<BrokerService.Result.Failed>(BrokerService.remove(env, me))
        assertTrue(env.ran.none { it.firstOrNull() == "taskkill" })
        assertTrue(Files.exists(envFile), "the still-registered task still needs its env")
    }

    @Test fun windowsRestartStopsTheLoopAndBrokerThenRunsWithoutElevation() {
        val env = winEnv()
        assertTrue(BrokerService.restart(env, me))
        assertTrue(env.ran.indexOf(killLoop) in 0 until env.ran.indexOf(killBroker))
        assertTrue(env.ran.indexOf(killBroker) < env.ran.indexOf(run))
        assertTrue(env.ran.none { it.last().contains("-Verb RunAs") })
        env.neverKilledByImage()
        env.neverKilled(400); env.neverKilled(401)
    }

    @Test fun windowsRestartStartsAnEndedTaskEvenWithNothingRunning() {
        val env = winEnv(procs = "")
        assertTrue(BrokerService.restart(env, me), "the loop was gone: /Run brings it back")
        assertTrue(env.ran.none { it.firstOrNull() == "taskkill" })
    }

    @Test fun windowsRemoveWhenNotInstalledDoesNotElevate() {
        val q = listOf("schtasks", "/Query", "/TN", "Supermux Host")
        val env = FakeOsEnv(os = DesktopOs.WINDOWS, home = createTempDirectory(),
            scripted = mapOf(q to listOf(OsEnv.RunResult(1, "", "not found"))))
        val r = assertIs<BrokerService.Result.Removed>(BrokerService.remove(env, me))
        assertEquals(null, r.path)
        assertTrue(env.ran.none { it.firstOrNull()?.endsWith("powershell.exe") == true })
    }

    @Test fun specRejectsLineBreaks() {
        assertFailsWith<IllegalArgumentException> { spec.copy(env = mapOf("A" to "x\ny")) }
        assertFailsWith<IllegalArgumentException> { spec.copy(env = mapOf("A\r" to "x")) }
    }

    @Test fun specRejectsKeysAShellOrPowerShellCannotTakeAsIs() {
        for (k in listOf("mux_lower", "A-B", "A B", "A=B", "\$(x)", "")) {
            assertFailsWith<IllegalArgumentException>(k) { spec.copy(env = mapOf(k to "x")) }
        }
    }

    @Test fun windowsAnEnvOnlyChangeRewritesTheEnvFileWithoutUac() {
        val home = createTempDirectory()
        val env = winEnv(home, registered = winXml(home))
        val changed = winSpec.copy(env = winSpec.env + ("MUX_RELAY_DOMAIN" to ""))
        assertIs<BrokerService.Result.Installed>(BrokerService.install(changed, env, me))
        assertEquals(emptyList(), elevatedCalls(env), "the definition didn't change")
        assertTrue("\$env:MUX_RELAY_DOMAIN = ''\r\n" in Files.readString(env.localAppData.resolve("Supermux/broker-env.ps1")))
        assertTrue(run in env.ran)
    }

    // ── upgrading from 1.0.0, whose keep-alive ran the APP under our names ─────────────────────

    @Test fun appRunsAsServiceRecognisesTheJobOnEachOs() {
        val home = createTempDirectory()
        assertTrue(BrokerService.appRunsAsService(FakeOsEnv(os = DesktopOs.MACOS, home = home, envVars = mapOf("XPC_SERVICE_NAME" to "dev.supermux.host"))))
        assertFalse(BrokerService.appRunsAsService(FakeOsEnv(os = DesktopOs.MACOS, home = home, envVars = mapOf("XPC_SERVICE_NAME" to "application.dev.supermux.desktop.123"))))
        assertTrue(BrokerService.appRunsAsService(FakeOsEnv(os = DesktopOs.LINUX, home = home, cgroup = LegacyKeepAlive.LINUX_JOB_CGROUP)))
        assertFalse(BrokerService.appRunsAsService(FakeOsEnv(os = DesktopOs.LINUX, home = home, cgroup = "0::/user.slice/user-1000.slice/session-2.scope\n")))
        assertTrue(BrokerService.appRunsAsService(FakeOsEnv(os = DesktopOs.WINDOWS, home = home, envVars = mapOf("SUPERMUX_KEEP_ALIVE" to "1"))))
        assertFalse(BrokerService.appRunsAsService(FakeOsEnv(os = DesktopOs.WINDOWS, home = home)))
    }

    @Test fun legacyDefinitionsAreRecognisedAndAreNotOurs() {
        val home = createTempDirectory()
        val mac = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501)
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist")
        Files.createDirectories(plist.parent); Files.writeString(plist, LegacyKeepAlive.plist)
        assertTrue(BrokerService.isLegacyInstalled(mac)); assertFalse(BrokerService.isOursInstalled(mac, me))
        Files.writeString(plist, BrokerService.launchdPlist(spec))
        assertFalse(BrokerService.isLegacyInstalled(mac)); assertTrue(BrokerService.isOursInstalled(mac, me))

        val linux = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000)
        val unit = home.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent); Files.writeString(unit, LegacyKeepAlive.unit)
        assertTrue(BrokerService.isLegacyInstalled(linux)); assertFalse(BrokerService.isOursInstalled(linux, me))
        Files.delete(unit)
        val xdg = home.resolve(".config/autostart/supermux-host.desktop")
        Files.createDirectories(xdg.parent); Files.writeString(xdg, LegacyKeepAlive.xdg)
        assertTrue(BrokerService.isLegacyInstalled(linux)); assertFalse(BrokerService.isOursInstalled(linux, me))

        val win = winEnv(registered = LegacyKeepAlive.taskXml)
        assertTrue(BrokerService.isLegacyInstalled(win)); assertFalse(BrokerService.isOursInstalled(win, me), "same task name, but it runs the app")
        assertFalse(BrokerService.isLegacyInstalled(winEnv(registered = winXml(createTempDirectory()))))
    }

    @Test fun macInstallAsThe100JobWritesTheNewPlistAndBootsNothingOut() {
        assumePosixHost()
        val home = createTempDirectory()
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist")
        Files.createDirectories(plist.parent); Files.writeString(plist, LegacyKeepAlive.plist)
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501, envVars = LegacyKeepAlive.macJobEnv)
        val r = assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env, me))
        assertTrue(r.nextLogin && !r.enabled)
        assertTrue(BrokerService.MANAGED_MARKER in Files.readString(plist), "the new definition is in place for the next login")
        assertEquals("rw-------", mode(plist))
        assertEquals(listOf(listOf("launchctl", "enable", "gui/501/dev.supermux.host")), env.ran, "no bootout, bootstrap or kickstart of the job that is this app")
        env.ran.clear()
        assertFalse(BrokerService.restart(env, me))
        assertIs<BrokerService.Result.Removed>(BrokerService.remove(env, me))
        assertFalse(Files.exists(plist))
        assertEquals(emptyList(), env.ran, "the job (this app) ends with the app")
    }

    @Test fun linuxInstallAsThe100JobEnablesWithoutStoppingIt() {
        val home = createTempDirectory()
        val unit = home.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent); Files.writeString(unit, LegacyKeepAlive.unit)
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000", cgroup = LegacyKeepAlive.LINUX_JOB_CGROUP)
        val r = assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env, me))
        assertTrue(r.nextLogin)
        assertTrue(BrokerService.MANAGED_MARKER in Files.readString(unit))
        assertTrue(listOf("systemctl", "--user", "enable", "supermux-host") in env.ran)
        assertTrue(env.ran.none { "--now" in it || "restart" in it || "stop" in it }, env.ran.toString())
        env.ran.clear()
        assertFalse(BrokerService.restart(env, me))
        BrokerService.remove(env, me)
        assertFalse(Files.exists(unit))
        assertTrue(listOf("systemctl", "--user", "disable", "supermux-host") in env.ran)
        assertTrue(env.ran.none { "--now" in it }, env.ran.toString())
    }

    @Test fun linuxUnitReplacesThe100XdgEntry() {
        val home = createTempDirectory()
        val xdg = home.resolve(".config/autostart/supermux-host.desktop")
        Files.createDirectories(xdg.parent); Files.writeString(xdg, LegacyKeepAlive.xdg)
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env, me))
        assertFalse(Files.exists(xdg), "it would open the app at every login")
    }

    @Test fun windowsInstallAsThe100JobRegistersWithoutEndingOrRunningIt() {
        val home = createTempDirectory()
        val base = winEnv(home, registered = LegacyKeepAlive.taskXml)
        val env = object : OsEnv by base {
            override fun getenv(name: String): String? = if (name == "SUPERMUX_KEEP_ALIVE") "1" else null
        }
        val r = assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec, env, me))
        assertTrue(r.nextLogin)
        val script = elevatedCalls(base).single().last()
        assertTrue("RegisterTask(" in script && ".Run(" !in script, script)
        assertTrue(run !in base.ran && base.ran.none { it.firstOrNull() == "taskkill" })
        assertFalse(BrokerService.restart(env, me))
        base.ran.clear()
        BrokerService.remove(env, me)
        val removal = elevatedCalls(base).single().last()
        assertTrue("/Delete" in removal && "/End" !in removal, removal)
        assertTrue("System32\\schtasks.exe" in removal && "PSModulePath" in removal, removal)
    }

    @Test fun windowsDeclinedUacOverThe100TaskDoesNotRunTheOldApp() {
        val home = createTempDirectory()
        val env = winEnv(home, registered = LegacyKeepAlive.taskXml, failIf = { it.firstOrNull()?.endsWith("powershell.exe") == true && it.last().contains("-Verb RunAs") })
        val r = assertIs<BrokerService.Result.Failed>(BrokerService.install(winSpec, env, me))
        assertFalse(r.previousStillRunning, "the old task runs the app, not a broker")
        assertTrue(run !in env.ran)
    }

    @Test fun removeOnMacBootsOutAndDeletes() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501)
        BrokerService.install(spec, env, me)
        BrokerService.remove(env, me)
        assertFalse(Files.exists(home.resolve("Library/LaunchAgents/dev.supermux.host.plist")))
    }
    // ── whose definition is it? (#77) ─────────────────────────────────────────────────────────

    /** A copy of the app that adopted nothing from before install ids. */
    private val mine = BrokerService.Owner("aaaabbbbccccddddaaaabbbbccccdddd")
    private val theirs = BrokerService.Owner("0123456789abcdef0123456789abcdef")
    private fun stamped(s: BrokerService.Spec, o: BrokerService.Owner) = s.copy(env = s.env + (BrokerService.INSTALL_ENV to o.id))

    @Test fun everyInstalledDefinitionCarriesTheInstallersId() {
        assumePosixHost()
        val home = createTempDirectory()
        val mac = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501, failing = setOf(printHost))
        BrokerService.install(spec, mac, mine)
        assertTrue("<key>MUX_DESKTOP_INSTALL</key>\n    <string>${mine.id}</string>" in Files.readString(home.resolve("Library/LaunchAgents/dev.supermux.host.plist")))
        val linux = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        BrokerService.install(spec, linux, mine)
        assertTrue("Environment=\"MUX_DESKTOP_INSTALL=${mine.id}\"" in Files.readString(home.resolve(".config/systemd/user/supermux-host.service")))
        val xdg = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000)
        BrokerService.install(spec, xdg, mine)
        assertTrue("export MUX_DESKTOP_INSTALL='${mine.id}'" in Files.readString(home.resolve(".config/supermux/broker.env")))
        val win = winEnv(home)
        BrokerService.install(winSpec, win, me)
        assertTrue("\$env:MUX_DESKTOP_INSTALL = '${me.id}'" in Files.readString(win.localAppData.resolve("Supermux/broker-env.ps1")))
    }

    @Test fun macAnotherCopysServiceIsNeverReplacedRemovedOrRestarted() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501)
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist")
        Files.createDirectories(plist.parent)
        val text = BrokerService.launchdPlist(stamped(spec, theirs)).also { Files.writeString(plist, it) }
        assertEquals(BrokerService.Result.NotOurs(plist), BrokerService.install(spec, env, mine))
        assertEquals(BrokerService.Result.NotOurs(plist), BrokerService.remove(env, mine))
        assertFalse(BrokerService.restart(env, mine))
        assertFalse(BrokerService.isOursInstalled(env, mine))
        assertTrue(BrokerService.anotherCopysInstalled(env, mine))
        assertEquals(text, Files.readString(plist))
        assertEquals(emptyList(), env.ran, "no bootout, bootstrap or kickstart")
        // It is theirs to manage; and a takeover the user confirmed may replace it.
        assertTrue(BrokerService.isOursInstalled(env, theirs))
        assertIs<BrokerService.Result.Installed>(BrokerService.install(spec, env, mine, replaceOther = true))
        assertTrue(mine.id in Files.readString(plist))
    }

    @Test fun anUnstampedDefinitionIsOnlyTheOwnersWhoAdoptedIt() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501)
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist")
        Files.createDirectories(plist.parent); Files.writeString(plist, BrokerService.launchdPlist(spec))
        assertFalse(BrokerService.isOursInstalled(env, mine))
        assertTrue(BrokerService.anotherCopysInstalled(env, mine))
        assertTrue(BrokerService.isOursInstalled(env, mine.copy(ownsUnstamped = true)))
        // Not the app's at all (the retired Swift app's): nobody's to adopt.
        Files.writeString(plist, "<plist><dict><key>Label</key><string>dev.supermux.host</string></dict></plist>")
        assertFalse(BrokerService.isOursInstalled(env, mine.copy(ownsUnstamped = true)))
        assertFalse(BrokerService.anotherCopysInstalled(env, mine), "an old service is a takeover question, not another copy")
    }

    @Test fun linuxRemoveTakesOursAndLeavesAnotherCopysAutostart() {
        assumePosixHost()
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        BrokerService.install(spec, env, mine)
        val entry = home.resolve(".config/autostart/supermux-host.desktop")
        val envFile = home.resolve(".config/supermux/broker.env")
        BrokerService.writePrivate(envFile, BrokerService.xdgEnvFile(stamped(spec, theirs)))
        BrokerService.writePrivate(entry, BrokerService.xdgAutostart(stamped(spec, theirs), envFile))
        assertIs<BrokerService.Result.Removed>(BrokerService.remove(env, mine))
        assertFalse(Files.exists(home.resolve(".config/systemd/user/supermux-host.service")))
        assertTrue(Files.exists(entry) && Files.exists(envFile))
        assertEquals(BrokerService.Result.NotOurs(entry), BrokerService.remove(env, mine))
    }

    @Test fun linuxAnotherCopysUnitIsNeitherReplacedNorDisabled() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        val unit = home.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent)
        val text = BrokerService.systemdUnit(stamped(spec, theirs)).also { Files.writeString(unit, it) }
        assertEquals(BrokerService.Result.NotOurs(unit), BrokerService.install(spec, env, mine))
        assertEquals(BrokerService.Result.NotOurs(unit), BrokerService.remove(env, mine))
        assertFalse(BrokerService.restart(env, mine))
        assertEquals(text, Files.readString(unit))
        assertEquals(emptyList(), env.ran)
    }

    @Test fun linuxAnotherCopysAutostartBlocksInstallingAUnit() {
        // Both would start a broker for the same user at the next login.
        assumePosixHost()
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        val entry = home.resolve(".config/autostart/supermux-host.desktop")
        val envFile = home.resolve(".config/supermux/broker.env")
        BrokerService.writePrivate(envFile, BrokerService.xdgEnvFile(stamped(spec, theirs)))
        BrokerService.writePrivate(entry, BrokerService.xdgAutostart(stamped(spec, theirs), envFile))
        assertEquals(BrokerService.Result.NotOurs(entry), BrokerService.install(spec, env, mine))
        assertFalse(Files.exists(home.resolve(".config/systemd/user/supermux-host.service")))
        assertEquals(emptyList(), env.ran)
    }

    @Test fun unstampedRunsSeesTheStateDirAsEachLinuxFormatEscapesIt() {
        // A unit's ExecStart= and Environment= double backslashes and %, the XDG Exec= line escapes its own
        // way: a state dir whose path has them (every path on Windows, a rare one on Linux) is still this one.
        val state = Path.of("C:\\Users\\a 50%\\.mux\\state")
        val legacy = BrokerService.Spec(state.resolve("desktop-assets/bin/supermux-broker"), emptyMap(), state.resolve("desktop-broker.log"))
        val unitHome = createTempDirectory()
        val unitEnv = FakeOsEnv(os = DesktopOs.LINUX, home = unitHome, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        val unit = unitHome.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent)
        Files.writeString(unit, BrokerService.systemdUnit(legacy))
        assertTrue(BrokerService.unstampedRuns(unitEnv, state), Files.readString(unit))

        val xdgHome = createTempDirectory()
        val xdgEnv = FakeOsEnv(os = DesktopOs.LINUX, home = xdgHome, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        val envFile = xdgHome.resolve(".config/supermux/broker.env")
        val entry = xdgHome.resolve(".config/autostart/supermux-host.desktop")
        BrokerService.writePrivate(envFile, BrokerService.xdgEnvFile(legacy))
        BrokerService.writePrivate(entry, BrokerService.xdgAutostart(legacy, envFile))
        assertTrue(BrokerService.unstampedRuns(xdgEnv, state), Files.readString(entry))
        assertFalse(BrokerService.unstampedRuns(xdgEnv, Path.of("C:\\Users\\a 50%\\.mux\\other")), "another state dir's broker is not this one")
    }

    @Test fun anotherCopysPortIsReadFromItsDefinitionOnEveryOs() {
        // Where its broker runs, as each format writes MUX_WEB_PORT. A definition of ours is not another copy's.
        fun on9899(s: BrokerService.Spec) = stamped(s, theirs).let { it.copy(env = it.env + ("MUX_WEB_PORT" to "9899")) }
        val macHome = createTempDirectory()
        val mac = FakeOsEnv(os = DesktopOs.MACOS, home = macHome, uid = 501)
        val plist = macHome.resolve("Library/LaunchAgents/dev.supermux.host.plist")
        Files.createDirectories(plist.parent)
        Files.writeString(plist, BrokerService.launchdPlist(on9899(spec)))
        assertEquals(9899, BrokerService.anotherCopysPort(mac, mine))
        assertEquals(null, BrokerService.anotherCopysPort(mac, theirs))

        val unitHome = createTempDirectory()
        val linux = FakeOsEnv(os = DesktopOs.LINUX, home = unitHome, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        val unit = unitHome.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent)
        Files.writeString(unit, BrokerService.systemdUnit(on9899(spec)))
        assertEquals(9899, BrokerService.anotherCopysPort(linux, mine))

        val xdgHome = createTempDirectory()
        val xdg = FakeOsEnv(os = DesktopOs.LINUX, home = xdgHome, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        val envFile = xdgHome.resolve(".config/supermux/broker.env")
        BrokerService.writePrivate(envFile, BrokerService.xdgEnvFile(on9899(spec)))
        BrokerService.writePrivate(xdgHome.resolve(".config/autostart/supermux-host.desktop"), BrokerService.xdgAutostart(on9899(spec), envFile))
        assertEquals(9899, BrokerService.anotherCopysPort(xdg, mine))

        val winHome = createTempDirectory()
        val win = winEnv(winHome, registered = winXml(winHome))
        BrokerService.writePrivate(win.localAppData.resolve("Supermux/broker-env.ps1"), "\uFEFF" + BrokerService.windowsEnvFile(on9899(winSpec)))
        assertEquals(9899, BrokerService.anotherCopysPort(win, mine))
    }

    @Test fun windowsAnotherCopysTaskIsNeitherReregisteredEndedDeletedNorRestarted() {
        val home = createTempDirectory()
        val env = winEnv(home, registered = winXml(home))
        val envFile = env.localAppData.resolve("Supermux/broker-env.ps1")
        val text = "\uFEFF" + BrokerService.windowsEnvFile(stamped(winSpec, theirs))
        BrokerService.writePrivate(envFile, text)
        assertEquals(BrokerService.Result.NotOurs(BrokerService.WINDOWS_TASK_PATH), BrokerService.install(winSpec, env, mine))
        assertEquals(BrokerService.Result.NotOurs(BrokerService.WINDOWS_TASK_PATH), BrokerService.remove(env, mine))
        assertFalse(BrokerService.restart(env, mine))
        assertEquals(text, Files.readString(envFile), "its env stays as it is")
        assertEquals(emptyList(), elevatedCalls(env), "no UAC prompt")
        assertTrue(env.ran.none { it.firstOrNull() == "taskkill" } && run !in env.ran)
    }

    @Test fun anUnstampedDefinitionRunsTheBrokerOfTheStateDirItNames() {
        val home = createTempDirectory()
        val env = FakeOsEnv(os = DesktopOs.MACOS, home = home, uid = 501)
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist")
        Files.createDirectories(plist.parent); Files.writeString(plist, BrokerService.launchdPlist(spec))
        assertTrue(BrokerService.unstampedRuns(env, Path.of("/Users/a/.mux/state")))
        assertFalse(BrokerService.unstampedRuns(env, Path.of("/Users/a/.mux/stat")))
        Files.writeString(plist, BrokerService.launchdPlist(stamped(spec, theirs)))
        assertFalse(BrokerService.unstampedRuns(env, Path.of("/Users/a/.mux/state")), "stamped: nothing from before ids")
    }
}

/** Records argv instead of running it. Shared by later tests. */
class FakeOsEnv(
    override val os: DesktopOs,
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
    /** This process's env ([OsEnv.getenv]). */
    private val envVars: Map<String, String> = emptyMap(),
    /** `/proc/self/cgroup` ([OsEnv.selfCgroup]). */
    private val cgroup: String? = null,
) : OsEnv {
    override fun getenv(name: String): String? = envVars[name]
    override fun selfCgroup(): String? = cgroup
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
