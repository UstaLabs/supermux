package dev.supermux.desktop.update

import dev.supermux.desktop.platform.DesktopOs
import dev.supermux.desktop.platform.OsCommand
import dev.supermux.desktop.platform.openCommand
import dev.supermux.desktop.platform.windowsPowerShell
import dev.supermux.fs.hostParent
import dev.supermux.update.ClientPlatform
import dev.supermux.update.ClientUpdateChecker
import dev.supermux.update.ClientUpdateStatus
import io.ktor.client.HttpClient
import java.awt.Desktop
import java.io.File
import java.util.prefs.Preferences

/**
 * The OS-bound half of desktop's self-update: which platform this is, where the release feed lives,
 * and how to hand a downloaded installer (.deb / .msi / .dmg) or a URL to the desktop environment.
 *
 * Renamed from `AppUpdate` in cluster G5 — `update/AppUpdate.kt` is the SHARED screen + banner now,
 * and a basename may exist in only one app module.
 */
object DesktopUpdateSource {
    private val prefs: Preferences = Preferences.userRoot().node("dev/supermux/desktop/app_update")
    private const val KEY_DISMISSED = "dismissed_latest"
    private const val KEY_BACKGROUND = "background_updates"
    private const val KEY_INSTALLING = "installing_version"

    internal fun platform(os: DesktopOs = DesktopOs.current): ClientPlatform = when (os) {
        DesktopOs.WINDOWS -> ClientPlatform.DESKTOP_WINDOWS
        DesktopOs.MACOS -> ClientPlatform.DESKTOP_MACOS
        DesktopOs.LINUX -> ClientPlatform.DESKTOP_LINUX
    }

    fun installerExtension(platform: ClientPlatform = platform()): String = when (platform) {
        ClientPlatform.DESKTOP_WINDOWS -> "msi"
        ClientPlatform.DESKTOP_MACOS -> "dmg"
        else -> "deb"
    }

    suspend fun check(http: HttpClient, currentVersion: String = DESKTOP_APP_VERSION): ClientUpdateStatus {
        return ClientUpdateChecker(http).check(
            platform = platform(),
            currentVersion = currentVersion,
        )
    }

    private const val UPDATE_MSIEXEC = "SUPERMUX_UPDATE_MSIEXEC"
    private const val UPDATE_MSIEXEC_ARGS = "SUPERMUX_UPDATE_MSIEXEC_ARGS"
    private const val UPDATE_APP = "SUPERMUX_UPDATE_APP"
    private const val UPDATE_APP_PID = "SUPERMUX_UPDATE_APP_PID"

    /**
     * The quiet Windows update's helper (#113): wait for this app to exit (Windows Installer replaces its files only once
     * they are free), run msiexec with a progress bar only and wait for it, then start the app again, whatever the
     * install answered. Its paths come in the environment, never as code; no double quote anywhere, since Java can't
     * escape one inside a Windows argument.
     */
    internal const val WINDOWS_UPDATE_SCRIPT =
        "try { Wait-Process -Id ([int]\$env:$UPDATE_APP_PID) -Timeout 120 -ErrorAction SilentlyContinue } catch {}; " +
            "Start-Process -FilePath \$env:$UPDATE_MSIEXEC -ArgumentList \$env:$UPDATE_MSIEXEC_ARGS -Wait; " +
            "if (\$env:$UPDATE_APP -and (Test-Path -LiteralPath \$env:$UPDATE_APP)) { Start-Process -FilePath \$env:$UPDATE_APP }"

    /**
     * msiexec's own command line for [msi]: `/passive` (a progress bar, no wizard, no folder or files-in-use question),
     * no restart, into [installDir] (the folder the running app was installed in, so the upgrade stays there), with a
     * verbose log next to the MSI. msiexec parses this line itself; a Windows path can hold no double quote.
     */
    internal fun msiexecArguments(msi: File, installDir: String?, log: File): String = buildString {
        append("/i \"${msi.absolutePath}\" /passive /norestart")
        installDir?.trimEnd('\\', '/')?.takeIf { it.isNotEmpty() }?.let { append(" INSTALLDIR=\"$it\"") }
        append(" /l*v \"${log.absolutePath}\"")
    }

