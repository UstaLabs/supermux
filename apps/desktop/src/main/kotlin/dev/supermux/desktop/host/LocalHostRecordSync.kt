package dev.supermux.desktop.host

import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * Keeps the paired "This computer" record on the supervisor's port. The supervisor can move the
 * broker (port in use, a takeover onto the old service's port); the record's `directUrl` must follow,
 * or the fleet keeps dialling the old port and every broker call fails.
 */

/** What to change on [recordId]: its direct URL, and the hostId to fill in when it had none. */
data class HostRecordFix(val recordId: String, val directUrl: String, val fillHostId: String?)

/**
 * Pure. [hostId] is the supervisor's running broker. "This computer" is the record with that hostId,
 * else a loopback record with no hostId (one paired before hostIds existed). Only a loopback (or
 * missing) direct URL is rewritten: a record that reaches this computer some other way is the
 * user's. Remote hosts and loopback records naming another hostId are never touched.
 * Null = nothing to do.
 */
fun thisComputerRecordFix(hosts: List<PairedHost>, hostId: String?, localBaseUrl: String): HostRecordFix? {
    if (hostId.isNullOrBlank()) return null
    val rec = hosts.firstOrNull { it.hostId == hostId }
        ?: hosts.firstOrNull { it.hostId.isNullOrBlank() && isLoopbackUrl(it.directUrl) }
        ?: return null
    val direct = rec.directUrl?.takeIf { it.isNotBlank() }
    if (direct != null && !isLoopbackUrl(direct)) return null
    val fill = hostId.takeIf { rec.hostId.isNullOrBlank() }
    if (fill == null && direct != null && sameUrl(direct, localBaseUrl)) return null
    return HostRecordFix(rec.recordId, localBaseUrl, fill)
}

private fun sameUrl(a: String, b: String) = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

/** Apply [fix] to [store], keeping the record's token, name, relay and the rest. True if it changed. */
fun applyHostRecordFix(store: PairedHostStore, fix: HostRecordFix, hostId: String): Boolean {
    if (fix.fillHostId != null) store.backfillHostId(fix.recordId, fix.fillHostId)
    // backfillHostId may have merged the record into another with this hostId: find it by hostId now.
    val rec = store.list().firstOrNull { it.hostId == hostId } ?: return false
    store.addOrUpdate(displayName = rec.displayName, token = rec.token, directUrl = fix.directUrl, hostId = hostId)
    return true
}

/** The supervisor's (localBaseUrl, hostId) while its broker runs. Distinct. */
fun HostSupervisor.runningAddress(): Flow<Pair<String, String>> =
    combine(prefs, hostId, status) { p, id, s ->
        if (s is HostingStatus.Running && id != null) "http://127.0.0.1:${p.port}" to id else null
    }.filterNotNull().distinctUntilChanged()

/**
 * Keep "This computer" on the supervisor's address for as long as it runs, including the first
 * value at launch (a record left on an old port by a previous run). [onChanged] refreshes the fleet.
 */
suspend fun syncThisComputerRecord(sup: HostSupervisor, store: PairedHostStore, onChanged: () -> Unit) {
    sup.runningAddress().collect { (url, id) ->
        val fix = thisComputerRecordFix(store.list(), id, url) ?: return@collect
        val changed = runCatching { applyHostRecordFix(store, fix, id) }
            .onFailure { System.err.println("supermux host: can't update This computer's record: ${it.message}") }
            .getOrDefault(false)
        if (changed) onChanged()
    }
}
