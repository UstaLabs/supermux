package dev.supermux.ui.editor

import dev.supermux.net.ReviewComment
import dev.supermux.net.Walkthrough
import dev.supermux.net.WalkthroughStep
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalkthroughStateTest {

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

    @Test fun composer_state_persists_a_draft_per_anchor_and_clears_on_close() {
        val state = WalkthroughState("s1")
        val a = CommentAnchor("r", "a.kt", "new", 12)
        val b = CommentAnchor("r", "a.kt", "new", 30)
        // onComposerState(line > 0, text) → store the draft for that line.
        state.setDraft(a, "half a thou")
        state.setDraft(b, "elsewhere")
        assertEquals("half a thou", state.draft(a))
        assertEquals("elsewhere", state.draft(b))
        // onComposerState(line == 0) → the composer closed; its draft goes away, the other survives.
        state.clearDraft(a)
        assertEquals("", state.draft(a))
        assertEquals("elsewhere", state.draft(b))
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
