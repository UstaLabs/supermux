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
        assertTrue("<Command>conhost.exe</Command>" in xml, "a headless conhost: no Windows Terminal window")
        assertTrue("<Arguments>--headless powershell.exe -NoLogo" in xml)
        assertTrue("\$env:MUX_WINDOWS_TASK = '1'" in xml, "the broker knows it runs under the loop")
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
        assertTrue("\$supermuxHost.HasExited) { break }" in xml, "an ended task's loop stops instead of respawning")
        assertTrue("-Filter ('ProcessId=' + \$PID)" in xml && "\$_.ToString()" in xml, "no double quotes of its own in the script")
        assertTrue("\$null = \$supermuxHost.Handle" in xml, "the parent's handle is pinned against pid reuse")
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

    @Test fun aDeclinedUacPromptIsAFailureNotASuccess() {
        val env = winEnv()
        BrokerService.install(winSpec, env)
        val script = elevatedCalls(env).single().last()
        // Start-Process's error is non-terminating: without -ErrorAction Stop + a null check,
        // `exit $process.ExitCode` would exit 0 after a "No".
        assertTrue("-ErrorAction Stop" in script && "catch { exit 1223 }" in script && "if (-not \$process) { exit 1223 }" in script, script)
    }

    @Test fun windowsInstallIsOneElevatedCallWithCreateAndRun() {
        val env = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = createTempDirectory())
        BrokerService.install(winSpec, env)
        val elevated = elevatedCalls(env)
        assertEquals(1, elevated.size)
        assertTrue("/Create" in elevated[0].last() && "/Run" in elevated[0].last())
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
        os = OsEnv.Os.WINDOWS, home = home,
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

    @Test fun windowsTaskTargetsFindOrphanedAndOldLoopsAndAChildModeBroker() {
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
            // the app's child-mode broker (bare, parent java)
            p(20, 30, "supermux-broker.exe", "C:\\s\\supermux-broker.exe"),
            p(21, 15, "supermux-broker.exe", "\"C:\\s\\supermux-broker.exe\" shim"),
        )
        val t = BrokerService.windowsTaskTargets(procs)
        assertEquals(listOf(10L, 12L), t.loops)
        assertEquals(listOf(20L), t.brokers)
    }

    @Test fun bareBrokerCommandLines() {
        assertTrue(BrokerService.isBareBrokerCommandLine("\"C:\\Program Files\\x\\supermux-broker.exe\""))
        assertTrue(BrokerService.isBareBrokerCommandLine("C:\\s\\supermux-broker.exe  "))
        assertFalse(BrokerService.isBareBrokerCommandLine("\"C:\\s\\supermux-broker.exe\" shim"))
        assertFalse(BrokerService.isBareBrokerCommandLine("C:\\s\\supermux-broker.exe credential get"))
        assertFalse(BrokerService.isBareBrokerCommandLine(""))
        assertFalse(BrokerService.isBareBrokerCommandLine("\"C:\\s\\other.exe\""))
    }

    @Test fun theProcessListerHasNoDoubleQuotes() {
        assertTrue('"' !in listing.last(), "Java does not escape double quotes on Windows")
    }

    @Test fun windowsRemoveEndsAndDeletesElevatedThenStopsLoopAndBrokerByPid() {
        val env = winEnv()
        BrokerService.install(winSpec, env)
        env.ran.clear()
        val tasklist = listOf("tasklist", "/FI", "PID eq 300", "/NH")
        var polls = 0
        val waiting = object : OsEnv by env {
            override fun runCapture(argv: List<String>): String? {
                if (argv == tasklist) { env.ran += argv; return if (polls++ < 2) "supermux-broker.exe   300 Console   1  150,000 K" else "INFO: No tasks" }
                return env.runCapture(argv)
            }
        }
        assertIs<BrokerService.Result.Removed>(BrokerService.remove(waiting))
        val elevated = elevatedCalls(env).single()
        assertTrue("/End" in elevated.last() && elevated.last().indexOf("/End") < elevated.last().indexOf("/Delete"))
        assertTrue(env.ran.indexOf(elevated) < env.ran.indexOf(killLoop))
        assertTrue(env.ran.indexOf(killLoop) < env.ran.indexOf(killBroker), "the loop goes first, or it respawns the broker")
        assertEquals(3, env.ran.count { it == tasklist }, "polls the broker's pid until it's gone")
        env.neverKilledByImage()
        env.neverKilled(400); env.neverKilled(401)
    }

    private fun elevatedCalls(env: FakeOsEnv) = env.ran.filter { it.firstOrNull() == "powershell.exe" && it.last().contains("-Verb RunAs") }

    @Test fun windowsReinstallOfTheSameDefinitionRestartsWithoutUac() {
        val env = winEnv(registered = BrokerService.windowsTaskXml(winSpec))
        assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec, env))
        env.ran.clear()
        val again = assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec, env))
        assertTrue(again.enabled)
        assertEquals(emptyList(), elevatedCalls(env), "an app update with the same task definition needs no UAC prompt")
        assertTrue(env.ran.indexOf(killLoop) in 0 until env.ran.indexOf(killBroker))
        assertTrue(env.ran.indexOf(killBroker) < env.ran.indexOf(run), "the old broker is gone before the task starts again")
        env.neverKilledByImage()
    }

    @Test fun windowsInstallAfterTheCallerStoppedTheServiceDoesNotStopItAgain() {
        val env = winEnv(registered = BrokerService.windowsTaskXml(winSpec))
        BrokerService.install(winSpec, env)
        env.ran.clear()
        assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec, env, alreadyStopped = true))
        assertTrue(listing !in env.ran && env.ran.none { it.firstOrNull() == "taskkill" })
        assertTrue(run in env.ran)
    }

    @Test fun theRegisteredDefinitionDecidesNotOurFile() {
        // Our file says the new definition (a prompt was declined after it was written); the task
        // still runs the old one: that is a change, so it must ask again.
        val home = createTempDirectory()
        BrokerService.install(winSpec, winEnv(home, registered = null))
        val old = BrokerService.windowsTaskXml(winSpec.copy(env = mapOf("MUX_WEB_PORT" to "8787")))
        val env = winEnv(home, registered = old)
        BrokerService.install(winSpec, env)
        assertEquals(1, elevatedCalls(env).size)
        // schtasks escapes differently from us: the comparison is on the unescaped values.
        val ours = BrokerService.windowsTaskXml(winSpec)
        assertTrue(BrokerService.sameTaskAction(ours.replace("\"", "&quot;").replace("'", "&apos;"), ours))
        assertFalse(BrokerService.sameTaskAction(old, ours))
        assertFalse(BrokerService.sameTaskAction(null, ours))
    }

    @Test fun windowsChangedDefinitionStopsTheOldBrokerThenElevatesOnce() {
        val env = winEnv()
        BrokerService.install(winSpec, env)
        env.ran.clear()
        assertIs<BrokerService.Result.Installed>(BrokerService.install(winSpec.copy(env = mapOf("MUX_WEB_PORT" to "8787")), env))
        val elevated = elevatedCalls(env)
        assertEquals(1, elevated.size)
        assertTrue(env.ran.indexOf(killBroker) in 0 until env.ran.indexOf(elevated[0]), "re-registering must not leave the old loop running")
    }

    @Test fun windowsDeclinedUacOnAChangedDefinitionStartsThePreviousOneAgain() {
        val home = createTempDirectory()
        BrokerService.install(winSpec, winEnv(home))
        val env = winEnv(home, failIf = { it.firstOrNull() == "powershell.exe" && it.last().contains("-Verb RunAs") })
        val r = assertIs<BrokerService.Result.Failed>(BrokerService.install(winSpec.copy(env = mapOf("MUX_WEB_PORT" to "8787")), env))
        assertTrue(r.previousStillRunning)
        assertEquals(BrokerService.WINDOWS_UPDATE_DECLINED, r.message)
        assertEquals(1, elevatedCalls(env).size)
        assertTrue(env.ran.indexOf(elevatedCalls(env)[0]) < env.ran.lastIndexOf(run), "the old definition's broker runs again")
        assertFalse(Files.exists(env.localAppData.resolve("Supermux/supermux-host-task.xml")), "the file must not claim the new definition")
    }

    @Test fun windowsDeclinedUacOnAFirstInstallIsAPlainFailure() {
        val q = listOf("schtasks", "/Query", "/TN", "Supermux Host")
        val env = FakeOsEnv(os = OsEnv.Os.WINDOWS, home = createTempDirectory(),
            scripted = mapOf(q to listOf(OsEnv.RunResult(1, "", "not found"))),
            failIf = { it.firstOrNull() == "powershell.exe" && it.last().contains("-Verb RunAs") })
        val r = assertIs<BrokerService.Result.Failed>(BrokerService.install(winSpec, env))
        assertFalse(r.previousStillRunning)
        assertTrue(run !in env.ran)
    }

    @Test fun stopIsWindowsOnlyAndKillsByPid() {
        val mac = FakeOsEnv(os = OsEnv.Os.MAC, home = createTempDirectory(), uid = 501)
        assertFalse(BrokerService.stop(mac))
        assertEquals(emptyList(), mac.ran)
        val win = winEnv()
        assertTrue(BrokerService.stop(win))
        assertTrue(killLoop in win.ran && killBroker in win.ran)
        win.neverKilledByImage()
        win.neverKilled(400); win.neverKilled(401)
        assertTrue(win.ran.none { it.firstOrNull() == "schtasks" }, "stop leaves the task registered and not started")
    }

    @Test fun stopKillsNothingWhenItCannotListProcesses() {
        val win = winEnv(procs = null)
        assertFalse(BrokerService.stop(win))
        assertTrue(win.ran.none { it.firstOrNull() == "taskkill" })
    }

    @Test fun windowsTaskReplacesALingeringInstanceOnRun() {
        assertTrue("<MultipleInstancesPolicy>StopExisting</MultipleInstancesPolicy>" in BrokerService.windowsTaskXml(winSpec))
    }

    @Test fun windowsRemoveFailsWhenTheElevatedBatchFails() {
        val home = createTempDirectory()
        val installEnv = winEnv(home)
        BrokerService.install(winSpec, installEnv)
        val taskXml = installEnv.localAppData.resolve("Supermux/supermux-host-task.xml")
        val env = winEnv(home, failIf = { it.firstOrNull() == "powershell.exe" && it.last().contains("-Verb RunAs") })
        assertIs<BrokerService.Result.Failed>(BrokerService.remove(env))
        assertTrue(env.ran.none { it.firstOrNull() == "taskkill" })
        assertTrue(Files.exists(taskXml))
    }

    @Test fun windowsRestartStopsTheLoopAndBrokerThenRunsWithoutElevation() {
        val env = winEnv()
        assertTrue(BrokerService.restart(env))
        assertTrue(env.ran.indexOf(killLoop) in 0 until env.ran.indexOf(killBroker))
        assertTrue(env.ran.indexOf(killBroker) < env.ran.indexOf(run))
        assertTrue(env.ran.none { it.last().contains("-Verb RunAs") })
        env.neverKilledByImage()
        env.neverKilled(400); env.neverKilled(401)
    }

    @Test fun windowsRestartStartsAnEndedTaskEvenWithNothingRunning() {
        val env = winEnv(procs = "")
        assertTrue(BrokerService.restart(env), "the loop was gone: /Run brings it back")
        assertTrue(env.ran.none { it.firstOrNull() == "taskkill" })
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