    /**
     * Windows MSI: the hidden helper that updates the app in place and starts it again ([WINDOWS_UPDATE_SCRIPT] in
     * Windows PowerShell under a headless conhost, so no window opens). [app] is the running app's launcher
     * (jpackage's `jpackage.app-path`), [appPid] this process. Tools are named by their System32 paths ([systemRoot],
     * `%SystemRoot%`): a bare name is looked up in the app's folder and the PATH first. Null elsewhere: the OS opens
     * the installer.
     */
    internal fun windowsUpdateCommand(
        os: DesktopOs,
        msi: File,
        app: String?,
        appPid: Long,
        systemRoot: String? = System.getenv("SystemRoot"),
    ): OsCommand? {
        if (os != DesktopOs.WINDOWS || !msi.name.endsWith(".msi", ignoreCase = true)) return null
        val root = systemRoot?.takeIf { it.isNotBlank() }?.trimEnd('\\', '/') ?: "C:\\Windows"
        val log = File(msi.absoluteFile.parentFile, msi.nameWithoutExtension + ".log")
        val installDir = app?.takeIf { it.isNotBlank() }?.let(::hostParent)
        return OsCommand(
            listOf(
                "$root\\System32\\conhost.exe", "--headless", windowsPowerShell(root),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_UPDATE_SCRIPT,
            ),
            mapOf(
                UPDATE_MSIEXEC to "$root\\System32\\msiexec.exe",
                UPDATE_MSIEXEC_ARGS to msiexecArguments(msi, installDir, log),
                UPDATE_APP to (app ?: ""),
                UPDATE_APP_PID to appPid.toString(),
            ),
        )
    }

    /**
     * Hand a downloaded installer to the OS. True when the app must now quit: a Windows MSI, which the helper installs
     * once this process is gone (its broker was stopped before, see DesktopAppUpdater.install). Public since G1:
     * [DesktopAppUpdater] splits the old download-and-open into the two halves the shared `AppUpdater` seam exposes.
     */
    fun openInstaller(file: File): Boolean {
        windowsUpdateCommand(DesktopOs.current, file, System.getProperty("jpackage.app-path"), ProcessHandle.current().pid())?.let { cmd ->
            cmd.start()
            return true
        }
        val app = System.getProperty("jpackage.app-path")
        if (file.name.endsWith(".deb") && linuxRestarts(DesktopOs.current, app, File(PKEXEC).canExecute())) {
            return installLinuxDeb(file, app, ProcessHandle.current().pid(), run = { dev.supermux.desktop.host.SystemOsEnv.runResult(it, PASSWORD_PROMPT_MS).exit }, start = { it.start() })
        }
        val bundle = macBundle(app)
        if (file.name.endsWith(".dmg") && bundle != null && macRestarts(DesktopOs.current, app) { it.canWrite() }) {
            val mount = java.nio.file.Files.createTempDirectory("supermux-update-mount").toFile()
            return installMacDmg(
                file, bundle, ProcessHandle.current().pid(), mount,
                run = { dev.supermux.desktop.host.SystemOsEnv.runResult(it, COPY_MS).exit },
                findApp = { m -> m.listFiles()?.firstOrNull { it.name.endsWith(".app") } },
                start = { it.start() },
            )
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
            return false
        }
        openCommand(file.absolutePath).start(ProcessBuilder.Redirect.INHERIT)
        return false
    }

    // ── Linux (#113): "Restart to update" ──

    private const val UPDATE_RELAUNCH_APP = "SUPERMUX_UPDATE_APP"
    private const val UPDATE_RELAUNCH_PID = "SUPERMUX_UPDATE_APP_PID"
    private const val PKEXEC = "/usr/bin/pkexec"

    /** How long the password prompt may stay open before the update gives up. */
    private const val PASSWORD_PROMPT_MS = 10 * 60_000L

