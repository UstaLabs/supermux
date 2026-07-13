package dev.supermux.desktop.terminal

import com.jediterm.core.util.TermSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD spec for [MuxTtyConnector] — the shared TerminalClient byte-stream ↔ JediTerm char-stream
 * bridge. Blocking behaviour is exercised with plain threads + latches under a 1s timeout so a
 * deadlock fails fast instead of hanging the suite.
 */
class MuxTtyConnectorTest {

    private fun connector(
        sendInput: (ByteArray) -> Unit = {},
        requestResize: (Int, Int) -> Unit = { _, _ -> },
        isConnected: () -> Boolean = { true },
    ) = MuxTtyConnector(sendInput, requestResize, isConnected)

    /** Reads exactly [count] chars off the (blocking) connector on a worker thread, honouring
     *  partial reads, and returns the decoded string — or fails if it doesn't complete in 1s. */
    private fun readString(c: MuxTtyConnector, count: Int, chunk: Int = count): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        val t = Thread {
            val sb = StringBuilder()
            val buf = CharArray(chunk)
            while (sb.length < count) {
                val n = c.read(buf, 0, chunk)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            result.set(sb.toString())
            done.countDown()
        }
        t.isDaemon = true
        t.start()
        assertTrue(done.await(1, TimeUnit.SECONDS), "read did not complete within 1s (deadlock?)")
        return result.get()
    }

    @Test
    fun offer_server_bytes_are_readable_as_chars() {
        val c = connector()
        c.offerServerBytes("hi".toByteArray(Charsets.UTF_8))
        assertEquals("hi", readString(c, 2))
    }

    @Test
    fun multibyte_char_split_across_two_offers_decodes_whole() {
        val c = connector()
        // "→" = U+2192 = 0xE2 0x86 0x92, split across two frames.
        c.offerServerBytes(byteArrayOf(0xE2.toByte()))
        c.offerServerBytes(byteArrayOf(0x86.toByte(), 0x92.toByte()))
        assertEquals("→", readString(c, 1))
    }

    @Test
    fun write_bytes_forwards_verbatim_to_send_input() {
        val sent = AtomicReference<ByteArray>()
        val c = connector(sendInput = { sent.set(it) })
        val payload = byteArrayOf(1, 2, 3, 127)
        c.write(payload)
        assertTrue(payload.contentEquals(sent.get()))
    }

    @Test
    fun write_string_forwards_as_utf8_bytes() {
        val sent = AtomicReference<ByteArray>()
        val c = connector(sendInput = { sent.set(it) })
        c.write("é→")
        assertTrue("é→".toByteArray(Charsets.UTF_8).contentEquals(sent.get()))
    }

    @Test
    fun resize_forwards_cols_and_rows() {
        val cols = AtomicReference<Int>()
        val rows = AtomicReference<Int>()
        val c = connector(requestResize = { co, ro -> cols.set(co); rows.set(ro) })
        c.resize(TermSize(120, 40))
        assertEquals(120, cols.get())
        assertEquals(40, rows.get())
    }

    @Test
    fun is_connected_delegates_to_lambda() {
        val flag = AtomicBoolean(false)
        val c = connector(isConnected = { flag.get() })
        assertFalse(c.isConnected)
        flag.set(true)
        assertTrue(c.isConnected)
    }

    @Test
    fun close_stream_makes_read_return_minus_one() {
        val c = connector()
        val result = AtomicReference<Int>()
        val done = CountDownLatch(1)
        Thread {
            result.set(c.read(CharArray(8), 0, 8))
            done.countDown()
        }.apply { isDaemon = true }.start()
        // Give the reader a moment to block on the empty queue, then close.
        Thread.sleep(50)
        c.closeStream()
        assertTrue(done.await(1, TimeUnit.SECONDS), "read did not unblock after closeStream")
        assertEquals(-1, result.get())
        // EOF is sticky: subsequent reads also return -1 without blocking.
        assertEquals(-1, c.read(CharArray(8), 0, 8))
    }

    @Test
    fun wait_for_unblocks_after_close_stream() {
        val c = connector()
        val done = CountDownLatch(1)
        Thread {
            c.waitFor()
            done.countDown()
        }.apply { isDaemon = true }.start()
        Thread.sleep(50)
        assertEquals(1, done.count) // still blocked before close
        c.closeStream()
        assertTrue(done.await(1, TimeUnit.SECONDS), "waitFor did not unblock after closeStream")
    }

    @Test
    fun inject_display_bytes_preserve_order_with_server_bytes() {
        val c = connector()
        c.offerServerBytes("A".toByteArray())
        c.injectDisplayBytes("B".toByteArray())
        c.offerServerBytes("C".toByteArray())
        assertEquals("ABC", readString(c, 3))
    }

    @Test
    fun on_user_input_tap_fires_before_send_input() {
        val order = mutableListOf<String>()
        val c = connector(sendInput = { synchronized(order) { order.add("send") } })
        c.onUserInput = { synchronized(order) { order.add("tap") } }
        c.write(byteArrayOf(42))
        assertEquals(listOf("tap", "send"), order)
    }

    @Test
    fun small_buffer_over_large_chunk_returns_partial_without_loss() {
        val c = connector()
        val big = "x".repeat(100)
        c.offerServerBytes(big.toByteArray())
        // Read with a 10-char buffer: must reassemble all 100 chars across partial reads.
        assertEquals(big, readString(c, 100, chunk = 10))
    }

    @Test
    fun ready_reflects_pending_data() {
        val c = connector()
        assertFalse(c.ready())
        c.offerServerBytes("z".toByteArray())
        assertTrue(c.ready())
        readString(c, 1)
        assertFalse(c.ready())
    }

    @Test
    fun on_user_input_null_by_default_and_write_still_sends() {
        val sent = AtomicReference<ByteArray>()
        val c = connector(sendInput = { sent.set(it) })
        assertNull(c.onUserInput)
        c.write(byteArrayOf(9))
        assertTrue(byteArrayOf(9).contentEquals(sent.get()))
    }
}
