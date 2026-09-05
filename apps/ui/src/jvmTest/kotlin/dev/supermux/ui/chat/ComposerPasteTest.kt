package dev.supermux.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.PickedFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paste-image contract for [Composer]: the pure key predicates, real Ctrl/Meta key injection, and
 * the menu-nonce path that drives the SAME staging funnel the attach affordance and the drop target
 * use. The clipboard itself is `Platform.clipboard` (a [FakePlatform] here), so nothing touches the
 * real system clipboard; the host-side decode/caps/paste-cache rules live with the desktop actual
 * and are pinned by `:desktop`'s `DesktopPasteCacheTest`.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerPasteTest {

    // ── pure key predicate — Ctrl and Meta are DISTINCT flags ───────────────────
    @Test fun pasteKey_ctrlV_down_isPaste() {
        assertTrue(
            isComposerPasteKey(
                Key.V, KeyEventType.KeyDown,
                ctrlPressed = true, metaPressed = false, shiftPressed = false,
            ),
        )
    }

    @Test fun pasteKey_metaV_down_isPaste() {
        assertTrue(
            isComposerPasteKey(
                Key.V, KeyEventType.KeyDown,
                ctrlPressed = false, metaPressed = true, shiftPressed = false,
            ),
        )
    }

    @Test fun pasteKey_v_without_modifier_isNotPaste() {
        assertFalse(
            isComposerPasteKey(
                Key.V, KeyEventType.KeyDown,
                ctrlPressed = false, metaPressed = false, shiftPressed = false,
            ),
        )
    }

    @Test fun pasteKey_shift_v_isNotPaste_fallsThroughForPlainText() {
        // Ctrl/Cmd+Shift+V is "paste as plain text" — never the image-paste chord.
        assertFalse(
            isComposerPasteKey(
                Key.V, KeyEventType.KeyDown,
                ctrlPressed = true, metaPressed = false, shiftPressed = true,
            ),
        )
        assertFalse(
            isComposerPasteKey(
                Key.V, KeyEventType.KeyDown,
                ctrlPressed = false, metaPressed = true, shiftPressed = true,
            ),
        )
    }

    @Test fun pasteKey_otherKeys_areNotPaste() {
        assertFalse(
            isComposerPasteKey(
                Key.C, KeyEventType.KeyDown,
                ctrlPressed = true, metaPressed = false, shiftPressed = false,
            ),
        )
        assertFalse(
            isComposerPasteKey(
                Key.V, KeyEventType.KeyUp,
                ctrlPressed = true, metaPressed = false, shiftPressed = false,
            ),
        )
    }

    // ── handler decision: consume + stage only when bound AND the clipboard has an image ──
    @Test fun handleComposerPasteKey_ctrl_invokesOnPasteImage() {
        var pasted = 0
        val consumed = handleComposerPasteKey(
            key = Key.V, type = KeyEventType.KeyDown,
            ctrlPressed = true, metaPressed = false, shiftPressed = false,
            uploadBound = true, likelyHasImage = { true }, onPasteImage = { pasted++ },
        )
        assertTrue(consumed)
        assertEquals(1, pasted)
    }

    @Test fun handleComposerPasteKey_meta_invokesOnPasteImage() {
        var pasted = 0
        val consumed = handleComposerPasteKey(
            key = Key.V, type = KeyEventType.KeyDown,
            ctrlPressed = false, metaPressed = true, shiftPressed = false,
            uploadBound = true, likelyHasImage = { true }, onPasteImage = { pasted++ },
        )
        assertTrue(consumed)
        assertEquals(1, pasted)
    }

    @Test fun handleComposerPasteKey_textOnly_doesNotConsume_andDoesNotStage() {
        var pasted = 0
        val consumed = handleComposerPasteKey(
            key = Key.V, type = KeyEventType.KeyDown,
            ctrlPressed = true, metaPressed = false, shiftPressed = false,
            uploadBound = true, likelyHasImage = { false }, onPasteImage = { pasted++ },
        )
        assertFalse(consumed)
        assertEquals(0, pasted)
    }

    @Test fun handleComposerPasteKey_nonPasteKey_doesNotProbeClipboard() {
        var probeCalls = 0
        val consumed = handleComposerPasteKey(
            key = Key.A, type = KeyEventType.KeyDown,
            ctrlPressed = true, metaPressed = false, shiftPressed = false,
            uploadBound = true, likelyHasImage = { probeCalls++; true }, onPasteImage = {},
        )
        assertFalse(consumed)
        assertEquals(0, probeCalls, "clipboard must not be probed on non-paste keys")
    }

    // ── stage-or-fallthrough decision (drives the Ctrl/Cmd+V handler) ───────────
    @Test fun shouldStageClipboardPaste_requiresUploadAndFiles() {
        val png = listOf(picked("a.png"))
        assertTrue(shouldStageClipboardPaste(uploadBound = true, files = png))
        assertFalse(shouldStageClipboardPaste(uploadBound = false, files = png))
        assertFalse(shouldStageClipboardPaste(uploadBound = true, files = emptyList()))
        assertFalse(shouldStageClipboardPaste(uploadBound = false, files = emptyList()))
    }

    /**
     * Ctrl+Shift+V is paste-as-plain-text — must fall through on a real key event (not only the
     * pure predicate). An injected event with an image on the clipboard must NOT stage or consume.
     */
    @Test fun ctrlShiftV_keyEvent_fallsThrough_doesNotStage() = runComposeUiTest {
        val platform = FakePlatform().apply { clipboard.images = listOf(picked("shot.png")) }
        setPlatformContent(platform) {
            Composer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-1" },
            )
        }
        onNodeWithTag("composer-input").performClick()
        onNodeWithTag("composer-input").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.V) }
            }
        }
        waitForIdle()
        assertEquals(0, platform.clipboard.reads, "Ctrl+Shift+V must not read clipboard images")
        assertEquals(0, platform.clipboard.probes, "clipboard must not be probed for Shift+V")
        assertTrue(onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag("composer-paste-pending").fetchSemanticsNodes().isEmpty())
    }

    /** Real Ctrl+V on the focused field with an image on the clipboard stages it. */
    @Test fun ctrlV_keyEvent_stagesPasteImage() = runComposeUiTest {
        val uploaded = mutableListOf<String>()
        val platform = FakePlatform().apply { clipboard.images = listOf(picked("from-ctrl-v.png")) }
        setPlatformContent(platform) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "file-$name" },
            )
        }
        onNodeWithTag("composer-input").performClick()
        onNodeWithTag("composer-input").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("from-ctrl-v.png"), uploaded)
    }

    /** Real Meta+V (macOS Cmd) — a distinct modifier, the same stage path. */
    @Test fun metaV_keyEvent_stagesPasteImage() = runComposeUiTest {
        val uploaded = mutableListOf<String>()
        val platform = FakePlatform().apply { clipboard.images = listOf(picked("from-meta-v.png")) }
        setPlatformContent(platform) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "file-$name" },
            )
        }
        onNodeWithTag("composer-input").performClick()
        onNodeWithTag("composer-input").performKeyInput {
            withKeyDown(Key.MetaLeft) { pressKey(Key.V) }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("from-meta-v.png"), uploaded)
    }

    /**
     * Text-only Ctrl+V: the probe says no image → the paste key falls through (not consumed) and
     * stages nothing. Compose's Skiko harness does not reliably deliver a platform clipboard paste
     * into the field, so text arrival is proven separately via typing (→ onValueChange).
     */
    @Test fun textOnlyCtrlV_doesNotStage_andFieldAcceptsTypedText() = runComposeUiTest {
        var draft by mutableStateOf("")
        val platform = FakePlatform() // no clipboard images
        setPlatformContent(platform) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-1" },
            )
        }
        onNodeWithTag("composer-input").performClick()
        onNodeWithTag("composer-input").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
        }
        waitForIdle()
        assertEquals(0, platform.clipboard.reads, "text-only Ctrl+V must not read clipboard images")
        assertTrue(onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty())

        onNodeWithTag("composer-input").performTextInput("hello-from-text-paste")
        assertTrue(
            draft.contains("hello-from-text-paste"),
            "text must reach the field via the draft, got draft='$draft'",
        )
    }

    /** Pending chip appears while the clipboard read is still running (encode feedback). */
    @Test fun pasteImage_showsPendingChipDuringEncode() = runComposeUiTest {
        val gate = java.util.concurrent.CountDownLatch(1)
        val platform = object : FakePlatform() {}.apply { clipboard.images = listOf(picked("slow.png")) }
        platform.clipboard.beforeRead = { gate.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        setPlatformContent(platform) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-1" },
                pasteImageRequestNonce = 1L,
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-paste-pending").fetchSemanticsNodes().isNotEmpty()
        }
        gate.countDown()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
        assertTrue(
            onAllNodesWithTag("composer-paste-pending").fetchSemanticsNodes().isEmpty(),
            "pending chip must clear once the read finishes",
        )
    }

    /** Edit ▸ Paste image (the nonce) → the same staging funnel Ctrl/Cmd+V uses. */
    @Test fun pasteImage_viaMenuNonce_uploadsAndEnablesSend() = runComposeUiTest {
        val uploaded = mutableListOf<String>()
        val platform = FakePlatform().apply { clipboard.images = listOf(picked("pasted.png")) }
        setPlatformContent(platform) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "file-$name" },
                pasteImageRequestNonce = 1L,
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithText("pasted.png").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("pasted.png"), uploaded)
        onNodeWithTag("composer-send").assertIsEnabled()
    }

    /**
     * Chip remove does not delete whatever the host staged for the paste: the chip **disappears**
     * (the action completed) while the on-disk file the clipboard handed over **still exists**
     * (desktop reclaims its paste cache by age, never by path).
     */
    @Test fun pasteTemp_leftForPrunerAfterChipRemoved() = runComposeUiTest {
        val dir = Files.createTempDirectory("cmp-paste-fixture").toFile().apply { deleteOnExit() }
        val temp = File(dir, "pasted.png").apply { writeText("bytes"); deleteOnExit() }
        val platform = FakePlatform().apply {
            clipboard.images = listOf(PickedFile(temp.name, "image/png", ByteArrayChunkSource(temp.readBytes())))
        }
        setPlatformContent(platform) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-1" },
                pasteImageRequestNonce = 1L,
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip-remove").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(temp.exists(), "file present while chip is shown")
        onNodeWithTag("composer-chip-remove").performClick()
        waitForIdle()
        assertTrue(
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty(),
            "chip must be removed from the UI",
        )
        assertTrue(temp.exists(), "chip remove must not delete the host's staged paste file")
    }

    private fun picked(name: String) =
        PickedFile(name, "image/png", ByteArrayChunkSource(byteArrayOf(1, 2, 3)))
}
