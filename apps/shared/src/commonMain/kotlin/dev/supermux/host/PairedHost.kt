package dev.supermux.host

import kotlinx.serialization.Serializable

/** One paired broker. recordId is the always-present internal key; hostId is
 *  backfilled from GET /host once known (null against pre-Plan-1 brokers). */
@Serializable
data class PairedHost(
    val recordId: String,
    val hostId: String? = null,
    val displayName: String,
    val directUrl: String? = null,
    val relayUrl: String? = null,
    val token: String,
    /** The transport carries the credential itself — the browser's HttpOnly cookie — so a blank
     *  [token] still dials. Metadata, not a credential: nothing here can authenticate anything. */
    val ambientAuth: Boolean = false,
    val platform: String? = null,
    val version: String? = null,
    val lastSeenAt: Long = 0L,
)
