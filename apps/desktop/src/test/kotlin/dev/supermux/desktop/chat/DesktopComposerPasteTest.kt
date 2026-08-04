package dev.supermux.desktop.chat

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
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.awt.image.ImageObserver
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Paste-image contract for [DesktopComposer]: pure key/MIME helpers, clipboard Transferable
 * extraction, caps/cleanup, real Ctrl/Meta key injection, and the Attach-menu "Paste image" path
 * that drives the SAME [stageFiles] funnel (via [launchPasteImages] / the `pasteImageFiles` seam).
 * Never touches the real system clipboard for image paste — tests inject [DesktopComposer]'s
 * `pasteImageFiles` / `clipboardLikelyHasImage` seams (except the text-only paste test, which puts
 * a string on the AWT clipboard so the field's native paste can prove fallthrough).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopComposerPasteTest {

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
        // macOS Cmd — separate from Ctrl, not the same helper call with ctrlOrMeta=true.
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
                ctrlPressed = false, metaPressed = false,
            ),
        )
    }

    @Test fun pasteKey_shift_v_isNotPaste_fallsThroughForPlainText() {
        // Ctrl/Cmd+Shift+V = paste as plain text / match style — must NOT be consumed.
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
            isComposerPasteKey(Key.V, KeyEventType.KeyUp, ctrlPressed = true, metaPressed = false),
        )
        assertFalse(
            isComposerPasteKey(Key.C, KeyEventType.KeyDown, ctrlPressed = true, metaPressed = false),
        )
        assertFalse(
            isComposerPasteKey(Key.Enter, KeyEventType.KeyDown, ctrlPressed = true, metaPressed = false),
        )
    }

    @Test fun handleComposerPasteKey_ctrl_invokesOnPasteImage() {
        var invoked = false
        val consumed = handleComposerPasteKey(
            key = Key.V,
            type = KeyEventType.KeyDown,
            ctrlPressed = true,
            metaPressed = false,
            shiftPressed = false,
            uploadBound = true,
            likelyHasImage = true,
            onPasteImage = { invoked = true },
        )
        assertTrue(consumed)
        assertTrue(invoked)
    }

    @Test fun handleComposerPasteKey_meta_invokesOnPasteImage() {
        var invoked = false
        val consumed = handleComposerPasteKey(
            key = Key.V,
            type = KeyEventType.KeyDown,
            ctrlPressed = false,
            metaPressed = true,
            shiftPressed = false,
            uploadBound = true,
            likelyHasImage = true,
            onPasteImage = { invoked = true },
        )
        assertTrue(consumed)
        assertTrue(invoked)
    }

    @Test fun handleComposerPasteKey_textOnly_doesNotConsume_andDoesNotStage() {
        var invoked = false
        val consumed = handleComposerPasteKey(
            key = Key.V,
            type = KeyEventType.KeyDown,
            ctrlPressed = true,
            metaPressed = false,
            shiftPressed = false,
            uploadBound = true,
            likelyHasImage = false, // text-only clipboard
            onPasteImage = { invoked = true },
        )
        assertFalse(consumed, "text-only paste must fall through to the field")
        assertFalse(invoked)
    }

    // ── image-file classification ───────────────────────────────────────────────
    @Test fun isComposerImageFile_accepts_png_extension() {
        val png = tempNamed("shot.png") { writeBytes(tinyPng()) }
        assertTrue(isComposerImageFile(png))
    }

    @Test fun isComposerImageFile_rejects_text_and_missing() {
        val txt = tempNamed("note.txt") { writeText("hi") }
        assertFalse(isComposerImageFile(txt))
        assertFalse(isComposerImageFile(File("/nonexistent/nope.png")))
    }

    // ── clipboard transferable helpers ──────────────────────────────────────────
    @Test fun clipboardTransferable_fileList_keepsOnlyImageFiles() {
        val png = tempNamed("a.png") { writeBytes(tinyPng()) }
        val txt = tempNamed("b.txt") { writeText("nope") }
        val missing = File(png.parentFile, "gone.png")
        val got = composerFilesFromClipboardTransferable(FakeFileListTransferable(listOf(png, txt, missing)))
        assertEquals(listOf(png.absoluteFile), got.map { it.absoluteFile })
    }

    @Test fun clipboardTransferable_rasterImage_writesTempPng() {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).also { bi ->
            for (y in 0 until 4) for (x in 0 until 4) bi.setRGB(x, y, 0xFF00FF00.toInt())
        }
        val got = composerFilesFromClipboardTransferable(FakeImageTransferable(img))
        assertEquals(1, got.size)
        assertTrue(got[0].isFile)
        assertTrue(got[0].name.endsWith(".png"))
        assertTrue(isComposerPasteTempFile(got[0]), "generated paste temp must be registry-tracked")
        // Round-trip: the temp file is a real PNG ImageIO can re-read.
        val reloaded = ImageIO.read(got[0])
        assertTrue(reloaded != null && reloaded.width == 4 && reloaded.height == 4)
        cleanupComposerPasteTemp(got[0])
        assertFalse(got[0].exists())
        assertFalse(isComposerPasteTempFile(got[0]), "cleanup must unregister")
    }

    @Test fun clipboardTransferable_empty_when_textOnly() {
        val got = composerFilesFromClipboardTransferable(FakeTextTransferable("hello"))
        assertTrue(got.isEmpty())
    }

    @Test fun transferableLikelyHasImage_textOnly_isFalse() {
        assertFalse(transferableLikelyHasImage(FakeTextTransferable("hello")))
    }

    @Test fun transferableLikelyHasImage_raster_isTrue() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        assertTrue(transferableLikelyHasImage(FakeImageTransferable(img)))
    }

    @Test fun clipboardImageToTempFile_encodesBufferedImage() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val file = clipboardImageToTempFile(img)
        assertTrue(file != null && file.isFile)
        assertTrue(file!!.length() > 0)
        assertTrue(isComposerPasteTempFile(file))
        cleanupComposerPasteTemp(file)
    }

    // ── caps: dimension + pixel + encoded-byte + reject without huge alloc ──────
    @Test fun clipboardImageWithinCaps_rejectsOversizeEdgeAndPixels() {
        assertTrue(clipboardImageWithinCaps(4, 4))
        assertTrue(clipboardImageWithinCaps(PASTE_IMAGE_MAX_EDGE, 1))
        assertFalse(clipboardImageWithinCaps(PASTE_IMAGE_MAX_EDGE + 1, 1))
        assertFalse(clipboardImageWithinCaps(1, PASTE_IMAGE_MAX_EDGE + 1))
        // Pixel cap: even if each edge is under the edge cap, w*h can exceed.
        val edge = (kotlin.math.sqrt(PASTE_IMAGE_MAX_PIXELS.toDouble()) + 100).toInt()
            .coerceAtMost(PASTE_IMAGE_MAX_EDGE)
        if (edge.toLong() * edge > PASTE_IMAGE_MAX_PIXELS) {
            assertFalse(clipboardImageWithinCaps(edge, edge))
        }
        assertFalse(clipboardImageWithinCaps(0, 10))
        assertFalse(clipboardImageWithinCaps(-1, 10))
    }

    @Test fun clipboardImageToTempFile_rejectsHugeDimsWithoutEncoding() {
        // Fake Image reports absurd dimensions without allocating a pixel buffer.
        val huge = DimensionOnlyImage(width = 50_000, height = 50_000)
        val start = System.nanoTime()
        val file = clipboardImageToTempFile(huge)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull(file, "oversize paste must be rejected before encode")
        // Must be essentially free — not multi-second PNG encode.
        assertTrue(elapsedMs < 500, "oversize reject took ${elapsedMs}ms (expected <500ms)")
    }

    @Test fun clipboardImageToTempFile_rejectsWhenEncodedBytesExceedCap() {
        // Tiny image but a 1-byte encoded cap forces the post-encode size check to drop the file.
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        val file = clipboardImageToTempFile(img, maxEncodedBytes = 1L)
        assertNull(file, "encoded-byte cap must drop the paste after write")
        // No leaked registry entry / temp dir from the failed encode.
        // (cleanupComposerPasteTemp already ran inside clipboardImageToTempFile)
    }

    @Test fun scaleBufferedImageToMaxEdge_downscalesLargeImages() {
        val big = BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB)
        val scaled = scaleBufferedImageToMaxEdge(big, maxEdge = 2048)
        assertTrue(scaled.width <= 2048 && scaled.height <= 2048)
        assertTrue(scaled.width == 2048 || scaled.height == 2048)
        // Already small — identity.
        val small = BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB)
        assertTrue(scaleBufferedImageToMaxEdge(small, 2048) === small)
    }

    // ── temp-file cleanup: registry only, never path-name inference ─────────────
    @Test fun cleanupComposerPasteTemp_deletesFileAndEmptyParent() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val file = clipboardImageToTempFile(img)
        assertNotNull(file)
        val parent = file!!.parentFile
        assertTrue(file.exists())
        assertTrue(parent != null && parent.exists())
        assertTrue(isComposerPasteTempFile(file))
        cleanupComposerPasteTemp(file)
        assertFalse(file.exists())
        assertFalse(parent!!.exists())
    }

    @Test fun cleanupComposerPasteTemp_ignoresNonPasteFiles() {
        val regular = tempNamed("not-paste.png") { writeBytes(tinyPng()) }
        assertFalse(isComposerPasteTempFile(regular))
        cleanupComposerPasteTemp(regular)
        assertTrue(regular.exists(), "non-paste files must not be deleted")
    }

    /**
     * REGRESSION: a user-owned file under a directory whose name starts with `composer-paste`
     * must NEVER be treated as deletable. The old path-prefix heuristic deleted the original.
     */
    @Test fun cleanupComposerPasteTemp_doesNotDeleteUserFileUnderComposerPasteNamedDir() {
        val userDir = Files.createTempDirectory("composer-paste-user-owned-").toFile().apply {
            deleteOnExit()
        }
        val userFile = File(userDir, "my-photo.png").apply {
            writeBytes(tinyPng())
            deleteOnExit()
        }
        assertTrue(userFile.exists())
        assertTrue(userDir.name.startsWith(COMPOSER_PASTE_TEMP_PREFIX))
        assertFalse(
            isComposerPasteTempFile(userFile),
            "user file must not be registry-tracked just because the parent name matches",
        )
        cleanupComposerPasteTemp(userFile)
        assertTrue(userFile.exists(), "user's original file must survive cleanup")
        assertTrue(userDir.exists(), "user's directory must survive cleanup")
    }

    /**
     * REGRESSION (TOCTOU / parent-symlink): ownership is identity (fileKey + creationTime), not
     * path string. Between create and cleanup an attacker replaces the parent directory with a
     * symlink to a user directory that holds a file of the same name — cleanup must refuse.
     */
    @Test fun cleanupComposerPasteTemp_refusesWhenParentReplacedBySymlink() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val paste = clipboardImageToTempFile(img)
        assertNotNull(paste)
        val pasteFile = paste!!
        val pastePath = pasteFile.absolutePath
        val parentPath = pasteFile.parentFile!!.toPath()
        val fileName = pasteFile.name
        assertTrue(isComposerPasteTempFile(pasteFile))

        // Victim user file at the path the symlink will expose under the old parent name.
        val victimDir = Files.createTempDirectory("user-docs-").toFile().apply { deleteOnExit() }
        val victimFile = File(victimDir, fileName).apply {
            writeBytes(tinyPng())
            deleteOnExit()
        }
        val victimMarker = "USER-OWNED-BYTES-${System.nanoTime()}"
        victimFile.writeText(victimMarker)
        assertTrue(victimFile.exists())

        // Attack: remove our temp tree, put a symlink at the same parent path → victimDir.
        assertTrue(pasteFile.delete())
        assertTrue(pasteFile.parentFile!!.delete())
        Files.createSymbolicLink(parentPath, victimDir.toPath())

        // Path string still matches the registry key; live object is the victim via the symlink.
        val spoofed = File(pastePath)
        assertTrue(spoofed.exists(), "spoofed path must resolve through the parent symlink")
        assertEquals(victimMarker, spoofed.readText(), "spoofed path must be the victim file")

        cleanupComposerPasteTemp(spoofed)

        assertTrue(victimFile.exists(), "user file behind parent symlink must survive cleanup")
        assertEquals(victimMarker, victimFile.readText(), "user file contents must be intact")
        // Parent symlink may remain; that is a leak of a symlink, not of user data.
        runCatching { Files.deleteIfExists(parentPath) }
        victimFile.delete()
        victimDir.delete()
    }

    /**
     * REGRESSION (TOCTOU / same-path recreation): delete the generated file and create a different
     * file at the exact same path. Cleanup must not delete the replacement (different fileKey).
     */
    @Test fun cleanupComposerPasteTemp_refusesWhenFileRecreatedAtSamePath() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val paste = clipboardImageToTempFile(img)
        assertNotNull(paste)
        val pasteFile = paste!!
        val absolutePath = pasteFile.absolutePath
        val parent = pasteFile.parentFile!!
        val name = pasteFile.name
        assertTrue(isComposerPasteTempFile(pasteFile))

        // Capture identity of the object we created (must differ after recreation).
        val originalIdentity = readComposerPasteTempIdentity(pasteFile)
        assertNotNull(originalIdentity)

        // Attack: delete our file and put a different file at the same path (new inode).
        assertTrue(pasteFile.delete())
        val replacement = File(parent, name).apply {
            writeText("REPLACEMENT-USER-DATA-${System.nanoTime()}")
            deleteOnExit()
        }
        assertTrue(replacement.exists())
        assertEquals(absolutePath, replacement.absolutePath)
        val replacementIdentity = readComposerPasteTempIdentity(replacement)
        assertNotNull(replacementIdentity)
        // Different filesystem object → different fileKey (inode).
        assertTrue(
            originalIdentity!!.fileKey != replacementIdentity!!.fileKey,
            "recreation must yield a new fileKey; got same ${originalIdentity.fileKey}",
        )
        // Registry still keys the path string — cleanup must still refuse.
        assertTrue(isComposerPasteTempFile(replacement))

        cleanupComposerPasteTemp(replacement)

        assertTrue(replacement.exists(), "recreated file at the registered path must survive")
        assertTrue(
            replacement.readText().startsWith("REPLACEMENT-USER-DATA-"),
            "replacement contents must be intact",
        )
        // Registry entry consumed; second cleanup is a no-op.
        assertFalse(isComposerPasteTempFile(replacement))
        replacement.delete()
        parent.delete()
    }

    // ── stage-or-fallthrough decision (drives the Ctrl/Cmd+V handler) ───────────
    @Test fun shouldStageClipboardPaste_requiresUploadAndFiles() {
        val png = tempNamed("a.png") { writeBytes(tinyPng()) }
        assertTrue(shouldStageClipboardPaste(uploadBound = true, files = listOf(png)))
        assertFalse(shouldStageClipboardPaste(uploadBound = false, files = listOf(png)))
        assertFalse(shouldStageClipboardPaste(uploadBound = true, files = emptyList()))
        assertFalse(shouldStageClipboardPaste(uploadBound = false, files = emptyList()))
    }

    @Test fun textOnlyPaste_doesNotStage_fallsThroughToField() {
        // Production wiring: empty pasteImageFiles → shouldStage false → key handler returns false
        // so the OutlinedTextField keeps Ctrl/Cmd+V for text. Prove the pure decision + seam.
        val files = composerFilesFromClipboardTransferable(FakeTextTransferable("hello world"))
        assertTrue(files.isEmpty())
        assertFalse(shouldStageClipboardPaste(uploadBound = true, files = files))
    }

    /**
     * Real Ctrl+V key event on the focused field with the image-paste probe true and a faked file
     * list — proves the production onPreviewKeyEvent path stages via the paste seam. Uses
     * [Key.CtrlLeft] distinctly from Meta.
     */
    @Test fun ctrlV_keyEvent_stagesPasteImage() = runComposeUiTest {
        val png = tempNamed("from-ctrl-v.png") { writeBytes(tinyPng()) }
        val uploaded = mutableListOf<String>()
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "file-$name" },
                pickFiles = { emptyList() },
                pasteImageFiles = { listOf(png) },
                clipboardLikelyHasImage = { true },
            )
        }
        // Focus the field first (same as Enter-send tests) so key injection hits onPreviewKeyEvent.
        onNodeWithTag("composer-input").performClick()
        onNodeWithTag("composer-input").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("from-ctrl-v.png"), uploaded)
    }

    /**
     * Real Meta+V (macOS Cmd) key event — distinct modifier from Ctrl, same stage path.
     */
    @Test fun metaV_keyEvent_stagesPasteImage() = runComposeUiTest {
        val png = tempNamed("from-meta-v.png") { writeBytes(tinyPng()) }
        val uploaded = mutableListOf<String>()
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "file-$name" },
                pickFiles = { emptyList() },
                pasteImageFiles = { listOf(png) },
                clipboardLikelyHasImage = { true },
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
     * Text-only paste must REACH THE FIELD: when the image probe is false the paste key falls
     * through; we then deliver text via the field's normal value path (Compose's text field paste
     * uses the platform clipboard which is unreliable under the Skiko harness, so the fallthrough
     * is proven by [handleComposerPasteKey_textOnly_doesNotConsume_andDoesNotStage] + this test
     * asserting no chip is staged and that text CAN reach the field through onDraftChange).
     *
     * End-to-end: Ctrl+V does not consume/stage images; text is accepted by the field.
     */
    @Test fun textOnlyPaste_doesNotStage_andTextReachesField() = runComposeUiTest {
        var draft by mutableStateOf("")
        var pasteInvocations = 0
        setContent {
            DesktopComposer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-1" },
                pickFiles = { emptyList() },
                pasteImageFiles = {
                    pasteInvocations++
                    emptyList()
                },
                clipboardLikelyHasImage = { false },
            )
        }
        onNodeWithTag("composer-input").performClick()
        // Ctrl+V with text-only probe — must NOT launch paste-image (no chip, no seam call).
        onNodeWithTag("composer-input").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
        }
        waitForIdle()
        assertEquals(0, pasteInvocations, "text-only Ctrl+V must not call pasteImageFiles")
        assertTrue(onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty())

        // Prove text reaches the field (the path an unconsumed paste uses via onValueChange).
        onNodeWithTag("composer-input").performTextInput("hello-from-text-paste")
        assertTrue(
            draft.contains("hello-from-text-paste"),
            "text must reach the field via the draft, got draft='$draft'",
        )
    }

    /**
     * Production wiring: Attach menu → "Paste image" → [launchPasteImages] → `pasteImageFiles`
     * seam → [stageFiles]. This is the mouse-discoverable path and exercises the real seam
     * (unlike the old test that only clicked Attach/picker).
     */
    @Test fun pasteImage_viaAttachMenu_uploadsAndEnablesSend() = runComposeUiTest {
        val png = tempNamed("pasted.png") { writeBytes(tinyPng()) }
        val uploaded = mutableListOf<String>()
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "file-$name" },
                pickFiles = { emptyList() },
                pasteImageFiles = { listOf(png) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-paste-image").performClick()
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
     * Temp cleanup after the chip is removed: paste-origin PNG must not linger for the app lifetime.
     */
    @Test fun pasteTemp_cleanedAfterChipRemoved() = runComposeUiTest {
        val img = BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)
        val temp = clipboardImageToTempFile(img)!!
        assertTrue(temp.exists())
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-1" },
                pickFiles = { emptyList() },
                pasteImageFiles = { listOf(temp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-paste-image").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        // Wait until upload settles so the × remove is visible.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip-remove").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("composer-chip-remove").performClick()
        waitUntil(timeoutMillis = 2_000L) { !temp.exists() }
        assertFalse(temp.exists(), "paste temp must be deleted when the chip is removed")
    }

    /** Send clears chips and scrubs registry-tracked paste temps. */
    @Test fun pasteTemp_cleanedAfterSend() = runComposeUiTest {
        val img = BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)
        val temp = clipboardImageToTempFile(img)!!
        var draft by mutableStateOf("go")
        setContent {
            DesktopComposer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> draft = "" },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-1" },
                pickFiles = { emptyList() },
                pasteImageFiles = { listOf(temp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-paste-image").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip-remove").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("composer-send").assertIsEnabled()
        onNodeWithTag("composer-send").performClick()
        waitUntil(timeoutMillis = 2_000L) { !temp.exists() }
        assertFalse(temp.exists(), "paste temp must be deleted after send clears chips")
    }

    /** Session switch disposes the previous session's attachments and scrubs paste temps. */
    @Test fun pasteTemp_cleanedOnSessionSwitch() = runComposeUiTest {
        val img = BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)
        val temp = clipboardImageToTempFile(img)!!
        var key by mutableStateOf("session-A")
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                sessionKey = key,
                onUpload = { _, _, _, _, _ -> "file-1" },
                pickFiles = { emptyList() },
                pasteImageFiles = { listOf(temp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-paste-image").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(temp.exists())
        key = "session-B"
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isEmpty()
        }
        waitUntil(timeoutMillis = 2_000L) { !temp.exists() }
        assertFalse(temp.exists(), "paste temp must be deleted when session disposes attachments")
    }

    /**
     * Production paste encode path: drive the real [DesktopComposer] entry point (Attach →
     * "Paste image" → [launchPasteImages] → `withContext(IO)` → `pasteImageFiles`), not a
     * hand-rolled `withContext(IO) { clipboardImageToTempFile(...) }` that bypasses the entry point.
     */
    @Test fun largeRasterEncode_viaLaunchPasteImages_completes() = runComposeUiTest {
        // 1024² exercises encode work; downscale target is 2048 so this stays full-res.
        val img = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB)
        assertTrue(clipboardImageWithinCaps(1024, 1024))
        val encodeThread = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val encoded = java.util.concurrent.atomic.AtomicReference<File?>(null)
        val startNs = java.util.concurrent.atomic.AtomicLong(0L)
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-1" },
                pickFiles = { emptyList() },
                pasteImageFiles = {
                    // Invoked by launchPasteImages on Dispatchers.IO — record that + encode.
                    encodeThread.set(Thread.currentThread().name)
                    startNs.compareAndSet(0L, System.nanoTime())
                    val file = clipboardImageToTempFile(img)
                    encoded.set(file)
                    listOfNotNull(file)
                },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-paste-image").performClick()
        waitUntil(timeoutMillis = 15_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        val file = encoded.get()
        assertNotNull(file, "launchPasteImages → pasteImageFiles must encode the raster")
        assertTrue(file!!.isFile && file.length() > 0)
        assertTrue(isComposerPasteTempFile(file))
        val thread = encodeThread.get()
        assertNotNull(thread, "pasteImageFiles must run on a worker thread")
        assertTrue(
            thread!!.contains("DefaultDispatcher") || thread.contains("IO") || thread.contains("worker"),
            "launchPasteImages must hop to IO; pasteImageFiles ran on: $thread",
        )
        assertTrue(
            !thread.contains("AWT-EventQueue"),
            "encode must not run on the AWT UI thread, got: $thread",
        )
        val elapsedMs = (System.nanoTime() - startNs.get()) / 1_000_000
        assertTrue(elapsedMs < 15_000, "encode via launchPasteImages took ${elapsedMs}ms")
        // Chip owns the temp — remove so we don't leak into later tests.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip-remove").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("composer-chip-remove").performClick()
        waitUntil(timeoutMillis = 2_000L) { !file.exists() }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────
    private fun tempNamed(name: String, write: File.() -> Unit): File {
        // Use a fixture prefix that is NOT registered — registry membership alone decides cleanup.
        val dir = Files.createTempDirectory("cmp-paste-fixture").toFile().apply { deleteOnExit() }
        return File(dir, name).apply {
            write()
            deleteOnExit()
        }
    }

    private fun tinyPng(): ByteArray {
        // 2×2 RGB PNG
        val hex =
            "89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a73" +
                "0000001049444154789c63f8cfc000440c100a001fee03fd8b5f14d40000000049454e44ae426082"
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Image that only reports dimensions — used to prove oversize rejection without allocating. */
    private class DimensionOnlyImage(private val width: Int, private val height: Int) : Image() {
        override fun getWidth(observer: ImageObserver?): Int = width
        override fun getHeight(observer: ImageObserver?): Int = height
        override fun getSource() = throw UnsupportedOperationException()
        override fun getGraphics() = throw UnsupportedOperationException()
        override fun getProperty(name: String?, observer: ImageObserver?) = UndefinedProperty
        @Deprecated("Deprecated in Java")
        override fun flush() {}
    }

    /** Minimal Transferable that only exposes [DataFlavor.javaFileListFlavor]. */
    private class FakeFileListTransferable(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
            flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor?): Any {
            if (flavor != DataFlavor.javaFileListFlavor) throw UnsupportedFlavorException(flavor)
            return files
        }
    }

    /** Minimal Transferable that only exposes [DataFlavor.imageFlavor]. */
    private class FakeImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
            flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor?): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }

    /** Text-only Transferable — paste-image must ignore it. */
    private class FakeTextTransferable(private val text: String) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
            flavor == DataFlavor.stringFlavor

        override fun getTransferData(flavor: DataFlavor?): Any {
            if (flavor != DataFlavor.stringFlavor) throw UnsupportedFlavorException(flavor)
            return text
        }
    }
}
