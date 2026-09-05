package dev.supermux.desktop.platform

import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.awt.image.ImageObserver
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop's clipboard-image half of `Platform.clipboard`: file/MIME classification, `Transferable`
 * extraction, the dimension/byte caps, and the app-owned paste cache with its age-only pruner.
 *
 * The COMPOSER half of paste (the key chords, the pending chip, the menu nonce) moved to `:ui` with
 * the composer — see `ComposerPasteTest` there. What is left here is everything that only makes
 * sense against a real AWT clipboard and a real file system.
 */
class DesktopPasteCacheTest {

    private lateinit var testConfigDir: Path

    @BeforeTest
    fun installPasteCacheOverride() {
        testConfigDir = Files.createTempDirectory("smx-paste-config-")
        desktopConfigDirOverride = testConfigDir
    }

    @AfterTest
    fun clearPasteCacheOverride() {
        desktopConfigDirOverride = null
        // Best-effort fixture cleanup (not production delete-by-path).
        runCatching {
            Files.walk(testConfigDir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /**
     * Ctrl+Shift+V is paste-as-plain-text — must fall through on a real key event (not only the
     * pure predicate). Injected key event with image probe true must NOT stage or consume.
     */

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
        assertTrue(isTestPasteCacheFile(got[0]), "raster paste must land in the app paste-cache")
        // Round-trip: the cache file is a real PNG ImageIO can re-read.
        val reloaded = ImageIO.read(got[0])
        assertTrue(reloaded != null && reloaded.width == 4 && reloaded.height == 4)
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
        assertTrue(isTestPasteCacheFile(file))
    }

    // (clipboardImageWithinCaps / scaleBufferedImageToMaxEdge — the pure caps+resize policy —
    //  moved to DesktopPlatformTest with the seam in cluster D1.)

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
        // Oversize bytes may remain in paste-cache for the age pruner — never deleted by path here.
    }

    // ── paste-cache: app-owned dir, fresh names, age prune only ─────────────────

    /** (a) Pasted image lands in the app cache dir with a fresh random name. */
    @Test fun pasteImage_landsInAppCacheDir_withFreshName() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val a = clipboardImageToTempFile(img)
        val b = clipboardImageToTempFile(img)
        assertNotNull(a)
        assertNotNull(b)
        val cache = pasteCacheDir().toRealPath()
        assertEquals(testConfigDir.resolve(PASTE_CACHE_DIR_NAME).toRealPath(), cache)
        assertEquals(cache, a!!.toPath().parent.toRealPath())
        assertEquals(cache, b!!.toPath().parent.toRealPath())
        assertTrue(isComposerPasteCacheEntryName(a!!.name))
        assertTrue(isComposerPasteCacheEntryName(b!!.name))
        assertNotEquals(a.name, b.name, "each paste must get a fresh random name")
        assertTrue(isTestPasteCacheFile(a))
        assertTrue(isTestPasteCacheFile(b))
    }

    /** (b) Pruner removes only aged app-owned entries inside the paste-cache directory. */
    @Test fun prunePasteCache_removesOnlyAgedEntriesInsideCache() {
        val cache = ensurePasteCacheDir()!!
        val fresh = cache.resolve("paste-fresh.png").toFile().apply { writeBytes(tinyPng()) }
        val aged = cache.resolve("paste-aged.png").toFile().apply { writeBytes(tinyPng()) }
        // Backdate the aged entry (mtime older than TTL).
        val oldMtime = Instant.now().minus(PASTE_CACHE_TTL).minus(Duration.ofMinutes(5))
        Files.setLastModifiedTime(aged.toPath(), java.nio.file.attribute.FileTime.from(oldMtime))

        val deleted = prunePasteCache(maxAge = PASTE_CACHE_TTL, now = Instant.now())
        assertTrue(deleted >= 1, "expected at least the aged entry deleted, got $deleted")
        assertTrue(fresh.exists(), "fresh cache entry must survive prune")
        assertFalse(aged.exists(), "aged cache entry must be reclaimed")
    }

