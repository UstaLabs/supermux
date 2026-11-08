package dev.supermux.work

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** #95: a finished download is handed to another app from `<staging>/<stagingFolderName(stored path)>/<real name>`. */
class StagingFolderNameTest {
    private val report = "/var/mobile/Containers/Data/Application/A1/Library/Caches/supermux-media/rec-1/3f9c4d00aa11.pdf"

    @Test fun oneStoredFileIsAlwaysStagedInTheSameFolder() {
        // A second Save or Share of the same download finds the copy the first one made.
        assertEquals(stagingFolderName(report), stagingFolderName(report))
    }

    @Test fun twoStoredFilesAreStagedApartEvenUnderOneName() {
        // Two different report.pdfs, each in a folder of its own: one never overwrites the other while an app reads it.
        assertNotEquals(stagingFolderName(report), stagingFolderName(report.replace("rec-1", "rec-2")))
        assertNotEquals(stagingFolderName(report), stagingFolderName(report.replace("3f9c4d00aa11", "3f9c4d00aa12")))
    }

    @Test fun theFolderIsSixteenHexDigits() {
        // A plain path segment on every file system: no separator, no dot, nothing to escape.
        for (path in listOf(report, "", "/ä/ö/ü.pdf", "C:\\Users\\u\\report.pdf")) {
            val folder = stagingFolderName(path)
            assertTrue(Regex("^[0-9a-f]{16}$").matches(folder), "$path -> $folder")
        }
    }
}
