package dev.supermux.desktop.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

/**
 * "Even with the lid closed" on a MacBook (spec "Lid helper mechanism"). No code signing needed:
 *
 *  - A root LaunchDaemon [LABEL], installed ONCE with an admin prompt (`osascript … with
 *    administrator privileges`), runs [daemonScript]: every [LOOP_S] s it holds
 *    `pmset -a disablesleep 1` while the user's lease file was touched less than [LEASE_MAX_AGE_S] s
 *    ago, and `pmset -a disablesleep 0` otherwise. It resets to 0 when it starts.
 *  - The app touches the lease every [TOUCH_MS] ms while the box is ticked ([LidLease]) and deletes
 *    it on untick and on ANY quit, so lid sleep comes back within 45 s, also after a crash.
 *  - The daemon only ever runs fixed-argv `pmset`; it never executes anything from the lease.
 *
 * Everything that builds text (the script, the plist, the shell commands, the AppleScript) is pure
 * and unit-tested; only [install] / [uninstall] touch the system.
 */
object LidSleepHelper {
    const val LABEL = "dev.supermux.lidsleep"
    const val SCRIPT_PATH = "/Library/PrivilegedHelperTools/dev.supermux.lidsleep.sh"
    const val PLIST_PATH = "/Library/LaunchDaemons/dev.supermux.lidsleep.plist"
    const val LEASE_MAX_AGE_S = 45
    const val LOOP_S = 10
    const val TOUCH_MS = 15_000L

    const val INSTALL_PROMPT = "supermux wants to install a helper that keeps your Mac awake with the lid closed."
    const val UNINSTALL_PROMPT = "supermux wants to remove its lid helper."
    const val CANCELLED = "Cancelled. The lid helper wasn't installed."

    private val USER_RE = Regex("[A-Za-z0-9._-]+")
    /** A temp path we hand to the root shell: no quotes, spaces or shell metacharacters. */
    private val SAFE_PATH_RE = Regex("/[A-Za-z0-9._/+-]+")

    /** The macOS short user name baked into the daemon. */
    fun validUser(user: String?): Boolean =
        user != null && user.length <= 64 && USER_RE.matches(user) && user != "." && user != ".." && !user.startsWith("-")

    fun safePath(path: String): Boolean = SAFE_PATH_RE.matches(path) && ".." !in path.split('/')

    /** `/Users/<user>/.mux/state/lidsleep.lease`. */
    fun leasePath(user: String): String {
        require(validUser(user)) { "invalid user name" }
        return "/Users/$user/.mux/state/lidsleep.lease"
    }

    /**
     * The root daemon's `/bin/sh` script. [user] is validated to `[A-Za-z0-9._-]+`, so it can sit in
     * single quotes. The lease counts only when it is a regular file (not a symlink: BSD `stat`
     * reads the link itself and `-L` is checked first), owned by [user], and modified less than
     * [LEASE_MAX_AGE_S] s ago (and not in the future). It reads the current state with
     * `pmset -g | grep SleepDisabled` and only calls `pmset` when it has to change.
     */
    fun daemonScript(user: String): String {
        val lease = leasePath(user)
        return """
            |#!/bin/sh
            |# supermux lid helper ($LABEL). Installed by the supermux app; runs as root.
            |# It only ever runs: pmset -a disablesleep 1 | pmset -a disablesleep 0
            |PATH=/usr/bin:/bin:/usr/sbin:/sbin
            |export PATH
            |LEASE='$lease'
            |OWNER='$user'
            |MAX_AGE=$LEASE_MAX_AGE_S
            |
            |sleep_disabled() {
            |  v=${'$'}(/usr/bin/pmset -g | /usr/bin/grep SleepDisabled | /usr/bin/awk '{print ${'$'}2}')
            |  if [ "${'$'}v" = "1" ]; then echo 1; else echo 0; fi
            |}
            |
            |lease_fresh() {
            |  [ -L "${'$'}LEASE" ] && return 1
            |  [ -f "${'$'}LEASE" ] || return 1
            |  owner=${'$'}(/usr/bin/stat -f %Su "${'$'}LEASE" 2>/dev/null)
            |  [ "${'$'}owner" = "${'$'}OWNER" ] || return 1
            |  mtime=${'$'}(/usr/bin/stat -f %m "${'$'}LEASE" 2>/dev/null)
            |  case "${'$'}mtime" in ''|*[!0-9]*) return 1 ;; esac
            |  now=${'$'}(/bin/date +%s)
            |  age=${'$'}((now - mtime))
            |  [ "${'$'}age" -ge 0 ] && [ "${'$'}age" -lt "${'$'}MAX_AGE" ]
            |}
            |
            |/usr/bin/pmset -a disablesleep 0
            |
            |while :; do
            |  if lease_fresh; then
            |    [ "${'$'}(sleep_disabled)" = "1" ] || /usr/bin/pmset -a disablesleep 1
            |  else
            |    [ "${'$'}(sleep_disabled)" = "0" ] || /usr/bin/pmset -a disablesleep 0
            |  fi
            |  /bin/sleep $LOOP_S
            |done
            |""".trimMargin()
    }

