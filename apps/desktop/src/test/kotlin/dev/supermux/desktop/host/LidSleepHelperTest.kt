package dev.supermux.desktop.host

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LidSleepHelperTest {

    // ── user name validation ──

    @Test fun user_names_are_restricted_to_a_safe_set() {
        for (ok in listOf("ahmet", "john.doe", "a_b-c", "User1")) assertTrue(LidSleepHelper.validUser(ok), ok)
        for (bad in listOf(null, "", ".", "..", "-rf", "a b", "a'b", "a\"b", "a;b", "a/b", "\$(id)", "a`b`", "ä", "x".repeat(65))) {
            assertFalse(LidSleepHelper.validUser(bad), "$bad")
        }
    }

    @Test fun the_lease_lives_in_the_users_state_dir() {
        assertEquals("/Users/ahmet/.mux/state/lidsleep.lease", LidSleepHelper.leasePath("ahmet"))
        assertFailsWith<IllegalArgumentException> { LidSleepHelper.leasePath("a'b") }
        assertFailsWith<IllegalArgumentException> { LidSleepHelper.daemonScript("\$(reboot)") }
    }

    // ── the daemon script ──

    private val script = LidSleepHelper.daemonScript("ahmet")

    @Test fun the_script_bakes_in_the_user_and_lease() {
        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertTrue("LEASE='/Users/ahmet/.mux/state/lidsleep.lease'" in script)
        assertTrue("OWNER='ahmet'" in script)
        assertTrue("MAX_AGE=45" in script)
        assertTrue("/bin/sleep 10" in script)
    }

    @Test fun the_script_only_runs_fixed_argv_pmset() {
        val pmsetCalls = Regex("/usr/bin/pmset [^|\n]*").findAll(script).map { it.value.trim() }.toSet()
        assertEquals(
            setOf("/usr/bin/pmset -a disablesleep 0", "/usr/bin/pmset -a disablesleep 1", "/usr/bin/pmset -g"),
            pmsetCalls,
        )
        // Never a variable as a pmset argument, never anything read from the lease executed.
        val args = Regex("pmset -a disablesleep (\\S+)").findAll(script).map { it.groupValues[1] }.toList()
        assertTrue(args.isNotEmpty() && args.all { it == "0" || it == "1" }, "$args")
        assertFalse("eval" in script)
        assertFalse(Regex("\\. \"?\\\$LEASE").containsMatchIn(script))
        assertFalse(Regex("cat \"?\\\$LEASE").containsMatchIn(script))
    }

    @Test fun the_script_resets_to_zero_before_the_loop() {
        val reset = script.indexOf("/usr/bin/pmset -a disablesleep 0")
        val loop = script.indexOf("while :; do")
        assertTrue(reset in 0 until loop, "reset must come first")
    }

    @Test fun the_script_checks_symlink_owner_and_age() {
        val symlink = script.indexOf("[ -L \"\$LEASE\" ] && return 1")
        val regular = script.indexOf("[ -f \"\$LEASE\" ] || return 1")
        assertTrue(symlink >= 0, "symlink check")
        assertTrue(regular > symlink, "the symlink check runs before -f (which follows links)")
        assertTrue("/usr/bin/stat -f %Su \"\$LEASE\"" in script)
        assertTrue("[ \"\$owner\" = \"\$OWNER\" ] || return 1" in script)
        assertTrue("/usr/bin/stat -f %m \"\$LEASE\"" in script)
        assertTrue("case \"\$mtime\" in ''|*[!0-9]*) return 1 ;; esac" in script)
        assertTrue("[ \"\$age\" -ge 0 ] && [ \"\$age\" -lt \"\$MAX_AGE\" ]" in script)
    }

    @Test fun the_script_reads_the_state_and_only_changes_it_when_needed() {
        assertTrue("/usr/bin/pmset -g | /usr/bin/grep SleepDisabled" in script)
        assertTrue("[ \"\$(sleep_disabled)\" = \"1\" ] || /usr/bin/pmset -a disablesleep 1" in script)
        assertTrue("[ \"\$(sleep_disabled)\" = \"0\" ] || /usr/bin/pmset -a disablesleep 0" in script)
    }

    @Test fun the_script_is_valid_sh() {
        val sh = java.io.File("/bin/sh")
        if (!sh.canExecute()) return
        val f = Files.createTempFile("lidsleep", ".sh")
        try {
            Files.writeString(f, script)
            val p = ProcessBuilder("/bin/sh", "-n", f.toString()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), out)
        } finally {
            Files.deleteIfExists(f)
        }
    }

    // ── the plist ──

    @Test fun the_plist_runs_the_script_at_load_and_keeps_it_alive() {
        val p = LidSleepHelper.plist()
        assertTrue("<string>dev.supermux.lidsleep</string>" in p)
        assertTrue("<string>/bin/sh</string>" in p)
        assertTrue("<string>/Library/PrivilegedHelperTools/dev.supermux.lidsleep.sh</string>" in p)
        assertTrue(Regex("<key>RunAtLoad</key>\\s*<true/>").containsMatchIn(p))
        assertTrue(Regex("<key>KeepAlive</key>\\s*<true/>").containsMatchIn(p))
    }

    // ── the install / uninstall commands ──

    @Test fun the_install_command_copies_with_root_ownership_and_bootstraps() {
        val cmd = LidSleepHelper.installCommand("/private/var/folders/x_y/T/supermux-lidsleep1.sh", "/private/var/folders/x_y/T/supermux-lidsleep2.plist")
        assertEquals(
            "/bin/mkdir -p /Library/PrivilegedHelperTools && " +
                "/usr/bin/install -o root -g wheel -m 0755 '/private/var/folders/x_y/T/supermux-lidsleep1.sh' /Library/PrivilegedHelperTools/dev.supermux.lidsleep.sh && " +
                "/usr/bin/install -o root -g wheel -m 0644 '/private/var/folders/x_y/T/supermux-lidsleep2.plist' /Library/LaunchDaemons/dev.supermux.lidsleep.plist && " +
                "if /bin/launchctl print system/dev.supermux.lidsleep >/dev/null 2>&1; " +
                "then /bin/launchctl kickstart -k system/dev.supermux.lidsleep; " +
                "else /bin/launchctl bootstrap system /Library/LaunchDaemons/dev.supermux.lidsleep.plist; fi",
            cmd,
        )
    }

    @Test fun the_install_command_refuses_unsafe_paths() {
        for (bad in listOf("relative/x.sh", "/tmp/a b.sh", "/tmp/a'b.sh", "/tmp/\$(id).sh", "/tmp/a\"b", "/tmp/../etc/x", "/tmp/a;b")) {
            assertFailsWith<IllegalArgumentException>(bad) { LidSleepHelper.installCommand(bad, "/tmp/ok.plist") }
            assertFailsWith<IllegalArgumentException>(bad) { LidSleepHelper.installCommand("/tmp/ok.sh", bad) }
        }
    }

    @Test fun uninstall_boots_out_removes_and_gives_sleep_back() {
        assertEquals(
            "/bin/launchctl bootout system/dev.supermux.lidsleep 2>/dev/null; " +
                "/bin/rm -f /Library/LaunchDaemons/dev.supermux.lidsleep.plist /Library/PrivilegedHelperTools/dev.supermux.lidsleep.sh; " +
                "/usr/bin/pmset -a disablesleep 0",
            LidSleepHelper.uninstallCommand(),
        )
    }

    @Test fun one_admin_prompt_through_osascript() {
        val argv = LidSleepHelper.adminArgv("/bin/echo 'hi'", "Why \"quoted\"")
        assertEquals("/usr/bin/osascript", argv[0])
        assertEquals("-e", argv[1])
        assertEquals(
            "do shell script \"/bin/echo 'hi'\" with prompt \"Why \\\"quoted\\\"\" with administrator privileges",
            argv[2],
        )
        assertEquals(3, argv.size)
        assertEquals("\"a\\\\b\\\"c\"", LidSleepHelper.appleScriptString("a\\b\"c"))
    }

    @Test fun a_cancelled_prompt_is_recognised() {
        assertTrue(LidSleepHelper.wasCancelled("0:52: execution error: User canceled. (-128)"))
        assertFalse(LidSleepHelper.wasCancelled("launchctl: Bootstrap failed: 5"))
    }

    @Test fun install_is_refused_off_a_mac_without_running_anything() {
        val ran = mutableListOf<List<String>>()
        val linux = object : OsEnv by FakeOs(OsEnv.Os.LINUX) {
            override fun runResult(argv: List<String>): OsEnv.RunResult { ran += argv; return OsEnv.RunResult(0, "", "") }
        }
        assertTrue(LidSleepHelper.install("ahmet", linux) is LidSleepHelper.Outcome.Failed)
        assertTrue(LidSleepHelper.uninstall(linux) is LidSleepHelper.Outcome.Failed)
        assertTrue(ran.isEmpty())
    }

    @Test fun install_runs_one_osascript_and_cleans_its_temp_files() {
        val ran = mutableListOf<List<String>>()
        val mac = object : OsEnv by FakeOs(OsEnv.Os.MAC) {
            override fun runResult(argv: List<String>): OsEnv.RunResult { ran += argv; return OsEnv.RunResult(1, "", "User canceled. (-128)") }
        }
        val r = LidSleepHelper.install("ahmet", mac)
        assertEquals(LidSleepHelper.Outcome.Failed(LidSleepHelper.CANCELLED), r)
        assertEquals(1, ran.size)
        assertEquals("/usr/bin/osascript", ran[0][0])
        val tmp = Regex("'(/[^']+\\.sh)'").find(ran[0][2])!!.groupValues[1]
        assertFalse(Files.exists(java.nio.file.Path.of(tmp)), "temp script removed")
    }

    @Test fun installed_means_both_files_and_this_users_lease() {
        val dir = Files.createTempDirectory("lid")
        val script = dir.resolve("s.sh")
        val plist = dir.resolve("p.plist")
        assertFalse(LidSleepHelper.isInstalled("ahmet", script, plist))
        Files.writeString(script, LidSleepHelper.daemonScript("ahmet"))
        assertFalse(LidSleepHelper.isInstalled("ahmet", script, plist))
        Files.writeString(plist, LidSleepHelper.plist())
        assertTrue(LidSleepHelper.isInstalled("ahmet", script, plist))
        assertFalse(LidSleepHelper.isInstalled("someone", script, plist))
    }

    // ── the lease file ──

    @Test fun the_lease_is_created_0600_and_stamped_with_the_clock() {
        val dir = Files.createTempDirectory("lease")
        val lease = dir.resolve("state/lidsleep.lease")
        var now = 1_700_000_000_000L
        val ops = FileLeaseOps(lease) { now }
        ops.touch()
        assertTrue(Files.isRegularFile(lease))
        assertEquals(1_700_000_000_000L, Files.getLastModifiedTime(lease).toMillis())
        runCatching { assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(lease))) }
        now += 15_000
        ops.touch()
        assertEquals(1_700_000_015_000L, Files.getLastModifiedTime(lease).toMillis())
        ops.delete()
        assertFalse(Files.exists(lease))
        ops.delete() // idempotent
    }

    @Test fun a_symlink_at_the_lease_path_is_replaced_not_followed() {
        val dir = Files.createTempDirectory("lease")
        val target = dir.resolve("target")
        Files.writeString(target, "keep")
        val lease = dir.resolve("lidsleep.lease")
        Files.createSymbolicLink(lease, target)
        FileLeaseOps(lease) { 1_000L }.touch()
        assertFalse(Files.isSymbolicLink(lease))
        assertTrue(Files.isRegularFile(lease))
        assertEquals("keep", Files.readString(target))
        assertTrue(Files.getLastModifiedTime(target).toMillis() != 1_000L)
    }

    // ── the touch scheduler ──

    private class RecordingOps : LeaseFileOps {
        val events = mutableListOf<String>()
        override fun touch() { events += "touch" }
        override fun delete() { events += "delete" }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun the_lease_is_touched_every_15_s_and_deleted_on_stop() = runTest {
        val ops = RecordingOps()
        val lease = LidLease(ops, backgroundScope)
        lease.start()
        runCurrent()
        assertEquals(listOf("touch"), ops.events)
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(1, ops.events.count { it == "touch" })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, ops.events.count { it == "touch" })
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(4, ops.events.count { it == "touch" })
        assertTrue(lease.held)
        lease.stop()
        assertEquals("delete", ops.events.last())
        assertFalse(lease.held)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("delete", ops.events.last(), "no touch after stop")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun start_is_idempotent_and_stop_can_repeat() = runTest {
        val ops = RecordingOps()
        val lease = LidLease(ops, backgroundScope)
        lease.start()
        lease.start()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(2, ops.events.count { it == "touch" })
        lease.stop()
        lease.stop()
        assertEquals(listOf("touch", "touch", "delete", "delete"), ops.events)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun a_failing_touch_keeps_the_schedule() = runTest {
        var n = 0
        val ops = object : LeaseFileOps {
            override fun touch() { n++; if (n == 1) error("disk full") }
            override fun delete() = Unit
        }
        val lease = LidLease(ops, backgroundScope)
        lease.start()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(2, n)
        lease.stop()
    }
}

/** A minimal [OsEnv] for tests; override what a test needs. */
internal open class FakeOs(override val os: OsEnv.Os) : OsEnv {
    override val home: java.nio.file.Path = java.nio.file.Path.of("/home/u")
    override val localAppData: java.nio.file.Path = home
    override val uid: Long? = 501
    override val xdgRuntimeDir: String? = null
    override fun hasCommand(name: String) = false
    override fun run(argv: List<String>) = runResult(argv).exit == 0
    override fun runResult(argv: List<String>) = OsEnv.RunResult(-1, "", "not in tests")
    override fun sleep(ms: Long) = Unit
    override fun runCapture(argv: List<String>): String? = null
}
