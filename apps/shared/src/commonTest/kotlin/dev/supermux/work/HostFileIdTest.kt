package dev.supermux.work

import dev.supermux.media.MediaKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostFileIdTest {
    private val path = "/home/u/report.pdf"
    private val mtime = 1_762_500_000_000L
    private val size = 4096L

    @Test fun theSameFileGetsTheSameId() {
        assertEquals(hostFileId(path, mtime, size), hostFileId(path, mtime, size))
    }

    @Test fun aChangeToAnyPartGivesAnotherId() {
        val id = hostFileId(path, mtime, size)
        assertNotEquals(id, hostFileId("/home/u/report2.pdf", mtime, size))
        assertNotEquals(id, hostFileId(path, mtime + 1, size))
        assertNotEquals(id, hostFileId(path, mtime, size + 1))
    }

    @Test fun itIsShapedLikeABrokerFileIdSoTheStoreTakesIt() {
        for (id in listOf(hostFileId(path, mtime, size), hostFileId("", 0, 0))) {
            assertTrue(Regex("^[0-9a-f]{32}$").matches(id), id)
            assertNotNull(MediaKey.of("rec-1", id, "report.pdf", null))
        }
    }
}
