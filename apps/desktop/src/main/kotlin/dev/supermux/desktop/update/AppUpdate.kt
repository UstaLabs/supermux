package dev.supermux.desktop.update

import dev.supermux.update.ClientPlatform
import dev.supermux.update.ClientUpdateChecker
import dev.supermux.update.ClientUpdateStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
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

    /**
     * Download the installer and open it with the system handler (Software Install /
     * MSI wizard / DiskImageMounter). Returns an error string, or null on success.
     */
    suspend fun downloadAndOpen(
        http: HttpClient,
        downloadUrl: String,
    ): String? = withContext(Dispatchers.IO) {
        val bytes = try {
            ClientUpdateChecker(http).download(downloadUrl)
        } catch (e: Throwable) {
            return@withContext e.message ?: "Download failed"
        }
        val ext = installerExtension()
        val file = try {
            val dir = Files.createTempDirectory("supermux-update").toFile()
            File(dir, "supermux-update.$ext").also { it.writeBytes(bytes) }
        } catch (e: Throwable) {
            return@withContext e.message ?: "Could not write installer"
        }
        try {
            openFile(file)
            null
        } catch (e: Throwable) {
            e.message ?: "Could not open installer"
        }
    }

    private fun openFile(file: File) {
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
