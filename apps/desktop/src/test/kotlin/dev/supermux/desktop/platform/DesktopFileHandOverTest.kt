package dev.supermux.desktop.platform

import dev.supermux.ui.platform.FileAction
import dev.supermux.work.DownloadedFile
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #95: a finished download reaches the OS as the FILE it already is, under its real name; no byte of it is read into
 * memory. Only the STAGING half ([stageNamed]) is driven here. NEVER call `openFile` or `openLocalFile` from a test:
 * it forks a real viewer, and on this box `xdg-open` hands the file to kde-open, which waits on an invisible dialog
 * and hangs Gradle. `saveFile` raises a modal AWT dialog, so only its answer for a download with no file is asked.
 */
class DesktopFileHandOverTest {
    private val made = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        made.forEach { it.deleteRecursively() }
        made.clear()
    }

    /** A download as the media store keeps it: a file under an id of its own, not under its real [name]. */
    private fun stored(name: String, content: String): DownloadedFile {
        val dir = Files.createTempDirectory("sm-download-").toFile().also { made += it }
        val file = File(dir, "3f9c4d7a1b2e.bin").also { it.writeText(content) }
        return DownloadedFile(name, "application/pdf", file.length(), file.absolutePath)
    }

    /** [stageNamed], remembering the folder it made so a test leaves nothing behind. */
    private fun stage(file: DownloadedFile): File? = stageNamed(file)?.also { made += it.parentFile }

    @Test
    fun `two downloads with the same name from different paths stage apart, each with its own bytes`() {
        // The same length on purpose: a staging that went by the name and the size alone would hand over the first twice.
        val first = stored("report.pdf", "AAAA first")
        val second = stored("report.pdf", "BBBB other")

        val a = assertNotNull(stage(first))
        val b = assertNotNull(stage(second))

        assertTrue(a.isFile && b.isFile, "both are staged as files")
        assertNotEquals(a.parentFile, b.parentFile, "a folder per download, or the second overwrites the first")
        assertEquals("AAAA first", a.readText())
        assertEquals("BBBB other", b.readText())
    }

    @Test
    fun `staging the same download again reuses the staged file`() {
        val download = stored("report.pdf", "0123456789")
        val first = assertNotNull(stage(download))
        // A staged file that is already there, the same size but marked: a second staging that made the file again
        // (a copy of a big file on every Open) would overwrite the mark.
        val marker = "m".repeat(download.size.toInt())
        assertTrue(first.delete(), "it was staged")
        first.writeText(marker)

        val second = assertNotNull(stage(download))

        assertEquals(first, second)
        assertEquals(marker, second.readText(), "reused, not staged again")
    }

    @Test
    fun `the staged file keeps the download's real name`() {
        val staged = assertNotNull(stage(stored("Q3 report (final) v2.pdf", "x")))

        assertEquals("Q3 report (final) v2.pdf", staged.name)
        assertEquals("x", staged.readText())
    }

    @Test
    fun `a name with a directory part cannot take the staged file out of the staging folder`() {
        val staged = assertNotNull(stage(stored("../../escape.txt", "x")))
        // This process stages its attachments in one folder; the folder of the download has to be right inside it.
        val staging = assertNotNull(stageAttachmentForTest("probe-${System.nanoTime()}", ByteArray(0)))
            .also { made += it }.parentFile

        assertEquals("escape.txt", staged.name)
        assertEquals(staging.canonicalFile, staged.canonicalFile.parentFile.parentFile)
    }

    @Test
    fun `a download with no file on disk is not staged`() {
        val inTheBrowser = DownloadedFile("report.pdf", "application/pdf", 3, path = null, handle = Any())

        assertNull(stageNamed(inTheBrowser))
    }

    /** Answered before any dialog: with a display, asking where to save would wait on a modal one. */
    @Test
    fun `a download with no file on disk is not saved either`() = runTest {
        val inTheBrowser = DownloadedFile("report.pdf", "application/pdf", 3, path = null, handle = Any())

        assertNull(DesktopFileAccess().saveFile(inTheBrowser))
    }

    @Test
    fun `desktop offers Open and Save but no share sheet`() {
        assertEquals(setOf(FileAction.Open, FileAction.Save), DesktopFileAccess().fileActions)
    }

    @Test
    fun `a download fetched again under the same path is staged again`() {
        val old = stored("report.pdf", "old bytes")
        val source = File(old.path!!)
        val first = assertNotNull(stage(old))
        assertTrue(first.isFile, "the old one is staged")

        // Storage > Clear, then the same file fetched again, longer now: a NEW file at the same path.
        assertTrue(source.delete())
        source.writeText("the newer, longer bytes")
        val fresh = old.copy(size = source.length())
        val second = assertNotNull(stage(fresh))

        assertEquals(first, second)
        assertEquals("the newer, longer bytes", second.readText())
    }

    /**
     * Open hands the viewer a COPY. A link would be the stored file itself: a viewer that saves in place would rewrite the
     * media store's entry (the cache never revalidates a file id), and the link would keep the bytes alive after
     * Storage > Clear.
     */
    @Test
    fun `the staged file is a separate copy, so editing it leaves the stored file unchanged`() {
        val download = stored("report.pdf", "original bytes")
        val staged = assertNotNull(stage(download))

        // In place, as a viewer that saves in place does.
        staged.writeText("edited in the viewer")

        assertEquals("original bytes", File(download.path!!).readText(), "the media store's file is not touched")
        assertEquals("edited in the viewer", staged.readText())
    }
}
