package dev.supermux.ui.work

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.AttachmentList
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.work.Download
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadState
import dev.supermux.work.DownloadedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The chat's file card on the fleet's downloads (#95) — the bug Ahmet hit: a big file downloading from a chat
 * stopped, or lost what it was for, when he moved away. The card now starts the download in the center by its key
 * and only waits for it: leaving lets go, the download carries on and waits in the bar; staying hands the FILE over
 * at once, as a tap always did (R1); coming back shows the same download. A preview's in-memory media, which has no
 * download, keeps the bytes path.
 */
@OptIn(ExperimentalTestApi::class)
class FileCardDownloadTest {
    // The center runs here: unconfined, so a start has run up to its first suspension by the time it returns.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tempFiles = mutableListOf<File>()

    @AfterTest fun tearDown() {
        scope.cancel()
        tempFiles.forEach { it.delete() }
    }

    private val report = Attachment(file_id = "f1", kind = "document", mime = "application/pdf", size = 3, name = "report.pdf")

    /**
     * A host's attachments, keyed `att:rec/<id>`. A download says it has 42 of 100 bytes, waits for [gate] and then
     * leaves a real file (a hand-over checks the file is there); with [fails] it gets nothing. [byteReads] counts
     * what was read into memory instead.
     */
    private inner class HostMedia(private val fails: Boolean = false) : AttachmentMedia {
        val gate = CompletableDeferred<Unit>()
        val downloads = AtomicInteger(0)
        val byteReads = AtomicInteger(0)
        val cancelled = CopyOnWriteArrayList<String>()

        override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? {
            byteReads.incrementAndGet()
            return byteArrayOf(1, 2, 3)
        }

        override fun downloadKey(att: Attachment): String = "att:rec/${att.file_id}"

        override suspend fun download(att: Attachment, progress: (received: Long, total: Long?) -> Unit): DownloadedFile? {
            downloads.incrementAndGet()
            if (fails) return null
            progress(42, 100)
            gate.await()
            val file = File.createTempFile("mux95-card", ".pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            tempFiles += file
            return DownloadedFile(att.name ?: att.file_id, att.mime ?: "application/octet-stream", 3, file.absolutePath)
        }

        override fun cancelDownload(att: Attachment) {
            cancelled += att.file_id
        }
    }

    /** The card of [report] in a chat on [center]'s fleet, while [shown]: a pointer host (desktop) or a phone. */
    private fun ComposeUiTest.card(
        platform: FakePlatform,
        center: DownloadCenter,
        media: AttachmentMedia,
        pointer: Boolean = true,
        shown: () -> Boolean = { true },
    ) = setPlatformContent(
        platform,
        pointer = pointer,
        widthClass = if (pointer) WindowWidthClass.Expanded else WindowWidthClass.Compact,
        inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
    ) {
        CompositionLocalProvider(LocalDownloads provides center) {
            if (shown()) AttachmentList(attachments = listOf(report), alignEnd = false, media = media)
        }
    }

    /** What [center] announces as finished with nobody waiting (the shell's "<name> downloaded" notice). */
    private fun announced(center: DownloadCenter): List<Download> {
        val heard = CopyOnWriteArrayList<Download>()
        scope.launch { center.unclaimedCompletions.collect { heard += it } }
        return heard
    }

    /**
     * Gives work that hops threads (a hand-over checks its file off the main thread) up to [ms] to be [done], and
     * returns either way: the assertion after it says what was wanted.
     */
    private fun ComposeUiTest.settle(ms: Long = 5_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) {
            waitForIdle()
            Thread.sleep(10)
        }
    }

    private fun FakePlatform.handedOverNothing() = assertEquals(
        emptyList(),
        files.savedFiles + files.openedFiles + files.sharedFiles + files.openedSaved.map { it.name } + files.saved + files.opened,
        "nothing was saved, opened or shared",
    )

    // ── the regression ─────────────────────────────────────────────────────────────────────────

    @Test fun leavingTheChatNeitherStopsTheDownloadNorOpensItLater() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        val media = HostMedia()
        var shown by mutableStateOf(true)
        card(platform, center, media, shown = { shown })
        onNodeWithTag("attachment_chip").performClick()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("att:rec/f1"), center.items.value.map { it.key })

        // He moves to another chat; the download goes on without him.
        shown = false
        waitForIdle()
        onNodeWithTag("attachment_chip").assertDoesNotExist()
        media.gate.complete(Unit)
        settle { heard.isNotEmpty() }

        assertIs<DownloadState.Done>(center.items.value.single().state)
        assertEquals(listOf("att:rec/f1"), heard.map { it.key }, "announced once, for the bar and the notice")
        assertEquals(emptyList(), media.cancelled, "leaving stops nothing")
        // Nothing pops up over the chat he moved to (2a): no Save dialog, no other app.
        platform.handedOverNothing()
    }

    @Test fun stayingHandsTheFileOverAtOnce() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        val media = HostMedia()
        card(platform, center, media, pointer = false)
        onNodeWithTag("attachment_chip").performClick()
        settle { center.items.value.isNotEmpty() }
        media.gate.complete(Unit)
        settle { platform.files.openedFiles.isNotEmpty() }

        assertEquals(listOf("report.pdf"), platform.files.openedFiles)
        assertEquals(emptyList(), heard.toList(), "the card that waited acted on it: no notice")
        assertEquals(0, media.byteReads.get(), "the FILE is handed over, never read into memory")
    }

    @Test fun comingBackToTheChatShowsTheSameDownload() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope)
        val media = HostMedia()
        var shown by mutableStateOf(true)
        card(FakePlatform(), center, media, shown = { shown })
        onNodeWithTag("attachment_chip").performClick()
        settle { center.items.value.isNotEmpty() }
        shown = false
        waitForIdle()

        // A new card for the same attachment, never tapped, while the download is still under way.
        shown = true
        waitForIdle()
        onNode(
            hasTestTag("attachment_progress") and hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertEquals(1, media.downloads.get(), "the same download, not a second one")
    }

    @Test fun aPreviewsInMemoryMediaKeepsTheBytesPath() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        card(platform, center, AttachmentMedia.fromBytes { byteArrayOf(1, 2, 3) }, pointer = false)
        onNodeWithTag("attachment_chip").performClick()
        settle { platform.files.opened.isNotEmpty() }

        assertEquals(listOf("report.pdf|application/pdf|3"), platform.files.opened)
        assertEquals(emptyList(), center.items.value, "no download to list")
    }

    // ── what the card says ─────────────────────────────────────────────────────────────────────

    @Test fun aDownloadThatFailedOffersARetryAndATapRetries() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope)
        val media = HostMedia(fails = true)
        card(FakePlatform(), center, media)
        onNodeWithTag("attachment_chip").performClick()
        settle { onAllNodesWithText("Download failed — tap to retry").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Download failed — tap to retry").assertIsDisplayed()
        assertIs<DownloadState.Failed>(center.items.value.single().state)

        onNodeWithTag("attachment_chip").performClick()
        settle { media.downloads.get() == 2 }
        assertEquals(2, media.downloads.get())
    }

    // The bar's Cancel must reach the transfer itself: the media cache runs it on its own scope.
    @Test fun theBarsCancelStopsTheCardsTransferAndTheCardOffersARetry() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope)
        val media = HostMedia()
        card(FakePlatform(), center, media)
        onNodeWithTag("attachment_chip").performClick()
        settle { center.items.value.isNotEmpty() }
        center.cancel("att:rec/f1")
        settle { onAllNodesWithText("Download failed — tap to retry").fetchSemanticsNodes().isNotEmpty() }

        assertEquals(listOf("f1"), media.cancelled.toList())
        onNodeWithText("Download failed — tap to retry").assertIsDisplayed()
    }
}
