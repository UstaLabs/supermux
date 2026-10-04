package dev.supermux.desktop.host

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the REAL daemon script (macOS only: BSD `stat -f`) with its binaries swapped for stubs:
 * `pmset` logs its argv and keeps a fake `SleepDisabled` value, `launchctl` only logs. Paths that
 * would be root-owned (marker, lease, self files) point into a temp dir. Never touches the real
 * power settings.
 */
class LidDaemonBehaviourTest {
    private lateinit var dir: Path
    private val user: String = System.getProperty("user.name") ?: ""

    @BeforeTest fun mac_only() {
        assumeTrue(System.getProperty("os.name").orEmpty().contains("Mac"))
        dir = Files.createTempDirectory("lid-daemon").toRealPath()
    }

    private val log get() = dir.resolve("pmset.log")
    private val gFile get() = dir.resolve("pmset-g.txt")
    private val marker get() = dir.resolve("held")
    private val lease get() = dir.resolve("lidsleep.lease")

    private fun exe(name: String, body: String): Path = dir.resolve(name).also {
        Files.writeString(it, body)
        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** The script with stubbed binaries and sandboxed paths. [functionsOnly] drops the `main "$@"` line. */
    private fun sandboxed(functionsOnly: Boolean, owner: String = user): Path {
        val pmset = exe(
            "pmset",
            """
            |#!/bin/sh
            |echo "${'$'}*" >> '$log'
            |if [ "${'$'}1" = "-g" ]; then cat '$gFile' 2>/dev/null; exit 0; fi
            |if [ "${'$'}1" = "-a" ] && [ "${'$'}2" = "disablesleep" ]; then printf ' SleepDisabled\t\t%s\n' "${'$'}3" > '$gFile'; fi
            |""".trimMargin(),
        )
        val launchctl = exe("launchctl", "#!/bin/sh\necho \"launchctl \$*\" >> '$log'\n")
        var text = LidSleepHelper.daemonScript("ahmet", "/Applications/supermux.app")
        fun set(name: String, value: String) {
            text = text.replace(Regex("^$name=.*$", RegexOption.MULTILINE), Regex.escapeReplacement("$name=$value"))
        }
        set("LEASE", "'$lease'")
        set("OWNER", "'$owner'")
        set("APP", "''")
        set("MARKER", "'$marker'")
        set("APP_MISSING", "'${dir.resolve("app-missing")}'")
        set("SELF_SCRIPT", "'${dir.resolve("self.sh")}'")
        set("SELF_PLIST", "'${dir.resolve("self.plist")}'")
        set("PMSET", pmset.toString())
        set("LAUNCHCTL", launchctl.toString())
        if (functionsOnly) text = text.trimEnd().removeSuffix("main \"\$@\"")
        return dir.resolve(if (functionsOnly) "funcs.sh" else "daemon.sh").also { Files.writeString(it, text) }
    }

    private fun run(snippet: String, owner: String = user): Int {
        val f = sandboxed(functionsOnly = true, owner = owner)
        val p = ProcessBuilder("/bin/sh", "-c", ". '$f'; $snippet").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS), out)
        return p.exitValue()
    }

    private fun calls(): List<String> = if (Files.exists(log)) Files.readAllLines(log) else emptyList()
    private fun sets(): List<String> = calls().filter { it.startsWith("-a ") }

    private fun freshLease() {
        Files.writeString(lease, "")
        Files.setLastModifiedTime(lease, FileTime.fromMillis(System.currentTimeMillis()))
    }

    // ── exact parsing: unknown means reset ──

    @Test fun garbled_or_unknown_pmset_output_resets_when_the_marker_is_there() {
        for (out in listOf("", "SleepDisabled\n", " SleepDisabled\t\tyes\n", "garbage\nmore\n", " SleepDisabled\t\t1\n SleepDisabled\t\t0\n")) {
            Files.deleteIfExists(log)
            Files.writeString(gFile, out)
            Files.writeString(marker, "")
            run("release")
            assertEquals(listOf("-a disablesleep 0"), sets(), "output: ${out.replace("\n", "\\n")}")
            assertFalse(Files.exists(marker))
        }
    }

    @Test fun exactly_zero_skips_the_reset_but_clears_the_marker() {
        Files.writeString(gFile, " SleepDisabled\t\t0\n")
        Files.writeString(marker, "")
        run("release")
        assertEquals(emptyList(), sets())
        assertFalse(Files.exists(marker))
    }

    @Test fun without_the_marker_release_never_touches_pmset() {
        Files.writeString(gFile, " SleepDisabled\t\t1\n") // Amphetamine or the user's own pmset
        run("release")
        assertEquals(emptyList(), calls())
    }

    // ── hold ──

    @Test fun hold_writes_the_marker_and_sets_one() {
        Files.writeString(gFile, " SleepDisabled\t\t0\n")
        run("hold")
        assertEquals(listOf("-a disablesleep 1"), sets())
        assertTrue(Files.exists(marker))
    }

    @Test fun hold_leaves_someone_elses_one_alone() {
        Files.writeString(gFile, " Sleep On Power Button 1\n SleepDisabled\t\t1\n")
        run("hold")
        assertEquals(emptyList(), sets())
        assertFalse(Files.exists(marker), "not ours: never released later")
    }

    // ── the lease ──

    @Test fun the_lease_counts_only_fresh_owned_and_not_a_symlink() {
        assertEquals(1, run("lease_fresh"), "missing")
        freshLease()
        assertEquals(0, run("lease_fresh"))
        assertEquals(1, run("lease_fresh", owner = "nobody-else"), "owner")
        Files.setLastModifiedTime(lease, FileTime.fromMillis(System.currentTimeMillis() - 60_000))
        assertEquals(1, run("lease_fresh"), "stale")
        Files.delete(lease)
        val target = dir.resolve("target")
        Files.writeString(target, "")
        Files.createSymbolicLink(lease, target)
        assertEquals(1, run("lease_fresh"), "symlink")
    }

    // ── the whole daemon: start and the signal trap ──

    // Output to a file, as launchd gives it /dev/null: Process.destroy() closes a pipe, and the
    // shell's job notice on that closed pipe would SIGPIPE the trap.
    private fun startDaemon(): Process =
        ProcessBuilder("/bin/sh", sandboxed(functionsOnly = false).toString())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("daemon.out").toFile())
            .start()

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!cond()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    @Test fun no_marker_at_start_means_no_reset() {
        Files.writeString(gFile, " SleepDisabled\t\t1\n")
        val p = startDaemon()
        try {
            Thread.sleep(1_000)
            assertEquals(emptyList(), sets())
        } finally {
            p.destroy()
            p.waitFor(5, TimeUnit.SECONDS)
        }
        assertEquals(emptyList(), sets(), "the trap had nothing of ours to give back")
        assertTrue(Files.readString(gFile).contains("SleepDisabled\t\t1"))
    }

    @Test fun a_marker_left_by_a_crash_is_reset_at_start() {
        Files.writeString(gFile, " SleepDisabled\t\t1\n")
        Files.writeString(marker, "")
        val p = startDaemon()
        try {
            waitFor("the start reset") { sets().isNotEmpty() }
            assertEquals(listOf("-a disablesleep 0"), sets())
            waitFor("the marker removal") { !Files.exists(marker) }
        } finally {
            p.destroy()
            p.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test fun term_gives_back_what_the_daemon_held_promptly() {
        Files.writeString(gFile, " SleepDisabled\t\t0\n")
        freshLease()
        val p = startDaemon()
        waitFor("the hold") { Files.exists(marker) && sets().contains("-a disablesleep 1") }
        val t0 = System.currentTimeMillis()
        p.destroy() // SIGTERM, as launchd's bootout
        assertTrue(p.waitFor(5, TimeUnit.SECONDS), "the 10 s sleep must not delay the exit")
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
        assertEquals(0, p.exitValue())
        assertEquals("-a disablesleep 0", sets().last())
        assertFalse(Files.exists(marker))
    }
}
