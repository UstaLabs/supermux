package dev.supermux.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MediaKeyTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test fun aBrokerIdOnARecordIdBecomesHostSlashIdDotExt() {
        val key = assertNotNull(MediaKey.of("rec-1", id, "shot.PNG", "image/png"))
        assertEquals("rec-1/$id.png", key.relativePath)
    }

    @Test fun anythingButThirtyTwoLowercaseHexIsRefused() {
        for (bad in listOf("../../x", "a/b", "a\\b", "", id.dropLast(1), id.uppercase(), "$id/..", "..$id")) {
            assertNull(MediaKey.of("rec-1", bad, "a.png", "image/png"), "id '$bad' must not become a path")
        }
    }

    @Test fun aHostThatCouldNameAPathIsRefused() {
        for (bad in listOf("", "..", "../h", "a/b", "a\\b", "h h", "x".repeat(65))) {
            assertNull(MediaKey.of(bad, id, "a.png", "image/png"), "host '$bad' must not become a folder")
        }
    }

    @Test fun theExtensionComesFromTheNameThenTheMimeThenBin() {
        assertEquals("jpg", mediaExtension("IMG_1.JPG", "image/png"))
        assertEquals("mov", mediaExtension(null, "video/quicktime"))
        assertEquals("mp4", mediaExtension("clip", "video/mp4"))
        assertEquals("png", mediaExtension("x.verylongext", "image/png"))
        assertEquals("svg", mediaExtension(null, "image/svg+xml"))
        assertEquals("bin", mediaExtension("noext", null))
        assertEquals("bin", mediaExtension(null, "image/x-icon"))
        assertEquals("bin", mediaExtension(null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }

    @Test fun anExtensionCanNeverCarryASeparatorOrADot() {
        // The name's directory part is ignored, so a traversal hidden in the name falls to the mime.
        assertEquals("jpg", mediaExtension("a.jpg/../../x", "image/jpeg"))
        assertEquals("png", mediaExtension("evil.p\\ng", "image/png"))
        assertEquals("bin", mediaExtension("a.p/ng", null))
    }
}
