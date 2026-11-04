// One reader for every pairing code a user can scan, paste or open (card #59).
//
// The desktop host's QR is a one-time claim (JSON); `supermux pair`, Settings ▸ Devices and the web
// onboarding hand out links that already carry a device token. The first-run connect page read the
// links only, so a brand-new phone could not pair with the desktop app's own QR while Add host, which
// read both, could. Both now ask this one parser, and both redeem a claim through [redeem].
package dev.supermux.pairing

import dev.supermux.host.PairingPayload
import dev.supermux.net.PairClaimResult
import dev.supermux.net.PairUrl

/** A pairing code, read. */
sealed interface PairingCode {
    /** The desktop host's QR: a one-time claim this device exchanges for its own token ([redeem]). */
    data class Claim(val payload: PairingPayload) : PairingCode

    /** A code that already carries a device token: `https://…/pair?t=`, `supermux://pair?t=`, or a bare token. */
    data class Token(val pair: PairUrl) : PairingCode

    companion object {
        /**
         * The claim is read first: a bare token is anything at all once [fallbackBaseUrl] is known, so
         * the other way round a claim would be sent to that broker as a bearer token. For the same
         * reason JSON that is not a claim this build understands is never a bare token either.
         */
        fun parse(raw: String, fallbackBaseUrl: String? = null): PairingCode? {
            val input = raw.trim()
            PairingPayload.parse(input)?.let { return Claim(it) }
            if (input.startsWith("{")) return null
            return PairUrl.parse(input, fallbackBaseUrl)?.let(::Token)
        }
    }
}

/** Where a claim is redeemed: the relay when the code has one, else the host's direct URL. */
val PairingPayload.claimUrl: String? get() = relayUrl ?: directUrl

/** What redeeming a claim gave: this device's token, from the host the code named — or why not. */
sealed interface ClaimRedemption {
    /** [url] is the address it was redeemed on ([claimUrl]). */
    data class Redeemed(val url: String, val result: PairClaimResult) : ClaimRedemption
    data class Failed(val message: String) : ClaimRedemption
}

/**
 * Redeem this one-time claim through [claim] (`POST /pair/claim`) and keep the answer only when it
 * comes from the host the code named: an exact, non-empty hostId match (spec §3.4 — a missing id is a
 * failure, not a pass, or a MITM broker could simply omit it). Add host and the first-run connect page
 * both redeem through here, so neither can skip the check.
 */
suspend fun PairingPayload.redeem(
    deviceName: String,
    claim: suspend (url: String, secret: String, deviceName: String) -> PairClaimResult?,
): ClaimRedemption {
    val url = claimUrl ?: return ClaimRedemption.Failed("That pairing link has no host URL.")
    val res = claim(url, claimSecret, deviceName)
        ?: return ClaimRedemption.Failed("The host rejected the pairing — the claim is expired or already used.")
    if (res.deviceToken.isBlank()) return ClaimRedemption.Failed("The host didn't return a device token.")
    val returned = res.host?.hostId
    if (returned.isNullOrBlank() || returned != hostId) {
        return ClaimRedemption.Failed(
            "Host identity mismatch (link $hostId, got ${returned?.ifBlank { null } ?: "none"}) — aborting.",
        )
    }
    return ClaimRedemption.Redeemed(url, res)
}
