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
 *    ago, and gives it back otherwise.
 *  - It only ever gives back what IT set: a root-owned marker ([MARKER_PATH]) exists exactly while
 *    the daemon holds `disablesleep 1`. Someone else's `disablesleep 1` (Amphetamine, the user's own
 *    `pmset`) is never touched. On start, and on TERM/INT/HUP (bootout, shutdown), it resets only
 *    when the marker is there.
 *  - The app touches the lease every [TOUCH_MS] ms while the box is ticked ([LidLease]) and deletes
 *    it on untick and on ANY quit, so lid sleep comes back within 45 s, also after a crash.
 *  - When the app bundle it was installed from has been gone for a day, it resets, deletes itself
 *    and boots itself out.
 *  - The daemon only ever runs fixed-argv `pmset`; it never executes anything from the lease.
 *
 * Install hands root NO user-owned file: the script and the plist travel base64-encoded inside the
 * root command ([installCommand]). Everything that builds text is pure and unit-tested; only
 * [install] / [uninstall] touch the system.
 */
object LidSleepHelper {
    const val LABEL = "dev.supermux.lidsleep"
    const val HELPER_DIR = "/Library/PrivilegedHelperTools"
    const val SCRIPT_PATH = "$HELPER_DIR/dev.supermux.lidsleep.sh"
    const val PLIST_PATH = "/Library/LaunchDaemons/dev.supermux.lidsleep.plist"

    /**
     * Exists while the daemon holds `disablesleep 1`. In the root-owned helper dir, NOT /var/run:
     * `pmset -a disablesleep` survives a reboot, and /var/run does not, so a marker there would be
     * lost exactly when a crash mid-hold needs it.
     */
    const val MARKER_PATH = "$HELPER_DIR/dev.supermux.lidsleep.held"

    /** When the app bundle was first seen missing (epoch seconds), root-owned. */
    const val APP_MISSING_PATH = "$HELPER_DIR/dev.supermux.lidsleep.app-missing"
    private const val SCRIPT_TMP = "$HELPER_DIR/.dev.supermux.lidsleep.sh.new"
    private const val PLIST_TMP = "$HELPER_DIR/.dev.supermux.lidsleep.plist.new"

    const val LEASE_MAX_AGE_S = 45
    const val LOOP_S = 10
    const val TOUCH_MS = 15_000L
    /** The app-bundle check runs every 60 loops (10 min). */
    const val APP_CHECK_LOOPS = 60
    const val APP_GONE_S = 86_400

    const val INSTALL_PROMPT = "supermux wants to install a helper that keeps your Mac awake with the lid closed."
    const val UNINSTALL_PROMPT = "supermux wants to remove its lid helper."
    const val CANCELLED = "Cancelled. The lid helper wasn't installed."
    const val UNINSTALL_CANCELLED = "Cancelled. The lid helper is still installed."

    private val USER_RE = Regex("[A-Za-z0-9._-]+")
    /** A path we put in a root shell or script: no quotes, spaces or shell metacharacters. */
    private val SAFE_PATH_RE = Regex("/[A-Za-z0-9._/+-]+")
    private val BASE64_RE = Regex("[A-Za-z0-9+/=]+")

    /** The macOS short user name baked into the daemon. */
    fun validUser(user: String?): Boolean =
        user != null && user.length <= 64 && USER_RE.matches(user) && user != "." && user != ".." && !user.startsWith("-")

    fun safePath(path: String): Boolean = SAFE_PATH_RE.matches(path) && ".." !in path.split('/')

    /** `/Users/<user>/.mux/state/lidsleep.lease`. */
    fun leasePath(user: String): String {
        require(validUser(user)) { "invalid user name" }
        return "/Users/$user/.mux/state/lidsleep.lease"
    }

    /** The daemon watches `/Users/<user>`: an account whose home is elsewhere can't use it. */
    fun homeSupported(user: String, home: String?): Boolean =
        validUser(user) && home != null && home.trimEnd('/') == "/Users/$user"

