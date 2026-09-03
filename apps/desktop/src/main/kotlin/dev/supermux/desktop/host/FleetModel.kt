package dev.supermux.desktop.host

import dev.supermux.host.PairedHost
import dev.supermux.proto.SessionInfo

typealias HostView = dev.supermux.host.HostView

/**
 * Pure, framework-free fleet-view model (spec §5) — the desktop mirror of
 * `apps/android/.../host/FleetModel.kt`. Everything the merged multi-host session list needs that
 * ISN'T Compose: a per-host badge color slot, a compact badge label, an offline "last seen"
 * string, the host filter, and the per-host → merged session fold. Kept Compose/broker-free so it
 * unit-tests on the JVM ([FleetModelTest]) and so the UI layer only maps a [colorIndex] → a theme
 * Color and [FleetStore] only wires flows to [mergeSessions]/[hostViewsFrom].
 */

/** Number of distinct badge colors the UI palette provides; [hostColorIndex] maps into `0 until` this. */
const val HOST_PALETTE_SIZE = 6

fun hostDisplayLabel(displayName: String): String {
    val trimmed = displayName.trim()
    val wrapped = Regex("^This\\s+(?:computer|host)\\s*\\((.+)\\)$", RegexOption.IGNORE_CASE)
        .matchEntire(trimmed)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    if (wrapped.isNotEmpty()) return wrapped
    if (trimmed.equals("This computer", ignoreCase = true) ||
        trimmed.equals("This host", ignoreCase = true)
    ) return "Host"
    return trimmed.ifEmpty { "Host" }
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
    val label = hostDisplayLabel(displayName)
    val firstToken = label.split(Regex("\\s+")).firstOrNull().orEmpty()
    return firstToken.take(14).ifEmpty { "Host" }
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

typealias MergedSessions = dev.supermux.host.MergedSessions

fun filterSessions(
    sessions: List<SessionInfo>,
    sessionHost: Map<String, String>,
    filter: String?,
): List<SessionInfo> = dev.supermux.host.filterSessions(sessions, sessionHost, filter)

fun mergeSessions(
    order: List<String>,
    sessionsByHost: Map<String, List<SessionInfo>>,
): MergedSessions = dev.supermux.host.mergeSessions(order, sessionsByHost)

fun hostViewsFrom(hosts: List<PairedHost>, online: Map<String, Boolean>): List<HostView> =
    dev.supermux.host.hostViewsFrom(hosts, online)
