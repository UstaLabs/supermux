package dev.supermux.desktop.host

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BUNDLED = "1.5.0 (abc1234)"

/** A broker child that exits when told to (or when destroyed). */
class FakeChild(override val pid: Long? = 4242, override val startMillis: Long? = 1_000) : ChildHandle {
    private val done = CompletableFuture<Int>()
    var destroyed = 0
    var forced = 0
    override val isAlive: Boolean get() = !done.isDone
    override val exitCode: Int? get() = if (done.isDone) done.get() else null
    override fun destroy() { destroyed++; done.complete(143) }
    override fun destroyForcibly() { forced++; done.complete(137) }
    override fun onExit(): CompletableFuture<*> = done.thenApply { it }
    fun exit(code: Int = 1) { done.complete(code) }
}

/** A fake OS process table: pid -> (info, handle). */
class FakeProcessTable : ProcessTable {
    val procs = mutableMapOf<Long, Pair<ProcInfo, ChildHandle?>>()
    override fun info(pid: Long): ProcInfo? = procs[pid]?.takeIf { it.second?.isAlive != false }?.first
    override fun handle(pid: Long): ChildHandle? = procs[pid]?.second?.takeIf { it.isAlive }
}

/** One supervisor over fakes: a scripted probe, recorded prefs saves, fake children and a FakeOsEnv. */
private class Harness(
    val ts: TestScope,
    os: OsEnv.Os = OsEnv.Os.MAC,
    prefs: HostingPrefs = HostingPrefs(background = false),
    failing: Set<List<String>> = emptySet(),
    commands: Set<String> = setOf("launchctl", "systemctl", "loginctl", "schtasks", "powershell.exe"),
    scripted: Map<List<String>, List<OsEnv.RunResult>> = emptyMap(),
    failIf: (List<String>) -> Boolean = { false },
    captures: Map<List<String>, String> = emptyMap(),
    envVars: Map<String, String> = emptyMap(),
    cgroup: String? = null,
    val repo: Path? = null,
    xdg: String? = null,
    val home: Path = createTempDirectory("sup-home"),
    val state: Path = createTempDirectory("sup-state"),
) {
    val env = FakeOsEnv(os = os, home = home, uid = 501, xdgRuntimeDir = xdg, commands = commands, failing = failing, scripted = scripted, failIf = failIf, captures = captures, envVars = envVars, cgroup = cgroup)
    val events = mutableListOf<String>()
    var saved = prefs
    val children = mutableListOf<FakeChild>()
    val launches = mutableListOf<ChildLaunch>()
    var probeFn: (Int) -> HostProbeResult = { HostProbeResult.PortFree }
    var bins = HostBinaries.SidecarBinaries(
        brokerPath = state.resolve("desktop-assets/bin/supermux-broker"),
        binDir = state.resolve("desktop-assets/bin"),
        sessiondPath = null, frpcPath = null, tmuxPath = null, zmxDir = null,
    )
    val table = FakeProcessTable()
    var baseEnv: Map<String, String> = mapOf("HOME" to home.toString())
    var packaged = true
    /** The launch-time read of the bundled build, and the background re-read. */
    var bundledFn: suspend () -> String? = { BUNDLED }
    var lateBundledFn: suspend () -> String? = { BUNDLED }
    var lateReads = 0
    var killedReads = 0
    /** Runs inside startChild, before the child is returned (e.g. to cancel the caller). */
    var onStart: () -> Unit = {}
    /** The next children start already dead (they never become healthy). */
    var deadOnArrival = false
    val statuses = mutableListOf<HostingStatus>()
    val ourPlist: Path = home.resolve("Library/LaunchAgents/dev.supermux.host.plist")

    val sup = HostSupervisor(
        stateDir = state,
        loadPrefs = { saved },
        savePrefs = { events += "save:${it.port}"; saved = it },
        probe = { port -> probeFn(port) },
        osEnv = env,
        materialize = { events += "materialize"; bins },
        packaged = { packaged },
        bundledBuild = { bundledFn() },
        lateBundledBuild = { lateReads++; lateBundledFn() },
        killBundledReads = { killedReads++ },
        startChild = { l ->
            events += "startChild"
            launches += l
            onStart()
            FakeChild(pid = 5000L + children.size).also { if (deadOnArrival) it.exit(1); children += it }
        },
        processes = table,
        baseEnv = { baseEnv },
        repoDir = { repo },
        bunPath = { "bun" },
        hostName = "testbox",
        freePort = { 45678 },
        now = { ts.testScheduler.currentTime },
        io = StandardTestDispatcher(ts.testScheduler),
        scope = ts.backgroundScope,
        existingPath = "/usr/bin:/bin",
        userHome = home.toString(),
        log = { events += "log:$it" },
    )

    init {
        ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) { sup.status.collect { statuses += it } }
    }

    val liveChild: FakeChild? get() = children.lastOrNull()?.takeIf { it.isAlive }
    fun desktop(id: String = "h-own", build: String = BUNDLED) =
        HostProbeResult.Supermux(id, build, "binary", "desktop", state.toString())
    fun outside(id: String = "h1", build: String = BUNDLED) =
        HostProbeResult.Supermux(id, build, "binary", null, state.toString())
    fun healthyIfChild(): (Int) -> HostProbeResult = { if (liveChild != null) desktop() else HostProbeResult.PortFree }
    fun bootstrapped(plist: Path = ourPlist) = env.ran.any { it == listOf("launchctl", "bootstrap", "gui/501", plist.toString()) }
    fun ourPids() = children.map { it.pid }
    fun writeOurPlist() {
        Files.createDirectories(ourPlist.parent)
        Files.writeString(ourPlist, BrokerService.launchdPlist(BrokerService.Spec(bins.brokerPath!!, emptyMap(), state.resolve("l.log"))))
    }
}

class HostSupervisorTest {
    private fun res(name: String) = String(javaClass.getResourceAsStream("/takeover/$name")!!.readAllBytes(), Charsets.UTF_8)
    private val running = HostingStatus.Running(9898, readOnly = false)

