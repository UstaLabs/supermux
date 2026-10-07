package dev.supermux.desktop.host

import java.util.concurrent.TimeUnit

/**
 * Linux fallback (spec "Keep the computer awake", 17b): when the local broker reports
 * `keepAwake.reasonCode == "denied"`, polkit refused the broker's inhibitor (it runs under
 * `user@.service`, outside the desktop session). The app runs INSIDE the user's session, so it may
 * hold the same sleep inhibitor itself while it is open.
 *
 * The held command never outlives the app: it waits on the app's pid (the broker's
 * `LINUX_WAIT_SH` idea), so even a SIGKILL of the app releases the lock within a few seconds.
 * [release] (wired to every quit path) kills it at once. Nothing here throws.
 */
class AppSleepInhibitor(
    private val which: (String) -> String?,
    private val appPid: Long = ProcessHandle.current().pid(),
    private val spawn: (List<String>) -> Process = { argv ->
        ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    },
    /** How long a candidate must stay alive to count as holding (a refusal exits at once). */
    private val settleMs: Long = 1_500,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var process: Process? = null
    /** The candidate being settled right now, so a quit can kill it without waiting. */
    @Volatile private var pending: Process? = null
    @Volatile private var released = false
    private val holdLock = Any()

    val held: Boolean get() = process?.isAlive == true

    /** Hold the inhibitor (blocking up to [settleMs] per candidate). True when one holds. */
    fun hold(): Boolean = synchronized(holdLock) {
        if (released) return false
        if (process?.isAlive == true) return true
        process = null
        for ((name, argv) in candidates(which, appPid)) {
            if (released) return false
            val p = try {
                spawn(argv)
            } catch (e: Exception) {
                log("app keep-awake: $name didn't start: ${e.message ?: e}")
                continue
            }
            pending = p
            val exited = try {
                p.waitFor(settleMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                true
            } finally {
                pending = null
            }
            if (released) {
                kill(p)
                return false
            }
            if (!exited && p.isAlive) {
                log("app keep-awake: holding with $name (pid ${p.pid()})")
                process = p
                // A quit that raced us between the check above and here: give it back.
                if (released) {
                    process = null
                    kill(p)
                    return false
                }
                return true
            }
            log("app keep-awake: $name refused (exit ${runCatching { p.exitValue() }.getOrNull() ?: "?"})")
        }
        false
    }

    /**
     * Release the inhibitor. Idempotent. [final]: the app is quitting — never hold again, and kill
     * a candidate that is still settling right away instead of waiting for [hold] to finish.
     */
    fun release(final: Boolean = false) {
        if (final) {
            released = true
            pending?.let(::kill)
        }
        val p = process ?: return
        process = null
        kill(p)
        log("app keep-awake: released")
    }

    private fun kill(p: Process) {
        runCatching {
            p.descendants().forEach { it.destroy() }
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    companion object {
        const val WHO = "supermux"
        const val WHY = "Hosting agents"

        /** Ends when the app ($1) or our parent (the inhibitor) is gone. */
        val WAIT_SH = listOf(
            "app=\"\$1\"",
            "parent=\$PPID",
            "trap 'exit 0' TERM INT HUP",
            "while kill -0 \"\$app\" 2>/dev/null && kill -0 \"\$parent\" 2>/dev/null; do sleep 2; done",
        ).joinToString("\n")

        /** Runs "$@" (an inhibitor that holds until killed) and kills it when the app ($1) is gone. */
        val BOUND_SH = listOf(
            "app=\"\$1\"",
            "shift",
            "child=\"\"",
            "stop() { trap - TERM INT HUP; if [ -n \"\$child\" ]; then kill \"\$child\" 2>/dev/null; wait \"\$child\" 2>/dev/null; fi; exit 0; }",
            "trap stop TERM INT HUP",
            "\"\$@\" &",
            "child=\$!",
            "while kill -0 \"\$app\" 2>/dev/null && kill -0 \"\$child\" 2>/dev/null; do sleep 2; done",
            "if kill -0 \"\$child\" 2>/dev/null; then stop; fi",
            "wait \"\$child\"",
        ).joinToString("\n")

        /** The fallback chain, as the broker's: systemd-inhibit, gnome-session-inhibit, kde-inhibit. */
        fun candidates(which: (String) -> String?, pid: Long): List<Pair<String, List<String>>> = buildList {
            which("systemd-inhibit")?.let {
                add(
                    "systemd-inhibit" to listOf(
                        it, "--what=sleep", "--who=$WHO", "--why=$WHY", "--mode=block",
                        "/bin/sh", "-c", WAIT_SH, "supermux-app-keep-awake", pid.toString(),
                    ),
                )
            }
            which("gnome-session-inhibit")?.let {
                add(
                    "gnome-session-inhibit" to listOf(
                        "/bin/sh", "-c", BOUND_SH, "supermux-app-keep-awake", pid.toString(),
                        it, "--inhibit", "suspend", "--inhibit-only",
                    ),
                )
            }
            which("kde-inhibit")?.let {
                add("kde-inhibit" to listOf(it, "--power", "/bin/sh", "-c", WAIT_SH, "supermux-app-keep-awake", pid.toString()))
            }
        }
    }
}
