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

    // `by lazy` and not a null-check: the default `LazyThreadSafetyMode.SYNCHRONIZED` is what
    // actually delivers the "exactly ONE store" guarantee this object's KDoc claims. The
    // check-then-assign it replaced could hand two callers two different stores under a race, and
    // each would then hold its own in-memory fleet and overwrite the other's saves — the precise
    // failure the guarantee exists to prevent. Construction is cheap (a Keychain read happens on
    // first `list()`, not here), so paying for synchronisation costs nothing.

    /** The shared store; recordIds are random UUIDs, matching Swift's `UUID().uuidString`. */
    private val storeInstance: PairedHostStore by lazy {
        PairedHostStore(KeychainHostPersistence()) { NSUUID().UUIDString }
    }

    /** The shared per-host offline-snapshot cache (spec §5), outside the Keychain. */
    private val snapshotInstance: HostSnapshotStore by lazy {
        HostSnapshotStore(IosSnapshotPersistence())
    }

    fun store(): PairedHostStore = storeInstance

    fun snapshotStore(): HostSnapshotStore = snapshotInstance

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
