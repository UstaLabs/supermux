package dev.supermux.pairing

import dev.supermux.net.HostIdentity
import dev.supermux.net.PairClaimResult
import dev.supermux.net.PairUrl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic tests for [PairingState] — no live network. The `probeOverride` seam
 * (mirroring HostStore's `sendFrameOverride`) lets us drive the Validating →
 * Confirm/Error transition deterministically; the `UnconfinedTestDispatcher` runs the
 * launched coroutine synchronously so assertions can read `state.value` immediately.
 *
 * Moved from `:desktop` in cluster G6 with the class under test. The temp-file
 * `DesktopTokenStore` became [InMemoryPairingTokenStore] — the store contract the state
 * machine actually depends on is the four members of [PairingTokenStore], and each host's
 * real store has its own tests.
 */
/** A well-formed 26-char base32 hostId (spec §3.1). */
private const val HID = "amsfen6cjgpd7pjqw6nimlahvy"

/** The desktop host wizard's QR, exactly as it encodes it (HostWizard.kt `encodePairingPayload`). */
private const val CLAIM =
    """{"v":1,"action":"pair","hostId":"$HID","name":"studio","relayUrl":"https://h-$HID.relay.supermux.dev",""" +
        """"directUrl":"http://127.0.0.1:9898","claimSecret":"s3cret"}"""

@OptIn(ExperimentalCoroutinesApi::class)
class PairingStateTest {
    private fun tempStore(): PairingTokenStore = InMemoryPairingTokenStore()

    private fun state(probe: (suspend (PairUrl) -> String?)? = null) = PairingState(
        store = tempStore(),
        scope = TestScope(UnconfinedTestDispatcher()),
        probeOverride = probe,
    )

    @Test fun garbage_input_is_an_error_without_touching_the_network() {
        val s = state(probe = { error("must not be called") })
        s.validate("not a url and no fallback base")
        assertIs<PairingUiState.Error>(s.state.value)
    }

    @Test fun blank_input_is_an_error() {
        val s = state(probe = { error("must not be called") })
        s.validate("   ")
        assertIs<PairingUiState.Error>(s.state.value)
    }

    @Test fun bare_token_without_fallback_base_is_an_error() {
        // PairUrl.parse returns null for a bare token with no fallback base — mirrors the
        // Android ViewModel test surface (no PairUrl to validate against the broker at all).
        val s = state(probe = { error("must not be called") })
        s.validate("just-a-token", fallbackBase = null)
        assertIs<PairingUiState.Error>(s.state.value)
    }

    @Test fun valid_link_probes_and_transitions_to_confirm() {
        val s = state(probe = { "my-laptop" })
        s.validate("https://host:9898/pair?t=abc123")
        val confirm = assertIs<PairingUiState.Confirm>(s.state.value)
        assertEquals("wss://host:9898", confirm.pair.baseUrl)
        assertEquals("abc123", confirm.pair.token)
        assertEquals("my-laptop", confirm.deviceName)
    }

    @Test fun failed_probe_transitions_to_error() {
        val s = state(probe = { null })
        s.validate("https://host:9898/pair?t=abc123")
        val err = assertIs<PairingUiState.Error>(s.state.value)
        assertTrue(err.message.isNotBlank())
    }

    @Test fun bare_token_with_fallback_base_reaches_the_probe_seam() {
        val s = state(probe = { p -> "resolved-${p.token}" })
        s.validate("manual-token", fallbackBase = "ws://10.0.2.2:9898")
        val confirm = assertIs<PairingUiState.Confirm>(s.state.value)
        assertEquals("ws://10.0.2.2:9898", confirm.pair.baseUrl)
        assertEquals("resolved-manual-token", confirm.deviceName)
    }

    @Test fun reset_error_clears_back_to_idle() {
        val s = state(probe = { null })
        s.validate("https://host:9898/pair?t=abc123")
        assertIs<PairingUiState.Error>(s.state.value)
        s.resetError()
        assertIs<PairingUiState.Idle>(s.state.value)
    }

    @Test fun reset_error_is_a_no_op_outside_error_state() {
        val s = state()
        assertIs<PairingUiState.Idle>(s.state.value)
        s.resetError()
        assertIs<PairingUiState.Idle>(s.state.value)
    }

    @Test fun cancel_confirm_returns_to_idle() {
        val s = state(probe = { "dev" })
        s.validate("https://host:9898/pair?t=abc123")
        assertIs<PairingUiState.Confirm>(s.state.value)
        s.cancelConfirm()
        assertIs<PairingUiState.Idle>(s.state.value)
    }

    @Test fun confirm_persist_saves_base_and_token_and_marks_paired() {
        val store = tempStore()
        val s = PairingState(store, TestScope(UnconfinedTestDispatcher()), probeOverride = { "dev" })
        val pair = PairUrl("wss://host:9898", "abc123")
        s.confirmPersist(pair)
        assertIs<PairingUiState.Paired>(s.state.value)
        assertEquals("abc123", store.load())
        assertEquals("wss://host:9898", store.loadBaseUrl())
    }

    @Test fun fallback_base_url_reads_from_the_store() {
        val store = tempStore()
        store.saveBaseUrl("ws://prior:9898")
        val s = PairingState(store, TestScope(UnconfinedTestDispatcher()))
        assertEquals("ws://prior:9898", s.fallbackBaseUrl())
    }

    @Test fun fallback_base_url_is_null_when_unset() {
        val s = state()
        assertNull(s.fallbackBaseUrl())
    }

    // ── close() semantics ────────────────────────────────────────────────────────────
    // close() only releases the internal probe HttpClient (built lazily, and ktor's close()
    // is idempotent); it does NOT tear down the caller-owned scope or reset the state
    // machine. So:
    //  - calling it twice is safe,
    //  - calling it before any validate is safe,
    //  - validate-after-close still works when the probe seam bypasses the http client
    //    (the injected probeOverride never touches it). Only a real network probe after
    //    close() would fail — and that failure surfaces as the normal Error state.

    @Test fun close_is_idempotent() {
        val s = state()
        s.close()
        s.close()
        assertIs<PairingUiState.Idle>(s.state.value)
    }

    @Test fun close_before_any_validate_is_safe() {
        val s = state(probe = { "dev" })
        s.close()
        assertIs<PairingUiState.Idle>(s.state.value)
    }

    @Test fun validate_after_close_still_works_via_the_probe_seam() {
        val s = state(probe = { "dev" })
        s.close()
        s.validate("https://host:9898/pair?t=abc123")
        val confirm = assertIs<PairingUiState.Confirm>(s.state.value)
        assertEquals("dev", confirm.deviceName)
    }

    @Test fun parse_failure_after_close_still_reports_error() {
        val s = state(probe = { error("must not be called") })
        s.close()
        s.validate("garbage with no token")
        assertIs<PairingUiState.Error>(s.state.value)
    }

    // ── a claim: the desktop host's QR ──────────────────────────────────────────────────

    @Test fun a_desktop_claim_qr_is_read_not_rejected() {
        // Card #59: the first-run connect page fed this to the link parser alone and answered
        // "Couldn't read a token from that…" before any network call.
        val s = state(probe = { error("a claim carries no token to probe") })
        s.validate(CLAIM, fallbackBase = null)
        assertIs<PairingUiState.Confirm>(s.state.value)
    }

    private fun claimState(
        store: PairingTokenStore = tempStore(),
        claim: suspend (url: String, secret: String, deviceName: String) -> PairClaimResult?,
    ) = PairingState(
        store = store,
        scope = TestScope(UnconfinedTestDispatcher()),
        probeOverride = { error("a claim carries no token to probe") },
        deviceName = "Pixel 9",
        claimOverride = claim,
    )

    private fun minted(name: String = "Pixel 9") =
        PairClaimResult(host = HostIdentity(hostId = HID, name = "studio"), deviceToken = "minted-token", name = name)

    @Test fun a_claim_waits_for_the_tofu_confirm_showing_where_and_as_whom() {
        val s = claimState { _, _, _ -> error("nothing is redeemed before the TOFU confirm") }
        s.validate(CLAIM, fallbackBase = null)
        val confirm = assertIs<PairingUiState.Confirm>(s.state.value)
        assertEquals(HID, confirm.claim?.hostId)
        assertEquals("wss://h-$HID.relay.supermux.dev", confirm.pair.baseUrl)
        assertEquals("Pixel 9", confirm.deviceName)
    }

    @Test fun a_claim_is_redeemed_on_confirm_and_the_minted_token_persisted() {
        val store = tempStore()
        val calls = mutableListOf<List<String>>()
        val s = claimState(store) { url, secret, name -> calls += listOf(url, secret, name); minted(name) }
        s.validate(CLAIM, fallbackBase = null)
        assertNull(store.load())
        s.confirm()
        assertIs<PairingUiState.Paired>(s.state.value)
        assertEquals(listOf(listOf("https://h-$HID.relay.supermux.dev", "s3cret", "Pixel 9")), calls)
        assertEquals("minted-token", store.load())
        assertEquals("wss://h-$HID.relay.supermux.dev", store.loadBaseUrl())
    }

    @Test fun a_dismissed_claim_is_never_redeemed() {
        val s = claimState { _, _, _ -> error("a dismissed claim must stay usable") }
        s.validate(CLAIM, fallbackBase = null)
        s.cancelConfirm()
        assertIs<PairingUiState.Idle>(s.state.value)
    }

    @Test fun a_claim_answered_by_another_host_persists_nothing() {
        val store = tempStore()
        val s = claimState(store) { _, _, _ ->
            PairClaimResult(host = HostIdentity(hostId = "zzzzzzzzzzzzzzzzzzzzzzzzzz"), deviceToken = "tok")
        }
        s.validate(CLAIM, fallbackBase = null)
        s.confirm()
        val err = assertIs<PairingUiState.Error>(s.state.value)
        assertTrue("mismatch" in err.message)
        assertNull(store.load())
        assertNull(store.loadBaseUrl())
    }

    @Test fun an_expired_or_used_claim_is_an_error_and_persists_nothing() {
        val store = tempStore()
        val s = claimState(store) { _, _, _ -> null }
        s.validate(CLAIM, fallbackBase = null)
        s.confirm()
        assertIs<PairingUiState.Error>(s.state.value)
        assertNull(store.load())
    }

    @Test fun a_claim_without_a_relay_is_redeemed_on_its_direct_url() {
        val direct = CLAIM.replace(""""relayUrl":"https://h-$HID.relay.supermux.dev",""", "")
            .replace("http://127.0.0.1:9898", "http://192.168.1.20:9898")
        val store = tempStore()
        var at: String? = null
        val s = claimState(store) { url, _, name -> at = url; minted(name) }
        s.validate(direct, fallbackBase = null)
        assertEquals("ws://192.168.1.20:9898", assertIs<PairingUiState.Confirm>(s.state.value).pair.baseUrl)
        s.confirm()
        assertEquals("http://192.168.1.20:9898", at)
        assertEquals("ws://192.168.1.20:9898", store.loadBaseUrl())
    }

    @Test fun a_claim_is_not_sent_as_a_token_to_a_broker_already_known() {
        val store = tempStore().apply { saveBaseUrl("ws://prior:9898") }
        val s = claimState(store) { _, _, _ -> null }
        s.validate(CLAIM) // the default fallback is the stored base: a bare token would go to it
        assertIs<PairingUiState.Confirm>(s.state.value)
    }

    @Test fun confirm_still_persists_a_links_own_token() {
        val store = tempStore()
        val s = PairingState(store, TestScope(UnconfinedTestDispatcher()), probeOverride = { "dev" })
        s.validate("https://host:9898/pair?t=abc123")
        s.confirm()
        assertIs<PairingUiState.Paired>(s.state.value)
        assertEquals("abc123", store.load())
        assertEquals("wss://host:9898", store.loadBaseUrl())
    }

    @Test fun confirm_with_nothing_pending_does_nothing() {
        val store = tempStore()
        val s = PairingState(store, TestScope(UnconfinedTestDispatcher()))
        s.confirm()
        assertIs<PairingUiState.Idle>(s.state.value)
        assertNull(store.load())
    }
}
