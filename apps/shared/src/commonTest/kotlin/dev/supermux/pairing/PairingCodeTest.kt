package dev.supermux.pairing

import dev.supermux.host.PairingPayload
import dev.supermux.net.HostIdentity
import dev.supermux.net.PairClaimResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A well-formed 26-char base32 hostId (spec §3.1). */
private const val HID = "amsfen6cjgpd7pjqw6nimlahvy"

/** The desktop host wizard's QR, exactly as it encodes it (HostWizard.kt `encodePairingPayload`). */
private const val CLAIM =
    """{"v":1,"action":"pair","hostId":"$HID","name":"studio","relayUrl":"https://h-$HID.relay.supermux.dev",""" +
        """"directUrl":"http://127.0.0.1:9898","claimSecret":"s3cret"}"""

/**
 * [PairingCode.parse] is the one reader for every pairing code (card #59): the first-run connect
 * page and Add host both ask it, so the desktop host's claim QR and the token links read alike on
 * both. [redeem] is the one claim check both go through.
 */
class PairingCodeTest {

    // ── parse ────────────────────────────────────────────────────────────────────────────────

    @Test fun the_desktop_hosts_claim_qr_reads_as_a_claim() {
        val claim = assertIs<PairingCode.Claim>(PairingCode.parse(CLAIM))
        assertEquals(HID, claim.payload.hostId)
        assertEquals("s3cret", claim.payload.claimSecret)
    }

    @Test fun a_pasted_claim_with_surrounding_whitespace_still_reads() {
        assertIs<PairingCode.Claim>(PairingCode.parse("\n  $CLAIM \n"))
    }

    @Test fun pairing_links_read_as_tokens() {
        val https = assertIs<PairingCode.Token>(PairingCode.parse("https://host:9898/pair?t=abc123"))
        assertEquals("wss://host:9898", https.pair.baseUrl)
        assertEquals("abc123", https.pair.token)
        val deep = assertIs<PairingCode.Token>(PairingCode.parse("supermux://pair?t=abc123&base=https%3A%2F%2Fhost"))
        assertEquals("wss://host", deep.pair.baseUrl)
        assertEquals("abc123", deep.pair.token)
    }

    @Test fun a_bare_token_needs_a_known_broker() {
        assertNull(PairingCode.parse("abc123"))
        val bare = assertIs<PairingCode.Token>(PairingCode.parse("abc123", fallbackBaseUrl = "ws://10.0.2.2:9898"))
        assertEquals("ws://10.0.2.2:9898", bare.pair.baseUrl)
        assertEquals("abc123", bare.pair.token)
    }

    @Test fun a_claim_is_never_taken_for_a_bare_token() {
        // With a broker already known anything at all is a bare token: read the other way round,
        // the whole claim, secret included, would go to that broker as a bearer token.
        assertIs<PairingCode.Claim>(PairingCode.parse(CLAIM, fallbackBaseUrl = "ws://10.0.2.2:9898"))
    }

    @Test fun json_that_is_not_a_claim_is_not_a_bare_token_either() {
        val fromANewerHost = CLAIM.replace("\"v\":1", "\"v\":2")
        assertNull(PairingCode.parse(fromANewerHost, fallbackBaseUrl = "ws://10.0.2.2:9898"))
    }

    @Test fun blank_reads_as_nothing() {
        assertNull(PairingCode.parse("   ", fallbackBaseUrl = "ws://10.0.2.2:9898"))
    }

    // ── redeem ───────────────────────────────────────────────────────────────────────────────

    private val payload = assertIs<PairingCode.Claim>(PairingCode.parse(CLAIM)).payload

    @Test fun redeem_claims_on_the_relay_and_keeps_the_named_hosts_token() = runTest {
        val calls = mutableListOf<List<String>>()
        val r = payload.redeem("Pixel 9") { url, secret, name ->
            calls += listOf(url, secret, name)
            PairClaimResult(host = HostIdentity(hostId = HID), deviceToken = "minted", name = name)
        }
        val ok = assertIs<ClaimRedemption.Redeemed>(r)
        assertEquals("https://h-$HID.relay.supermux.dev", ok.url)
        assertEquals("minted", ok.result.deviceToken)
        assertEquals(listOf(listOf("https://h-$HID.relay.supermux.dev", "s3cret", "Pixel 9")), calls)
    }

    @Test fun redeem_uses_the_direct_url_when_there_is_no_relay() = runTest {
        val direct = payload.copy(relayUrl = null, directUrl = "http://192.168.1.20:9898")
        var at: String? = null
        val r = direct.redeem("x") { url, _, _ -> at = url; PairClaimResult(HostIdentity(hostId = HID), "tok") }
        assertIs<ClaimRedemption.Redeemed>(r)
        assertEquals("http://192.168.1.20:9898", at)
    }

    @Test fun redeem_refuses_a_token_from_another_host_or_from_no_named_host() = runTest {
        val other = payload.redeem("x") { _, _, _ -> PairClaimResult(HostIdentity(hostId = "zzzzzzzzzzzzzzzzzzzzzzzzzz"), "tok") }
        assertTrue("mismatch" in assertIs<ClaimRedemption.Failed>(other).message)
        val anonymous = payload.redeem("x") { _, _, _ -> PairClaimResult(host = null, deviceToken = "tok") }
        assertIs<ClaimRedemption.Failed>(anonymous)
    }

    @Test fun redeem_fails_on_a_rejected_claim_or_an_empty_token() = runTest {
        assertIs<ClaimRedemption.Failed>(payload.redeem("x") { _, _, _ -> null })
        assertIs<ClaimRedemption.Failed>(payload.redeem("x") { _, _, _ -> PairClaimResult(HostIdentity(hostId = HID), "") })
    }

    @Test fun redeem_sends_nothing_for_a_code_with_no_address() = runTest {
        val nowhere: PairingPayload = payload.copy(relayUrl = null, directUrl = null)
        assertIs<ClaimRedemption.Failed>(nowhere.redeem("x") { _, _, _ -> error("must not be sent") })
    }
}
