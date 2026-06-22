package dev.supermux.net

import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** The JVM [ZlibInflater] actual must round-trip a [Deflater] stream, incrementally. */
class InflateJvmTest {
    private fun deflate(input: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(input)
        d.finish()
        val out = ByteArray(input.size * 2 + 64)
        val n = d.deflate(out)
        d.end()
        return out.copyOf(n)
    }

    @Test fun round_trips_a_full_stream() {
        val original = ByteArray(5000) { ((it * 31 + 7) and 0xff).toByte() }
        val compressed = deflate(original)
        val inf = ZlibInflater()
        inf.feed(compressed)
        val parts = ArrayList<Byte>()
        while (true) {
            val c = inf.inflate()
            if (c.isEmpty()) break
            for (b in c) parts.add(b)
        }
        inf.close()
        assertContentEquals(original, parts.toByteArray())
    }

    @Test fun round_trips_with_incremental_feeding() {
        // Feed the compressed bytes in small chunks — the ZRLE case (zlib stream
        // spread across multiple WS frames / updates).
        val original = ByteArray(8192) { (it and 0x7f).toByte() }
        val compressed = deflate(original)
        val inf = ZlibInflater()
        val parts = ArrayList<Byte>()
        var i = 0
        val step = 13
        while (i < compressed.size) {
            val end = minOf(i + step, compressed.size)
            inf.feed(compressed.copyOfRange(i, end))
            i = end
            while (true) {
                val c = inf.inflate()
                if (c.isEmpty()) break
                for (b in c) parts.add(b)
            }
        }
        inf.close()
        assertContentEquals(original, parts.toByteArray())
    }

    @Test fun two_concatenated_messages_share_one_stream() {
        // ZRLE shares ONE zlib stream across rects: deflate two messages into a
        // single stream (no finish between) and ensure incremental inflate yields
        // both in order.
        val a = ByteArray(1000) { (it and 0xff).toByte() }
        val b = ByteArray(1000) { ((255 - it) and 0xff).toByte() }
        val d = Deflater()
        val out = ArrayList<Byte>()
        val tmp = ByteArray(4096)
        // message a (flush, don't finish)
        d.setInput(a)
        var n = d.deflate(tmp, 0, tmp.size, Deflater.SYNC_FLUSH)
        for (k in 0 until n) out.add(tmp[k])
        // message b
        d.setInput(b)
        n = d.deflate(tmp, 0, tmp.size, Deflater.SYNC_FLUSH)
        for (k in 0 until n) out.add(tmp[k])
        d.end()

        val inf = ZlibInflater()
        inf.feed(out.toByteArray())
        val got = ArrayList<Byte>()
        while (true) {
            val c = inf.inflate()
            if (c.isEmpty()) break
            for (x in c) got.add(x)
        }
        inf.close()
        assertTrue(got.size >= a.size + b.size)
        assertContentEquals(a, got.subList(0, a.size).toByteArray())
        assertContentEquals(b, got.subList(a.size, a.size + b.size).toByteArray())
    }
}
