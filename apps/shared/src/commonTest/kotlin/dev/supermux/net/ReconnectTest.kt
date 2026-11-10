package dev.supermux.net

import kotlinx.coroutines.async
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    @Test fun retry_now_ends_the_backoff_wait_at_once() = runTest {
        // Card #121: the offline strip's Retry dials without waiting out the backoff.
        val wait = ReconnectWait()
        val waiting = async { wait.await(8_000) }
        runCurrent()
        wait.retryNow()
        waiting.await()
        assertEquals(0, currentTime)
    }

    @Test fun without_a_retry_the_wait_runs_its_full_backoff() = runTest {
        ReconnectWait().await(8_000)
        assertEquals(8_000, currentTime)
    }
}
