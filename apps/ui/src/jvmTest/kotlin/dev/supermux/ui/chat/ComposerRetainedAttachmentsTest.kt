package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.PickedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A staged photo must survive its composition being torn down and rebuilt — what an Android
 * rotation does to the whole activity. Taking the composer out of composition and putting it back
 * stands in for that recreation.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerRetainedAttachmentsTest {

    private fun photo() = PickedFile("shot.jpg", "image/jpeg", ByteArrayChunkSource(byteArrayOf(1, 2, 3)))

    @Test fun a_staged_photo_survives_a_recreation_when_the_host_retains_attachments() = runComposeUiTest {
        val platform = FakePlatform()
        val store = ComposerAttachmentStore(scope = CoroutineScope(Dispatchers.Unconfined))
        val uploaded = mutableListOf<String>()
        var shown by mutableStateOf(true)
        setPlatformContent(platform, pointer = false, inputMode = InputMode.Touch) {
            CompositionLocalProvider(LocalComposerAttachmentStore provides store) {
                if (shown) {
                    Composer(
                        draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                        onSend = { _, _ -> }, onInterrupt = {},
                        onUpload = { _, name, _, _, _ -> uploaded.add(name); "f" },
                    )
                }
            }
        }
        waitForIdle()
        assertTrue(platform.pendingPicks.tryEmit(photo()))
        waitUntil(timeoutMillis = 5_000L) { uploaded.isNotEmpty() }
        // A photo is a thumbnail tile, still addressed by the chip's tag and named for a reader.
        onNodeWithTag("composer-chip").assertIsDisplayed()
        onNodeWithContentDescription("shot.jpg").assertIsDisplayed()

        shown = false
        waitForIdle()
        onNodeWithTag("composer-chip").assertDoesNotExist()
        shown = true
        waitForIdle()

        onNodeWithTag("composer-chip").assertIsDisplayed()
        // Back as it was — the finished upload is not re-run.
        assertEquals(listOf("shot.jpg"), uploaded)
    }

    @Test fun without_a_store_the_chip_lives_in_composition_as_before() = runComposeUiTest {
        val platform = FakePlatform()
        val uploaded = mutableListOf<String>()
        var shown by mutableStateOf(true)
        setPlatformContent(platform, pointer = false, inputMode = InputMode.Touch) {
            if (shown) {
                Composer(
                    draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                    onSend = { _, _ -> }, onInterrupt = {},
                    onUpload = { _, name, _, _, _ -> uploaded.add(name); "f" },
                )
            }
        }
        waitForIdle()
        assertTrue(platform.pendingPicks.tryEmit(photo()))
        waitUntil(timeoutMillis = 5_000L) { uploaded.isNotEmpty() }
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        onNodeWithTag("composer-chip").assertDoesNotExist()
    }

    @Test fun a_document_keeps_its_name_chip() = runComposeUiTest {
        val platform = FakePlatform()
        val uploaded = mutableListOf<String>()
        setPlatformContent(platform, pointer = false, inputMode = InputMode.Touch) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "f" },
            )
        }
        waitForIdle()
        assertTrue(platform.pendingPicks.tryEmit(PickedFile("notes.pdf", "application/pdf", ByteArrayChunkSource(byteArrayOf(1)))))
        waitUntil(timeoutMillis = 5_000L) { uploaded.isNotEmpty() }
        onNodeWithTag("composer-chip").assertIsDisplayed()
        assertTrue(isMediaMime("video/mp4") && isMediaMime("image/png") && !isMediaMime("application/pdf"))
    }
}
