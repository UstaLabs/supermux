package dev.supermux.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadCenterTest {
    private class FakeSource(var result: DownloadedFile? = file("a.pdf")) : DownloadSource {
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        var cancels = 0
        var progress: ((Long, Long?) -> Unit)? = null

        override suspend fun fetch(progress: (Long, Long?) -> Unit): DownloadedFile? {
            fetches++
            this.progress = progress
            gate.await()
            return result
        }

        override fun cancel() {
            cancels++
        }
    }

    companion object {
        fun file(name: String) = DownloadedFile(name, "application/pdf", 3, "/cache/$name")
    }

    // The test's own scope, so a job left running fails the test instead of hiding in the background.
    private fun TestScope.center() = DownloadCenter(this)

    @Test fun startListsARunningItemNewestFirst() = runTest {
        val c = center()
        c.start("k1", "a.pdf", "application/pdf", DownloadPurpose.File, FakeSource())
        c.start("k2", "b.pdf", "application/pdf", DownloadPurpose.File, FakeSource())
        runCurrent()
        assertEquals(listOf("k2", "k1"), c.items.value.map { it.key })
        assertTrue(c.items.value.all { it.state is DownloadState.Running })
        c.close()
    }

    @Test fun progressIsPublished() = runTest {
        val c = center()
        val s = FakeSource()
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        runCurrent()
        s.progress!!(2, 4)
        assertEquals(DownloadState.Running(2, 4), c.items.value.single().state)
        c.close()
    }

    @Test fun aClaimedFinishIsTheScreensAndEmitsNoNotice() = runTest {
        val c = center()
        val s = FakeSource()
        val notices = mutableListOf<String>()
        backgroundScope.launch { c.unclaimedCompletions.collect { notices += it.key } }
        val claim = c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        val got = async {
            try {
                claim.await()
            } finally {
                claim.release()
            }
        }
        runCurrent()
        s.gate.complete(Unit)
        assertEquals("/cache/a.pdf", got.await()?.path)
        runCurrent()
        assertEquals(emptyList(), notices)
    }

    @Test fun aFinishAfterTheScreenLeftEmitsOneNotice() = runTest {
        val c = center()
        val s = FakeSource()
        val notices = mutableListOf<String>()
        backgroundScope.launch { c.unclaimedCompletions.collect { notices += it.key } }
        val claim = c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        claim.release()
        runCurrent()
        s.gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("k"), notices)
        assertIs<DownloadState.Done>(c.items.value.single().state)
    }

    @Test fun aFinishBeforeTheScreenWaitsIsStillClaimed() = runTest {
        val c = center()
        val s = FakeSource().apply { gate.complete(Unit) }
        val notices = mutableListOf<String>()
        backgroundScope.launch { c.unclaimedCompletions.collect { notices += it.key } }
        val claim = c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        runCurrent()
        assertNotNull(claim.await())
        claim.release()
        runCurrent()
        assertEquals(emptyList(), notices)
    }

    @Test fun startingARunningKeyJoinsIt() = runTest {
        val c = center()
        val s = FakeSource()
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        runCurrent()
        assertEquals(1, s.fetches)
        c.close()
    }

    @Test fun startingAFailedKeyStartsItAgain() = runTest {
        val c = center()
        val s = FakeSource(result = null).apply { gate.complete(Unit) }
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        runCurrent()
        assertIs<DownloadState.Failed>(c.items.value.single().state)
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        runCurrent()
        assertEquals(2, s.fetches)
    }

    @Test fun cancelStopsTheJobAndTheSharedTransfer() = runTest {
        val c = center()
        val s = FakeSource()
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        runCurrent()
        c.cancel("k")
        runCurrent()
        assertEquals(DownloadState.Cancelled, c.items.value.single().state)
        assertEquals(1, s.cancels)
    }

    @Test fun closeCancelsEverySharedTransfer() = runTest {
        val c = center()
        val a = FakeSource()
        val b = FakeSource()
        c.start("a", "a.pdf", "application/pdf", DownloadPurpose.File, a)
        c.start("b", "b.pdf", "application/pdf", DownloadPurpose.File, b)
        runCurrent()
        c.close()
        runCurrent()
        assertEquals(listOf(1, 1), listOf(a.cancels, b.cancels))
    }

    // The source hears a Cancel when it is pressed, not when the job's catch gets its turn on the dispatcher: by
    // then the fleet has closed its hosts, and the media cache's transfer would run on until its next range failed.
    @Test fun cancelTellsTheSharedTransferAtOnce() = runTest {
        val c = center()
        val s = FakeSource()
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        runCurrent()
        c.cancel("k")
        assertEquals(1, s.cancels, "told before the job's catch has run")
        runCurrent()
        assertEquals(1, s.cancels, "and told once")
    }

    @Test fun closeTellsEverySharedTransferAtOnce() = runTest {
        val c = center()
        val a = FakeSource()
        val b = FakeSource()
        c.start("a", "a.pdf", "application/pdf", DownloadPurpose.File, a)
        c.start("b", "b.pdf", "application/pdf", DownloadPurpose.File, b)
        runCurrent()
        c.close()
        assertEquals(listOf(1, 1), listOf(a.cancels, b.cancels), "told before the jobs' catches have run")
        runCurrent()
        assertEquals(listOf(1, 1), listOf(a.cancels, b.cancels), "and told once")
    }

    @Test fun retryFetchesTheSameSourceAgain() = runTest {
        val c = center()
        val s = FakeSource()
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        runCurrent()
        c.cancel("k")
        runCurrent()
        c.retry("k")
        runCurrent()
        assertEquals(2, s.fetches)
        assertIs<DownloadState.Running>(c.items.value.single().state)
        c.close()
    }

    @Test fun aVanishedFileBecomesRetryable() = runTest {
        val c = center()
        val s = FakeSource().apply { gate.complete(Unit) }
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s).release()
        runCurrent()
        c.markMissing("k")
        assertIs<DownloadState.Failed>(c.items.value.single().state)
    }

    @Test fun clearFinishedKeepsOnlyRunning() = runTest {
        val c = center()
        val done = FakeSource().apply { gate.complete(Unit) }
        val running = FakeSource()
        c.start("d", "d.pdf", "application/pdf", DownloadPurpose.File, done).release()
        c.start("r", "r.pdf", "application/pdf", DownloadPurpose.File, running)
        runCurrent()
        c.clearFinished()
        assertEquals(listOf("r"), c.items.value.map { it.key })
        c.close()
    }

    @Test fun aThrowingSourceFailsWithItsMessage() = runTest {
        val c = center()
        val boom = object : DownloadSource {
            override suspend fun fetch(progress: (Long, Long?) -> Unit): DownloadedFile? = error("disk full")
        }
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, boom)
        runCurrent()
        assertEquals(DownloadState.Failed("disk full"), c.items.value.single().state)
    }

    // A request timing out inside the source surfaces as a CancellationException while the job is still
    // active: that is the download failing, not the user's Cancel.
    @Test fun aTimeoutInsideTheSourceIsAFailureNotACancel() = runTest {
        val c = center()
        val stuck = object : DownloadSource {
            override suspend fun fetch(progress: (Long, Long?) -> Unit): DownloadedFile? = withTimeout(10) { awaitCancellation() }
        }
        c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, stuck)
        advanceUntilIdle()
        assertIs<DownloadState.Failed>(c.items.value.single().state)
    }

    // A job cancelled before its body ran never reaches its own catch: the center must still settle it, or the
    // item stays Running and every waiter hangs. Here the fleet has closed (its scope is over) before the start.
    @Test fun aStartOnAnEndedScopeIsCancelledAndItsWaiterIsAnswered() = runTest {
        val ended = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).apply { cancel() }
        val c = DownloadCenter(ended)
        val claim = c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, FakeSource())
        runCurrent()
        assertEquals(DownloadState.Cancelled, c.items.value.single().state)
        assertNull(claim.await())
    }

    @Test fun aCancelBeforeTheJobFirstRanLeavesItCancelled() = runTest {
        val c = center()
        val s = FakeSource()
        val claim = c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, s)
        c.cancel("k")
        runCurrent()
        assertEquals(DownloadState.Cancelled, c.items.value.single().state)
        assertNull(claim.await())
        assertEquals(0, s.fetches)
    }

    // The same when the scope ends between the start and the job's first run.
    @Test fun aScopeEndingBeforeTheJobFirstRanLeavesItCancelled() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val c = DownloadCenter(scope)
        val claim = c.start("k", "a.pdf", "application/pdf", DownloadPurpose.File, FakeSource())
        scope.cancel()
        runCurrent()
        assertEquals(DownloadState.Cancelled, c.items.value.single().state)
        assertNull(claim.await())
    }
}
