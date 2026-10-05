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
    /** ERROR_CANCELLED: what the elevated batch exits with when the UAC prompt is declined. */
    internal const val UAC_DECLINED_EXIT = 1223

    const val WINDOWS_UPDATE_DECLINED =
        "Couldn't update the background service (permission declined); still running the previous version."

    /** Set in the Scheduled Task broker's env: it runs under the task's restart loop. */
    const val WINDOWS_TASK_ENV = "MUX_WINDOWS_TASK"

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
        /**
         * [previousStillRunning]: the install didn't happen, but OUR previous service definition is
         * still installed and was started again, so a broker is running (an older one). Windows:
         * the user declined the UAC prompt for a changed task definition.
         */
        data class Failed(val message: String, val previousStillRunning: Boolean = false) : Result
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

    /**
     * Windows Task Scheduler 1.4 XML for the current interactive user.
     *
     * The action is `conhost.exe --headless powershell.exe …`, not powershell.exe itself: on
     * Windows 11 the default terminal is Windows Terminal, which ignores `-WindowStyle Hidden`, so a
     * console process started at logon gets a visible, empty terminal window, and closing it kills
     * the loop and the broker. A headless conhost gives the loop a console with no window.
     *
     * Ending the task (`schtasks /End`) only terminates conhost, so the loop remembers its parent
     * and stops once the broker exits after conhost is gone; otherwise it would respawn the broker.
     * `MUX_WINDOWS_TASK=1` tells the broker it runs under this loop (`/system/restart` = exit).
     */
    fun windowsTaskXml(spec: Spec): String {
        val sets = (withManaged(spec.env) + (WINDOWS_TASK_ENV to "1")).entries
            .joinToString("; ") { (k, v) -> "\$env:$k = ${powershellLiteral(v)}" }
        // The parent (the task's conhost) is looked up once and its handle opened (`.Handle`), so
        // HasExited stays about THAT process even if its pid is reused later. If WMI can't answer
        // (null), the loop just never stops on its own; stopping the task then kills it by pid.
        // No double quotes anywhere: the script travels inside one quoted -Command argument.
        val script = "\$PSDefaultParameterValues['Out-File:Encoding']='utf8'; $sets; " +
            "\$supermuxHost = Get-Process -Id (Get-CimInstance Win32_Process -Filter ('ProcessId=' + \$PID)).ParentProcessId -ErrorAction SilentlyContinue; " +
            "if (\$supermuxHost) { \$null = \$supermuxHost.Handle }; " +
            "while (\$true) { & ${powershellLiteral(spec.broker.toString())} 2>&1 | ForEach-Object { \$_.ToString() } | " +
            "Out-File -Append -FilePath ${powershellLiteral(spec.log.toString())}; " +
            "if (\$supermuxHost -and \$supermuxHost.HasExited) { break }; Start-Sleep -Seconds 5 }"
        val arguments = listOf(
            "--headless",
            "powershell.exe",
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
    <MultipleInstancesPolicy>StopExisting</MultipleInstancesPolicy>
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
      <Command>conhost.exe</Command>
      <Arguments>${xml(arguments)}</Arguments>
      <WorkingDirectory>${xml(workingDirectory)}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""
    }

    /**
     * Install (or re-install, which restarts) the service. [alreadyStopped]: the caller just stopped
     * our running service broker itself ([stop], Windows), so don't stop it again.
     */
    fun install(spec: Spec, env: OsEnv = SystemOsEnv, alreadyStopped: Boolean = false): Result = when (env.os) {
        OsEnv.Os.MAC -> installLaunchd(spec, env)
        OsEnv.Os.LINUX -> installSystemd(spec, env)
        OsEnv.Os.WINDOWS -> installWindowsTask(spec, env, alreadyStopped)
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
        // Stop the loop and its broker, then start the task afresh; no elevation needed. /Run also
        // brings back a task whose loop died, and (StopExisting) replaces a lingering instance.
        OsEnv.Os.WINDOWS -> {
            stopWindowsTask(env)
            env.run(listOf("schtasks", "/Run", "/TN", WINDOWS_TASK_NAME))
        }
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
                // bootout returns while the old job is still shutting down (graceful SIGTERM); bootstrapping
                // over it fails with "Input/output error". Wait until launchd has dropped it.
                awaitLaunchdGone("$domain/$LAUNCHD_LABEL", env)
                env.runResult(listOf("launchctl", "enable", "$domain/$LAUNCHD_LABEL"))
                val last = bootstrapWithRetry(domain, plist, env)
                if (last.exit != 0) return Result.Failed("launchctl bootstrap failed: ${last.err.trim()}")
                Result.Installed(plist, true)
            } else Result.Installed(plist, false)
        }.getOrElse { Result.Failed("launchd install failed: ${it.message}") }
    }

    /**
     * Poll `launchctl print <target>` until it fails (launchd no longer knows the job), up to 20 × 500 ms.
     * True once it's gone. Call after a bootout and before bootstrapping the same label again.
     */
    internal fun awaitLaunchdGone(target: String, env: OsEnv): Boolean {
        for (i in 1..20) {
            if (env.runResult(listOf("launchctl", "print", target)).exit != 0) return true
            env.sleep(500)
        }
        return env.runResult(listOf("launchctl", "print", target)).exit != 0
    }

    /** `launchctl bootstrap`, retried 10 × 1 s (the just-booted-out label can still be tearing down). */
    internal fun bootstrapWithRetry(domain: String, plist: Path, env: OsEnv): OsEnv.RunResult {
        var last = OsEnv.RunResult(-1, "", "")
        for (attempt in 1..10) {
            last = env.runResult(listOf("launchctl", "bootstrap", domain, plist.toString()))
            if (last.exit == 0) break
            if (attempt < 10) env.sleep(1_000)
        }
        return last
    }

    private fun removeLaunchd(env: OsEnv): Result {
        val plist = env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist")
        return runCatching {
            if (env.hasCommand("launchctl") && env.uid != null) {
                env.run(listOf("launchctl", "bootout", "gui/${env.uid}/$LAUNCHD_LABEL"))
                awaitLaunchdGone("gui/${env.uid}/$LAUNCHD_LABEL", env)
            }
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

    /**
     * Windows: stop OUR running broker without elevation — the task's loop, then the broker — and
     * wait until the broker process is gone, so its .exe can be replaced. The task stays registered;
     * [install] or `schtasks /Run` starts it again. No-op elsewhere (false).
     */
    fun stop(env: OsEnv = SystemOsEnv): Boolean = env.os == OsEnv.Os.WINDOWS && stopWindowsTask(env)

    /**
     * Stop the task's loop, then its broker, BY PID, and wait for the broker to exit. Never by image
     * name: every agent's MCP shim is `supermux-broker.exe shim` (and the credential helper
     * `supermux-broker.exe credential`), and those must survive a broker restart or update.
     * False when the processes couldn't be listed or a broker is still alive after ~10 s.
     */
    private fun stopWindowsTask(env: OsEnv): Boolean {
        val procs = listWindowsProcesses(env) ?: return false
        val targets = windowsTaskTargets(procs)
        // The loop first, or it starts the broker again ~5 s after the kill.
        if (targets.loops.isNotEmpty()) env.run(taskkillArgv(targets.loops))
        if (targets.brokers.isNotEmpty()) env.run(taskkillArgv(targets.brokers))
        return awaitWindowsPidsGone(env, targets.brokers)
    }

    /**
     * Pure: does the registered task's XML ([registered], `schtasks /Query /XML`) run what [ours]
     * says — same command, arguments, working directory and instance policy? Null: unknown (no).
     */
    fun sameTaskAction(registered: String?, ours: String): Boolean {
        if (registered.isNullOrBlank()) return false
        val tags = listOf("Command", "Arguments", "WorkingDirectory", "MultipleInstancesPolicy")
        fun field(xml: String, tag: String): String? = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")?.replace("&amp;", "&")
        return tags.all { field(registered, it) == field(ours, it) }
    }

    /** One `Win32_Process` row: what [windowsTaskTargets] decides on. */
    data class WinProcess(val pid: Long, val parentPid: Long, val name: String, val commandLine: String)

    /** The task's restart loops and the brokers to stop; agents' `… shim` / `… credential` processes never. */
    data class WindowsTaskTargets(val loops: List<Long>, val brokers: List<Long>)

    /**
     * Pure. A loop is a powershell.exe whose command line runs the broker and sets
     * [WINDOWS_TASK_ENV] under the task's (headless) conhost — or orphaned once `schtasks /End`
     * killed that conhost — or, for loops an older app version installed, `MUX_MANAGED_BY`. A broker
     * is a `supermux-broker.exe` that is a loop's child or runs with no subcommand at all (a bare
     * broker); `supermux-broker.exe shim|credential|…` is never one.
     */
    fun windowsTaskTargets(procs: List<WinProcess>): WindowsTaskTargets {
        val byPid = procs.associateBy { it.pid }
        fun named(p: WinProcess?, n: String) = p != null && p.name.equals(n, ignoreCase = true)
        val loops = procs.filter { p ->
            named(p, "powershell.exe") && p.commandLine.contains(WINDOWS_BROKER_EXE, ignoreCase = true) && when {
                p.commandLine.contains(WINDOWS_TASK_ENV) -> byPid[p.parentPid].let { it == null || named(it, "conhost.exe") }
                else -> p.commandLine.contains("MUX_MANAGED_BY")
            }
        }.map { it.pid }
        val brokers = procs.filter { p ->
            named(p, WINDOWS_BROKER_EXE) && (p.parentPid in loops || isBareBrokerCommandLine(p.commandLine))
        }.map { it.pid }
        return WindowsTaskTargets(loops, brokers)
    }

    /** `"C:\…\supermux-broker.exe"` (or unquoted) with nothing after it: the broker itself, no subcommand. */
    fun isBareBrokerCommandLine(commandLine: String): Boolean {
        val c = commandLine.trim()
        if (c.isEmpty()) return false
        val rest = if (c.startsWith('"')) {
            val end = c.indexOf('"', 1)
            if (end < 0) return false
            if (!c.substring(1, end).endsWith(WINDOWS_BROKER_EXE, ignoreCase = true)) return false
            c.substring(end + 1)
        } else {
            val end = c.indexOf(WINDOWS_BROKER_EXE, ignoreCase = true)
            if (end < 0) return false
            c.substring(end + WINDOWS_BROKER_EXE.length)
        }
        return rest.isBlank()
    }

    /**
     * Lists powershell / conhost / broker processes as `pid TAB parentPid TAB name TAB commandLine`.
     * No double quotes in the script: Java does not escape them on Windows.
     */
    internal fun listWindowsProcessesArgv(): List<String> = listOf(
        "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
        "Get-CimInstance Win32_Process | Where-Object { \$_.Name -eq 'powershell.exe' -or \$_.Name -eq 'conhost.exe' -or " +
            "\$_.Name -eq '$WINDOWS_BROKER_EXE' } | ForEach-Object { [string]\$_.ProcessId + [char]9 + [string]\$_.ParentProcessId + " +
            "[char]9 + \$_.Name + [char]9 + [string]\$_.CommandLine }",
    )

    internal fun listWindowsProcesses(env: OsEnv): List<WinProcess>? =
        env.runCapture(listWindowsProcessesArgv())?.let(::parseWindowsProcesses)

    /** Pure: [listWindowsProcessesArgv]'s output → rows (malformed lines are skipped). */
    fun parseWindowsProcesses(out: String): List<WinProcess> = out.lineSequence().mapNotNull { line ->
        val f = line.trimEnd('\r').split('\t', limit = 4)
        if (f.size < 3) return@mapNotNull null
        val pid = f[0].trim().toLongOrNull() ?: return@mapNotNull null
        WinProcess(pid, f[1].trim().toLongOrNull() ?: 0, f[2].trim(), f.getOrElse(3) { "" })
    }.toList()

    private fun taskkillArgv(pids: List<Long>): List<String> =
        listOf("taskkill", "/F") + pids.flatMap { listOf("/PID", it.toString()) }

    /** Poll `tasklist` until none of [pids] runs, up to 20 × 500 ms. True once they're all gone. */
    internal fun awaitWindowsPidsGone(env: OsEnv, pids: List<Long>): Boolean {
        fun alive(pid: Long) = env.runCapture(listOf("tasklist", "/FI", "PID eq $pid", "/NH"))
            ?.contains(WINDOWS_BROKER_EXE, ignoreCase = true) == true
        var left = pids
        for (i in 1..20) {
            left = left.filter(::alive)
            if (left.isEmpty()) return true
            env.sleep(500)
        }
        return left.none(::alive)
    }

    private fun installWindowsTask(spec: Spec, env: OsEnv, alreadyStopped: Boolean): Result {
        val taskXml = env.localAppData.resolve(WINDOWS_TASK_XML)
        val content = "\uFEFF" + windowsTaskXml(spec)
        val run = listOf("schtasks", "/Run", "/TN", WINDOWS_TASK_NAME)
        return runCatching {
            Files.createDirectories(taskXml.parent)
            val wasInstalled = isInstalled(env)
            // Compare with what is REGISTERED, not with our file: the file can say one thing while
            // the task still runs another (a prompt declined after the file was written).
            val unchanged = wasInstalled && sameTaskAction(env.runCapture(listOf("schtasks", "/Query", "/TN", WINDOWS_TASK_NAME, "/XML")), content)
            // A running instance must not keep the old loop (and broker) alive past re-registration,
            // and an unchanged one restarts with the (maybe new) broker binary.
            if (wasInstalled && !alreadyStopped) stopWindowsTask(env)
            if (unchanged && wasInstalled) {
                // The registered task already says exactly this (an app update with the same env, a
                // restart): no UAC prompt, just start it again.
                if (env.run(run)) return Result.Installed(taskXml, true)
            }
            Files.writeString(taskXml, content, Charsets.UTF_16LE)
            // ONE elevated invocation (one UAC prompt): create the task, then start it now.
            val ok = runElevatedSchtasksBatch(
                env,
                listOf(
                    listOf("/Create", "/TN", WINDOWS_TASK_NAME, "/XML", taskXml.toString(), "/F"),
                    listOf("/Run", "/TN", WINDOWS_TASK_NAME),
                ),
            )
            if (!ok) {
                // The file must not claim a definition that was never registered (the next install
                // would skip the prompt and run the old one).
                Files.deleteIfExists(taskXml)
                // Declined UAC on a CHANGED definition: we stopped the old broker above, so start the
                // old definition again (no elevation) rather than leave nothing running.
                if (wasInstalled && env.run(run)) {
                    return Result.Failed(WINDOWS_UPDATE_DECLINED, previousStillRunning = true)
                }
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
            // /End first: the task's PowerShell loop would otherwise respawn the broker ~5 s after taskkill.
            val ended = runElevatedSchtasksBatch(env, listOf(
                listOf("/End", "/TN", WINDOWS_TASK_NAME),
                listOf("/Delete", "/TN", WINDOWS_TASK_NAME, "/F"),
            ))
            // Declined UAC or a failed /Delete leaves the task registered; its loop would respawn the broker.
            if (!ended) return Result.Failed("Windows Scheduled Task remove failed: the elevated /End + /Delete did not succeed")
            // /End only terminates the task's own process (the headless conhost): stop its loop too,
            // then the broker, and wait until the broker is really gone. Starting the same .exe while
            // the killed one is still being torn down fails with a sharing violation.
            stopWindowsTask(env)
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
            val call = "& schtasks.exe ${args.joinToString(" ") { powershellLiteral(it) }}"
            // /End fails when the task is not running, which is fine: keep going to the next call.
            if (args.firstOrNull() == "/End") call else "$call; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }"
        }
        val innerArgs = listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", windowsArgument(inner))
            .joinToString(" ")
        // A declined UAC prompt makes Start-Process fail with a NON-terminating error: $process is
        // then null and `exit $null.ExitCode` exits 0, reporting a success that never happened
        // (the task looked installed and no broker ran). Stop on it and exit 1223 (ERROR_CANCELLED).
        val script =
            "try { \$process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ErrorAction Stop " +
                "-ArgumentList ${powershellLiteral(innerArgs)} } catch { exit $UAC_DECLINED_EXIT }; " +
                "if (-not \$process) { exit $UAC_DECLINED_EXIT }; exit \$process.ExitCode"
        return env.run(
            listOf(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-Command", script,
            ),
        )
    }
}
