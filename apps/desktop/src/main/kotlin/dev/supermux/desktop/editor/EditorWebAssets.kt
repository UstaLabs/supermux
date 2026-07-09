// M3 editor: the CodeMirror bundle rides the classpath (build.gradle.kts copies the SAME committed
// index.html + cm6.js the mobile apps ship into resources/editor/), but CEF loads pages from a real
// file:// path — not a jar entry. So on first use we extract the two resources to a stable dir on
// disk and hand the browser `file://<dir>/index.html`.
//
// Re-extraction is gated by a CRC32 CONTENT hash of the ACTUAL on-disk file (not byte size): we
// re-write whenever the extracted file's CRC differs from the packaged resource's CRC. That catches
// BOTH cases a size stamp misses — a new bundle that happens to keep the same length, AND an on-disk
// file that drifted/corrupted since extraction (its bytes changed, so its CRC changed). Hashing the
// packaged CRC of what we're about to write, not tracking a sidecar stamp, keeps the check
// self-verifying: it trusts the file's own current bytes, never a marker that could go stale. Writes
// are ATOMIC (temp file + ATOMIC_MOVE) so a crash mid-write can never leave CEF pointing at a
// half-written, unparseable bundle.
package dev.supermux.desktop.editor

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import kotlin.io.path.exists

/** Extracts the committed editor web bundle from the classpath onto disk for CEF `file://` loads. */
object EditorWebAssets {

    /** Classpath resources shipped by the `processResources` copy in build.gradle.kts. */
    private val RESOURCES = listOf("editor/index.html", "editor/cm6.js")

    /**
     * Ensure [dir] holds the current bundle; returns the `index.html` path to hand CEF. Each file is
     * (re)written only when missing or its on-disk CRC32 differs from the packaged resource's — so a
     * normal run after the first does zero writes, and any content change (even same-size) or on-disk
     * drift triggers a re-extract.
     */
    fun extractTo(dir: Path): Path {
        Files.createDirectories(dir)
        for (res in RESOURCES) {
            val name = res.substringAfterLast('/')
            extractResource(res, dir.resolve(name))
        }
        return dir.resolve("index.html")
    }

    private fun extractResource(resource: String, dest: Path) {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("missing classpath resource: $resource (processResources copy did not run?)")
        // Content stamp: compare the file's ACTUAL current CRC (cheap; the bundle is ~1.16MB) to the
        // packaged CRC. Matches → nothing changed on disk or in the jar → no write.
        val upToDate = dest.exists() && crc32(Files.readAllBytes(dest)) == crc32(bytes)
        if (upToDate) return

        Files.createDirectories(dest.parent)
        atomicWrite(dest, bytes)
    }

    private fun atomicWrite(dest: Path, bytes: ByteArray) {
        val tmp = Files.createTempFile(dest.parent, "${dest.fileName}.", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Rare (some exotic FS) — fall back to a plain replace. Still far better than an
                // in-place truncating write, which is the only thing that could half-write dest.
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun crc32(bytes: ByteArray): String {
        val crc = CRC32().apply { update(bytes) }
        return crc.value.toString(16)
    }
}
