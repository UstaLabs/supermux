package dev.supermux.android.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileDownloadSinkTest {
    @Test
    fun writesLandInTheFileAndClearStartsOver() {
        val file = File.createTempFile("update", ".apk.part")
        try {
            file.writeBytes(byteArrayOf(9, 9, 9, 9, 9))
            FileDownloadSink(file).use { sink ->
                // A leftover .part from an earlier try is not resumed into: the sink starts empty.
                assertEquals(0L, sink.size)
                sink.write(byteArrayOf(0, 1, 2, 3), 1, 2)
                assertEquals(2L, sink.size)
                sink.clear()
                assertEquals(0L, sink.size)
                sink.write(byteArrayOf(4, 5, 6), 0, 3)
                assertEquals(3L, sink.size)
            }
            assertArrayEquals(byteArrayOf(4, 5, 6), file.readBytes())
        } finally {
            file.delete()
        }
    }
}
