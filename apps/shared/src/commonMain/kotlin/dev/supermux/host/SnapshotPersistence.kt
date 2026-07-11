package dev.supermux.host

/**
 * Platform storage for the offline-snapshot cache (spec §5): ORDINARY app storage, OUTSIDE the
 * secure token store (the cache holds no secrets). Implementations move bytes only — the
 * [HostSnapshotStore] owns the replace/prune logic and is platform-neutral. Mirrors the
 * [HostPersistence] split-storage pattern.
 */
interface SnapshotPersistence {
    fun loadAll(): List<HostSnapshot>
    fun saveAll(snapshots: List<HostSnapshot>)
}
