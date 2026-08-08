// The desktop chat composer — a keyboard-first input with attachment chips (M4d). A single soft
// rounded card holds the multiline draft (top), a chip row when attachments are staged, and a
// bottom toolbar: + attach · model pill · reasoning/"mode" pill on the left; mic + send/stop on
// the right. Enter sends, Shift+Enter inserts a newline — the same preview-phase key handling as
// OnboardingScreen.submitOnEnter, so hardware Enter never also drops a newline and a
// blank/sending/upload-blocked draft lets the field keep the key.
//
// Unlike the launcher (which STAGES files pre-spawn and uploads them post-spawn), the chat composer
// uploads each chip IMMEDIATELY against the LIVE session — so a chip carries a live upload STATE
// (Uploading(pct) → Done(fileId) | Failed), not a launcher-style StagedUpload. Send is gated while
// any chip is still Uploading OR Failed (the "any upload failure blocks the send" rule, ported from
// Android's ChatPanel composer) so a message is never sent minus its attachment.
//
// M4d-T2 adds external-file drag-and-drop via `androidx.compose.foundation.draganddrop.dragAndDropTarget`
// (compose-multiplatform 1.11.1). The Modifier itself is STABLE; the payload type it hands back —
// `androidx.compose.ui.draganddrop.DragData` / the `DragAndDropEvent.dragData()` accessor — is marked
// `@ExperimentalComposeUiApi` in this release (confirmed by decompiling the shipped jars: no marker on
// `dragAndDropTarget`, but `DragData` and `dragData()` both carry it), hence the file-level `@OptIn`
// below. Compose Desktop surfaces an OS file drop as `DragData.FilesList` (java.awt's
// DataFlavor.javaFileListFlavor under the hood); each entry is a `file:` URI string, converted back to
// a File and funneled through the SAME `stageFiles` path the Attach dialog uses, so a dropped file
// gets an identical ComposerAttachment + upload + progress. Clipboard paste-image (Ctrl/Cmd+V) also
// routes through that funnel: a raster image on the system clipboard is written into the app's
// exclusive paste-cache directory (beside desktop config), and any copied image files
// (javaFileListFlavor) are filtered and staged the same way. Cache entries are pruned by age —
// never deleted by individual path during chip/send lifecycle (see paste-cache section below).
package dev.supermux.desktop.chat

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import dev.supermux.desktop.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.desktop.session.AgentLogo
import dev.supermux.desktop.session.hasAgentLogo
import dev.supermux.desktop.session.DEFAULT_MODEL_ID
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.ui.Speedometer
import dev.supermux.desktop.upload.FileChunkSource
import dev.supermux.net.ChunkSource
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.effortSpeedometerParams
import dev.supermux.net.sortEffortLevelsLowToHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Pure Enter-key predicate for the composer: `true` only for a KeyDown Enter / NumPad-Enter with
 * Shift NOT held — i.e. the "send" chord. Shift+Enter (newline), key-up, and every other key are
 * `false`. Extracted from [DesktopComposer]'s `onPreviewKeyEvent` so the send-on-Enter contract is
 * unit-testable as plain logic, independent of whether the desktop UI-test harness can inject key
 * events into a focused field.
 */
internal fun isComposerSendKey(key: Key, type: KeyEventType, shiftPressed: Boolean): Boolean =
    type == KeyEventType.KeyDown &&
        (key == Key.Enter || key == Key.NumPadEnter) &&
        !shiftPressed

/** Live upload state of one composer attachment chip. */
sealed interface UploadState {
    /** Upload in flight; [pct] is 0f..1f absolute progress (0 until the first callback). */
    data class Uploading(val pct: Float) : UploadState
    /** Finalized on the broker — [fileId] is what a send passes in `attachments`. */
    data class Done(val fileId: String) : UploadState
    /** The resumable upload gave up — the chip stays with a Retry affordance (never a silent drop). */
    data object Failed : UploadState
}

/**
 * One staged attachment in the chat composer. Tracked by a stable [id] (progress copies the object,
 * so object identity is not usable). [source] is kept so Retry can re-run the upload. [runSeq] is
 * the identity of the *current* upload run for this chip: Retry bumps it, and a progress/terminal
 * callback only applies while it still matches — so a late callback from a superseded run (or from a
 * run whose chip was removed) is dropped, never resurrecting or clobbering a chip (the M4c lesson).
 */
data class ComposerAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val source: FileChunkSource,
    val state: UploadState,
    val kind: String? = null,
    val runSeq: Long = 0L,
    /**
     * Absolute path of the staged local file when known (paste-cache entry / picked path).
     * Paste images live under the app-owned paste-cache; they are **not** deleted when the chip
     * is removed — age-based [prunePasteCache] reclaims them. Null when the source is not
     * path-backed (shouldn't happen for [FileChunkSource] stages).
     */
    val localPath: String? = null,
)

/**
 * One-shot "attach this file then send" request for [DesktopComposer] (M4d-T3), delivered from
 * outside the composer's own state (ShellUiState.externalAttach → SessionDetail → ChatPanel).
 * Drives the SAME `stageFiles`/`sendWith` funnel the Attach dialog + Send button use — see
 * [DesktopComposer]'s `externalAttach` param KDoc. Set by the off-by-default `SM_CHAT_ATTACH`
 * headless hook in Main.kt so the attach→upload→send round-trip can be proven under Xvfb with no
 * pointer/keyboard input.
 */
data class ComposerExternalAttach(val filePath: String, val text: String)

/** One-shot "transcribe this WAV file and append its cleaned text to the draft" request for
 *  [DesktopComposer] (M5-1), delivered from outside the composer's own mic-click state
 *  (ShellUiState.externalDictate -> SessionDetail -> ChatPanel), mirroring
 *  [ComposerExternalAttach]. Drives the SAME [DesktopComposer]'s `onTranscribeAudio` seam the mic
 *  button uses — only the TRIGGER differs (a file already on disk instead of a live TargetDataLine
 *  capture) — so it proves the real POST->append round-trip under Xvfb, where there is no real mic.
 *  Set by the off-by-default `SM_DICTATE` headless hook in Main.kt. */
data class ComposerExternalDictate(val wavPath: String)

/** Best-effort MIME for a path (java.nio Files.probeContentType), octet-stream when unknown. Pure —
 *  mirrors the launcher's `probeMime` so chat + launcher guess identically. */
internal fun composerMime(path: java.nio.file.Path): String =
    runCatching { Files.probeContentType(path) }.getOrNull() ?: "application/octet-stream"

/** Kind guess from a MIME: audio → "voice", else null (broker infers). Mirrors the launcher. */
internal fun composerKind(mime: String): String? =
    if (mime.startsWith("audio")) "voice" else null

