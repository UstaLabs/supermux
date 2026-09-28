package dev.supermux.editor.plugins.diff

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A host that writes down what the review told it. */
internal class RecordingHost : DiffHost {
    val log = ArrayList<String>()
    override fun onCommentSubmit(line: Int, text: String) { log += "submit $line $text" }
    override fun onReply(threadId: String, text: String) { log += "reply $threadId $text" }
    override fun onResolve(threadId: String) { log += "resolve $threadId" }
    override fun onComposerOpen(line: Int) { log += "open $line" }
    override fun onComposerDraft(line: Int, text: String) { log += "draft $line $text" }
    override fun onComposerClosed() { log += "closed" }
    override fun onRevert(hunk: DiffHunk) { log += "revert ${hunk.bFrom}" }
}

class ReviewTest {
    private val base = (1..100).joinToString("\n") { "line $it" }
    private val working = base.replace("line 50\n", "line 50 changed\n")
    private val t1 = ReviewThread("t1", 49, comments = listOf(ReviewComment("c1", "user", "why?"), ReviewComment("c2", "agent", "because")))
    private val t2 = ReviewThread("t2", 10, resolved = true, comments = listOf(ReviewComment("c3", "user", "done")))

    private fun view(host: DiffHost = RecordingHost(), config: ReviewConfig = ReviewConfig(), diff: DiffConfig = DiffConfig()) =
        EditorView(EditorState.create(working, extensions = extensionOf(inlineDiff(base, diff), review(host, config))))

    private fun EditorView.blocks() = state.facet(decorationsFacet).flatMap { it.toList() }.filter { (it.value as? Decoration.BlockWidget)?.key?.type?.startsWith("review:") == true }
    private fun EditorView.lineOf(pos: Int) = state.doc.lineIndexAt(pos)
    private fun EditorView.gutter(column: String, line: Int) = state.facet(gutterClickFacet).any { it.click(this, column, line, null) }

    @Test fun threadsAreBlockWidgetsUnderTheirLinesWithABubble() {
        val v = view()
        Review.setThreads(v, listOf(t1, t2))
        val blocks = v.blocks()
        assertEquals(listOf(10 to "t2", 49 to "t1"), blocks.map { v.lineOf(it.from) to (it.value as Decoration.BlockWidget).key.id })
        assertTrue(blocks.all { !(it.value as Decoration.BlockWidget).above })
        val resolved = blocks.first { (it.value as Decoration.BlockWidget).key.id == "t2" }.value as Decoration.BlockWidget
        assertTrue(resolved.estimatedHeightLines < 2f, "a resolved thread is one line")
        val markers = v.state.facet(gutterMarkersFacet).flatMap { it.toList() }.filter { it.value.column == Review.COLUMN }
        assertEquals(listOf(0 to "comment-add", 10 to "comment", 49 to "comment"), markers.map { v.lineOf(it.from) to it.value.kind })
        assertEquals("review thread: 2 comments", markers.last().value.tooltip)
    }

