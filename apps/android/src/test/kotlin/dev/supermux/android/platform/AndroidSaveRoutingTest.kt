package dev.supermux.android.platform

import dev.supermux.ui.platform.SavedFile
import dev.supermux.ui.platform.SavedPlace
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #95: Save on Android puts a picture or a video in the gallery and any other file in Downloads, with no dialog — it
 * opened the share chooser before. The MediaStore rows are a seam ([MediaStoreRows]): only a device has the provider,
 * so the Fold checks the rows themselves; here, where each kind goes and that a row is only published whole.
 */
class AndroidSaveRoutingTest {
    private val made = mutableListOf<File>()

    @AfterTest fun cleanUp() = made.forEach { it.deleteRecursively() }

    private fun stored(bytes: ByteArray): String =
        File(Files.createTempDirectory("sm-dl-").toFile().also { made += it }, "3f9c.bin").also { it.writeBytes(bytes) }.absolutePath

    @Test fun picturesGoToPicturesMoviesToMoviesTheRestToDownloads() {
        assertEquals(MediaShelf.Pictures, mediaShelfFor("image/jpeg"))
        assertEquals(MediaShelf.Pictures, mediaShelfFor("image/heic"))
        assertEquals(MediaShelf.Movies, mediaShelfFor("video/mp4"))
        assertEquals(MediaShelf.Downloads, mediaShelfFor("application/pdf"))
        assertEquals(MediaShelf.Downloads, mediaShelfFor("image/svg+xml"))
        assertEquals(MediaShelf.Downloads, mediaShelfFor(""))
        assertEquals("Pictures/Supermux", MediaShelf.Pictures.relativePath)
        assertEquals("Movies/Supermux", MediaShelf.Movies.relativePath)
        assertEquals("Download/Supermux", MediaShelf.Downloads.relativePath)
        assertEquals(SavedPlace.Gallery, MediaShelf.Movies.place)
        assertEquals(SavedPlace.Downloads, MediaShelf.Downloads.place)
    }

    private class FakeRows(val failOpen: Boolean = false) : MediaStoreRows<String> {
        val inserted = mutableListOf<Triple<MediaShelf, String, String>>()
        val published = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val sink = ByteArrayOutputStream()

        override fun insertPending(shelf: MediaShelf, name: String, mime: String): String {
            inserted += Triple(shelf, name, mime)
            return "content://media/${shelf.name}/1"
        }

        override fun open(row: String): OutputStream? = if (failOpen) throw IOException("no space") else sink
        override fun publish(row: String) { published += row }
        override fun discard(row: String) { discarded += row }
    }

    @Test fun aPhotoIsCopiedWholeIntoThePicturesShelfThenPublished() = runTest {
        val bytes = ByteArray(300_000) { it.toByte() }
        val rows = FakeRows()
        val saved = saveToMediaStore(stored(bytes), "../a/photo.jpg", "image/jpeg", rows)
        assertEquals(SavedFile("photo.jpg", "content://media/Pictures/1", SavedPlace.Gallery), saved)
        assertEquals(listOf(Triple(MediaShelf.Pictures, "photo.jpg", "image/jpeg")), rows.inserted)
        assertContentEquals(bytes, rows.sink.toByteArray())
        assertEquals(listOf("content://media/Pictures/1"), rows.published)
        assertEquals(emptyList(), rows.discarded)
    }

    @Test fun aPdfGoesToDownloads() = runTest {
        val saved = saveToMediaStore(stored(byteArrayOf(1)), "report.pdf", "application/pdf", FakeRows())
        assertEquals(SavedPlace.Downloads, saved?.place)
    }

    // A half-written file never shows in the gallery: the row is dropped, and the caller hears it failed.
    @Test fun aFailedCopyDiscardsTheRow() = runTest {
        val rows = FakeRows(failOpen = true)
        assertNull(saveToMediaStore(stored(byteArrayOf(1)), "photo.jpg", "image/jpeg", rows))
        assertEquals(emptyList(), rows.published)
        assertTrue(rows.discarded.isNotEmpty())
    }
}