/** Filters picked/dropped files to ones that still exist as a regular file on disk — pure so the
 *  filtering is unit-testable without AWT or Compose. Silently drops entries that vanished between
 *  the OS drop/dialog and staging (a stale symlink target, a file deleted mid-drag) rather than
 *  letting a missing file crash [FileChunkSource]. */
internal fun filterExistingFiles(files: List<File>): List<File> = files.filter { it.isFile }

/** Converts the file-URI strings from `DragData.FilesList.readFiles()` back into [File]s. Compose
 *  Desktop's drag source encodes each dropped OS file as `File.toURI().toString()` (a `file:` URI),
 *  not a raw path — pure so the URI parsing is unit-testable without an actual AWT drag session. An
 *  entry that fails to parse (malformed/non-file URI) is dropped rather than throwing. */
internal fun composerFilesFromDragData(uris: List<String>): List<File> =
    uris.mapNotNull { runCatching { File(URI(it)) }.getOrNull() }

/**
 * Pure Ctrl/Cmd+V paste-key predicate for the composer: `true` only for a KeyDown V with Ctrl OR
 * Meta held (Windows/Linux vs macOS) and Shift **not** held. Ctrl and Meta are **separate** flags
 * so tests can prove each modifier path distinctly (not a single `ctrlOrMeta=true` called twice).
 * Ctrl/Cmd+Shift+V is the conventional "paste as plain text / match style" chord and must fall
 * through to the text field.
 */
internal fun isComposerPasteKey(
    key: Key,
    type: KeyEventType,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
    shiftPressed: Boolean = false,
): Boolean =
    type == KeyEventType.KeyDown &&
        key == Key.V &&
        (ctrlPressed || metaPressed) &&
        !shiftPressed

/**
 * Whether a paste-image gesture should consume the key and call [stageFiles]: the upload seam must
 * be bound (text-only composers ignore paste-image) AND the clipboard seam returned at least one
 * image file. Pure so the stage-or-fallthrough decision is unit-testable without key injection.
 */
internal fun shouldStageClipboardPaste(uploadBound: Boolean, files: List<File>): Boolean =
    uploadBound && files.isNotEmpty()

/**
 * Production paste-key handler decision used by [DesktopComposer]'s `onPreviewKeyEvent`.
 * Returns true when the event should be **consumed** for paste-image (and [onPasteImage] is
 * invoked); false when the key must fall through to the text field (text paste / non-paste keys).
 * Distinct [ctrlPressed]/[metaPressed] so Ctrl vs Meta are real separate inputs.
 */
internal fun handleComposerPasteKey(
    key: Key,
    type: KeyEventType,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
    shiftPressed: Boolean,
    uploadBound: Boolean,
    /** Lazily evaluated so the clipboard is only probed after the paste-key chord matches. */
    likelyHasImage: () -> Boolean,
    onPasteImage: () -> Unit,
): Boolean {
    if (!isComposerPasteKey(key, type, ctrlPressed, metaPressed, shiftPressed)) return false
    if (uploadBound && likelyHasImage()) {
        onPasteImage()
        return true
    }
    return false
}

/** Image file extensions accepted for clipboard file-list paste (probe MIME may be octet-stream
 *  for a just-copied path with no content-type association). */
private val IMAGE_FILE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff",
)

/**
 * Dimension / size caps for clipboard raster paste. Applied **before** allocating a pixel buffer
 * or encoding PNG so a huge paste cannot freeze or OOM the desktop process.
 * - [PASTE_IMAGE_MAX_EDGE]: max width or height in pixels (hard reject)
 * - [PASTE_IMAGE_MAX_PIXELS]: max `width * height` (bounds the ARGB buffer for non-[BufferedImage]s)
 * - [PASTE_IMAGE_ENCODE_MAX_EDGE]: soft downscale target before PNG encode (keeps large pastes fast)
 * - [PASTE_IMAGE_MAX_ENCODED_BYTES]: max written PNG size; oversize files are dropped (left for pruner)
 */
internal const val PASTE_IMAGE_MAX_EDGE: Int = 8192
internal const val PASTE_IMAGE_MAX_PIXELS: Long = 16L * 1024L * 1024L // 16 MP
/** Max edge after optional downscale for encode — 4096² PNG encode is multi-second; 2048² is cheap. */
internal const val PASTE_IMAGE_ENCODE_MAX_EDGE: Int = 2048
internal const val PASTE_IMAGE_MAX_ENCODED_BYTES: Long = 8L * 1024L * 1024L // 8 MiB

// ── paste-cache: app-owned directory, random names, age-based prune only ─────────
//
// Pasted raster images are written under `<desktop-config>/paste-cache/` (next to auth.json /
// ui-state.json / kcef-bundle), NOT under shared /tmp. Each write uses a fresh UUID name; names
// are never reused. Individual files are never deleted by path during chip remove / send /
// session dispose — that class of bug is removed by design. [prunePasteCache] reclaims entries
// older than [PASTE_CACHE_TTL] on startup and opportunistically on write. The pruner never
// follows symlinks out of the cache and refuses to run if the cache path resolves outside the
// app config directory.

/** Directory name under the desktop config root for clipboard paste PNGs. */
internal const val PASTE_CACHE_DIR_NAME = "paste-cache"

/** Age after which paste-cache entries are reclaimed. Short: pastes only need to survive upload. */
internal val PASTE_CACHE_TTL: Duration = Duration.ofHours(1)

/**
 * Test override for the desktop config directory ([DesktopTokenStore.defaultPath] parent).
 * When set, paste-cache is `<override>/paste-cache/`. Cleared by tests in teardown.
 */
@Volatile
internal var desktopConfigDirOverride: Path? = null

/** Resolved desktop config directory (production or test override). */
internal fun desktopConfigDir(): Path =
    (desktopConfigDirOverride ?: DesktopTokenStore.defaultPath().parent)
        .toAbsolutePath().normalize()

/** Paste-cache directory: always a direct child of [desktopConfigDir] named [PASTE_CACHE_DIR_NAME]. */
internal fun pasteCacheDir(): Path = desktopConfigDir().resolve(PASTE_CACHE_DIR_NAME)

/**
 * Ensure the paste-cache directory exists and its real path still lies under the app config dir.
 * Returns null when the path would escape the config root (e.g. paste-cache is a symlink out) —
 * callers must not write then.
 */
internal fun ensurePasteCacheDir(): Path? = runCatching {
    val config = desktopConfigDir()
    Files.createDirectories(config)
    val cache = pasteCacheDir()
    Files.createDirectories(cache)
    val configReal = config.toRealPath()
    val cacheReal = cache.toRealPath()
    if (!cacheReal.startsWith(configReal)) return@runCatching null
    // Must remain the direct paste-cache child of config (no intervening symlink rename games).
    if (cacheReal.fileName.toString() != PASTE_CACHE_DIR_NAME) return@runCatching null
    cacheReal
}.getOrNull()

