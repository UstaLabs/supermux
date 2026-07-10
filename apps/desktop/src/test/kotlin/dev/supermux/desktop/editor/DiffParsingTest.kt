package dev.supermux.desktop.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function tests for [parseDiffLines] / [diffStats] (M4g-2 Task 4) — the load-bearing unified-
 * diff parser, ported BYTE-FOR-BYTE from Android `DiffView.kt:667-726` (itself "ported 1:1 from
 * DiffView.swift/DiffView.vue"). Exercised directly (no Compose) against known unified-diff strings.
 */
class DiffParsingTest {

    @Test fun a_hunk_header_alone_produces_one_hunk_row() {
        val lines = parseDiffLines("@@ -1,3 +1,3 @@\n")
        assertEquals(listOf(DiffLine(DiffLineType.Hunk, "@@ -1,3 +1,3 @@", null)), lines)
    }

    @Test fun added_lines_get_incrementing_new_side_line_numbers_from_the_hunk_header() {
        val diff = "@@ -1,2 +5,3 @@\n+one\n+two\n+three\n"
        val lines = parseDiffLines(diff)
        assertEquals(
            listOf(
                DiffLine(DiffLineType.Hunk, "@@ -1,2 +5,3 @@", null),
                DiffLine(DiffLineType.Add, "one", 5),
                DiffLine(DiffLineType.Add, "two", 6),
                DiffLine(DiffLineType.Add, "three", 7),
            ),
            lines,
        )
    }

    @Test fun deleted_lines_have_no_new_side_line_number_and_dont_advance_the_counter() {
        val diff = "@@ -1,3 +1,2 @@\n-gone\n+kept\n"
        val lines = parseDiffLines(diff)
        assertEquals(
            listOf(
                DiffLine(DiffLineType.Hunk, "@@ -1,3 +1,2 @@", null),
                DiffLine(DiffLineType.Del, "gone", null),
                DiffLine(DiffLineType.Add, "kept", 1),
            ),
            lines,
        )
    }

    @Test fun context_lines_advance_the_new_side_counter_like_added_lines() {
        val diff = "@@ -1,3 +1,3 @@\n unchanged\n-old\n+new\n"
        val lines = parseDiffLines(diff)
        assertEquals(
            listOf(
                DiffLine(DiffLineType.Hunk, "@@ -1,3 +1,3 @@", null),
                DiffLine(DiffLineType.Ctx, "unchanged", 1),
                DiffLine(DiffLineType.Del, "old", null),
                DiffLine(DiffLineType.Add, "new", 2),
            ),
            lines,
        )
    }

    @Test fun lines_before_the_first_hunk_header_are_dropped() {
        val diff = "diff --git a/a.txt b/a.txt\nindex 111..222 100644\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n"
        val lines = parseDiffLines(diff)
        assertEquals(DiffLineType.Hunk, lines.first().type)
        assertEquals(3, lines.size) // hunk + del + add, the file-header noise is gone
    }

    @Test fun a_second_hunk_resets_the_new_side_counter_from_its_own_header() {
        val diff = "@@ -1 +1 @@\n+a\n@@ -10 +20 @@\n+b\n"
        val lines = parseDiffLines(diff)
        assertEquals(
            listOf(
                DiffLine(DiffLineType.Hunk, "@@ -1 +1 @@", null),
                DiffLine(DiffLineType.Add, "a", 1),
                DiffLine(DiffLineType.Hunk, "@@ -10 +20 @@", null),
                DiffLine(DiffLineType.Add, "b", 20),
            ),
            lines,
        )
    }

    @Test fun a_blank_diff_string_parses_to_no_lines() {
        assertEquals(emptyList(), parseDiffLines(""))
    }

    @Test fun a_hunk_header_with_no_plus_group_defaults_the_new_side_counter_to_zero() {
        val diff = "@@ malformed @@\n+x\n"
        val lines = parseDiffLines(diff)
        assertEquals(DiffLine(DiffLineType.Add, "x", 0), lines[1])
    }

    // ── diffStats ─────────────────────────────────────────────────────────────────────

    @Test fun diff_stats_counts_added_and_deleted_lines_ignoring_file_headers() {
        val diff = "--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-old1\n-old2\n+new1\n+new2\n+new3\n"
        assertEquals(3 to 2, diffStats(diff))
    }

    @Test fun diff_stats_on_a_diff_with_no_changes_is_zero_zero() {
        assertEquals(0 to 0, diffStats("@@ -1 +1 @@\n context\n"))
    }
}
