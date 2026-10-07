package dev.supermux.ui.chat

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
                loadBytes = { fetches.incrementAndGet(); ByteArray(4) },
            )
        }
        onNodeWithTag("attachment_video_poster").assertIsDisplayed()
        assertEquals(0, fetches.get(), "a transcript must not eagerly download every clip")
        assertEquals(0, onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().size)
    }

    @Test fun video_note_and_mime_only_videos_also_get_the_poster() = runComposeUiTest {
        setPlatformContent {
            AttachmentList(
                attachments = listOf(
                    videoAtt(kind = "video_note", mime = null),
                    videoAtt(kind = null, mime = "video/quicktime"),
                ),
                alignEnd = false,
                loadBytes = { ByteArray(4) },
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
                loadBytes = { ByteArray(16) },
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
                loadBytes = { gate.await() },
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
                loadBytes = { null },
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
                loadBytes = { ByteArray(8) },
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
                loadBytes = { ByteArray(8) },
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
                loadBytes = { ByteArray(9) },
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
}