/**
 * Whether [name] matches the paste-cache files this app writes (`paste-<uuid>.png`).
 * The age pruner only unlinks names that pass this check — foreign files in the cache dir
 * (even aged ones) are left alone.
 */
internal fun isComposerPasteCacheEntryName(name: String): Boolean =
    name.startsWith("paste-") && name.endsWith(".png")

/**
 * Reclaim aged **app-owned** regular-file entries inside the paste-cache.
 *
 * Safety rules (by design — no identity registry):
 * - Refuses to run if the cache path's real location is outside the app config directory.
 * - Lists the cache directory only; never recurses.
 * - Reads each entry with [LinkOption.NOFOLLOW_LINKS]; skips symlinks and non-regular files.
 * - Deletes only regular files whose **name** matches [isComposerPasteCacheEntryName]
 *   (`paste-*.png` — the only names [clipboardImageToTempFile] writes) **and** whose
 *   last-modified time is older than [maxAge]. Anything else in the directory is not ours.
 *
 * @return number of files deleted, or `-1` when the pruner refused to run (unsafe path).
 */
internal fun prunePasteCache(
    maxAge: Duration = PASTE_CACHE_TTL,
    now: Instant = Instant.now(),
): Int {
    val config = desktopConfigDir()
    val cache = pasteCacheDir()
    if (!Files.exists(cache)) return 0

    val configReal = runCatching {
        if (Files.exists(config)) config.toRealPath() else config.toAbsolutePath().normalize()
    }.getOrElse { return -1 }
    val cacheReal = runCatching { cache.toRealPath() }.getOrElse { return -1 }
    if (!cacheReal.startsWith(configReal)) return -1
    if (cacheReal.fileName.toString() != PASTE_CACHE_DIR_NAME) return -1

    // When paste-cache itself is a symlink, toRealPath already followed it; we require the
    // resolved location to sit under config (checked above). Listing uses the real path so we
    // never walk through a symlink entry *out* of the cache via a child link.
    if (!Files.isDirectory(cacheReal)) return -1

    val cutoff = now.minus(maxAge)
    var deleted = 0
    runCatching {
        Files.newDirectoryStream(cacheReal).use { stream ->
            for (entry in stream) {
                // Provenance: only ever unlink names we generate (paste-<uuid>.png).
                if (!isComposerPasteCacheEntryName(entry.fileName.toString())) continue
                val attrs = runCatching {
                    Files.readAttributes(
                        entry,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }.getOrNull() ?: continue
                // Never follow or delete through a symlink (target may lie outside the cache).
                if (attrs.isSymbolicLink) continue
                if (!attrs.isRegularFile) continue
                if (attrs.lastModifiedTime().toInstant().isAfter(cutoff)) continue
                // Re-check immediately before unlink: skip if entry became a symlink or renamed.
                val still = runCatching {
                    Files.readAttributes(
                        entry,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }.getOrNull() ?: continue
                if (still.isSymbolicLink || !still.isRegularFile) continue
                if (!isComposerPasteCacheEntryName(entry.fileName.toString())) continue
                if (runCatching { Files.deleteIfExists(entry) }.getOrDefault(false)) {
                    deleted++
                }
            }
        }
    }
    return deleted
}

/**
 * Whether [file] looks like an image suitable for paste-to-attach: an `image/` MIME from
 * [composerMime], or a known image extension when the probe falls back to octet-stream.
 */
internal fun isComposerImageFile(file: File): Boolean {
    if (!file.isFile) return false
    val mime = composerMime(file.toPath())
    if (mime.startsWith("image/")) return true
    return file.extension.lowercase() in IMAGE_FILE_EXTENSIONS
}

/**
 * Whether [image] passes dimension caps (edge + pixel count) before any buffer allocation / encode.
 * Pure so oversize rejection is unit-testable without ImageIO.
 */
internal fun clipboardImageWithinCaps(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    if (width > PASTE_IMAGE_MAX_EDGE || height > PASTE_IMAGE_MAX_EDGE) return false
    // Promote to Long before multiply to avoid Int overflow on huge dims.
    if (width.toLong() * height.toLong() > PASTE_IMAGE_MAX_PIXELS) return false
    return true
}

/**
 * Scale [source] so neither edge exceeds [maxEdge], preserving aspect ratio. Returns [source]
 * unchanged when already within the bound. Pure relative to the bitmap.
 */
internal fun scaleBufferedImageToMaxEdge(source: BufferedImage, maxEdge: Int): BufferedImage {
    val w = source.width
    val h = source.height
    if (w <= maxEdge && h <= maxEdge) return source
    val scale = minOf(maxEdge.toDouble() / w, maxEdge.toDouble() / h)
    val nw = (w * scale).roundToInt().coerceAtLeast(1)
    val nh = (h * scale).roundToInt().coerceAtLeast(1)
    val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, nw, nh, null)
    } finally {
        g.dispose()
    }
    return out
}

/**
 * Write an AWT [Image] (screenshot / copy-from-viewer paste) into the app-owned [pasteCacheDir]
 * as a freshly named PNG and return the file. Applies dimension + encoded-byte caps before /
 * after encode. Large images are downscaled to [PASTE_IMAGE_ENCODE_MAX_EDGE] before PNG encode.
 *
 * **No per-file deletion** — failed encodes may leave a partial/oversize entry for [prunePasteCache];
 * chip remove / send never unlink by path. Names are UUID-based and never reused.
 *
 * **Must not run on the UI thread** for large rasters — PNG encode of a multi-megapixel image is
 * multi-second without downscale. Call from [Dispatchers.IO].
 *
 * @param maxEncodedBytes injectable for tests of the encoded-byte reject path without writing 8 MiB.
 */
internal fun clipboardImageToTempFile(
    image: Image,
    maxEncodedBytes: Long = PASTE_IMAGE_MAX_ENCODED_BYTES,
    encodeMaxEdge: Int = PASTE_IMAGE_ENCODE_MAX_EDGE,
): File? = runCatching {
    val w = image.getWidth(null)
    val h = image.getHeight(null)
    if (!clipboardImageWithinCaps(w, h)) return null
    // Cap already bounds the w*h*4 allocation for non-BufferedImage copies.
    val raw = when (image) {
        is BufferedImage -> image
        else -> BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { bi ->
            val g = bi.createGraphics()
            try {
                g.drawImage(image, 0, 0, null)
            } finally {
                g.dispose()
            }
        }
    }
    // Downscale large pastes before PNG encode (quality/speed trade-off for chat attach).
    val buffered = scaleBufferedImageToMaxEdge(raw, encodeMaxEdge)
    val dir = ensurePasteCacheDir() ?: return null
    // Opportunistic reclaim of aged entries (also runs at app startup).
    prunePasteCache()
    val out = dir.resolve("paste-${UUID.randomUUID()}.png").toFile()
    if (!ImageIO.write(buffered, "png", out)) {
        // Leave any partial for the age pruner — never delete-by-path.
        return null
    }
    if (out.length() > maxEncodedBytes) {
        // Oversize: leave for pruner; do not hand out the path.
        return null
    }
    out
}.getOrNull()

