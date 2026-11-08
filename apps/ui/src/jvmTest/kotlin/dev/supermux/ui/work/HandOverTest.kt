package dev.supermux.ui.work

import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.FileAction
import dev.supermux.ui.platform.SavedFile
import dev.supermux.work.Download
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadSource
import dev.supermux.work.DownloadState
import dev.supermux.work.DownloadedFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A finished download handed over as the FILE it is (#95): a tap's own rule (R1) and the bar's actions (R3),
 * each of which first checks that the file is still there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandOverTest {
    // A real file, as the media store leaves one.
    private val report = File.createTempFile("report", ".pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @AfterTest fun deleteTheFile() {
        report.delete()
    }

    private fun reportFile(path: String? = report.absolutePath, handle: Any? = null) =
        DownloadedFile("report.pdf", "application/pdf", 3, path, handle)

    /** [done] finished in a real center on the test's scope, with nobody waiting: how the bar finds it. */
    private fun TestScope.finished(done: DownloadedFile = reportFile()): Pair<DownloadCenter, Download> {
        val center = DownloadCenter(this)
        val source = object : DownloadSource {
            override suspend fun fetch(progress: (Long, Long?) -> Unit): DownloadedFile = done
        }
        center.start("att:h/f1", done.name, done.mime, DownloadPurpose.File, source).release()
        runCurrent()
        val item = center.items.value.single()
        assertIs<DownloadState.Done>(item.state)
        return center to item
    }

    /** A file the OS reclaimed (or Storage › Clear removed) after it finished. */
    private fun removedFile(): DownloadedFile {
        val gone = File.createTempFile("gone", ".pdf").apply { delete() }
        return reportFile(path = gone.absolutePath)
    }

    private fun FakePlatform.handedOverNothing() =
        assertEquals(emptyList(), files.savedFiles + files.openedFiles + files.sharedFiles + files.openedSaved.map { it.name })

    // ── a tap (R1): what the screen did before #95, with the file ──────────────────────────────────

    @Test fun aPointerHostSavesItThenOpensWhatWasSaved() = runTest {
        val platform = FakePlatform()
        val (center, item) = finished()
        assertTrue(handOverAsTapped(platform, pointer = true, center, item))
        assertEquals(listOf("report.pdf"), platform.files.savedFiles)
        assertEquals(listOf(SavedFile("report.pdf", "/fake/report.pdf")), platform.files.openedSaved)
        assertEquals(emptyList(), platform.files.openedFiles)
    }

    @Test fun aCancelledSaveOpensNothingAndIsNoError() = runTest {
        val platform = FakePlatform().apply { files.saveResult = false }
        val (center, item) = finished()
        assertFalse(handOverAsTapped(platform, pointer = true, center, item))
        assertEquals(listOf("report.pdf"), platform.files.savedFiles)
        assertEquals(emptyList(), platform.files.openedSaved)
        assertEquals(emptyList(), platform.notices.shown)
    }

    @Test fun aPointerHostThatCannotSaveOpensIt() = runTest {
        val platform = FakePlatform().apply { files.fileActions = setOf(FileAction.Open) }
        val (center, item) = finished()
        assertTrue(handOverAsTapped(platform, pointer = true, center, item))
        assertEquals(listOf("report.pdf"), platform.files.openedFiles)
        assertEquals(emptyList(), platform.files.savedFiles)
    }

    @Test fun aTouchHostOpensIt() = runTest {
        val platform = FakePlatform()
        val (center, item) = finished()
        assertTrue(handOverAsTapped(platform, pointer = false, center, item))
        assertEquals(listOf("report.pdf"), platform.files.openedFiles)
        assertEquals(emptyList(), platform.files.savedFiles)
        assertEquals(emptyList(), platform.files.sharedFiles)
    }

    // iOS: no Open of its own; its "open in…" is the share sheet.
    @Test fun aTouchHostThatCannotOpenSharesIt() = runTest {
        val platform = FakePlatform().apply { files.fileActions = setOf(FileAction.Save, FileAction.Share) }
        val (center, item) = finished()
        assertTrue(handOverAsTapped(platform, pointer = false, center, item))
        assertEquals(listOf("report.pdf"), platform.files.sharedFiles)
        assertEquals(emptyList(), platform.files.openedFiles)
        assertEquals(emptyList(), platform.files.savedFiles)
    }

    @Test fun anOpenThatNoAppTookSaysSo() = runTest {
        val platform = FakePlatform().apply { files.openResult = false }
        val (center, item) = finished()
        assertFalse(handOverAsTapped(platform, pointer = false, center, item))
        assertEquals(listOf("Couldn't open report.pdf"), platform.notices.shown)
    }

    @Test fun aRemovedFileBecomesARetryableFailureAndNothingOpens() = runTest {
        val platform = FakePlatform()
        val (center, item) = finished(removedFile())
        assertFalse(handOverAsTapped(platform, pointer = false, center, item))
        assertIs<DownloadState.Failed>(center.items.value.single().state)
        assertEquals(listOf("report.pdf was removed — retry to download it again"), platform.notices.shown)
        platform.handedOverNothing()
    }

    // ── the bar's actions (R3) ─────────────────────────────────────────────────────────────────

    @Test fun theBarsOpenOpensIt() = runTest {
        val platform = FakePlatform()
        val (center, item) = finished()
        assertTrue(handOver(platform, center, item, FileAction.Open))
        assertEquals(listOf("report.pdf"), platform.files.openedFiles)
        assertEquals(emptyList(), platform.files.savedFiles)
    }

    @Test fun theBarsSaveOnlySaves() = runTest {
        val platform = FakePlatform()
        val (center, item) = finished()
        assertTrue(handOver(platform, center, item, FileAction.Save))
        assertEquals(listOf("report.pdf"), platform.files.savedFiles)
        assertEquals(emptyList(), platform.files.openedSaved)
        assertEquals(emptyList(), platform.files.openedFiles)
    }

    @Test fun theBarsShareSharesIt() = runTest {
        val platform = FakePlatform()
        val (center, item) = finished()
        assertTrue(handOver(platform, center, item, FileAction.Share))
        assertEquals(listOf("report.pdf"), platform.files.sharedFiles)
        assertEquals(emptyList(), platform.files.openedFiles)
    }

    @Test fun theBarsOpenThatNoAppTookSaysSo() = runTest {
        val platform = FakePlatform().apply { files.openResult = false }
        val (center, item) = finished()
        assertFalse(handOver(platform, center, item, FileAction.Open))
        assertEquals(listOf("Couldn't open report.pdf"), platform.notices.shown)
    }

    @Test fun theBarsActionsCheckTheFileFirstToo() = runTest {
        val platform = FakePlatform()
        val (center, item) = finished(removedFile())
        assertFalse(handOver(platform, center, item, FileAction.Save))
        assertIs<DownloadState.Failed>(center.items.value.single().state)
        assertEquals(listOf("report.pdf was removed — retry to download it again"), platform.notices.shown)
        platform.handedOverNothing()
    }

    // The web keeps a finished download as a browser Blob: no path, and still there.
    @Test fun aBrowserBlobIsThereWithoutAPath() = runTest {
        val platform = FakePlatform()
        val (center, item) = finished(reportFile(path = null, handle = Any()))
        assertTrue(handOver(platform, center, item, FileAction.Open))
        assertEquals(listOf("report.pdf"), platform.files.openedFiles)
        assertIs<DownloadState.Done>(center.items.value.single().state)
    }
}
