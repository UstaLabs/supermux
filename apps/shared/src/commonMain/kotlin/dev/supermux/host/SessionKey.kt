package dev.supermux.host

import dev.supermux.proto.SessionInfo

/**
 * Host-qualified session identity (spec §5/§9: "identity is (recordId, sessionId)"). Two brokers can
 * present the SAME broker-local sessionId — accidentally near-impossible, but trivial for a hostile or
 * restored host — so the multi-host reducer must key per-session state by (recordId, sessionId), never
 * by the bare id. Keyed by the bare id, one host's session HIDES the other in the merged list and its
 * live frames CONTAMINATE the wrong chat. This type is the composite key that closes that hole.
 *
 * Serialized as "recordId<US>sessionId" (U+001F unit separator — present in neither a UUID recordId nor
 * a broker sessionId) so it threads through the existing String-keyed maps unchanged.
 */
data class SessionKey(val recordId: String, val sessionId: String) {
    fun asString(): String = "$recordId$SEP$sessionId"

    companion object {
        const val SEP = '\u001F'

        fun of(recordId: String, sessionId: String) = SessionKey(recordId, sessionId)

        /** Compose the composite string directly (no allocation of a [SessionKey]). */
        fun key(recordId: String, sessionId: String): String = "$recordId$SEP$sessionId"

        /** Parse a composite string; null if it carries no separator (e.g. a legacy bare id). */
        fun parse(s: String): SessionKey? {
            val i = s.indexOf(SEP)
            if (i < 0) return null
            return SessionKey(s.substring(0, i), s.substring(i + 1))
        }

        /**
         * Flatten a composite-keyed per-session map down to a bare-sessionId map for the UI, OWNER-
         * STRICTLY: for a session with a known [owner], ONLY the owner host's value is surfaced; a
         * non-owner host's entry for the same id is DROPPED, never merged — so a collision can never
         * route another host's state into the owner's chat (the finding's "route state incorrectly").
         * A session with no recorded owner (not in any bucket) falls back to first-seen.
         */
        fun <V> flatten(byKey: Map<String, V>, owner: Map<String, String>): Map<String, V> {
            val out = LinkedHashMap<String, V>()
            for ((k, v) in byKey) {
                val sk = parse(k) ?: continue
                val o = owner[sk.sessionId]
                when {
                    o == null -> if (sk.sessionId !in out) out[sk.sessionId] = v // no owner: first wins
                    o == sk.recordId -> out[sk.sessionId] = v                     // owner value wins
                    // else: non-owner entry for an owned session → dropped (no contamination)
                }
            }
            return out
        }

        /** Bare-sessionId set flattened from composite keys (e.g. the "sending…" set). */
        fun flattenSet(keys: Set<String>): Set<String> =
            keys.mapNotNullTo(LinkedHashSet()) { parse(it)?.sessionId }
    }
}

/** One (host, session) pair in the merged fleet — the row identity the finding wants host-qualified. */
data class FleetRow(val recordId: String, val session: SessionInfo) {
    val key: String get() = SessionKey.key(recordId, session.id)
}

/**
 * Merge every host's sessions into the fleet in host [order], WITHOUT dropping collisions: two hosts
 * presenting the same sessionId yield TWO rows with distinct [FleetRow.key]s (spec §5 "both appear").
 * Hosts present in [sessionsByHost] but not in [order] are appended (defensive, stable).
 */
fun mergeFleetRows(order: List<String>, sessionsByHost: Map<String, List<SessionInfo>>): List<FleetRow> {
    val ids = LinkedHashSet(order).apply { addAll(sessionsByHost.keys) }
    val rows = ArrayList<FleetRow>()
    for (rid in ids) sessionsByHost[rid]?.forEach { s -> rows += FleetRow(rid, s) }
    return rows
}

/** Owner index (bare sessionId → recordId): the FIRST host in fleet order to present the id owns it
 *  for routing + the deduped UI list. A collision's non-owner is reachable only via its [FleetRow.key]. */
fun fleetOwners(rows: List<FleetRow>): Map<String, String> {
    val owner = LinkedHashMap<String, String>()
    for (r in rows) if (r.session.id !in owner) owner[r.session.id] = r.recordId
    return owner
}

/** The merged list the UI renders: one [SessionInfo] per bare id (owner wins), preserving fleet order.
 *  Both colliding sessions live in [mergeFleetRows]; this collapses to a crash-free keyed list. */
fun mergedSessions(rows: List<FleetRow>): List<SessionInfo> {
    val seen = HashSet<String>()
    val out = ArrayList<SessionInfo>()
    for (r in rows) if (seen.add(r.session.id)) out += r.session
    return out
}
