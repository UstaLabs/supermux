package dev.supermux.host

import kotlinx.browser.localStorage
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * The browser's host registry, in `localStorage`.
 *
 * It reuses the SAME commonMain codec every other platform uses ([HostMetaCodec]) rather than
 * serialising [PairedHost] directly, so the split-storage guarantee of spec §3.2 holds here too:
 * the metadata blob structurally cannot contain a token. There is exactly one host — the page
 * origin — and its token is blank (the HttpOnly cookie is the credential), so the token side is
 * an ordinary `localStorage` map and is, in practice, always empty strings. Corrupt or missing
 * JSON loads as empty, which the caller reads as "not set up yet".
 */
class LocalStorageHostPersistence(
    private val metaKey: String = "supermux:hosts",
    private val tokenKey: String = "supermux:hostTokens",
) : HostPersistence {

    override fun loadAll(): List<PairedHost> {
        val tokens = readTokens()
        return HostMetaCodec.decode(localStorage[metaKey]) { tokens[it] }
    }

    override fun saveAll(hosts: List<PairedHost>) {
        // Tokens first, metadata last — the same write order as the Keychain/DataStore hosts, so a
        // crash between the two leaves a token with no host (pruned next save) and never a host
        // that looks paired but cannot connect.
        localStorage[tokenKey] = json.encodeToString(tokenSerializer, hosts.associate { it.recordId to it.token })
        localStorage[metaKey] = HostMetaCodec.encodeMeta(hosts)
    }

    private fun readTokens(): Map<String, String> =
        localStorage[tokenKey]
            ?.let { runCatching { json.decodeFromString(tokenSerializer, it) }.getOrNull() }
            .orEmpty()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val tokenSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}

/** Last-seen session lists per host, so the sidebar paints before the socket connects. */
class LocalStorageSnapshotPersistence(private val key: String = "supermux:snapshots") : SnapshotPersistence {
    override fun loadAll(): List<HostSnapshot> = HostSnapshotCodec.decode(localStorage[key])

    override fun saveAll(snapshots: List<HostSnapshot>) {
        localStorage[key] = HostSnapshotCodec.encode(snapshots)
    }
}
