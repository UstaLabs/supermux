package dev.supermux.android.platform

import android.content.ContextWrapper
import dev.supermux.ui.platform.FileAction
import dev.supermux.ui.platform.SavedFile
import dev.supermux.work.DownloadedFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #95: a finished download reaches the OS as the FILE it already is, under its real name; no byte of it is read into
 * memory (a download can be gigabytes, and a phone's heap is a fraction of that). Driven without a device, as
 * [AndroidChatSeamsTest] is: the staging ([stageNamed]) takes the folder it stages into, and the save ([saveStoredFile])
 * takes the two things only a device has — the save dialog's answer and a stream into the document it chose.
 *
 * What a JVM test cannot reach is the intent half of Open and Share (`FileProvider`, `startActivity`). Memory is not
 * measurable here either: that no whole-file read (`readBytes`) is on these paths is a matter of reading the code, and
 * what is asserted is that a 9 MB file comes through intact.
 */
class AndroidFileHandOverTest {
    private val made = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        made.forEach { it.deleteRecursively() }
        made.clear()
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { made += it }

    /** Stands for `cacheDir/attachments`, the folder the FileProvider shares. */
    private val attachments: File by lazy { tempDir("sm-attachments-") }

    /** A download as the media store keeps it: a file under an id of its own, not under its real [name]. */
    private fun stored(name: String, bytes: ByteArray): DownloadedFile {
        val file = File(tempDir("sm-download-"), "3f9c4d7a1b2e.bin").also { it.writeBytes(bytes) }
        return DownloadedFile(name, "application/pdf", file.length(), file.absolutePath)
    }

    private fun stored(name: String, content: String): DownloadedFile = stored(name, content.toByteArray())

    private fun nineMegabytes(): ByteArray = ByteArray(9 * 1024 * 1024) { (it * 31 + it / 251).toByte() }

    private fun sha256Of(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                digest.update(chunk, 0, n)
            }
        }
        return digest.digest()
    }

    // ── staging: what Open and Share hand to the FileProvider ────────────────

    @Test
    fun `two downloads with the same name from different paths stage apart, each with its own bytes`() {
        // The same length on purpose: a staging that went by the name and the size alone would hand over the first twice.
        val first = stored("report.pdf", "AAAA first")
        val second = stored("report.pdf", "BBBB other")

        val a = assertNotNull(stageNamed(first, attachments))
        val b = assertNotNull(stageNamed(second, attachments))

        assertTrue(a.isFile && b.isFile, "both are staged as files")
        assertNotEquals(a.parentFile, b.parentFile, "a folder per download, or the second overwrites the first")
        assertEquals("AAAA first", a.readText())
        assertEquals("BBBB other", b.readText())
    }

    @Test
    fun `a 9 MB download is staged as a byte-identical copy`() {
        val download = stored("clip.mp4", nineMegabytes())

        val staged = assertNotNull(stageNamed(download, attachments))

        assertEquals(9L * 1024 * 1024, staged.length())
        assertContentEquals(sha256Of(File(download.path!!)), sha256Of(staged))
    }

    @Test
    fun `staging the same download again reuses the staged file`() {
        val download = stored("report.pdf", "0123456789")
        val first = assertNotNull(stageNamed(download, attachments))
        // A staged file that is already there, the same size but marked: a second staging that made the file again
        // (a copy of a big file on every Open) would overwrite the mark.
        val marker = "m".repeat(download.size.toInt())
        assertTrue(first.delete(), "it was staged")
        first.writeText(marker)

        val second = assertNotNull(stageNamed(download, attachments))

        assertEquals(first, second)
        assertEquals(marker, second.readText(), "reused, not staged again")
    }

    @Test
    fun `the staged file keeps the download's real name`() {
        val staged = assertNotNull(stageNamed(stored("Q3 report (final) v2.pdf", "x"), attachments))

        assertEquals("Q3 report (final) v2.pdf", staged.name)
        assertEquals("x", staged.readText())
    }

    @Test
    fun `a name with a directory part cannot take the staged file out of the staging folder`() {
        val staged = assertNotNull(stageNamed(stored("../../escape.txt", "x"), attachments))

        assertEquals("escape.txt", staged.name)
        // <staging>/<unique>/escape.txt: the folder of the download is right inside the staging folder.
        assertEquals(attachments.canonicalFile, staged.canonicalFile.parentFile.parentFile)
    }

    @Test
    fun `a download with no file on disk is not staged`() {
        val inTheBrowser = DownloadedFile("report.pdf", "application/pdf", 3, path = null, handle = Any())

        assertNull(stageNamed(inTheBrowser, attachments))
    }

    @Test
    fun `a download whose file is gone is not staged`() {
        val download = stored("report.pdf", "payload")
        assertTrue(File(download.path!!).delete())

        assertNull(stageNamed(download, attachments))
    }

    @Test
    fun `a download fetched again under the same path is staged again`() {
        val old = stored("report.pdf", "old bytes")
        val source = File(old.path!!)
        val first = assertNotNull(stageNamed(old, attachments))

        // Storage > Clear, then the same file fetched again, longer now: a NEW file at the same path.
        assertTrue(source.delete())
        source.writeText("the newer, longer bytes")
        val second = assertNotNull(stageNamed(old.copy(size = source.length()), attachments))

        assertEquals(first, second)
        assertEquals("the newer, longer bytes", second.readText())
    }

    /** What Open hands over is a copy: a viewer that saves in place must not rewrite the media store's entry. */
    @Test
    fun `the staged file is a separate copy, so editing it leaves the stored file unchanged`() {
        val download = stored("report.pdf", "original bytes")
        val staged = assertNotNull(stageNamed(download, attachments))

        staged.writeText("edited in the viewer")

        assertEquals("original bytes", File(download.path!!).readText(), "the media store's file is not touched")
    }

    @Test
    fun `staging leaves nothing but the staged file in its folder`() {
        val staged = assertNotNull(stageNamed(stored("report.pdf", "payload"), attachments))

        assertEquals(listOf("report.pdf"), staged.parentFile.list()?.toList())
    }

    // ── the actions this host offers ─────────────────────────────────────────

    @Test
    fun `android offers Open, Save and Share`() {
        // A Context is an abstract stub in a JVM test, but a ContextWrapper over nothing is one that nothing here asks.
        val files = AndroidFileAccess(ContextWrapper(null), SaveHost())

        assertEquals(setOf(FileAction.Open, FileAction.Save, FileAction.Share), files.fileActions)
    }

    // ── Save: the save dialog, then the file streamed into the document it chose ──

    /** The host's own save dialog, asked through the real [SaveHost]: the user cancels it. */
    @Test
    fun `saveFile asks the save dialog for the download's real name, and a cancelled dialog saves nothing`() = runTest {
        val download = stored("docs/report.pdf", "payload")
        val host = SaveHost()
        var asked: Pair<String, String>? = null
        host.onLaunch = { mime, name ->
            asked = mime to name
            host.deliver(null)
        }

        val saved = AndroidFileAccess(ContextWrapper(null), host).saveFile(download)

        assertNull(saved)
        assertEquals("application/pdf" to "report.pdf", asked)
    }

    @Test
    fun `a save writes the stored file's bytes into the target stream`() = runTest {
        val bytes = nineMegabytes()
        val download = stored("docs/report.pdf", bytes)
        val sink = ByteArrayOutputStream()
        var asked: Pair<String, String>? = null

        val saved = saveStoredFile<String>(
            download,
            chooseTarget = { mime, name ->
                asked = mime to name
                "content://docs/report.pdf"
            },
            openTarget = { sink },
        )

        assertEquals(SavedFile("report.pdf", "content://docs/report.pdf"), saved)
        assertEquals("application/pdf" to "report.pdf", asked)
        assertContentEquals(bytes, sink.toByteArray())
    }

    /** Answered before any dialog: with nothing to copy, asking where to put it would be asking for nothing. */
    @Test
    fun `a download with no file on disk is not saved, and nothing is asked`() = runTest {
        val inTheBrowser = DownloadedFile("report.pdf", "application/pdf", 3, path = null, handle = Any())
        var asked = false

        val saved = saveStoredFile<String>(
            inTheBrowser,
            chooseTarget = { _, _ ->
                asked = true
                "content://docs/report.pdf"
            },
            openTarget = { ByteArrayOutputStream() },
        )

        assertNull(saved)
        assertFalse(asked, "no dialog for a download with no file")
    }

    @Test
    fun `a cancelled save opens no stream`() = runTest {
        var opened = false

        val saved = saveStoredFile<String>(
            stored("report.pdf", "payload"),
            chooseTarget = { _, _ -> null },
            openTarget = {
                opened = true
                ByteArrayOutputStream()
            },
        )

        assertNull(saved)
        assertFalse(opened)
    }

    @Test
    fun `a target the provider cannot open is not saved`() = runTest {
        val saved = saveStoredFile<String>(
            stored("report.pdf", "payload"),
            chooseTarget = { _, _ -> "content://docs/gone" },
            openTarget = { null },
        )

        assertNull(saved)
    }

    @Test
    fun `a copy that fails midway is not saved`() = runTest {
        val diskFull = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("disk full")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("disk full")
            }
        }

        val saved = saveStoredFile<String>(
            stored("report.pdf", ByteArray(1024 * 1024) { 7 }),
            chooseTarget = { _, _ -> "content://docs/report.pdf" },
            openTarget = { diskFull },
        )

        assertNull(saved)
    }
}
