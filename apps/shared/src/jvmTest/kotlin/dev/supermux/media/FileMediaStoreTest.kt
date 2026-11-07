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

    @Test fun aRetainedWriteIsContinuedByTheNextOpen() = runTest {
        val w1 = assertNotNull(store.openResumable(key))
        assertEquals(0L, w1.offset)
        w1.append(byteArrayOf(1, 2, 3), 3)
        w1.retain()
        assertNull(store.read(key), "a partial is never an entry")
        val w2 = assertNotNull(store.openResumable(key))
        assertEquals(3L, w2.offset)
        w2.append(byteArrayOf(4, 5), 2)
        assertTrue(w2.commit())
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), store.read(key))
        assertEquals(root.resolve("v1/rec-1/$id.mp4").toString(), store.path(key))
    }

    @Test fun anAbandonedWriteLeavesNothing() = runTest {
        val w = assertNotNull(store.openResumable(key))
        w.append(byteArrayOf(1, 2), 2)
        w.abandon()
        assertNull(store.path(key))
        assertTrue(allFiles().isEmpty(), "found ${allFiles()}")
        assertEquals(0L, assertNotNull(store.openResumable(key)).offset)
    }

    @Test fun twoOpensNeverShareAPartial() = runTest {
        assertNotNull(store.openResumable(key)).apply { append(byteArrayOf(1, 2, 3), 3); retain() }
        val a = assertNotNull(store.openResumable(key))
        val b = assertNotNull(store.openResumable(key))
        assertEquals(listOf(0L, 3L), listOf(a.offset, b.offset).sorted())
        a.abandon()
        b.abandon()
    }

    @Test fun clearRemovesPartials() = runTest {
        assertNotNull(store.openResumable(key)).apply { append(byteArrayOf(1), 1); retain() }
        store.clear()
        assertEquals(0L, assertNotNull(store.openResumable(key)).offset)
    }

    @Test fun removeHostRemovesItsPartialsAndNoOthers() = runTest {
        val other = MediaKey.of("rec-2", id, "clip.mp4", "video/mp4")!!
        assertNotNull(store.openResumable(key)).apply { append(byteArrayOf(1), 1); retain() }
        assertNotNull(store.openResumable(other)).apply { append(byteArrayOf(2, 2), 2); retain() }
        store.removeHost("rec-1")
        assertEquals(0L, assertNotNull(store.openResumable(key)).offset)
        assertEquals(2L, assertNotNull(store.openResumable(other)).offset)
    }

    @Test fun anEndIsFinal() = runTest {
        val w = assertNotNull(store.openResumable(key))
        w.append(byteArrayOf(7), 1)
        assertTrue(w.commit())
        w.abandon()
        w.retain()
        assertFalse(w.commit(), "a write that has ended commits nothing more")
        assertContentEquals(byteArrayOf(7), store.read(key))
        assertEquals(listOf(root.resolve("v1/rec-1/$id.mp4")), allFiles(), "a later end must not leave a partial behind")
    }

    @Test fun whatAWriteLeavesOnDiskIsOnlyWhatItKept() = runTest {
        val dir = root.resolve("v1/rec-1")
        val w1 = assertNotNull(store.openResumable(key))
        w1.append(byteArrayOf(1, 2), 2)
        w1.retain()
        assertEquals(listOf(dir.resolve("$id.mp4.partial")), allFiles())
        val w2 = assertNotNull(store.openResumable(key))
        w2.append(byteArrayOf(3), 1)
        assertTrue(w2.commit())
        assertEquals(listOf(dir.resolve("$id.mp4")), allFiles(), "neither the partial nor a part file may outlive the commit")
    }

    @Test fun throughTheCacheAStoppedFileDownloadResumesOnDisk() = runTest {
        // The store's I/O on the test scheduler, so every write has landed when the cache answers.
        val onScheduler = FileMediaStore(root.toString(), fileUri = { "file://$it" }, io = UnconfinedTestDispatcher(testScheduler))
        val cache = MediaCache(onScheduler, this, rangeBytes = 4)
        val content = ByteArray(10) { it.toByte() }
        val att = Attachment(file_id = id, name = "clip.mp4", mime = "video/mp4")
        assertNull(cache.file("rec-1", att, { from, to, sink -> if (from == 0L) rangeServer(content)(from, to, sink) else null }))
        val calls = mutableListOf<Pair<Long, Long>>()
        val got = assertNotNull(cache.file("rec-1", att, rangeServer(content, calls)))
        assertEquals(4L, calls.first().first, "the retry continued the kept part")
        assertEquals(root.resolve("v1/rec-1/$id.mp4").toString(), got.path)
        assertEquals(10L, got.size)
        assertContentEquals(content, root.resolve("v1/rec-1/$id.mp4").readBytes())
        assertEquals(listOf(root.resolve("v1/rec-1/$id.mp4")), allFiles(), "neither a partial nor a part file is left")
    }

    @Test fun onlyAStoredEntryHasASize() = runTest {
        assertNotNull(store.openResumable(key)).apply { append(byteArrayOf(1, 2), 2); retain() }
        assertNull(store.size(key), "a partial is not an entry")
        assertTrue(store.write(key, ByteArray(7)))
        assertEquals(7L, store.size(key))
    }

    @Test fun aCommitThatCannotLandLeavesNothingBehind() = runTest {
        val w = assertNotNull(store.openResumable(key))
        w.append(byteArrayOf(1), 1)
        // A directory stands where the entry belongs: the move cannot succeed, and no finished file is there to stand in.
        Files.createDirectories(root.resolve("v1/rec-1/$id.mp4/in-the-way"))
        assertFalse(w.commit())
        assertTrue(allFiles().isEmpty(), "found ${allFiles()}")
    }
}
