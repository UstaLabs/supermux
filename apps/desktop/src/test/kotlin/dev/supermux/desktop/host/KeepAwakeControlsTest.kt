package dev.supermux.desktop.host

import dev.supermux.host.PairedHost
import dev.supermux.net.KeepAwakePatch
import dev.supermux.net.KeepAwakeState
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeepAwakeControlsTest {

    // ── the PUT goes to the local broker with "This computer"'s token ──

    private val hosts = listOf(
        PairedHost(recordId = "r1", hostId = "h1", displayName = "Mac", directUrl = "http://127.0.0.1:9898", token = "stored"),
        PairedHost(recordId = "r2", hostId = "h2", displayName = "Pi", directUrl = "https://pi.example", token = "other"),
    )

    @Test fun the_change_uses_the_stored_this_computer_token_on_loopback() = runTest {
        val calls = mutableListOf<Triple<String, String, KeepAwakePatch>>()
        val r = setKeepAwakeOnLocalBroker(
            localUrl = "http://127.0.0.1:9898", hostId = "h1", hosts = hosts,
            patch = KeepAwakePatch(enabled = false), log = {},
        ) { url, token, patch -> calls += Triple(url, token, patch); KeepAwakeState(enabled = false) }
        assertEquals(listOf(Triple("http://127.0.0.1:9898", "stored", KeepAwakePatch(enabled = false))), calls)
        assertEquals(false, r?.enabled)
    }

    @Test fun no_token_means_no_call() = runTest {
        var called = false
        val r = setKeepAwakeOnLocalBroker("http://127.0.0.1:9898", "h9", hosts.drop(1), KeepAwakePatch(enabled = true), {}) { _, _, _ ->
            called = true; null
        }
        assertNull(r)
        assertFalse(called)
    }

    @Test fun a_refused_change_is_null() = runTest {
        assertNull(setKeepAwakeOnLocalBroker("http://127.0.0.1:9898", "h1", hosts, KeepAwakePatch(onBattery = false), {}) { _, _, _ -> null })
    }

    // ── the Linux app-held fallback: only for a denied, enabled keep-awake ──

    @Test fun the_app_holds_only_when_the_broker_was_denied() {
        assertTrue(KeepAwakeControls.appShouldHold(KeepAwakeState(enabled = true, active = false, reasonCode = "denied")))
        assertFalse(KeepAwakeControls.appShouldHold(null))
        assertFalse(KeepAwakeControls.appShouldHold(KeepAwakeState(enabled = false, active = false, reasonCode = "denied")))
        assertFalse(KeepAwakeControls.appShouldHold(KeepAwakeState(enabled = true, active = true)))
        assertFalse(KeepAwakeControls.appShouldHold(KeepAwakeState(enabled = true, active = false, reasonCode = "on_battery")))
        assertFalse(KeepAwakeControls.appShouldHold(KeepAwakeState(enabled = true, active = false, reasonCode = "gave_up")))
    }

    @Test fun the_linux_fallback_chain_matches_the_brokers() {
        val all = AppSleepInhibitor.candidates({ "/usr/bin/$it" }, 4242)
        assertEquals(listOf("systemd-inhibit", "gnome-session-inhibit", "kde-inhibit"), all.map { it.first })
        val systemd = all[0].second
        assertEquals(
            listOf("/usr/bin/systemd-inhibit", "--what=sleep", "--who=supermux", "--why=Hosting agents", "--mode=block", "/bin/sh", "-c"),
            systemd.take(7),
        )
        assertEquals("4242", systemd.last())
        assertTrue("kill -0 \"\$app\"" in systemd[7])
        val gnome = all[1].second
        assertEquals(listOf("/usr/bin/gnome-session-inhibit", "--inhibit", "suspend", "--inhibit-only"), gnome.takeLast(4))
        assertEquals(listOf("/usr/bin/kde-inhibit", "--power", "/bin/sh", "-c"), all[2].second.take(4))
        // Only what exists is tried.
        assertEquals(listOf("kde-inhibit"), AppSleepInhibitor.candidates({ if (it == "kde-inhibit") "/k" else null }, 1).map { it.first })
    }

    @Test fun the_wait_scripts_are_valid_sh() {
        if (!java.io.File("/bin/sh").canExecute()) return
        for (body in listOf(AppSleepInhibitor.WAIT_SH, AppSleepInhibitor.BOUND_SH)) {
            val f = Files.createTempFile("wait", ".sh")
            try {
                Files.writeString(f, body)
                assertEquals(0, ProcessBuilder("/bin/sh", "-n", f.toString()).start().waitFor())
            } finally {
                Files.deleteIfExists(f)
            }
        }
    }

    @Test fun a_refusing_inhibitor_falls_through_and_a_spawn_error_never_throws() {
        if (!java.io.File("/bin/sh").canExecute()) return
        val spawned = mutableListOf<String>()
        val inh = AppSleepInhibitor(
            which = { "/x/$it" },
            spawn = { argv ->
                spawned += argv.first()
                when {
                    argv.first().endsWith("systemd-inhibit") -> error("boom") // can't even start
                    argv.first() == "/bin/sh" -> ProcessBuilder("/bin/sh", "-c", "exit 1").start() // refused at once
                    else -> ProcessBuilder("/bin/sh", "-c", "sleep 30").start() // holds
                }
            },
            settleMs = 300,
        )
        assertTrue(inh.hold())
        assertTrue(inh.held)
        assertEquals(listOf("/x/systemd-inhibit", "/bin/sh", "/x/kde-inhibit"), spawned)
        inh.release(final = true)
        assertFalse(inh.held)
        assertFalse(inh.hold(), "never again after the quit")
    }

    @Test fun a_final_release_does_not_wait_for_a_settling_candidate() {
        if (!java.io.File("/bin/sh").canExecute()) return
        val inh = AppSleepInhibitor(
            which = { if (it == "systemd-inhibit") "/x/systemd-inhibit" else null },
            spawn = { ProcessBuilder("/bin/sh", "-c", "sleep 30").start() },
            settleMs = 20_000,
        )
        val holder = Thread { inh.hold() }.apply { start() }
        Thread.sleep(300) // hold() is now settling the candidate
        val t0 = System.currentTimeMillis()
        inh.release(final = true)
        assertTrue(System.currentTimeMillis() - t0 < 3_000, "release must not wait out the settle")
        holder.join(5_000)
        assertFalse(holder.isAlive)
        assertFalse(inh.held)
    }

    @Test fun mac_on_battery_parser() {
        assertEquals(true, PowerFacts.macOnBattery("Now drawing from 'Battery Power'\n"))
        assertEquals(false, PowerFacts.macOnBattery("Now drawing from 'AC Power'\n"))
        assertNull(PowerFacts.macOnBattery(""))
    }

    // ── power facts ──

    @Test fun battery_and_filevault_parsers() {
        assertTrue(PowerFacts.macHasBattery("Now drawing from 'Battery Power'\n -InternalBattery-0 (id=123)\t87%; discharging;"))
        assertFalse(PowerFacts.macHasBattery("Now drawing from 'AC Power'\n"))
        assertTrue(PowerFacts.windowsHasBattery("1\r\n"))
        assertFalse(PowerFacts.windowsHasBattery("0"))
        assertFalse(PowerFacts.windowsHasBattery(null))
        assertTrue(PowerFacts.fileVaultOff("FileVault is Off.\n"))
        assertFalse(PowerFacts.fileVaultOff("FileVault is On.\n"))
        assertFalse(PowerFacts.fileVaultOff(null))
    }

    @Test fun linux_battery_ignores_device_scoped_supplies() {
        val root = Files.createTempDirectory("ps")
        fun supply(name: String, type: String, scope: String?) {
            val d = Files.createDirectories(root.resolve(name))
            Files.writeString(d.resolve("type"), "$type\n")
            scope?.let { Files.writeString(d.resolve("scope"), "$it\n") }
        }
        supply("AC", "Mains", null)
        supply("hidpp_battery_0", "Battery", "Device")
        assertFalse(PowerFacts.linuxHasBattery(root))
        supply("BAT0", "Battery", null)
        assertTrue(PowerFacts.linuxHasBattery(root))
        assertFalse(PowerFacts.linuxHasBattery(root.resolve("missing")))
    }

    // ── /host carries keepAwake ──

    @Test fun the_probe_parses_keep_awake() {
        val r = HostProber.parse(
            """{"hostId":"h1","name":"m","protocolVersion":1,"keepAwake":{"enabled":true,"onBattery":false,"active":false,"supported":true,"reason":"Paused","reasonCode":"on_battery"}}""",
        ) as HostProbeResult.Supermux
        assertEquals(KeepAwakeState(enabled = true, onBattery = false, active = false, supported = true, reason = "Paused", reasonCode = "on_battery"), r.keepAwake)
        assertNull((HostProber.parse("""{"hostId":"h1","name":"m","protocolVersion":1}""") as HostProbeResult.Supermux).keepAwake)
    }
}