    /** The .deb installed with one password prompt: polkit's pkexec runs dpkg as root. Absolute paths, never the PATH. */
    internal fun linuxInstallArgv(deb: File): List<String> = listOf(PKEXEC, "/usr/bin/dpkg", "-i", deb.absolutePath)

    /** Linux restarts to update when the app is the packaged one ([app], jpackage's launcher) and pkexec is there. */
    internal fun linuxRestarts(os: DesktopOs, app: String?, pkexecPresent: Boolean): Boolean =
        os == DesktopOs.LINUX && !app.isNullOrBlank() && pkexecPresent

    /**
     * The relaunch helper: waits (up to ~2 min) for the app's process to end, then starts the app again, detached. Its
     * paths come in the environment, never as code.
     */
    internal const val LINUX_RELAUNCH_SCRIPT =
        "i=0; while kill -0 \"\$$UPDATE_RELAUNCH_PID\" 2>/dev/null && [ \$i -lt 600 ]; do sleep 0.2; i=\$((i+1)); done; " +
            "[ -x \"\$$UPDATE_RELAUNCH_APP\" ] && setsid \"\$$UPDATE_RELAUNCH_APP\" >/dev/null 2>&1 < /dev/null &"

    internal fun linuxRelaunchCommand(app: String, appPid: Long): OsCommand = OsCommand(
        listOf("/bin/sh", "-c", LINUX_RELAUNCH_SCRIPT),
        mapOf(UPDATE_RELAUNCH_APP to app, UPDATE_RELAUNCH_PID to appPid.toString()),
    )

    /**
     * Install [deb] while the app still runs (Linux replaces a running program's files), and only when that worked
     * start the relaunch helper: true, the app must now quit. A cancelled password prompt (pkexec's 126) or a failed
     * dpkg throws, with the app left running.
     */
    internal fun installLinuxDeb(deb: File, app: String, appPid: Long, run: (List<String>) -> Int, start: (OsCommand) -> Any): Boolean {
        when (val exit = run(linuxInstallArgv(deb))) {
            0 -> Unit
            126, 127 -> throw IllegalStateException("The update was cancelled, so supermux wasn't updated.")
            else -> throw IllegalStateException("The update couldn't be installed (dpkg exit $exit).")
        }
        start(linuxRelaunchCommand(app, appPid))
        return true
    }

    // ── macOS (#113): "Restart to update" ──

    private const val SWAP_BUNDLE = "SUPERMUX_UPDATE_BUNDLE"
    private const val SWAP_NEW = "SUPERMUX_UPDATE_NEW"
    private const val SWAP_OPEN = "SUPERMUX_UPDATE_OPEN"

    /** How long mounting and copying the new app may take. */
    private const val COPY_MS = 5 * 60_000L

    /** The installed bundle (`…/Supermux Desktop.app`) jpackage's launcher [app] runs from; null outside an .app (a dev run). */
    internal fun macBundle(app: String?): File? {
        var f = app?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        while (true) {
            if (f.name.endsWith(".app")) return f
            f = f.parentFile ?: return null
        }
    }

    /** macOS restarts to update when the installed bundle's folder is [writable] without a password. */
    internal fun macRestarts(os: DesktopOs, app: String?, writable: (File) -> Boolean): Boolean {
        if (os != DesktopOs.MACOS) return false
        val bundle = macBundle(app) ?: return false
        return bundle.parentFile?.let(writable) == true
    }

    /** Where the new bundle waits, next to the installed one (same volume, so the swap is a rename). */
    private fun macStaged(bundle: File) = File(bundle.parentFile, ".${bundle.name}.update")