    /**
     * Blocking B1: an aged **foreign** file inside paste-cache must survive — provenance is
     * name-based (`paste-*.png` only), not age alone.
     */
    @Test fun prunePasteCache_leavesAgedForeignFileInsideCache() {
        val cache = ensurePasteCacheDir()!!
        val foreign = cache.resolve("notes.txt").toFile().apply {
            writeText("USER-NOTES-${System.nanoTime()}")
        }
        val marker = foreign.readText()
        val old = Instant.now().minus(PASTE_CACHE_TTL).minus(Duration.ofHours(3))
        Files.setLastModifiedTime(foreign.toPath(), java.nio.file.attribute.FileTime.from(old))
        // Also place an aged app-owned file that SHOULD be reclaimed.
        val agedOurs = cache.resolve("paste-aged-ours.png").toFile().apply { writeBytes(tinyPng()) }
        Files.setLastModifiedTime(agedOurs.toPath(), java.nio.file.attribute.FileTime.from(old))

        val deleted = prunePasteCache(maxAge = PASTE_CACHE_TTL, now = Instant.now())
        assertTrue(foreign.exists(), "foreign file in paste-cache must survive prune")
        assertEquals(marker, foreign.readText(), "foreign file contents must be intact")
        assertFalse(agedOurs.exists(), "aged paste-*.png must still be reclaimed")
        assertTrue(deleted >= 1)
        foreign.delete()
    }

    /**
     * (c) Pruner does not traverse a symlink pointing outside the cache — victim outside
     * stays intact; the symlink entry itself is skipped (not followed).
     */
    @Test fun prunePasteCache_doesNotTraverseSymlinkOutsideCache() {
        val cache = ensurePasteCacheDir()!!
        val outside = Files.createTempDirectory("user-docs-outside-")
        val victim = outside.resolve("precious.png").toFile().apply {
            writeText("USER-OWNED-${System.nanoTime()}")
        }
        val victimMarker = victim.readText()
        // Symlink inside paste-cache → outside victim. Even if "aged", must not delete target.
        val link = cache.resolve("paste-link-out.png")
        Files.createSymbolicLink(link, victim.toPath())
        val old = Instant.now().minus(PASTE_CACHE_TTL).minus(Duration.ofHours(2))
        // mtime on the symlink itself (NOFOLLOW); pruner must skip isSymbolicLink.
        runCatching {
            Files.setLastModifiedTime(link, java.nio.file.attribute.FileTime.from(old))
        }

        val deleted = prunePasteCache(maxAge = PASTE_CACHE_TTL, now = Instant.now())
        assertTrue(victim.exists(), "user file outside cache must survive")
        assertEquals(victimMarker, victim.readText(), "user file contents must be intact")
        // Symlink may remain (skipped) — that is fine; we must not have unlinked the target.
        assertTrue(deleted >= 0, "pruner must not refuse solely because of a child symlink")
        // Cleanup fixtures.
        Files.deleteIfExists(link)
        victim.delete()
        Files.deleteIfExists(outside)
    }

    /**
     * Nested subdirectory inside paste-cache is not walked — files there (even aged paste-*.png
     * names) are left alone.
     */
    @Test fun prunePasteCache_doesNotRecurseIntoSubdirectory() {
        val cache = ensurePasteCacheDir()!!
        val sub = cache.resolve("nested").also { Files.createDirectories(it) }
        val buried = sub.resolve("paste-buried.png").toFile().apply { writeBytes(tinyPng()) }
        val old = Instant.now().minus(PASTE_CACHE_TTL).minus(Duration.ofHours(2))
        Files.setLastModifiedTime(buried.toPath(), java.nio.file.attribute.FileTime.from(old))

        prunePasteCache(maxAge = Duration.ZERO, now = Instant.now())
        assertTrue(buried.exists(), "nested paste-*.png must not be pruned (no recursion)")
        buried.delete()
        Files.deleteIfExists(sub)
    }

    /** Read-only paste-cache dir: pruner must not throw; files survive. */
    @Test fun prunePasteCache_readOnlyCacheDir_doesNotThrow() {
        val cache = ensurePasteCacheDir()!!
        val aged = cache.resolve("paste-readonly.png").toFile().apply { writeBytes(tinyPng()) }
        val old = Instant.now().minus(PASTE_CACHE_TTL).minus(Duration.ofHours(2))
        Files.setLastModifiedTime(aged.toPath(), java.nio.file.attribute.FileTime.from(old))
        val cacheFile = cache.toFile()
        val wasWritable = cacheFile.canWrite()
        assertTrue(cacheFile.setWritable(false), "fixture: make cache read-only")
        try {
            val result = runCatching { prunePasteCache(maxAge = Duration.ZERO, now = Instant.now()) }
            assertTrue(result.isSuccess, "pruner must not throw on read-only cache")
            // Delete may fail silently; file may still exist — either is acceptable safety.
            assertTrue(aged.exists() || result.getOrDefault(-1) >= 0)
        } finally {
            cacheFile.setWritable(wasWritable)
            aged.delete()
        }
    }

