package dev.supermux.desktop.host

import dev.supermux.desktop.platform.windowsPowerShell
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

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
    /** Written by app versions before the env moved out of the task; deleted on install / remove (it held the env). */
    const val WINDOWS_TASK_XML = "Supermux/supermux-host-task.xml"
    /**
     * Under `%LOCALAPPDATA%` (only the user, SYSTEM and Administrators can read it): the task loop's
     * env, a script of `$env:KEY = '…'` lines the loop dot-sources. Not a KEY=value file parsed in the
     * task's command line: Windows refuses to start a task whose PowerShell command parses one
     * (`Get-Content … | ForEach-Object { $_.IndexOf('=') … }` ends in 0x80070005 at launch).
     */
    const val WINDOWS_ENV_FILE = "Supermux/broker-env.ps1"
    /** The XDG autostart's env, sourced by its `Exec` line (0600): the values never appear in an argv. */
    const val XDG_ENV_FILE = ".config/supermux/broker.env"
    /** ERROR_CANCELLED: what the elevated batch exits with when the UAC prompt is declined. */
    internal const val UAC_DECLINED_EXIT = 1223

    const val WINDOWS_STOP_FAILED =
        "Couldn't stop the background service to update it; it keeps running the previous version."

    const val WINDOWS_UPDATE_DECLINED =
        "Couldn't update the background service (permission declined); still running the previous version."

    /** Set in the Scheduled Task broker's env: it runs under the task's restart loop. */
    const val WINDOWS_TASK_ENV = "MUX_WINDOWS_TASK"

    /** An env key every definition can carry as-is: a shell, PowerShell and systemd name. */
    val ENV_KEY = Regex("[A-Z0-9_]+")

    data class Spec(val broker: Path, val env: Map<String, String>, val log: Path) {
        init {
            fun bad(x: String) = x.contains('\n') || x.contains('\r') || x.contains('\u0000')
            require(env.none { (k, v) -> bad(k) || bad(v) }) { "env must not contain line breaks" }
            require(env.keys.all { ENV_KEY.matches(it) }) { "env keys must be [A-Z0-9_]+" }
            require(!bad(broker.toString()) && !bad(log.toString())) { "paths must not contain line breaks" }
        }
    }

    sealed interface Result {
        /**
         * [nextLogin]: written, but it takes effect only at the next login — the app itself runs as
         * the job under our name (a 1.0.0 keep-alive), and replacing that job now would kill the
         * app. Run the broker as the app's child until then.
         */
        data class Installed(val path: Path, val enabled: Boolean, val nextLogin: Boolean = false) : Result
        data class Removed(val path: Path?) : Result
        data object Unsupported : Result
        /**
         * [previousStillRunning]: the install didn't happen, but OUR previous service definition is
         * still installed and was started again, so a broker is running (an older one). Windows:
         * the user declined the UAC prompt for a changed task definition.
         */
        data class Failed(val message: String, val previousStillRunning: Boolean = false) : Result
        /** Another copy of the app's definition is at [path]: left exactly as it is (#77). */
        data class NotOurs(val path: Path) : Result
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

    /**
     * The app's bundle id (build.gradle.kts `macOS { bundleID }`). A LaunchAgent that runs a bare
     * Mach-O is otherwise listed under its signing certificate's NAME (the developer's personal name,
     * blank icon) in System Settings ▸ Login Items ▸ App Background Activity; with
     * `AssociatedBundleIdentifiers` macOS 13+ shows it as the app. An install from before this key
     * gets it at the next update's reinstall (UpdateOwn rewrites and reloads the definition).
     */
    const val MAC_APP_BUNDLE_ID = "dev.supermux.desktop"

    fun launchdPlist(spec: Spec): String {
        val env = withManaged(spec.env).entries.joinToString("") { (k, v) -> "\n    <key>${xml(k)}</key>\n    <string>${xml(v)}</string>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <!-- $MANAGED_MARKER -->
  <key>Label</key>
  <string>$LAUNCHD_LABEL</string>
  <key>AssociatedBundleIdentifiers</key>
  <array>
    <string>$MAC_APP_BUNDLE_ID</string>
  </array>
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

    /** POSIX shell single-quoting: the value is taken literally. */
    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * The XDG autostart entry. Its env lives in [envFile] (0600, [xdgEnvFile]), which the `Exec`
     * line sources: an autostart entry's argv is visible to every user (`ps`), the env's values
     * (tokens a takeover carried) must not be.
     */
    fun xdgAutostart(spec: Spec, envFile: Path): String {
        val script = ". ${shQuote(envFile.toString())} && exec ${shQuote(spec.broker.toString())}"
        return """[Desktop Entry]
# $MANAGED_MARKER
Type=Application
Name=supermux
Comment=Keep supermux running in the background
Exec=/bin/sh -c ${xdgQuote(script)}
X-GNOME-Autostart-enabled=true
Terminal=false
"""
    }

    /** [xdgAutostart]'s env file: one `export KEY='value'` per line. */
    fun xdgEnvFile(spec: Spec): String =
        withManaged(spec.env).entries.joinToString("") { (k, v) -> "export $k=${shQuote(v)}\n" }

    /** The Windows task loop's env file: one `$env:KEY = '…'` per line, dot-sourced by [windowsTaskXml]'s loop. */
    fun windowsEnvFile(spec: Spec): String =
        withManaged(spec.env).entries.joinToString("") { (k, v) -> "\$env:$k = ${powershellLiteral(v)}\r\n" }

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
     *
     * The env is NOT in the definition: the loop reads [envFile] ([windowsEnvFile], under the user's
     * private `%LOCALAPPDATA%`) before every start of the broker. A registered task's XML can be
     * read back by administrators and backup tools; the env's tokens stay in the user's profile.
     * So an env change (relay on/off) changes no definition and needs no UAC prompt.
     */
    fun windowsTaskXml(spec: Spec, envFile: Path): String {
        // The parent (the task's conhost) is looked up once and its handle opened (`.Handle`), so
        // HasExited stays about THAT process even if its pid is reused later. If WMI can't answer
        // (null), the loop just never stops on its own; stopping the task then kills it by pid.
        // No double quotes anywhere: the script travels inside one quoted -Command argument.
        val env = powershellLiteral(envFile.toString())
        val loadEnv = "if (Test-Path -LiteralPath $env) { . $env }"
        val script = "\$PSDefaultParameterValues['Out-File:Encoding']='utf8'; \$env:$WINDOWS_TASK_ENV = '1'; " +
            "\$supermuxHost = Get-Process -Id (Get-CimInstance Win32_Process -Filter ('ProcessId=' + \$PID)).ParentProcessId -ErrorAction SilentlyContinue; " +
            "if (\$supermuxHost) { \$null = \$supermuxHost.Handle }; " +
            "while (\$true) { $loadEnv; & ${powershellLiteral(spec.broker.toString())} 2>&1 | ForEach-Object { \$_.ToString() } | " +
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

    // ── the 1.0.0 keep-alive ─────────────────────────────────────────────────────────────────
    //
    // 1.0.0's keep-alive ran THE APP at login, under the very names the broker service uses now
    // (launchd `dev.supermux.host`, systemd `supermux-host.service`, XDG `supermux-host.desktop`,
    // task "Supermux Host"), with SUPERMUX_KEEP_ALIVE=1 and no managed marker. After an upgrade the
    // app may well be running AS that job. The invariant: never boot out, restart or end a job whose
    // process is the running app. Such an install only writes the new definition, which takes over
    // at the next login; until then the broker runs as the app's child (Result.Installed.nextLogin).

    /** Set in the env of everything the 1.0.0 keep-alive started (the app). */
    const val LEGACY_KEEP_ALIVE_ENV = "SUPERMUX_KEEP_ALIVE"

    /**
     * True iff THIS process (the app) is what our service name's job runs: launchd started us as
     * `dev.supermux.host` (`XPC_SERVICE_NAME`), we live in `supermux-host.service`'s cgroup, or the
     * 1.0.0 keep-alive's env says so (Windows: its task ran PowerShell, which ran the app).
     */
    fun appRunsAsService(env: OsEnv = SystemOsEnv): Boolean {
        if (env.getenv(LEGACY_KEEP_ALIVE_ENV) == "1") return true
        return when (env.os) {
            OsEnv.Os.MAC -> env.getenv("XPC_SERVICE_NAME") == LAUNCHD_LABEL
            OsEnv.Os.LINUX -> env.selfCgroup()?.lineSequence()?.any { it.trimEnd().endsWith("/$SYSTEMD_UNIT") } == true
            else -> false
        }
    }

    /** A 1.0.0 definition: it launches the app (SUPERMUX_KEEP_ALIVE), never the broker, and has no marker. */
    internal fun isLegacyDefinition(text: String): Boolean =
        MANAGED_MARKER !in text && LEGACY_KEEP_ALIVE_ENV in text && "supermux-broker" !in text

    /** 1.0.0's XDG entry set no env: it is the one named "supermux host" that runs no broker. */
    internal fun isLegacyXdgEntry(text: String): Boolean =
        MANAGED_MARKER !in text && "Name=supermux host" in text && "supermux-broker" !in text

    /** True iff a 1.0.0 keep-alive definition (it runs the app, not the broker) is installed under our names. */
    fun isLegacyInstalled(env: OsEnv = SystemOsEnv): Boolean = runCatching {
        fun file(p: Path) = if (Files.isRegularFile(p)) Files.readString(p) else null
        when (env.os) {
            OsEnv.Os.MAC -> file(env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist"))?.let(::isLegacyDefinition) == true
            OsEnv.Os.LINUX -> file(env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT"))?.let(::isLegacyDefinition) == true ||
                file(xdgAutostartPath(env))?.let(::isLegacyXdgEntry) == true
            OsEnv.Os.WINDOWS -> registeredTaskXml(env)?.let(::isLegacyDefinition) == true
            OsEnv.Os.OTHER -> false
        }
    }.getOrDefault(false)

    private fun registeredTaskXml(env: OsEnv): String? =
        env.runCapture(listOf("schtasks", "/Query", "/TN", WINDOWS_TASK_NAME, "/XML"))

    // ── whose definition is it? (#77) ────────────────────────────────────────────────────────
    //
    // Every copy of the app on a computer uses the names above: a dev build next to the installed
    // app, a reinstall with fresh app data. So each definition carries the id of the copy that wrote
    // it ([INSTALL_ENV], from [InstallIdentity]), and a copy replaces, restarts or removes only a
    // definition that carries its own: the app never stops or reconfigures a broker it did not start.

    /** The env key naming the copy of the app ([InstallIdentity]) a definition belongs to. */
    const val INSTALL_ENV = "MUX_DESKTOP_INSTALL"

    /**
     * The copy of the app asking ([InstallIdentity]). [ownsUnstamped]: a definition from before
     * install ids (marked, no [INSTALL_ENV]) is this copy's; its first id-aware launch decided so.
     * [persisted]: false when the id could not be saved or read back, so no definition may carry it.
     */
    data class Owner(val id: String, val ownsUnstamped: Boolean = false, val persisted: Boolean = true)

    /** Why an [Owner] that isn't [Owner.persisted] installs nothing. */
    const val UNSAVED_ID = "this copy of the app couldn't keep its install id, so it sets up no background service"

    /** What an [Owner] may do with a definition under our names. */
    enum class Ownership {
        /** It carries the owner's id: replace, restart or remove it. */
        OURS,

        /** 1.0.0's keep-alive, which runs the app, not a broker: replaced or removed as before. */
        LEGACY,

        /** Another copy's (another id, or none), or not the app's at all: never touched. */
        OTHER,
    }

    /**
     * Pure. [env] is the text that holds a definition's env (null: not on disk); [marked]: the app
     * wrote the definition ([MANAGED_MARKER]); [legacy]: it is 1.0.0's keep-alive.
     */
    internal fun ownership(env: String?, marked: Boolean, legacy: Boolean, me: Owner): Ownership = when {
        legacy -> Ownership.LEGACY
        env != null && INSTALL_ENV in env -> if (me.id in env) Ownership.OURS else Ownership.OTHER
        marked && me.ownsUnstamped -> Ownership.OURS
        else -> Ownership.OTHER
    }

    /** Pure, for [Takeover]: true iff a definition holding its own env is another copy's. */
    internal fun isAnotherCopys(definition: String, me: Owner): Boolean =
        ownership(definition, marked = true, legacy = isLegacyDefinition(definition), me = me) == Ownership.OTHER

    private enum class Kind { PLIST, UNIT, XDG, TASK }

    /**
     * One definition under our names. [env] is the text its env lives in ([envPath]): the definition
     * itself, the XDG env file, the Windows task loop's env file (null when missing). [marked]: the app
     * wrote it; a registered task's XML keeps no comment, so every task that isn't 1.0.0's counts.
     */
    private class Slot(
        val kind: Kind, val path: Path, val text: String, val envPath: Path, val env: String?, val marked: Boolean, val legacy: Boolean,
    ) {
        val unstamped: Boolean get() = marked && !legacy && (env == null || INSTALL_ENV !in env)

        fun ownership(me: Owner): Ownership = BrokerService.ownership(env, marked, legacy, me)
    }

    private fun readIfFile(p: Path): String? = if (Files.isRegularFile(p)) Files.readString(p) else null

    private fun launchdSlot(env: OsEnv): Slot? {
        val plist = env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist")
        val text = readIfFile(plist) ?: return null
        return Slot(Kind.PLIST, plist, text, plist, text, MANAGED_MARKER in text, isLegacyDefinition(text))
    }

    private fun unitSlot(env: OsEnv): Slot? {
        val unit = env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT")
        val text = readIfFile(unit) ?: return null
        return Slot(Kind.UNIT, unit, text, unit, text, MANAGED_MARKER in text, isLegacyDefinition(text))
    }

    private fun xdgSlot(env: OsEnv): Slot? {
        val entry = xdgAutostartPath(env)
        val text = readIfFile(entry) ?: return null
        return Slot(Kind.XDG, entry, text, xdgEnvPath(env), readIfFile(xdgEnvPath(env)), MANAGED_MARKER in text, isLegacyXdgEntry(text))
    }

    /** The registered task: its XML says whether it is 1.0.0's, its loop's env file whose it is. */
    private fun taskSlot(env: OsEnv): Slot? {
        if (!isInstalled(env)) return null
        val xml = registeredTaskXml(env).orEmpty()
        val envFile = windowsEnvPath(env)
        return Slot(Kind.TASK, WINDOWS_TASK_PATH, xml, envFile, readIfFile(envFile), marked = true, legacy = isLegacyDefinition(xml))
    }

    /** systemd --user needs both systemctl and a runtime dir; otherwise the XDG autostart stands in. */
    private fun usesSystemd(env: OsEnv): Boolean = env.hasCommand("systemctl") && !env.xdgRuntimeDir.isNullOrBlank()

    private fun slots(env: OsEnv): List<Slot> = when (env.os) {
        OsEnv.Os.MAC -> listOfNotNull(launchdSlot(env))
        OsEnv.Os.LINUX -> listOfNotNull(unitSlot(env), xdgSlot(env))
        OsEnv.Os.WINDOWS -> listOfNotNull(taskSlot(env))
        OsEnv.Os.OTHER -> emptyList()
    }

    /** The definition [install] would write over. */
    private fun installSlot(env: OsEnv): Slot? = when (env.os) {
        OsEnv.Os.MAC -> launchdSlot(env)
        OsEnv.Os.LINUX -> if (usesSystemd(env)) unitSlot(env) else xdgSlot(env)
        OsEnv.Os.WINDOWS -> taskSlot(env)
        OsEnv.Os.OTHER -> null
    }

    /**
     * True iff a definition from before install ids is installed and runs [stateDir]'s broker: what
     * an earlier version of this app wrote, if anything did (HostSupervisor's first id-aware launch).
     * The bin dir as each format writes it (XML-escaped, a PowerShell literal), then a separator.
     */
    fun unstampedRuns(env: OsEnv = SystemOsEnv, stateDir: Path): Boolean = runCatching {
        val bin = stateDir.resolve(HostBinaries.BIN_SUBDIR).toString()
        val names = listOf(bin, xml(bin), bin.replace("'", "''")).distinct().joinToString("|") { Regex.escape(it) }
        val runs = Regex("""(?:$names)[\\/;:'"<]""")
        slots(env).any { it.unstamped && (runs.containsMatchIn(it.text) || runs.containsMatchIn(it.env.orEmpty())) }
    }.getOrDefault(false)

    /**
     * Writes [me]'s id into the definitions from before install ids that [me] adopted, in place, so no
     * other copy can adopt them too. Nothing running is reloaded or restarted: launchd and the Windows
     * task loop read them at the next start, systemd is told to re-read its units. True iff every one
     * of them carries the id now.
     */
    fun stampInPlace(env: OsEnv = SystemOsEnv, me: Owner): Boolean = runCatching {
        var all = true
        for (slot in slots(env).filter { it.unstamped && it.ownership(me) == Ownership.OURS }) {
            val text = slot.env
            val stamped = when {
                text == null -> null
                slot.kind == Kind.PLIST -> Regex("""<key>EnvironmentVariables</key>\s*<dict>""").find(text)?.let {
                    text.replaceRange(it.range, it.value + "\n    <key>$INSTALL_ENV</key>\n    <string>${me.id}</string>")
                }
                slot.kind == Kind.UNIT -> Regex("""(?m)^\[Service][ \t]*$""").find(text)?.let {
                    text.replaceRange(it.range, it.value + "\nEnvironment=" + sdQuote("$INSTALL_ENV=${me.id}"))
                }
                slot.kind == Kind.XDG -> endLine(text, "\n") + "export $INSTALL_ENV=" + shQuote(me.id) + "\n"
                else -> endLine(text, "\r\n") + "\$env:$INSTALL_ENV = " + powershellLiteral(me.id) + "\r\n"
            }
            if (stamped == null) { all = false; continue }
            writePrivate(slot.envPath, stamped)
            if (slot.kind == Kind.UNIT && usesSystemd(env)) env.run(listOf("systemctl", "--user", "daemon-reload"))
        }
        all
    }.getOrDefault(false)

    private fun endLine(text: String, eol: String) = if (text.isEmpty() || text.endsWith("\n")) text else text + eol

    /**
     * Install (or re-install, which restarts) [me]'s service; the definition carries [me]'s id.
     * [alreadyStopped]: the caller just stopped our running service broker itself ([stop], Windows),
     * so don't stop it again. When the app runs as the job ([appRunsAsService]) nothing running is
     * touched: see [Result.Installed.nextLogin]. Another copy's definition under our name is left as
     * it is ([Result.NotOurs]) unless [replaceOther]: a takeover the user confirmed, which backed it
     * up and stopped it first.
     */
    fun install(spec: Spec, env: OsEnv = SystemOsEnv, me: Owner, alreadyStopped: Boolean = false, replaceOther: Boolean = false): Result {
        if (!me.persisted) return Result.Failed(UNSAVED_ID)
        // Unreadable is not "nothing there": write nothing over what can't be told apart.
        val (target, all) = try { installSlot(env) to slots(env) } catch (e: Exception) { return Result.Failed("couldn't read what is installed: ${e.message}") }
        // Not over another copy's definition, nor next to one (both would start a broker at login).
        val other = all.firstOrNull { it.ownership(me) == Ownership.OTHER && (it.marked || it.path == target?.path) }
            ?: target?.takeIf { it.ownership(me) == Ownership.OTHER }
        if (other != null && !replaceOther) return Result.NotOurs(other.path)
        val stamped = spec.copy(env = spec.env + (INSTALL_ENV to me.id))
        val asJob = appRunsAsService(env)
        return when (env.os) {
            OsEnv.Os.MAC -> installLaunchd(stamped, env, asJob)
            OsEnv.Os.LINUX -> installSystemd(stamped, env, asJob)
            OsEnv.Os.WINDOWS -> installWindowsTask(stamped, env, alreadyStopped, asJob)
            OsEnv.Os.OTHER -> Result.Unsupported
        }
    }

    /**
     * Remove [me]'s definition (or a 1.0.0 one); another copy's is left as it is ([Result.NotOurs]).
     * When the app runs as the job, the job itself is left to end with the app.
     */
    fun remove(env: OsEnv = SystemOsEnv, me: Owner): Result {
        val asJob = appRunsAsService(env)
        return when (env.os) {
            OsEnv.Os.MAC -> removeLaunchd(env, asJob, me)
            OsEnv.Os.LINUX -> removeSystemd(env, asJob, me)
            OsEnv.Os.WINDOWS -> removeWindowsTask(env, asJob, me)
            OsEnv.Os.OTHER -> Result.Unsupported
        }
    }

    /**
     * Restart [me]'s running service in place. True iff the OS accepted the command. Never the job
     * that is the app, never another copy's service.
     */
    fun restart(env: OsEnv = SystemOsEnv, me: Owner): Boolean = if (appRunsAsService(env) || !isOursInstalled(env, me)) false else when (env.os) {
        OsEnv.Os.MAC -> env.uid != null && env.run(listOf("launchctl", "kickstart", "-k", "gui/${env.uid}/$LAUNCHD_LABEL"))
        OsEnv.Os.LINUX -> env.run(listOf("systemctl", "--user", "restart", SYSTEMD_NAME))
        // Stop the loop and its broker, then start the task afresh; no elevation needed. /Run also
        // brings back a task whose loop died, and (StopExisting) replaces a lingering instance.
        // A broker that didn't stop must not get a second loop next to it.
        OsEnv.Os.WINDOWS -> stopWindowsTask(env) && env.run(listOf("schtasks", "/Run", "/TN", WINDOWS_TASK_NAME))
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
     * True iff a definition of [me]'s is installed: it carries [me]'s id. Every copy of the app (and
     * the retired Swift app, and 1.0.0's keep-alive) used the same names, so existence is not enough.
     */
    fun isOursInstalled(env: OsEnv = SystemOsEnv, me: Owner): Boolean = runCatching {
        slots(env).any { it.ownership(me) == Ownership.OURS }
    }.getOrDefault(false)

    /** True iff a definition another copy of the app wrote is installed under our names (#77). */
    fun anotherCopysInstalled(env: OsEnv = SystemOsEnv, me: Owner): Boolean = runCatching {
        slots(env).any { it.marked && it.ownership(me) == Ownership.OTHER }
    }.getOrDefault(false)

    /** True iff OUR "service" is the Linux XDG autostart fallback (no systemd unit of ours): it only starts at login. */
    fun isOursXdgAutostart(env: OsEnv = SystemOsEnv, me: Owner): Boolean = env.os == OsEnv.Os.LINUX && runCatching {
        xdgSlot(env)?.ownership(me) == Ownership.OURS && unitSlot(env)?.ownership(me) != Ownership.OURS
    }.getOrDefault(false)

    /** The XDG autostart fallback file (Linux without systemd --user). */
    fun xdgAutostartPath(env: OsEnv): Path = env.home.resolve(".config/autostart/$XDG_AUTOSTART_FILE")

    /** The XDG autostart's env file ([xdgEnvFile]). */
    fun xdgEnvPath(env: OsEnv): Path = env.home.resolve(XDG_ENV_FILE)

    /** The Windows task loop's env file ([windowsEnvFile]). */
    fun windowsEnvPath(env: OsEnv): Path = env.localAppData.resolve(WINDOWS_ENV_FILE)

    /**
     * Write [text] to [file] readable by its owner only (0600 where the file system has POSIX
     * permissions; elsewhere the parent's ACL, `%LOCALAPPDATA%`'s being the user's own). The file is
     * created private and then moved over the old one, so it is never readable by others, not even
     * for a moment, and a crash never leaves it half-written.
     */
    internal fun writePrivate(file: Path, text: String, charset: java.nio.charset.Charset = Charsets.UTF_8) {
        val dir = file.parent
        Files.createDirectories(dir)
        val posix = "posix" in file.fileSystem.supportedFileAttributeViews()
        val tmp = if (posix) {
            Files.createTempFile(dir, ".${file.fileName}", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createTempFile(dir, ".${file.fileName}", ".tmp")
        }
        try {
            Files.writeString(tmp, text, charset)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun installLaunchd(spec: Spec, env: OsEnv, asJob: Boolean): Result {
        val plist = env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist")
        return runCatching {
            Files.createDirectories(plist.parent)
            spec.log.parent?.let { Files.createDirectories(it) }
            writePrivate(plist, launchdPlist(spec))
            if (asJob) {
                // We ARE the loaded dev.supermux.host job: a bootout would SIGTERM this app. launchd
                // loads the new plist at the next login; `enable` only clears a disabled override.
                if (env.hasCommand("launchctl") && env.uid != null) {
                    env.runResult(listOf("launchctl", "enable", "gui/${env.uid}/$LAUNCHD_LABEL"))
                }
                return Result.Installed(plist, enabled = false, nextLogin = true)
            }
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

    private fun removeLaunchd(env: OsEnv, asJob: Boolean, me: Owner): Result {
        val plist = env.home.resolve("Library/LaunchAgents/$LAUNCHD_LABEL.plist")
        return runCatching {
            if (launchdSlot(env)?.ownership(me) == Ownership.OTHER) return Result.NotOurs(plist)
            // As the job, the file goes and the loaded job (us) ends with the app.
            if (!asJob && env.hasCommand("launchctl") && env.uid != null) {
                env.run(listOf("launchctl", "bootout", "gui/${env.uid}/$LAUNCHD_LABEL"))
                awaitLaunchdGone("gui/${env.uid}/$LAUNCHD_LABEL", env)
            }
            Result.Removed(if (Files.deleteIfExists(plist)) plist else null)
        }.getOrElse { Result.Failed("launchd remove failed: ${it.message}") }
    }

    private fun installSystemd(spec: Spec, env: OsEnv, asJob: Boolean): Result {
        if (!usesSystemd(env)) return installXdgAutostart(spec, env)
        val unit = env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT")
        return runCatching {
            Files.createDirectories(unit.parent)
            spec.log.parent?.let { Files.createDirectories(it) }
            writePrivate(unit, systemdUnit(spec))
            // 1.0.0's XDG entry launched the app at login: the unit replaces it.
            val xdg = xdgAutostartPath(env)
            if (Files.isRegularFile(xdg) && isLegacyXdgEntry(Files.readString(xdg))) Files.deleteIfExists(xdg)
            env.run(listOf("systemctl", "--user", "daemon-reload"))
            if (asJob) {
                // This app runs inside supermux-host.service: `restart` / `enable --now` would stop it.
                // Enabled, the new definition starts at the next login (and, Restart=always, as soon
                // as the app exits).
                val enabled = env.run(listOf("systemctl", "--user", "enable", SYSTEMD_NAME))
                env.uid?.let { env.run(listOf("loginctl", "enable-linger", it.toString())) }
                return Result.Installed(unit, enabled, nextLogin = true)
            }
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
        val envFile = xdgEnvPath(env)
        return runCatching {
            writePrivate(envFile, xdgEnvFile(spec))
            writePrivate(file, xdgAutostart(spec, envFile))
            Result.Installed(file, enabled = false) // only starts at next login
        }.getOrElse { Result.Failed("xdg autostart install failed: ${it.message}") }
    }

    private fun removeSystemd(env: OsEnv, asJob: Boolean, me: Owner): Result {
        val unit = env.home.resolve(".config/systemd/user/$SYSTEMD_UNIT")
        val autostart = env.home.resolve(".config/autostart/$XDG_AUTOSTART_FILE")
        return runCatching {
            // Either one may be another copy's: that one stays, with all that belongs to it.
            val unitOther = unitSlot(env)?.ownership(me) == Ownership.OTHER
            val autostartOther = xdgSlot(env)?.ownership(me) == Ownership.OTHER
            val systemd = usesSystemd(env) && !unitOther
            // As the job, never --now: that stops this app. The unit ends with the app.
            if (systemd) env.run(listOf("systemctl", "--user", "disable") + (if (asJob) emptyList() else listOf("--now")) + SYSTEMD_NAME)
            val a = !unitOther && Files.deleteIfExists(unit)
            if (!unitOther) Files.deleteIfExists(env.home.resolve(".config/systemd/user/default.target.wants/$SYSTEMD_UNIT"))
            val b = !autostartOther && Files.deleteIfExists(autostart)
            if (!autostartOther) Files.deleteIfExists(xdgEnvPath(env))
            if (systemd) env.run(listOf("systemctl", "--user", "daemon-reload"))
            when {
                a -> Result.Removed(unit)
                b -> Result.Removed(autostart)
                unitOther -> Result.NotOurs(unit)
                autostartOther -> Result.NotOurs(autostart)
                else -> Result.Removed(null)
            }
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
     * is a `supermux-broker.exe` whose parent is one of those loops. Never the app's own child
     * broker (background off: its parent is the app) and never `supermux-broker.exe shim|credential|…`.
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
        val brokers = procs.filter { p -> named(p, WINDOWS_BROKER_EXE) && p.parentPid in loops }.map { it.pid }
        return WindowsTaskTargets(loops, brokers)
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

    /** What [Result.Installed] / [Result.Removed] name for the Windows task: it has no file of ours. */
    val WINDOWS_TASK_PATH: Path = Path.of("Task Scheduler", WINDOWS_TASK_NAME)

    private fun installWindowsTask(spec: Spec, env: OsEnv, alreadyStopped: Boolean, asJob: Boolean): Result {
        val envFile = windowsEnvPath(env)
        val xml = windowsTaskXml(spec, envFile)
        val run = listOf("schtasks", "/Run", "/TN", WINDOWS_TASK_NAME)
        return runCatching {
            // The loop reads its env from here on every (re)start: write it before anything runs.
            // With a BOM: Windows PowerShell reads a BOM-less script in the ANSI code page.
            writePrivate(envFile, "\uFEFF" + windowsEnvFile(spec))
            // An older version kept a copy of the definition, env included, next to it.
            Files.deleteIfExists(env.localAppData.resolve(WINDOWS_TASK_XML))
            val wasInstalled = isInstalled(env)
            // Compare with what is REGISTERED: that is what runs.
            val registered = if (wasInstalled) registeredTaskXml(env) else null
            val unchanged = wasInstalled && sameTaskAction(registered, xml)
            // The 1.0.0 task (same name) runs the app: never "the previous broker" to fall back to.
            val wasOurs = wasInstalled && registered?.let(::isLegacyDefinition) != true
            if (asJob) {
                // This app runs under the task: ending or re-running it (StopExisting) could end the app.
                // Register the new definition only; it starts the broker from the next logon.
                if (unchanged) return Result.Installed(WINDOWS_TASK_PATH, enabled = false, nextLogin = true)
                return if (runElevated(env, registerTaskScript(xml, start = false))) {
                    Result.Installed(WINDOWS_TASK_PATH, enabled = false, nextLogin = true)
                } else {
                    Result.Failed("Windows Scheduled Task install failed (elevation declined or Task Scheduler error)")
                }
            }
            // A running instance must not keep the old loop (and broker) alive past re-registration,
            // and an unchanged one restarts with the (maybe new) broker binary.
            // If it won't stop, change nothing: a new loop next to the old broker would be a second one.
            if (wasInstalled && !alreadyStopped && !stopWindowsTask(env)) {
                return Result.Failed(WINDOWS_STOP_FAILED, previousStillRunning = true)
            }
            if (unchanged && wasInstalled) {
                // The registered task already says exactly this (an app update, an env change, a
                // restart): no UAC prompt, just start it again.
                if (env.run(run)) return Result.Installed(WINDOWS_TASK_PATH, true)
            }
            // ONE elevated invocation (one UAC prompt): register the task, then start it now.
            val ok = runElevated(env, registerTaskScript(xml, start = true))
            if (!ok) {
                // Declined UAC on a CHANGED definition: we stopped the old broker above, so start the
                // old definition again (no elevation) rather than leave nothing running.
                if (wasOurs && env.run(run)) {
                    return Result.Failed(WINDOWS_UPDATE_DECLINED, previousStillRunning = true)
                }
                Result.Failed("Windows Scheduled Task install failed (elevation declined or Task Scheduler error)")
            } else Result.Installed(WINDOWS_TASK_PATH, true)
        }.getOrElse { Result.Failed("Windows Scheduled Task install failed: ${it.message}") }
    }

    private val BASE64 = Regex("[A-Za-z0-9+/=]+")

    /**
     * The elevated script that registers [xml] as our task (and, with [start], runs it). The XML
     * travels base64-encoded INSIDE the elevated command line and is decoded in the elevated
     * process's memory: there is no file in between that the user's own (unelevated, maybe
     * malicious) processes could swap after the user said yes to the prompt.
     */
    internal fun registerTaskScript(xml: String, start: Boolean): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8))
        check(BASE64.matches(b64))
        val name = powershellLiteral(WINDOWS_TASK_NAME)
        // Task Scheduler's COM API through plain .NET: no cmdlet, so no module is auto-loaded. A
        // module (ScheduledTasks) would be looked up on PSModulePath, whose first entry is the
        // user's own Documents folder: a module planted there would run elevated.
        // RegisterTask(path, xml, TASK_CREATE_OR_UPDATE = 6, user, password, TASK_LOGON_INTERACTIVE_TOKEN = 3)
        val run = if (start) " \$null = \$task.Run(\$null);" else ""
        return "$ELEVATED_PRELUDE try { \$xml = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$b64')); " +
            "\$svc = [Activator]::CreateInstance([Type]::GetTypeFromProgID('Schedule.Service')); \$svc.Connect(); " +
            "\$task = \$svc.GetFolder('\\').RegisterTask($name, \$xml, 6, \$null, \$null, 3);$run } catch { exit 1 }; exit 0"
    }

    /**
     * First in every elevated script: stop on errors, and let module auto-loading see the system's
     * modules only (never the user-writable Documents\WindowsPowerShell\Modules).
     */
    private const val SYSTEM_MODULES_ONLY = "\$env:PSModulePath = \$PSHOME + '\\Modules';"
    private const val ELEVATED_PRELUDE = "\$ErrorActionPreference = 'Stop'; $SYSTEM_MODULES_ONLY"

    /** schtasks by its System32 path: a bare name is looked up on the (user-influenced) PATH. */
    private const val SCHTASKS = "(\$env:SystemRoot + '\\System32\\schtasks.exe')"

    private fun removeWindowsTask(env: OsEnv, asJob: Boolean, me: Owner): Result {
        val taskXml = env.localAppData.resolve(WINDOWS_TASK_XML)
        return runCatching {
            val task = taskSlot(env)
            // Another copy's task: no UAC prompt to end and delete it, and its env file stays.
            if (task?.ownership(me) == Ownership.OTHER) return Result.NotOurs(WINDOWS_TASK_PATH)
            Files.deleteIfExists(taskXml)
            if (task == null) {
                Files.deleteIfExists(windowsEnvPath(env))
                return Result.Removed(null)
            }
            // /End first: the task's PowerShell loop would otherwise respawn the broker ~5 s after taskkill.
            // Not when the app runs under the task: /End would end the app; deleting it leaves us running.
            val delete = listOf("/Delete", "/TN", WINDOWS_TASK_NAME, "/F")
            val ended = runElevated(env, schtasksScript(
                if (asJob) listOf(delete) else listOf(listOf("/End", "/TN", WINDOWS_TASK_NAME), delete),
            ))
            // Declined UAC or a failed /Delete leaves the task registered; its loop would respawn the broker.
            if (!ended) return Result.Failed("Windows Scheduled Task remove failed: the elevated /End + /Delete did not succeed")
            // /End only terminates the task's own process (the headless conhost): stop its loop too,
            // then the broker, and wait until the broker is really gone. Starting the same .exe while
            // the killed one is still being torn down fails with a sharing violation, and a broker
            // still running must not get a child broker next to it.
            if (!stopWindowsTask(env)) {
                return Result.Failed("Windows Scheduled Task removed, but its broker is still running")
            }
            Files.deleteIfExists(windowsEnvPath(env))
            Result.Removed(WINDOWS_TASK_PATH)
        }.getOrElse { Result.Failed("Windows Scheduled Task remove failed: ${it.message}") }
    }

    /** Every schtasks call in one script; it exits with the first non-zero exit code. */
    private fun schtasksScript(calls: List<List<String>>): String = "$SYSTEM_MODULES_ONLY " + calls.joinToString("; ") { args ->
        val call = "& $SCHTASKS ${args.joinToString(" ") { powershellLiteral(it) }}"
        // /End fails when the task is not running, which is fine: keep going to the next call.
        if (args.firstOrNull() == "/End") call else "$call; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }"
    }

    /** Windows PowerShell by its System32 path, never whatever `powershell.exe` the search path finds first. */
    val WINDOWS_POWERSHELL: String = windowsPowerShell()

    /**
     * Windows 11 denies even current-user Task Scheduler registration to a non-elevated process.
     * Elevate once (one UAC prompt) and run [inner] (PowerShell) in that elevated process; false if
     * it exits non-zero. The registered task itself stays InteractiveToken/LeastPrivilege.
     */
    private fun runElevated(env: OsEnv, inner: String): Boolean {
        val innerArgs = listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", windowsArgument(inner))
            .joinToString(" ")
        // A declined UAC prompt makes Start-Process fail with a NON-terminating error: $process is
        // then null and `exit $null.ExitCode` exits 0, reporting a success that never happened
        // (the task looked installed and no broker ran). Stop on it and exit 1223 (ERROR_CANCELLED).
        val script =
            "try { \$process = Start-Process -FilePath (\$env:SystemRoot + '\\System32\\WindowsPowerShell\\v1.0\\powershell.exe') " +
                "-Verb RunAs -Wait -PassThru -ErrorAction Stop " +
                "-ArgumentList ${powershellLiteral(innerArgs)} } catch { exit $UAC_DECLINED_EXIT }; " +
                "if (-not \$process) { exit $UAC_DECLINED_EXIT }; exit \$process.ExitCode"
        return env.runResult(
            listOf(
                WINDOWS_POWERSHELL, "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-Command", script,
            ),
            OsEnv.PROMPT_TIMEOUT_MS,
        ).exit == 0
    }
}
