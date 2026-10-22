package dev.supermux.media

import dev.supermux.proto.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMediaStoreTest {
    private val tmp: Path = Files.createTempDirectory("media-store-test")
    private val root: Path = tmp.resolve("root")
    private val store = FileMediaStore(root.toString(), fileUri = { "file://$it" }, io = Dispatchers.IO)
    private val id = "0123456789abcdef0123456789abcdef"
    private val key = MediaKey.of("rec-1", id, "clip.mp4", "video/mp4")!!

    @AfterTest fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private fun allFiles(): List<Path> = Files.walk(tmp).use { s -> s.filter { Files.isRegularFile(it) }.toList() }

    @Test fun aWriteIsReadBackAndHasAFileUri() = runTest {
        assertTrue(store.write(key, byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), store.read(key))
        assertTrue(store.exists(key))
        val target = root.resolve("v1/rec-1/$id.mp4")
        assertEquals("file://$target", store.uri(key))
        assertContentEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }

    @Test fun aMissingEntryIsNull() = runTest {
        assertNull(store.read(key))
        assertNull(store.uri(key))
        assertFalse(store.exists(key))
    }

    @Test fun aStreamIsInvisibleUntilItCompletes() = runTest {
        val target = root.resolve("v1/rec-1/$id.mp4")
        assertTrue(
            store.writeStream(key) { append ->
                append(byteArrayOf(1, 2), 2)
                assertFalse(target.exists(), "a half-written entry must not be visible")
                append(byteArrayOf(3), 1)
                true
            },
        )
        assertContentEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertEquals(listOf(target), allFiles(), "no part file may be left behind")
    }

    @Test fun anAbandonedOrFailedStreamLeavesNothing() = runTest {
        assertFalse(
            store.writeStream(key) { append ->
                append(byteArrayOf(1), 1)
                false
            },
        )
        runCatching {
            store.writeStream(key) { append ->
                append(byteArrayOf(1), 1)
                error("network")
            }
        }
        assertTrue(allFiles().isEmpty(), "found ${allFiles()}")
    }

    @Test fun removeHostClearAndSize() = runTest {
        val other = MediaKey.of("rec-2", id, "a.png", "image/png")!!
        store.write(key, ByteArray(10))
        store.write(other, ByteArray(5))
        assertEquals(15L, store.sizeBytes())
        store.removeHost("rec-1")
        assertNull(store.read(key))
        assertNotNull(store.read(other))
        assertEquals(5L, store.sizeBytes())
        store.clear()
        assertEquals(0L, store.sizeBytes())
        assertTrue(allFiles().isEmpty())
    }

    @Test fun throughTheCacheNothingLandsOutsideTheRoot() = runTest {
        // The store's I/O on the test scheduler, so `advanceUntilIdle()` really waits for every write.
        val onScheduler = FileMediaStore(root.toString(), fileUri = { "file://$it" }, io = UnconfinedTestDispatcher(testScheduler))
        val cache = MediaCache(onScheduler, this)
        for (bad in listOf("../../x", "a/b", "..", "$id/..")) {
            cache.bytes("rec-1", Attachment(file_id = bad, name = "../../evil.png"), fetch = { byteArrayOf(1) }, download = null)
        }
        cache.bytes("../..", Attachment(file_id = id, name = "a.png"), fetch = { byteArrayOf(1) }, download = null)
        // The control: a valid key on the same path IS written, so the empty result above is not vacuous.
        cache.bytes("rec-1", Attachment(file_id = id, name = "ok.png"), fetch = { byteArrayOf(1) }, download = null)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(root.resolve("v1/rec-1/$id.png")), allFiles())
    }

    @Test fun clearCarriesOnPastAnEntryThatWillNotGo() = runTest {
        val other = MediaKey.of("rec-2", id, "a.png", "image/png")!!
        store.write(key, ByteArray(10))
        store.write(other, ByteArray(5))
        // rec-1's folder becomes read-only, so the file inside it cannot be deleted (a clip the player
        // holds open on Windows behaves the same way).
        val locked = root.resolve("v1/rec-1")
        Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
        try {
            assertTrue(runCatching { store.clear() }.isFailure, "a stuck entry is reported")
            assertNull(store.read(other), "one stuck file must not keep every other one on disk")
            assertNotNull(store.read(key))
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }
}
