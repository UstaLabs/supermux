package dev.supermux.host

import dev.supermux.proto.SessionInfo

/**
 * Pure per-host offline-snapshot cache (spec §5): recordId → its last-known LIVE session list,
 * persisted through [SnapshotPersistence] after every mutation and loaded at construction so a host
 * that is offline at launch renders its last-known sessions (dimmed, with last-seen) instead of an
 * empty group.
 *
 * Platform-neutral (the storage actual is per platform, e.g. Android DataStore). Insertion-ordered
 * so a caller seeding buckets from [all] preserves a stable host order.
 */
class HostSnapshotStore(private val persistence: SnapshotPersistence) {
    private val byRecord = LinkedHashMap<String, HostSnapshot>()

    init {
        persistence.loadAll().forEach { byRecord[it.recordId] = it }
    }

    fun all(): List<HostSnapshot> = byRecord.values.toList()

    fun get(recordId: String): HostSnapshot? = byRecord[recordId]

    /**
     * Replace THIS host's cached snapshot WHOLESALE (spec §5 "replaced wholesale on each successful
     * full snapshot") — a fresh full snapshot supersedes the last, it is never merged. [sessions]
     * must be the live list only (archived sessions are not cached).
     */
    fun replace(
        recordId: String,
        sessions: List<SessionInfo>,
        fetchedAt: Long,
        brokerVersion: String? = null,
    ) {
        byRecord[recordId] = HostSnapshot(recordId, sessions, fetchedAt, brokerVersion)
        flush()
    }

    /** Drop a forgotten host's cache (spec §5 "dropped when the host is forgotten"). */
    fun remove(recordId: String) {
        if (byRecord.remove(recordId) != null) flush()
    }

    /** Prune caches for records no longer in the fleet (e.g. a host forgotten while the app was
     *  dead, so its cache never got the per-forget [remove]). */
    fun retainOnly(recordIds: Collection<String>) {
        val keep = recordIds.toSet()
        if (byRecord.keys.retainAll(keep)) flush()
    }

    private fun flush() = persistence.saveAll(byRecord.values.toList())
}
