package dev.supermux.android.host

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Android [HostPersistence] (spec §3.2). The multi-host fleet is stored SPLIT:
 *
 *  - **Metadata** (recordId, hostId, displayName, direct/relay URLs, platform, version,
 *    lastSeenAt) → a single JSON string in a Jetpack **DataStore** (`host_registry`), i.e.
 *    ordinary app storage, mirroring how the app already persists drafts/launcher prefs.
 *  - **Tokens** → the existing Keystore-backed **EncryptedSharedPreferences** (`supermux_secure`,
 *    the same file [dev.supermux.auth.SecureTokenStore] uses), **one entry per recordId** keyed
 *    `host_token:<recordId>`. Deliberately NOT one encrypted blob for the whole fleet: a single
 *    undecryptable/lost token must cost at most one host (re-pair), never wipe the list.
 *
 * The [dev.supermux.host.PairedHostStore] list logic sits on top of this and is platform-agnostic
 * — this class only moves bytes.
 */
class AndroidHostPersistence(context: Context) : HostPersistence {
    private val appContext = context.applicationContext
    private val tokens = HostTokenStore(appContext)
    private val metaKey = stringPreferencesKey(META_KEY)

    override fun loadAll(): List<PairedHost> {
        val metaJson = runCatching {
            runBlocking { appContext.hostRegistryDataStore.data.first()[metaKey] }
        }.getOrNull()
        return HostMetaCodec.decode(metaJson) { tokens.get(it) }
    }

    override fun saveAll(hosts: List<PairedHost>) {
        // Tokens first, metadata second: metadata is the source of truth on load, so a crash
        // between the two writes leaves at worst an orphan token (pruned next save) — never a
        // host that exists in metadata with no token.
        val liveIds = hosts.map { it.recordId }.toSet()
        (tokens.recordIds() - liveIds).forEach { tokens.remove(it) }   // best-effort local revoke on forget
        hosts.forEach { tokens.put(it.recordId, it.token) }
        val metaJson = HostMetaCodec.encodeMeta(hosts)
        runCatching {
            runBlocking { appContext.hostRegistryDataStore.edit { it[metaKey] = metaJson } }
        }.onFailure { Log.w(TAG, "host metadata save failed", it) }
    }

    private companion object {
        const val TAG = "SupermuxHostStore"
        const val META_KEY = "hosts"
    }
}

/** App-scoped DataStore holding the host-registry metadata JSON (tokens live in the secure store). */
private val Context.hostRegistryDataStore by preferencesDataStore(name = "host_registry")

/**
 * Pure, framework-free split codec: `List<PairedHost>` ⇄ (metadata JSON, per-record token lookup).
 * Isolated from Android so it is unit-testable on the JVM and so the "no token in the metadata
 * blob" guarantee is structurally enforced ([Meta] has no token field).
 */
internal object HostMetaCodec {
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

/**
 * One-token-per-host secure store, backed by the SAME Keystore-encrypted `supermux_secure`
 * prefs file [dev.supermux.auth.SecureTokenStore] uses (the app's established secure-store
 * pattern), with keys namespaced `host_token:<recordId>`. Reuses that file's resilient
 * create-or-reset behavior: an undecryptable keyset (broken-Tink v1 build, or a backup restored
 * onto a device whose Keystore lacks the master key) resets the file instead of crashing at
 * launch — the fleet's metadata survives in DataStore and the affected hosts re-pair.
 */
internal class HostTokenStore(context: Context) {
    private val prefs: SharedPreferences = openResilient(context.applicationContext)

    fun get(recordId: String): String? = prefs.getString(key(recordId), null)
    fun put(recordId: String, token: String) { prefs.edit().putString(key(recordId), token).apply() }
    fun remove(recordId: String) { prefs.edit().remove(key(recordId)).apply() }

    /** recordIds that currently have a stored token (used to prune orphans on save). */
    fun recordIds(): Set<String> =
        prefs.all.keys.asSequence()
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) }
            .toSet()

    private fun key(recordId: String) = "$PREFIX$recordId"

    private companion object {
        const val PREFIX = "host_token:"
        const val FILE = "supermux_secure"

        fun openResilient(ctx: Context): SharedPreferences = try {
            create(ctx)
        } catch (e: Exception) {
            Log.w("SupermuxHostStore", "encrypted host-token prefs unreadable, resetting (re-pair required)", e)
            ctx.deleteSharedPreferences(FILE)
            create(ctx)
        }

        fun create(ctx: Context): SharedPreferences = EncryptedSharedPreferences.create(
            ctx, FILE,
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
