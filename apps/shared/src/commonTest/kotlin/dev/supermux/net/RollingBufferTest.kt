package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rolling buffer reassembles a length-prefixed stream across arbitrary chunk splits. */
class RollingBufferTest {
    @Test fun take_returns_null_until_enough() {
        val rb = RollingBuffer()
        assertNull(rb.take(4))
        rb.append(byteArrayOf(1, 2))
        assertNull(rb.take(4))
        rb.append(byteArrayOf(3, 4, 5))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), rb.take(4))
        assertEquals(1, rb.available)
        assertContentEquals(byteArrayOf(5), rb.take(1))
    }

    @Test fun spans_many_tiny_chunks() {
        val rb = RollingBuffer()
        val full = ByteArray(1000) { (it and 0xff).toByte() }
        var i = 0
        while (i < full.size) { rb.append(byteArrayOf(full[i])); i++ }
        val got = rb.take(1000)!!
        assertContentEquals(full, got)
    }

    @Test fun interleaved_takes_and_appends() {
        val rb = RollingBuffer()
        rb.append(byteArrayOf(10, 11, 12, 13, 14, 15))
        assertContentEquals(byteArrayOf(10, 11), rb.take(2))
        rb.append(byteArrayOf(16, 17))
        assertContentEquals(byteArrayOf(12, 13, 14), rb.take(3))
        assertContentEquals(byteArrayOf(15, 16, 17), rb.take(3))
        assertNull(rb.take(1))
    }

    @Test fun take_zero_is_empty() {
        val rb = RollingBuffer()
        assertContentEquals(ByteArray(0), rb.take(0))
    }
}
