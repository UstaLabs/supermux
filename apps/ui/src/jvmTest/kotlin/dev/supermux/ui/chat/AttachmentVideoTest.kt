package dev.supermux.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import dev.supermux.ui.work.LocalDownloads
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadOrigin
import dev.supermux.work.DownloadState
import dev.supermux.work.DownloadedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.ui.platform.FakePlatform
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

/**
 * Inline video in the shared chat stream. The real player mounts Compose Media Player's native
 * backend, so every test here injects the `renderPlayer` seam — a headless Gradle worker must never
 * load AVFoundation/GStreamer. What is asserted is the state machine around it: nothing is
 * downloaded until the user clicks, a failed download or a backend error falls back to the download
 * chip, and the staged file is named so its demuxer can be picked.
 */
@OptIn(ExperimentalTestApi::class)
class AttachmentVideoTest {

    private fun videoAtt(kind: String? = "video", mime: String? = "video/mp4") =
        Attachment(file_id = "v1", kind = kind, mime = mime, name = "clip.mp4")

    @Test fun video_shows_a_poster_and_downloads_nothing_until_clicked() = runComposeUiTest {
        val fetches = AtomicInteger(0)
        setPlatformContent {
            AttachmentList(
                attachments = listOf(videoAtt()),
                alignEnd = false,
                media = AttachmentMedia.fromBytes { fetches.incrementAndGet(); ByteArray(4) },
            )
        }
        onNodeWithTag("attachment_video_poster").assertIsDisplayed()
        assertEquals(0, fetches.get(), "a transcript must not eagerly download every clip")
        assertEquals(0, onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().size)
    }

    @Test fun the_poster_shows_the_clips_length_before_anything_is_fetched() = runComposeUiTest {
        val fetches = AtomicInteger(0)
        setPlatformContent {
            AttachmentList(
                attachments = listOf(videoAtt().copy(duration_ms = 75_000)),
                alignEnd = false,
                media = AttachmentMedia.fromBytes { fetches.incrementAndGet(); ByteArray(4) },
            )
        }
        onNodeWithTag("attachment_video_duration", useUnmergedTree = true).assertTextEquals("1:15")
        assertEquals(0, fetches.get())
    }

    @Test fun video_note_and_mime_only_videos_also_get_the_poster() = runComposeUiTest {
        setPlatformContent {
            AttachmentList(
                attachments = listOf(
                    videoAtt(kind = "video_note", mime = null),
                    videoAtt(kind = null, mime = "video/quicktime"),
                ),
                alignEnd = false,
                media = AttachmentMedia.fromBytes { ByteArray(4) },
            )
        }
        assertEquals(2, onAllNodesWithTag("attachment_video_poster").fetchSemanticsNodes().size)
    }

    @Test fun clicking_the_poster_downloads_then_mounts_the_player() = runComposeUiTest {
        val platform = FakePlatform()
        val mounted = AtomicReference<String?>(null)
        setPlatformContent(platform) {
            InlineVideo(
                att = videoAtt(),
                media = AttachmentMedia.fromBytes { ByteArray(16) },
                renderPlayer = { uri, _, _ ->
                    mounted.set(uri)
                    Text("player", modifier = Modifier.testTag("stub_player"))
                },
            )
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("stub_player").fetchSemanticsNodes().isNotEmpty()
        }
        val uri = assertNotNull(mounted.get(), "player must be handed the staged URI")
        assertTrue(uri.startsWith("file://"), "the backend opens the clip by URI — got $uri")
        assertEquals(listOf("video_v1.mp4"), platform.files.stagedNames, "keep the container hint the demuxer needs")
        assertEquals(listOf(16), platform.files.stagedBytes, "bytes must reach the staging seam")
    }