    /**
     * The `.app` bundle the running app lives in, from candidate paths inside it (the launcher
     * command, the Compose resources dir). Null in a dev run or when the path isn't [safePath].
     */
    fun appBundlePath(candidates: List<String?>): String? {
        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            val parts = c.split('/')
            val i = parts.indexOfFirst { it.endsWith(".app") }
            if (i <= 0) continue
            val bundle = parts.take(i + 1).joinToString("/")
            if (bundle.startsWith("/") && safePath(bundle)) return bundle
        }
        return null
    }

    /** [appBundlePath] for this process. */
    fun currentAppBundle(): String? = appBundlePath(
        listOf(
            runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull(),
            System.getProperty("compose.application.resources.dir"),
            System.getProperty("java.home"),
        ),
    )

    /**
     * The root daemon's `/bin/sh` script. [user] is validated to `[A-Za-z0-9._-]+` and [appBundle]
     * to [safePath], so both can sit in single quotes. The binaries are plain variables at the top
     * (a behaviour test swaps them for stubs); launchd runs it with these defaults. The last line
     * runs `main`: everything above it is functions and settings.
     */
    fun daemonScript(user: String, appBundle: String? = null): String {
        val lease = leasePath(user)
        require(appBundle == null || safePath(appBundle)) { "unsafe app path" }
        val d = '$'
        return """
            |#!/bin/sh
            |# supermux lid helper ($LABEL). Installed by the supermux app; runs as root.
            |# It only ever runs: pmset -a disablesleep 1 | pmset -a disablesleep 0, and gives back only
            |# what it set itself (the marker). It removes itself once the app is gone for a day.
            |PATH=/usr/bin:/bin:/usr/sbin:/sbin
            |export PATH
            |LEASE='$lease'
            |OWNER='$user'
            |APP='${appBundle ?: ""}'
            |MAX_AGE=$LEASE_MAX_AGE_S
            |MARKER='$MARKER_PATH'
            |APP_MISSING='$APP_MISSING_PATH'
            |SELF_SCRIPT='$SCRIPT_PATH'
            |SELF_PLIST='$PLIST_PATH'
            |APP_CHECK_LOOPS=$APP_CHECK_LOOPS
            |APP_GONE_S=$APP_GONE_S
            |PMSET=/usr/bin/pmset
            |STAT=/usr/bin/stat
            |DATE=/bin/date
            |LAUNCHCTL=/bin/launchctl
            |SLEEP=/bin/sleep
            |
            |# Exactly the SleepDisabled value ("0" / "1"); anything else is unknown.
            |sleep_disabled() {
            |  "${d}PMSET" -g 2>/dev/null | /usr/bin/awk '${d}1=="SleepDisabled"{print ${d}2; exit}'
            |}
            |
            |lease_fresh() {
            |  [ -L "${d}LEASE" ] && return 1
            |  [ -f "${d}LEASE" ] || return 1
            |  owner=${d}("${d}STAT" -f %Su "${d}LEASE" 2>/dev/null)
            |  [ "${d}owner" = "${d}OWNER" ] || return 1
            |  mtime=${d}("${d}STAT" -f %m "${d}LEASE" 2>/dev/null)
            |  case "${d}mtime" in ''|*[!0-9]*) return 1 ;; esac
            |  now=${d}("${d}DATE" +%s)
            |  age=${d}((now - mtime))
            |  [ "${d}age" -ge 0 ] && [ "${d}age" -lt "${d}MAX_AGE" ]
            |}
            |
            |# Hold: set 1 unless it is exactly 1 already. The marker is written first, so a crash in
            |# between still leads to a reset.
            |hold() {
            |  [ "${d}(sleep_disabled)" = "1" ] && return 0
            |  : > "${d}MARKER"
            |  "${d}PMSET" -a disablesleep 1
            |}
            |
            |# Release only what we set: nothing without the marker. Skip pmset only when it is exactly 0.
            |release() {
            |  [ -f "${d}MARKER" ] || return 0
            |  if [ "${d}(sleep_disabled)" = "0" ] || "${d}PMSET" -a disablesleep 0; then
            |    /bin/rm -f "${d}MARKER"
            |  fi
            |}
            |
            |# True once the app bundle has been missing for more than APP_GONE_S seconds.
            |app_gone_too_long() {
            |  [ -n "${d}APP" ] || return 1
            |  if [ -e "${d}APP" ]; then
            |    /bin/rm -f "${d}APP_MISSING"
            |    return 1
            |  fi
            |  now=${d}("${d}DATE" +%s)
            |  since=${d}(/bin/cat "${d}APP_MISSING" 2>/dev/null)
            |  case "${d}since" in ''|*[!0-9]*) echo "${d}now" > "${d}APP_MISSING"; return 1 ;; esac
            |  [ ${d}((now - since)) -gt "${d}APP_GONE_S" ]
            |}
            |
            |remove_self() {
            |  release
            |  /bin/rm -f "${d}APP_MISSING" "${d}SELF_PLIST" "${d}SELF_SCRIPT"
            |  "${d}LAUNCHCTL" bootout system/$LABEL
            |  exit 0
            |}
            |
            |on_signal() {
            |  trap - TERM INT HUP
            |  [ -n "${d}sleeper" ] && kill "${d}sleeper" 2>/dev/null
            |  release
            |  exit 0
            |}
            |
            |main() {
            |  sleeper=""
            |  trap on_signal TERM INT HUP
            |  # A marker left behind means we stopped mid-hold: reset. Otherwise leave pmset alone.
            |  release
            |  n=0
            |  while :; do
            |    if [ "${d}n" -le 0 ]; then
            |      n=${d}APP_CHECK_LOOPS
            |      if app_gone_too_long; then remove_self; fi
            |    fi
            |    n=${d}((n - 1))
            |    if lease_fresh; then hold; else release; fi
            |    "${d}SLEEP" $LOOP_S &
            |    sleeper=${d}!
            |    wait "${d}sleeper"
            |    sleeper=""
            |  done
            |}
            |
            |main "${d}@"
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

    fun base64(text: String): String = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /**
     * The root shell command for [install]. The script and plist arrive base64-encoded IN the
     * command (only `[A-Za-z0-9+/=]`, safe in single quotes and in the AppleScript string), are
     * decoded into root-owned temp files in [HELPER_DIR], installed root:wheel 0755 / 0644, and the
     * daemon is loaded (or restarted with the new script when it is already loaded: bootstrapping a
     * loaded label fails). No user-owned file is ever read by root. The temp files are removed
     * whatever happens, and the command exits with the install's status.
     */
    fun installCommand(script: String, plist: String): String {
        val s64 = base64(script)
        val p64 = base64(plist)
        check(BASE64_RE.matches(s64) && BASE64_RE.matches(p64))
        return "{ /bin/mkdir -p $HELPER_DIR && " +
            "/bin/echo '$s64' | /usr/bin/base64 -D > $SCRIPT_TMP && " +
            "/usr/bin/install -o root -g wheel -m 0755 $SCRIPT_TMP $SCRIPT_PATH && " +
            "/bin/echo '$p64' | /usr/bin/base64 -D > $PLIST_TMP && " +
            "/usr/bin/install -o root -g wheel -m 0644 $PLIST_TMP $PLIST_PATH && " +
            "if /bin/launchctl print system/$LABEL >/dev/null 2>&1; " +
            "then /bin/launchctl kickstart -k system/$LABEL; " +
            "else /bin/launchctl bootstrap system $PLIST_PATH; fi; }; " +
            "s=\$?; /bin/rm -f $SCRIPT_TMP $PLIST_TMP; exit \$s"
    }

    /**
     * Boot the daemon out (its TERM trap gives back what it held), give sleep back only when the
     * marker says the daemon held it, and remove every file.
     */
    fun uninstallCommand(): String =
        "/bin/launchctl bootout system/$LABEL 2>/dev/null; " +
            "if [ -f $MARKER_PATH ]; then /usr/bin/pmset -a disablesleep 0; fi; " +
            "/bin/rm -f $MARKER_PATH $APP_MISSING_PATH $PLIST_PATH $SCRIPT_PATH"

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

    enum class InstallState { NOT_INSTALLED, INSTALLED, OTHER_USER }

    /** Whether the helper is installed, and for whom (the lease its script watches). */
    fun installState(user: String, script: Path = Path.of(SCRIPT_PATH), plist: Path = Path.of(PLIST_PATH)): InstallState =
        runCatching {
            if (!Files.isRegularFile(plist) || !Files.isRegularFile(script)) return InstallState.NOT_INSTALLED
            if (validUser(user) && Files.readString(script).contains("LEASE='${leasePath(user)}'")) InstallState.INSTALLED
            else InstallState.OTHER_USER
        }.getOrDefault(InstallState.NOT_INSTALLED)

    sealed interface Outcome {
        data object Ok : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Install with one admin prompt (the files travel inside the command). Blocking. */
    fun install(user: String, os: OsEnv, appBundle: String? = currentAppBundle(), log: (String) -> Unit = {}): Outcome {
        if (os.os != OsEnv.Os.MAC) return Outcome.Failed("The lid helper is only for Macs.")
        if (!validUser(user)) return Outcome.Failed("Unsupported user name.")
        return try {
            val cmd = installCommand(daemonScript(user, appBundle?.takeIf(::safePath)), plist())
            val r = os.runResult(adminArgv(cmd, INSTALL_PROMPT))
            log("lid helper install: exit ${r.exit}${r.err.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}")
            when {
                r.exit == 0 -> Outcome.Ok
                wasCancelled(r.err) -> Outcome.Failed(CANCELLED)
                else -> Outcome.Failed("Couldn't install the lid helper: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}")
            }
        } catch (e: Exception) {
            log("lid helper install failed: ${e.message ?: e}")
            Outcome.Failed("Couldn't install the lid helper: ${e.message ?: e}")
        }
    }

    /** Boot out and remove the daemon with one admin prompt. Blocking. */
    fun uninstall(os: OsEnv, log: (String) -> Unit = {}): Outcome {
        if (os.os != OsEnv.Os.MAC) return Outcome.Failed("The lid helper is only for Macs.")
        val r = os.runResult(adminArgv(uninstallCommand(), UNINSTALL_PROMPT))
        log("lid helper uninstall: exit ${r.exit}${r.err.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}")
        return when {
            r.exit == 0 -> Outcome.Ok
            wasCancelled(r.err) -> Outcome.Failed(UNINSTALL_CANCELLED)
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
    @Volatile private var closed = false
    private var job: Job? = null

    val held: Boolean get() = holding

    /** Touch now and every [intervalMs]. A no-op once [close]d. */
    @Synchronized
    fun start() {
        if (closed) return
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

    /** The app is quitting: a permanent latch (no [start] ever again), then [stop]. */
    fun close() {
        closed = true
        stop()
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
