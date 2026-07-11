package dev.supermux.desktop.pairing

import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.net.PairUrl
import java.nio.file.Files
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
 * (mirroring DesktopAppState's `sendFrameOverride`) lets us drive the Validating →
 * Confirm/Error transition deterministically; the `UnconfinedTestDispatcher` runs the
 * launched coroutine synchronously so assertions can read `state.value` immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingStateTest {
    private fun tempStore(): DesktopTokenStore =
        DesktopTokenStore(Files.createTempDirectory("smx-pairing").resolve("auth.json"))

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
    // close() only releases the internal probe HttpClient (ktor's close() is idempotent);
    // it does NOT tear down the caller-owned scope or reset the state machine. So:
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
}
