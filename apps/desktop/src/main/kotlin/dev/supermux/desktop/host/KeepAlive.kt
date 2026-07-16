package dev.supermux.desktop.host

import java.nio.file.Files
import java.nio.file.Path

/**
 * Login autostart for the desktop-as-host keep-alive box (Plan 3 Task 4 / spec §6, D6): keep this
 * computer available as a host after the window closes and across sign-in.
 *
 *  - **macOS** → a launchd LaunchAgent plist at `~/Library/LaunchAgents/dev.supermux.host.plist`,
 *    bootstrapped via `launchctl` into the user's GUI domain.
 *  - **Linux** → a systemd `--user` unit `supermux-host.service` enabled with `systemctl --user`,
 *    falling back to an XDG autostart `.desktop` file when systemd `--user` isn't usable.
 *  - **Windows / other** → no-op (client-only; native hosting is a preview, Task 6).
 *
 * Every `getuid` / `launchctl` / `systemctl` touch is behind the platform check in [install]/[remove]
 * (they no-op on the wrong OS). The plist / unit / autostart STRING generation is pure and unit-tested
 * ([KeepAliveTest]); the real OS install is gated by the injected [KeepAliveEnv] so tests drive the
 * Linux + macOS branches on any host without real side effects. Unchecking the wizard box installs
 * neither (the wizard simply doesn't call [install]).
 */
object KeepAlive {

    const val LAUNCHD_LABEL = "dev.supermux.host"
    const val SYSTEMD_UNIT = "supermux-host.service"
    const val SYSTEMD_NAME = "supermux-host" // the enable/disable target (unit minus .service)
    const val XDG_AUTOSTART_FILE = "supermux-host.desktop"

    enum class Os { MAC, LINUX, OTHER }

    /**
     * What the login agent launches to keep hosting: the host-launcher [exec] argv (the packaged app
     * with its keep-hosting flag, or the bundled broker — resolved by the caller), plus the [hostId]
     * for traceability and log paths. Framework-free so the generators below are pure.
     */
    data class Spec(
        val exec: List<String>,
        val hostId: String? = null,
        val hostName: String? = null,
        val label: String = LAUNCHD_LABEL,
        val outLog: String = "/tmp/supermux-host.out.log",
        val errLog: String = "/tmp/supermux-host.err.log",
    ) {
        init {
            require(exec.isNotEmpty()) { "KeepAlive.Spec.exec must not be empty" }
        }
    }

    sealed interface Result {
        data class Installed(val path: Path, val enabled: Boolean) : Result
        data class Removed(val path: Path?) : Result
        data object Unsupported : Result
        data class Failed(val message: String) : Result
    }

