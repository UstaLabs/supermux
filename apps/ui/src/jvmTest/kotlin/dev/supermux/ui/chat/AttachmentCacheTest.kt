package dev.supermux.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The chat side of the media cache: a row that comes back on screen paints from memory in its first
 * frame, a cached copy that will not decode is fetched again once, and a video plays from the
 * cache's own file instead of a staged copy.
 */
@OptIn(ExperimentalTestApi::class)
class AttachmentCacheTest {
    // Coil's memory cache is a process singleton: start every test from an empty one, or a bitmap an
    // earlier test decoded under the same key would answer for this one.
    @BeforeTest fun emptyCoil() {
        SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
    }

    private fun image(id: String) = Attachment(file_id = id, kind = "image", mime = "image/png", name = "shot.png")

    private fun video(id: String) = Attachment(file_id = id, kind = "video", mime = "video/mp4", name = "clip.mp4")

    /** Memory-tier stand-in: [peek] answers once [bytes] has run, like the real cache. */
    private class CachedMedia(val body: ByteArray) : AttachmentMedia {
        var loads = 0
        var cached: ByteArray? = null

        override fun peek(att: Attachment): ByteArray? = cached

        override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? {
            loads++
            return body.also { cached = it }
        }
    }

    @Test fun aRowThatComesBackPaintsInItsFirstFrame() = runComposeUiTest {
        val media = CachedMedia(TINY_PNG_BYTES)
        var shown by mutableStateOf(true)
        setPlatformContent {
            if (shown) AttachmentList(listOf(image("c1")), alignEnd = false, media = media)
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("attachment_image").fetchSemanticsNodes().isNotEmpty() }

        shown = false
        waitForIdle()
        mainClock.autoAdvance = false
        shown = true
        mainClock.advanceTimeByFrame()

        assertEquals(1, onAllNodesWithTag("attachment_image").fetchSemanticsNodes().size, "painted in the first frame")
        assertEquals(0, onAllNodesWithTag("attachment_image_loading").fetchSemanticsNodes().size, "no spinner on the way back")
        assertEquals(1, media.loads, "the second showing is served from memory")
    }

    @Test fun aCopyThatWillNotDecodeIsFetchedAgainOnce() = runComposeUiTest {
        val refreshes = mutableListOf<Boolean>()
        val media = object : AttachmentMedia {
            override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? {
                refreshes += refresh
                return if (refresh) TINY_PNG_BYTES else byteArrayOf(1, 2, 3)
            }
        }
        setPlatformContent { AttachmentList(listOf(image("c2")), alignEnd = false, media = media) }
        waitUntil(timeoutMillis = 10_000L) { onAllNodesWithTag("attachment_image").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(false, true), refreshes)
        assertEquals(0, onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().size)
    }

    @Test fun aRefetchThatStillWillNotDecodeFallsBackToTheChip() = runComposeUiTest {
        var calls = 0
        val media = object : AttachmentMedia {
            override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? {
                calls++
                return byteArrayOf(1, 2, 3)
            }
        }
        setPlatformContent { AttachmentList(listOf(image("c3")), alignEnd = false, media = media) }
        waitUntil(timeoutMillis = 10_000L) { onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, calls, "one fetch, one refetch, then the chip — never a loop")
    }

    @Test fun aCachedVideoFilePlaysWithoutStaging() = runComposeUiTest {
        val platform = FakePlatform()
        var mounted: String? = null
        var byteLoads = 0
        val media = object : AttachmentMedia {
            override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? {
                byteLoads++
                return ByteArray(4)
            }

            override suspend fun playableUri(att: Attachment): String = "file:///cache/rec-1/v1.mp4"
        }
        setPlatformContent(platform) {
            InlineVideo(video("v1"), media, renderPlayer = { uri, _, _ -> mounted = uri })
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) { mounted != null }
        assertEquals("file:///cache/rec-1/v1.mp4", mounted)
        assertEquals(emptyList(), platform.files.stagedNames, "a cached file is played in place")
        assertEquals(0, byteLoads, "the clip never passes through memory")
    }

    @Test fun withoutAFileTheVideoIsStagedAsBefore() = runComposeUiTest {
        val platform = FakePlatform()
        var mounted: String? = null
        setPlatformContent(platform) {
            InlineVideo(video("v2"), AttachmentMedia.fromBytes { ByteArray(16) }, renderPlayer = { uri, _, _ -> mounted = uri })
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) { mounted != null }
        assertEquals(listOf("video_v2.mp4"), platform.files.stagedNames)
    }

    @Test fun openingACachedVideoExternallyReadsItsBytesThen() = runComposeUiTest {
        val platform = FakePlatform()
        var external: (() -> Unit)? = null
        val media = object : AttachmentMedia {
            override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? = ByteArray(9)

            override suspend fun playableUri(att: Attachment): String = "file:///cache/rec-1/v3.mp4"
        }
        setPlatformContent(platform, pointer = true) {
            InlineVideo(video("v3"), media, renderPlayer = { _, onExternal, _ -> external = onExternal })
        }
        onNodeWithTag("attachment_video_poster").performClick()
        waitUntil(timeoutMillis = 5_000L) { external != null }
        external!!()
        waitUntil(timeoutMillis = 5_000L) { platform.files.saved.isNotEmpty() }
        assertEquals(listOf("clip.mp4|video/mp4|9"), platform.files.saved)
    }
}
