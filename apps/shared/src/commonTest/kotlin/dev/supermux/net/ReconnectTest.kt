package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconnectTest {
    @Test fun backoff_grows_exponentially_and_caps() {
        val p = ReconnectPolicy(baseMs = 500, maxMs = 8000)
        assertEquals(500, p.delayForAttempt(0))
        assertEquals(1000, p.delayForAttempt(1))
        assertEquals(2000, p.delayForAttempt(2))
        assertEquals(8000, p.delayForAttempt(10)) // capped
    }

    @Test fun reset_returns_to_base() {
        val p = ReconnectPolicy(baseMs = 500, maxMs = 8000)
        p.delayForAttempt(5)
        assertEquals(500, p.delayForAttempt(0))
    }

    @Test fun every_connect_requires_a_fresh_snapshot() {
        val state = ConnectionSyncState()
        assertTrue(!state.synced)
        state.onFrame(isSnapshot = false); assertTrue(!state.synced)
        state.onFrame(isSnapshot = true); assertTrue(state.synced)
        state.onDisconnect(); assertTrue(!state.synced) // reconnect must re-snapshot
    }
}