    /**
     * The swap helper: waits (up to ~2 min) for the app to exit, moves the installed bundle aside, the new one into its
     * place, removes the old one and opens the new one. If the new one can't take its place the old one goes back.
     * Its paths come in the environment, never as code.
     */
    internal const val MAC_SWAP_SCRIPT =
        "i=0; while kill -0 \"\$$UPDATE_RELAUNCH_PID\" 2>/dev/null && [ \$i -lt 600 ]; do sleep 0.2; i=\$((i+1)); done; " +
            "old=\"\$$SWAP_BUNDLE.old\"; rm -rf \"\$old\"; " +
            "if mv \"\$$SWAP_BUNDLE\" \"\$old\"; then " +
            "if mv \"\$$SWAP_NEW\" \"\$$SWAP_BUNDLE\"; then rm -rf \"\$old\"; else mv \"\$old\" \"\$$SWAP_BUNDLE\"; fi; fi; " +
            "\"\$$SWAP_OPEN\" \"\$$SWAP_BUNDLE\" >/dev/null 2>&1 < /dev/null &"

    internal fun macSwapCommand(bundle: File, appPid: Long, opener: String = "/usr/bin/open"): OsCommand = OsCommand(
        listOf("/bin/sh", "-c", MAC_SWAP_SCRIPT),
        mapOf(
            SWAP_BUNDLE to bundle.absolutePath, SWAP_NEW to macStaged(bundle).absolutePath,
            SWAP_OPEN to opener, UPDATE_RELAUNCH_PID to appPid.toString(),
        ),
    )

    /**
     * While the app still runs: mount [dmg] at [mount], copy the app in it ([findApp]) next to [bundle], unmount, then
     * start the swap helper: true, the app must now quit. Anything that fails throws, with the app left running and
     * no copy left behind.
     */
    internal fun installMacDmg(
        dmg: File, bundle: File, appPid: Long, mount: File,
        run: (List<String>) -> Int, findApp: (File) -> File?, start: (OsCommand) -> Any,
    ): Boolean {
        val staged = macStaged(bundle)
        run(listOf("/usr/bin/hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.absolutePath, dmg.absolutePath))
            .takeIf { it != 0 }?.let { throw IllegalStateException("The update couldn't be opened (hdiutil exit $it).") }
        try {
            val newApp = findApp(mount) ?: throw IllegalStateException("The update has no app in it.")
            staged.deleteRecursively()
            run(listOf("/usr/bin/ditto", newApp.absolutePath, staged.absolutePath)).takeIf { it != 0 }?.let {
                staged.deleteRecursively()
                throw IllegalStateException("The update couldn't be copied (ditto exit $it).")
            }
        } finally {
            run(listOf("/usr/bin/hdiutil", "detach", mount.absolutePath, "-quiet"))
        }
        start(macSwapCommand(bundle, appPid))
        return true
    }

    /** Whether this app's installer restarts it by itself: the Windows MSI, or a packaged Linux app with pkexec. */
    fun restartsToUpdate(): Boolean = when (installerExtension()) {
        "msi" -> true
        "deb" -> linuxRestarts(DesktopOs.current, System.getProperty("jpackage.app-path"), File(PKEXEC).canExecute())
        "dmg" -> macRestarts(DesktopOs.current, System.getProperty("jpackage.app-path")) { it.canWrite() }
        else -> false
    }

    /** AWT's browser first; otherwise [openCommand], which never hands the URL to `cmd` (an `&` would split it). */
    fun openUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI(url))
            } else {
                openCommand(url).start()
            }
        }
    }

    fun isDismissed(latestVersion: String): Boolean =
        prefs.get(KEY_DISMISSED, null) == latestVersion

    fun dismiss(latestVersion: String) {
        prefs.put(KEY_DISMISSED, latestVersion)
    }

    /** "Download updates in the background" (#113): on unless the user turned it off. */
    fun backgroundUpdates(): Boolean = prefs.getBoolean(KEY_BACKGROUND, true)

    fun setBackgroundUpdates(on: Boolean) {
        prefs.putBoolean(KEY_BACKGROUND, on)
    }

    /** The version the app's own install was started for (#113); null when none is pending. */
    fun installing(): String? = prefs.get(KEY_INSTALLING, null)

    fun setInstalling(version: String?) {
        if (version == null) prefs.remove(KEY_INSTALLING) else prefs.put(KEY_INSTALLING, version)
    }
}
