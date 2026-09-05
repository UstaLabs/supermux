// Desktop clipboard-image paste + its app-owned paste cache — the guts of
// `DesktopPlatform.clipboard` (cluster D1). Moved here verbatim from `chat/DesktopComposer.kt`:
// the shared composer reads `Platform.clipboard`, so the AWT `Transferable` handling, the
// dimension/byte caps and the paste-cache pruner all belong on the desktop side of the seam. The
// composer keeps calling them directly until it moves into `:ui` (cluster D3).
package dev.supermux.desktop.platform

import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import dev.supermux.desktop.auth.DesktopTokenStore

/** Best-effort MIME for a path — delegates to the platform's single `probeMime`, so the composer,
 *  the launcher and `DesktopPlatform.pickFiles` all guess identically. */
internal fun composerMime(path: Path): String = probeMime(path.toFile())

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
// ui-state.json / jcef-cache), NOT under shared /tmp. Each write uses a fresh UUID name; names
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
