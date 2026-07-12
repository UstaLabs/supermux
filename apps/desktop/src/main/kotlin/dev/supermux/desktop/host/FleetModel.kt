package dev.supermux.desktop.host

import dev.supermux.host.PairedHost
import dev.supermux.proto.SessionInfo

/**
 * Pure, framework-free fleet-view model (spec §5) — the desktop mirror of
 * `apps/android/.../host/FleetModel.kt`. Everything the merged multi-host session list needs that
 * ISN'T Compose: a per-host badge color slot, a compact badge label, an offline "last seen"
 * string, the host filter, and the per-host → merged session fold. Kept Compose/broker-free so it
 * unit-tests on the JVM ([FleetModelTest]) and so the UI layer only maps a [colorIndex] → a theme
 * Color and [FleetState] only wires flows to [mergeSessions]/[hostViewsFrom].
 */

/** Number of distinct badge colors the UI palette provides; [hostColorIndex] maps into `0 until` this. */
const val HOST_PALETTE_SIZE = 6

/**
 * One paired host as the fleet list renders it: identity + live reachability + a stable badge
 * color slot. [colorIndex]/[shortLabel] are derived so the same host always looks the same.
 */
data class HostView(
    val recordId: String,
    val hostId: String?,
    val displayName: String,
    val online: Boolean,
    val lastSeenAt: Long = 0L,
) {
    /** Stable 0..[HOST_PALETTE_SIZE)-1 color slot — seeded by the durable hostId when known,
     *  else the recordId, so the dot color survives a hostId backfill. */
    val colorIndex: Int get() = hostColorIndex(hostId ?: recordId)

    /** Compact label for the per-row badge (first word, capped) — the chip row uses the full name. */
    val shortLabel: String get() = hostShortLabel(displayName)
}

/**
 * Deterministic badge-color slot for [seed] (a hostId or recordId). FNV-1a over the chars so it
 * is stable across processes (unlike relying on any platform hashCode) and spreads similar
 * recordIds/UUIDs across the palette. Always in `0 until paletteSize`.
 */
fun hostColorIndex(seed: String, paletteSize: Int = HOST_PALETTE_SIZE): Int {
    if (seed.isEmpty() || paletteSize <= 1) return 0
    var h = -0x7ee3623b // 2166136261 (FNV offset basis) as a signed Int
    for (c in seed) {
        h = h xor c.code
        h *= 0x01000193 // FNV prime
    }
    return ((h % paletteSize) + paletteSize) % paletteSize
}

/** Compact badge text: the first whitespace-delimited token of the display name, capped at 14
 *  chars (the chip row shows the full name; the row badge must stay short). */
fun hostShortLabel(displayName: String): String {
    val trimmed = displayName.trim()
    if (trimmed.isEmpty()) return "host"
    val firstToken = trimmed.split(Regex("\\s+")).firstOrNull().orEmpty()
    return firstToken.take(14).ifEmpty { trimmed.take(14) }
}

/**
 * Relative "last seen" for an offline host group header — e.g. "just now", "5m ago", "2h ago",
 * "3d ago". [lastSeenAt] is epoch-millis (0 = never seen → empty). Mirrors the list's relTime
 * buckets so offline headers read consistently with row timestamps.
 */
fun formatLastSeen(nowMs: Long, lastSeenAt: Long): String {
    if (lastSeenAt <= 0L) return ""
    val diffSec = ((nowMs - lastSeenAt) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "just now"
        diffSec < 3600L -> "${diffSec / 60}m ago"
        diffSec < 86_400L -> "${diffSec / 3600}h ago"
        else -> "${diffSec / 86_400}d ago"
    }
}

/**
 * The sessions visible under the current host [filter] (a recordId, or null = "All"). An unknown
 * filter (host forgotten while selected) falls back to All so the list never blanks out.
 */
fun filterSessions(
    sessions: List<SessionInfo>,
    sessionHost: Map<String, String>,
    filter: String?,
): List<SessionInfo> {
    if (filter == null) return sessions
    if (sessionHost.values.none { it == filter }) return sessions
    return sessions.filter { sessionHost[it.id] == filter }
}

/** A merged fleet session list plus the sessionId → owning-host recordId map that drives per-row
 *  badges and per-session routing. */
data class MergedSessions(
    val sessions: List<SessionInfo>,
    val sessionHost: Map<String, String>,
)

/**
 * Flatten the per-host session buckets into ONE merged list (in store [order], then any cached
 * bucket whose host is no longer in the store) plus the sessionId → recordId owner map. Session
 * ids are globally unique across hosts, so a duplicate id (should not happen) keeps its FIRST
 * owner in store order rather than double-rendering. Mirrors Android AppViewModel.rebuildSessions.
 */
fun mergeSessions(
    order: List<String>,
    sessionsByHost: Map<String, List<SessionInfo>>,
): MergedSessions {
    val ids = LinkedHashSet(order).apply { addAll(sessionsByHost.keys) }
    val flat = ArrayList<SessionInfo>()
    val owner = LinkedHashMap<String, String>()
    for (rid in ids) {
        sessionsByHost[rid]?.forEach { s ->
            if (owner[s.id] == null) {
                flat += s
                owner[s.id] = rid
            }
        }
    }
    return MergedSessions(flat, owner)
}

/** Derive the [HostView] list from the store's [hosts] (source of order + identity + lastSeenAt)
 *  and the live [online] reachability map (recordId → connected). */
fun hostViewsFrom(hosts: List<PairedHost>, online: Map<String, Boolean>): List<HostView> =
    hosts.map { h ->
        HostView(
            recordId = h.recordId,
            hostId = h.hostId,
            displayName = h.displayName,
            online = online[h.recordId] == true,
            lastSeenAt = h.lastSeenAt,
        )
    }
