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

    private class Rig(val ts: kotlinx.coroutines.test.TestScope, prefs: HostingPrefs = HostingPrefs(), var installed: Boolean = false) {
        var saved = prefs
        val ops = Ops()
        var installs = 0
        var uninstalls = 0
        var installResult: LidSleepHelper.Outcome = LidSleepHelper.Outcome.Ok
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
        val controls = KeepAwakeControls(
            supervisor = sup,
            os = FakeOs(OsEnv.Os.MAC),
            user = "ahmet",
            facts = PowerFactsCache(FakeOs(OsEnv.Os.MAC), dispatcher, battery = { true }, fileVault = { true }),
            scope = ts.backgroundScope,
            io = dispatcher,
            lease = LidLease(ops, ts.backgroundScope),
            lidInstalledCheck = { installed },
            lidInstall = { installs++; installResult.also { if (it == LidSleepHelper.Outcome.Ok) installed = true } },
            lidUninstall = { uninstalls++; installed = false; LidSleepHelper.Outcome.Ok },
            inhibitor = null,
            put = { _, _, patch -> puts += patch; KeepAwakeState(enabled = patch.enabled ?: true, active = patch.enabled ?: true) },
        )
    }

    @Test fun ticking_installs_once_then_holds_the_lease_and_saves_the_choice() = runTest {
        val r = Rig(this)
        r.controls.start()
        runCurrentSafe()
        r.controls.setLidClosed(true)
        runCurrentSafe()
        assertEquals(1, r.installs)
        assertTrue(r.controls.lidHelperInstalled.value)
        assertTrue(r.controls.lidClosed.value)
        assertTrue(r.saved.lidClosed)
        assertEquals("touch", r.ops.events.first())
        // Untick: delete the lease, save off. Tick again: no second install.
        r.controls.setLidClosed(false)
        assertEquals("delete", r.ops.events.last())
        assertFalse(r.saved.lidClosed)
        r.controls.setLidClosed(true)
        runCurrentSafe()
        assertEquals(1, r.installs)
        r.sup.quit()
    }

    @Test fun a_cancelled_install_leaves_it_off_with_the_reason() = runTest {
        val r = Rig(this)
        r.installResult = LidSleepHelper.Outcome.Failed(LidSleepHelper.CANCELLED)
        r.controls.start()
        runCurrentSafe()
        r.controls.setLidClosed(true)
        runCurrentSafe()
        assertFalse(r.controls.lidClosed.value)
        assertFalse(r.saved.lidClosed)
        assertEquals(LidSleepHelper.CANCELLED, r.controls.lidError.value)
        assertTrue(r.ops.events.none { it == "touch" })
    }

    @Test fun any_quit_deletes_the_lease() = runTest {
        val r = Rig(this, installed = true)
        r.controls.start()
        runCurrentSafe()
        r.controls.setLidClosed(true)
        runCurrentSafe()
        assertTrue(r.ops.events.contains("touch"))
        r.sup.quit()
        assertEquals("delete", r.ops.events.last())
        testScheduler.advanceTimeBy(60_000)
        runCurrentSafe()
        assertEquals("delete", r.ops.events.last(), "never touched again after the quit")
    }

    @Test fun a_saved_choice_resumes_the_lease_at_launch_when_installed() = runTest {
        val r = Rig(this, prefs = HostingPrefs(lidClosed = true), installed = true)
        r.controls.start()
        runCurrentSafe()
        assertEquals(listOf("touch"), r.ops.events)
        r.sup.quit()
    }

    @Test fun a_saved_choice_without_the_helper_does_not_touch() = runTest {
        val r = Rig(this, prefs = HostingPrefs(lidClosed = true), installed = false)
        r.controls.start()
        runCurrentSafe()
        assertTrue(r.ops.events.isEmpty())
    }

    @Test fun uninstall_stops_the_lease_and_clears_the_choice() = runTest {
        val r = Rig(this, installed = true)
        r.controls.start()
        runCurrentSafe()
        r.controls.setLidClosed(true)
        runCurrentSafe()
        r.controls.uninstallLidHelper()
        assertEquals(1, r.uninstalls)
        assertFalse(r.controls.lidHelperInstalled.value)
        assertFalse(r.controls.lidClosed.value)
        assertFalse(r.saved.lidClosed)
        assertEquals("delete", r.ops.events.last())
    }

    @Test fun the_battery_and_filevault_facts_load_once() = runTest {
        val r = Rig(this)
        r.controls.start()
        runCurrentSafe()
        assertEquals(true, r.controls.hasBattery.value)
        assertEquals(true, r.controls.fileVaultOff.value)
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

    private fun kotlinx.coroutines.test.TestScope.runCurrentSafe() = testScheduler.runCurrent()
}
