package dev.supermux.host

/** Pure multi-host list logic. `newId` supplies recordIds (platform RNG in prod,
 *  fixed in tests). Persists through HostPersistence after every mutation. */
class PairedHostStore(
    private val persistence: HostPersistence,
    private val newId: () -> String,
) {
    private val hosts: MutableList<PairedHost> = persistence.loadAll().toMutableList()

    fun list(): List<PairedHost> = hosts.toList()

    fun add(displayName: String, token: String, relayUrl: String? = null,
            directUrl: String? = null, hostId: String? = null,
            platform: String? = null, version: String? = null): PairedHost {
        val h = PairedHost(recordId = newId(), hostId = hostId, displayName = displayName,
            token = token, relayUrl = relayUrl, directUrl = directUrl, platform = platform, version = version)
        hosts.add(h); flush(); return h
    }

    /** One-time seed of the pre-multi-host (token, baseUrl). No-op if any host exists. */
    fun migrateFromSingleHost(token: String, baseUrl: String) {
        if (hosts.isNotEmpty()) return
        val isRelay = baseUrl.contains(".relay.")
        hosts.add(PairedHost(recordId = newId(), displayName = "This host", token = token,
            relayUrl = if (isRelay) baseUrl else null, directUrl = if (isRelay) null else baseUrl))
        flush()
    }

    /** Learn a record's hostId from GET /host; merge if another record already has it. */
    fun backfillHostId(recordId: String, hostId: String) {
        val idx = hosts.indexOfFirst { it.recordId == recordId }
        if (idx < 0) return
        val dupe = hosts.indexOfFirst { it.hostId == hostId && it.recordId != recordId }
        if (dupe >= 0) {
            // Same host reached two ways — collapse to one record at the earliest
            // position. Keep the backfilled record (the one the user has been
            // using): its display name and token, only falling back to the
            // duplicate's name if the user never set one.
            val keepAt = minOf(idx, dupe)
            val merged = hosts[idx].copy(
                hostId = hostId,
                displayName = hosts[idx].displayName.ifBlank { hosts[dupe].displayName },
            )
            hosts[keepAt] = merged
            hosts.removeAt(maxOf(idx, dupe))
        } else {
            hosts[idx] = hosts[idx].copy(hostId = hostId)
        }
        flush()
    }

    fun rename(recordId: String, name: String) = mutate(recordId) { it.copy(displayName = name) }
    fun updateSeen(recordId: String, at: Long) = mutate(recordId) { it.copy(lastSeenAt = at) }
    fun remove(recordId: String) { hosts.removeAll { it.recordId == recordId }; flush() }

    private fun mutate(recordId: String, f: (PairedHost) -> PairedHost) {
        val i = hosts.indexOfFirst { it.recordId == recordId }; if (i < 0) return
        hosts[i] = f(hosts[i]); flush()
    }
    private fun flush() = persistence.saveAll(hosts)
}
