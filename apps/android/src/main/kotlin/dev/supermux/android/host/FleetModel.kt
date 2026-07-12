package dev.supermux.android.host

import dev.supermux.host.HOST_PALETTE_SIZE as SharedHostPaletteSize
import dev.supermux.proto.SessionInfo

/**
 * Android fleet-view shims → the shared [dev.supermux.host] FleetModel (spec §5, D5).
 *
 * The pure color-slot / label / last-seen / filter logic now lives in commonMain (`shared`) so
 * Android (Compose) and iOS (SwiftUI) render byte-identical badges from ONE algorithm — no
 * per-platform reimplementation. These thin aliases keep every existing Android call site (and the
 * Compose [HostBadge]) unchanged while the implementation is single-sourced; the OKLCH dot palette
 * moved with it (see [HostBadge.hostDotColor] → `dev.supermux.host.hostDotArgb`).
 */

/** @see dev.supermux.host.HostView */
typealias HostView = dev.supermux.host.HostView

/** @see dev.supermux.host.HOST_PALETTE_SIZE */
const val HOST_PALETTE_SIZE = SharedHostPaletteSize

fun hostColorIndex(seed: String, paletteSize: Int = HOST_PALETTE_SIZE): Int =
    dev.supermux.host.hostColorIndex(seed, paletteSize)

fun hostShortLabel(displayName: String): String =
    dev.supermux.host.hostShortLabel(displayName)

fun formatLastSeen(nowMs: Long, lastSeenAt: Long): String =
    dev.supermux.host.formatLastSeen(nowMs, lastSeenAt)

fun filterSessions(
    sessions: List<SessionInfo>,
    sessionHost: Map<String, String>,
    filter: String?,
): List<SessionInfo> = dev.supermux.host.filterSessions(sessions, sessionHost, filter)
