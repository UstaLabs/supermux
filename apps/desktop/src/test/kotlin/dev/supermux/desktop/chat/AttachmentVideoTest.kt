package dev.supermux.desktop.chat

import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.Attachment
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

/**
 * Inline video in the desktop chat stream. The real player mounts Compose Media Player's native
 * backend, so every test here injects the `renderPlayer` seam — a headless Gradle worker must
 * never load AVFoundation/GStreamer. What is asserted is the state machine around it: nothing is
 * downloaded until the user clicks, a failed download or a backend error falls back to the
 * download chip, and the temp file the backend opens is named so its demuxer can be picked.
 */
@OptIn(ExperimentalTestApi::class)
class AttachmentVideoTest {

    private fun videoAtt(kind: String? = "video", mime: String? = "video/mp4") =
        Attachment(file_id = "v1", kind = kind, mime = mime, name = "clip.mp4")

    @Test fun video_shows_a_poster_and_downloads_nothing_until_clicked() = runComposeUiTest {
        val fetches = AtomicInteger(0)
        setContent {
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
        setContent {
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
        val mounted = java.util.concurrent.atomic.AtomicReference<File?>(null)
        setContent {
            InlineVideo(
                att = videoAtt(),
                loadBytes = { ByteArray(16) },
                renderPlayer = { file, _ ->
                    mounted.set(file)
                    Text("player", modifier = Modifier.testTag("stub_player"))
                },
            )
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("stub_player").fetchSemanticsNodes().isNotEmpty()
        }
        val file = assertNotNull(mounted.get(), "player must be handed the cached file")
        assertTrue(file.exists(), "the backend opens a real file by URI")
        assertEquals("mp4", file.extension, "keep the container hint the demuxer needs")
        assertTrue(file.length() == 16L, "bytes must round-trip to disk")
        file.delete()
    }

    @Test fun a_slow_download_shows_the_spinner_not_the_chip() = runComposeUiTest {
        val gate = CompletableDeferred<ByteArray?>()
        setContent {
            InlineVideo(
                att = videoAtt(),
                loadBytes = { gate.await() },
                renderPlayer = { _, _ -> Text("player", modifier = Modifier.testTag("stub_player")) },
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
        setContent {
            InlineVideo(
                att = videoAtt(),
                loadBytes = { null },
                renderPlayer = { _, _ -> Text("player", modifier = Modifier.testTag("stub_player")) },
            )
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_chip").assertIsDisplayed()
    }

    @Test fun a_backend_error_falls_back_to_the_chip() = runComposeUiTest {
        // The player reports a codec/source error (e.g. a container the OS backend cannot demux);
        // the transcript must not be left with a black rectangle.
        setContent {
            InlineVideo(
                att = videoAtt(),
                loadBytes = { ByteArray(8) },
                renderPlayer = { _, onError -> onError() },
            )
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_chip").assertIsDisplayed()
    }

    /**
     * Regression: the first build froze the whole app the moment a clip was clicked. Two causes,
     * both covered here and by the `Dispatchers.IO` hop in InlineVideoPlayer:
     * 1. `File.toURI()` produces `file:/path` (no authority). The backend's own local-file check
     *    looks for "://", finds none, treats the string as a bare path, and fails "File not found".
     * 2. Reporting that failure runs `runBlocking { withContext(Main) }` on the calling thread —
     *    the EDT — parking it against itself.
     */
    @Test fun media_uri_has_a_file_authority_and_round_trips_to_the_same_file() {
        val f = assertNotNull(writeAttachmentTempFile(ByteArray(2), "video_uri", "clip.mp4", "mp4"))
        val uri = mediaUriFor(f)
        assertTrue(uri.startsWith("file:///"), "backends parse a file:// authority, not file:/ — got $uri")
        assertTrue(
            File(uri.removePrefix("file://")).exists(),
            "the backend strips exactly this prefix to stat the file; it must resolve",
        )
        assertEquals(f.absolutePath, File(java.net.URI(uri)).absolutePath)
        f.delete()
    }

    @Test fun media_uri_percent_encodes_a_space() {
        val f = assertNotNull(writeAttachmentTempFile(ByteArray(1), "holiday clip", "a.mp4", "mp4"))
        val uri = mediaUriFor(f)
        assertTrue(" " !in uri, "a raw space breaks URI parsing in the backends — got $uri")
        assertEquals(f.absolutePath, File(java.net.URI(uri)).absolutePath)
        f.delete()
    }

    @Test fun temp_file_keeps_the_name_extension_and_falls_back_to_a_default() {
        val mp4 = assertNotNull(writeAttachmentTempFile(ByteArray(3), "video_v1", "holiday.MOV", "mp4"))
        assertEquals("video_v1.MOV", mp4.name, "the attachment's own container wins")
        mp4.delete()

        val noExt = assertNotNull(writeAttachmentTempFile(ByteArray(3), "video_v2", null, "mp4"))
        assertEquals("video_v2.mp4", noExt.name, "fall back to the default container hint")
        noExt.delete()
    }

    @Test fun two_clips_do_not_collide_in_the_temp_dir() {
        val a = assertNotNull(writeAttachmentTempFile(byteArrayOf(1), "video_aaa", "clip.mp4", "mp4"))
        val b = assertNotNull(writeAttachmentTempFile(byteArrayOf(2, 2), "video_bbb", "clip.mp4", "mp4"))
        assertTrue(a.absolutePath != b.absolutePath, "file_id-keyed names must not collide")
        assertEquals(1, a.length().toInt())
        assertEquals(2, b.length().toInt())
        a.delete()
        b.delete()
    }
}
