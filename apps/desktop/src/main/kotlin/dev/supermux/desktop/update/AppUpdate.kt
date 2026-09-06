package dev.supermux.desktop.update

import dev.supermux.update.ClientPlatform
import dev.supermux.update.ClientUpdateChecker
import dev.supermux.update.ClientUpdateStatus
import io.ktor.client.HttpClient
import java.awt.Desktop
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences

/**
 * Desktop client self-update against supermux.dev/versions.json (GitHub fallback).
 * Downloads the platform installer (.deb / .msi / .dmg) and opens it with the OS.
 */
object AppUpdate {
    private val prefs: Preferences = Preferences.userRoot().node("dev/supermux/desktop/app_update")
    private const val KEY_DISMISSED = "dismissed_latest"

    fun platform(): ClientPlatform {
        val os = System.getProperty("os.name")?.lowercase(Locale.US).orEmpty()
        return when {
            os.contains("win") -> ClientPlatform.DESKTOP_WINDOWS
            os.contains("mac") || os.contains("darwin") -> ClientPlatform.DESKTOP_MACOS
            else -> ClientPlatform.DESKTOP_LINUX
        }
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
    fun openInstaller(file: File) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
            return
        }
        val os = System.getProperty("os.name")?.lowercase(Locale.US).orEmpty()
        val cmd = when {
            os.contains("win") -> arrayOf("cmd", "/c", "start", "", file.absolutePath)
            os.contains("mac") || os.contains("darwin") -> arrayOf("open", file.absolutePath)
            else -> arrayOf("xdg-open", file.absolutePath)
        }
        ProcessBuilder(*cmd).inheritIO().start()
    }

    fun openUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI(url))
            } else {
                val os = System.getProperty("os.name")?.lowercase(Locale.US).orEmpty()
                val cmd = when {
                    os.contains("win") -> arrayOf("cmd", "/c", "start", "", url)
                    os.contains("mac") || os.contains("darwin") -> arrayOf("open", url)
                    else -> arrayOf("xdg-open", url)
                }
                ProcessBuilder(*cmd).start()
            }
        }
    }

    fun isDismissed(latestVersion: String): Boolean =
        prefs.get(KEY_DISMISSED, null) == latestVersion

    fun dismiss(latestVersion: String) {
        prefs.put(KEY_DISMISSED, latestVersion)
    }
}