    /** The LaunchDaemon: started at boot, restarted if it ever exits. */
    fun plist(): String = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |  <key>Label</key>
        |  <string>$LABEL</string>
        |  <key>ProgramArguments</key>
        |  <array>
        |    <string>/bin/sh</string>
        |    <string>$SCRIPT_PATH</string>
        |  </array>
        |  <key>RunAtLoad</key>
        |  <true/>
        |  <key>KeepAlive</key>
        |  <true/>
        |</dict>
        |</plist>
        |""".trimMargin()

    /**
     * The root shell command for [install]: copy the script (root:wheel 0755) and the plist
     * (root:wheel 0644) into place, then load the daemon (or restart it with the new script when it
     * is already loaded: bootstrapping a loaded label fails). Built only from fixed strings and the
     * two temp paths, which must pass [safePath].
     */
    fun installCommand(tmpScript: String, tmpPlist: String): String {
        require(safePath(tmpScript)) { "unsafe script path" }
        require(safePath(tmpPlist)) { "unsafe plist path" }
        return listOf(
            "/bin/mkdir -p /Library/PrivilegedHelperTools",
            "/usr/bin/install -o root -g wheel -m 0755 '$tmpScript' $SCRIPT_PATH",
            "/usr/bin/install -o root -g wheel -m 0644 '$tmpPlist' $PLIST_PATH",
            "if /bin/launchctl print system/$LABEL >/dev/null 2>&1; " +
                "then /bin/launchctl kickstart -k system/$LABEL; " +
                "else /bin/launchctl bootstrap system $PLIST_PATH; fi",
        ).joinToString(" && ")
    }

    /** Boot the daemon out, remove both files, and give sleep back. */
    fun uninstallCommand(): String =
        "/bin/launchctl bootout system/$LABEL 2>/dev/null; " +
            "/bin/rm -f $PLIST_PATH $SCRIPT_PATH; " +
            "/usr/bin/pmset -a disablesleep 0"

    /** An AppleScript string literal. */
    fun appleScriptString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** `osascript -e 'do shell script "<cmd>" with prompt "…" with administrator privileges'`: ONE password prompt. */
    fun adminArgv(cmd: String, prompt: String): List<String> = listOf(
        "/usr/bin/osascript",
        "-e",
        "do shell script ${appleScriptString(cmd)} with prompt ${appleScriptString(prompt)} with administrator privileges",
    )

    /** True when osascript's failure was the user pressing Cancel (error -128). */
    fun wasCancelled(err: String): Boolean = "-128" in err || err.contains("User canceled", ignoreCase = true)

    /** Installed for [user]: both files exist and the script watches [user]'s lease. */
    fun isInstalled(user: String, script: Path = Path.of(SCRIPT_PATH), plist: Path = Path.of(PLIST_PATH)): Boolean {
        if (!validUser(user)) return false
        return runCatching {
            Files.isRegularFile(plist) && Files.isRegularFile(script) &&
                Files.readString(script).contains("LEASE='${leasePath(user)}'")
        }.getOrDefault(false)
    }

    sealed interface Outcome {
        data object Ok : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Write the script + plist to temp files and install them with one admin prompt. Blocking. */
    fun install(user: String, os: OsEnv, log: (String) -> Unit = {}): Outcome {
        if (os.os != OsEnv.Os.MAC) return Outcome.Failed("The lid helper is only for Macs.")
        if (!validUser(user)) return Outcome.Failed("Unsupported user name.")
        var script: Path? = null
        var plistFile: Path? = null
        return try {
            script = Files.createTempFile("supermux-lidsleep", ".sh")
            plistFile = Files.createTempFile("supermux-lidsleep", ".plist")
            Files.writeString(script, daemonScript(user))
            Files.writeString(plistFile, plist())
            val s = script.toRealPath().toString()
            val p = plistFile.toRealPath().toString()
            if (!safePath(s) || !safePath(p)) return Outcome.Failed("The temp folder's path isn't supported.")
            val r = os.runResult(adminArgv(installCommand(s, p), INSTALL_PROMPT))
            log("lid helper install: exit ${r.exit}${r.err.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}")
            when {
                r.exit == 0 -> Outcome.Ok
                wasCancelled(r.err) -> Outcome.Failed(CANCELLED)
                else -> Outcome.Failed("Couldn't install the lid helper: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}")
            }
        } catch (e: Exception) {
            log("lid helper install failed: ${e.message ?: e}")
            Outcome.Failed("Couldn't install the lid helper: ${e.message ?: e}")
        } finally {
            script?.let { runCatching { Files.deleteIfExists(it) } }
            plistFile?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** Boot out and remove the daemon with one admin prompt. Blocking. */
    fun uninstall(os: OsEnv, log: (String) -> Unit = {}): Outcome {
        if (os.os != OsEnv.Os.MAC) return Outcome.Failed("The lid helper is only for Macs.")
        val r = os.runResult(adminArgv(uninstallCommand(), UNINSTALL_PROMPT))
        log("lid helper uninstall: exit ${r.exit}${r.err.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}")
        return when {
            r.exit == 0 -> Outcome.Ok
            wasCancelled(r.err) -> Outcome.Failed("Cancelled.")
            else -> Outcome.Failed("Couldn't remove the lid helper: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}")
        }
    }
}

/** The lease file seam: [LidLease] touches and deletes through it. */
interface LeaseFileOps {
    /** Create (mode 0600) or refresh the lease's modification time. */
    fun touch()
    fun delete()
}

/**
 * The real lease file. [now] stamps the modification time (injectable for tests). A symlink at the
 * path is replaced by a regular file: the daemon would ignore it anyway.
 */
class FileLeaseOps(private val path: Path, private val now: () -> Long = System::currentTimeMillis) : LeaseFileOps {
    override fun touch() {
        path.parent?.let { Files.createDirectories(it) }
        if (Files.isSymbolicLink(path)) Files.delete(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val attrs = runCatching { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }.getOrNull()
            try {
                if (attrs != null) Files.createFile(path, attrs) else Files.createFile(path)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
            } catch (_: UnsupportedOperationException) {
                Files.createFile(path)
            }
        }
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
        Files.setLastModifiedTime(path, FileTime.fromMillis(now()))
    }

    override fun delete() {
        Files.deleteIfExists(path)
    }
}

/**
 * Touches the lease every [intervalMs] while held. [stop] deletes it; touch and delete are
 * serialised, and a touch never runs after a stop, so a quit cannot leave a fresh lease behind.
 */
class LidLease(
    private val ops: LeaseFileOps,
    private val scope: CoroutineScope,
    private val intervalMs: Long = LidSleepHelper.TOUCH_MS,
    private val log: (String) -> Unit = {},
) {
    private val fileLock = Any()
    @Volatile private var holding = false
    private var job: Job? = null

    val held: Boolean get() = holding

    @Synchronized
    fun start() {
        if (holding && job?.isActive == true) return
        holding = true
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                synchronized(fileLock) {
                    if (holding) runCatching { ops.touch() }.onFailure { log("lid lease: couldn't touch: ${it.message ?: it}") }
                }
                delay(intervalMs)
            }
        }
    }

    /** Stop touching and delete the lease. Idempotent, never throws. */
    fun stop() {
        synchronized(this) {
            job?.cancel()
            job = null
        }
        synchronized(fileLock) {
            holding = false
            runCatching { ops.delete() }.onFailure { log("lid lease: couldn't delete: ${it.message ?: it}") }
        }
    }
}
