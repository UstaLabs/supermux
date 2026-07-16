package dev.supermux.host

import dev.supermux.proto.SessionInfo
import dev.supermux.ui.oklchToArgb

/**
 * Pure, framework-free fleet-view model (spec §5) — the SINGLE source of truth every native client
 * reuses for the merged multi-host session list. Everything the list needs that ISN'T platform UI:
 * a per-host badge color slot, a compact badge label, an offline "last seen" string, the host
 * filter, and the fixed OKLCH dot palette. Kept in commonMain so Android (Compose) and iOS (SwiftUI)
 * render byte-identical badge colors from ONE algorithm — no per-platform reimplementation (spec D5).
 *
 * Colors are single-sourced too: [hostDotArgb] resolves a slot to a packed 0xAARRGGBB via the shared
 * [oklchToArgb], so a host's dot is the exact same color on every platform.
 */

/** Number of distinct badge colors the palette provides; [hostColorIndex] maps into `0 until` this. */
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

    /** Human-facing name with obsolete local-only prefixes removed. */
    val displayLabel: String get() = hostDisplayLabel(displayName)

    /** Compact label for the per-row badge (first word, capped) — the chip row uses the full name. */
    val shortLabel: String get() = hostShortLabel(displayName)
}

/**
 * Old desktop builds persisted names such as `This computer (Ahmet's MacBook)`; in a fleet every
 * host then appeared as the identical `This` badge. Preserve custom names, but unwrap that legacy
 * local-only prefix everywhere so existing installs become clear without a destructive migration.
 */
fun hostDisplayLabel(displayName: String): String {
    val trimmed = displayName.trim()
    val wrapped = Regex("^This\\s+(?:computer|host)\\s*\\((.+)\\)$", RegexOption.IGNORE_CASE)
        .matchEntire(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        .orEmpty()
    if (wrapped.isNotEmpty()) return wrapped
    if (trimmed.equals("This computer", ignoreCase = true) ||
        trimmed.equals("This host", ignoreCase = true)
    ) return "Host"
    return trimmed.ifEmpty { "Host" }
}

fun isLegacyHostDisplayName(displayName: String): Boolean =
    hostDisplayLabel(displayName) != displayName.trim()

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
    return sessions.filter { sessionHost[it.id] == filter }
}

// ── Fixed OKLCH dot palette (shared so Android + iOS paint the identical color per slot) ──────────
// Six fixed hues spread around the wheel; L/C tuned per theme for a legible small dot. Held FIXED
// across any dynamic/wallpaper color — a host's dot color is its identity.
private val HOST_HUES = doubleArrayOf(195.0, 300.0, 70.0, 22.0, 250.0, 150.0)

/** Hue (degrees) for a badge color slot — wraps into the palette. */
fun hostHueDegrees(colorIndex: Int): Double =
    HOST_HUES[((colorIndex % HOST_HUES.size) + HOST_HUES.size) % HOST_HUES.size]

/**
 * The fixed dot color for a host color slot as packed 0xAARRGGBB, theme-aware. L/C are tuned for a
 * legible small dot on each theme's surface (dark: brighter+less chroma; light: darker+more chroma).
 * Callers wrap the Int in their platform Color (Compose `Color(argb)` / SwiftUI via ARGB channels).
 */
fun hostDotArgb(colorIndex: Int, dark: Boolean): Int {
    val h = hostHueDegrees(colorIndex)
    return if (dark) oklchToArgb(0.74, 0.135, h) else oklchToArgb(0.55, 0.15, h)
}
