package dev.supermux.ui.editor

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.plugins.diff.DiffPage
import dev.supermux.editor.plugins.diff.Review
import dev.supermux.editor.plugins.diff.inlineDiff
import dev.supermux.editor.plugins.diff.review
import dev.supermux.net.AddCommentBody
import dev.supermux.net.ReviewComment
import dev.supermux.net.Walkthrough
import dev.supermux.net.WalkthroughStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The diff plugin's DiffHost on the review endpoints (M5 A5): 0-based plugin lines vs 1-based wire
 * lines, drafts, replies, resolve, paging; the thread mapping and the walkthrough slice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewHostsTest {
    private val text = "zero\none\ntwo\nthree\nfour\n"

    private fun comment(id: String, line: Int, parent: String? = null, status: String = "open", body: String = "c$id", author: String = "user") =
        ReviewComment(id = id, parentId = parent, repo = "r", path = "f.kt", side = "RIGHT", anchorLine = line, anchorContext = "ctx", body = body, author = author, status = status)

    private fun walkthrough(): WalkthroughState = WalkthroughState("s1").apply {
        applyWalkthrough(Walkthrough(id = "w", sessionId = "s1", title = "T", revision = 1, steps = listOf(
            WalkthroughStep(id = "a", ord = 0, title = "A", bodyMd = "", repo = "r", path = "f.kt", rangeStart = 2, rangeEnd = 3),
            WalkthroughStep(id = "b", ord = 1, title = "B", bodyMd = ""),
        )))
    }

    @Test fun a_comment_is_posted_on_the_wire_line_with_its_line_text_and_its_draft_goes() = runTest {
        val state = walkthrough()
        val posted = mutableListOf<AddCommentBody>()
        val host = FileReviewHost(
            repo = "r", path = "f.kt", scope = this,
            lineText = { lineOf(text, it) },
            drafts = WalkthroughDrafts(state, "r", "f.kt", "RIGHT"),
            comments = { state.comments },
            submit = { line, context, body ->
                posted += AddCommentBody(repo = "r", path = "f.kt", side = "RIGHT", anchorLine = line, anchorContext = context, body = body, deliver = "instant")
                state.applyComment(comment("n1", line, body = body))
                true
            },
            reply = { _, _ -> true },
            resolve = { true },
        )
        host.onComposerDraft(2, "half typed")
        assertEquals("half typed", state.draft(CommentAnchor("r", "f.kt", "RIGHT", 3)))

        host.onCommentSubmit(2, "  looks off  ")
        runCurrent()
        assertEquals(1, posted.size)
        assertEquals(3, posted.single().anchorLine)            // plugin line 2 = wire line 3
        assertEquals("two", posted.single().anchorContext)
        assertEquals("looks off", posted.single().body)
        assertEquals("", state.draft(CommentAnchor("r", "f.kt", "RIGHT", 3)))
        assertEquals(listOf("n1"), state.comments.map { it.id })
    }

    @Test fun a_failed_post_keeps_the_draft() = runTest {
        val state = walkthrough()
        val host = FileReviewHost("r", "f.kt", this, { lineOf(text, it) }, WalkthroughDrafts(state, "r", "f.kt", "RIGHT"), { state.comments },
            submit = { _, _, _ -> false }, reply = { _, _ -> true }, resolve = { true })
        host.onCommentSubmit(0, "keep me")
        runCurrent()
        assertEquals("keep me", state.draft(CommentAnchor("r", "f.kt", "RIGHT", 1)))
    }

    @Test fun reopening_a_composer_hands_back_its_draft_and_cancel_drops_it() {
        val drafts = MapReviewDrafts()
        val host = FileReviewHost("r", "f.kt", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), { lineOf(text, it) }, drafts, { emptyList() },
            submit = { _, _, _ -> true }, reply = { _, _ -> true }, resolve = { true })
        val view = EditorView(EditorState.create(text, extensions = extensionOf(inlineDiff(text, host = host), review(host))))
        host.target = view

        host.onComposerDraft(3, "kept")
        host.onComposerOpen(3)          // the gutter tapped again on that line
        assertEquals("kept", Review.composer(view.state)?.draft)
        assertEquals(3, Review.composer(view.state)?.line)

        host.onComposerClosed()
        assertEquals("", drafts.get(4))
    }

    @Test fun a_reply_goes_to_its_root_and_resolve_marks_the_thread() = runTest {
        val state = walkthrough()
        state.seedComments(listOf(comment("t1", 2)))
        val replied = mutableListOf<Pair<String, String>>()
        val resolved = mutableListOf<String>()
        val host = FileReviewHost("r", "f.kt", this, { lineOf(text, it) }, WalkthroughDrafts(state, "r", "f.kt", "RIGHT"), { state.comments },
            submit = { _, _, _ -> true },
            reply = { root, body -> replied += root.id to body; true },
            resolve = { id -> resolved += id; true })
        host.onReply("t1", "ok!")
        host.onReply("missing", "dropped")
        host.onResolve("t1")
        runCurrent()
        assertEquals(listOf("t1" to "ok!"), replied)
        assertEquals(listOf("t1"), resolved)
    }

    @Test fun paging_reaches_the_step_shell() {
        val state = walkthrough()
        val host = FileReviewHost("r", "f.kt", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), { "" }, MapReviewDrafts(), { emptyList() },
            submit = { _, _, _ -> true }, reply = { _, _ -> true }, resolve = { true },
            page = { if (it == DiffPage.NEXT) state.next() else state.previous() })
        host.onDiffPage(DiffPage.NEXT)
        assertEquals(1, state.stepIndex)
        host.onDiffPage(DiffPage.PREVIOUS)
        assertEquals(0, state.stepIndex)
    }

    @Test fun threads_are_zero_based_on_the_current_line_with_their_replies() {
        val comments = listOf(
            comment("t1", 5).copy(currentLine = 7),
            comment("r1", 5, parent = "t1", author = "agent", body = "answer"),
            comment("t2", 1, status = "resolved"),
            comment("x", 3).copy(path = "other.kt"),
        )
        val threads = reviewThreads(comments, "r", "f.kt")
        assertEquals(listOf("t1", "t2"), threads.map { it.id })
        assertEquals(6, threads[0].line)
        assertEquals(listOf("user", "agent"), threads[0].comments.map { it.author })
        assertTrue(threads[1].resolved)
        assertEquals(0, threads[1].line)
    }

    @Test fun a_step_is_its_range_in_zero_based_lines_with_twenty_lines_of_context() {
        val step = WalkthroughStep(id = "a", ord = 0, title = "A", bodyMd = "", path = "f.kt", rangeStart = 10, rangeEnd = 14)
        val c = walkthroughDiffConfig(step, lineCount = 100)
        assertEquals(9..13, c.range)
        assertEquals(20, c.context)
        assertEquals(false, c.editable)
        assertEquals(false, c.plain)
        assertTrue(walkthroughDiffConfig(step.copy(anchorStatus = "not_in_diff"), 100).plain)
        // A range past the end of a shorter file is clamped.
        assertEquals(4..4, walkthroughDiffConfig(step, lineCount = 5).range)
    }

    private val patch = """
        diff --git a/f.kt b/f.kt
        --- a/f.kt
        +++ b/f.kt
        @@ -1,3 +1,3 @@
         zero
        -uno
        +one
         two
        @@ -10,2 +10,3 @@ fun later()
         nine
        +ten
         eleven
    """.trimIndent() + "\n"

    @Test fun the_base_is_the_patch_reversed_and_a_misfit_patch_shows_no_changes() {
        assertEquals("zero\nuno\ntwo\nthree\nfour\n", diffBase(text, patch.substringBefore("@@ -10")))
        assertEquals(text, diffBase(text, null))
        assertEquals(text, diffBase(text, "@@ -1,2 +1,2 @@\n-nothing\n-like\n+this\n+file\n"))
    }

    @Test fun a_comment_carries_the_hunk_header_of_its_line() {
        assertEquals("@@ -1,3 +1,3 @@", hunkHeaderFor(patch, 2))
        assertEquals("@@ -10,2 +10,3 @@ fun later()", hunkHeaderFor(patch, 11))
        // A line expanded into between the hunks: the nearest hunk above.
        assertEquals("@@ -1,3 +1,3 @@", hunkHeaderFor(patch, 6))
        assertEquals("", hunkHeaderFor("", 3))
    }

    @Test fun line_of_text() {
        assertEquals("zero", lineOf(text, 0))
        assertEquals("four", lineOf(text, 4))
        assertEquals("", lineOf(text, 5))
        assertEquals("", lineOf(text, 99))
        assertNull(null)
    }
}
