package dev.supermux.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Pure, framework-free split codec: `List<PairedHost>` ⇄ (metadata JSON, per-record token lookup).
 *
 * Shared across platforms (spec §3.2): the multi-host fleet is persisted SPLIT — metadata
 * (recordId/hostId/displayName/urls/platform/version/lastSeenAt) as one JSON string in ordinary app
 * storage (Android DataStore, iOS UserDefaults), each token in the platform secure store (Keystore /
 * Keychain) keyed by recordId. Deliberately NOT one encrypted blob for the whole fleet: a single
 * lost/undecryptable token must cost at most one host (re-pair), never wipe the list.
 *
 * The "no token in the metadata blob" guarantee is STRUCTURALLY enforced here — [Meta] has no token
 * field, so [encodeMeta] cannot serialize one. Lives in commonMain so every native
 * [HostPersistence] reuses the exact same encoding (Android [dev.supermux.android.host], iOS Swift
 * `KeychainHostPersistence` via the Shared framework) — no per-platform reimplementation.
 */
object HostMetaCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Meta.serializer())

    /** Everything in [PairedHost] EXCEPT the token — the only shape written to normal storage. */
    @Serializable
    private data class Meta(
        val recordId: String,
        val hostId: String? = null,
        val displayName: String,
        val directUrl: String? = null,
        val relayUrl: String? = null,
        val platform: String? = null,
        val version: String? = null,
        val lastSeenAt: Long = 0L,
    )

    fun encodeMeta(hosts: List<PairedHost>): String =
        json.encodeToString(
            listSerializer,
            hosts.map {
                Meta(
                    recordId = it.recordId, hostId = it.hostId, displayName = it.displayName,
                    directUrl = it.directUrl, relayUrl = it.relayUrl,
                    platform = it.platform, version = it.version, lastSeenAt = it.lastSeenAt,
                )
            },
        )

    /**
     * Rebuild the fleet from the metadata blob, re-injecting each record's token via [token].
     * A missing token ([token] returns null) yields an EMPTY token so the host is preserved
     * (visible, re-pairable) rather than dropped. Blank/corrupt JSON → empty list.
     */
    fun decode(metaJson: String?, token: (recordId: String) -> String?): List<PairedHost> {
        if (metaJson.isNullOrBlank()) return emptyList()
        val metas = runCatching { json.decodeFromString(listSerializer, metaJson) }.getOrNull()
            ?: return emptyList()
        return metas.map { m ->
            PairedHost(
                recordId = m.recordId, hostId = m.hostId, displayName = m.displayName,
                directUrl = m.directUrl, relayUrl = m.relayUrl,
                token = token(m.recordId) ?: "",
                platform = m.platform, version = m.version, lastSeenAt = m.lastSeenAt,
            )
        }
    }
}
