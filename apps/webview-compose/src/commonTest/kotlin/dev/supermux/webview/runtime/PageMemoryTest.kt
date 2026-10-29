package dev.supermux.webview.runtime

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageMemoryTest {
    private fun memory(tag: Int) = PageMemory(SnapshotKey("k$tag", 1, 1, false, 1f), null, null, null, false, null, false)

    /** [chars] of page state, with an empty origin so that only they count. */
    private fun state(chars: Int) = SavedPageState("x".repeat(chars), origin = "")
    private fun withState(chars: Int) = PageMemory(null, null, null, state(chars), true, null, false)

    /** What a memory with [chars] of page state and nothing else costs under [key]. */
    private fun cost(key: String, chars: Int) = 2L * chars + 2L * key.length + PageMemoryStore.ENTRY_OVERHEAD

    @Test fun recallsWhatWasRemembered() {
        val store = PageMemoryStore(maxEntries = 4)
        val remembered = memory(1)
        store.remember("a", remembered)
        assertSame(remembered, store.recall("a"))
        assertNull(store.recall("b"))
    }

    @Test fun forgetsTheOldestWhenOverItsByteBudget() {
        val store = PageMemoryStore(maxEntries = 10, maxBytes = 2 * cost("a", 30) - 1)
        store.remember("a", withState(30))
        store.remember("b", withState(30))
        assertNull(store.recall("a"))
        assertNotNull(store.recall("b"))
    }

    @Test fun forgetsTheLeastRecentlyRememberedBeyondItsLimit() {
        val store = PageMemoryStore(maxEntries = 2)
        store.remember("a", memory(1))
        store.remember("b", memory(2))
        store.remember("a", memory(3))
        store.remember("c", memory(4))
        assertNull(store.recall("b"))
        assertNotNull(store.recall("a"))
        assertNotNull(store.recall("c"))
    }

    @Test fun anOversizedEngineStateIsDroppedButTheRestIsKept() {
        val store = PageMemoryStore(maxEntries = 4, maxBytes = 1_000)
        val snapshotKey = SnapshotKey("a", 10, 10, dark = true, density = 2f)
        val size = DpSize(320.dp, 200.dp)
        val pageState = SavedPageState("""{"n":1}""", "https://a.invalid")
        store.remember("a", PageMemory(snapshotKey, size, ByteArray(2_000), pageState, true, SizeReport(216f, 200f), true))
        val kept = assertNotNull(store.recall("a"))
        assertNull(kept.engineState)
        assertEquals(snapshotKey, kept.snapshotKey)
        assertEquals(size, kept.contentSize)
        assertEquals(pageState, kept.pageState)
        assertTrue(kept.pageSavesState)
        assertEquals(SizeReport(216f, 200f), kept.lastSizeReport)
        assertTrue(kept.growsWithViewport)
    }

    @Test fun anEntryTooBigEvenWithoutEngineStateIsNotKeptAndForgetsTheOldOne() {
        val store = PageMemoryStore(maxEntries = 4, maxBytes = 1_000)
        store.remember("a", withState(10))
        store.remember("b", withState(10))
        store.remember("a", PageMemory(null, null, ByteArray(10), state(500), true, null, false))
        assertNull(store.recall("a"))
        assertNotNull(store.recall("b"))
    }

    @Test fun replacingAnEntryAccountsForItsOldCost() {
        val budget = 1_000L
        val store = PageMemoryStore(maxEntries = 10, maxBytes = budget)
        store.remember("a", withState(300))
        store.remember("a", withState(10))
        // "b" takes exactly what's left; if the first "a" still counted, "a" would go.
        val rest = budget - cost("a", 10)
        val chars = ((rest - cost("b", 0)) / 2).toInt()
        assertEquals(rest, cost("b", chars))
        store.remember("b", withState(chars))
        assertNotNull(store.recall("a"))
        assertNotNull(store.recall("b"))
        // The budget is full: one more entry pushes out the least recently used.
        store.remember("c", withState(0))
        assertNull(store.recall("a"))
        assertNotNull(store.recall("b"))
        assertNotNull(store.recall("c"))
    }

    @Test fun anEntryCostingExactlyTheBudgetIsKeptWhole() {
        val memory = PageMemory(null, null, ByteArray(100), state(50), true, null, false)
        val store = PageMemoryStore(maxEntries = 4, maxBytes = 100 + 2 * 50 + 2 * 1 + PageMemoryStore.ENTRY_OVERHEAD)
        store.remember("a", memory)
        assertSame(memory, store.recall("a"))
    }

    @Test fun thePageStatesOriginCountsTowardsTheBudget() {
        val origin = "https://a.invalid"
        val memory = PageMemory(null, null, null, SavedPageState("x".repeat(10), origin), true, null, false)
        val exact = cost("a", 10) + 2L * origin.length
        val fits = PageMemoryStore(maxEntries = 4, maxBytes = exact)
        fits.remember("a", memory)
        assertSame(memory, fits.recall("a"))
        val short = PageMemoryStore(maxEntries = 4, maxBytes = exact - 1)
        short.remember("a", memory)
        assertNull(short.recall("a"))
    }

    @Test fun keysCountTowardsTheBudget() {
        val store = PageMemoryStore(maxEntries = 4, maxBytes = 1L shl 20)
        val key = "k".repeat(1_000_000)
        store.remember(key, PageMemory(null, null, null, null, false, null, false))
        assertNull(store.recall(key))
    }

    @Test fun savedPageStateDoesNotPrintThePagesData() {
        val text = SavedPageState("""{"token":"secret"}""", "https://a.invalid").toString()
        assertFalse("secret" in text)
        assertTrue("https://a.invalid" in text)
    }

    @Test fun countsItsPagesAndWhatTheyCost() {
        val store = PageMemoryStore(maxEntries = 2)
        assertEquals(0, store.count)
        assertEquals(0L, store.sizeBytes)
        store.remember("a", withState(10))
        store.remember("b", withState(20))
        assertEquals(2, store.count)
        assertEquals(cost("a", 10) + cost("b", 20), store.sizeBytes)
        // Replaced, then pushed out: each leaves with what it cost.
        store.remember("a", withState(5))
        store.remember("c", withState(0))
        assertEquals(2, store.count)
        assertEquals(cost("a", 5) + cost("c", 0), store.sizeBytes)
        assertEquals(16L shl 20, store.maxBytes)
    }

    @Test fun recallRefreshesRecency() {
        val store = PageMemoryStore(maxEntries = 2)
        store.remember("a", memory(1))
        store.remember("b", memory(2))
        assertNotNull(store.recall("a"))
        store.remember("c", memory(3))
        assertNull(store.recall("b"))
        assertNotNull(store.recall("a"))
        assertNotNull(store.recall("c"))
    }
}
