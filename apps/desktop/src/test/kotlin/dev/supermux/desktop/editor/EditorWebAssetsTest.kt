package dev.supermux.desktop.editor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorWebAssetsTest {

    private val tmp: Path = createTempDirectory("editor-web-test")

    @AfterTest
    fun cleanup() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun extract_writes_both_files_and_returns_index_html() {
        val index = EditorWebAssets.extractTo(tmp)

        assertEquals(tmp.resolve("index.html"), index)
        assertTrue(Files.exists(tmp.resolve("index.html")), "index.html not extracted")
        assertTrue(Files.exists(tmp.resolve("cm6.js")), "cm6.js not extracted")
        // cm6.js is the real ~1.16MB bundle, not a stub.
        assertTrue(Files.size(tmp.resolve("cm6.js")) > 1_000_000, "cm6.js too small")
    }

    @Test
    fun re_extract_restores_a_file_whose_size_drifted() {
        EditorWebAssets.extractTo(tmp)
        val original = Files.size(tmp.resolve("cm6.js"))

        // Simulate a stale/corrupt bundle (different size) and re-extract.
        Files.write(tmp.resolve("cm6.js"), byteArrayOf(1, 2, 3))
        EditorWebAssets.extractTo(tmp)

        assertEquals(original, Files.size(tmp.resolve("cm6.js")), "drifted bundle was not re-extracted")
    }

    @Test
    fun re_extract_is_a_noop_when_sizes_match() {
        val index = EditorWebAssets.extractTo(tmp)
        val firstModified = Files.getLastModifiedTime(index)

        // Second call must NOT rewrite (same size) — modified time stays put.
        val index2 = EditorWebAssets.extractTo(tmp)

        assertEquals(index, index2)
        assertEquals(firstModified, Files.getLastModifiedTime(index2), "up-to-date file was needlessly rewritten")
    }
}
