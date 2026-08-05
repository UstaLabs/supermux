package dev.supermux.desktop.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
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

    // ── drag-drop pure helpers (M4d-T2) ─────────────────────────────────────────
    @Test fun filterExistingFiles_dropsNonexistentEntries() {
        val real = tempFile("keep.txt")
        val missing = File(real.parentFile, "does-not-exist.txt")
        assertEquals(listOf(real), filterExistingFiles(listOf(real, missing)))
    }

    @Test fun filterExistingFiles_dropsDirectories() {
        // A dropped directory (isFile == false) is filtered too — stage() only handles single files.
        val dir = Files.createTempDirectory("composer-attach-dir").toFile().apply { deleteOnExit() }
        val real = tempFile("keep.txt")
        assertEquals(listOf(real), filterExistingFiles(listOf(dir, real)))
    }

    @Test fun composerFilesFromDragData_parsesFileUris() {
        val real = tempFile("dropped.txt")
        val uri = real.toURI().toString()
        assertEquals(listOf(real.absoluteFile), composerFilesFromDragData(listOf(uri)).map { it.absoluteFile })
    }

    @Test fun composerFilesFromDragData_dropsMalformedEntries() {
        val real = tempFile("dropped.txt")
        val uris = listOf(real.toURI().toString(), "not a uri at all")
        assertEquals(1, composerFilesFromDragData(uris).size)
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
        onNodeWithTag("composer-attach-files").performClick()
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
        onNodeWithTag("composer-attach-files").performClick()
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
        onNodeWithTag("composer-attach-files").performClick()
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
        onNodeWithTag("composer-attach-files").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag("composer-send").performClick()
        assertEquals("", sentText)                 // blank draft, attachment-only send
        assertEquals(listOf("file-77"), sentIds)   // the finalized file_id was gathered
        // chips cleared after send
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    // ── stageFiles: a BATCH (as an external drop or a multi-select dialog pick funnels in) stages one
    // chip per file, uploads each, and silently drops a nonexistent entry (a stale drop, a file
    // deleted mid-drag) instead of crashing. The actual AWT drag/drop gesture can't be driven under
    // runComposeUiTest (documented — T3 covers it live/manually); this exercises the SAME funnel
    // (stageFiles) the drop handler's onDrop calls, via the existing pickFiles() seam, so both entry
    // points (Attach dialog, external drop) are proven identical without needing a fake OS drag.
    @Test fun stageFiles_batchStagesAllValidFiles_andUploadsEach_filteringMissingOnes() = runComposeUiTest {
        val tmp1 = tempFile("first.txt")
        val tmp2 = tempFile("second.txt")
        val missing = File(tmp1.parentFile, "vanished.txt") // never created — must be filtered
        val uploadedNames = mutableListOf<String>()
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploadedNames.add(name); "file-$name" },
                pickFiles = { listOf(tmp1, missing, tmp2) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-attach-files").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().size == 2 }

        // Exactly the two real files staged + uploaded — the missing entry never became a chip.
        onNodeWithText("first.txt").assertExists()
        onNodeWithText("second.txt").assertExists()
        assertEquals(setOf("first.txt", "second.txt"), uploadedNames.toSet())
        onNodeWithTag("composer-send").assertIsEnabled()
    }

    // ── the drop path shares T1's session-scoped state + guards: no parallel plumbing, no regression ─
    @Test fun stageFiles_reusesSessionScopedState_sessionSwitchStillClearsChips() = runComposeUiTest {
        val tmp1 = tempFile("a.txt")
        val tmp2 = tempFile("b.txt")
        var key by mutableStateOf("A")
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                sessionKey = key,
                onUpload = { _, _, _, _, _ -> "file-ok" },
                pickFiles = { listOf(tmp1, tmp2) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-attach-files").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().size == 2 }

        // A batch stage (as a drop would funnel in) is still governed by T1's session scoping: a
        // session switch drops BOTH chips, same as a single-file stage.
        key = "B"
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    // ── session switch drops the previous session's chips (no cross-session leak) ─
    @Test fun sessionSwitch_clearsChips() = runComposeUiTest {
        val tmp = tempFile("note.txt")
        var key by mutableStateOf("A")
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                sessionKey = key,
                onUpload = { _, _, _, _, _ -> "file-ok" },
                pickFiles = { listOf(tmp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-attach-files").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("composer-send").assertIsEnabled()

        // Switch sessions — the composer stays composed (as in ChatPanel), so the A-scoped chip
        // MUST NOT survive into B (else B's send would gather A's file_id).
        key = "B"
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    // ── a late (queued) progress callback must not clobber a settled Done chip ───
    @Test fun lateProgressAfterDone_doesNotResurrectUploading() = runComposeUiTest {
        var captured: ((Long, Long) -> Unit)? = null
        val tmp = tempFile("note.txt")
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, onProgress -> captured = onProgress; "file-ok" },
                pickFiles = { listOf(tmp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-attach-files").performClick()
        // Chip settles to Done (label = bare name).
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText("note.txt").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("composer-send").assertIsEnabled()

        // Simulate uploadResumable's final onProgress(total,total) arriving AFTER the Done write.
        // Without the terminal-state guard this flips the chip back to Uploading(1.0) → send disabled
        // forever + × hidden (a stuck dead-end). With the guard it stays Done.
        captured!!.invoke(100, 100)
        waitForIdle()
        onNodeWithText("note.txt").assertExists()       // still Done (not "note.txt · 100%")
        onNodeWithTag("composer-send").assertIsEnabled()
    }

    // ── externalAttach (M4d-T3, the SM_CHAT_ATTACH headless hook's wiring) ──────
    // Delivering a ComposerExternalAttach drives the SAME stageFiles()+sendWith() funnel a human
    // Attach-then-Send would: a chip appears (Uploading → Done), then the requested text is sent with
    // the finalized file_id, chips clear, and the caller's one-shot holder is consumed exactly once.
    @Test fun externalAttach_stagesUploadsAndSends_thenConsumes() = runComposeUiTest {
        var sentText: String? = null
        var sentIds: List<String>? = null
        var consumedCount = 0
        val tmp = tempFile("report.txt")
        var request by mutableStateOf<ComposerExternalAttach?>(
            ComposerExternalAttach(tmp.absolutePath, "check this file"),
        )
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { t, ids -> sentText = t; sentIds = ids },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-ext-1" },
                externalAttach = request,
                onExternalAttachConsumed = { consumedCount++; request = null },
            )
        }
        waitUntil(timeoutMillis = 5_000L) { sentText != null }
        assertEquals("check this file", sentText)
        assertEquals(listOf("file-ext-1"), sentIds)
        assertEquals(1, consumedCount)
        // Sent chip is cleared (mirrors a click-driven send).
        onAllNodesWithTag("composer-chip").assertCountEquals(0)
    }

    // A failed upload never sends — the request is still consumed (so a caller's one-shot holder
    // doesn't wedge), but onSend is never called.
    @Test fun externalAttach_failedUpload_neverSends_butStillConsumes() = runComposeUiTest {
        var sendCalls = 0
        var consumed = false
        val tmp = tempFile("report.txt")
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> sendCalls++ },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> null }, // upload gives up → Failed
                externalAttach = ComposerExternalAttach(tmp.absolutePath, "hi"),
                onExternalAttachConsumed = { consumed = true },
            )
        }
        waitUntil(timeoutMillis = 5_000L) { consumed }
        assertEquals(0, sendCalls)
        // The failed chip stays (Retry affordance) — never silently dropped.
        onNodeWithText("report.txt · Retry").assertExists()
    }

    // A path that isn't a real file (a stale/typo'd SM_CHAT_ATTACH arg) is dropped without ever
    // staging a chip or touching onUpload.
    @Test fun externalAttach_missingFile_dropsWithoutStaging() = runComposeUiTest {
        var uploadCalls = 0
        var consumed = false
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> uploadCalls++; "file-x" },
                externalAttach = ComposerExternalAttach("/nonexistent/path/nope.txt", "hi"),
                onExternalAttachConsumed = { consumed = true },
            )
        }
        waitUntil(timeoutMillis = 5_000L) { consumed }
        assertEquals(0, uploadCalls)
        onAllNodesWithTag("composer-chip").assertCountEquals(0)
    }

    private fun tempFile(name: String): File {
        val dir = Files.createTempDirectory("composer-attach").toFile().apply { deleteOnExit() }
        return File(dir, name).apply { writeText("payload"); deleteOnExit() }
    }
}