/**
 * Cheap flavor-only probe: does this transferable *likely* hold a pasteable image? Used on the UI
 * thread to decide whether to consume Ctrl/Cmd+V without running PNG encode. File-list is filtered
 * to image files; raster [DataFlavor.imageFlavor] is accepted without decoding.
 */
internal fun transferableLikelyHasImage(transferable: Transferable): Boolean {
    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        @Suppress("UNCHECKED_CAST")
        val files = runCatching {
            transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        }.getOrNull()
            ?.mapNotNull { it as? File }
            ?.filter(::isComposerImageFile)
            .orEmpty()
        if (files.isNotEmpty()) return true
    }
    return transferable.isDataFlavorSupported(DataFlavor.imageFlavor)
}

/**
 * Extract image files from a clipboard [Transferable]. Prefers a file-list of existing image files
 * (user copied image files in the file manager); otherwise encodes a raster [DataFlavor.imageFlavor]
 * snapshot to a temp PNG (capped). Pure relative to the transferable so tests can feed a fake
 * without touching the real system clipboard. **May be slow** for large rasters — call off the UI
 * thread.
 */
internal fun composerFilesFromClipboardTransferable(transferable: Transferable): List<File> {
    // File-list first: multi-select paste of real files should keep original names/MIME.
    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        @Suppress("UNCHECKED_CAST")
        val files = runCatching {
            transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        }.getOrNull()
            ?.mapNotNull { it as? File }
            ?.filter(::isComposerImageFile)
            .orEmpty()
        if (files.isNotEmpty()) return files
    }
    if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val img = runCatching {
            transferable.getTransferData(DataFlavor.imageFlavor) as? Image
        }.getOrNull()
        if (img != null) {
            clipboardImageToTempFile(img)?.let { return listOf(it) }
        }
    }
    return emptyList()
}

/**
 * Read image files currently on the system clipboard (AWT). Empty on any failure / text-only clip.
 * **May encode a large PNG** — always invoke from [Dispatchers.IO], never the Compose UI thread.
 */
internal fun composerClipboardImageFiles(): List<File> = runCatching {
    val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return emptyList()
    composerFilesFromClipboardTransferable(contents)
}.getOrDefault(emptyList())

/**
 * Cheap UI-thread probe of the system clipboard for paste-image consumption decisions.
 * Does not encode; only checks flavors / file-list membership.
 */
internal fun composerClipboardLikelyHasImage(): Boolean = runCatching {
    val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return false
    transferableLikelyHasImage(contents)
}.getOrDefault(false)

/**
 * Send-gating predicate: something to send (text OR at least one attachment) AND no chip is still
 * Uploading or Failed AND not already sending. Pure so the gating matrix is unit-testable without a
 * UI harness. The Uploading/Failed block is the load-bearing correctness bit — never send a message
 * minus its attachment (ported from Android's `anyBlocking` rule).
 */
internal fun canSendComposer(
    text: String,
    attachments: List<ComposerAttachment>,
    sending: Boolean,
): Boolean =
    (text.isNotBlank() || attachments.isNotEmpty()) &&
        attachments.none { it.state is UploadState.Uploading || it.state is UploadState.Failed } &&
        !sending

/**
 * Display label for the composer's model pill: the [ModelInfo.displayName] of the current model id,
 * the raw id when it isn't in the catalog, or "Default" when the session has no explicit model (a
 * null/blank current). Pure so the current→label mapping is unit-testable. Mirrors the launcher's
 * DEFAULT_MODEL_ID handling so a null model round-trips to "Default" in both surfaces.
 */
internal fun composerModelLabel(current: String?, models: List<ModelInfo>): String {
    val id = current?.takeIf { it.isNotBlank() } ?: return "Default"
    return models.firstOrNull { it.id == id }?.displayName ?: id
}

/** The picker-option id that matches [current] (so it gets the check): the raw model id, or the
 *  [DEFAULT_MODEL_ID] sentinel when the session has no explicit model (null/blank). Pure. */
internal fun composerModelSelectedId(current: String?): String =
    current?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ID

/** Blocking AWT multi-select file picker (modal on the EDT by AWT contract — fine, Compose Desktop
 *  Main == EDT). The default [DesktopComposer.pickFiles] seam; tests inject a fake. */
internal fun composerPickFiles(): List<File> {
    val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files?.toList() ?: emptyList()
}

