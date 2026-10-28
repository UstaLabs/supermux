package dev.supermux.webview.runtime

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class PageMemoryTest {
    private fun memory(tag: Int) = PageMemory(SnapshotKey("k$tag", 1, 1, false), null, null, null, false)

    @Test fun recallsWhatWasRemembered() {
        val store = PageMemoryStore(maxEntries = 4)
        val remembered = memory(1)
        store.remember("a", remembered)
        assertSame(remembered, store.recall("a"))
        assertNull(store.recall("b"))
    }

    @Test fun forgetsTheOldestWhenOverItsByteBudget() {
        val store = PageMemoryStore(maxEntries = 10, maxBytes = 100)
        fun withState(chars: Int) = PageMemory(null, null, null, "x".repeat(chars), true)
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
}
