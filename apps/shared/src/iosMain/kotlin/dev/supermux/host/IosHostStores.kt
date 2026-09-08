package dev.supermux.host

import dev.supermux.auth.SecureTokenStore
import platform.Foundation.NSUUID

/**
 * Process-wide holder for the multi-host [PairedHostStore] and the offline-snapshot cache, plus the
 * one-time legacy single-host migration. The iOS mirror of Android's `HostStores` and of the Swift
 * app's `HostStore` enum.
 *
 * There must be exactly ONE `PairedHostStore` alive in the process. The store keeps the fleet in
 * memory and writes through to storage; a second instance would hold a stale copy and the two would
 * overwrite each other's saves. Under the Compose shell this holder is that one — which is why
 * Swift must stop touching `HostStore.shared` once the Kotlin root exists (cluster H2).
 */
object IosHostStores {

    private var storeInstance: PairedHostStore? = null
    private var snapshotInstance: HostSnapshotStore? = null

    /** The shared store; recordIds are random UUIDs, matching Swift's `UUID().uuidString`. */
    fun store(): PairedHostStore =
        storeInstance ?: PairedHostStore(KeychainHostPersistence()) { NSUUID().UUIDString }
            .also { storeInstance = it }

    /** The shared per-host offline-snapshot cache (spec §5), outside the Keychain. */
    fun snapshotStore(): HostSnapshotStore =
        snapshotInstance ?: HostSnapshotStore(IosSnapshotPersistence()).also { snapshotInstance = it }

    /**
     * One-time single-host → `PairedHost[0]` migration, run at launch. Idempotent: a store that
     * already holds a host is left alone, so this is safe to call on every launch — and it must be,
     * because the `SM_PAIR_TOKEN` / `SM_PAIR_BASE` env seed writes the LEGACY store and needs this
     * pass to become a real host record. Mirrors Android's `HostStores.migrateFromLegacyIfNeeded`
     * and Swift's `HostStore.migrateFromLegacyIfNeeded`; existing paired users land as
     * `PairedHost[0]` with zero re-pairing.
     */
    fun migrateFromLegacyIfNeeded(): PairedHost? {
        val store = store()
        store.list().firstOrNull()?.let { return it }
        val legacy = SecureTokenStore()
        val token = legacy.load()
        val baseUrl = legacy.loadBaseUrl()
        if (token.isNullOrBlank() || baseUrl.isNullOrBlank()) return null
        store.migrateFromSingleHost(token, baseUrl)
        return store.list().firstOrNull()
    }
}
