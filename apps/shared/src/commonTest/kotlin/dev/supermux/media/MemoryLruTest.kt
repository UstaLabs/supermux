package dev.supermux.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MemoryLruTest {
    private fun bytes(n: Int) = ByteArray(n)

    @Test fun aPutIsReturnedByGet() {
        val lru = MemoryLru(maxBytes = 100, maxEntryBytes = 50)
        lru.put("a", bytes(10))
        assertEquals(10, assertNotNull(lru.get("a")).size)
        assertEquals(10, lru.sizeBytes)
    }

    @Test fun theLeastRecentlyUsedGoesFirst() {
        val lru = MemoryLru(maxBytes = 30, maxEntryBytes = 30)
        lru.put("a", bytes(10))
        lru.put("b", bytes(10))
        lru.put("c", bytes(10))
        lru.get("a") // a is now the most recent
        lru.put("d", bytes(10)) // 40 > 30: b goes, not a
        assertNull(lru.get("b"))
        assertNotNull(lru.get("a"))
        assertNotNull(lru.get("c"))
        assertNotNull(lru.get("d"))
        assertEquals(30, lru.sizeBytes)
    }

    @Test fun anEntryOverTheEntryCapIsNeverKept() {
        val lru = MemoryLru(maxBytes = 100, maxEntryBytes = 20)
        lru.put("small", bytes(5))
        lru.put("big", bytes(21))
        assertNull(lru.get("big"))
        assertNotNull(lru.get("small"), "a rejected big entry must not evict anything")
    }

    @Test fun replacingAKeyKeepsTheTotalHonest() {
        val lru = MemoryLru(maxBytes = 100, maxEntryBytes = 100)
        lru.put("a", bytes(10))
        lru.put("a", bytes(30))
        assertEquals(30, lru.sizeBytes)
    }

    @Test fun removePrefixDropsOneHostOnly() {
        val lru = MemoryLru(maxBytes = 100, maxEntryBytes = 100)
        lru.put("h1/a", bytes(1))
        lru.put("h1/b", bytes(1))
        lru.put("h2/a", bytes(1))
        lru.removePrefix("h1/")
        assertNull(lru.get("h1/a"))
        assertNull(lru.get("h1/b"))
        assertNotNull(lru.get("h2/a"))
        assertEquals(1, lru.sizeBytes)
    }

    @Test fun clearEmptiesIt() {
        val lru = MemoryLru(maxBytes = 100, maxEntryBytes = 100)
        lru.put("a", bytes(3))
        lru.remove("missing")
        lru.clear()
        assertNull(lru.get("a"))
        assertEquals(0, lru.sizeBytes)
    }
}
