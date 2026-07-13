package dev.supermux.desktop.upload

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TDD coverage for the JVM `ChunkSource` over `java.io.File` (M4a-T1). */
class FileChunkSourceTest {

    /** 64 known bytes: byte i has value i, so any slice is trivially verifiable. */
    private fun tempFile(bytes: Int = 64): java.io.File {
        val file = Files.createTempFile("file-chunk-source-test", ".bin").toFile()
        file.writeBytes(ByteArray(bytes) { it.toByte() })
        file.deleteOnExit()
        return file
    }

    @Test fun size_matches_the_file_length() {
        val file = tempFile(64)
        val source = FileChunkSource(file)
        assertEquals(64L, source.size)
    }

    @Test fun read_from_the_start_returns_the_exact_bytes() {
        val file = tempFile(64)
        val source = FileChunkSource(file)
        val bytes = source.read(0, 10)
        assertEquals(ByteArray(10) { it.toByte() }.toList(), bytes.toList())
    }

    @Test fun read_mid_file_returns_the_exact_bytes_at_that_offset() {
        val file = tempFile(64)
        val source = FileChunkSource(file)
        val bytes = source.read(20, 10)
        assertEquals(ByteArray(10) { (20 + it).toByte() }.toList(), bytes.toList())
    }

    @Test fun read_past_eof_returns_a_clamped_short_array() {
        val file = tempFile(64)
        val source = FileChunkSource(file)
        val bytes = source.read(60, 10)
        assertEquals(4, bytes.size)
        assertEquals(ByteArray(4) { (60 + it).toByte() }.toList(), bytes.toList())
    }

    @Test fun read_at_eof_returns_empty() {
        val file = tempFile(64)
        val source = FileChunkSource(file)
        assertTrue(source.read(64, 10).isEmpty())
    }

    @Test fun read_after_eof_returns_empty() {
        val file = tempFile(64)
        val source = FileChunkSource(file)
        assertTrue(source.read(100, 10).isEmpty())
    }

    @Test fun read_of_an_empty_file_returns_empty() {
        val file = tempFile(0)
        val source = FileChunkSource(file)
        assertEquals(0L, source.size)
        assertTrue(source.read(0, 10).isEmpty())
    }

    @Test fun two_threads_reading_different_offsets_concurrently_both_get_correct_bytes() {
        val file = tempFile(64)
        val source = FileChunkSource(file)
        val latch = CountDownLatch(2)
        var firstBytes: ByteArray? = null
        var secondBytes: ByteArray? = null
        var firstError: Throwable? = null
        var secondError: Throwable? = null

        val t1 = Thread {
            try {
                firstBytes = source.read(0, 8)
            } catch (t: Throwable) {
                firstError = t
            } finally {
                latch.countDown()
            }
        }
        val t2 = Thread {
            try {
                secondBytes = source.read(32, 8)
            } catch (t: Throwable) {
                secondError = t
            } finally {
                latch.countDown()
            }
        }
        t1.start()
        t2.start()
        assertTrue(latch.await(1, TimeUnit.SECONDS), "both reads should complete within 1s")

        assertTrue(firstError == null, "first read threw: $firstError")
        assertTrue(secondError == null, "second read threw: $secondError")
        assertEquals(ByteArray(8) { it.toByte() }.toList(), firstBytes!!.toList())
        assertEquals(ByteArray(8) { (32 + it).toByte() }.toList(), secondBytes!!.toList())
    }
}
