package dev.supermux.ui.work

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.ui.chat.ChatMediaItem
import dev.supermux.ui.chat.ChatMediaViewer
import dev.supermux.ui.chat.InlineVideo
import dev.supermux.ui.chat.MediaHandle
import dev.supermux.ui.chat.TINY_PNG_BYTES
import dev.supermux.ui.chat.attachmentImageKey
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.editor.FilePreviewKind
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.work.Download
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadSource
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The viewer's Save and the inline clip's system-player button on the fleet's downloads (#95): each starts the
 * download by its key and only waits for it, so closing the viewer or leaving the chat neither stops the download
 * nor loses it — it waits in the bar, announced — while staying hands the FILE over at once (R1). And a clip whose
 * download was cancelled in the bar shows its file card rather than fetching the whole clip again into memory.
 * The player is a stub: a headless worker must never load a native backend.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerSaveDownloadTest {
    // The center runs here: unconfined, so a start has run up to its first suspension by the time it returns.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tempFiles = mutableListOf<File>()

    @AfterTest fun tearDown() {
        scope.cancel()
        tempFiles.forEach { it.delete() }
    }

    private val picture = ChatMediaItem.FromAttachment(
        "m1",
        Attachment(file_id = "mux95-pic", kind = "photo", mime = "image/png", name = "a.png"),
        FilePreviewKind.Image,
    )
    private val clip = Attachment(file_id = "mux95-clip", kind = "video", mime = "video/mp4", name = "clip.mp4")

    /**
     * A host's attachments, keyed `att:h/<id>`: a picture shows from its bytes, a clip plays from the cache's file,
     * and a download waits for [gate], then leaves a real file (a hand-over checks it is there) — or, with [fails],
     * gets nothing.
     */
    private inner class HostMedia(private val fails: Boolean = false) : AttachmentMedia {
        val gate = CompletableDeferred<Unit>()

        override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? = TINY_PNG_BYTES

        override suspend fun playableUri(att: Attachment): String = "file:///cache/${att.file_id}.mp4"

        override fun downloadKey(att: Attachment): String = "att:h/${att.file_id}"

        override suspend fun download(att: Attachment, progress: (received: Long, total: Long?) -> Unit): DownloadedFile? {
            if (fails) return null
            gate.await()
            return realFile(att)
        }
    }

    /**
     * A clip as the media cache serves it: ONE transfer, which its inline player and its download share (the
     * `#file` slot). Cancelling the download ends that transfer with nothing, so the player gets no file either.
     */
    private inner class SharedClipMedia : AttachmentMedia {
        private val transfer = CompletableDeferred<Boolean>()
        val playerWaiting = AtomicBoolean(false)
        val byteReads = AtomicInteger(0)

        override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? {
            byteReads.incrementAndGet()
            return ByteArray(16)
        }

        override suspend fun playableUri(att: Attachment): String? {
            playerWaiting.set(true)
            return if (transfer.await()) "file:///cache/${att.file_id}.mp4" else null
        }

        override fun downloadKey(att: Attachment): String = "att:h/${att.file_id}"

        override suspend fun download(att: Attachment, progress: (received: Long, total: Long?) -> Unit): DownloadedFile? =
            if (transfer.await()) realFile(att) else null

        override fun cancelDownload(att: Attachment) {
            transfer.complete(false)
        }
    }

    private fun realFile(att: Attachment): DownloadedFile {
        val file = File.createTempFile("mux95-viewer", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        tempFiles += file
        return DownloadedFile(att.name ?: att.file_id, att.mime ?: "application/octet-stream", 3, file.absolutePath)
    }

    /** The chat's viewer, open at [picture] while [open], in a fleet with [center]; a pointer host (desktop). */
    private fun ComposeUiTest.viewer(platform: FakePlatform, center: DownloadCenter, media: AttachmentMedia, open: () -> Boolean = { true }) =
        setPlatformContent(platform) {
            CompositionLocalProvider(LocalDownloads provides center) {
                if (open()) {
                    ChatMediaViewer(
                        items = listOf(picture),
                        startKey = picture.key,
                        resolve = { item -> MediaHandle((item as ChatMediaItem.FromAttachment).att, media, attachmentImageKey(item.att)) },
                        onDismiss = {},
                        renderPlayer = { uri, _, _ -> Text("player $uri", modifier = Modifier.testTag("stub_player")) },
                    )
                }
            }
        }

    /** [clip] inline in a chat with [center]'s fleet while [shown]; [external] gets the player's system-player button. */
    private fun ComposeUiTest.inlineClip(
        platform: FakePlatform,
        center: DownloadCenter,
        media: AttachmentMedia,
        external: AtomicReference<(() -> Unit)?> = AtomicReference(null),
        shown: () -> Boolean = { true },
    ) = setPlatformContent(platform) {
        CompositionLocalProvider(LocalDownloads provides center) {
            if (shown()) {
                InlineVideo(
                    att = clip,
                    media = media,
                    renderPlayer = { _, onExternal, _ ->
                        external.set(onExternal)
                        Text("player", modifier = Modifier.testTag("stub_player"))
                    },
                )
            }
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

    private fun ComposeUiTest.awaitPicture() =
        settle { onAllNodesWithTag("chat_media_viewer_image").fetchSemanticsNodes().isNotEmpty() }

    private fun FakePlatform.handedOverNothing() = assertEquals(
        emptyList(),
        files.savedFiles + files.openedFiles + files.sharedFiles + files.openedSaved.map { it.name } + files.saved + files.opened,
        "nothing was saved, opened or shared",
    )

    // ── the viewer's Save ──────────────────────────────────────────────────────────────────────

    @Test fun theViewersSaveGoesOnAfterTheViewerCloses() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        val media = HostMedia()
        var open by mutableStateOf(true)
        viewer(platform, center, media, open = { open })
        awaitPicture()
        onNodeWithTag("chat_media_viewer_save").performClick()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("att:h/mux95-pic"), center.items.value.map { it.key })

        open = false
        waitForIdle()
        onNodeWithTag("chat_media_viewer").assertDoesNotExist()
        media.gate.complete(Unit)
        settle { heard.isNotEmpty() }

        assertIs<DownloadState.Done>(center.items.value.single().state)
        assertEquals(listOf("att:h/mux95-pic"), heard.map { it.key }, "announced: it waits in the bar")
        platform.handedOverNothing()
    }

    @Test fun theViewersSaveWhileItIsOpenSavesTheFileThenOpensIt() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        val media = HostMedia()
        viewer(platform, center, media)
        awaitPicture()
        onNodeWithTag("chat_media_viewer_save").performClick()
        settle { center.items.value.isNotEmpty() }
        media.gate.complete(Unit)
        settle { platform.files.openedSaved.isNotEmpty() }

        assertEquals(listOf("a.png"), platform.files.savedFiles)
        assertEquals(listOf("a.png"), platform.files.openedSaved.map { it.name })
        assertEquals(emptyList(), platform.files.saved, "the FILE is saved, not bytes read into memory")
        assertEquals(emptyList(), heard.toList(), "the viewer that waited acted on it: no notice")
    }

    @Test fun aSaveThatFailsWhileTheViewerIsOpenSaysSo() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        viewer(platform, center, HostMedia(fails = true))
        awaitPicture()
        onNodeWithTag("chat_media_viewer_save").performClick()
        settle { platform.notices.shown.isNotEmpty() }

        assertEquals(listOf("Couldn't download a.png"), platform.notices.shown)
        platform.handedOverNothing()
    }

    // ── the inline clip ────────────────────────────────────────────────────────────────────────

    @Test fun theClipsSystemPlayerButtonGoesOnAfterTheChatIsLeft() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        val media = HostMedia()
        val external = AtomicReference<(() -> Unit)?>(null)
        var shown by mutableStateOf(true)
        inlineClip(platform, center, media, external, shown = { shown })
        onNodeWithTag("attachment_video_poster").performClick()
        settle { external.get() != null }
        val openInTheSystemPlayer = assertNotNull(external.get(), "the clip plays")
        openInTheSystemPlayer()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("att:h/mux95-clip"), center.items.value.map { it.key })

        shown = false
        waitForIdle()
        media.gate.complete(Unit)
        settle { heard.isNotEmpty() }

        assertIs<DownloadState.Done>(center.items.value.single().state)
        assertEquals(listOf("att:h/mux95-clip"), heard.map { it.key }, "announced: it waits in the bar")
        platform.handedOverNothing()
    }

    // A3 shares one transfer between a clip's inline player and its download. Cancelled in the bar, the player gets
    // no file — and falling back to the bytes would fetch the whole clip again, into memory.
    @Test fun aClipWhoseDownloadWasCancelledShowsItsFileCardAndIsNeverReadIntoMemory() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val media = SharedClipMedia()
        inlineClip(platform, center, media)
        onNodeWithTag("attachment_video_poster").performClick()
        settle { media.playerWaiting.get() }

        // The same file is downloading in the bar (its viewer's Save started it, say), and he cancels it there.
        val source = object : DownloadSource {
            override suspend fun fetch(progress: (received: Long, total: Long?) -> Unit): DownloadedFile? = media.download(clip, progress)

            override fun cancel() = media.cancelDownload(clip)
        }
        center.start("att:h/mux95-clip", "clip.mp4", "video/mp4", DownloadPurpose.File, source).release()
        center.cancel("att:h/mux95-clip")
        settle { onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty() }

        assertEquals(0, media.byteReads.get(), "the clip was read into memory after its download was cancelled")
        onNodeWithTag("attachment_chip").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("stub_player").fetchSemanticsNodes().size)
    }
}
