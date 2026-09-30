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
    const val WINDOWS_BROKER_EXE = "supermux-broker.exe"
    const val WINDOWS_TASK_XML = "Supermux/supermux-host-task.xml"

    data class Spec(val broker: Path, val env: Map<String, String>, val log: Path) {
        init {
            fun bad(x: String) = x.contains('\n') || x.contains('\r')
            require(env.none { (k, v) -> bad(k) || bad(v) }) { "env must not contain line breaks" }
            require(!bad(broker.toString()) && !bad(log.toString())) { "paths must not contain line breaks" }
        }
    }

    sealed interface Result {
        data class Installed(val path: Path, val enabled: Boolean) : Result
        data class Removed(val path: Path?) : Result
        data object Unsupported : Result
        data class Failed(val message: String) : Result
    }

    /** Every definition we write carries these so [Takeover] can never mistake it for an old service. */
    const val MANAGED_MARKER = "supermux-managed: desktop"

    private fun withManaged(env: Map<String, String>): Map<String, String> = env + ("MUX_MANAGED_BY" to "desktop")

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun powershellLiteral(value: String): String =
        "'${value.replace(Regex("['\u2018\u2019\u201A\u201B]")) { it.value + it.value }}'"

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
        val env = withManaged(spec.env).entries.joinToString("") { (k, v) -> "\n    <key>${xml(k)}</key>\n    <string>${xml(v)}</string>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <!-- $MANAGED_MARKER -->
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
  <string>Standard</string>
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

    /** ExecStart additionally expands `$VAR`, so a literal `$` must be `$$`. */
    private fun sdExecQuote(s: String) = sdQuote(s).replace("$", "$$")

    /** Desktop Entry `Exec=` argument: quoted, reserved chars backslashed, backslashes doubled, `%` doubled. */
    private fun xdgQuote(s: String): String {
        val reserved = s.replace(Regex("[\"`$\\\\]")) { "\\" + it.value }
        return "\"" + reserved.replace("\\", "\\\\").replace("%", "%%") + "\""
    }

    fun systemdUnit(spec: Spec): String {
        val env = withManaged(spec.env).entries.joinToString("") { (k, v) -> "\nEnvironment=${sdQuote("$k=$v")}" }
        return """[Unit]
Description=supermux broker ($MANAGED_MARKER)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=${sdExecQuote(spec.broker.toString())}
Restart=always
RestartSec=3$env
StandardOutput=append:${spec.log.toString().replace("%", "%%")}
StandardError=append:${spec.log.toString().replace("%", "%%")}

[Install]
WantedBy=default.target
"""
    }

    fun xdgAutostart(spec: Spec): String {
        val envArgs = withManaged(spec.env).entries.joinToString(" ") { (k, v) -> xdgQuote("$k=$v") }
        return """[Desktop Entry]
# $MANAGED_MARKER
Type=Application
Name=supermux
Comment=Keep supermux running in the background
Exec=env $envArgs ${xdgQuote(spec.broker.toString())}
X-GNOME-Autostart-enabled=true
Terminal=false
"""
    }

    /** Windows Task Scheduler 1.4 XML for the current interactive user. */
    fun windowsTaskXml(spec: Spec): String {
        val sets = withManaged(spec.env).entries.joinToString("; ") { (k, v) -> "\$env:$k = ${powershellLiteral(v)}" }
        val script = "\$PSDefaultParameterValues['Out-File:Encoding']='utf8'; $sets; " +
            "while (\$true) { & ${powershellLiteral(spec.broker.toString())} 2>&1 | ForEach-Object { \"\$_\" } | " +
            "Out-File -Append -FilePath ${powershellLiteral(spec.log.toString())}; Start-Sleep -Seconds 5 }"
        val arguments = listOf(
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
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
  <!-- $MANAGED_MARKER -->
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

    /** Restart the running service in place. True iff the OS accepted the command. */
    fun restart(env: OsEnv = SystemOsEnv): Boolean = when (env.os) {
        OsEnv.Os.MAC -> env.uid != null && env.run(listOf("launchctl", "kickstart", "-k", "gui/${env.uid}/$LAUNCHD_LABEL"))
        OsEnv.Os.LINUX -> env.run(listOf("systemctl", "--user", "restart", SYSTEMD_NAME))
        // The task's loop respawns the broker ~5 s after it dies; no elevation needed.
        OsEnv.Os.WINDOWS -> env.run(listOf("taskkill", "/F", "/IM", WINDOWS_BROKER_EXE))
        OsEnv.Os.OTHER -> false
    }

    fun isInstalled(env: OsEnv = SystemOsEnv): Boolean = when (env.os) {
        OsEnv.Os.MAC -> Files.exists(env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist"))
        OsEnv.Os.LINUX -> Files.exists(env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT")) ||
            Files.exists(env.home.resolve(".config/autostart/$XDG_AUTOSTART_FILE"))
        OsEnv.Os.WINDOWS -> env.runResult(listOf("schtasks", "/Query", "/TN", WINDOWS_TASK_NAME)).exit == 0
        OsEnv.Os.OTHER -> false
    }

    /**
     * True iff a definition WE wrote (it carries [MANAGED_MARKER]) is installed. The retired Swift
     * app used the same launchd label, so file existence alone is not enough.
     */
    fun isOursInstalled(env: OsEnv = SystemOsEnv): Boolean = runCatching {
        fun ours(p: Path) = Files.isRegularFile(p) && MANAGED_MARKER in Files.readString(p)
        when (env.os) {
            OsEnv.Os.MAC -> ours(env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist"))
            OsEnv.Os.LINUX -> ours(env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT")) || ours(xdgAutostartPath(env))
            OsEnv.Os.WINDOWS -> isInstalled(env)
            OsEnv.Os.OTHER -> false
        }
    }.getOrDefault(false)

    /** True iff OUR "service" is the Linux XDG autostart fallback (no systemd unit of ours): it only starts at login. */
    fun isOursXdgAutostart(env: OsEnv = SystemOsEnv): Boolean = env.os == OsEnv.Os.LINUX && runCatching {
        fun ours(p: Path) = Files.isRegularFile(p) && MANAGED_MARKER in Files.readString(p)
        ours(xdgAutostartPath(env)) && !ours(env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT"))
    }.getOrDefault(false)

    /** The XDG autostart fallback file (Linux without systemd --user). */
    fun xdgAutostartPath(env: OsEnv): Path = env.home.resolve(".config/autostart/$XDG_AUTOSTART_FILE")

    private fun installLaunchd(spec: Spec, env: OsEnv): Result {
        val plist = env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist")
        return runCatching {
            Files.createDirectories(plist.parent)
            spec.log.parent?.let { Files.createDirectories(it) }
            Files.writeString(plist, launchdPlist(spec))
            if (env.hasCommand("launchctl") && env.uid != null) {
                val domain = "gui/${env.uid}"
                env.runResult(listOf("launchctl", "bootout", "$domain/$LAUNCHD_LABEL")) // ignore: first install has none
                env.runResult(listOf("launchctl", "enable", "$domain/$LAUNCHD_LABEL"))
                val last = bootstrapWithRetry(domain, plist, env)
                if (last.exit != 0) return Result.Failed("launchctl bootstrap failed: ${last.err.trim()}")
                Result.Installed(plist, true)
            } else Result.Installed(plist, false)
        }.getOrElse { Result.Failed("launchd install failed: ${it.message}") }
    }

    /** `launchctl bootstrap`, retried (the just-booted-out label can still be tearing down). */
    internal fun bootstrapWithRetry(domain: String, plist: Path, env: OsEnv): OsEnv.RunResult {
        var last = OsEnv.RunResult(-1, "", "")
        for (attempt in 1..5) {
            last = env.runResult(listOf("launchctl", "bootstrap", domain, plist.toString()))
            if (last.exit == 0) break
            if (attempt < 5) env.sleep(500)
        }
        return last
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
            spec.log.parent?.let { Files.createDirectories(it) }
            Files.writeString(unit, systemdUnit(spec))
            env.run(listOf("systemctl", "--user", "daemon-reload"))
            val enabled = env.run(listOf("systemctl", "--user", "enable", "--now", SYSTEMD_NAME))
            // enable --now is a no-op for an already-running unit; restart applies a new env/binary.
            env.run(listOf("systemctl", "--user", "restart", SYSTEMD_NAME))
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
            Result.Installed(file, enabled = false) // only starts at next login
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
            Files.deleteIfExists(env.home.resolve(".config/systemd/user/default.target.wants/$SYSTEMD_UNIT"))
            val b = Files.deleteIfExists(autostart)
            Result.Removed(when { a -> unit; b -> autostart; else -> null })
        }.getOrElse { Result.Failed("systemd remove failed: ${it.message}") }
    }

    private fun installWindowsTask(spec: Spec, env: OsEnv): Result {
        val taskXml = env.localAppData.resolve(WINDOWS_TASK_XML)
        return runCatching {
            Files.createDirectories(taskXml.parent)
            Files.writeString(taskXml, "\uFEFF" + windowsTaskXml(spec), Charsets.UTF_16LE)
            // ONE elevated invocation (one UAC prompt): create the task, then start it now.
            val ok = runElevatedSchtasksBatch(
                env,
                listOf(
                    listOf("/Create", "/TN", WINDOWS_TASK_NAME, "/XML", taskXml.toString(), "/F"),
                    listOf("/Run", "/TN", WINDOWS_TASK_NAME),
                ),
            )
            if (!ok) {
                Files.deleteIfExists(taskXml)
                Result.Failed("Windows Scheduled Task install failed (elevation declined or schtasks error)")
            } else Result.Installed(taskXml, true)
        }.getOrElse { Result.Failed("Windows Scheduled Task install failed: ${it.message}") }
    }

    private fun removeWindowsTask(env: OsEnv): Result {
        val taskXml = env.localAppData.resolve(WINDOWS_TASK_XML)
        return runCatching {
            if (!isInstalled(env)) {
                Files.deleteIfExists(taskXml)
                return Result.Removed(null)
            }
            runElevatedSchtasksBatch(env, listOf(listOf("/Delete", "/TN", WINDOWS_TASK_NAME, "/F")))
            env.run(listOf("taskkill", "/F", "/IM", WINDOWS_BROKER_EXE))
            val existed = Files.deleteIfExists(taskXml)
            Result.Removed(if (existed) taskXml else null)
        }.getOrElse { Result.Failed("Windows Scheduled Task remove failed: ${it.message}") }
    }

    /**
     * Windows 11 denies even current-user Task Scheduler registration to a non-elevated process.
     * Elevate once (one UAC prompt) and run every schtasks call inside that elevated PowerShell;
     * false if any exit code is non-zero. The registered task itself stays InteractiveToken/LeastPrivilege.
     */
    private fun runElevatedSchtasksBatch(env: OsEnv, calls: List<List<String>>): Boolean {
        val inner = calls.joinToString("; ") { args ->
            "& schtasks.exe ${args.joinToString(" ") { powershellLiteral(it) }}; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }"
        }
        val innerArgs = listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", windowsArgument(inner))
            .joinToString(" ")
        val script =
            "\$process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru " +
                "-ArgumentList ${powershellLiteral(innerArgs)}; exit \$process.ExitCode"
        return env.run(
            listOf(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-Command", script,
            ),
        )
    }
}
