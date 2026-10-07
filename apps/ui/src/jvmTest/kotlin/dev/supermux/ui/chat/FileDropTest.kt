package dev.supermux.ui.chat

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure halves of the JVM external-file drop actual: parsing the `file:` URIs Compose Desktop
 * hands back from `DragData.FilesList`, and filtering what still exists on disk. Moved here with
 * the drop target itself (they were `DesktopComposerAttachTest`'s drag-drop helper cases) — the
 * actual AWT drag session cannot be driven under `runComposeUiTest`, so these pin the parts that
 * can be.
 */
class FileDropTest {

    @Test fun filterExistingFiles_dropsNonexistentEntries() {
        val real = tempFile("keep.txt")
        val missing = File(real.parentFile, "does-not-exist.txt")
        assertEquals(listOf(real), filterExistingFiles(listOf(real, missing)))
    }

    @Test fun filterExistingFiles_dropsDirectories() {
        // A dropped directory (isFile == false) is filtered too — staging only handles single files.
        val dir = Files.createTempDirectory("composer-drop-dir").toFile().apply { deleteOnExit() }
        val real = tempFile("keep.txt")
        assertEquals(listOf(real), filterExistingFiles(listOf(dir, real)))
    }

    @Test fun composerFilesFromDragData_parsesFileUris() {
        val real = tempFile("dropped.txt")
        val uri = real.toURI().toString()
        assertEquals(listOf(real.absoluteFile), composerFilesFromDragData(listOf(uri)).map { it.absoluteFile })
    }

    @Test fun composerFilesFromDragData_dropsMalformedEntries() {
        val real = tempFile("dropped.txt")
        val uris = listOf(real.toURI().toString(), "not a uri at all")
        assertEquals(1, composerFilesFromDragData(uris).size)
    }

    @Test fun droppedPickedFile_carriesNameMimeAndStreamingBytes() {
        val real = tempFile("shot.png")
        val picked = droppedPickedFile(real)
        assertEquals("shot.png", picked.name)
        assertEquals("image/png", picked.mime)
        assertEquals("payload", picked.source.read(0, 64).decodeToString())
    }

    private fun tempFile(name: String): File {
        val dir = Files.createTempDirectory("composer-drop").toFile().apply { deleteOnExit() }
        return File(dir, name).apply { writeText("payload"); deleteOnExit() }
    }
}
