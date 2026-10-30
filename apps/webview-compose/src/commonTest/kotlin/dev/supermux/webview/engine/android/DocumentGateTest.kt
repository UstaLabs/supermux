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

    @Test fun twoFragmentOnlyLoadsInARowKeepTheDocumentsOwnToken() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.loaded(t2, sameDocument = true)
        gate.loaded(t3, sameDocument = true)
        assertTrue(gate.acceptsMessage(t1), "the same document, still signing with the token it began with")
        assertTrue(gate.acceptsMessage(t3))
    }

    @Test fun aDocumentThePageNavigatesToItselfSignsWithTheEpochsToken() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.loaded(t2, sameDocument = true)
        assertEquals(Completion.Later(false), gate.navigationCompleted(Any(), byPage = true, didCommit = true, sameDocument = false))
        assertTrue(gate.acceptsMessage(t2), "a document that began after the boundary")
        assertFalse(gate.acceptsMessage(t1), "the document that signed with it is gone")
        gate.loaded(t3, sameDocument = true)
        assertTrue(gate.acceptsMessage(t2), "carried with the document that signs with it")
        assertFalse(gate.acceptsMessage(t1))
    }

    @Test fun aSynchronousCallbackIsHeardOnceOurNavigationCommittedInTheBrowser() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.boundary(t2)
        assertFalse(gate.acceptsSynchronousCallbacks, "the earlier document is shut out at the boundary")
        val ours = Any()
        gate.liveNavigationStarted(ours, byPage = false)
        assertFalse(gate.acceptsSynchronousCallbacks, "not before ours commits")
        gate.liveNavigationCompleted(ours, byPage = false, didCommit = true)
        assertTrue(gate.acceptsSynchronousCallbacks, "the browser's current document is ours")
        assertFalse(gate.acceptsDocumentEvents, "what was posted before the commit still drains")
    }

    @Test fun neitherTheEarlierDocumentsNavigationsNorTheLoadOursReplacedOpenIt() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.boundary(t2)
        gate.liveNavigationCompleted(Any(), byPage = false, didCommit = false)
        val ours = Any()
        gate.liveNavigationStarted(ours, byPage = false)
        gate.liveNavigationStarted(Any(), byPage = true)
        gate.liveNavigationCompleted(Any(), byPage = true, didCommit = true)
        assertFalse(gate.acceptsSynchronousCallbacks)
        gate.liveNavigationCompleted(ours, byPage = false, didCommit = true)
        assertTrue(gate.acceptsSynchronousCallbacks)
    }

    @Test fun oursThatDoesNotCommitKeepsSynchronousCallbacksShut() {
        val gate = DocumentGate()
        gate.boundary(t1)
        val ours = Any()
        gate.liveNavigationStarted(ours, byPage = false)
        gate.liveNavigationCompleted(ours, byPage = false, didCommit = false)
        gate.liveNavigationCompleted(Any(), byPage = false, didCommit = true)
        gate.liveNavigationCompleted(Any(), byPage = true, didCommit = true)
        assertFalse(gate.acceptsSynchronousCallbacks)
    }

    @Test fun aLoadFoldedIntoOneUnderWayOpensSynchronousCallbacksWhenItCommits() {
        val gate = DocumentGate()
        gate.boundary(t1)
        gate.liveNavigationCompleted(Any(), byPage = false, didCommit = true)
        assertTrue(gate.acceptsSynchronousCallbacks)
    }

    @Test fun whatThePostedOrderHearsTheSynchronousOneHearsTooUntilTheNextBoundary() {
        val gate = DocumentGate()
        gate.loaded(t1)
        assertTrue(gate.acceptsSynchronousCallbacks, "ours committed long ago")
        gate.boundary(t2)
        assertFalse(gate.acceptsSynchronousCallbacks)
    }

    /** Our earlier navigation's commit in the browser opens nothing in the next epoch: its own commit has to. */
    @Test fun aBoundaryShutsWhatALiveCommitOpened() {
        val gate = DocumentGate()
        gate.boundary(t1)
        val ours = Any()
        gate.liveNavigationStarted(ours, byPage = false)
        gate.liveNavigationCompleted(ours, byPage = false, didCommit = true)
        assertTrue(gate.acceptsSynchronousCallbacks)
        gate.boundary(t2)
        assertFalse(gate.acceptsSynchronousCallbacks, "the earlier epoch's commit opened this one")
    }

    /** The earlier document's own navigation, started before ours, never takes ours's place, even when it commits. */
    @Test fun aPageNavigationThatStartsBeforeOursIsNeverOurs() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.boundary(t2)
        val link = Any()
        gate.liveNavigationStarted(link, byPage = true)
        val ours = Any()
        gate.liveNavigationStarted(ours, byPage = false)
        gate.liveNavigationCompleted(link, byPage = true, didCommit = true)
        assertFalse(gate.acceptsSynchronousCallbacks, "the earlier document's navigation opened it")
        gate.liveNavigationCompleted(ours, byPage = false, didCommit = true)
        assertTrue(gate.acceptsSynchronousCallbacks)
    }

    /** With none of ours started, only an app-initiated commit can be ours (a folded load): never one the page started. */
    @Test fun aPageCommitWithNoneOfOursStartedOpensNothing() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.boundary(t2)
        gate.liveNavigationCompleted(Any(), byPage = true, didCommit = true)
        assertFalse(gate.acceptsSynchronousCallbacks)
    }

    /** A server redirect is the app's while a navigation it started loads: ours, before its commit, and back or forward. */
    @Test fun whatLoadsNowIsTheAppsOnlyWhenTheAppStartedIt() {
        val gate = DocumentGate()
        assertFalse(gate.appNavigationLoading, "nothing loads")
        gate.boundary(t1)
        val ours = Any()
        gate.liveNavigationStarted(ours, byPage = false)
        assertTrue(gate.appNavigationLoading, "our load, before it commits: its redirects aren't the client's")
        gate.liveNavigationCompleted(ours, byPage = false, didCommit = true)
        assertFalse(gate.appNavigationLoading, "it committed")
        val link = Any()
        gate.liveNavigationStarted(link, byPage = true)
        assertFalse(gate.appNavigationLoading, "the page's own navigation: its redirects are the client's")
        gate.liveNavigationCompleted(link, byPage = true, didCommit = false)
        val back = Any()
        gate.liveNavigationStarted(back, byPage = false)
        assertTrue(gate.appNavigationLoading, "back, after ours committed")
        gate.liveNavigationStarted(Any(), byPage = true, sameDocument = true)
        assertTrue(gate.appNavigationLoading, "a fragment the page changes meanwhile loads no document")
        val replacing = Any()
        gate.liveNavigationStarted(replacing, byPage = true)
        assertFalse(gate.appNavigationLoading, "the page's navigation replaced it")
        gate.liveNavigationCompleted(back, byPage = false, didCommit = false)
        assertFalse(gate.appNavigationLoading, "what loads now is still the page's")
    }

    /**
     * A load that failed leaves the document shown as it was: the next boundary's same-document "ours" (back to an entry
     * of that document) keeps the token that document began with, not the failed load's, which no document has.
     */
    @Test fun aDocumentKeptAcrossAFailedLoadKeepsItsToken() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.markerRan(gate.boundary(t2))
        val failed = Any()
        assertTrue(gate.navigationStarted(failed, byPage = false))
        assertEquals(Completion.OursFailed, gate.navigationCompleted(failed, byPage = false, didCommit = false, sameDocument = false))
        gate.loaded(t3, sameDocument = true)
        assertTrue(gate.acceptsMessage(t1), "the document shown signs with the token it began with")
        assertFalse(gate.acceptsMessage(t2), "no document signs with the failed load's")
    }

    /** So does a load that a second one replaced before it committed. */
    @Test fun aDocumentKeptAcrossALoadThatNeverCommittedKeepsItsToken() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.markerRan(gate.boundary(t2))
        assertTrue(gate.navigationStarted(Any(), byPage = false))
        gate.loaded(t3, sameDocument = true)
        assertTrue(gate.acceptsMessage(t1))
        assertFalse(gate.acceptsMessage(t2))
    }

    /** A boundary right after ours committed in the browser, its posted completion still to run: the new document's. */
    @Test fun aBoundaryRightAfterALiveCommitKnowsWhichDocumentIsShown() {
        val gate = DocumentGate()
        gate.loaded(t1)
        gate.boundary(t2)
        val ours = Any()
        gate.liveNavigationStarted(ours, byPage = false)
        gate.liveNavigationCompleted(ours, byPage = false, didCommit = true)
        gate.loaded(t3, sameDocument = true)
        assertTrue(gate.acceptsMessage(t2), "the new document signs with its epoch's token")
        assertFalse(gate.acceptsMessage(t1))

        val kept = DocumentGate()
        kept.loaded(t1)
        kept.boundary(t2)
        val fragment = Any()
        kept.liveNavigationStarted(fragment, byPage = false, sameDocument = true)
        kept.liveNavigationCompleted(fragment, byPage = false, didCommit = true, sameDocument = true)
        kept.loaded(t3, sameDocument = true)
        assertTrue(kept.acceptsMessage(t1), "two fragment-only loads: still the first document")
        assertFalse(kept.acceptsMessage(t2))
    }

    /** Whether the document shown is ours yet: not while ours loads, nor once it failed; from its commit on, either way. */
    @Test fun oursCommittedSaysWhetherTheDocumentShownIsOurs() {
        val gate = DocumentGate()
        assertFalse(gate.oursCommitted, "nothing loaded yet")
        gate.loaded(t1)
        assertTrue(gate.oursCommitted)
        val epoch = gate.boundary(t2)
        assertFalse(gate.oursCommitted, "ours still loads")
        val failed = Any()
        gate.liveNavigationStarted(failed, byPage = false)
        gate.liveNavigationCompleted(failed, byPage = false, didCommit = false)
        gate.markerRan(epoch)
        assertTrue(gate.navigationStarted(failed, byPage = false))
        assertEquals(Completion.OursFailed, gate.navigationCompleted(failed, byPage = false, didCommit = false, sameDocument = false))
        assertFalse(gate.oursCommitted, "ours failed: the document shown is the one before it")
        gate.boundary(t3)
        val next = Any()
        gate.liveNavigationStarted(next, byPage = false)
        gate.liveNavigationCompleted(next, byPage = false, didCommit = true)
        assertTrue(gate.oursCommitted, "committed in the browser, before its posted completion")
    }

    /**
     * Load progress is ours from the marker on (our navigation's, then its documents'), but not once ours failed: the
     * document shown then is the one before it, whose progress isn't the new content's (review, minor).
     */
    @Test fun progressIsHeardForOurLoadAndItsDocumentsOnly() {
        val gate = DocumentGate()
        val epoch = gate.boundary(t1)
        assertFalse(gate.acceptsProgress, "posted before the boundary")
        gate.markerRan(epoch)
        assertTrue(gate.acceptsProgress, "our load")
        val ours = Any()
        assertTrue(gate.navigationStarted(ours, byPage = false))
        assertEquals(Completion.OursFailed, gate.navigationCompleted(ours, byPage = false, didCommit = false, sameDocument = false))
        assertFalse(gate.acceptsProgress, "ours failed: the document shown is an earlier one")
        gate.loaded(t2)
        assertTrue(gate.acceptsProgress, "the next load's document")
        gate.navigationCompleted(Any(), byPage = true, didCommit = true, sameDocument = false)
        assertTrue(gate.acceptsProgress, "a document the page went to")
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
