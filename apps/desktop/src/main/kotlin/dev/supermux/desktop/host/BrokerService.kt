package dev.supermux.desktop.host

import java.nio.file.Files
import java.nio.file.Path

/**
 * "Keep running in the background" (spec D2): the OS service manager runs THE BROKER — launchd
 * LaunchAgent (macOS), systemd --user unit (Linux, XDG autostart fallback), logon Scheduled Task
 * (Windows). The argv is always a LIST built from a real path — never a joined command line.
 */
object BrokerService {
    const val LAUNCHD_LABEL = "dev.supermux.host"
    const val SYSTEMD_UNIT = "supermux-host.service"
    const val SYSTEMD_NAME = "supermux-host"
    const val XDG_AUTOSTART_FILE = "supermux-host.desktop"
    const val WINDOWS_TASK_NAME = "Supermux Host"
    const val WINDOWS_TASK_XML = "Supermux/supermux-host-task.xml"

    data class Spec(val broker: Path, val env: Map<String, String>, val log: Path)

    sealed interface Result {
        data class Installed(val path: Path, val enabled: Boolean) : Result
        data class Removed(val path: Path?) : Result
        data object Unsupported : Result
        data class Failed(val message: String) : Result
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun powershellLiteral(value: String): String = "'${value.replace("'", "''")}'"

    /**
     * Quote one CreateProcess argument using the CommandLineToArgvW backslash/quote rules.
     * Task Scheduler stores the complete PowerShell argument string rather than an argv array.
     */
    private fun windowsArgument(value: String): String {
        if (value.isNotEmpty() && value.none { it.isWhitespace() || it == '"' }) return value
        val out = StringBuilder("\"")
        var slashes = 0
        for (char in value) {
            when (char) {
                '\\' -> slashes++
                '"' -> {
                    out.append("\\".repeat(slashes * 2 + 1)).append('"')
                    slashes = 0
                }
                else -> {
                    out.append("\\".repeat(slashes)).append(char)
                    slashes = 0
                }
            }
        }
        out.append("\\".repeat(slashes * 2)).append('"')
        return out.toString()
    }

