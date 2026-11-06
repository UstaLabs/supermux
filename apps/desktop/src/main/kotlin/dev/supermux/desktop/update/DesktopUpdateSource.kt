package dev.supermux.desktop.update

import dev.supermux.desktop.platform.DesktopOs
import dev.supermux.desktop.platform.OsCommand
import dev.supermux.desktop.platform.openCommand
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

    /** Hand a downloaded installer to the OS. Public since G1: [DesktopAppUpdater] splits the old
     *  download-and-open into the two halves the shared `AppUpdater` seam exposes. */
    /**
     * Windows MSI: run `msiexec /i` ourselves and quit the app, which the caller must then do.
     * Windows Installer can only replace the app's files (an in-place MajorUpgrade) once this
     * process has let go of them; opening the .msi with the shell would leave the app running and
     * the installer asking to close it or to reboot. Null elsewhere: the OS opens the installer.
     * msiexec is named by its full System32 path ([systemRoot], `%SystemRoot%`): a bare name is
     * looked up in the app's directory and the PATH first.
     */
    internal fun windowsInstallerArgv(os: DesktopOs, file: File, systemRoot: String? = System.getenv("SystemRoot")): List<String>? =
        if (os == DesktopOs.WINDOWS && file.name.endsWith(".msi", ignoreCase = true)) {
            val root = systemRoot?.takeIf { it.isNotBlank() }?.trimEnd('\\', '/') ?: "C:\\Windows"
            listOf("$root\\System32\\msiexec.exe", "/i", file.absolutePath)
        } else {
            null
        }

    /** Hand a downloaded installer to the OS. True when the app must now quit (Windows MSI). */
    fun openInstaller(file: File): Boolean {
        windowsInstallerArgv(DesktopOs.current, file)?.let { argv ->
            OsCommand(argv).start()
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
