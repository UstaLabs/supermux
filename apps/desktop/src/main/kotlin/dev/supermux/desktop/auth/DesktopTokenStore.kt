package dev.supermux.desktop.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

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
        // Create the temp file with owner-only perms up-front so the token is
        // never on disk with umask (potentially world-readable) permissions,
        // then atomically rename over the destination (also crash-safe).
        val tmp = if (path.parent.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.createTempFile(
                path.parent, "auth", ".tmp",
                PosixFilePermissions.asFileAttribute(OWNER_ONLY),
            )
        } else {
            Files.createTempFile(path.parent, "auth", ".tmp")
        }
        try {
            Files.writeString(tmp, json.encodeToString(DesktopTokenStoreBlob.serializer(), blob))
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) } // only present if the move failed
        }
        runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) } // no-op on Windows
    }

    fun save(token: String) = write(read().copy(token = token))
    fun load(): String? = read().token?.takeIf { it.isNotBlank() }
    fun saveBaseUrl(url: String) = write(read().copy(baseUrl = url))
    fun loadBaseUrl(): String? = read().baseUrl?.takeIf { it.isNotBlank() }

    /** Deletes the credential file. Returns false if a live credential may still be on disk. */
    fun clear(): Boolean =
        runCatching { Files.deleteIfExists(path) }.isSuccess && !Files.exists(path)

    companion object {
        private val OWNER_ONLY =
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

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