    fun launchdPlist(spec: Spec): String {
        val env = spec.env.entries.joinToString("") { (k, v) -> "\n    <key>${xml(k)}</key>\n    <string>${xml(v)}</string>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LAUNCHD_LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>${xml(spec.broker.toString())}</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>5</integer>
  <key>ProcessType</key>
  <string>Background</string>
  <key>EnvironmentVariables</key>
  <dict>$env
  </dict>
  <key>StandardOutPath</key>
  <string>${xml(spec.log.toString())}</string>
  <key>StandardErrorPath</key>
  <string>${xml(spec.log.toString())}</string>
</dict>
</plist>
"""
    }

    private fun sdQuote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%") + "\""

    fun systemdUnit(spec: Spec): String {
        val env = spec.env.entries.joinToString("") { (k, v) -> "\nEnvironment=${sdQuote("$k=$v")}" }
        return """[Unit]
Description=supermux broker (managed by the supermux app)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=${sdQuote(spec.broker.toString())}
Restart=always
RestartSec=3$env
StandardOutput=append:${spec.log}
StandardError=append:${spec.log}

[Install]
WantedBy=default.target
"""
    }

    fun xdgAutostart(spec: Spec): String {
        val envArgs = spec.env.entries.joinToString(" ") { (k, v) -> sdQuote("$k=$v") }
        return """[Desktop Entry]
Type=Application
Name=supermux
Comment=Keep supermux running in the background
Exec=env $envArgs ${sdQuote(spec.broker.toString())}
X-GNOME-Autostart-enabled=true
Terminal=false
"""
    }

    /** Windows Task Scheduler 1.4 XML for the current interactive user. */
    fun windowsTaskXml(spec: Spec): String {
        val sets = spec.env.entries.joinToString("; ") { (k, v) -> "\$env:$k = ${powershellLiteral(v)}" }
        val script = "& { $sets; & ${powershellLiteral(spec.broker.toString())} *>> ${powershellLiteral(spec.log.toString())} }"
        val arguments = listOf(
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            windowsArgument(script),
        ).joinToString(" ")
        val executable = spec.broker.toString()
        val separator = maxOf(executable.lastIndexOf('\\'), executable.lastIndexOf('/'))
        val workingDirectory = if (separator > 0) executable.substring(0, separator) else "."
        return """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RestartOnFailure>
      <Interval>PT1M</Interval>
      <Count>255</Count>
    </RestartOnFailure>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>powershell.exe</Command>
      <Arguments>${xml(arguments)}</Arguments>
      <WorkingDirectory>${xml(workingDirectory)}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""
    }

    fun install(spec: Spec, env: OsEnv = SystemOsEnv): Result = when (env.os) {
        OsEnv.Os.MAC -> installLaunchd(spec, env)
        OsEnv.Os.LINUX -> installSystemd(spec, env)
        OsEnv.Os.WINDOWS -> installWindowsTask(spec, env)
        OsEnv.Os.OTHER -> Result.Unsupported
    }

    fun remove(env: OsEnv = SystemOsEnv): Result = when (env.os) {
        OsEnv.Os.MAC -> removeLaunchd(env)
        OsEnv.Os.LINUX -> removeSystemd(env)
        OsEnv.Os.WINDOWS -> removeWindowsTask(env)
        OsEnv.Os.OTHER -> Result.Unsupported
    }

    /**
     * Restart the running service in place. True iff the OS accepted the command. On Windows this
     * raises a UAC prompt, so it is only called from an explicit user action, never automatically.
     */
    fun restart(env: OsEnv = SystemOsEnv): Boolean = when (env.os) {
        OsEnv.Os.MAC -> env.uid != null && env.run(listOf("launchctl", "kickstart", "-k", "gui/${env.uid}/$LAUNCHD_LABEL"))
        OsEnv.Os.LINUX -> env.run(listOf("systemctl", "--user", "restart", SYSTEMD_NAME))
        OsEnv.Os.WINDOWS -> {
            runElevatedSchtasks(env, listOf("/End", "/TN", WINDOWS_TASK_NAME))
            runElevatedSchtasks(env, listOf("/Run", "/TN", WINDOWS_TASK_NAME))
        }
        OsEnv.Os.OTHER -> false
    }

    fun isInstalled(env: OsEnv = SystemOsEnv): Boolean = when (env.os) {
        OsEnv.Os.MAC -> Files.exists(env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist"))
        OsEnv.Os.LINUX -> Files.exists(env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT")) ||
            Files.exists(env.home.resolve(".config/autostart/$XDG_AUTOSTART_FILE"))
        OsEnv.Os.WINDOWS -> Files.exists(env.localAppData.resolve(WINDOWS_TASK_XML))
        OsEnv.Os.OTHER -> false
    }

    private fun installLaunchd(spec: Spec, env: OsEnv): Result {
        val plist = env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist")
        return runCatching {
            Files.createDirectories(plist.parent)
            Files.writeString(plist, launchdPlist(spec))
            val enabled = if (env.hasCommand("launchctl") && env.uid != null) {
                val domain = "gui/${env.uid}"
                env.run(listOf("launchctl", "bootout", "$domain/$LAUNCHD_LABEL")) // ignore: first install has none
                val ok = env.run(listOf("launchctl", "bootstrap", domain, plist.toString()))
                env.run(listOf("launchctl", "enable", "$domain/$LAUNCHD_LABEL"))
                ok
            } else false
            Result.Installed(plist, enabled)
        }.getOrElse { Result.Failed("launchd install failed: ${it.message}") }
    }

    private fun removeLaunchd(env: OsEnv): Result {
        val plist = env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist")
        return runCatching {
            if (env.hasCommand("launchctl") && env.uid != null) env.run(listOf("launchctl", "bootout", "gui/${env.uid}/$LAUNCHD_LABEL"))
            Result.Removed(if (Files.deleteIfExists(plist)) plist else null)
        }.getOrElse { Result.Failed("launchd remove failed: ${it.message}") }
    }

    private fun installSystemd(spec: Spec, env: OsEnv): Result {
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

    private fun installXdgAutostart(spec: Spec, env: OsEnv): Result {
        val file = env.home.resolve(".config/autostart/$XDG_AUTOSTART_FILE")
        return runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, xdgAutostart(spec))
            Result.Installed(file, enabled = true)
        }.getOrElse { Result.Failed("xdg autostart install failed: ${it.message}") }
    }

    private fun removeSystemd(env: OsEnv): Result {
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

    private fun installWindowsTask(spec: Spec, env: OsEnv): Result {
        val taskXml = env.localAppData.resolve(WINDOWS_TASK_XML)
        return runCatching {
            Files.createDirectories(taskXml.parent)
            Files.writeString(taskXml, windowsTaskXml(spec), Charsets.UTF_16)
            val enabled = runElevatedSchtasks(
                env,
                listOf("/Create", "/TN", WINDOWS_TASK_NAME, "/XML", taskXml.toString(), "/F"),
            )
            Result.Installed(taskXml, enabled)
        }.getOrElse { Result.Failed("Windows Scheduled Task install failed: ${it.message}") }
    }

    private fun removeWindowsTask(env: OsEnv): Result {
        val taskXml = env.localAppData.resolve(WINDOWS_TASK_XML)
        return runCatching {
            runElevatedSchtasks(env, listOf("/Delete", "/TN", WINDOWS_TASK_NAME, "/F"))
            val existed = Files.deleteIfExists(taskXml)
            Result.Removed(if (existed) taskXml else null)
        }.getOrElse { Result.Failed("Windows Scheduled Task remove failed: ${it.message}") }
    }

    /**
     * Windows 11 denies even current-user Task Scheduler registration to a non-elevated process.
     * Elevate only schtasks (the registered task itself remains InteractiveToken/LeastPrivilege).
     */
    private fun runElevatedSchtasks(env: OsEnv, args: List<String>): Boolean {
        val argumentLine = args.joinToString(" ") { windowsArgument(it) }
        val script =
            "\$process = Start-Process -FilePath 'schtasks.exe' -Verb RunAs -Wait -PassThru " +
                "-ArgumentList ${powershellLiteral(argumentLine)}; exit \$process.ExitCode"
        return env.run(
            listOf(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script,
            ),
        )
    }
}
