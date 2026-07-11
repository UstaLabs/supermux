package dev.supermux.android.host

import android.content.Context
import android.util.Log
import dev.supermux.auth.SecureTokenStore
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import java.util.UUID

/**
 * Process-wide holder for the multi-host [PairedHostStore] (spec §3.2) plus the one-time
 * migration from the legacy single-host `(baseUrl, token)`.
 *
 * The store is built once over [AndroidHostPersistence]; recordIds come from a real UUID RNG.
 * For now the app still drives a single connection, sourced from `PairedHost[0]` — the fleet-list
 * UI is a later task. This layer only makes the storage + migration exist and run at launch.
 */
object HostStores {
    private const val TAG = "SupermuxMultiHost"

    @Volatile private var instance: PairedHostStore? = null

    /** The shared store (lazily built; recordId = random UUID). */
    fun store(context: Context): PairedHostStore =
        instance ?: synchronized(this) {
            instance ?: PairedHostStore(
                AndroidHostPersistence(context.applicationContext),
            ) { UUID.randomUUID().toString() }.also { instance = it }
        }

    /**
     * One-time single-host → `PairedHost[0]` migration, run at launch. Idempotent and safe to
     * call every launch: no-op once the store holds any host (and [PairedHostStore.migrateFromSingleHost]
     * guards emptiness internally). Reads the legacy [SecureTokenStore] `(token, baseUrl)`; existing
     * paired users land as `PairedHost[0]` with ZERO re-pairing. Returns the effective first host.
     */
    fun migrateFromLegacyIfNeeded(context: Context): PairedHost? {
        val store = store(context)
        val existing = store.list()
        if (existing.isNotEmpty()) {
            Log.i(TAG, "migration skipped: store already holds ${existing.size} host(s)")
            return existing.firstOrNull()
        }
        val legacy = SecureTokenStore()
        val token = legacy.load()
        val baseUrl = legacy.loadBaseUrl()
        if (!token.isNullOrBlank() && !baseUrl.isNullOrBlank()) {
            store.migrateFromSingleHost(token, baseUrl)
            Log.i(TAG, "migrated legacy single-host to PairedHost[0] (baseUrl=$baseUrl)")
            return store.list().firstOrNull()
        }
        Log.i(TAG, "no legacy single-host to migrate (token/baseUrl absent)")
        return null
    }
}
