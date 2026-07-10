package dev.supermux.desktop.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.upload.FileChunkSource
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Attachment contract for [DesktopComposer] (M4d): the pure send-gating matrix + mime/kind helpers,
 * and the staged-chip lifecycle (Uploading → Done | Failed, retry, remove, send-gather) driven
 * through [runComposeUiTest] with a FAKED upload seam + a faked file picker so the tests never touch
 * the network or an AWT dialog. The load-bearing rule: send is blocked while any chip is Uploading
 * OR Failed (never send a message minus its attachment).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopComposerAttachTest {

    private fun att(state: UploadState, id: String = "1") = ComposerAttachment(
        id = id,
        name = "f",
        mime = "text/plain",
        source = FileChunkSource(File("/nonexistent")),
        state = state,
    )

    // ── pure gating matrix ──────────────────────────────────────────────────────
    @Test fun canSend_emptyAndNoAttachments_false() {
        assertFalse(canSendComposer("", emptyList(), sending = false))
        assertFalse(canSendComposer("   ", emptyList(), sending = false))
    }

    @Test fun canSend_textOnly_true() {
        assertTrue(canSendComposer("hi", emptyList(), sending = false))
    }

    @Test fun canSend_textOnly_whileSending_false() {
        assertFalse(canSendComposer("hi", emptyList(), sending = true))
    }

    @Test fun canSend_attachmentUploading_false() {
        assertFalse(canSendComposer("hi", listOf(att(UploadState.Uploading(0.5f))), sending = false))
        // even with only the attachment (no text) an in-flight upload blocks the send
        assertFalse(canSendComposer("", listOf(att(UploadState.Uploading(0f))), sending = false))
    }

    @Test fun canSend_attachmentDone_true_evenWithBlankText() {
        assertTrue(canSendComposer("", listOf(att(UploadState.Done("file-1"))), sending = false))
        assertTrue(canSendComposer("hi", listOf(att(UploadState.Done("file-1"))), sending = false))
    }

    @Test fun canSend_attachmentFailed_false() {
        assertFalse(canSendComposer("hi", listOf(att(UploadState.Failed)), sending = false))
        assertFalse(canSendComposer("", listOf(att(UploadState.Failed)), sending = false))
    }

    @Test fun canSend_oneDoneOneUploading_false() {
        val list = listOf(att(UploadState.Done("a"), id = "1"), att(UploadState.Uploading(0.9f), id = "2"))
        assertFalse(canSendComposer("hi", list, sending = false))
    }

    @Test fun canSend_doneButSending_false() {
        assertFalse(canSendComposer("hi", listOf(att(UploadState.Done("file-1"))), sending = true))
    }

    // ── mime / kind helpers ─────────────────────────────────────────────────────
    @Test fun composerKind_audioIsVoice_othersNull() {
        assertEquals("voice", composerKind("audio/mpeg"))
        assertEquals("voice", composerKind("audio/wav"))
        assertNull(composerKind("image/png"))
        assertNull(composerKind("application/pdf"))
        assertNull(composerKind("text/plain"))
    }

    @Test fun composerMime_unknownPath_fallsBackToOctetStream() {
        // A path that can't be probed (nonexistent, no meaningful extension) → octet-stream fallback.
        val p = File("/nonexistent/no-extension-here").toPath()
        assertEquals("application/octet-stream", composerMime(p))
    }

    // ── staged chip: Uploading(pct) → Done, send gated then enabled ─────────────
    @Test fun stageFile_showsUploadingProgress_thenDone_gatesSend() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val tmp = tempFile("note.txt")
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, onProgress -> onProgress(30, 100); gate.await(); "file-xyz" },
                pickFiles = { listOf(tmp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        waitForIdle()
        // Chip shows a determinate % while uploading; send is BLOCKED (upload in flight).
        onNodeWithText("note.txt · 30%").assertExists()
        onNodeWithTag("composer-send").assertIsNotEnabled()

        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText("note.txt").fetchSemanticsNodes().isNotEmpty() }
        // Done: label is just the name; blank text + a Done chip enables send.
        onNodeWithText("note.txt").assertExists()
        onNodeWithTag("composer-send").assertIsEnabled()
    }

    // ── failed upload → Retry affordance + blocked send; retry re-runs → Done ───
    @Test fun failedUpload_showsRetry_blocksSend_retryReRunsToDone() = runComposeUiTest {
        var calls = 0
        val tmp = tempFile("note.txt")
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> calls++; if (calls == 1) null else "file-ok" },
                pickFiles = { listOf(tmp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText("note.txt · Retry").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("composer-send").assertIsNotEnabled() // Failed blocks send

        onNodeWithTag("composer-chip-retry").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText("note.txt").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, calls) // retry actually re-ran the upload
        onNodeWithTag("composer-send").assertIsEnabled()
    }

    // ── remove chip → chip gone, send disabled again (blank text, no attachments) ─
    @Test fun removeChip_dropsIt_andDisablesSend() = runComposeUiTest {
        val tmp = tempFile("note.txt")
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-ok" },
                pickFiles = { listOf(tmp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("composer-send").assertIsEnabled()

        onNodeWithTag("composer-chip-remove").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    // ── send gathers the Done file_ids + clears the chips ───────────────────────
    @Test fun send_gathersFileIds_andClearsChips() = runComposeUiTest {
        var sentText: String? = null
        var sentIds: List<String>? = null
        val tmp = tempFile("note.txt")
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { t, ids -> sentText = t; sentIds = ids },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-77" },
                pickFiles = { listOf(tmp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag("composer-send").performClick()
        assertEquals("", sentText)                 // blank draft, attachment-only send
        assertEquals(listOf("file-77"), sentIds)   // the finalized file_id was gathered
        // chips cleared after send
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    private fun tempFile(name: String): File {
        val dir = Files.createTempDirectory("composer-attach").toFile().apply { deleteOnExit() }
        return File(dir, name).apply { writeText("payload"); deleteOnExit() }
    }
}