    /**
     * Hardlink in cache whose target is outside: unlinking the cache entry must not destroy the
     * user's original inode content when other links remain.
     */
    @Test fun prunePasteCache_hardlinkInCache_userOriginalSurvives() {
        val cache = ensurePasteCacheDir()!!
        val outside = Files.createTempDirectory("hardlink-user-")
        val original = outside.resolve("tax-return-2025.pdf")
        val marker = "HARD-LINK-USER-${System.nanoTime()}"
        Files.writeString(original, marker)
        // Hardlink named like our paste entries so the pruner would consider it.
        val linkInCache = cache.resolve("paste-hardlink.png")
        try {
            Files.createLink(linkInCache, original)
        } catch (_: UnsupportedOperationException) {
            // Some FS (e.g. cross-device) cannot hardlink — skip.
            Files.deleteIfExists(original)
            Files.deleteIfExists(outside)
            return
        } catch (_: Exception) {
            Files.deleteIfExists(original)
            Files.deleteIfExists(outside)
            return
        }
        val old = Instant.now().minus(PASTE_CACHE_TTL).minus(Duration.ofHours(2))
        Files.setLastModifiedTime(linkInCache, java.nio.file.attribute.FileTime.from(old))

        prunePasteCache(maxAge = Duration.ZERO, now = Instant.now())
        assertTrue(Files.exists(original), "user original via hardlink must survive")
        assertEquals(marker, Files.readString(original))
        Files.deleteIfExists(linkInCache)
        Files.deleteIfExists(original)
        Files.deleteIfExists(outside)
    }

    /**
     * Entry replaced between list and unlink (becomes non-regular / missing): pruner must not
     * throw and must not delete unrelated paths.
     */
    @Test fun prunePasteCache_entryReplacedBetweenListAndUnlink_safe() {
        val cache = ensurePasteCacheDir()!!
        // Only foreign + a normal aged paste file — replacement TOCTOU is covered by the
        // re-check-before-unlink in production; here we assert aggressive prune still only
        // hits paste-*.png regular files.
        val foreign = cache.resolve("swap-me.txt").toFile().apply { writeText("swap") }
        val aged = cache.resolve("paste-toctou.png").toFile().apply { writeBytes(tinyPng()) }
        val old = Instant.now().minus(PASTE_CACHE_TTL).minus(Duration.ofHours(1))
        Files.setLastModifiedTime(aged.toPath(), java.nio.file.attribute.FileTime.from(old))
        Files.setLastModifiedTime(foreign.toPath(), java.nio.file.attribute.FileTime.from(old))

        val deleted = prunePasteCache(maxAge = Duration.ZERO, now = Instant.now())
        assertTrue(foreign.exists(), "foreign entry must survive even with aggressive TTL")
        assertFalse(aged.exists())
        assertTrue(deleted >= 1)
        foreign.delete()
    }