    // ── Pure string generators (unit-tested) ────────────────────────────────────────

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** macOS LaunchAgent plist. RunAtLoad + KeepAlive(SuccessfulExit=false) so it relaunches. */
    fun launchdPlist(spec: Spec): String {
        val args = spec.exec.joinToString("\n") { "    <string>${xmlEscape(it)}</string>" }
        val hostIdEnv = spec.hostId?.let {
            "\n    <key>SUPERMUX_HOST_ID</key>\n    <string>${xmlEscape(it)}</string>"
        } ?: ""
        val hostNameEnv = spec.hostName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "\n    <key>MUX_HOST_NAME</key>\n    <string>${xmlEscape(it)}</string>"
        } ?: ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${xmlEscape(spec.label)}</string>
  <key>ProgramArguments</key>
  <array>
$args
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
  </dict>
  <key>ProcessType</key>
  <string>Background</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>SUPERMUX_KEEP_ALIVE</key>
    <string>1</string>$hostIdEnv$hostNameEnv
  </dict>
  <key>StandardOutPath</key>
  <string>${xmlEscape(spec.outLog)}</string>
  <key>StandardErrorPath</key>
  <string>${xmlEscape(spec.errLog)}</string>
</dict>
</plist>
"""
    }

    /** Linux systemd `--user` unit. Restart=on-failure so the host survives a crash. */
    fun systemdUnit(spec: Spec): String {
        val execStart = spec.exec.joinToString(" ")
        val hostIdEnv = spec.hostId?.let { "\nEnvironment=SUPERMUX_HOST_ID=$it" } ?: ""
        val hostNameEnv = spec.hostName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "\nEnvironment=\"MUX_HOST_NAME=${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } ?: ""
        return """[Unit]
Description=supermux host (keep this computer available as a host)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$execStart
Restart=on-failure
RestartSec=5
Environment=SUPERMUX_KEEP_ALIVE=1$hostIdEnv$hostNameEnv

[Install]
WantedBy=default.target
"""
    }

    /** XDG autostart `.desktop` fallback when systemd `--user` isn't available. */
    fun xdgAutostart(spec: Spec): String {
        val execLine = spec.exec.joinToString(" ")
        return """[Desktop Entry]
Type=Application
Name=supermux host
Comment=Keep this computer available as a supermux host
Exec=$execLine
X-GNOME-Autostart-enabled=true
Terminal=false
"""
    }

    // ── OS-gated install / remove (real work behind the injected env) ────────────────

    /** Install the login agent for the current OS. No-op (Unsupported) on Windows/other. */
    fun install(spec: Spec, env: KeepAliveEnv = SystemKeepAliveEnv): Result = when (env.os) {
        Os.MAC -> installLaunchd(spec, env)
        Os.LINUX -> installSystemd(spec, env)
        Os.OTHER -> Result.Unsupported
    }

    /** Remove the login agent for the current OS. No-op on Windows/other. */
    fun remove(env: KeepAliveEnv = SystemKeepAliveEnv): Result = when (env.os) {
        Os.MAC -> removeLaunchd(env)
        Os.LINUX -> removeSystemd(env)
        Os.OTHER -> Result.Unsupported
    }

    private fun installLaunchd(spec: Spec, env: KeepAliveEnv): Result {
        val plist = env.home.resolve("Library/LaunchAgents/${spec.label}.plist")
        return runCatching {
            Files.createDirectories(plist.parent)
            Files.writeString(plist, launchdPlist(spec))
            // launchctl is guarded: absent (or non-macOS shell) ⇒ the plist is in place for next login.
            val enabled = if (env.hasCommand("launchctl")) {
                val uid = env.uid
                val domain = if (uid != null) "gui/$uid" else null
                if (domain != null) {
                    env.run(listOf("launchctl", "bootstrap", domain, plist.toString()))
                    env.run(listOf("launchctl", "enable", "$domain/${spec.label}"))
                } else false
            } else false
            Result.Installed(plist, enabled)
        }.getOrElse { Result.Failed("launchd install failed: ${it.message}") }
    }

    private fun removeLaunchd(env: KeepAliveEnv): Result {
        val plist = env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist")
        return runCatching {
            if (env.hasCommand("launchctl") && env.uid != null) {
                env.run(listOf("launchctl", "bootout", "gui/${env.uid}/$LAUNCHD_LABEL"))
            }
            val existed = Files.deleteIfExists(plist)
            Result.Removed(if (existed) plist else null)
        }.getOrElse { Result.Failed("launchd remove failed: ${it.message}") }
    }

    private fun installSystemd(spec: Spec, env: KeepAliveEnv): Result {
        // systemd --user needs both systemctl and a runtime dir; otherwise fall back to XDG autostart.
        if (!env.hasCommand("systemctl") || env.xdgRuntimeDir.isNullOrBlank()) {
            return installXdgAutostart(spec, env)
        }
        val unit = env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT")
        return runCatching {
            Files.createDirectories(unit.parent)
            Files.writeString(unit, systemdUnit(spec))
            env.run(listOf("systemctl", "--user", "daemon-reload"))
            val enabled = env.run(listOf("systemctl", "--user", "enable", "--now", SYSTEMD_NAME))
            // Best-effort linger so the host survives logout (no sudo needed for one's own user).
            env.uid?.let { env.run(listOf("loginctl", "enable-linger", it.toString())) }
            Result.Installed(unit, enabled)
        }.getOrElse { Result.Failed("systemd install failed: ${it.message}") }
    }

    private fun installXdgAutostart(spec: Spec, env: KeepAliveEnv): Result {
        val file = env.home.resolve(".config/autostart/$XDG_AUTOSTART_FILE")
        return runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, xdgAutostart(spec))
            Result.Installed(file, enabled = true)
        }.getOrElse { Result.Failed("xdg autostart install failed: ${it.message}") }
    }

    private fun removeSystemd(env: KeepAliveEnv): Result {
        val unit = env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT")
        val autostart = env.home.resolve(".config/autostart/$XDG_AUTOSTART_FILE")
        return runCatching {
            if (env.hasCommand("systemctl") && !env.xdgRuntimeDir.isNullOrBlank()) {
                env.run(listOf("systemctl", "--user", "disable", "--now", SYSTEMD_NAME))
                env.run(listOf("systemctl", "--user", "daemon-reload"))
            }
            val a = Files.deleteIfExists(unit)
            val b = Files.deleteIfExists(autostart)
            Result.Removed(when { a -> unit; b -> autostart; else -> null })
        }.getOrElse { Result.Failed("systemd remove failed: ${it.message}") }
    }
}

/**
 * Injectable OS seam for [KeepAlive] so the Linux + macOS install branches unit-test on any host
 * without real launchctl/systemctl side effects. [SystemKeepAliveEnv] is the real one.
 */
interface KeepAliveEnv {
    val os: KeepAlive.Os
    val home: Path
    val uid: Long?
    val xdgRuntimeDir: String?
    fun hasCommand(name: String): Boolean
    /** Run [argv] best-effort; true iff it exited 0. Never throws. */
    fun run(argv: List<String>): Boolean
}

/** The real environment: OS from `os.name`, uid via UnixSystem (guarded), PATH command probing. */
object SystemKeepAliveEnv : KeepAliveEnv {
    override val os: KeepAlive.Os = run {
        val name = System.getProperty("os.name")?.lowercase() ?: ""
        when {
            name.contains("mac") || name.contains("darwin") -> KeepAlive.Os.MAC
            name.contains("nux") || name.contains("nix") -> KeepAlive.Os.LINUX
            else -> KeepAlive.Os.OTHER
        }
    }

    override val home: Path = Path.of(System.getProperty("user.home") ?: ".")

    override val uid: Long? by lazy {
        // getuid is macOS/Linux-only; guarded so it never runs on Windows.
        if (os == KeepAlive.Os.OTHER) null
        else runCatching { com.sun.security.auth.module.UnixSystem().uid }.getOrNull()
    }

    override val xdgRuntimeDir: String? get() = System.getenv("XDG_RUNTIME_DIR")

    override fun hasCommand(name: String): Boolean =
        runCatching {
            val which = if (os == KeepAlive.Os.OTHER) "where" else "which"
            ProcessBuilder(which, name)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
        }.getOrDefault(false)

    override fun run(argv: List<String>): Boolean =
        runCatching {
            ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
        }.getOrDefault(false)
}
