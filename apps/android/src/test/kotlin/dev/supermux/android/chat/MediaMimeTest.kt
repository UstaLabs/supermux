package dev.supermux.android.chat

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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
}
