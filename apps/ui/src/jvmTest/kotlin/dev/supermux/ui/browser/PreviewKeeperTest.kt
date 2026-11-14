package dev.supermux.ui.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

// #155: the broker keeps one preview key per device (24 hours; a mint replaces the last at once), so the process keeps
// one per host, renews it at 12 hours, and puts each new key in the Browser profile's store before a page uses it.
class PreviewKeeperTest {
    private class Store {
        val puts = mutableListOf<String>()
        var accepts = true
        val sink = PreviewKeeper.Sink<String> { puts += it; accepts }
    }

    private val hour = 60 * 60_000L

    @Test fun aKeyIsMintedOnceAndPutInTheStoreBeforeThePageGetsIt() = runTest {
        var mints = 0
        val keeper = PreviewKeeper(mint = { "k${++mints}" }, now = { 0L })
        val store = Store()
        assertEquals("k1", keeper.ready(store.sink))
        assertEquals("k1", keeper.ready(store.sink))
        assertEquals(1, mints)
        assertEquals(listOf("k1"), store.puts, "put once, not on every page")
    }

    @Test fun aKeyTwelveHoursOldIsRenewedAndTheNewOneGoesInTheStore() = runTest {
        var t = 0L
        var mints = 0
        val keeper = PreviewKeeper(mint = { "k${++mints}" }, now = { t })
        val store = Store()
        keeper.ready(store.sink)
        t = 12 * hour - 1
        assertEquals("k1", keeper.ready(store.sink))
        t = 12 * hour
        assertEquals("k2", keeper.ready(store.sink))
        assertEquals(listOf("k1", "k2"), store.puts)
    }

    // A key minted with nowhere to put it would kill the one the store holds (a mint replaces it on the broker).
    @Test fun withNoLiveEngineARenewalWaitsAndMintsNothing() = runTest {
        var mints = 0
        val keeper = PreviewKeeper(mint = { "k${++mints}" }, now = { 0L })
        keeper.renewIfDue()
        assertEquals(0, mints)
        val store = Store()
        val detach = keeper.attach(store.sink)
        keeper.renewIfDue()
        assertEquals(listOf("k1"), store.puts)
        detach()
    }

    @Test fun aRenewalGoesThroughAnAttachedEngineAndEveryTabSeesItInTheSharedStore() = runTest {
        var t = 0L
        var mints = 0
        val keeper = PreviewKeeper(mint = { "k${++mints}" }, now = { t })
        val store = Store()
        val detach = keeper.attach(store.sink)
        keeper.renewIfDue()
        keeper.renewIfDue()
        assertEquals(listOf("k1"), store.puts, "not due again yet")
        t = 12 * hour
        keeper.renewIfDue()
        assertEquals(listOf("k1", "k2"), store.puts)
        detach()
        t = 24 * hour
        keeper.renewIfDue()
        assertEquals(2, mints, "a detached engine is no way into the store")
    }

    @Test fun aKeyTheStoreRefusedIsPutAgainOnTheNextPageWithoutAMint() = runTest {
        var mints = 0
        val keeper = PreviewKeeper(mint = { "k${++mints}" }, now = { 0L })
        val store = Store().apply { accepts = false }
        keeper.ready(store.sink)
        store.accepts = true
        assertEquals("k1", keeper.ready(store.sink))
        assertEquals(listOf("k1", "k1"), store.puts)
        assertEquals(1, mints)
    }

    @Test fun aFailedMintIsTriedAgainAfterAMinute() = runTest {
        var t = 0L
        var answer: String? = null
        var mints = 0
        val keeper = PreviewKeeper(mint = { mints++; answer }, now = { t })
        val store = Store()
        assertNull(keeper.ready(store.sink))
        t = 30_000
        assertNull(keeper.ready(store.sink))
        assertEquals(1, mints, "not again within the minute")
        answer = "k"
        t = 61_000
        assertEquals("k", keeper.ready(store.sink))
        assertEquals(listOf("k"), store.puts)
    }

    @Test fun aThrowingMintIsAFailureNotACrash() = runTest {
        val keeper = PreviewKeeper<String>(mint = { throw IllegalStateException("HTTP 500") }, now = { 0L })
        assertNull(keeper.ready(Store().sink))
    }

    // The page that asked may leave mid-request (over the relay it often does): the key the broker already swapped in must
    // still reach the store, or the store keeps the one the mint just killed.
    @Test fun aMintFinishesIntoTheStoreEvenWhenThePageThatAskedIsGone() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val keeper = PreviewKeeper(mint = { gate.await(); "k1" }, now = { 0L })
        val store = Store()
        val asking = launch { keeper.ready(store.sink) }
        runCurrent()
        asking.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("k1"), store.puts)
    }

    @Test fun theDriverRenewsOnStartOnReconnectAndOnItsTick() = runTest {
        var t = 0L
        var mints = 0
        val keeper = PreviewKeeper(mint = { "k${++mints}" }, now = { t })
        val store = Store()
        keeper.attach(store.sink)
        val reconnects = MutableSharedFlow<Unit>()
        val driver = keeper.drive(this, reconnects, tickMs = 15 * 60_000L)
        runCurrent()
        assertEquals(listOf("k1"), store.puts, "on start")
        t = 13 * hour
        reconnects.emit(Unit)
        runCurrent()
        assertEquals(listOf("k1", "k2"), store.puts, "on reconnect, being due")
        t = 25 * hour
        advanceTimeBy(15 * 60_000L + 1)
        assertEquals(listOf("k1", "k2", "k3"), store.puts, "on the tick")
        driver.cancel()
    }

    @Test fun oneDriverAtATimeAndAnotherTakesOverWhenItsScopeEnds() = runTest {
        val keeper = PreviewKeeper(mint = { "k" }, now = { 0L })
        val first = Job(coroutineContext[Job])
        val a = keeper.drive(this + first, MutableSharedFlow())
        assertSame(a, keeper.drive(this, MutableSharedFlow()))
        first.cancel()
        runCurrent()
        val b = keeper.drive(this, MutableSharedFlow())
        assert(b !== a && b.isActive)
        b.cancel()
    }

    @Test fun aCancelledAskRethrows() = runTest {
        val keeper = PreviewKeeper<String>(mint = { throw CancellationException("gone") }, now = { 0L })
        assertFailsWith<CancellationException> { keeper.ready(Store().sink) }
    }
}