/** The lid-closed flow and the quit release, over a supervisor with fakes (no admin prompt, no pmset). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeepAwakeLidFlowTest {
    private class Ops : LeaseFileOps {
        val events = mutableListOf<String>()
        override fun touch() { events += "touch" }
        override fun delete() { events += "delete" }
    }

    private class Rig(
        val ts: kotlinx.coroutines.test.TestScope,
        prefs: HostingPrefs = HostingPrefs(),
        var state: LidSleepHelper.InstallState = LidSleepHelper.InstallState.NOT_INSTALLED,
        home: String = "/Users/ahmet",
    ) {
        var saved = prefs
        val ops = Ops()
        var installs = 0
        var uninstalls = 0
        var installResult: LidSleepHelper.Outcome = LidSleepHelper.Outcome.Ok
        var uninstallResult: LidSleepHelper.Outcome = LidSleepHelper.Outcome.Ok
        var onBattery: Boolean? = false
        val puts = mutableListOf<KeepAwakePatch>()
        val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(ts.testScheduler)
        val sup = HostSupervisor(
            stateDir = Files.createTempDirectory("ka-state"),
            loadPrefs = { saved },
            savePrefs = { saved = it },
            probe = { HostProbeResult.PortFree },
            osEnv = FakeOs(OsEnv.Os.MAC),
            hostName = "testbox",
            io = dispatcher,
            scope = ts.backgroundScope,
            log = {},
        )
        val lease = LidLease(ops, ts.backgroundScope)
        val controls = KeepAwakeControls(
            supervisor = sup,
            os = FakeOs(OsEnv.Os.MAC),
            user = "ahmet",
            home = home,
            facts = PowerFactsCache(FakeOs(OsEnv.Os.MAC), dispatcher, battery = { true }, fileVault = { true }),
            scope = ts.backgroundScope,
            io = dispatcher,
            lease = lease,
            lidInstallState = { state },
            lidInstall = {
                installs++
                installResult.also { if (it == LidSleepHelper.Outcome.Ok) state = LidSleepHelper.InstallState.INSTALLED }
            },
            lidUninstall = {
                uninstalls++
                uninstallResult.also { if (it == LidSleepHelper.Outcome.Ok) state = LidSleepHelper.InstallState.NOT_INSTALLED }
            },
            onBatteryNow = { onBattery },
            inhibitor = null,
            put = { _, _, patch -> puts += patch; KeepAwakeState(enabled = patch.enabled ?: true, active = patch.enabled ?: true) },
        )
    }

    private fun kotlinx.coroutines.test.TestScope.settle() = testScheduler.runCurrent()

    @Test fun ticking_installs_once_then_holds_the_lease_and_saves_the_choice() = runTest {
        val r = Rig(this)
        r.controls.start()
        settle()
        r.controls.setLidClosed(true)
        settle()
        assertEquals(1, r.installs)
        assertEquals(LidSleepHelper.InstallState.INSTALLED, r.controls.lid.value.state)
        assertTrue(r.controls.lid.value.on)
        assertTrue(r.saved.lidClosed)
        assertEquals("touch", r.ops.events.first())
        // Untick: delete the lease, save off. Tick again: no second install.
        r.controls.setLidClosed(false)
        settle()
        assertEquals("delete", r.ops.events.last())
        assertFalse(r.saved.lidClosed)
        assertFalse(r.controls.lid.value.on)
        r.controls.setLidClosed(true)
        settle()
        assertEquals(1, r.installs)
        r.sup.quit()
    }

    @Test fun a_cancelled_install_leaves_it_off_with_the_reason() = runTest {
        val r = Rig(this)
        r.installResult = LidSleepHelper.Outcome.Failed(LidSleepHelper.CANCELLED)
        r.controls.start()
        settle()
        r.controls.setLidClosed(true)
        settle()
        assertFalse(r.controls.lid.value.on)
        assertFalse(r.saved.lidClosed)
        assertEquals(LidSleepHelper.CANCELLED, r.controls.lid.value.error)
        assertTrue(r.ops.events.none { it == "touch" })
    }

    @Test fun any_quit_deletes_the_lease_and_latches_it_shut() = runTest {
        val r = Rig(this, state = LidSleepHelper.InstallState.INSTALLED)
        r.controls.start()
        settle()
        r.controls.setLidClosed(true)
        settle()
        assertTrue(r.ops.events.contains("touch"))
        r.sup.quit()
        assertEquals("delete", r.ops.events.last())
        // Nothing can start it again after the quit: not a tick, not a lid input change.
        r.controls.setLidClosed(true)
        r.lease.start()
        testScheduler.advanceTimeBy(60_000)
        settle()
        assertEquals("delete", r.ops.events.last(), "never touched again after the quit")
    }

    @Test fun a_saved_choice_resumes_the_lease_at_launch_when_installed() = runTest {
        val r = Rig(this, prefs = HostingPrefs(lidClosed = true), state = LidSleepHelper.InstallState.INSTALLED)
        r.controls.start()
        settle()
        assertEquals(listOf("touch"), r.ops.events)
        assertTrue(r.controls.lid.value.on)
        r.sup.quit()
    }

    @Test fun a_saved_choice_without_the_helper_shows_off_and_does_not_touch() = runTest {
        val r = Rig(this, prefs = HostingPrefs(lidClosed = true), state = LidSleepHelper.InstallState.NOT_INSTALLED)
        r.controls.start()
        settle()
        assertTrue(r.ops.events.isEmpty())
        assertTrue(r.controls.lid.value.pref)
        assertFalse(r.controls.lid.value.on)
    }

    @Test fun another_users_helper_shows_off_and_ticking_reinstalls() = runTest {
        val r = Rig(this, prefs = HostingPrefs(lidClosed = true), state = LidSleepHelper.InstallState.OTHER_USER)
        r.controls.start()
        settle()
        assertFalse(r.controls.lid.value.on)
        assertTrue(r.ops.events.isEmpty())
        r.controls.setLidClosed(true)
        settle()
        assertEquals(1, r.installs)
        assertTrue(r.controls.lid.value.on)
        r.sup.quit()
    }

    @Test fun a_home_outside_users_cannot_tick() = runTest {
        val r = Rig(this, home = "/Volumes/Data/ahmet")
        r.controls.start()
        settle()
        assertFalse(r.controls.lid.value.homeSupported)
        r.controls.setLidClosed(true)
        settle()
        assertEquals(0, r.installs)
        assertFalse(r.controls.lid.value.on)
    }

    @Test fun with_also_on_battery_off_the_lease_pauses_on_battery() = runTest {
        val r = Rig(this, state = LidSleepHelper.InstallState.INSTALLED)
        r.controls.start()
        settle()
        r.controls.setLidClosed(true)
        settle()
        assertEquals(listOf("touch"), r.ops.events)
        // The broker says "Also on battery" is off and it paused: lid sleep comes back.
        r.sup.publishKeepAwake(KeepAwakeState(enabled = true, onBattery = false, active = false, reasonCode = "on_battery"))
        settle()
        assertEquals("delete", r.ops.events.last())
        assertTrue(r.controls.lid.value.pausedOnBattery)
        assertTrue(r.controls.lid.value.on, "still chosen; just paused")
        // Back on AC: it holds again.
        r.sup.publishKeepAwake(KeepAwakeState(enabled = true, onBattery = false, active = true))
        settle()
        assertEquals("touch", r.ops.events.last())
        r.sup.quit()
    }

    @Test fun the_local_battery_check_pauses_too_when_keep_awake_is_off() = runTest {
        val r = Rig(this, prefs = HostingPrefs(lidClosed = true), state = LidSleepHelper.InstallState.INSTALLED)
        r.sup.publishKeepAwake(KeepAwakeState(enabled = false, onBattery = false))
        r.onBattery = true
        r.controls.start()
        settle()
        testScheduler.advanceTimeBy(31_000)
        settle()
        assertTrue(r.controls.lid.value.pausedOnBattery)
        assertEquals("delete", r.ops.events.lastOrNull() ?: "delete")
        assertFalse(r.lease.held)
        r.sup.quit()
    }

    @Test fun with_also_on_battery_on_it_holds_on_battery() = runTest {
        val r = Rig(this, prefs = HostingPrefs(lidClosed = true), state = LidSleepHelper.InstallState.INSTALLED)
        r.sup.publishKeepAwake(KeepAwakeState(enabled = true, onBattery = true, active = true))
        r.onBattery = true
        r.controls.start()
        settle()
        testScheduler.advanceTimeBy(31_000)
        settle()
        assertFalse(r.controls.lid.value.pausedOnBattery)
        assertTrue(r.lease.held)
        r.sup.quit()
    }

    @Test fun uninstall_stops_the_lease_and_clears_the_choice() = runTest {
        val r = Rig(this, state = LidSleepHelper.InstallState.INSTALLED)
        r.controls.start()
        settle()
        r.controls.setLidClosed(true)
        settle()
        r.controls.uninstallLidHelper()
        settle()
        assertEquals(1, r.uninstalls)
        assertEquals(LidSleepHelper.InstallState.NOT_INSTALLED, r.controls.lid.value.state)
        assertFalse(r.controls.lid.value.on)
        assertFalse(r.saved.lidClosed)
        assertEquals("delete", r.ops.events.last())
    }

    @Test fun a_cancelled_uninstall_keeps_everything() = runTest {
        val r = Rig(this, state = LidSleepHelper.InstallState.INSTALLED)
        r.uninstallResult = LidSleepHelper.Outcome.Failed(LidSleepHelper.UNINSTALL_CANCELLED)
        r.controls.start()
        settle()
        r.controls.uninstallLidHelper()
        settle()
        assertEquals("Cancelled. The lid helper is still installed.", r.controls.lid.value.error)
        assertEquals(LidSleepHelper.InstallState.INSTALLED, r.controls.lid.value.state)
    }

    @Test fun the_battery_and_filevault_facts_load_once() = runTest {
        val r = Rig(this)
        r.controls.start()
        settle()
        assertEquals(true, r.controls.hasBattery.value)
        assertEquals(true, r.controls.fileVaultOff.value)
        r.sup.quit()
    }

    @Test fun a_keep_awake_change_publishes_the_brokers_answer() = runTest {
        val r = Rig(this)
        r.controls.hosts = { listOf(PairedHost(recordId = "r1", hostId = null, displayName = "Mac", directUrl = "http://127.0.0.1:9898", token = "t")) }
        assertTrue(r.controls.setEnabled(false))
        assertEquals(listOf(KeepAwakePatch(enabled = false)), r.puts)
        assertEquals(false, r.sup.keepAwake.value?.enabled)
        assertNull(r.controls.writeError.value)
        r.controls.hosts = { emptyList() }
        assertFalse(r.controls.setOnBattery(false))
        assertEquals(KeepAwakeControls.WRITE_FAILED, r.controls.writeError.value)
    }

    @Test fun the_battery_pause_rule() {
        fun ka(onBattery: Boolean, code: String? = null) = KeepAwakeState(enabled = true, onBattery = onBattery, reasonCode = code)
        assertTrue(KeepAwakeControls.pausedOnBattery(ka(false, "on_battery"), null))
        assertTrue(KeepAwakeControls.pausedOnBattery(ka(false), true))
        assertFalse(KeepAwakeControls.pausedOnBattery(ka(false), false))
        assertFalse(KeepAwakeControls.pausedOnBattery(ka(true, "on_battery"), true), "Also on battery ON holds")
        assertFalse(KeepAwakeControls.pausedOnBattery(null, true))
    }
}
