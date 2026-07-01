package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePathRefTest {
    // ── parseFilePathRef (anchored) — ports file-path-ref.test.ts ──
    @Test fun parses_single_line() =
        assertEquals(FilePathRef("src/main.ts", 105), parseFilePathRef("src/main.ts:105"))

    @Test fun parses_range() =
        assertEquals(FilePathRef("src/utils.ts", 10, 20), parseFilePathRef("src/utils.ts:10-20"))

    @Test fun parses_bare() =
        assertEquals(FilePathRef("src/main.ts"), parseFilePathRef("src/main.ts"))

    @Test fun parses_absolute_with_line() =
        assertEquals(
            FilePathRef("/home/user/projects/app/src/foo.ts", 42),
            parseFilePathRef("/home/user/projects/app/src/foo.ts:42"),
        )

    @Test fun parses_home_with_range() =
        assertEquals(
            FilePathRef("~/projects/app/src/foo.ts", 5, 15),
            parseFilePathRef("~/projects/app/src/foo.ts:5-15"),
        )

    @Test fun rejects_non_numeric_suffix() = assertNull(parseFilePathRef("src/file.ts:abc"))
    @Test fun rejects_inverted_range() = assertNull(parseFilePathRef("src/file.ts:20-10"))

    // ── findFilePathRefs (in-text) — ports linkifyFilePaths semantics ──
    @Test fun finds_path_mid_sentence() {
        val m = findFilePathRefs("see src/main.ts:105 now")
        assertEquals(1, m.size)
        assertEquals(FilePathRef("src/main.ts", 105), m[0].ref)
        assertEquals("src/main.ts:105", m[0].display)
    }

    @Test fun inverted_range_is_not_linkified() =
        assertEquals(emptyList(), findFilePathRefs("src/foo.ts:20-10"))

    @Test fun non_numeric_suffix_links_path_only() {
        val m = findFilePathRefs("src/file.ts:abc")
        assertEquals(1, m.size)
        assertEquals(FilePathRef("src/file.ts"), m[0].ref)
        assertEquals("src/file.ts", m[0].display) // ":abc" left out of the match
    }

    @Test fun unknown_extension_skipped() =
        assertEquals(emptyList(), findFilePathRefs("assets/logo.png"))

    @Test fun bare_filename_without_dir_skipped() =
        assertEquals(emptyList(), findFilePathRefs("file.ts:42"))

    @Test fun home_path_detected() {
        val m = findFilePathRefs("open ~/p/app/a.kt please")
        assertEquals(1, m.size)
        assertEquals(FilePathRef("~/p/app/a.kt"), m[0].ref)
    }

    @Test fun multiple_in_one_run() {
        val m = findFilePathRefs("a/b.ts and c/d.kt")
        assertEquals(listOf("a/b.ts", "c/d.kt"), m.map { it.ref.path })
    }
}