    @Test fun threadsMapThroughEditsAndAnUpdateKeepsThemWhereTheyWent() {
        val v = view()
        Review.setThreads(v, listOf(t1))
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "a\nb\n"))))
        assertEquals(51, Review.threads(v.state).single().second)
        // Typing at the thread's line start keeps it on its line.
        val at = v.state.doc.lineStart(51)
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "x"))))
        assertEquals(51, Review.threads(v.state).single().second)
        // The host pushes the thread again (a new reply) at the line it knew: it stays where the edits took it.
        Review.setThreads(v, listOf(t1.copy(comments = t1.comments + ReviewComment("c9", "user", "ok"))))
        assertEquals(51, Review.threads(v.state).single().second)
        assertEquals(3, Review.threads(v.state).single().first.comments.size)
        // The host moves it: it goes there.
        Review.setThreads(v, listOf(t1.copy(line = 5)))
        assertEquals(5, Review.threads(v.state).single().second)
    }

    @Test fun theComposerOpensFromTheGutterAndReportsItsDraftAndSubmit() {
        val host = RecordingHost()
        val v = view(host)
        assertTrue(v.gutter(Review.COLUMN, 20))
        assertEquals(ReviewComposer(20, ""), Review.composer(v.state))
        assertTrue(v.blocks().any { (it.value as Decoration.BlockWidget).key.type == Review.COMPOSER })
        Review.typed(v, "hel")
        Review.typed(v, "hello")
        assertEquals(ReviewComposer(20, "hello"), Review.composer(v.state))
        // The host hears the draft when the widget reports it (debounced there), not per keystroke.
        Review.flushDraft(v)
        Review.flushDraft(v) // heard once
        // An edit above moves the open composer with its line.
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "new\n"))))
        assertTrue(Review.submit(v, "  hello  "))
        assertNull(Review.composer(v.state))
        assertEquals(listOf("open 20", "draft 20 hello", "submit 21 hello"), host.log)
    }

    @Test fun cancelClosesAndTellsTheHost() {
        val host = RecordingHost()
        // (Unfolded: a programmatic caret inside a folded run is clamped out of it.)
        val v = view(host, diff = DiffConfig(collapseUnchanged = false))
        Review.comment.run(v.also { it.dispatch(TransactionSpec(selection = EditorSelection.cursor(v.state.doc.lineStart(7)))) })
        assertEquals(7, Review.composer(v.state)?.line)
        assertFalse(Review.submit(v, "   "), "an empty comment is not sent")
        Review.cancel(v)
        assertNull(Review.composer(v.state))
        assertEquals(listOf("open 7", "closed"), host.log)
    }

    @Test fun theHostsComposerFollowsTodaysRule() {
        val v = view()
        Review.setComposer(v, ReviewComposer(30, "saved draft"))
        assertEquals(ReviewComposer(30, "saved draft"), Review.composer(v.state))
        Review.typed(v, "saved draft, more")
        // The same line again (a threads-only push carries the old draft): what is typed stays.
        Review.setComposer(v, ReviewComposer(30, "saved draft"))
        assertEquals("saved draft, more", Review.composer(v.state)?.draft)
        // Another line: it moves there with that line's draft.
        Review.setComposer(v, ReviewComposer(40, "other"))
        assertEquals(ReviewComposer(40, "other"), Review.composer(v.state))
        // Opened from the gutter (empty), then the host hands back the draft it kept for the line: taken.
        v.gutter(Review.COLUMN, 60)
        Review.setComposer(v, ReviewComposer(60, "kept"))
        assertEquals(ReviewComposer(60, "kept"), Review.composer(v.state))
        Review.setComposer(v, null)
        assertNull(Review.composer(v.state))
    }

    @Test fun replyAndResolveGoToTheHost() {
        val host = RecordingHost()
        val v = view(host)
        Review.setThreads(v, listOf(t1))
        assertTrue(Review.reply(v, "t1", " thanks "))
        assertFalse(Review.reply(v, "t1", " "))
        Review.resolve(v, "t1")
        assertEquals(listOf("reply t1 thanks", "resolve t1"), host.log)
    }

    @Test fun resolvedThreadsExpandPerView() {
        val v = view()
        Review.setThreads(v, listOf(t2))
        Review.toggleExpanded(v, "t2")
        assertEquals(setOf("t2"), Review.state(v.state)!!.expanded)
        val est = (v.blocks().single().value as Decoration.BlockWidget).estimatedHeightLines
        assertTrue(est > 2f, "expanded: the comments")
        Review.setThreads(v, listOf(t2.copy(comments = t2.comments + ReviewComment("c4", "agent", "yes"))))
        assertEquals(setOf("t2"), Review.state(v.state)!!.expanded, "an update keeps it expanded")
        Review.setThreads(v, emptyList())
        assertEquals(emptySet(), Review.state(v.state)!!.expanded, "a thread that went is forgotten")
    }

    @Test fun aThreadUpdateNeverResetsExpandedContext() {
        val v = view(diff = DiffConfig(context = 3))
        Diff.expand(v, Diff.model(v.state)!!.collapsed[0], Expand.ALL)
        val before = Diff.model(v.state)
        Review.setThreads(v, listOf(t1, t2))
        Review.setComposer(v, ReviewComposer(49, "x"))
        assertTrue(Diff.model(v.state) === before, "the diff did not change")
    }

    @Test fun noCommentingWhenTheHostCannotPost() {
        val v = view(config = ReviewConfig(canComment = false))
        assertFalse(v.gutter(Review.COLUMN, 3))
        assertNull(Review.composer(v.state))
        assertTrue(v.state.facet(gutterMarkersFacet).flatMap { it.toList() }.none { it.value.kind == "comment-add" })
    }

    // ------------------------------------------------------------------ the re-review: no lost typing --

    @Test fun pagingFlushesThePendingDraftFirst() {
        val log = ArrayList<String>()
        val host = object : DiffHost {
            override fun onComposerDraft(line: Int, text: String) { log += "draft $line $text" }
            override fun onDiffPage(direction: DiffPage) { log += "page $direction" }
        }
        val v = view(host)
        Review.openComposer(v, 20)
        Review.typed(v, "half a senten") // inside the debounce: the host has not heard it
        assertTrue(Diff.pageNext.run(v))
        assertEquals(listOf("draft 20 half a senten", "page NEXT"), log)
    }

    @Test fun anotherComposerFlushesTheOpenOnesDraftFirst() {
        val host = RecordingHost()
        val v = view(host)
        Review.openComposer(v, 20)
        Review.typed(v, "first")
        Review.openComposer(v, 30)
        Review.typed(v, "second")
        Review.setComposer(v, ReviewComposer(40, "kept for 40"))
        assertEquals(listOf("open 20", "draft 20 first", "open 30", "draft 30 second"), host.log)
        assertEquals(ReviewComposer(40, "kept for 40"), Review.composer(v.state))
        Review.flushDraft(v)
        assertEquals(4, host.log.size, "the host's own draft is not sent back to it")
    }

    @Test fun aComposerLineStaysOpenInAStepAndIsNotCountedAsAThread() {
        val v = view(diff = DiffConfig(range = 60..62, context = 3))
        Review.setThreads(v, listOf(t1.copy(line = 80)))
        Review.setComposer(v, ReviewComposer(90, "a draft", focus = false))
        val runs = Diff.collapsed(v.state)
        assertTrue(runs.none { 90 in it.bFrom until it.bTo }, "the composer's line is shown: $runs")
        assertEquals(1, runs.sumOf { it.comments }, "only the thread is counted")
    }
}
