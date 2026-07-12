package dev.supermux.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Schema-versioned JSON codec for the per-host offline-snapshot cache (spec §5). Wrapping the
 * snapshots in a versioned [Envelope] lets the on-disk format evolve: a version this build does not
 * recognize — or any decode failure (blank/corrupt/partial write) — yields an EMPTY cache, so the
 * hosts simply re-fetch their live snapshot on connect. Never a crash, never stale cross-version data.
 *
 * Pure/framework-free (commonMain) so every native platform reuses the exact same encoding.
 */
object HostSnapshotCodec {
    /** Bump when [HostSnapshot]'s shape changes incompatibly; an older/newer file then decodes empty. */
    const val VERSION = 1

    // encodeDefaults=true so the schema [version] is ALWAYS written — otherwise kotlinx omits the
    // default-valued field and a future build could not tell an old file from a current one (it would
    // decode the absent version as ITS OWN default and wrongly trust stale data).
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Serializable
    private data class Envelope(
        val version: Int = VERSION,
        val snapshots: List<HostSnapshot> = emptyList(),
    )

    fun encode(snapshots: Collection<HostSnapshot>): String =
        json.encodeToString(Envelope.serializer(), Envelope(VERSION, snapshots.toList()))

    fun decode(text: String?): List<HostSnapshot> {
        if (text.isNullOrBlank()) return emptyList()
        val env = runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull()
            ?: return emptyList()
        if (env.version != VERSION) return emptyList()
        return env.snapshots
    }
}
