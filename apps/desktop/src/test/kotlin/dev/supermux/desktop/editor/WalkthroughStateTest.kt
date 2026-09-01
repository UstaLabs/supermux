package dev.supermux.desktop.editor

import dev.supermux.net.ReviewComment
import dev.supermux.net.DiffFile
import dev.supermux.net.RepoDiff
import dev.supermux.net.Walkthrough
import dev.supermux.net.WalkthroughStep
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalkthroughStateTest {
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

    private fun walkthrough(revision: Int = 1, line: Int = 7) = Walkthrough(
        id = "w1",
        sessionId = "s1",
        title = "Tour",
        revision = revision,
        steps = listOf(
            WalkthroughStep(id = "a", ord = 0, title = "One", bodyMd = "A", path = "a.kt", anchorLine = line),
            WalkthroughStep(id = "b", ord = 1, title = "Two", bodyMd = "B"),
        ),
    )

    @Test fun load_and_navigation_are_clamped() = runTest {
        val state = WalkthroughState("s1")
        state.load { walkthrough() }

        assertEquals("a", state.currentStep?.id)
        state.previous()
        assertEquals(0, state.stepIndex)
        state.next()
        assertEquals("b", state.currentStep?.id)
        state.next()
        assertEquals(1, state.stepIndex)
        state.goTo(-8)
        assertEquals(0, state.stepIndex)
    }

    @Test fun revision_update_marks_changed_anchor_and_preserves_anchor_draft_and_scroll() {
        val state = WalkthroughState("s1")
        state.applyWalkthrough(walkthrough())
        val anchor = state.currentAnchor ?: error("anchor")
        state.setDraft(anchor, "unfinished")
        state.setScroll(anchor, 91)

        state.applyWalkthrough(walkthrough(revision = 2, line = 9))

        assertEquals(0, state.updatedStepIndex)
        assertEquals("unfinished", state.draft(anchor))
        assertEquals(91, state.scroll(anchor))
        assertEquals("unfinished", state.currentAnchor?.let(state::draft))
        assertEquals(91, state.currentAnchor?.let(state::scroll))
    }

    @Test fun same_revision_reanchor_marks_the_step_updated() {
        val state = WalkthroughState("s1")
        state.applyWalkthrough(walkthrough(revision = 4, line = 7))

        state.applyWalkthrough(walkthrough(revision = 4, line = 11))

        assertEquals(0, state.updatedStepIndex)
        assertEquals(11, state.currentStep?.anchorLine)
    }

    @Test fun older_walkthrough_frame_is_ignored() {
        val state = WalkthroughState("s1")
        state.applyWalkthrough(walkthrough(revision = 5, line = 12))

        state.applyWalkthrough(walkthrough(revision = 4, line = 7))

        assertEquals(5, state.walkthrough?.revision)
        assertEquals(12, state.currentStep?.anchorLine)
    }

    @Test fun stale_get_cannot_overwrite_a_frame_that_arrived_while_loading() = runTest {
        val state = WalkthroughState("s1")

        state.load {
            state.applyWalkthrough(walkthrough(revision = 3, line = 14))
            walkthrough(revision = 1, line = 7)
        }

        assertEquals(3, state.walkthrough?.revision)
        assertEquals(14, state.currentStep?.anchorLine)
    }

    @Test fun live_agent_reply_increments_local_unread_only_while_closed() {
        val state = WalkthroughState("s1")
        state.applyWalkthrough(walkthrough())
        val reply = ReviewComment(
            id = "r1", parentId = "c1", repo = "", path = "a.kt", side = "RIGHT",
            anchorLine = 7, body = "Done", author = "agent", status = "open",
        )

        state.applyComment(reply)
        assertEquals(1, state.unreadReplies)
        state.open(stepId = "a")
        assertEquals(0, state.unreadReplies)
        assertTrue(state.isOpen)
        state.applyComment(reply.copy(id = "r2"))
        assertEquals(0, state.unreadReplies)
        state.close()
        assertFalse(state.isOpen)
    }

    @Test fun explicit_open_target_does_not_override_later_navigation_on_revision() {
        val state = WalkthroughState("s1")
        state.applyWalkthrough(walkthrough())
        state.open(stepId = "a")
        state.next()

        state.applyWalkthrough(walkthrough(revision = 2))

        assertEquals("b", state.currentStep?.id)
    }
}
