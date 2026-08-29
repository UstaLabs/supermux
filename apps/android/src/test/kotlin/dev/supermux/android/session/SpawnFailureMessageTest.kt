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

    @Test fun jsonErrorFromRealBrokerMessageIsShownVerbatim() {
        val body = """{"error":"tmux created window 'x' but did not report its id"}"""
        val msg = "BrokerApi request unavailable: HTTP 500 $body"
        assertEquals(
            "tmux created window 'x' but did not report its id",
            spawnFailureMessage(CancellationException(msg)),
        )
    }

    @Test fun httpStatusWithoutJsonErrorIsSurfaced() {
        assertEquals(
            "Broker refused to start the session (HTTP 500)",
            spawnFailureMessage(CancellationException("BrokerApi request unavailable: HTTP 500 Internal Server Error")),
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

    @Test fun remapRethrowsBareCancellationUnchanged() {
        val bare = CancellationException()
        val thrown = assertFailsWith<CancellationException> { remapSpawnFailure(bare) }
        assertEquals(bare, thrown)
        val blank = CancellationException("")
        val thrownBlank = assertFailsWith<CancellationException> { remapSpawnFailure(blank) }
        assertEquals(blank, thrownBlank)
    }

    @Test fun remapTurnsUnavailableIntoIllegalState() {
        val e = assertFailsWith<IllegalStateException> {
            remapSpawnFailure(CancellationException("BrokerApi request unavailable"))
        }
        assertEquals("Broker refused to start the session", e.message)
    }

    @Test fun remapTurnsHttpUnavailableIntoIllegalState() {
        val raw = "BrokerApi request unavailable: HTTP 409 {\"error\":\"busy\"}"
        val e = assertFailsWith<IllegalStateException> {
            remapSpawnFailure(CancellationException(raw))
        }
        assertEquals("busy", e.message)
    }

    @Test fun networkFailureBranchBecomesRefusal() {
        assertEquals(
            "Broker refused to start the session",
            spawnFailureMessage(CancellationException("BrokerApi request unavailable: Connection refused")),
        )
    }
}
