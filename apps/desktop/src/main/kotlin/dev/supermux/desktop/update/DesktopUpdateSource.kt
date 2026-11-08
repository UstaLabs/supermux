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
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
            return false
        }
        openCommand(file.absolutePath).start(ProcessBuilder.Redirect.INHERIT)
        return false
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
}
