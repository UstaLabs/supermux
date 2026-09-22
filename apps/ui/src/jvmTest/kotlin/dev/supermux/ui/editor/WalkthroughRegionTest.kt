package dev.supermux.ui.editor

import dev.supermux.net.DiffFile
import dev.supermux.net.RepoDiff
import dev.supermux.net.ReviewComment
import dev.supermux.net.WalkthroughStep
import dev.supermux.ui.editor.engine.DiffRegionRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The diff-region and comment-thread projections `WalkthroughView` draws with: authored ranges
 * translated to truthful current-file decorations, the native unified-diff fallback slice, and the
 * in-editor thread payload. The state machine's own cases live in `WalkthroughStateTest`; the
 * `HostStore` + `WalkthroughSeam` half stays in desktop's `WalkthroughSeamTest` (it needs a ktor
 * client, which `:ui` deliberately does not depend on).
 */
class WalkthroughRegionTest {

    @Test fun native_region_uses_twenty_lines_of_context_and_original_line_numbers() {
        val content = (1..100).joinToString("\n") { "line $it" }
        val lines = regionLines(content, 40, 42)
        assertEquals(20, lines.first().newLine)
        assertEquals(62, lines.last().newLine)
        assertEquals(listOf(40, 41, 42), lines.filter { it.type == DiffLineType.Add }.mapNotNull { it.newLine })
    }

    @Test fun vanished_native_anchor_clamps_to_nearest_available_line() {
        val lines = regionLines("one\ntwo\nthree", 99, 101)
        assertEquals(3, lines.last().newLine)
        assertEquals(DiffLineType.Add, lines.last().type)
    }

    @Test fun walkthrough_region_uses_real_add_and_replacement_kinds() {
        val repos = listOf(RepoDiff("", listOf(DiffFile(
            path = "a.kt", status = "modified",
            diff = "@@ -1,4 +1,5 @@\n keep\n-old\n+changed\n+added\n tail",
        ))))
        val step = WalkthroughStep(
            id = "a", ord = 0, title = "One", bodyMd = "A", path = "a.kt",
            rangeStart = 2, rangeEnd = 3,
        )

        assertEquals(
            listOf(
                DiffRegionRange(2, 2, "change", deletedLines = listOf("old")),
                DiffRegionRange(3, 3, "add"),
            ),
            walkthroughRegionRanges(repos, step),
        )
        val rows = walkthroughDiffLines(repos, step, "keep\nchanged\nadded\ntail")
        assertTrue(rows.any { it.type == DiffLineType.Del && it.content == "old" })
    }

    @Test fun walkthrough_region_keeps_deleted_only_rows_for_jcef_minus_gutter() {
        val repos = listOf(RepoDiff("", listOf(DiffFile(
            path = "a.kt", status = "modified",
            diff = "@@ -1,3 +1,2 @@\n keep\n-removed\n tail",
        ))))
        val step = WalkthroughStep(
            id = "a", ord = 0, title = "One", bodyMd = "A", path = "a.kt",
            rangeStart = 2, rangeEnd = 2,
        )

        assertEquals(
            listOf(DiffRegionRange(2, 2, "delete", deletedLines = listOf("removed"))),
            walkthroughRegionRanges(repos, step),
        )
    }

    @Test fun native_region_synthesizes_a_clicked_full_file_context_line_outside_git_hunks() {
        val content = (1..80).joinToString("\n") { "line $it" }
        val repos = listOf(RepoDiff("", listOf(DiffFile(
            path = "a.kt", status = "modified", diff = "@@ -1 +1 @@\n-old\n+new",
        ))))
        val step = WalkthroughStep(
            id = "a", ord = 0, title = "One", bodyMd = "A", path = "a.kt",
            rangeStart = 1, rangeEnd = 1,
        )

        val rows = walkthroughDiffLines(repos, step, content, context = 40, focusLine = 35)

        assertTrue(rows.any { it.newLine == 35 && it.content == "line 35" })
    }

    @Test fun not_in_diff_native_region_has_no_added_rows() {
        val step = WalkthroughStep(
            id = "a", ord = 0, title = "One", bodyMd = "A", path = "a.kt",
            rangeStart = 2, rangeEnd = 2, anchorStatus = "not_in_diff",
        )
        val rows = walkthroughDiffLines(emptyList(), step, "one\ntwo\nthree", includeDiff = false)
        assertTrue(rows.all { it.type == DiffLineType.Ctx })
    }

    private fun comment(
        id: String, body: String, parentId: String? = null, line: Int = 12,
        status: String = "open", author: String = "user", path: String = "a.kt",
    ) = ReviewComment(
        id = id, parentId = parentId, repo = "r", path = path, side = "new",
        anchorLine = line, body = body, author = author, status = status,
    )

    @Test fun walkthrough_threads_group_replies_under_their_root() {
        val comments = listOf(
            comment("c1", "why?"),
            comment("c2", "because", parentId = "c1", author = "agent"),
            comment("c3", "done", line = 30, status = "resolved"),
            comment("other", "different file", path = "b.kt"),
        )
        val threads = walkthroughThreads(comments, "r", "a.kt")
        assertEquals(listOf("c1", "c3"), threads.map { it.id })
        assertEquals(listOf(12, 30), threads.map { it.line })
        assertEquals(listOf("open", "resolved"), threads.map { it.status })
        assertEquals(listOf("c1", "c2"), threads[0].comments.map { it.id })
        assertEquals("agent", threads[0].comments[1].author)
        assertEquals(1, threads[1].comments.size)
    }

    @Test fun walkthrough_threads_prefer_the_current_line_over_the_authored_anchor() {
        val threads = walkthroughThreads(listOf(comment("c1", "hi").copy(currentLine = 44)), "r", "a.kt")
        assertEquals(44, threads.single().line)
    }
}
