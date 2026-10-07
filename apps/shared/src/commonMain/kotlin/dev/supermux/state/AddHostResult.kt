package dev.supermux.state

import dev.supermux.host.PairedHost
import dev.supermux.net.HostIdentity

/** Outcome of an add-host attempt (spec §3.4 / §5) — the desktop mirror of Android's AddHostResult. */
sealed interface AddHostResult {
    data class Added(val host: PairedHost) : AddHostResult
    /** Typed-URL path reached a real supermux host, but it is already set up and needs a claim
     *  minted from its own UI (paste that link instead). */
    data class NeedsClaim(val identity: HostIdentity) : AddHostResult
    data class Error(val message: String) : AddHostResult
}
