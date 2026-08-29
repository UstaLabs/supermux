package dev.supermux.android.session

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpawnFailureMessageTest {
    @Test fun genericUnavailableBecomesRefusal() {
        assertEquals(
            "Broker refused to start the session",
            spawnFailureMessage(CancellationException("BrokerApi request unavailable")),
        )
    }

    @Test fun httpCodeInMessageIsSurfaced() {
        assertEquals(
            "Broker refused to start the session (HTTP 500)",
            spawnFailureMessage(CancellationException("HTTP 500")),
        )
    }

    @Test fun httpCodeInCauseIsSurfaced() {
        val t = IllegalStateException("wrap", CancellationException("spawn failed: HTTP 409"))
        assertEquals("Broker refused to start the session (HTTP 409)", spawnFailureMessage(t))
    }

    @Test fun otherMessagesPassThrough() {
        assertEquals("Need a workdir and a handoff message", spawnFailureMessage(IllegalArgumentException("Need a workdir and a handoff message")))
    }

    @Test fun remapKeepsRealCancellation() {
        assertFailsWith<CancellationException> {
            remapSpawnFailure(CancellationException("standalone coroutine was cancelled"))
        }
    }

    @Test fun remapTurnsUnavailableIntoIllegalState() {
        val e = assertFailsWith<IllegalStateException> {
            remapSpawnFailure(CancellationException("BrokerApi request unavailable"))
        }
        assertEquals("Broker refused to start the session", e.message)
    }
}
