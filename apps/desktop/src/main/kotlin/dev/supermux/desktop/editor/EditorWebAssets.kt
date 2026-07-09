// M3 editor: the CodeMirror bundle rides the classpath (build.gradle.kts copies the SAME committed
// index.html + cm6.js the mobile apps ship into resources/editor/), but CEF loads pages from a real
// file:// path — not a jar entry. So on first use we extract the two resources to a stable dir on
// disk and hand the browser `file://<dir>/index.html`.
//
// Re-extraction is version-stamped by byte size: cm6.js is a single built artifact, so a size change
// == a new bundle → re-write. Cheap, no hashing, and avoids stale bytes after an app update.
package dev.supermux.desktop.editor

import java.nio.file.Files
import java.nio.file.Path

/** Extracts the committed editor web bundle from the classpath onto disk for CEF `file://` loads. */
object EditorWebAssets {

    /** Classpath resources shipped by the `processResources` copy in build.gradle.kts. */
    private val RESOURCES = listOf("editor/index.html", "editor/cm6.js")

    /**
     * Ensure [dir] holds the current bundle; returns the `index.html` path to hand CEF. Each file is
     * (re)written only when missing or a different size than the packaged resource — so a normal run
     * after the first does zero writes.
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
        val upToDate = Files.exists(dest) && Files.size(dest) == bytes.size.toLong()
        if (!upToDate) {
            Files.createDirectories(dest.parent)
            Files.write(dest, bytes)
        }
    }
}