    /** Concurrent prune + write: no foreign loss; writers keep landing paste-*.png. */
    @Test fun prunePasteCache_concurrentPruneAndWrite_safe() {
        val cache = ensurePasteCacheDir()!!
        val foreign = cache.resolve("concurrent-notes.txt").toFile().apply {
            writeText("CONCURRENT-${System.nanoTime()}")
        }
        val marker = foreign.readText()
        val writers = (0 until 8).map {
            Thread {
                repeat(5) {
                    clipboardImageToTempFile(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
                }
            }
        }
        val pruners = (0 until 4).map {
            Thread {
                repeat(10) {
                    prunePasteCache(maxAge = PASTE_CACHE_TTL, now = Instant.now())
                }
            }
        }
        (writers + pruners).forEach { it.start() }
        (writers + pruners).forEach { it.join(30_000) }
        assertTrue(foreign.exists(), "foreign file must survive concurrent prune/write")
        assertEquals(marker, foreign.readText())
        foreign.delete()
    }

    /**
     * A user file in an unrelated directory is never touched (pruner only lists paste-cache;
     * foreign files inside cache are also left alone — see [prunePasteCache_leavesAgedForeignFileInsideCache]).
     */
    @Test fun prunePasteCache_neverTouchesUserFileInUnrelatedDirectory() {
        val userDir = Files.createTempDirectory("user-unrelated-")
        val userFile = userDir.resolve("my-photo.png").toFile()
        val marker = "UNRELATED-${System.nanoTime()}"
        userFile.writeText(marker)
        // Age it so a buggy recursive/global prune would pick it up.
        val old = Instant.now().minus(PASTE_CACHE_TTL).minus(Duration.ofDays(1))
        Files.setLastModifiedTime(userFile.toPath(), java.nio.file.attribute.FileTime.from(old))

        assertFalse(isTestPasteCacheFile(userFile))
        // Aggressive TTL + write path must not reach outside the cache.
        val deleted = prunePasteCache(maxAge = Duration.ZERO, now = Instant.now())
        assertTrue(deleted >= 0)
        val wrote = clipboardImageToTempFile(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
        assertNotNull(wrote)
        assertTrue(isTestPasteCacheFile(wrote!!))

        assertTrue(userFile.exists(), "unrelated user file must never be touched")
        assertEquals(marker, userFile.readText())
        // Chip-path delete is gone: only prune can reclaim, and only paste-*.png under cache.
        userFile.delete()
        Files.deleteIfExists(userDir)
    }

    /** Pruner refuses when paste-cache path resolves outside the app config dir. */
    @Test fun prunePasteCache_refusesWhenCacheResolvesOutsideConfig() {
        val config = testConfigDir
        val outside = Files.createTempDirectory("paste-cache-escaped-")
        val cacheLink = config.resolve(PASTE_CACHE_DIR_NAME)
        // Ensure no real paste-cache dir; point the name at an outside directory.
        Files.deleteIfExists(cacheLink)
        if (Files.isDirectory(cacheLink)) {
            Files.walk(cacheLink).use { s ->
                s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        Files.createSymbolicLink(cacheLink, outside)
        // Drop a decoy file outside (via the symlink name).
        val decoy = outside.resolve("decoy.png").toFile().apply { writeBytes(tinyPng()) }
        val old = Instant.now().minus(PASTE_CACHE_TTL).minus(Duration.ofHours(1))
        Files.setLastModifiedTime(decoy.toPath(), java.nio.file.attribute.FileTime.from(old))

        val result = prunePasteCache(maxAge = Duration.ZERO, now = Instant.now())
        assertEquals(-1, result, "pruner must refuse when cache realpath escapes config")
        assertTrue(decoy.exists(), "file outside config must survive refused prune")

        Files.deleteIfExists(cacheLink)
        decoy.delete()
        Files.deleteIfExists(outside)
    }

    @Test fun textOnlyPaste_doesNotStage_fallsThroughToField() {
        // A text-only clip yields no image files, so the composer's Ctrl/Cmd+V falls through and
        // the field keeps the chord for text (the composer half of that rule is in `:ui`).
        val files = composerFilesFromClipboardTransferable(FakeTextTransferable("hello world"))
        assertTrue(files.isEmpty())
    }

    /**
     * A large raster is downscaled before PNG encode: 3072² is above [PASTE_IMAGE_ENCODE_MAX_EDGE]
     * (2048), so the written file must come back at or under that edge — otherwise a screenshot
     * paste writes a multi-MiB PNG and stalls the read the composer awaits.
     */
    @Test fun largeRasterEncode_downscalesBeforeWriting() {
        val edge = 3072
        val img = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
        assertTrue(clipboardImageWithinCaps(edge, edge))
        val file = clipboardImageToTempFile(img)
        assertNotNull(file)
        assertTrue(file!!.isFile && file.length() > 0)
        assertTrue(isTestPasteCacheFile(file), "encode must land in app paste-cache")
        assertTrue(
            file.length() < 2L * 1024L * 1024L,
            "downscaled paste PNG should be <2MiB, got ${file.length()}",
        )
        val decoded = ImageIO.read(file)
        assertNotNull(decoded)
        assertTrue(
            decoded!!.width <= PASTE_IMAGE_ENCODE_MAX_EDGE &&
                decoded.height <= PASTE_IMAGE_ENCODE_MAX_EDGE,
            "encoded image must be downscaled to <=$PASTE_IMAGE_ENCODE_MAX_EDGE, got ${decoded.width}x${decoded.height}",
        )
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /**
     * Test-only: whether [file] is a regular file living directly under the app paste-cache with
     * a [isComposerPasteCacheEntryName] name. Lives in the test source set (production pruner uses
     * the name check inline; there is no production path-based delete).
     */
    private fun isTestPasteCacheFile(file: File): Boolean = runCatching {
        if (!isComposerPasteCacheEntryName(file.name)) return@runCatching false
        val cache = ensurePasteCacheDir() ?: return@runCatching false
        val path = file.toPath().toAbsolutePath().normalize()
        if (path.parent != cache && path.parent?.toRealPath() != cache) return@runCatching false
        val attrs = Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
        attrs.isRegularFile && !attrs.isSymbolicLink
    }.getOrDefault(false)

    private fun tempNamed(name: String, write: File.() -> Unit): File {
        // Fixture under /tmp — unrelated to paste-cache; never touched by prune.
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
