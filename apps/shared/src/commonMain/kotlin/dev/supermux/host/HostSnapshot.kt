package dev.supermux.host

import dev.supermux.proto.SessionInfo
import kotlinx.serialization.Serializable

/**
 * A host's last-known LIVE session list, cached OUTSIDE the secure token store so a disconnected
 * host still renders its sessions after an app restart (spec §5 "greyed group with last-seen …
 * rendered from a persisted last-snapshot per host"). Without this the in-memory bucket is lost on
 * process death and a reconnecting-but-currently-offline host shows an empty group.
 *
 * Holds no secrets — only session metadata for rendering. Archived sessions are NOT cached (the
 * control-WS snapshot carries live sessions only). Replaced wholesale per successful full snapshot;
 * dropped when the host is forgotten.
 */
@Serializable
data class HostSnapshot(
    val recordId: String,
    val sessions: List<SessionInfo> = emptyList(),
    /** Epoch millis the snapshot was captured (drives "last seen" freshness; display uses the
     *  host record's lastSeenAt, this is the cache's own provenance). */
    val fetchedAt: Long = 0L,
    /** The host's reported version at capture time (spec §5 `brokerVersion?`), display-only. */
    val brokerVersion: String? = null,
)
