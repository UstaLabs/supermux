package dev.supermux.desktop.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

@Serializable
private data class DesktopTokenStoreBlob(val token: String? = null, val baseUrl: String? = null)

/**
 * Persistent paired-broker credentials for the desktop app.
 * JSON file under the platform config dir, owner-only perms on POSIX.
 * (The :shared jvm SecureTokenStore actual is deliberately in-memory; this
 * class is the desktop app's real store.)
 */
class DesktopTokenStore(val path: Path = defaultPath()) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun read(): DesktopTokenStoreBlob =
        runCatching { json.decodeFromString<DesktopTokenStoreBlob>(Files.readString(path)) }
            .getOrDefault(DesktopTokenStoreBlob())

    private fun write(blob: DesktopTokenStoreBlob) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(DesktopTokenStoreBlob.serializer(), blob))
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } // no-op on Windows
    }

    fun save(token: String) = write(read().copy(token = token))
    fun load(): String? = read().token?.takeIf { it.isNotBlank() }
    fun saveBaseUrl(url: String) = write(read().copy(baseUrl = url))
    fun loadBaseUrl(): String? = read().baseUrl?.takeIf { it.isNotBlank() }
    fun clear() { runCatching { Files.deleteIfExists(path) } }

    companion object {
        fun defaultPath(): Path {
            val os = System.getProperty("os.name").lowercase()
            val base = when {
                os.contains("win") -> Path.of(System.getenv("APPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Roaming"))
                else -> Path.of(System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"))
            }
            return base.resolve("supermux-desktop").resolve("auth.json")
        }
    }
}
