package dev.supermux.ui.work

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.ui.chat.AttachmentList
import dev.supermux.ui.chat.LocalChatSessionId
import dev.supermux.ui.chat.LocalChatTitle
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.work.AudioPlayer
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadOrigin
import dev.supermux.work.DownloadState
import dev.supermux.work.DownloadedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A voice note in a chat (#118): its card's ▶ downloads it through the fleet's downloads exactly as a clip's ▶ does
 * (#95: the same key, the same ring while it loads, a stored one at once), then plays the stored file in the app's
 * player, remembering its chat and message. While it is what plays, the card shows ❚❚ and toggles it; leaving the
 * chat does not stop it.
 */
@OptIn(ExperimentalTestApi::class)
class AudioCardTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tempFiles = mutableListOf<File>()

    @AfterTest fun tearDown() {
        scope.cancel()
        tempFiles.forEach { it.delete() }
    }

    private val voice = Attachment(file_id = "v1", kind = "voice", mime = "audio/mp4", size = 3, name = "voice-note.m4a", duration_ms = 130_000)

    /** A host's attachments: a download waits for [gate], then the file is in the store and plays from there. */
    private inner class HostMedia(storedAlready: Boolean = false) : AttachmentMedia {
        val gate = CompletableDeferred<Unit>()
        val downloads = AtomicInteger(0)
        var file: File? = if (storedAlready) newFile() else null

        private fun newFile() = File.createTempFile("mux118", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)); tempFiles += this }

        private fun done(f: File) = DownloadedFile("voice-note.m4a", "audio/mp4", 3, f.absolutePath)

        override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? = byteArrayOf(1, 2, 3)

        override fun downloadKey(att: Attachment): String = "att:rec/${att.file_id}"

        override suspend fun download(att: Attachment, progress: (received: Long, total: Long?) -> Unit): DownloadedFile? {
            downloads.incrementAndGet()
            progress(1, 3)
            gate.await()
            return done(newFile().also { file = it })
        }

        override suspend fun stored(att: Attachment): DownloadedFile? = file?.let { done(it) }

        override suspend fun playableUri(att: Attachment): String? = file?.absolutePath
    }

    private fun ComposeUiTest.card(center: DownloadCenter, player: AudioPlayer, media: AttachmentMedia, shown: () -> Boolean = { true }) =
        setPlatformContent(FakePlatform()) {
            CompositionLocalProvider(
                LocalDownloads provides center,
                LocalAudioPlayer provides player,
                LocalChatSessionId provides "s1",
                LocalChatTitle provides "demo-day-recap",
            ) {
                if (shown()) AttachmentList(attachments = listOf(voice), alignEnd = false, media = media, entryId = "m7")
            }
        }

    private fun ComposeUiTest.settle(ms: Long = 5_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) {
            waitForIdle()
            Thread.sleep(10)
        }
    }

    @Test fun playDownloadsItAsAJobThenPlaysTheStoredFile() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope)
        val outputs = AudioDockTest.FakeOutputs()
        val player = AudioPlayer(outputs)
        val media = HostMedia()
        card(center, player, media)
        onNode(hasTestTag(PLAY) and hasContentDescription("Play")).assertIsDisplayed().performClick()
        settle { center.items.value.isNotEmpty() }

        // The center's job, under the card's key, from this chat's message; its ring while it loads.
        val job = center.items.value.single()
        assertEquals("att:rec/v1", job.key)
        assertEquals(DownloadOrigin("s1", "m7"), job.origin)
        assertIs<DownloadState.Running>(job.state)
        onNodeWithTag("attachment_ring").assertIsDisplayed()

        media.gate.complete(Unit)
        settle { player.state.value != null }
        val now = assertNotNull(player.state.value)
        assertEquals(media.file!!.absolutePath, now.item.source, "the stored file plays")
        assertEquals(DownloadOrigin("s1", "m7"), now.item.origin)
        assertEquals("demo-day-recap", now.item.chat)
        assertEquals("voice-note.m4a", now.item.title)
        assertEquals("att:rec/v1", now.item.key)
        assertEquals("open ${media.file!!.absolutePath}", outputs.made.single().calls.first())
        assertEquals(1, media.downloads.get())
    }

    @Test fun aStoredOnePlaysAtOnce() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope)
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        val media = HostMedia(storedAlready = true)
        card(center, player, media)
        onNode(hasTestTag(PLAY) and hasContentDescription("Play")).performClick()
        settle { player.state.value != null }
        assertEquals(media.file!!.absolutePath, player.state.value?.item?.source)
        assertEquals(0, media.downloads.get(), "nothing fetched again")
    }

    @Test fun whileItPlaysTheCardShowsPauseAndToggles() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope)
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        val media = HostMedia(storedAlready = true)
        card(center, player, media)
        onNode(hasTestTag(PLAY) and hasContentDescription("Play")).performClick()
        settle { player.state.value != null }
        onNode(hasTestTag(PLAY) and hasContentDescription("Pause")).assertIsDisplayed().performClick()
        waitForIdle()
        assertFalse(assertNotNull(player.state.value).playing)
        onNode(hasTestTag(PLAY) and hasContentDescription("Play")).performClick()
        waitForIdle()
        assertTrue(assertNotNull(player.state.value).playing)
    }

    @Test fun leavingTheChatKeepsItPlaying() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope)
        val outputs = AudioDockTest.FakeOutputs()
        val player = AudioPlayer(outputs)
        val media = HostMedia(storedAlready = true)
        var shown by mutableStateOf(true)
        card(center, player, media, shown = { shown })
        onNode(hasTestTag(PLAY) and hasContentDescription("Play")).performClick()
        settle { player.state.value != null }
        shown = false
        waitForIdle()
        onNodeWithTag(PLAY).assertDoesNotExist()
        assertTrue(player.state.value?.playing == true)
        assertFalse("release" in outputs.made.single().calls, "nothing stopped it")
    }

    @Test fun anotherCardIsNotShownAsPlaying() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope)
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        player.play(dev.supermux.work.AudioItem("other.mp3", "/cache/other.mp3", key = "att:rec/other"))
        card(center, player, HostMedia(storedAlready = true))
        onNode(hasTestTag(PLAY) and hasContentDescription("Play")).assertIsDisplayed()
    }

    private companion object {
        const val PLAY = "attachment_audio_play"
    }
}
