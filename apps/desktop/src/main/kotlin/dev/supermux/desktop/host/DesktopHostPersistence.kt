package dev.supermux.desktop.host

import dev.supermux.host.HostMetaCodec
import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Desktop [HostPersistence] (spec §3.2) — the multi-host fleet stored SPLIT, mirroring
 * [dev.supermux.android.host.AndroidHostPersistence] with plain files instead of DataStore/Keystore:
 *
 *  - **Metadata** (recordId, hostId, displayName, direct/relay URLs, platform, version, lastSeenAt)
 *    → a single versioned JSON file ([metaPath], default `~/.config/supermux-desktop/hosts.json`).
 *    The tokenless split is enforced STRUCTURALLY by [HostMetaCodec] — a token can't be serialized
 *    into it.
 *  - **Tokens** → a separate owner-only JSON file ([tokenPath], default
 *    `host-tokens.json`), one entry per recordId, written with the SAME atomic-move + 0600-perms
 *    pattern as [dev.supermux.desktop.auth.DesktopTokenStore]. Deliberately NOT one blob with the
 *    metadata: a corrupt/lost token file costs a re-pair, never the host list/identity.
 *
 * The [dev.supermux.host.PairedHostStore] list logic sits on top of this and is platform-agnostic —
 * this class only moves bytes.
 */
class DesktopHostPersistence(
    val metaPath: Path = DesktopHostStores.defaultDir().resolve(META_FILE),
    val tokenPath: Path = DesktopHostStores.defaultDir().resolve(TOKEN_FILE),
) : HostPersistence {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val tokens = HostTokenStore(tokenPath)

    /** Versioned envelope around [HostMetaCodec]'s tokenless-metadata JSON, so the on-disk format
     *  can evolve (v1: `meta` holds the exact HostMetaCodec array string). */
    @Serializable
    private data class RegistryFile(val version: Int = 1, val meta: String = "[]")

    override fun loadAll(): List<PairedHost> {
        val metaJson = runCatching {
            json.decodeFromString(RegistryFile.serializer(), Files.readString(metaPath)).meta
        }.getOrNull()
        return HostMetaCodec.decode(metaJson) { tokens.get(it) }
    }

    override fun saveAll(hosts: List<PairedHost>) {
        // Tokens first, metadata second: metadata is the source of truth on load, so a crash between
        // the two writes leaves at worst an orphan token (pruned next save) — never a metadata host
        // with no token.
        val liveIds = hosts.map { it.recordId }.toSet()
        (tokens.recordIds() - liveIds).forEach { tokens.remove(it) } // best-effort local revoke on forget
        hosts.forEach { tokens.put(it.recordId, it.token) }
        val envelope = RegistryFile(version = 1, meta = HostMetaCodec.encodeMeta(hosts))
        runCatching {
            writeOwnerOnly(metaPath, json.encodeToString(RegistryFile.serializer(), envelope))
        }
    }

    companion object {
        const val META_FILE = "hosts.json"
        const val TOKEN_FILE = "host-tokens.json"
    }
}

/**
 * One-token-per-host store keyed by recordId, persisted as a single owner-only JSON map file using
 * the SAME atomic-move + 0600-perms write [dev.supermux.desktop.auth.DesktopTokenStore] uses (the
 * desktop app's established on-disk-credential pattern). Kept in a SEPARATE file from the metadata
 * so a corrupt token file costs a re-pair, not the host list.
 */
class HostTokenStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun read(): MutableMap<String, String> =
        runCatching {
            json.decodeFromString<Map<String, String>>(Files.readString(path)).toMutableMap()
        }.getOrDefault(mutableMapOf())

    private fun write(map: Map<String, String>) {
        writeOwnerOnly(path, json.encodeToString(map))
    }

    fun get(recordId: String): String? = read()[recordId]?.takeIf { it.isNotBlank() }
    fun put(recordId: String, token: String) = write(read().apply { this[recordId] = token })
    fun remove(recordId: String) = write(read().apply { remove(recordId) })
    fun recordIds(): Set<String> = read().keys
}

private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

/** Atomic, owner-only-on-POSIX file write — the exact crash-safe pattern
 *  [dev.supermux.desktop.auth.DesktopTokenStore] uses (temp file with 0600 up-front, then an atomic
 *  rename over the destination). */
internal fun writeOwnerOnly(path: Path, content: String) {
    Files.createDirectories(path.parent)
    val posix = path.parent.fileSystem.supportedFileAttributeViews().contains("posix")
    val tmp = if (posix) {
        Files.createTempFile(path.parent, "host", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
    } else {
        Files.createTempFile(path.parent, "host", ".tmp")
    }
    try {
        Files.writeString(tmp, content)
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
