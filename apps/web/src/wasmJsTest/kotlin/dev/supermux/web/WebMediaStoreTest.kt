package dev.supermux.web

import dev.supermux.media.MediaKey
import dev.supermux.web.seams.WebMediaStore
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebMediaStoreTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test fun roundTripRemoveHostClearAndSize() = runTest {
        val store = WebMediaStore("test-media-${Random.nextInt()}")
        assertTrue(store.persistent, "Karma serves from localhost, a secure context: Cache Storage must exist")
        assertFalse(store.hasFiles)
        val a = MediaKey.of("rec-1", id, "a.png", "image/png")!!
        val b = MediaKey.of("rec-2", id, "b.png", "image/png")!!
        assertTrue(store.write(a, byteArrayOf(1, 2, 3)))
        assertTrue(
            store.writeStream(b) { append ->
                append(byteArrayOf(4, 5), 2)
                append(byteArrayOf(6), 1)
                true
            },
        )
        assertContentEquals(byteArrayOf(1, 2, 3), store.read(a))
        assertContentEquals(byteArrayOf(4, 5, 6), store.read(b))
        assertTrue(store.exists(a))
        assertNull(store.uri(a))
        assertEquals(6L, store.sizeBytes())
        store.removeHost("rec-1")
        assertNull(store.read(a))
        assertContentEquals(byteArrayOf(4, 5, 6), store.read(b))
        store.clear()
        assertNull(store.read(b))
        assertEquals(0L, store.sizeBytes())
    }
}
