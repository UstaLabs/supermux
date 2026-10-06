package dev.supermux.desktop.host

import dev.supermux.host.PairedHost

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The user's hosting choices for THIS computer (spec D1/D5):
 *  - [hosting]: "Host on this computer"
 *  - [background]: "Keep running in the background" (OS service runs the broker)
 *  - [relay]: remote access through the relay (MUX_RELAY_DOMAIN)
 *  - [port]: the saved host port — 9898 until a conflict forces a move; never moves back
 *  - [leftAloneHostIds]: brokers set up outside the app the user chose to leave alone
 *  - [lidClosed]: "Even with the lid closed" (MacBooks; the app holds the lid helper's lease)
 */
@Serializable
data class HostingPrefs(
    val hosting: Boolean = true,
    val background: Boolean = true,
    val relay: Boolean = true,
    val port: Int = DEFAULT_PORT,
    val leftAloneHostIds: Set<String> = emptySet(),
    val lidClosed: Boolean = false,
) {
    companion object { const val DEFAULT_PORT = 9898 }
}

class HostingPrefsStore(
    private val file: Path = defaultFile(),
    private val legacyPortFile: Path = BrokerPaths.defaultStateDir().resolve("desktop-sidecar.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): HostingPrefs {
        val stored = runCatching { json.decodeFromString(HostingPrefs.serializer(), Files.readString(file)) }.getOrNull()
        if (stored != null) return if (stored.port in 1..65535) stored else stored.copy(port = HostingPrefs.DEFAULT_PORT)
        val legacyPort = runCatching {
            json.decodeFromString(LegacyPort.serializer(), Files.readString(legacyPortFile)).alternatePort
        }.getOrNull()?.takeIf { it in 1..65535 }
        return HostingPrefs(port = legacyPort ?: HostingPrefs.DEFAULT_PORT)
    }

    /** True once `hosting.json` has been written (an upgrade from a pre-hosting build has none). */
    fun exists(): Boolean = Files.exists(file)

    /** Atomic write (unique tmp → move) so a crash or a concurrent save never leaves a half-written file. */
    @Synchronized
    fun save(prefs: HostingPrefs) {
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, "hosting", ".tmp")
        try {
            Files.writeString(tmp, json.encodeToString(HostingPrefs.serializer(), prefs))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Serializable private data class LegacyPort(val alternatePort: Int? = null)

    companion object {
        fun defaultFile(): Path =
            Path.of(System.getProperty("user.home") ?: ".", ".config", "supermux-desktop", "hosting.json")
    }
}
/**
 * The first prefs, decided before the first `ensure()` so an upgrade never starts hosting by surprise.
 * Returns null when nothing should be written:
 *  - `hosting.json` exists: the user's choice stands;
 *  - no paired host: first run, the wizard decides (its Done implies hosting);
 *  - a paired record for this computer (loopback directUrl): the defaults, hosting on.
 * Otherwise (paired only to other computers) hosting starts OFF.
 */
fun initialHostingPrefs(
    hosts: List<PairedHost>,
    prefsFileExists: Boolean,
    base: HostingPrefs = HostingPrefs(),
): HostingPrefs? = when {
    prefsFileExists -> null
    hosts.isEmpty() -> null
    hosts.any { isLoopbackUrl(it.directUrl) } -> null
    else -> base.copy(hosting = false)
}
