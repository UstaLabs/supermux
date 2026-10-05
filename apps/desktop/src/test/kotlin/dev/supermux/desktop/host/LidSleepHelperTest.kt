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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LidSleepHelperTest {

    // ── user name validation ──

    @Test fun user_names_are_restricted_to_a_safe_set() {
        for (ok in listOf("alex", "john.doe", "a_b-c", "User1")) assertTrue(LidSleepHelper.validUser(ok), ok)
        for (bad in listOf(null, "", ".", "..", "-rf", "a b", "a'b", "a\"b", "a;b", "a/b", "\$(id)", "a`b`", "ä", "x".repeat(65))) {
            assertFalse(LidSleepHelper.validUser(bad), "$bad")
        }
    }

    @Test fun the_lease_lives_in_the_users_state_dir() {
        assertEquals("/Users/alex/.mux/state/lidsleep.lease", LidSleepHelper.leasePath("alex"))
        assertFailsWith<IllegalArgumentException> { LidSleepHelper.leasePath("a'b") }
        assertFailsWith<IllegalArgumentException> { LidSleepHelper.daemonScript("\$(reboot)") }
    }

    // ── the daemon script ──

    private val script = LidSleepHelper.daemonScript("alex", "/Applications/supermux.app")

    @Test fun the_script_bakes_in_the_user_lease_and_app() {
        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertTrue("LEASE='/Users/alex/.mux/state/lidsleep.lease'" in script)
        assertTrue("OWNER='alex'" in script)
        assertTrue("APP='/Applications/supermux.app'" in script)
        assertTrue("MARKER='/Library/PrivilegedHelperTools/dev.supermux.lidsleep.held'" in script)
        assertTrue("MAX_AGE=45" in script)
        assertTrue("\"\$SLEEP\" 10 &" in script)
        assertTrue(script.trimEnd().endsWith("main \"\$@\""))
        assertTrue("APP=''" in LidSleepHelper.daemonScript("alex", null))
        assertFailsWith<IllegalArgumentException> { LidSleepHelper.daemonScript("alex", "/Applications/my app.app") }
    }

    @Test fun the_script_only_runs_fixed_argv_pmset() {
        assertTrue("PMSET=/usr/bin/pmset" in script)
        val calls = Regex("\"\\\$PMSET\" [^\n|]*").findAll(script).map { it.value.trim() }.toSet()
        assertEquals(setOf("\"\$PMSET\" -g 2>/dev/null", "\"\$PMSET\" -a disablesleep 1", "\"\$PMSET\" -a disablesleep 0; then"), calls)
        val args = Regex("pmset -a disablesleep (\\S+)").findAll(script).map { it.groupValues[1].trimEnd(',', ';') }.toList()
        assertTrue(args.isNotEmpty() && args.all { it == "0" || it == "1" }, "$args")
        assertFalse("eval" in script)
        assertFalse(Regex("\\. \"?\\\$LEASE").containsMatchIn(script))
        assertFalse(Regex("cat \"?\\\$LEASE").containsMatchIn(script))
    }

    @Test fun the_state_is_parsed_exactly() {
        assertTrue("\"\$PMSET\" -g 2>/dev/null | /usr/bin/awk '\$1==\"SleepDisabled\"{print \$2; exit}'" in script)
        // Hold skips only on exactly "1"; release skips only on exactly "0".
        assertTrue("[ \"\$(sleep_disabled)\" = \"1\" ] && return 0" in script)
        assertTrue("if [ \"\$(sleep_disabled)\" = \"0\" ] || \"\$PMSET\" -a disablesleep 0; then" in script)
    }

    @Test fun only_what_the_daemon_set_is_released() {
        val hold = script.substringAfter("hold() {").substringBefore("}")
        assertTrue(hold.indexOf(": > \"\$MARKER\"") in 0 until hold.indexOf("-a disablesleep 1"), "marker before setting 1")
        val release = script.substringAfter("release() {").substringBefore("\n}")
        assertTrue(release.trimStart().startsWith("[ -f \"\$MARKER\" ] || return 0"), "no marker, no reset")
        assertTrue("/bin/rm -f \"\$MARKER\"" in release)
        // Start: release() (marker-gated) before the loop, never an unconditional pmset 0.
        val main = script.substringAfter("main() {")
        assertTrue(main.indexOf("  release\n") in 0 until main.indexOf("while :; do"))
        assertFalse(Regex("^\\s*\"\\\$PMSET\" -a disablesleep 0\\s*$", RegexOption.MULTILINE).containsMatchIn(script))
    }

    @Test fun signals_give_back_what_was_held_and_the_sleep_is_interruptible() {
        assertTrue("trap on_signal TERM INT HUP" in script)
        val onSignal = script.substringAfter("on_signal() {").substringBefore("\n}")
        assertTrue("release" in onSignal && "exit 0" in onSignal)
        assertTrue("\"\$SLEEP\" 10 &\n    sleeper=\$!\n    wait \"\$sleeper\"" in script)
    }

    @Test fun the_script_checks_symlink_owner_and_age() {
        val symlink = script.indexOf("[ -L \"\$LEASE\" ] && return 1")
        val regular = script.indexOf("[ -f \"\$LEASE\" ] || return 1")
        assertTrue(symlink >= 0, "symlink check")
        assertTrue(regular > symlink, "the symlink check runs before -f (which follows links)")
        assertTrue("\"\$STAT\" -f %Su \"\$LEASE\"" in script)
        assertTrue("[ \"\$owner\" = \"\$OWNER\" ] || return 1" in script)
        assertTrue("\"\$STAT\" -f %m \"\$LEASE\"" in script)
        assertTrue("case \"\$mtime\" in ''|*[!0-9]*) return 1 ;; esac" in script)
        assertTrue("[ \"\$age\" -ge 0 ] && [ \"\$age\" -lt \"\$MAX_AGE\" ]" in script)
    }

    @Test fun the_daemon_removes_itself_once_the_app_is_gone_for_a_day() {
        assertTrue("APP_CHECK_LOOPS=60" in script)
        assertTrue("APP_GONE_S=86400" in script)
        assertTrue("APP_MISSING='/Library/PrivilegedHelperTools/dev.supermux.lidsleep.app-missing'" in script)
        val gone = script.substringAfter("app_gone_too_long() {").substringBefore("\n}")
        assertTrue("[ -n \"\$APP\" ] || return 1" in gone, "no baked app: never removes itself")
        assertTrue("if [ -e \"\$APP\" ]; then" in gone)
        assertTrue("[ \$((now - since)) -gt \"\$APP_GONE_S\" ]" in gone)
        val remove = script.substringAfter("remove_self() {").substringBefore("\n}").trim().lines().map { it.trim() }
        assertEquals(
            listOf(
                "release",
                "/bin/rm -f \"\$APP_MISSING\" \"\$SELF_PLIST\" \"\$SELF_SCRIPT\"",
                "\"\$LAUNCHCTL\" bootout system/dev.supermux.lidsleep",
                "exit 0",
            ),
            remove,
        )
        assertTrue("if app_gone_too_long; then remove_self; fi" in script)
    }

    @Test fun the_script_is_valid_sh() {
        if (!java.io.File("/bin/sh").canExecute()) return
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

    // ── the app bundle and the home folder ──

    @Test fun the_app_bundle_comes_from_a_path_inside_it() {
        assertEquals("/Applications/supermux.app", LidSleepHelper.appBundlePath(listOf("/Applications/supermux.app/Contents/MacOS/supermux")))
        assertEquals(
            "/Users/a/Apps/supermux.app",
            LidSleepHelper.appBundlePath(listOf(null, "/Users/a/Apps/supermux.app/Contents/app/resources")),
        )
        assertNull(LidSleepHelper.appBundlePath(listOf("/usr/bin/java", "/home/u/repo/apps/desktop")))
        assertNull(LidSleepHelper.appBundlePath(listOf("/Applications/my app.app/Contents/MacOS/x")))
    }

    @Test fun only_a_users_home_under_users_is_supported() {
        assertTrue(LidSleepHelper.homeSupported("alex", "/Users/alex"))
        assertTrue(LidSleepHelper.homeSupported("alex", "/Users/alex/"))
        assertFalse(LidSleepHelper.homeSupported("alex", "/Volumes/Data/alex"))
        assertFalse(LidSleepHelper.homeSupported("alex", null))
        assertFalse(LidSleepHelper.homeSupported("a b", "/Users/a b"))
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

    @Test fun the_install_command_carries_the_files_inside_it_as_base64() {
        val plist = LidSleepHelper.plist()
        val cmd = LidSleepHelper.installCommand(script, plist)
        val s64 = LidSleepHelper.base64(script)
        val p64 = LidSleepHelper.base64(plist)
        assertEquals(
            "{ /bin/mkdir -p /Library/PrivilegedHelperTools && " +
                "/bin/echo '$s64' | /usr/bin/base64 -D > /Library/PrivilegedHelperTools/.dev.supermux.lidsleep.sh.new && " +
                "/usr/bin/install -o root -g wheel -m 0755 /Library/PrivilegedHelperTools/.dev.supermux.lidsleep.sh.new /Library/PrivilegedHelperTools/dev.supermux.lidsleep.sh && " +
                "/bin/echo '$p64' | /usr/bin/base64 -D > /Library/PrivilegedHelperTools/.dev.supermux.lidsleep.plist.new && " +
                "/usr/bin/install -o root -g wheel -m 0644 /Library/PrivilegedHelperTools/.dev.supermux.lidsleep.plist.new /Library/LaunchDaemons/dev.supermux.lidsleep.plist && " +
                "if /bin/launchctl print system/dev.supermux.lidsleep >/dev/null 2>&1; " +
                "then /bin/launchctl kickstart -k system/dev.supermux.lidsleep; " +
                "else /bin/launchctl bootstrap system /Library/LaunchDaemons/dev.supermux.lidsleep.plist; fi; }; " +
                "s=\$?; /bin/rm -f /Library/PrivilegedHelperTools/.dev.supermux.lidsleep.sh.new /Library/PrivilegedHelperTools/.dev.supermux.lidsleep.plist.new; exit \$s",
            cmd,
        )
        assertEquals(script, String(java.util.Base64.getDecoder().decode(s64)))
        assertTrue(Regex("[A-Za-z0-9+/=]+").matches(s64) && Regex("[A-Za-z0-9+/=]+").matches(p64))
        // Nothing from a user-writable temp dir, and no double quote for the AppleScript string to escape.
        assertFalse("/tmp" in cmd || "/var/folders" in cmd || "/private/" in cmd)
        assertFalse('"' in cmd)
    }

    @Test fun the_install_command_is_valid_sh() {
        if (!java.io.File("/bin/sh").canExecute()) return
        val p = ProcessBuilder("/bin/sh", "-n", "-c", LidSleepHelper.installCommand(script, LidSleepHelper.plist())).redirectErrorStream(true).start()
        assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
    }

    @Test fun uninstall_boots_out_resets_only_what_was_held_and_removes_everything() {
        assertEquals(
            "/bin/launchctl bootout system/dev.supermux.lidsleep 2>/dev/null; " +
                "if [ -f /Library/PrivilegedHelperTools/dev.supermux.lidsleep.held ]; then /usr/bin/pmset -a disablesleep 0; fi; " +
                "/bin/rm -f /Library/PrivilegedHelperTools/dev.supermux.lidsleep.held /Library/PrivilegedHelperTools/dev.supermux.lidsleep.app-missing " +
                "/Library/LaunchDaemons/dev.supermux.lidsleep.plist /Library/PrivilegedHelperTools/dev.supermux.lidsleep.sh",
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
            override fun runResult(argv: List<String>, timeoutMs: Long) = runResult(argv)
        }
        assertTrue(LidSleepHelper.install("alex", linux) is LidSleepHelper.Outcome.Failed)
        assertTrue(LidSleepHelper.uninstall(linux) is LidSleepHelper.Outcome.Failed)
        assertTrue(ran.isEmpty())
    }

    @Test fun install_runs_one_osascript_with_the_files_inline() {
        val ran = mutableListOf<List<String>>()
        val mac = object : OsEnv by FakeOs(OsEnv.Os.MAC) {
            override fun runResult(argv: List<String>): OsEnv.RunResult { ran += argv; return OsEnv.RunResult(1, "", "User canceled. (-128)") }
            override fun runResult(argv: List<String>, timeoutMs: Long) = runResult(argv)
        }
        val r = LidSleepHelper.install("alex", mac, appBundle = "/Applications/supermux.app")
        assertEquals(LidSleepHelper.Outcome.Failed(LidSleepHelper.CANCELLED), r)
        assertEquals(1, ran.size)
        assertEquals("/usr/bin/osascript", ran[0][0])
        assertTrue(LidSleepHelper.base64(LidSleepHelper.daemonScript("alex", "/Applications/supermux.app")) in ran[0][2])
    }

    @Test fun a_cancelled_uninstall_says_it_is_still_installed() {
        val mac = object : OsEnv by FakeOs(OsEnv.Os.MAC) {
            override fun runResult(argv: List<String>) = OsEnv.RunResult(1, "", "User canceled. (-128)")
            override fun runResult(argv: List<String>, timeoutMs: Long) = runResult(argv)
        }
        assertEquals(LidSleepHelper.Outcome.Failed("Cancelled. The lid helper is still installed."), LidSleepHelper.uninstall(mac))
    }

    @Test fun install_state_says_for_whom() {
        val dir = Files.createTempDirectory("lid")
        val sc = dir.resolve("s.sh")
        val pl = dir.resolve("p.plist")
        assertEquals(LidSleepHelper.InstallState.NOT_INSTALLED, LidSleepHelper.installState("alex", sc, pl))
        Files.writeString(sc, LidSleepHelper.daemonScript("alex"))
        assertEquals(LidSleepHelper.InstallState.NOT_INSTALLED, LidSleepHelper.installState("alex", sc, pl))
        Files.writeString(pl, LidSleepHelper.plist())
        assertEquals(LidSleepHelper.InstallState.INSTALLED, LidSleepHelper.installState("alex", sc, pl))
        assertEquals(LidSleepHelper.InstallState.OTHER_USER, LidSleepHelper.installState("someone", sc, pl))
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
    @Test fun after_close_start_does_nothing() = runTest {
        val ops = RecordingOps()
        val lease = LidLease(ops, backgroundScope)
        lease.start()
        runCurrent()
        lease.close()
        lease.start()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("touch", "delete"), ops.events)
        assertFalse(lease.held)
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
