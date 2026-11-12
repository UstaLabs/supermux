package dev.supermux.ui.chat

import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.SavedFile
import dev.supermux.ui.platform.SavedPlace
import dev.supermux.ui.platform.isGalleryMedia
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The viewer's Save with bytes in hand (a host file, a preview: no download): it saves, on a phone too, where it
 * used to run the tap's "open with" chooser. And what the phone says afterwards: where the file went.
 */
class SaveAttachmentTest {
    @Test fun aTouchHostSavesTheBytesAndOpensNothing() = runTest {
        val platform = FakePlatform()
        val saved = saveAttachment(platform, pointer = false, "photo.png", "image/png", byteArrayOf(1, 2))
        assertEquals(SavedFile("photo.png", "/fake/photo.png"), saved)
        assertEquals(listOf("photo.png|image/png|2"), platform.files.saved)
        assertEquals(emptyList(), platform.files.opened)
        assertEquals(emptyList(), platform.files.openedSaved)
    }

    // Desktop: save as, then open the file the user chose, as before.
    @Test fun aPointerHostOpensWhatItSaved() = runTest {
        val platform = FakePlatform()
        saveAttachment(platform, pointer = true, "notes.txt", "text/plain", byteArrayOf(1))
        assertEquals(listOf(SavedFile("notes.txt", "/fake/notes.txt")), platform.files.openedSaved)
    }

    private val words = SavedWords(photos = "Saved to Photos", gallery = "Saved to Gallery", downloads = "Saved to Downloads", chosen = { "Saved $it" })

    @Test fun aPhoneSaysWhereItWent() {
        assertEquals("Saved to Photos", savedNotice(SavedFile("a.jpg", "x", SavedPlace.Photos), pointer = false, words))
        assertEquals("Saved to Gallery", savedNotice(SavedFile("a.mp4", "x", SavedPlace.Gallery), pointer = false, words))
        assertEquals("Saved to Downloads", savedNotice(SavedFile("a.pdf", "x", SavedPlace.Downloads), pointer = false, words))
        assertEquals("Saved a.pdf", savedNotice(SavedFile("a.pdf", "x"), pointer = false, words))
    }

    // A pointer host opens the saved file: that is its confirmation.
    @Test fun aPointerHostSaysNothing() {
        assertNull(savedNotice(SavedFile("a.pdf", "x", SavedPlace.Downloads), pointer = true, words))
    }

    // What goes to the gallery: pictures and videos it can show. SVG is a document there.
    @Test fun galleryMediaIsPicturesAndVideos() {
        assertTrue(isGalleryMedia("image/jpeg"))
        assertTrue(isGalleryMedia("IMAGE/PNG; q=1"))
        assertTrue(isGalleryMedia("video/quicktime"))
        assertFalse(isGalleryMedia("image/svg+xml"))
        assertFalse(isGalleryMedia("application/pdf"))
        assertFalse(isGalleryMedia(null))
    }
}
