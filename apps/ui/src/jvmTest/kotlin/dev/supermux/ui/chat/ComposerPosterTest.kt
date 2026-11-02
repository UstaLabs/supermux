package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.isEnabled
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.PickedFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A clip picked on a phone goes up with its poster: the frame the picker already grabbed for the
 * chip, as a JPEG, linked to the clip BEFORE the chip is done — the message must name the poster
 * when it is sent. A poster is best effort: without a frame there is none, and a failed one never
 * fails the clip.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerPosterTest {

    private val clip = PickedFile("clip.mp4", "video/mp4", ByteArrayChunkSource(ByteArray(8)), preview = "frame")

    private fun androidx.compose.ui.test.ComposeUiTest.waitForSendable() =
        waitUntil(timeoutMillis = 5_000L) {
            runCatching { onNodeWithTag("composer-send").fetchSemanticsNode() }.getOrNull()?.let { isEnabled().matches(it) } == true
        }

    @Test fun a_picked_clip_uploads_its_frame_as_its_poster_and_links_it_before_it_is_done() = runComposeUiTest {
        val uploads = mutableListOf<String>()
        val links = mutableListOf<Pair<String, String>>()
        val platform = FakePlatform(pickResult = listOf(clip))
        platform.files.posterFrames = { preview -> if (preview == "frame") byteArrayOf(1, 2, 3) else null }
        setPlatformContent(platform) {
            Composer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                actions = ComposerActions(linkPoster = { c, p -> links += c to p }),
                onUpload = { source, name, mime, _, _ ->
                    uploads += "$name|$mime|${source.size}"
                    if (mime == "image/jpeg") "poster-1" else "clip-1"
                },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        waitForSendable()
        assertEquals(listOf("clip.mp4|video/mp4|8", "clip.jpg|image/jpeg|3"), uploads)
        assertEquals(listOf("clip-1" to "poster-1"), links)
    }

    @Test fun a_clip_without_a_frame_goes_up_alone() = runComposeUiTest {
        val uploads = mutableListOf<String>()
        val links = mutableListOf<Pair<String, String>>()
        setPlatformContent(FakePlatform(pickResult = listOf(clip))) {
            Composer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                actions = ComposerActions(linkPoster = { c, p -> links += c to p }),
                onUpload = { _, name, mime, _, _ -> uploads += "$name|$mime"; "clip-1" },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        waitForSendable()
        assertEquals(listOf("clip.mp4|video/mp4"), uploads)
        assertEquals(emptyList(), links)
    }

    @Test fun a_poster_that_fails_to_upload_never_fails_the_clip() = runComposeUiTest {
        var sent: List<String>? = null
        val links = mutableListOf<Pair<String, String>>()
        val platform = FakePlatform(pickResult = listOf(clip))
        platform.files.posterFrames = { byteArrayOf(1) }
        setPlatformContent(platform) {
            Composer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, ids -> sent = ids },
                onInterrupt = {},
                actions = ComposerActions(linkPoster = { c, p -> links += c to p }),
                onUpload = { _, _, mime, _, _ -> if (mime == "image/jpeg") null else "clip-1" },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        waitForSendable()
        onNodeWithTag("composer-send").performClick()
        assertEquals(listOf("clip-1"), sent)
        assertEquals(emptyList(), links)
    }
}
