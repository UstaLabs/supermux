package dev.supermux.webview.engine.android

import dev.supermux.webview.engine.android.DocumentGate.Completion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentGateTest {
    private val t1 = "t1t1t1t1t1t1t1t1t1t1t1t1"
    private val t2 = "t2t2t2t2t2t2t2t2t2t2t2t2"
    private val t3 = "t3t3t3t3t3t3t3t3t3t3t3t3"

    /** A boundary whose marker has run and whose own navigation [ours] committed. */
    private fun DocumentGate.loaded(token: String, ours: Any = Any(), sameDocument: Boolean = false) {
        markerRan(boundary(token))
        assertTrue(navigationStarted(ours, byPage = false))
        assertEquals(Completion.Ours(sameDocument), navigationCompleted(ours, byPage = false, didCommit = true, sameDocument = sameDocument))
    }

    @Test fun nothingIsHeardUntilTheMarkerRunsAndOursCommits() {
        val gate = DocumentGate()
        val epoch = gate.boundary(t1)
        val stale = Any()
        assertFalse(gate.navigationStarted(stale, byPage = false), "queued before the boundary")
        assertEquals(Completion.Ignore, gate.navigationCompleted(stale, byPage = false, didCommit = true, sameDocument = false))
        assertFalse(gate.afterMarker)
        gate.markerRan(epoch)
        assertTrue(gate.afterMarker)
        assertFalse(gate.acceptsDocumentEvents)
        val ours = Any()
        assertTrue(gate.navigationStarted(ours, byPage = false))
        assertFalse(gate.acceptsMessage(t1), "not before ours commits")
        assertEquals(Completion.Ours(false), gate.navigationCompleted(ours, byPage = false, didCommit = true, sameDocument = false))
        assertTrue(gate.acceptsDocumentEvents)
        assertTrue(gate.acceptsMessage(t1))
        assertFalse(gate.acceptsMessage(t2))
    }

    @Test fun aMarkerOfAnEarlierBoundaryChangesNothing() {
        val gate = DocumentGate()
        val first = gate.boundary(t1)
        val second = gate.boundary(t2)
        gate.markerRan(first)
        assertFalse(gate.afterMarker)
        gate.markerRan(second)
        assertTrue(gate.afterMarker)
    }

    @Test fun thePagesOwnNavigationsAreNeverOurs() {
        val gate = DocumentGate()
        gate.markerRan(gate.boundary(t1))
        val link = Any()
        assertFalse(gate.navigationStarted(link, byPage = true))
        assertEquals(Completion.Ignore, gate.navigationCompleted(link, byPage = true, didCommit = true, sameDocument = false))
        val ours = Any()
        assertTrue(gate.navigationStarted(ours, byPage = false))
        assertEquals(Completion.Ours(false), gate.navigationCompleted(ours, byPage = false, didCommit = true, sameDocument = false))
    }

    @Test fun afterOursEveryCommitInTheEpochIsHeard() {
        val gate = DocumentGate()
        gate.loaded(t1)
        val link = Any()
        assertFalse(gate.navigationStarted(link, byPage = true))
        assertEquals(Completion.Later(false), gate.navigationCompleted(link, byPage = true, didCommit = true, sameDocument = false))
        assertEquals(Completion.Later(true), gate.navigationCompleted(Any(), byPage = true, didCommit = true, sameDocument = true))
        assertEquals(Completion.Ignore, gate.navigationCompleted(Any(), byPage = true, didCommit = false, sameDocument = false))
        assertTrue(gate.acceptsMessage(t1))
    }

    @Test fun oursThatDoesNotCommitLeavesTheGateShut() {
        val gate = DocumentGate()
        gate.markerRan(gate.boundary(t1))
        val ours = Any()
        gate.navigationStarted(ours, byPage = false)
        assertEquals(Completion.OursFailed, gate.navigationCompleted(ours, byPage = false, didCommit = false, sameDocument = false))
        assertFalse(gate.acceptsDocumentEvents)
        assertEquals(Completion.Ignore, gate.navigationCompleted(Any(), byPage = true, didCommit = true, sameDocument = false))
    }

    @Test fun anEarlierLoadThisOneAbortedIsNotOurs() {
        val gate = DocumentGate()
        gate.markerRan(gate.boundary(t1))
        assertEquals(Completion.Ignore, gate.navigationCompleted(Any(), byPage = false, didCommit = false, sameDocument = false))
        val ours = Any()
        assertTrue(gate.navigationStarted(ours, byPage = false))
        assertEquals(Completion.Ours(false), gate.navigationCompleted(ours, byPage = false, didCommit = true, sameDocument = false))
    }

    @Test fun aLoadFoldedIntoOneAlreadyUnderWayIsOursWhenItCompletes() {
        val gate = DocumentGate()
        gate.markerRan(gate.boundary(t1))
        assertEquals(Completion.Ours(false), gate.navigationCompleted(Any(), byPage = false, didCommit = true, sameDocument = false))
    }

    @Test fun aSameDocumentLoadKeepsThePreviousTokenUntilTheNextDocument() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.loaded(t2, sameDocument = true)
        assertTrue(gate.acceptsMessage(t1), "the same document, still signing with its token")
        assertTrue(gate.acceptsMessage(t2))
        gate.loaded(t3)
        assertFalse(gate.acceptsMessage(t1))
        assertFalse(gate.acceptsMessage(t2))
        assertTrue(gate.acceptsMessage(t3))
    }

    @Test fun aBoundaryShutsTheGateAtOnce() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.boundary(t2)
        assertFalse(gate.acceptsDocumentEvents)
        assertFalse(gate.acceptsMessage(t1))
        assertFalse(gate.acceptsMessage(t2))
        assertEquals(2, gate.epoch)
    }
}