/**
 * Chat composer with attachment chips.
 *
 * @param draft current draft text (hoisted — per-session in [ChatPanel]/AppShell).
 * @param sending true while the client-local "Sending…" marker is up (blocks re-send).
 * @param agentWorking true while the broker says the agent is busy — flips the trailing icon to
 *   Stop so the user can interrupt without leaving the composer.
 * @param onSend fired with the TRIMMED draft + the finalized attachment file_ids (gated so all
 *   staged chips are Done). The composer clears its own chips on send; the caller clears the draft.
 * @param onInterrupt fired by the Stop icon while [agentWorking].
 * @param onUpload the upload seam — `(source, name, mime, kind, onProgress) -> file_id?`. When null,
 *   the Attach affordance is hidden (text-only composer). [ChatPanel] binds this to
 *   `app.uploadResumable(session.id, …)`; tests inject a fake so they don't hit the network.
 * @param pickFiles the file-picker seam (default = the real AWT dialog); tests inject a fake.
 * @param pasteImageFiles the clipboard-image seam (default = [composerClipboardImageFiles]); returns
 *   image files currently on the system clipboard. Ctrl/Cmd+V routes a non-empty result through the
 *   SAME [stageFiles] funnel drag-drop / Attach use. Tests inject a fake so they never touch AWT
 *   clipboard state.
 * @param clipboardLikelyHasImage cheap UI-thread probe (default = [composerClipboardLikelyHasImage])
 *   used to decide whether Ctrl/Cmd+V should be consumed for paste-image. Tests inject so key-event
 *   tests do not depend on the real system clipboard.
 * @param externalAttach a one-shot "stage this file then send" request (M4d-T3), delivered from
 *   outside the composer's own click-driven state (see [ComposerExternalAttach] KDoc — the
 *   off-by-default `SM_CHAT_ATTACH` headless hook). Routed through the SAME `stageFiles`/`sendWith`
 *   funnel the Attach dialog + Send button use — never a parallel path. Applied once, then
 *   [onExternalAttachConsumed] clears the source (mirrors [ChatPanel]'s `externalOpen` pattern).
 * @param onExternalAttachConsumed fired once [externalAttach] has been staged, uploaded to a
 *   terminal state, and (on success) sent — or dropped (missing file / no [onUpload] bound / upload
 *   failed) — so the caller's one-shot holder resets.
 * @param onTranscribeAudio the mic-dictation transcribe seam — `(wavBytes, filename) -> cleaned
 *   text?`. When null, the MicButton is hidden entirely (mirrors [onUpload]'s null-hides-Attach
 *   rule). [ChatPanel] binds this to `app.transcribeAudio(session.id, bytes, filename)`.
 * @param micRecorderFactory the mic-capture seam (default = the real [MicRecorder]); tests inject
 *   a fake [MicCapture] so they never open a real audio line.
 * @param externalDictate a one-shot "transcribe this WAV file, no mic" request (M5-1), delivered
 *   from outside the composer's own click-driven state — see [ComposerExternalDictate]'s KDoc.
 * @param onExternalDictateConsumed fired once [externalDictate] has been read and its cleaned text
 *   (if any) appended, so the caller's one-shot holder resets.
 * @param models the session's model catalog + current selection (GET /sessions/<id>/models). When
 *   non-null (or [sessionModel] is set) a model pill renders above the field; picking a model fires
 *   [onPickModel]. [ChatPanel] owns this state (fetch-on-open + optimistic update after a pick).
 * @param reasoning the session's reasoning/thinking levels + current + visibility
 *   (GET /sessions/<id>/reasoning-levels). The reasoning pill renders ONLY when `visible` is true
 *   AND there is more than one level (matching Android's `effortVisible`); picking fires
 *   [onPickReasoning].
 * @param sessionModel the session's last-known model from [dev.supermux.proto.SessionInfo] — the
 *   fallback for the pill's current label until [models] loads (`models?.current ?: sessionModel`).
 * @param sessionAgent the session's agent kind (`claude`/`codex`/…); drives the text-height brand
 *   mark on the model pill (launcher parity).
 * @param onPickModel fired with the picked model id — the empty string for the "Default" (no
 *   explicit model) row. [ChatPanel] binds this to `app.switchModel(session.id, …)`.
 * @param onPickReasoning fired with the picked reasoning-level id. [ChatPanel] binds this to
 *   `app.switchReasoning(session.id, …)`.
 */
