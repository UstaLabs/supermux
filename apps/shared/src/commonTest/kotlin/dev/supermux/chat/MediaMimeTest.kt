package dev.supermux.chat

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MediaMimeTest {
    @Test fun accepts_image_mimes() {
        assertTrue(isAttachableMediaMime("image/png"))
        assertTrue(isAttachableMediaMime("image/jpeg"))
    }

    @Test fun accepts_video_mimes() {
        assertTrue(isAttachableMediaMime("video/mp4"))
        assertTrue(isAttachableMediaMime("video/quicktime"))
        assertTrue(isAttachableMediaMime("video/x-matroska"))
    }

    @Test fun rejects_other_and_null() {
        assertFalse(isAttachableMediaMime("application/pdf"))
        assertFalse(isAttachableMediaMime("audio/mpeg"))
        assertFalse(isAttachableMediaMime("text/plain"))
        assertFalse(isAttachableMediaMime(null))
        assertFalse(isAttachableMediaMime(""))
    }

    @Test fun mimeForFileName_maps_known_extensions_and_gives_up_otherwise() {
        assertEquals("image/png", mimeForFileName("shot.PNG"))
        assertEquals("video/quicktime", mimeForFileName("clip.mov"))
        assertEquals("audio/mp4", mimeForFileName("dictation-1.m4a"))
        assertNull(mimeForFileName("archive.wat"))
        assertNull(mimeForFileName("Makefile"))
    }
}