    @Test fun a_slow_download_shows_the_spinner_not_the_chip() = runComposeUiTest {
        val gate = CompletableDeferred<ByteArray?>()
        setPlatformContent {
            InlineVideo(
                att = videoAtt(),
                media = AttachmentMedia.fromBytes { gate.await() },
                renderPlayer = { _, _, _ -> Text("player", modifier = Modifier.testTag("stub_player")) },
            )
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_video_loading").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("stub_player").fetchSemanticsNodes().size)
    }

    @Test fun a_failed_download_falls_back_to_the_chip() = runComposeUiTest {
        setPlatformContent {
            InlineVideo(
                att = videoAtt(),
                media = AttachmentMedia.fromBytes { null },
                renderPlayer = { _, _, _ -> Text("player", modifier = Modifier.testTag("stub_player")) },
            )
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_chip").assertIsDisplayed()
    }

    @Test fun a_failed_staging_falls_back_to_the_chip() = runComposeUiTest {
        // The host could not write the temp file (a full disk, a locked cache dir).
        val platform = FakePlatform()
        platform.files.stageFails = true
        setPlatformContent(platform) {
            InlineVideo(
                att = videoAtt(),
                media = AttachmentMedia.fromBytes { ByteArray(8) },
                renderPlayer = { _, _, _ -> Text("player", modifier = Modifier.testTag("stub_player")) },
            )
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_chip").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("stub_player").fetchSemanticsNodes().size)
    }

    @Test fun a_backend_error_falls_back_to_the_chip() = runComposeUiTest {
        // The player reports a codec/source error (e.g. a container the OS backend cannot demux);
        // the transcript must not be left with a black rectangle.
        setPlatformContent {
            InlineVideo(
                att = videoAtt(),
                media = AttachmentMedia.fromBytes { ByteArray(8) },
                renderPlayer = { _, _, onError -> onError() },
            )
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_chip").assertIsDisplayed()
    }

    @Test fun the_external_button_hands_the_downloaded_bytes_to_the_host() = runComposeUiTest {
        val platform = FakePlatform()
        val external = AtomicReference<(() -> Unit)?>(null)
        setPlatformContent(platform) {
            InlineVideo(
                att = videoAtt(),
                media = AttachmentMedia.fromBytes { ByteArray(9) },
                renderPlayer = { _, onExternal, _ ->
                    external.set(onExternal)
                    Text("player", modifier = Modifier.testTag("stub_player"))
                },
            )
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("stub_player").fetchSemanticsNodes().isNotEmpty()
        }
        external.get()!!.invoke()
        // Pointer host → the shared save-as branch, same as the chip.
        waitUntil(timeoutMillis = 5_000L) { platform.files.saved.isNotEmpty() }
        assertEquals(listOf("clip.mp4|video/mp4|9"), platform.files.saved)
    }

    @Test fun temp_name_keeps_the_extension_and_falls_back_to_a_default() {
        assertEquals("video_v1.MOV", attachmentTempName("video_v1", "holiday.MOV", "mp4"))
        assertEquals("video_v2.mp4", attachmentTempName("video_v2", null, "mp4"))
        assertEquals("video_v3.mp4", attachmentTempName("video_v3", "noextension", "mp4"))
    }

    @Test fun two_clips_do_not_collide_in_the_staging_dir() {
        // Names are keyed by file_id, so two clips called clip.mp4 stay separate files.
        assertTrue(
            attachmentTempName("video_aaa", "clip.mp4", "mp4") !=
                attachmentTempName("video_bbb", "clip.mp4", "mp4"),
        )
    }

    @Test fun temp_name_strips_directories_and_never_goes_blank() {
        assertEquals("shot.png", attachmentTempName("reports/2026/shot.png", "shot.png", "bin"))
        assertEquals("file.bin", attachmentTempName("", null, "bin"))
    }

    // ── ▶ through the fleet's downloads (#95, round 2) ───────────────────────────────────────

    /**
     * A host's clips, keyed `att:rec/<id>`: a download says it has 42 of 100 bytes and waits for [gate]; once it is
     * done the clip plays from the stored file. [playableReads] counts what played without the center.
     */
    private class HostClips : AttachmentMedia {
        val gate = CompletableDeferred<Unit>()
        val downloads = AtomicInteger(0)
        val cancelled = CopyOnWriteArrayList<String>()
        @Volatile var stored = false

        override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? = null

        override suspend fun playableUri(att: Attachment): String? = if (stored) "file:///cache/${att.file_id}.mp4" else null

        override fun downloadKey(att: Attachment): String = "att:rec/${att.file_id}"

        override suspend fun stored(att: Attachment): DownloadedFile? =
            if (stored) DownloadedFile(att.name ?: att.file_id, att.mime ?: "video/mp4", 100, "/cache/${att.file_id}.mp4") else null

        override suspend fun download(att: Attachment, progress: (received: Long, total: Long?) -> Unit): DownloadedFile? {
            downloads.incrementAndGet()
            progress(42, 100)
            gate.await()
            stored = true
            return DownloadedFile(att.name ?: att.file_id, att.mime ?: "video/mp4", 100, "/cache/${att.file_id}.mp4")
        }

        override fun cancelDownload(att: Attachment) {
            cancelled += att.file_id
        }
    }

    private val centerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest fun endTheCenter() = centerScope.cancel()

    @Test fun play_is_a_download_in_the_center_with_its_chat_and_message() = runComposeUiTest {
        val center = DownloadCenter(centerScope)
        val media = HostClips()
        var shown by mutableStateOf(true)
        val mounted = AtomicReference<String?>(null)
        setPlatformContent {
            CompositionLocalProvider(LocalDownloads provides center, LocalChatSessionId provides "s1", LocalChatMediaEntry provides "m7") {
                if (shown) {
                    InlineVideo(
                        att = videoAtt(),
                        media = media,
                        renderPlayer = { uri, _, _ ->
                            mounted.set(uri)
                            Text("player", modifier = Modifier.testTag("stub_player"))
                        },
                    )
                }
            }
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) { center.items.value.isNotEmpty() }
        val item = center.items.value.single()
        assertEquals("att:rec/v1", item.key)
        assertEquals(DownloadOrigin("s1", "m7"), item.origin)
        assertEquals(DownloadState.Running(42, 100), item.state)
        // The loading state is the ring, with the job's progress.
        onNode(
            hasTestTag(VIDEO_RING) and hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNodeWithTag("attachment_video_loading").assertIsDisplayed()

        // Leaving the chat keeps it going.
        shown = false
        waitForIdle()
        assertEquals(DownloadState.Running(42, 100), center.items.value.single().state)
        assertEquals(emptyList(), media.cancelled.toList())
        assertEquals(1, media.downloads.get())
    }

    @Test fun once_the_download_is_done_the_clip_plays_from_its_file() = runComposeUiTest {
        val center = DownloadCenter(centerScope)
        val media = HostClips()
        val mounted = AtomicReference<String?>(null)
        setPlatformContent {
            CompositionLocalProvider(LocalDownloads provides center) {
                InlineVideo(
                    att = videoAtt(),
                    media = media,
                    renderPlayer = { uri, _, _ ->
                        mounted.set(uri)
                        Text("player", modifier = Modifier.testTag("stub_player"))
                    },
                )
            }
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) { center.items.value.isNotEmpty() }
        media.gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("stub_player").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("file:///cache/v1.mp4", mounted.get())
        assertEquals(1, media.downloads.get(), "one transfer")
    }

    @Test fun a_paused_play_is_the_poster_with_its_ring_not_an_error() = runComposeUiTest {
        val center = DownloadCenter(centerScope)
        val media = HostClips()
        setPlatformContent {
            CompositionLocalProvider(LocalDownloads provides center) {
                InlineVideo(att = videoAtt(), media = media, renderPlayer = { _, _, _ -> Text("player", modifier = Modifier.testTag("stub_player")) })
            }
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(VIDEO_RING, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The ring itself pauses: no menu.
        onNodeWithTag(VIDEO_RING, useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("attachment_video_poster").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodesWithTag("attachment_menu").fetchSemanticsNodes().size, "no menu")
        assertEquals(DownloadState.Cancelled, center.items.value.single().state)
        assertEquals(listOf("v1"), media.cancelled.toList())
        assertEquals(0, onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().size, "no error card")
        // The poster keeps the ring, at how far it got, with its ✕; a tap on the ring resumes it.
        onNode(hasTestTag(VIDEO_RING) and hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f)), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("${VIDEO_RING}_remove", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(VIDEO_RING, useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000L) { center.items.value.single().state is DownloadState.Running }
        assertEquals(2, media.downloads.get())
    }

    // After a restart (#95, round 3): a clip the store already holds plays at once from its file — no download, no ring.
    @Test fun a_stored_clip_plays_from_its_file_on_a_fresh_center() = runComposeUiTest {
        val center = DownloadCenter(centerScope)
        val media = HostClips().apply { stored = true }
        val mounted = AtomicReference<String?>(null)
        setPlatformContent {
            CompositionLocalProvider(LocalDownloads provides center) {
                InlineVideo(
                    att = videoAtt(),
                    media = media,
                    renderPlayer = { uri, _, _ ->
                        mounted.set(uri)
                        Text("player", modifier = Modifier.testTag("stub_player"))
                    },
                )
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(VIDEO_RING, useUnmergedTree = true).fetchSemanticsNodes().size, "no ring on the poster")
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("stub_player").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("file:///cache/v1.mp4", mounted.get())
        assertEquals(0, media.downloads.get(), "played from the store, not downloaded")
        assertEquals(emptyList(), center.items.value, "nothing listed")
    }

    private companion object {
        const val VIDEO_RING = "attachment_video_ring"
    }
}