@OptIn(ExperimentalComposeUiApi::class) // DragData / dragData() (external-file drop payload) — see
// the drop-target comment below for what was checked before opting in.
@Composable
fun DesktopComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    agentWorking: Boolean,
    onSend: (String, List<String>) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
    sessionKey: String = "",
    onUpload: (suspend (
        source: ChunkSource,
        name: String,
        mime: String,
        kind: String?,
        onProgress: (Long, Long) -> Unit,
    ) -> String?)? = null,
    pickFiles: () -> List<File> = ::composerPickFiles,
    pasteImageFiles: () -> List<File> = ::composerClipboardImageFiles,
    clipboardLikelyHasImage: () -> Boolean = ::composerClipboardLikelyHasImage,
    externalAttach: ComposerExternalAttach? = null,
    onExternalAttachConsumed: () -> Unit = {},
    onTranscribeAudio: (suspend (bytes: ByteArray, filename: String) -> String?)? = null,
    micRecorderFactory: () -> MicCapture = { MicRecorder() },
    externalDictate: ComposerExternalDictate? = null,
    onExternalDictateConsumed: () -> Unit = {},
    models: ModelsResponse? = null,
    reasoning: ReasoningResponse? = null,
    sessionModel: String? = null,
    sessionAgent: String? = null,
    onPickModel: (String) -> Unit = {},
    onPickReasoning: (String) -> Unit = {},
    /**
     * One-shot paste-image request from the native Edit ▸ Paste image menu (or tests). When the
     * nonce changes to a non-zero value, runs the same [launchPasteImages] path as Ctrl/Cmd+V /
     * right-click Paste image, then calls [onPasteImageRequestConsumed].
     */
    pasteImageRequestNonce: Long = 0L,
    onPasteImageRequestConsumed: () -> Unit = {},
) {
    // Attachment state is SCOPED to [sessionKey]: ChatPanel deliberately stays composed across
    // session switches (no key(session.id) wrapper), so a bare remember{} would leak session A's
    // uploaded chips into session B and gather A's file_ids into B's send. remember(sessionKey)
    // re-inits the list + id counters on switch (matches ChatPanel's prevSize/autoFollow pattern).
    val attachments = remember(sessionKey) { mutableStateListOf<ComposerAttachment>() }
    // Plain-var counters (single Main-thread dispatcher — no atomics needed); one holder per session.
    val ids = remember(sessionKey) { object { var nextId = 0L; var nextSeq = 0L } }
    val scope = rememberCoroutineScope()
    // Session switch discards [attachments] via remember(sessionKey). Paste-cache files are left
    // for [prunePasteCache] (age TTL) — never unlinked by path on dispose.

    // Guarded update: apply only when the chip STILL exists AND belongs to the run identified by
    // [seq]. A late callback from a removed chip (idx < 0) or a superseded run (runSeq mismatch, e.g.
    // after Retry) is silently dropped — the stale-callback guard.
    fun updateAtt(id: String, seq: Long, transform: (ComposerAttachment) -> ComposerAttachment) {
        val idx = attachments.indexOfFirst { it.id == id }
        if (idx >= 0 && attachments[idx].runSeq == seq) attachments[idx] = transform(attachments[idx])
    }

    // Start (or restart, on Retry) the resumable upload for one chip, driving Uploading(pct) →
    // Done(fileId) | Failed. Each run gets a fresh [seq] so an older run's callbacks can't win.
    fun launchUpload(id: String) {
        val up = onUpload ?: return
        val idx = attachments.indexOfFirst { it.id == id }
        if (idx < 0) return
        val seq = ++ids.nextSeq
        val att = attachments[idx].copy(state = UploadState.Uploading(0f), runSeq = seq)
        attachments[idx] = att
        scope.launch {
            val fileId = up(att.source, att.name, att.mime, att.kind) { sent, total ->
                val pct = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
                // Progress may arrive off the Main thread (uploadResumable runs its IO internally);
                // marshal the state write back onto the composer scope's dispatcher. Guard against a
                // TERMINAL clobber: uploadResumable fires a final onProgress(total,total) right before
                // returning, and that marshaled write is QUEUED — it lands AFTER the synchronous
                // Done/Failed write below. Only apply while the chip is still Uploading, so the queued
                // final progress can't resurrect a settled chip to Uploading(1.0) (a stuck dead-end:
                // Uploading blocks send AND hides the × remove).
                scope.launch {
                    updateAtt(id, seq) {
                        if (it.state is UploadState.Uploading) it.copy(state = UploadState.Uploading(pct)) else it
                    }
                }
            }
            updateAtt(id, seq) {
                if (fileId != null) it.copy(state = UploadState.Done(fileId))
                else it.copy(state = UploadState.Failed)
            }
        }
    }

    fun stage(file: File) {
        val mime = composerMime(file.toPath())
        val id = (++ids.nextId).toString()
        attachments.add(
            ComposerAttachment(
                id = id,
                name = file.name,
                mime = mime,
                source = FileChunkSource(file),
                state = UploadState.Uploading(0f),
                kind = composerKind(mime),
                // Remember the path for diagnostics; paste-cache entries are reclaimed by age prune.
                localPath = file.absolutePath,
            ),
        )
        launchUpload(id)
    }

    // Funnels a BATCH of files — from the Attach dialog OR an external OS drag-drop / paste — through
    // [stage] after filtering to files that still exist. The single funnel means a dropped/pasted
    // file gets an IDENTICAL ComposerAttachment + upload + progress to a FileDialog-picked one.
    fun stageFiles(files: List<File>) {
        filterExistingFiles(files).forEach { stage(it) }
    }

    /** Drop a chip. Paste-cache files are left for [prunePasteCache] (never unlinked by path). */
    fun removeAttachment(id: String) {
        val idx = attachments.indexOfFirst { it.id == id }
        if (idx < 0) return
        attachments.removeAt(idx)
    }

    /** Clear all chips (after a successful send). Paste-cache reclaim is age-based only. */
    fun clearAttachments() {
        attachments.clear()
    }

    // True while pasteImageFiles() is running on IO — shows a pending chip so the ~1s encode is not
    // silent (chip for the real file appears only after encode+stage returns).
    var pastePending by remember(sessionKey) { mutableStateOf(false) }

    /**
     * Run [pasteImageFiles] off the UI thread, then stage any results. Used by Ctrl/Cmd+V and the
     * Paste-image menu/context actions so PNG encode never freezes the composer. Shows a pending
     * chip immediately so the user gets feedback during encode.
     */
    fun launchPasteImages() {
        if (onUpload == null) return
        if (pastePending) return
        pastePending = true
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) { pasteImageFiles() }
                if (shouldStageClipboardPaste(uploadBound = true, files = files)) {
                    stageFiles(files)
                }
                // Encode failed / empty: any partial paste-cache write is left for [prunePasteCache].
            } finally {
                pastePending = false
            }
        }
    }

    // Edit ▸ Paste image / ShellUiState.pasteImageRequestNonce — same funnel as Ctrl/Cmd+V.
    LaunchedEffect(pasteImageRequestNonce) {
        if (pasteImageRequestNonce > 0L) {
            launchPasteImages()
            onPasteImageRequestConsumed()
        }
    }

    val canSend = canSendComposer(draft, attachments, sending)
    // Gather-and-send for an ARBITRARY [text] (not just the hoisted [draft]) — same gating +
    // file_id-gather + chip-clear the Send button/Enter key use. Parameterized so
    // [externalAttach]'s LaunchedEffect below can send its own text without racing the hoisted
    // draft's recomposition (see that effect's comment for why draft-then-doSend() doesn't work).
    fun sendWith(text: String) {
        if (canSendComposer(text, attachments, sending)) {
            val fileIds = attachments.mapNotNull { (it.state as? UploadState.Done)?.fileId }
            onSend(text.trim(), fileIds)
            clearAttachments()
        }
    }
    val doSend = { sendWith(draft) }

    val dictation = rememberDesktopDictation(
        resetKey = sessionKey,
        transcribeAudio = { bytes, name -> onTranscribeAudio?.invoke(bytes, name) },
        onAppend = { cleaned -> onDraftChange(draft + (if (draft.isBlank()) "" else " ") + cleaned) },
        recorderFactory = micRecorderFactory,
    )

    // SM_DICTATE headless hook delivery (M5-1): read the WAV straight off disk and feed it through
    // the SAME onTranscribeAudio seam the mic button uses — no MicCapture involved at all, since
    // there is no mic under Xvfb. A missing/blank path or an unbound seam is logged and dropped.
    LaunchedEffect(externalDictate) {
        val request = externalDictate ?: return@LaunchedEffect
        if (onTranscribeAudio == null) {
            println("[composer] SM_DICTATE ignored — no transcribe seam bound")
            onExternalDictateConsumed()
            return@LaunchedEffect
        }
        val file = File(request.wavPath)
        if (!file.isFile) {
            println("[composer] SM_DICTATE path is not a file: ${request.wavPath}")
            onExternalDictateConsumed()
            return@LaunchedEffect
        }
        val cleaned = onTranscribeAudio.invoke(file.readBytes(), file.name)?.trim()
        if (!cleaned.isNullOrEmpty()) {
            onDraftChange(draft + (if (draft.isBlank()) "" else " ") + cleaned)
            println("[composer] SM_DICTATE appended cleaned text for '${request.wavPath}'")
        } else {
            println("[composer] SM_DICTATE transcribe returned no text for '${request.wavPath}'")
        }
        onExternalDictateConsumed()
    }

    // SM_CHAT_ATTACH headless hook delivery (M4d-T3): stage the requested file through the SAME
    // [stageFiles] funnel the Attach dialog/drop target use, poll (no completion callback exists on
    // the upload seam to suspend on directly) until that chip reaches a TERMINAL state, then —  on
    // success — [sendWith] the requested text through the SAME gather-and-send path the Send button
    // uses. Deliberately does NOT go through onDraftChange+doSend(): draft is hoisted OUTSIDE this
    // composable (AppShell's draft map), so writing it here and immediately calling the
    // (stale-closure) doSend would race the recomposition that updates `draft` — sendWith(text)
    // sidesteps that entirely. Keyed on [externalAttach] (not Unit) so a new request re-runs.
    LaunchedEffect(externalAttach) {
        val request = externalAttach ?: return@LaunchedEffect
        if (onUpload == null) {
            println("[composer] SM_CHAT_ATTACH ignored — no upload seam bound (text-only composer)")
            onExternalAttachConsumed()
            return@LaunchedEffect
        }
        val file = File(request.filePath)
        if (!file.isFile) {
            println("[composer] SM_CHAT_ATTACH path is not a file: ${request.filePath}")
            onExternalAttachConsumed()
            return@LaunchedEffect
        }
        val beforeIds = attachments.map { it.id }.toSet()
        stageFiles(listOf(file))
        val newId = attachments.map { it.id }.firstOrNull { it !in beforeIds }
        if (newId == null) {
            println("[composer] SM_CHAT_ATTACH staging produced no chip: ${request.filePath}")
            onExternalAttachConsumed()
            return@LaunchedEffect
        }
        // Poll (200ms) for the new chip to leave Uploading — up to 60s (a resumable upload chunk
        // loop, not a single request; generous so a slow/large file doesn't false-time-out).
        val deadline = System.currentTimeMillis() + 60_000
        var current = attachments.firstOrNull { it.id == newId }
        while (current != null && current.state is UploadState.Uploading && System.currentTimeMillis() < deadline) {
            delay(200)
            current = attachments.firstOrNull { it.id == newId }
        }
        if (current?.state is UploadState.Done) {
            sendWith(request.text)
            println("[composer] SM_CHAT_ATTACH sent '${request.filePath}' + text to the session")
        } else {
            println("[composer] SM_CHAT_ATTACH upload did not finish Done (state=${current?.state}): ${request.filePath}")
        }
        onExternalAttachConsumed()
    }

    // Drag-over highlight — purely visual, reset defensively on both onExited (pointer left this
    // target's bounds) and onEnded (the whole OS drag session finished, e.g. dropped elsewhere).
    var dragOver by remember(sessionKey) { mutableStateOf(false) }

    // External-file drop target. `androidx.compose.foundation.draganddrop.dragAndDropTarget` (the
    // Modifier attached below) is STABLE — no ExperimentalFoundationApi marker on it. Reading the
    // dropped payload as `DragData` DOES need the file-level `@OptIn(ExperimentalComposeUiApi::class)`
    // above (see the file header). On desktop, an external OS file drop arrives as `DragData.FilesList`
    // — decompiling `DragDataFilesListImpl` shows it reads `DataFlavor.javaFileListFlavor` off the AWT
    // transferable and maps each `java.io.File` to `file.toURI().toString()`, so
    // [composerFilesFromDragData] parses those URIs back to Files. Gated on `onUpload != null` — the
    // same rule as the Attach button: a text-only composer (no upload seam bound) doesn't accept drops.
    // Note: this anonymous target has no equals(), so dragAndDropTarget's DropTargetElement rebuilds
    // the underlying delegate node on every recomposition (a minor churn, not a swap-in-place). It's
    // safe: the AWT DropTarget is owned at the scene root and dispatches per-event by live tree
    // traversal, and onDrop closes over the same remember(sessionKey)-scoped state regardless of which
    // instance is live — so no in-flight drag is lost and no cross-session leak. (A remember(sessionKey)
    // wrap + rememberUpdatedState callback would remove the churn — a cheap future tidy.)
    val dropTarget = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) {
            dragOver = true
        }

        override fun onExited(event: DragAndDropEvent) {
            dragOver = false
        }

        override fun onEnded(event: DragAndDropEvent) {
            dragOver = false
        }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            val files = (event.dragData() as? DragData.FilesList)
                ?.readFiles()
                ?.let(::composerFilesFromDragData)
                ?: return false
            if (files.isEmpty()) return false
            stageFiles(files)
            return true
        }
    }

    // Soft capsule card (reference mock): one surface for draft + bottom toolbar. Drop highlight
    // thickens the border; focus uses a quieter primary-tint outline so the field still reads
    // as "the" input without the old OutlinedTextField chrome.
    val cs = MaterialTheme.colorScheme
    val inputInteraction = remember { MutableInteractionSource() }
    val inputFocused by inputInteraction.collectIsFocusedAsState()
    var modelMenu by remember { mutableStateOf(false) }
    var reasoningMenu by remember { mutableStateOf(false) }
    val modelCurrent = models?.current ?: sessionModel
    val showModelPill = models != null || !sessionModel.isNullOrBlank()
    val r = reasoning
    val showReasoningPill = r != null && r.visible && r.levels.size > 1
    val cardShape = RoundedCornerShape(Radii.lg + 8.dp) // ~24dp — matches the mock capsule
    val cardBorder = when {
        dragOver -> cs.primary
        inputFocused -> cs.outline.copy(alpha = 0.55f)
        else -> cs.outlineVariant.copy(alpha = 0.65f)
    }
    val cardBorderWidth = if (dragOver) 2.dp else 1.dp

    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (onUpload != null) {
                    Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                } else {
                    Modifier
                },
            ),
    ) {
        ContextMenuArea(
            items = {
                if (onUpload != null) {
                    listOf(ContextMenuItem("Paste image") { launchPasteImages() })
                } else {
                    emptyList()
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(cs.surfaceContainerHigh.copy(alpha = 0.72f))
                    .border(cardBorderWidth, cardBorder, cardShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("composer-card"),
            ) {
                if (attachments.isNotEmpty() || pastePending) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (pastePending) {
                            PastePendingChip()
                        }
                        attachments.forEach { att ->
                            key(att.id) {
                                ComposerChip(
                                    att = att,
                                    onRemove = { removeAttachment(att.id) },
                                    onRetry = { launchUpload(att.id) },
                                )
                            }
                        }
                    }
                }

                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp, max = 160.dp)
                        .testTag("composer-input")
                        .onPreviewKeyEvent { e: KeyEvent ->
                            if (isComposerSendKey(e.key, e.type, e.isShiftPressed)) {
                                // Consume ONLY when we actually send; a blank/sending/upload-blocked
                                // draft falls through so the multiline field handles Enter itself.
                                if (canSend) {
                                    doSend()
                                    true
                                } else {
                                    false
                                }
                            } else if (
                                handleComposerPasteKey(
                                    key = e.key,
                                    type = e.type,
                                    ctrlPressed = e.isCtrlPressed,
                                    metaPressed = e.isMetaPressed,
                                    shiftPressed = e.isShiftPressed,
                                    uploadBound = onUpload != null,
                                    // Probe clipboard ONLY after the paste chord matches — not on
                                    // every keystroke (cross-process selection can stall).
                                    likelyHasImage = clipboardLikelyHasImage,
                                    onPasteImage = { launchPasteImages() },
                                )
                            ) {
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = TextStyle(
                        color = cs.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                    cursorBrush = SolidColor(cs.primary),
                    maxLines = 8,
                    interactionSource = inputInteraction,
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "Message the agent, tag @files, or use /commands and /skills",
                                    color = cs.onSurfaceVariant.copy(alpha = 0.72f),
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    maxLines = 2,
                                )
                            }
                            inner()
                        }
                    },
                )

                // Bottom toolbar — mirrors the reference: + · model · mode on the left; mic · send
                // on the right. Model/reasoning menus stay on the desktop DropdownMenu convention.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (onUpload != null) {
                            // + opens the file picker. Paste image stays on Ctrl/Cmd+V, context
                            // menu, and Edit ▸ Paste image — not under +.
                            IconButton(
                                onClick = { stageFiles(pickFiles()) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("composer-attach"),
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Attach",
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (showModelPill) {
                            Box(Modifier.testTag("composer-model-picker")) {
                                ComposerPill(
                                    label = composerModelLabel(modelCurrent, models?.models ?: emptyList()),
                                    testTag = "composer-model-pill",
                                    onClick = { modelMenu = true },
                                    leadingIcon = {
                                        if (sessionAgent != null && hasAgentLogo(sessionAgent)) {
                                            AgentLogo(sessionAgent, size = 12.dp)
                                        } else {
                                            Icon(
                                                Icons.Filled.AutoAwesome,
                                                contentDescription = null,
                                                tint = cs.onSurfaceVariant,
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                    },
                                )
                                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                    val selectedId = composerModelSelectedId(modelCurrent)
                                    val opts = listOf(DEFAULT_MODEL_ID to "Default") +
                                        (models?.models?.map { it.id to it.displayName } ?: emptyList())
                                    opts.forEach { (id, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            trailingIcon = {
                                                if (id == selectedId) {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        null,
                                                        Modifier.size(16.dp),
                                                        tint = cs.primary,
                                                    )
                                                }
                                            },
                                            modifier = Modifier.testTag("composer-model-$id"),
                                            onClick = {
                                                modelMenu = false
                                                onPickModel(if (id == DEFAULT_MODEL_ID) "" else id)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (r != null && showReasoningPill) {
                            val reasoning = r
                            // Always low→high for menu + gauge (broker order is not trusted).
                            val effortLevels = sortEffortLevelsLowToHigh(reasoning.levels)
                            val (gaugeLevels, gaugeValue) = effortSpeedometerParams(
                                current = reasoning.current,
                                levels = reasoning.levels,
                            )
                            Box(Modifier.testTag("composer-reasoning-picker")) {
                                ComposerPill(
                                    label = composerReasoningLabel(reasoning),
                                    testTag = "composer-reasoning-pill",
                                    onClick = { reasoningMenu = true },
                                    leadingIcon = {
                                        Speedometer(
                                            levels = gaugeLevels,
                                            value = gaugeValue,
                                            tint = cs.onSurfaceVariant,
                                            activeTint = cs.primary,
                                            iconSize = 14.dp,
                                            testTag = "composer-effort-gauge",
                                        )
                                    },
                                )
                                DropdownMenu(
                                    expanded = reasoningMenu,
                                    onDismissRequest = { reasoningMenu = false },
                                ) {
                                    effortLevels.forEach { level ->
                                        DropdownMenuItem(
                                            text = { Text(composerReasoningLevelLabel(level)) },
                                            trailingIcon = {
                                                if (level.id == reasoning.current) {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        null,
                                                        Modifier.size(16.dp),
                                                        tint = cs.primary,
                                                    )
                                                }
                                            },
                                            modifier = Modifier.testTag("composer-reasoning-${level.id}"),
                                            onClick = {
                                                reasoningMenu = false
                                                onPickReasoning(level.id)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onTranscribeAudio != null) {
                            MicButton(
                                recording = dictation.recording,
                                transcribing = dictation.transcribing,
                                micUnavailable = dictation.micUnavailable,
                                onClick = {
                                    if (dictation.recording) dictation.stopMic() else dictation.startMic()
                                },
                                modifier = Modifier.testTag("composer-mic"),
                            )
                        }
                        if (agentWorking) {
                            IconButton(
                                onClick = onInterrupt,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("composer-stop"),
                            ) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(cs.error),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Stop,
                                        contentDescription = "Stop",
                                        tint = cs.onError,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        } else {
                            IconButton(
                                onClick = doSend,
                                enabled = canSend,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("composer-send"),
                            ) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (canSend) cs.primary
                                            else cs.surfaceContainerHighest,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(dictation.errorMessage) {
            if (dictation.errorMessage != null) {
                delay(4000)
                dictation.errorMessage = null
            }
        }
        dictation.errorMessage?.let { msg ->
            Text(
                msg,
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp).testTag("composer-mic-error"),
            )
        }
    }
}

/**
 * Display label for the composer's effort pill. Always the short wire id (`low` / `medium` /
 * `high` / `xhigh` / `max` / …) — never the long broker [ReasoningLevel.description] ("Greater
 * reasoning depth", …). Pure so unit tests can lock the mapping without Compose.
 */
internal fun composerReasoningLabel(reasoning: ReasoningResponse): String {
    val current = reasoning.current?.takeIf { it.isNotBlank() } ?: return "effort"
    // Prefer catalog id when present (canonical casing); else the raw current value.
    return reasoning.levels.firstOrNull { it.id == current }?.id ?: current
}

/** Short effort label for a single level — always [ReasoningLevel.id], never the long description. */
internal fun composerReasoningLevelLabel(level: ReasoningLevel): String = level.id

/** Indeterminate chip shown while clipboard raster encode runs (before a real upload chip exists). */
@Composable
private fun PastePendingChip() {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("composer-paste-pending"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            color = cs.primary,
            strokeWidth = 1.5.dp,
        )
        Text(
            text = "Pasting image…",
            color = cs.onSurface,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** One staged-attachment chip: an in-flight determinate spinner + name (+ %) while Uploading, a
 *  "· Retry" affordance (whole chip clickable, error-tinted) on Failed, and an × remove once the
 *  upload is settled (Done/Failed — an in-flight upload has no ×, matching Android). */
@Composable
private fun ComposerChip(
    att: ComposerAttachment,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val state = att.state
    val failed = state is UploadState.Failed
    val uploading = state is UploadState.Uploading

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (failed) cs.errorContainer else cs.surfaceContainerHigh)
            .then(
                if (failed) Modifier.clickable { onRetry() }.testTag("composer-chip-retry")
                else Modifier,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("composer-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state is UploadState.Uploading) {
            CircularProgressIndicator(
                progress = { state.pct },
                modifier = Modifier.size(12.dp),
                color = cs.primary,
                strokeWidth = 1.5.dp,
            )
        }
        val label = when (state) {
            is UploadState.Uploading -> "${att.name} · ${(state.pct * 100).roundToInt()}%"
            is UploadState.Failed -> "${att.name} · Retry"
            is UploadState.Done -> att.name
        }
        Text(
            text = label,
            color = if (failed) cs.onErrorContainer else cs.onSurface,
            fontSize = 12.sp,
            maxLines = 1,
        )
        if (!uploading) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = if (failed) cs.onErrorContainer else cs.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onRemove() }
                    .testTag("composer-chip-remove"),
            )
        }
    }
}

/** Compact text-style pill (optional leading icon + label + chevron) that opens a [DropdownMenu] —
 *  the desktop model / mode chips inside the composer card. Borderless + muted ink so the pills
 *  sit as chrome on the soft card rather than separate outlined chips (mock parity). */
@Composable
private fun ComposerPill(
    label: String,
    testTag: String,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .testTag(testTag)
            .clip(RoundedCornerShape(Radii.pill))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leadingIcon?.invoke()
        Text(
            text = label.take(22),
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp),
        )
    }
}