    // 1
    @Test fun childModeStartsTheBrokerAsAChildWithTheManagedEnv() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertEquals(1, h.launches.size)
        val l = h.launches.single()
        assertEquals(listOf(h.bins.brokerPath.toString()), l.argv)
        assertEquals("desktop", l.env["MUX_MANAGED_BY"])
        assertEquals("9898", l.env["MUX_WEB_PORT"])
        assertEquals("relay.supermux.dev", l.env["MUX_RELAY_DOMAIN"])
        assertEquals(h.state.resolve("desktop-broker.log"), l.log)
        assertEquals(running, h.sup.status.value)
        assertEquals("h-own", h.sup.hostId.value)
        assertEquals("http://127.0.0.1:9898", h.sup.localBaseUrl)
        assertTrue(h.env.ran.isEmpty())
        assertEquals("5000:1000", Files.readString(h.state.resolve("desktop-broker.pid")))
    }

    // 2
    @Test fun backgroundModeBootstrapsTheServiceAndStartsNoChild() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.probeFn = { if (h.bootstrapped()) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        assertTrue(h.bootstrapped())
        assertTrue(h.launches.isEmpty())
        assertTrue(BrokerService.MANAGED_MARKER in Files.readString(h.ourPlist))
        assertEquals(running, h.sup.status.value)
    }

    // 3
    @Test fun foreignProcessSavesTheNewPortBeforeStartingTheChild() = runTest {
        val h = Harness(this)
        h.probeFn = { port -> if (port == 9898) HostProbeResult.ForeignProcess else h.healthyIfChild()(port) }
        h.sup.ensure()
        val save = h.events.indexOf("save:45678")
        assertTrue(save >= 0 && save < h.events.indexOf("startChild"), h.events.toString())
        assertEquals(45678, h.saved.port)
        assertEquals("45678", h.launches.single().env["MUX_WEB_PORT"])
        assertEquals(HostingStatus.Running(45678, false), h.sup.status.value)
        assertEquals("http://127.0.0.1:45678", h.sup.localBaseUrl)
    }

    // 4
    @Test fun ownServiceOnAnOldBuildIsMaterializedAndReinstalled() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        h.probeFn = { if (h.bootstrapped()) h.desktop() else h.desktop(build = "1.4.0 (old)") }
        h.sup.ensure()
        assertTrue("materialize" in h.events)
        assertTrue(h.bootstrapped())
        assertTrue(h.launches.isEmpty())
        assertEquals(running, h.sup.status.value)
    }

    @Test fun buildIsPublishedForAStartedAndAnAdoptedBroker() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        assertNull(h.sup.build.value)
        h.sup.ensure()
        assertEquals(BUNDLED, h.sup.build.value)
        // UseOwn: our service already on the bundled build is adopted as-is and reports its build.
        val h2 = Harness(this, prefs = HostingPrefs(background = true))
        h2.writeOurPlist()
        h2.probeFn = { h2.desktop() }
        h2.sup.ensure()
        assertEquals(BUNDLED, h2.sup.build.value)
        assertTrue(h2.launches.isEmpty())
    }

    @Test fun ownServiceOnTheBundledBuildIsUsedAsIs() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        assertTrue(h.env.ran.isEmpty())
        assertFalse("materialize" in h.events)
        assertEquals(running, h.sup.status.value)
    }

    // 5
    @Test fun leaveItAloneRemembersTheHostAndAdoptsReadOnly() = runTest {
        val h = Harness(this)
        h.probeFn = { h.outside("h1") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(HostingStatus.AskTakeover("h1"), h.sup.status.value)
        h.sup.answerTakeover(false)
        job.join()
        assertTrue("h1" in h.saved.leftAloneHostIds)
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
        assertEquals("h1", h.sup.hostId.value)
        assertTrue(h.launches.isEmpty() && h.env.ran.isEmpty())
    }

    @Test fun keepingANewerBrokerAdoptsItReadOnly() = runTest {
        val h = Harness(this)
        h.probeFn = { h.outside("h1", build = "9.0.0 (new)") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(HostingStatus.AskDowngrade("h1"), h.sup.status.value)
        h.sup.answerDowngrade(false)
        job.join()
        assertTrue("h1" in h.saved.leftAloneHostIds)
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
    }

    // 6
    @Test fun takeoverBacksUpCarriesTheRelayInstallsOursAndCommits() = runTest {
        val h = Harness(
            this,
            prefs = HostingPrefs(background = true, relay = false),
            failing = setOf(listOf("launchctl", "print", "gui/501/dev.supermux.host")),
        )
        Files.createDirectories(h.ourPlist.parent)
        Files.writeString(h.ourPlist, res("mac-native-host.plist"))
        h.probeFn = { if (h.bootstrapped()) h.desktop("h1") else h.outside("h1") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(HostingStatus.AskTakeover("h1"), h.sup.status.value)
        h.sup.answerTakeover(true)
        job.join()

        val backups = Files.list(h.state.resolve("takeover-backup")).use { s -> s.map { it.fileName.toString() }.toList() }
        assertTrue(backups.any { it.startsWith("dev.supermux.host.plist.") }, backups.toString())
        assertFalse(Files.exists(h.state.resolve("takeover-backup/pending.json")))
        val plist = Files.readString(h.ourPlist)
        assertTrue(BrokerService.MANAGED_MARKER in plist)
        assertTrue("<key>MUX_RELAY_DOMAIN</key>\n    <string>relay.supermux.dev</string>" in plist, plist)
        assertTrue("<string>testbox</string>" in plist) // ours wins over the carried MUX_HOST_NAME
        assertTrue(h.saved.relay)
        assertEquals(running, h.sup.status.value)
        assertTrue(h.launches.isEmpty())
        assertTrue(Files.exists(h.state.resolve("desktop-carried-env.json")))
        assertFalse(Files.exists(h.state.resolve("desktop-carried-env.pending.json")))
    }

    // 7
    @Test fun takeoverWhoseInstallFailsRollsBackTheOldService() = runTest {
        val home = createTempDirectory("sup-home")
        val oldPlist = home.resolve("Library/LaunchAgents/dev.supermux.broker.plist")
        val ours = home.resolve("Library/LaunchAgents/dev.supermux.host.plist")
        val h = Harness(
            this,
            home = home,
            prefs = HostingPrefs(background = true),
            failing = setOf(
                listOf("launchctl", "print", "gui/501/dev.supermux.broker"),
                listOf("launchctl", "bootstrap", "gui/501", ours.toString()),
            ),
        )
        Files.createDirectories(oldPlist.parent)
        val oldText = res("mac-native-host.plist").replace("<string>dev.supermux.host</string>", "<string>dev.supermux.broker</string>")
        Files.writeString(oldPlist, oldText)
        h.probeFn = { h.outside("h1") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        h.sup.answerTakeover(true)
        job.join()

        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue(s.reason.startsWith("Couldn't take over:"), s.reason)
        assertEquals(oldText, Files.readString(oldPlist))
        assertTrue(h.bootstrapped(oldPlist))
        assertFalse(Files.exists(ours))
        assertTrue(h.launches.isEmpty())
        assertFalse(Files.exists(h.state.resolve("takeover-backup/pending.json")))
    }

    @Test fun takeoverWhosePrepareFailsInstallsNothing() = runTest {
        val home = createTempDirectory("sup-home")
        val oldPlist = home.resolve("Library/LaunchAgents/dev.supermux.broker.plist")
        // `launchctl print` keeps succeeding: the old service never stops.
        val h = Harness(this, home = home, prefs = HostingPrefs(background = true, relay = false))
        Files.createDirectories(oldPlist.parent)
        Files.writeString(oldPlist, res("mac-native-host.plist").replace("<string>dev.supermux.host</string>", "<string>dev.supermux.broker</string>"))
        h.probeFn = { h.outside("h1") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        h.sup.answerTakeover(true)
        job.join()

        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue("did not stop" in s.reason, s.reason)
        assertFalse(Files.exists(h.ourPlist))
        assertFalse(h.bootstrapped())
        assertFalse(h.saved.relay) // prefs untouched
        assertTrue(h.launches.isEmpty())
    }

    @Test fun takeoverWithNoOldServiceSaysToQuitIt() = runTest {
        val h = Harness(this)
        h.probeFn = { h.outside("h1") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        h.sup.answerTakeover(true)
        job.join()
        assertEquals(
            HostingStatus.CantStart("supermux is already running on port 9898 but wasn't started by a service. Quit it, then try again."),
            h.sup.status.value,
        )
        assertTrue(h.env.ran.isEmpty() && h.launches.isEmpty())
    }

    // 8
    @Test fun childExitsAreRestartedWithBackoff() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.children[0].exit(1)
        advanceTimeBy(20_000)
        h.children[1].exit(1)
        advanceTimeBy(20_000)
        assertEquals(
            listOf(HostingStatus.Starting, running, HostingStatus.Restarting(1), running, HostingStatus.Restarting(2), running),
            h.statuses,
        )
        assertEquals(3, h.launches.size)
    }

    @Test fun backoffWaitsBeforeRespawning() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.children[0].exit(1)
        advanceTimeBy(900)
        assertEquals(HostingStatus.Restarting(1), h.sup.status.value)
        assertEquals(1, h.launches.size)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, h.launches.size)
    }

    @Test fun fiveQuickExitsIsACrashLoop() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("desktop-broker.log"), "starting\nerror: EADDRINUSE 9898\n")
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        repeat(5) {
            h.liveChild!!.exit(1)
            advanceTimeBy(19_000)
        }
        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue("EADDRINUSE" in s.reason, s.reason)
        assertEquals(5, h.launches.size)
        advanceTimeBy(60_000)
        assertEquals(5, h.launches.size) // no infinite respawn
    }

    @Test fun aChildThatCrashesTwentyFiveSecondsAfterEveryStartIsCapped() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        // Too slow to fill the 120 s window with more than 5 exits, too short to count as healthy.
        repeat(5) {
            advanceTimeBy(25_000)
            h.liveChild!!.exit(1)
        }
        advanceTimeBy(1_000)
        assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertEquals(5, h.launches.size)
        advanceTimeBy(120_000)
        assertEquals(5, h.launches.size)
    }

    // 9
    @Test fun readOnlyBrokerGoingDownIsCantStartAndNeverRestarted() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true, leftAloneHostIds = setOf("h1")))
        h.probeFn = { h.outside("h1") }
        h.sup.ensure()
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
        h.probeFn = { HostProbeResult.PortFree }
        advanceTimeBy(30_000)
        assertEquals(HostingStatus.CantStart("The broker set up outside the app stopped."), h.sup.status.value)
        assertTrue(h.launches.isEmpty())
        assertTrue(h.env.ran.isEmpty())
    }

    // 10
    @Test fun quitStopsTheChildInChildMode() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertTrue(h.sup.quitStopsBroker)
        h.sup.quit()
        h.sup.quit() // idempotent: the finally and the shutdown hook call it again
        assertEquals(1, h.children[0].destroyed)
        assertFalse(h.children[0].isAlive)
        assertFalse(h.sup.quitStopsBroker)
        assertFalse(Files.exists(h.state.resolve("desktop-broker.pid")))
        advanceTimeBy(30_000)
        assertEquals(1, h.launches.size) // the watch loop doesn't respawn after quit
    }

    @Test fun quitInServiceModeMakesNoOsCalls() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.probeFn = { if (h.bootstrapped()) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        val before = h.env.ran.size
        assertFalse(h.sup.quitStopsBroker)
        h.sup.quit()
        assertEquals(before, h.env.ran.size)
    }

    // 11
    @Test fun brokerEnvPutsCarriedUnderOurs() {
        val bins = HostBinaries.SidecarBinaries(
            brokerPath = Path.of("/s/bin/supermux-broker"), binDir = Path.of("/s/bin"),
            sessiondPath = null, frpcPath = null, tmuxPath = null, zmxDir = Path.of("/s/zmx"),
        )
        val env = brokerEnv(
            HostingPrefs(port = 9898, relay = false), bins,
            carried = mapOf("MUX_WEB_PORT" to "1", "MUX_TELEGRAM_BOT_TOKEN" to "1:a", "MUX_WEB_PUBLIC_URL" to "https://me.example"),
            stateDir = Path.of("/s/state"), hostName = "box", existingPath = "/usr/bin:/bin", home = "/Users/a", os = OsEnv.Os.MAC,
        )
        assertEquals("9898", env["MUX_WEB_PORT"])
        assertEquals("1:a", env["MUX_TELEGRAM_BOT_TOKEN"])
        assertEquals("https://me.example", env["MUX_WEB_PUBLIC_URL"])
        assertEquals("desktop", env["MUX_MANAGED_BY"])
        assertEquals("box", env["MUX_HOST_NAME"])
        assertEquals("", env["MUX_RELAY_DOMAIN"])
        assertEquals("/s/zmx", env["MUX_ZMX_BIN_DIR"])
        assertEquals("/s/bin:/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin:/Users/a/.local/bin", env["PATH"])
        assertNull(env["MUX_SESSIOND_PATH"])
        assertEquals(Path.of("/s/state").toString(), env["MUX_STATE_DIR"])
    }

    @Test fun brokerEnvWithoutCarriedUsesTheLoopbackPublicUrlAndDefaultsThePath() {
        val bins = HostBinaries.SidecarBinaries(null, null, null, null, null, null)
        val env = brokerEnv(HostingPrefs(port = 9912), bins, emptyMap(), Path.of("/s"), "box", existingPath = null, home = "/h", os = OsEnv.Os.LINUX)
        // The broker's web channel needs MUX_WEB_PORT and MUX_WEB_PUBLIC_URL together (web_env_invalid).
        assertEquals("http://127.0.0.1:9912", env["MUX_WEB_PUBLIC_URL"])
        assertEquals("relay.supermux.dev", env["MUX_RELAY_DOMAIN"])
        assertEquals("/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin:/h/.local/bin", env["PATH"])
    }

    @Test fun brokerEnvOnWindowsCarriesSessiondAndUsesSemicolons() {
        val bin = Path.of("C:/s/bin")
        val bins = HostBinaries.SidecarBinaries(bin.resolve("supermux-broker.exe"), bin, bin.resolve("mux-sessiond.exe"), null, null, null)
        val env = brokerEnv(HostingPrefs(), bins, emptyMap(), Path.of("C:/s"), "win", existingPath = "C:/Windows", home = "C:/u", os = OsEnv.Os.WINDOWS)
        assertEquals(bin.resolve("mux-sessiond.exe").toString(), env["MUX_SESSIOND_PATH"])
        assertEquals("$bin;C:/Windows", env["PATH"])
    }

    // ── Wait ──

    @Test fun busyPortIsReprobedUntilItFrees() = runTest {
        val h = Harness(this)
        var n = 0
        h.probeFn = { port -> if (n++ < 2) HostProbeResult.Busy else h.healthyIfChild()(port) }
        h.sup.ensure()
        assertEquals("9898", h.launches.single().env["MUX_WEB_PORT"])
        assertTrue(h.events.none { it.startsWith("save:") })
        assertEquals(4_000L, testScheduler.currentTime)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun busyPortForThirtySecondsMovesThePort() = runTest {
        val h = Harness(this)
        h.probeFn = { port -> if (port == 9898) HostProbeResult.Busy else h.healthyIfChild()(port) }
        h.sup.ensure()
        assertTrue(testScheduler.currentTime >= 30_000)
        assertEquals(45678, h.saved.port)
        assertEquals("45678", h.launches.single().env["MUX_WEB_PORT"])
        assertEquals(HostingStatus.Running(45678, false), h.sup.status.value)
    }

    // ── dev checkout, XDG fallback ──

    @Test fun devCheckoutFallsBackToABunChildInBackgroundMode() = runTest {
        val repo = createTempDirectory("repo")
        val h = Harness(this, prefs = HostingPrefs(background = true), repo = repo)
        h.bins = HostBinaries.SidecarBinaries(null, null, null, null, null, null)
        h.packaged = false
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        val l = h.launches.single()
        assertEquals(listOf("bun", repo.resolve("src/main.ts").toString()), l.argv)
        assertEquals(repo, l.workDir)
        assertTrue(h.env.ran.isEmpty())
        assertTrue(h.events.any { it.startsWith("log:") && "packaged" in it })
        assertEquals(HostSupervisor.DEV_BACKGROUND, h.sup.backgroundError.value)
        assertEquals(running, h.sup.status.value)
        assertTrue(h.saved.background)
    }

    @Test fun linuxXdgFallbackAlsoRunsTheBrokerNowAndLeavesItRunningOnQuit() = runTest {
        val h = Harness(this, os = OsEnv.Os.LINUX, prefs = HostingPrefs(background = true), commands = emptySet())
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertTrue(Files.exists(h.home.resolve(".config/autostart/supermux-host.desktop")))
        assertEquals(1, h.launches.size)
        assertTrue(h.saved.background)
        assertEquals(running, h.sup.status.value)
        assertFalse(h.sup.quitStopsBroker) // the XDG stand-in keeps running
        h.sup.quit()
        assertTrue(h.children[0].isAlive)
    }

    @Test fun linuxXdgBackgroundOffRemovesTheAutostart() = runTest {
        val h = Harness(this, os = OsEnv.Os.LINUX, prefs = HostingPrefs(background = true), commands = emptySet())
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        val autostart = h.home.resolve(".config/autostart/supermux-host.desktop")
        assertTrue(Files.exists(autostart))
        h.sup.setBackground(false)
        assertFalse(Files.exists(autostart), "it would start a broker at the next login")
        assertFalse(Files.exists(h.home.resolve(".config/supermux/broker.env")))
        assertFalse(h.saved.background)
        assertEquals(running, h.sup.status.value)
        assertTrue(h.sup.quitStopsBroker, "now an ordinary child")
    }

    @Test fun linuxXdgHostingOffRemovesTheAutostart() = runTest {
        val h = Harness(this, os = OsEnv.Os.LINUX, prefs = HostingPrefs(background = true), commands = emptySet())
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.sup.setHosting(false)
        assertFalse(Files.exists(h.home.resolve(".config/autostart/supermux-host.desktop")))
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
        assertTrue(h.children.none { it.isAlive })
    }

    @Test fun failedInstallFallsBackToAChildAndSaysSo() = runTest {
        val home = createTempDirectory("sup-home")
        val bootstrap = listOf("launchctl", "bootstrap", "gui/501", home.resolve("Library/LaunchAgents/dev.supermux.host.plist").toString())
        val h = Harness(this, home = home, prefs = HostingPrefs(background = true), failing = setOf(bootstrap))
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertEquals(1, h.launches.size)
        assertEquals(running, h.sup.status.value)
        assertTrue(h.sup.backgroundError.value!!.startsWith("Couldn't keep supermux running in the background:"))
        assertTrue(h.saved.background)
        assertFalse(Files.exists(h.ourPlist)) // removed before the child fallback
        assertTrue(h.sup.quitStopsBroker) // background ON, but quitting stops this child
    }

    // ── setters ──

    @Test fun setRelayRestartsTheChildWithTheNewEnv() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.sup.setRelay(false)
        assertFalse(h.saved.relay)
        assertEquals(1, h.children[0].destroyed)
        assertEquals(2, h.launches.size)
        assertEquals("", h.launches[1].env["MUX_RELAY_DOMAIN"])
        assertEquals(running, h.sup.status.value)
    }

    @Test fun setRelayReinstallsTheServiceWithTheNewEnv() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.probeFn = { if (h.bootstrapped()) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        h.sup.setRelay(false)
        assertTrue("<key>MUX_RELAY_DOMAIN</key>\n    <string></string>" in Files.readString(h.ourPlist))
        assertEquals(2, h.env.ran.count { it.getOrNull(1) == "bootstrap" })
        assertEquals(running, h.sup.status.value)
    }

    @Test fun setBackgroundOnStopsTheChildAndInstallsTheService() = runTest {
        val h = Harness(this)
        h.probeFn = { if (h.bootstrapped() || h.liveChild != null) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        h.sup.setBackground(true)
        assertTrue(h.saved.background)
        assertEquals(1, h.children[0].destroyed)
        assertTrue(h.bootstrapped())
        assertEquals(1, h.launches.size)
        assertEquals(running, h.sup.status.value)
        h.sup.quit()
        assertEquals(1, h.children[0].destroyed)
    }

    @Test fun setBackgroundOffRemovesTheServiceAndStartsAChild() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.probeFn = { if (h.bootstrapped() || h.liveChild != null) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        h.sup.setBackground(false)
        assertFalse(Files.exists(h.ourPlist))
        assertTrue(listOf("launchctl", "bootout", "gui/501/dev.supermux.host") in h.env.ran)
        assertEquals(1, h.launches.size)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun setHostingOffStopsEverything() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.sup.setHosting(false)
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
        assertFalse(h.children[0].isAlive)
        assertFalse(h.saved.hosting)
        advanceTimeBy(30_000)
        assertEquals(1, h.launches.size)
    }

    @Test fun hostingOffWhileAskingAbandonsTheQuestion() = runTest {
        val h = Harness(this)
        h.probeFn = { h.outside("h1") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        h.sup.setHosting(false)
        job.join()
        h.sup.answerTakeover(true) // too late: nothing to act on
        runCurrent()
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
        assertTrue(h.env.ran.isEmpty() && h.launches.isEmpty())
    }

    @Test fun forgetLeftAloneAsksAgain() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = false, leftAloneHostIds = setOf("h1")))
        h.probeFn = { h.outside("h1") }
        h.sup.ensure()
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
        launch { h.sup.forgetLeftAlone("h1") }
        runCurrent()
        assertFalse("h1" in h.saved.leftAloneHostIds)
        assertEquals(HostingStatus.AskTakeover("h1"), h.sup.status.value)
        h.sup.answerTakeover(false)
    }

    // ── re-parent, service watch, recover ──

    @Test fun ourChildFromAPreviousRunIsReparentedAndStoppedOnQuit() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("desktop-broker.pid"), "777:1000")
        val orphan = FakeChild(777, startMillis = 1_000)
        h.table.procs[777] = ProcInfo(1_400, "/s/desktop-assets/bin/supermux-broker") to orphan
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        assertTrue(h.launches.isEmpty())
        assertEquals(running, h.sup.status.value)
        h.sup.quit()
        assertEquals(1, orphan.destroyed)
    }

    @Test fun serviceDownThirtySecondsIsKickedOnceThenCantStart() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.probeFn = { if (h.bootstrapped()) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        h.probeFn = { HostProbeResult.PortFree }
        advanceTimeBy(40_000)
        assertEquals(1, h.env.ran.count { it == listOf("launchctl", "kickstart", "-k", "gui/501/dev.supermux.host") })
        assertEquals(HostingStatus.Restarting(1), h.sup.status.value)
        advanceTimeBy(40_000)
        assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertEquals(1, h.env.ran.count { it.getOrNull(1) == "kickstart" })
        assertTrue(h.launches.isEmpty())
    }

    @Test fun ensureFinishesAnInterruptedTakeoverFirst() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        val old = h.home.resolve("Library/LaunchAgents/dev.supermux.broker.plist").also { Files.writeString(it, "old") }
        writeJournal(h, old)
        Files.writeString(h.state.resolve("desktop-carried-env.pending.json"), "{\"MUX_X\":\"1\"}")
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        assertFalse(Files.exists(h.state.resolve("takeover-backup/pending.json")))
        assertEquals(mapOf("MUX_X" to "1"), CarriedEnvStore(h.state.resolve("desktop-carried-env.json")).load())
        assertFalse(Files.exists(old)) // commit retires the old definition
        assertEquals(running, h.sup.status.value)
    }

    @Test fun ensureRollsBackAnInterruptedTakeoverAndDoesNotFightTheRestoredBroker() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        val old = h.home.resolve("Library/LaunchAgents/dev.supermux.broker.plist")
        Files.createDirectories(old.parent)
        writeJournal(h, old)
        h.probeFn = {
            if (h.bootstrapped(old)) HostProbeResult.Supermux("h9", BUNDLED, "binary", null, "/elsewhere/state")
            else HostProbeResult.PortFree
        }
        h.sup.ensure()
        assertFalse(Files.exists(h.state.resolve("takeover-backup/pending.json")))
        assertEquals("old", Files.readString(old))
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
        assertTrue(h.launches.isEmpty())
        assertFalse(h.bootstrapped())
    }

    // ── review fixes: never two brokers ──

    @Test fun busyWithOurOwnChildAliveRestartsItOnTheSamePort() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.probeFn = { if (h.children.size >= 2 && h.liveChild != null) h.desktop() else HostProbeResult.Busy }
        h.sup.ensure()
        assertEquals(2, h.launches.size)
        assertEquals(1, h.children[0].destroyed)
        assertTrue(h.events.none { it.startsWith("save:") })
        assertEquals("9898", h.launches[1].env["MUX_WEB_PORT"])
        assertEquals(running, h.sup.status.value)
    }

    @Test fun aForeignLiveBrokerPidBlocksTheStart() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("broker.pid"), "999")
        h.table.procs[999] = ProcInfo(0, "/usr/local/bin/supermux") to null
        h.probeFn = { HostProbeResult.Busy }
        h.sup.ensure()
        assertEquals(
            HostingStatus.CantStart("supermux is already running on this computer (pid 999) but isn't answering on port 9898. Quit it, then try again."),
            h.sup.status.value,
        )
        assertTrue(h.launches.isEmpty())
        assertTrue(h.events.none { it.startsWith("save:") })
    }

    @Test fun aBrokerStillStartingIsAdoptedOnceItAnswers() = runTest {
        val h = Harness(this)
        // It holds broker.pid but hasn't bound the port yet.
        Files.writeString(h.state.resolve("broker.pid"), "999")
        h.table.procs[999] = ProcInfo(0, "/x/supermux-broker") to null
        var bound = false
        h.probeFn = { if (bound) h.desktop("h-booted") else HostProbeResult.PortFree }
        val job = launch { h.sup.ensure() }
        advanceTimeBy(3_000)
        bound = true
        job.join()
        assertEquals(running, h.sup.status.value)
        assertEquals("h-booted", h.sup.hostId.value)
        assertTrue(h.launches.isEmpty(), "no second broker")
    }

    @Test fun anOutsideBrokerStillStartingGetsTheTakeoverQuestion() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("broker.pid"), "999")
        h.table.procs[999] = ProcInfo(0, "/x/supermux-broker") to null
        var bound = false
        h.probeFn = { if (bound) h.outside("h-cli") else HostProbeResult.PortFree }
        val job = launch { h.sup.ensure() }
        advanceTimeBy(3_000)
        bound = true
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(HostingStatus.AskTakeover("h-cli"), h.sup.status.value)
        assertTrue(h.launches.isEmpty())
        job.cancel()
    }

    @Test fun aReusedBrokerPidDoesNotBlock() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("broker.pid"), "999")
        // Started long after the pid file was written: some other process got the pid.
        h.table.procs[999] = ProcInfo(System.currentTimeMillis() + 60_000, "/usr/bin/other") to null
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertEquals(1, h.launches.size)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun ourPlistPlusALivePreviousChildStopsTheChildAndUsesTheService() = runTest {
        // Background on (the service is installed), yet last session's child still runs.
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        Files.writeString(h.state.resolve("desktop-broker.pid"), "777:1000")
        val orphan = FakeChild(777, startMillis = 1_000)
        h.table.procs[777] = ProcInfo(1_000, "/s/supermux-broker") to orphan
        h.probeFn = { if (orphan.isAlive || h.bootstrapped()) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        assertEquals(1, orphan.destroyed)
        assertTrue(h.bootstrapped())
        // stopped via the child handle only: remove (bootout + delete) would also hit the child on Windows
        assertTrue(Files.exists(h.ourPlist))
        assertTrue(h.saved.background)
        assertTrue(h.launches.isEmpty())
        assertEquals(running, h.sup.status.value)
        val before = h.env.ran.size
        h.sup.quit() // service mode: nothing to stop
        assertEquals(before, h.env.ran.size)
    }

    @Test fun aLivePreviousChildWithNoServiceTurnsBackgroundOff() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        Files.writeString(h.state.resolve("desktop-broker.pid"), "777:1000")
        val orphan = FakeChild(777, startMillis = 1_000)
        h.table.procs[777] = ProcInfo(1_000, "/s/supermux-broker") to orphan
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        assertTrue(h.env.ran.isEmpty() && h.launches.isEmpty())
        assertFalse(h.saved.background)
        assertEquals(HostSupervisor.PREVIOUS_CHILD, h.sup.backgroundError.value)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun ourChildExitingWhileOurServiceIsInstalledHandsOverToTheService() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.writeOurPlist() // e.g. background mode was set up by another path meanwhile
        h.probeFn = { h.desktop() } // the service's broker answers
        h.children[0].exit(1)
        advanceTimeBy(30_000)
        assertEquals(1, h.launches.size) // no respawn next to the service
        assertEquals(running, h.sup.status.value)
        val before = h.env.ran.size
        h.sup.quit()
        assertEquals(before, h.env.ran.size) // service mode now
    }

    @Test fun orphanStartIntoBackgroundModeRespectsAForeignBrokerPid() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.probeFn = { h.desktop() }
        h.sup.ensure() // ours, no service, no child: orphan
        Files.writeString(h.state.resolve("broker.pid"), "999")
        h.table.procs[999] = ProcInfo(0, "/usr/local/bin/supermux") to null
        h.probeFn = { HostProbeResult.PortFree }
        advanceTimeBy(30_000)
        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue("pid 999" in s.reason, s.reason)
        assertFalse(h.bootstrapped())
        assertTrue(h.launches.isEmpty())
    }

    @Test fun backgroundOffWhenTheServiceCantBeRemovedStartsNoChild() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.probeFn = { if (h.bootstrapped()) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        val agents = h.ourPlist.parent
        agents.toFile().setWritable(false) // the plist can't be deleted: remove() fails
        try {
            h.sup.setBackground(false)
        } finally {
            agents.toFile().setWritable(true)
        }
        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue(s.reason.startsWith("Couldn't stop the background service:"), s.reason)
        assertTrue(h.saved.background)
        assertTrue(h.launches.isEmpty())
    }

    @Test fun backgroundOffWaitsForThePortBeforeStartingTheChild() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        val bootout = listOf("launchctl", "bootout", "gui/501/dev.supermux.host")
        var freeAt = Long.MAX_VALUE
        h.probeFn = { port ->
            val removed = h.env.ran.count { it == bootout } >= 2 // install boots out once, remove once more
            if (removed && freeAt == Long.MAX_VALUE) freeAt = testScheduler.currentTime + 3_000
            when {
                !removed -> if (h.bootstrapped()) h.desktop() else HostProbeResult.PortFree
                testScheduler.currentTime < freeAt -> h.desktop() // the service's broker is still stopping
                else -> h.healthyIfChild()(port)
            }
        }
        h.sup.ensure()
        var startedAt = -1L
        h.onStart = { startedAt = testScheduler.currentTime }
        h.sup.setBackground(false)
        assertTrue(startedAt >= freeAt, "started at $startedAt, port free at $freeAt")
        assertEquals(1, h.launches.size)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun aFailedCopyUsesTheBrokerAPreviousVersionInstalled() = runTest {
        val h = Harness(this)
        val previous = h.state.resolve("desktop-assets/bin/supermux-broker")
        Files.createDirectories(previous.parent)
        Files.writeString(previous, "old broker")
        h.bins = HostBinaries.SidecarBinaries(null, null, null, null, null, null)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertEquals(listOf(previous.toString()), h.launches.single().argv)
        assertTrue(h.events.any { "using the broker a previous version installed" in it })
        assertEquals(running, h.sup.status.value)
    }

    @Test fun aFailedCopyInBackgroundModeInstallsThePreviousBroker() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        val previous = h.state.resolve("desktop-assets/bin/supermux-broker")
        Files.createDirectories(previous.parent)
        Files.writeString(previous, "old broker")
        h.bins = HostBinaries.SidecarBinaries(null, null, null, null, null, null)
        h.probeFn = { if (h.bootstrapped()) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        assertTrue("<string>$previous</string>" in Files.readString(h.ourPlist))
        assertNull(h.sup.backgroundError.value)
        assertTrue(h.launches.isEmpty())
        assertEquals(running, h.sup.status.value)
    }

    @Test fun pidFileChildWithAnotherStartTimeIsNotAdopted() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("desktop-broker.pid"), "777:1000")
        val stranger = FakeChild(777)
        h.table.procs[777] = ProcInfo(50_000, "/s/supermux-broker") to stranger
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        h.sup.quit()
        assertEquals(0, stranger.destroyed)
    }

    @Test fun systemdEnableFailingAndNotHealthyRemovesTheUnitAndRunsAChild() = runTest {
        val enable = listOf("systemctl", "--user", "enable", "--now", "supermux-host")
        val h = Harness(this, os = OsEnv.Os.LINUX, prefs = HostingPrefs(background = true), xdg = "/run/user/501", failing = setOf(enable))
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertTrue(listOf("systemctl", "--user", "disable", "--now", "supermux-host") in h.env.ran)
        assertFalse(Files.exists(h.home.resolve(".config/systemd/user/supermux-host.service")))
        assertEquals(1, h.launches.size)
        assertTrue(h.sup.backgroundError.value!!.startsWith("Couldn't keep supermux running in the background:"))
        assertEquals(running, h.sup.status.value)
    }

    @Test fun systemdEnableFailingButHealthyIsServiceMode() = runTest {
        val enable = listOf("systemctl", "--user", "enable", "--now", "supermux-host")
        val h = Harness(this, os = OsEnv.Os.LINUX, prefs = HostingPrefs(background = true), xdg = "/run/user/501", failing = setOf(enable))
        h.probeFn = { if (h.env.ran.any { it.getOrNull(2) == "restart" }) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        assertTrue(h.launches.isEmpty())
        assertTrue(Files.exists(h.home.resolve(".config/systemd/user/supermux-host.service")))
        assertEquals(running, h.sup.status.value)
    }

    // ── cancellation ──

    @Test fun cancellingEnsureMidSpawnTracksTheChildAndEndsTerminal() = runTest {
        val h = Harness(this)
        h.probeFn = { HostProbeResult.PortFree }
        lateinit var job: Job
        h.onStart = { job.cancel() }
        job = launch { h.sup.ensure() }
        advanceTimeBy(10_000)
        job.join()
        assertEquals(1, h.launches.size)
        assertEquals(1, h.children[0].destroyed) // recorded, so stopped: never leaked
        assertEquals(HostingStatus.CantStart(HostSupervisor.INTERRUPTED), h.sup.status.value)
    }

    @Test fun cancellingEnsureAfterAHealthySpawnKeepsItRunningAndWatched() = runTest {
        val h = Harness(this)
        var probesAfterSpawn = 0
        // The first probe after the spawn misses (so ensure suspends and sees the cancel); later ones answer.
        h.probeFn = { if (h.liveChild != null && probesAfterSpawn++ > 0) h.desktop() else HostProbeResult.PortFree }
        lateinit var job: Job
        h.onStart = { job.cancel() }
        job = launch { h.sup.ensure() }
        advanceTimeBy(10_000)
        job.join()
        assertEquals(running, h.sup.status.value)
        h.children[0].exit(1)
        advanceTimeBy(5_000)
        assertEquals(2, h.launches.size) // supervised
    }

    // ── retry caps ──

    @Test fun fiveUnhealthyStartsInARowIsCantStart() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.deadOnArrival = true
        h.children[0].exit(1)
        advanceTimeBy(200_000)
        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue("didn't start after 5 tries" in s.reason, s.reason)
        assertEquals(6, h.launches.size)
    }

    @Test fun aStuckChildIsKilledAndRestarted() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.probeFn = { if (h.children.size >= 2 && h.liveChild != null) h.desktop() else HostProbeResult.Busy }
        advanceTimeBy(12_000)
        assertEquals(0, h.children[0].destroyed) // two failed polls: not yet
        advanceTimeBy(20_000)
        assertEquals(1, h.children[0].destroyed)
        assertEquals(2, h.launches.size)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun aLongHealthyRunResetsTheCrashCounter() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        repeat(4) { h.liveChild!!.exit(1); advanceTimeBy(20_000) }
        advanceTimeBy(70_000) // a healthy minute
        h.liveChild!!.exit(1)
        advanceTimeBy(500)
        assertEquals(HostingStatus.Restarting(1), h.sup.status.value)
    }

    // ── updates ──

    @Test fun updateCopyFailureKeepsTheRunningService() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        h.bins = HostBinaries.SidecarBinaries(null, null, null, null, null, null)
        h.probeFn = { h.desktop(build = "1.4.0 (old)") }
        h.sup.ensure()
        assertEquals(running, h.sup.status.value)
        assertTrue(h.env.ran.isEmpty())
        assertTrue(h.events.any { "keeping the running one" in it })
    }

    @Test fun updateCopyFailureKeepsTheRunningChild() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("desktop-broker.pid"), "777:1000")
        val orphan = FakeChild(777)
        h.table.procs[777] = ProcInfo(1_000, "/s/supermux-broker") to orphan
        h.bins = HostBinaries.SidecarBinaries(null, null, null, null, null, null)
        h.probeFn = { h.desktop(build = "1.4.0 (old)") }
        h.sup.ensure()
        assertEquals(running, h.sup.status.value)
        assertEquals(0, orphan.destroyed)
        assertTrue(h.launches.isEmpty())
    }

    @Test fun updateOwnInChildModeRestartsThePreviousChild() = runTest {
        val h = Harness(this)
        Files.writeString(h.state.resolve("desktop-broker.pid"), "777:1000")
        val orphan = FakeChild(777)
        h.table.procs[777] = ProcInfo(1_000, "/s/supermux-broker") to orphan
        h.probeFn = { if (h.liveChild != null) h.desktop() else h.desktop(build = "1.4.0 (old)") }
        h.sup.ensure()
        assertEquals(1, orphan.destroyed)
        assertEquals(1, h.launches.size)
        assertTrue("materialize" in h.events)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun anUpdateThatDidNotTakeIsNotRetriedOnThisLaunch() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        h.probeFn = { h.desktop(build = "1.4.0 (old)") } // never reports the bundled build
        h.sup.ensure()
        assertTrue(h.events.any { "warning" in it })
        h.sup.ensure()
        assertEquals(1, h.env.ran.count { it.getOrNull(1) == "bootstrap" })
        assertEquals(running, h.sup.status.value)
    }

    // ── a bundled build that is only known late (a slow first read) ──

    private fun slowFirstRead(ts: TestScope, late: suspend () -> String?): Harness {
        val h = Harness(ts, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        h.bundledFn = { null } // the 15 s read gave up (Defender's first scan, a slow disk)
        h.lateBundledFn = late
        h.probeFn = { if (h.bootstrapped()) h.desktop() else h.desktop(build = "1.4.0 (old)") }
        return h
    }

    @Test fun unknownAtLaunchThenABackgroundReadOfANewerBuildUpdatesExactlyOnce() = runTest {
        val h = slowFirstRead(this) { delay(40_000); BUNDLED }
        h.sup.ensure()
        // The launch stays fast: the running broker is kept, nothing reinstalled yet.
        assertFalse(h.bootstrapped())
        assertEquals(running, h.sup.status.value)
        assertTrue(h.logs().any { it == "plan: UseOwn (bundled unknown)" }, h.logs().toString())
        assertTrue(h.logs().any { it.startsWith("bundled build unknown at launch; reading it in the background") }, h.logs().toString())
        advanceTimeBy(41_000)
        runCurrent()
        assertEquals(1, h.env.ran.count { it.getOrNull(1) == "bootstrap" })
        val l = h.logs()
        assertTrue(l.any { it == "background read: bundled $BUNDLED after 40.0 s (running 1.4.0 (old))" }, l.toString())
        assertTrue(l.any { it == "update (after the background read): 1.4.0 (old) -> $BUNDLED" }, l.toString())
        assertEquals(running, h.sup.status.value)
        // Another launch decision on this launch never updates again.
        h.sup.ensure()
        advanceTimeBy(200_000)
        assertEquals(1, h.env.ran.count { it.getOrNull(1) == "bootstrap" })
        assertEquals(1, h.lateReads)
    }

    @Test fun aBackgroundReadOfTheRunningBuildChangesNothing() = runTest {
        val h = slowFirstRead(this) { delay(20_000); "1.4.0 (old)" }
        h.sup.ensure()
        advanceTimeBy(21_000)
        runCurrent()
        assertFalse(h.bootstrapped())
        assertTrue(h.logs().any { it == "background read: plan UseOwn; nothing to update" }, h.logs().toString())
        assertEquals(running, h.sup.status.value)
    }

    @Test fun aBackgroundReadThatStillFailsLeavesTheUpdateForTheNextLaunch() = runTest {
        val h = slowFirstRead(this) { delay(180_000); null }
        h.sup.ensure()
        advanceTimeBy(181_000)
        runCurrent()
        assertFalse(h.bootstrapped())
        assertTrue(h.logs().any { it.startsWith("background read: bundled build still unknown after 180.0 s") }, h.logs().toString())
        assertEquals(running, h.sup.status.value)
    }

    @Test fun quitDuringTheBackgroundReadKillsItsProbeAndUpdatesNothing() = runTest {
        val h = slowFirstRead(this) { delay(40_000); BUNDLED }
        h.sup.ensure()
        advanceTimeBy(10_000)
        h.sup.quit()
        assertEquals(1, h.killedReads)
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(h.bootstrapped())
        assertTrue(h.logs().none { it.startsWith("update (after the background read)") }, h.logs().toString())
    }

    @Test fun aBackgroundReadWhileHostingWasTurnedOffSaysWhyItDoesNothing() = runTest {
        val h = slowFirstRead(this) { delay(40_000); BUNDLED }
        h.sup.ensure()
        h.saved = h.saved.copy(hosting = false)
        h.sup.setHosting(false)
        advanceTimeBy(41_000)
        runCurrent()
        assertTrue(h.logs().any { it == "background read: not updating (hosting is off)" }, h.logs().toString())
        assertEquals(0, h.env.ran.count { it.getOrNull(1) == "bootstrap" })
    }

    @Test fun aKnownBundledBuildAtLaunchStartsNoBackgroundRead() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        advanceTimeBy(200_000)
        assertEquals(0, h.lateReads)
    }

    // ── orphan ──

    @Test fun orphanNeedsThreeFreeProbesInARowBeforeStartingOurs() = runTest {
        val h = Harness(this)
        h.probeFn = { h.desktop() }
        h.sup.ensure() // ours, but no child we can re-parent and no service: orphan
        val q = ArrayDeque(listOf<HostProbeResult>(
            HostProbeResult.PortFree, HostProbeResult.PortFree, h.desktop(),
            HostProbeResult.PortFree, HostProbeResult.PortFree, HostProbeResult.PortFree,
        ))
        h.probeFn = { port -> q.removeFirstOrNull() ?: h.healthyIfChild()(port) }
        advanceTimeBy(10_000)
        assertTrue(h.launches.isEmpty())
        advanceTimeBy(30_000)
        assertEquals(1, h.launches.size)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun orphanWithOurServiceInstalledNeverStartsAChild() = runTest {
        val h = Harness(this)
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        h.writeOurPlist() // e.g. installed by another app instance meanwhile
        h.probeFn = { HostProbeResult.PortFree }
        advanceTimeBy(200_000)
        assertTrue(h.launches.isEmpty())
    }

    @Test fun hostingOffWithAnOrphanStillAnsweringSaysSo() = runTest {
        val h = Harness(this)
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        h.sup.setHosting(false)
        assertEquals(HostingStatus.CantStart(HostSupervisor.STILL_RUNNING), h.sup.status.value)
    }

    // ── questions ──

    @Test fun answeringTheWrongKindOfQuestionDoesNothing() = runTest {
        val h = Harness(this)
        h.probeFn = { h.outside("h1") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        h.sup.answerDowngrade(true)
        runCurrent()
        assertEquals(HostingStatus.AskTakeover("h1"), h.sup.status.value)
        h.sup.answerTakeover(false)
        job.join()
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
    }

    @Test fun hostingOffBetweenTheAnswerAndItsHandlingWins() = runTest {
        val h = Harness(this)
        h.probeFn = { h.outside("h1") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        h.sup.answerTakeover(true) // answered, but ensure hasn't acted on it yet...
        h.sup.setHosting(false)    // ...when hosting is turned off
        job.join()
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
        assertFalse(Files.exists(h.state.resolve("takeover-backup")))
        assertTrue(h.launches.isEmpty())
    }

    @Test fun useBundledTakesOverANewerBroker() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true), failing = setOf(listOf("launchctl", "print", "gui/501/dev.supermux.host")))
        Files.createDirectories(h.ourPlist.parent)
        Files.writeString(h.ourPlist, res("mac-native-host.plist"))
        h.probeFn = { if (h.bootstrapped()) h.desktop("h1") else h.outside("h1", build = "9.0.0 (new)") }
        val job = launch { h.sup.ensure() }
        runCurrent()
        assertEquals(HostingStatus.AskDowngrade("h1"), h.sup.status.value)
        h.sup.answerDowngrade(true)
        job.join()
        assertTrue(BrokerService.MANAGED_MARKER in Files.readString(h.ourPlist))
        assertEquals(running, h.sup.status.value)
    }

    @Test fun takeoverThatNeverGetsHealthyRollsBackRelayAndPendingEnv() = runTest {
        val home = createTempDirectory("sup-home")
        val oldPlist = home.resolve("Library/LaunchAgents/dev.supermux.broker.plist")
        val h = Harness(
            this, home = home, prefs = HostingPrefs(background = true, relay = false),
            failing = setOf(listOf("launchctl", "print", "gui/501/dev.supermux.broker")),
        )
        Files.createDirectories(oldPlist.parent)
        Files.writeString(oldPlist, res("mac-native-host.plist").replace("<string>dev.supermux.host</string>", "<string>dev.supermux.broker</string>"))
        h.probeFn = { h.outside("h1") } // ours never answers
        val job = launch { h.sup.ensure() }
        runCurrent()
        h.sup.answerTakeover(true)
        job.join()
        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue(s.reason.startsWith("Couldn't take over:"), s.reason)
        assertFalse(h.saved.relay)
        assertFalse(Files.exists(h.state.resolve("desktop-carried-env.pending.json")))
        assertFalse(Files.exists(h.state.resolve("desktop-carried-env.json")))
        assertFalse(Files.exists(h.ourPlist))
        assertTrue(h.bootstrapped(oldPlist))
    }

    // ── restart ──

    @Test fun restartInChildModeReplacesTheChild() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.sup.restart()
        assertEquals(1, h.children[0].destroyed)
        assertEquals(2, h.launches.size)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun restartInServiceModeKicksTheService() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.probeFn = { if (h.bootstrapped()) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        h.sup.restart()
        assertTrue(listOf("launchctl", "kickstart", "-k", "gui/501/dev.supermux.host") in h.env.ran)
        assertTrue(h.launches.isEmpty())
        assertEquals(running, h.sup.status.value)
    }

    @Test fun restartInReadOnlyModeDoesNothing() = runTest {
        val h = Harness(this, prefs = HostingPrefs(leftAloneHostIds = setOf("h1")))
        h.probeFn = { h.outside("h1") }
        h.sup.ensure()
        h.sup.restart()
        assertTrue(h.env.ran.isEmpty() && h.launches.isEmpty())
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
    }

    // ── env / recovery ──

    @Test fun theChildEnvDropsInheritedMuxKeys() = runTest {
        val h = Harness(this)
        h.baseEnv = mapOf("HOME" to "/h", "MUX_TELEGRAM_BOT_TOKEN" to "inherited", "MUX_STATE_DIR" to "/elsewhere")
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        val env = h.launches.single().env
        assertEquals("/h", env["HOME"])
        assertFalse("MUX_TELEGRAM_BOT_TOKEN" in env)
        assertEquals(h.state.toString(), env["MUX_STATE_DIR"])
    }

    @Test fun theChildEnvDropsTheServiceManagersMarkers() = runTest {
        val h = Harness(this)
        h.baseEnv = mapOf(
            "HOME" to "/h", "INVOCATION_ID" to "abc", "JOURNAL_STREAM" to "8:123", "XPC_SERVICE_NAME" to "dev.supermux.host",
            "MUX_SERVICE_UNIT" to "supermux.service", "MUX_SERVICE_LABEL" to "dev.supermux.broker", "SUPERMUX_KEEP_ALIVE" to "1",
        )
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        val env = h.launches.single().env
        assertEquals("/h", env["HOME"])
        for (k in listOf("INVOCATION_ID", "JOURNAL_STREAM", "XPC_SERVICE_NAME", "MUX_SERVICE_UNIT", "MUX_SERVICE_LABEL", "SUPERMUX_KEEP_ALIVE")) {
            assertFalse(k in env, k)
        }
    }

    @Test fun aRestoredServiceThatStaysSilentIsCantStartNotASecondBroker() = runTest {
        val h = Harness(this)
        val old = h.home.resolve("Library/LaunchAgents/dev.supermux.broker.plist")
        Files.createDirectories(old.parent)
        writeJournal(h, old)
        Files.writeString(h.state.resolve("desktop-carried-env.pending.json"), "{}")
        h.probeFn = { HostProbeResult.PortFree }
        h.sup.ensure()
        assertEquals(HostingStatus.CantStart(HostSupervisor.RESTORED_SILENT), h.sup.status.value)
        assertTrue(h.launches.isEmpty())
        assertFalse(Files.exists(h.state.resolve("desktop-carried-env.pending.json")))
    }

    // ── service update on real launchd (VM scenario S7) ──

    private fun updateWithBootstrapFailing(ts: TestScope, failures: Int): Harness {
        val home = createTempDirectory("sup-home")
        val plist = home.resolve("Library/LaunchAgents/dev.supermux.host.plist")
        val boot = listOf("launchctl", "bootstrap", "gui/501", plist.toString())
        val io5 = OsEnv.RunResult(5, "", "Bootstrap failed: 5: Input/output error")
        val h = Harness(ts, home = home, prefs = HostingPrefs(background = true), scripted = mapOf(boot to List(failures) { io5 }))
        h.writeOurPlist()
        // The old broker's pid never goes away, so the child fallback is refused.
        Files.writeString(h.state.resolve("broker.pid"), "999")
        h.table.procs[999] = ProcInfo(0, "/s/desktop-assets/bin/supermux-broker") to null
        h.probeFn = { if (h.env.ran.count { it == boot } > failures) h.desktop() else h.desktop(build = "1.4.0 (old)") }
        return h
    }

    @Test fun serviceUpdateThatCantInstallOrFallBackStillLeavesOurDefinitionAndSaysWhy() = runTest {
        val h = updateWithBootstrapFailing(this, failures = 100)
        h.sup.ensure()
        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue("Input/output error" in s.reason, s.reason)
        assertTrue(Files.exists(h.ourPlist)) // never "plist deleted and nothing running"
        assertTrue(BrokerService.MANAGED_MARKER in Files.readString(h.ourPlist))
        assertTrue(h.launches.isEmpty())
    }

    @Test fun serviceUpdateWhoseInstallFailsOnceIsReinstalledWhenTheChildIsRefused() = runTest {
        val h = updateWithBootstrapFailing(this, failures = 10)
        h.sup.ensure()
        assertEquals(running, h.sup.status.value)
        assertTrue(Files.exists(h.ourPlist))
        assertTrue(h.launches.isEmpty())
        assertNull(h.sup.backgroundError.value)
    }

    @Test fun anExitingBrokerPidIsWaitedForThenTheChildStarts() = runTest {
        val h = Harness(this)
        val exiting = FakeChild(999)
        Files.writeString(h.state.resolve("broker.pid"), "999")
        h.table.procs[999] = ProcInfo(0, "/s/desktop-assets/bin/supermux-broker") to exiting
        backgroundScope.launch { kotlinx.coroutines.delay(5_000); exiting.exit(0) }
        h.probeFn = h.healthyIfChild()
        var startedAt = -1L
        h.onStart = { startedAt = testScheduler.currentTime }
        h.sup.ensure()
        assertEquals(1, h.launches.size)
        assertTrue(startedAt >= 5_000, "started at $startedAt")
        assertEquals(running, h.sup.status.value)
    }

    @Test fun theHostLogRotatesAtItsLimit() {
        val dir = createTempDirectory("hostlog")
        val log = HostLogFile(dir.resolve("desktop-host.log"), maxBytes = 64)
        repeat(5) { log.append("line $it with some padding to pass the limit") }
        assertTrue(Files.exists(dir.resolve("desktop-host.log.1")))
        assertTrue(Files.size(dir.resolve("desktop-host.log")) < 200)
        assertFalse(Files.exists(dir.resolve("desktop-host.log.2")))
    }

    private fun writeJournal(h: Harness, old: Path) {
        val dir = h.state.resolve("takeover-backup").also { Files.createDirectories(it) }
        val backup = dir.resolve("dev.supermux.broker.plist.1").also { Files.writeString(it, "old") }
        val p = Takeover.Prepared(
            stateDir = h.state.toString(),
            olds = listOf(Takeover.PreparedOne(Takeover.OldService(Takeover.Kind.LAUNCHD, "dev.supermux.broker", old.toString()), backup.toString())),
            carriedEnv = emptyMap(),
            oldRelay = null,
        )
        Files.writeString(dir.resolve("pending.json"), Takeover.encodeJournal(p))
    }

    @Test fun gitAvailableFollowsTheHealthyProbeAndClearsWhenHostingStops() = runTest {
        val h = Harness(this)
        assertNull(h.sup.gitAvailable.value)
        h.probeFn = { if (h.liveChild != null) h.desktop().copy(gitAvailable = false) else HostProbeResult.PortFree }
        h.sup.ensure()
        assertIs<HostingStatus.Running>(h.sup.status.value)
        assertEquals(false, h.sup.gitAvailable.value)
        h.probeFn = { HostProbeResult.PortFree }
        h.sup.setHosting(false)
        assertNull(h.sup.gitAvailable.value)
    }

    @Test fun gitRequirementFollowsTheBrokerAndClearsWhenHostingStops() = runTest {
        val h = Harness(this)
        assertNull(h.sup.gitRequirement.value)
        val missing = dev.supermux.net.GitRequirement(ok = false, install = "manual", hint = "Install git")
        h.probeFn = {
            if (h.liveChild != null) {
                h.desktop().copy(requirements = dev.supermux.net.HostRequirements(missing))
            } else HostProbeResult.PortFree
        }
        h.sup.ensure()
        assertEquals(missing, h.sup.gitRequirement.value)
        h.probeFn = { HostProbeResult.PortFree }
        h.sup.setHosting(false)
        assertNull(h.sup.gitRequirement.value)
    }

    // ── the supervisor's log on success paths ──

    private fun Harness.logs() = events.filter { it.startsWith("log:") }.map { it.removePrefix("log:") }

    @Test fun aNormalChildStartLogsProbePlanModeAndHealth() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        val l = h.logs()
        assertTrue(l.any { it == "probe :9898: free" }, l.toString())
        assertTrue(l.any { it.startsWith("plan: Start") }, l.toString())
        assertTrue(l.any { it.startsWith("starting the broker as a child on port 9898") }, l.toString())
        assertTrue(l.any { it == "mode: none -> child" }, l.toString())
        assertTrue(l.any { it.startsWith("healthy on port 9898: h-own") }, l.toString())
        assertTrue(l.all { it.length <= 200 }, l.toString())
    }

    @Test fun aPortMoveIsLogged() = runTest {
        val h = Harness(this)
        h.probeFn = { port -> if (port == 9898) HostProbeResult.ForeignProcess else h.healthyIfChild()(port) }
        h.sup.ensure()
        val l = h.logs()
        assertTrue(l.any { it == "probe :9898: something else holds the port" }, l.toString())
        assertTrue(l.any { it == "port 9898 is taken by something else; moving to 45678" }, l.toString())
    }

    @Test fun aCrashRestartIsLogged() = runTest {
        val h = Harness(this)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.children[0].exit(1)
        advanceTimeBy(20_000)
        val l = h.logs()
        assertTrue(l.any { it == "the broker exited (code 1)" }, l.toString())
        assertTrue(l.any { it.startsWith("restarting the broker: attempt 1") }, l.toString())
        assertTrue(l.any { it == "restarted (attempt 1)" }, l.toString())
    }

    @Test fun anUpdateLogsFromAndToBuilds() = runTest {
        val h = Harness(this)
        var old = true
        h.probeFn = { if (old) h.desktop(build = "1.4.0 (old)") else h.healthyIfChild()(it) }
        h.onStart = { old = false }
        h.sup.ensure()
        val l = h.logs()
        assertTrue(l.any { it == "update: 1.4.0 (old) -> $BUNDLED" }, l.toString())
    }

    @Test fun theServiceInstallIsLoggedWithoutItsEnvironment() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.baseEnv = h.baseEnv + ("ANTHROPIC_API_KEY" to "sk-secret-value")
        h.probeFn = { if (h.bootstrapped()) h.desktop() else HostProbeResult.PortFree }
        h.sup.ensure()
        val l = h.logs()
        assertTrue(l.any { it.startsWith("service install: installed dev.supermux.host.plist") }, l.toString())
        assertTrue(l.any { it == "mode: none -> service" }, l.toString())
        assertTrue(l.none { "sk-secret-value" in it || "ANTHROPIC_API_KEY" in it }, l.toString())
    }

    @Test fun windowsUpdateWithUacDeclinedKeepsThePreviousTaskRunningWithoutASecondPrompt() = runTest {
        val elevated = { argv: List<String> -> argv.firstOrNull()?.endsWith("powershell.exe") == true && argv.last().contains("-Verb RunAs") }
        // Our task is registered (schtasks /Query answers) with an older definition; nothing else runs.
        val h = Harness(this, os = OsEnv.Os.WINDOWS, prefs = HostingPrefs(background = true), failIf = elevated, captures = noProcesses)
        h.probeFn = { h.desktop(build = "1.4.0 (old)") } // the old broker, running again after /Run
        h.sup.ensure()
        assertEquals(running, h.sup.status.value)
        assertEquals(BrokerService.WINDOWS_UPDATE_DECLINED, h.sup.backgroundError.value)
        assertEquals(1, h.env.ran.count(elevated), "no second UAC prompt to remove the task")
        assertTrue(listOf("schtasks", "/Run", "/TN", "Supermux Host") in h.env.ran)
        assertTrue(h.launches.isEmpty(), "no child next to the service")
        assertEquals(HostSupervisor.Mode.SERVICE, h.sup.mode)
    }

    private val noProcesses = mapOf(BrokerService.listWindowsProcessesArgv() to "")
    private val elevatedCall = { argv: List<String> -> argv.firstOrNull()?.endsWith("powershell.exe") == true && argv.last().contains("-Verb RunAs") }

    @Test fun windowsUpdateWhoseBrokerWontStopChangesNothing() = runTest {
        // The process listing fails: the old broker may still run, so no new loop may start.
        val h = Harness(this, os = OsEnv.Os.WINDOWS, prefs = HostingPrefs(background = true))
        h.probeFn = { h.desktop(build = "1.4.0 (old)") }
        h.sup.ensure()
        assertEquals(running, h.sup.status.value)
        assertEquals(BrokerService.WINDOWS_STOP_FAILED, h.sup.backgroundError.value)
        assertEquals(0, h.env.ran.count(elevatedCall), "no re-registration")
        assertTrue(listOf("schtasks", "/Run", "/TN", "Supermux Host") !in h.env.ran, "no second loop")
        assertTrue(h.launches.isEmpty())
        assertEquals(HostSupervisor.Mode.SERVICE, h.sup.mode)
    }

    @Test fun hostingOffWithUacDeclinedKeepsHostingOnAndSaysWhy() = runTest {
        val h = Harness(this, os = OsEnv.Os.WINDOWS, prefs = HostingPrefs(background = true), failIf = elevatedCall, captures = noProcesses)
        h.probeFn = { h.desktop() }
        h.sup.ensure()
        assertEquals(running, h.sup.status.value)
        h.sup.setHosting(false)
        val s = assertIs<HostingStatus.CantStart>(h.sup.status.value)
        assertTrue(s.reason.startsWith("Couldn't stop the background service:"), s.reason)
        assertTrue(h.saved.hosting, "the task is still registered: we are still hosting")
        assertTrue(h.sup.prefs.value.hosting)
        assertTrue(h.launches.isEmpty())
    }

    // ── upgrading from 1.0.0 while the app runs as its keep-alive job ──

    private val jobVerbs = setOf("bootout", "bootstrap", "kickstart", "restart", "stop")

    @Test fun macUpgradeAsThe100JobNeverBootsTheAppOut() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true), envVars = LegacyKeepAlive.macJobEnv)
        Files.createDirectories(h.ourPlist.parent); Files.writeString(h.ourPlist, LegacyKeepAlive.plist)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertEquals(running, h.sup.status.value)
        assertTrue(h.env.ran.none { it.getOrNull(1) in jobVerbs }, h.env.ran.toString())
        assertTrue(BrokerService.MANAGED_MARKER in Files.readString(h.ourPlist), "the broker service takes over at the next login")
        assertEquals(1, h.launches.size, "until then the broker is the app's child")
        assertEquals(HostSupervisor.Mode.CHILD, h.sup.mode)
        assertEquals(HostSupervisor.NEXT_LOGIN, h.sup.backgroundError.value)
        assertTrue(h.sup.quitStopsBroker)
        // The child crashing restarts the child: the next-login service runs nothing yet.
        h.children[0].exit(1)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, h.launches.size)
        assertTrue(h.env.ran.none { it.getOrNull(1) in jobVerbs }, h.env.ran.toString())
        // Background off: the next-login definition goes; the job (this app) and its child stay.
        h.sup.setBackground(false)
        assertFalse(Files.exists(h.ourPlist))
        assertTrue(h.liveChild != null)
        assertTrue(h.env.ran.none { it.getOrNull(1) in jobVerbs }, h.env.ran.toString())
        assertFalse(h.saved.background)
    }

    @Test fun linuxUpgradeAsThe100UnitNeverRestartsIt() = runTest {
        val h = Harness(this, os = OsEnv.Os.LINUX, prefs = HostingPrefs(background = true), xdg = "/run/user/1000", cgroup = LegacyKeepAlive.LINUX_JOB_CGROUP)
        val unit = h.home.resolve(".config/systemd/user/supermux-host.service")
        Files.createDirectories(unit.parent); Files.writeString(unit, LegacyKeepAlive.unit)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        assertEquals(running, h.sup.status.value)
        assertTrue(h.env.ran.none { "--now" in it || it.getOrNull(2) in jobVerbs }, h.env.ran.toString())
        assertTrue(BrokerService.MANAGED_MARKER in Files.readString(unit))
        assertEquals(HostSupervisor.Mode.CHILD, h.sup.mode)
        h.sup.setHosting(false)
        assertFalse(Files.exists(unit))
        assertTrue(h.env.ran.none { "--now" in it || it.getOrNull(2) in jobVerbs }, h.env.ran.toString())
        assertEquals(HostingStatus.NotHosting, h.sup.status.value)
    }

    @Test fun backgroundOffAlsoRemovesThe100KeepAlive() = runTest {
        val h = Harness(this)
        Files.createDirectories(h.ourPlist.parent); Files.writeString(h.ourPlist, LegacyKeepAlive.plist)
        h.probeFn = h.healthyIfChild()
        h.sup.ensure()
        h.sup.setBackground(false)
        assertFalse(Files.exists(h.ourPlist), "it would open the app at every login")
        assertEquals(running, h.sup.status.value)
    }

    @Test fun windowsNeverAsksATakeoverItCannotDo() = runTest {
        // No task of ours is registered.
        val h = Harness(this, os = OsEnv.Os.WINDOWS, captures = noProcesses, failIf = { it == listOf("schtasks", "/Query", "/TN", "Supermux Host") })
        h.probeFn = { h.outside("h-cli") }
        h.sup.ensure() // returns: no question to wait on
        assertEquals(HostingStatus.Running(9898, readOnly = true), h.sup.status.value)
        assertEquals("h-cli", h.sup.hostId.value)
        assertTrue(h.launches.isEmpty() && h.env.ran.none { it.firstOrNull() == "schtasks" && it.getOrNull(1) != "/Query" })
    }

    /** A broker (pid 999) holds broker.pid; once [booting] is set, the port is free once, then it answers as ours. */
    private fun bootingBroker(h: Harness): () -> Unit {
        var booting = false
        var n = 0
        val child = h.healthyIfChild()
        h.probeFn = { port -> if (!booting) child(port) else if (n++ == 0) HostProbeResult.PortFree else h.desktop("h-booted") }
        return {
            Files.writeString(h.state.resolve("broker.pid"), "999")
            h.table.procs[999] = ProcInfo(0, "/x/supermux-broker") to null
            booting = true
        }
    }

    @Test fun aChildRelaunchAdoptsABrokerThatFinishedStartingInsteadOfSpawning() = runTest {
        val h = Harness(this)
        val boot = bootingBroker(h)
        h.sup.ensure()
        assertEquals(1, h.launches.size)
        boot()
        h.sup.setRelay(false) // relaunches the child: launchChildLocked meets the booting broker
        assertEquals(1, h.launches.size, "no second broker spawned")
        assertEquals(running, h.sup.status.value)
        assertEquals("h-booted", h.sup.hostId.value)
        assertEquals(HostSupervisor.Mode.ORPHAN, h.sup.mode)
    }

    @Test fun aServiceInstallAdoptsABrokerThatFinishedStartingInsteadOfInstalling() = runTest {
        val h = Harness(this)
        val boot = bootingBroker(h)
        h.sup.ensure()
        boot()
        h.sup.setBackground(true) // a fresh install: launchLocked meets the booting broker
        assertFalse(h.bootstrapped(), "nothing installed next to it")
        assertFalse(Files.exists(h.ourPlist))
        assertEquals(1, h.launches.size)
        assertEquals(running, h.sup.status.value)
        assertEquals(HostSupervisor.Mode.ORPHAN, h.sup.mode)
    }

    // ── background off reaches a broker the login definition started ──

    @Test fun linuxXdgBackgroundOffInOrphanModeRemovesTheAutostartAndStopsItsBroker() = runTest {
        val h = Harness(this, os = OsEnv.Os.LINUX, prefs = HostingPrefs(background = true), commands = emptySet())
        // At login the XDG autostart started the broker: not our child, so orphan mode.
        val autostart = h.home.resolve(".config/autostart/supermux-host.desktop")
        Files.createDirectories(autostart.parent)
        Files.writeString(autostart, BrokerService.xdgAutostart(BrokerService.Spec(h.bins.brokerPath!!, emptyMap(), h.state.resolve("l.log")), h.home.resolve(".config/supermux/broker.env")))
        val atLogin = FakeChild(999, startMillis = 0)
        Files.writeString(h.state.resolve("broker.pid"), "999")
        h.table.procs[999] = ProcInfo(0, h.bins.brokerPath.toString()) to atLogin
        val child = h.healthyIfChild()
        h.probeFn = { if (atLogin.isAlive) h.desktop() else child(it) }
        h.sup.ensure()
        assertEquals(HostSupervisor.Mode.ORPHAN, h.sup.mode)
        h.sup.setBackground(false)
        assertFalse(Files.exists(autostart), "it would start a broker at the next login")
        assertEquals(1, atLogin.destroyed)
        assertEquals(1, h.launches.size)
        assertEquals(HostSupervisor.Mode.CHILD, h.sup.mode)
        assertEquals(running, h.sup.status.value)
        assertFalse(h.saved.background)
    }

    @Test fun macBackgroundOffInOrphanModeRemovesTheServiceThenRunsAChild() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = true))
        h.writeOurPlist()
        val bootout = listOf("launchctl", "bootout", "gui/501/dev.supermux.host")
        val child = h.healthyIfChild()
        h.probeFn = { if (bootout !in h.env.ran) h.desktop() else child(it) }
        h.sup.ensure()
        // e.g. the service is installed but appRunsAsService said no service was live: orphan
        h.sup.mode = HostSupervisor.Mode.ORPHAN
        h.sup.setBackground(false)
        assertFalse(Files.exists(h.ourPlist))
        assertTrue(bootout in h.env.ran)
        assertEquals(1, h.launches.size)
        assertEquals(running, h.sup.status.value)
    }

    @Test fun ensureRemovesAServiceLeftInstalledWithBackgroundOff() = runTest {
        val h = Harness(this, prefs = HostingPrefs(background = false))
        h.writeOurPlist()
        val bootout = listOf("launchctl", "bootout", "gui/501/dev.supermux.host")
        val child = h.healthyIfChild()
        h.probeFn = { if (bootout !in h.env.ran) h.desktop() else child(it) }
        h.sup.ensure()
        assertFalse(Files.exists(h.ourPlist), "it would start a broker at every login next to our child")
        assertEquals(1, h.launches.size)
        assertEquals(HostSupervisor.Mode.CHILD, h.sup.mode)
        assertEquals(running, h.sup.status.value)
        assertFalse(h.saved.background)
    }
}
