package dev.supermux.editor.plugins.diff

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.extensionOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review threads and the composer in composed editors: rendering, collapse, typing, submit, focus, alignment. */
@OptIn(ExperimentalTestApi::class)
class ReviewUiTest {
    private val base = (1..80).joinToString("\n") { "line $it" }
    private val working = base.replace("line 12\n", "line 12 changed\n")
    private val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft
    private val open = ReviewThread("t1", 11, comments = listOf(ReviewComment("c1", "user", "Is this right?"), ReviewComment("c2", "agent", "Yes, see the spec.")))
    private val resolved = ReviewThread("t2", 5, resolved = true, comments = listOf(ReviewComment("c3", "user", "Fixed now"), ReviewComment("c4", "agent", "Thanks")))

    private fun ComposeUiTest.show(host: DiffHost, config: DiffConfig = DiffConfig(collapseUnchanged = false)): EditorView {
        val view = EditorView(EditorState.create(working, extensions = extensionOf(inlineDiff(base, config), review(host))))
        Review.setThreads(view, listOf(open, resolved))
        setContent { Box(Modifier.size(800.dp, 900.dp)) { InlineDiffEditor(view, Modifier.fillMaxSize()) } }
        waitForIdle()
        return view
    }

    @Test fun threadsRenderAndAResolvedOneExpandsOnATap() = runComposeUiTest {
        val v = show(RecordingHost())
        onNodeWithText("Is this right?").assertExists()
        onNodeWithText("Yes, see the spec.").assertExists()
        onNodeWithText("Agent").assertExists()
        onNodeWithText("✓ resolved · 1 reply").assertExists()
        onNodeWithText("Fixed now").assertDoesNotExist()
        onNodeWithText("✓ resolved · 1 reply").performClick()
        waitForIdle()
        onNodeWithText("Fixed now").assertExists()
        onNodeWithText("✓ resolved · hide").performClick()
        waitForIdle()
        onNodeWithText("Fixed now").assertDoesNotExist()
        assertTrue(!v.focused, "a tap on a thread never takes the editor's focus")
    }

    /** A host whose replies succeed or fail ([posts]); a posted one is cleared through Review.clearReply. */
    private class ReplyHost(var posts: Boolean) : DiffHost {
        val log = mutableListOf<String>()
        var view: EditorView? = null
        override fun onReply(threadId: String, text: String) {
            log += "reply $threadId $text"
            if (posts) view?.let { Review.clearReply(it, threadId) }
        }
        override fun onResolve(threadId: String) { log += "resolve $threadId" }
    }

    @Test fun replyAndResolveButtons() = runComposeUiTest {
        val host = ReplyHost(posts = true)
        host.view = show(host)
        onNodeWithTag(ReviewTags.REPLY_FIELD).performClick()
        onNodeWithTag(ReviewTags.REPLY_FIELD).performTextInput("Thanks!")
        onNodeWithTag(ReviewTags.REPLY).performClick()
        waitForIdle()
        onNodeWithTag(ReviewTags.RESOLVE).performClick()
        waitForIdle()
        assertEquals(listOf("reply t1 Thanks!", "resolve t1"), host.log)
        assertEquals("", onNodeWithTag(ReviewTags.REPLY_FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text, "sent: cleared")
    }

    /** Re-review: a reply the host could not post keeps what was typed. */
    @Test fun aFailedReplyKeepsItsText() = runComposeUiTest {
        val host = ReplyHost(posts = false)
        val v = show(host)
        host.view = v
        onNodeWithTag(ReviewTags.REPLY_FIELD).performClick()
        onNodeWithTag(ReviewTags.REPLY_FIELD).performTextInput("Thanks!")
        onNodeWithTag(ReviewTags.REPLY).performClick()
        waitForIdle()
        assertEquals(listOf("reply t1 Thanks!"), host.log)
        assertEquals("Thanks!", onNodeWithTag(ReviewTags.REPLY_FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text, "failed: kept")
        assertEquals("Thanks!", Review.replyDraft(v.state, "t1"))
    }

    @Test fun theComposerTakesTheFocusSendsItsDraftAndGivesTheFocusBack() = runComposeUiTest {
        val host = RecordingHost()
        val v = show(host)
        v.focus()
        waitForIdle()
        assertTrue(Review.openComposer(v, 30))
        waitForIdle()
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).assertIsFocused()
        assertTrue(!v.focused)
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performTextInput("Looks good")
        waitForIdle()
        assertEquals("Looks good", Review.composer(v.state)?.draft)
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performKeyInput { withKeyDown(mod) { pressKey(Key.Enter) } }
        waitForIdle()
        assertNull(Review.composer(v.state))
        assertEquals(listOf("open 30", "draft 30 Looks good", "submit 30 Looks good"), host.log)
        assertTrue(v.focused, "the focus is back in the editor")
    }

    @Test fun escapeCancelsAndARestoredDraftShows() = runComposeUiTest {
        val host = RecordingHost()
        val v = show(host)
        Review.setComposer(v, ReviewComposer(20, "a kept draft"))
        waitForIdle()
        assertEquals("a kept draft", onNodeWithTag(ReviewTags.COMPOSER_FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(Review.composer(v.state))
        assertEquals(listOf("closed"), host.log)
        assertTrue(v.focused)
    }

    @Test fun theSubmitButtonOfAComposerOpenedBelowTheScreen() = runComposeUiTest {
        val host = RecordingHost()
        val v = show(host)
        // Line 60 is far below the screen: opening scrolls the composer into view.
        Review.openComposer(v, 60)
        waitForIdle()
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performTextInput("from the button")
        onNodeWithTag(ReviewTags.SUBMIT).performClick()
        waitForIdle()
        assertEquals("submit 60 from the button", host.log.last())
    }

    @Test fun aThreadOnTheWorkingSideKeepsBothSidesAligned() = runComposeUiTest {
        val a = EditorView(EditorState.create(base))
        val b = EditorView(EditorState.create(working, extensions = review(RecordingHost())))
        val pair = DiffPair(a, b, DiffConfig(collapseUnchanged = false))
        Review.setThreads(b, listOf(open))
        setContent { Box(Modifier.size(900.dp, 700.dp)) { SideBySideDiff(pair, Modifier.fillMaxSize()) } }
        waitForIdle()
        val h = onRoot().fetchSemanticsNode().size.height.toFloat()
        var compared = 0
        for (line in 0 until 40) {
            if (line == 11) continue // the changed line
            val ya = a.coordsAtPos(a.state.doc.lineStart(line))!!.top
            val yb = b.coordsAtPos(b.state.doc.lineStart(line))!!.top
            if (ya < 0 || ya > h - 20) continue
            assertTrue(abs(ya - yb) <= 1f, "line $line: A $ya, B $yb")
            compared++
        }
        assertTrue(compared > 15, "$compared")
        // The lines below the thread are pushed down on both sides by the thread's height.
        val below = a.coordsAtPos(a.state.doc.lineStart(12))!!.top - a.coordsAtPos(a.state.doc.lineStart(11))!!.top
        assertTrue(below > 60, "A padded under line 12 for B's thread: $below px")
    }

    @Test fun theDraftReachesTheHostDebouncedAndIsFlushedOnClose() = runComposeUiTest {
        val host = RecordingHost()
        val v = show(host)
        Review.openComposer(v, 20)
        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performTextInput("one")
        mainClock.advanceTimeBy(100)
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performTextInput(" two")
        mainClock.advanceTimeBy(100)
        assertEquals(listOf("open 20"), host.log, "nothing while typing")
        mainClock.advanceTimeBy(DRAFT_DEBOUNCE_MS + 50)
        assertEquals(listOf("open 20", "draft 20 one two"), host.log, "one draft once the typing stopped")
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performTextInput("!")
        mainClock.advanceTimeBy(50)
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performKeyInput { pressKey(Key.Escape) }
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(listOf("open 20", "draft 20 one two", "draft 20 one two!", "closed"), host.log, "flushed before the close")
    }

    @Test fun aSidewaysWheelPagesTheWalkthrough() = runComposeUiTest {
        val pages = ArrayList<DiffPage>()
        val host = object : DiffHost { override fun onDiffPage(direction: DiffPage) { pages += direction } }
        val view = EditorView(EditorState.create(working, extensions = extensionOf(inlineDiff(base, DiffConfig(editable = false), host))))
        setContent { Box(Modifier.size(800.dp, 600.dp).testTag("host")) { InlineDiffEditor(view, Modifier.fillMaxSize()) } }
        waitForIdle()
        onNodeWithTag("host").performMouseInput { moveTo(center); scroll(6f, ScrollWheel.Horizontal) }
        waitForIdle()
        assertEquals(listOf(DiffPage.NEXT), pages)
        mainClock.advanceTimeBy(600)
        onNodeWithTag("host").performMouseInput { scroll(-6f, ScrollWheel.Horizontal) }
        waitForIdle()
        assertEquals(listOf(DiffPage.NEXT, DiffPage.PREVIOUS), pages)
        // Vertical scrolling never pages.
        mainClock.advanceTimeBy(600)
        onNodeWithTag("host").performMouseInput { scroll(10f) }
        waitForIdle()
        assertEquals(2, pages.size)
    }

    @Test fun aDisposedComposerFlushesItsDraftAndARecreatedOneStillReports() = runComposeUiTest {
        val host = RecordingHost()
        val view = EditorView(EditorState.create(working, extensions = extensionOf(inlineDiff(base, DiffConfig(collapseUnchanged = false)), review(host))))
        var shown by androidx.compose.runtime.mutableStateOf(true)
        setContent { Box(Modifier.size(800.dp, 900.dp)) { if (shown) InlineDiffEditor(view, Modifier.fillMaxSize()) } }
        waitForIdle()
        Review.openComposer(view, 10)
        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performTextInput("abc")
        mainClock.advanceTimeBy(50)
        // The editor goes (another pane) inside the debounce: the typing is not lost.
        shown = false
        mainClock.advanceTimeBy(50)
        assertEquals(listOf("open 10", "draft 10 abc"), host.log)
        // Back: the recreated widget starts from the state's draft, and new typing is still reported.
        shown = true
        mainClock.advanceTimeBy(50)
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals("abc", onNodeWithTag(ReviewTags.COMPOSER_FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        onNodeWithTag(ReviewTags.COMPOSER_FIELD).performTextInput("d")
        waitForIdle()
        mainClock.advanceTimeBy(DRAFT_DEBOUNCE_MS + 100)
        waitForIdle()
        assertEquals("abcd", Review.composer(view.state)?.draft)
        assertEquals(listOf("open 10", "draft 10 abc", "draft 10 abcd"), host.log)
    }

    /** M5 B2: a reply typed into a thread survives the thread's widget being disposed (the editor gone and back). */
    @Test fun aReplyDraftSurvivesItsWidgetsDisposal() = runComposeUiTest {
        val view = EditorView(EditorState.create(working, extensions = extensionOf(inlineDiff(base, DiffConfig(collapseUnchanged = false)), review(RecordingHost()))))
        Review.setThreads(view, listOf(open, resolved))
        var shown by androidx.compose.runtime.mutableStateOf(true)
        setContent { Box(Modifier.size(800.dp, 900.dp)) { if (shown) InlineDiffEditor(view, Modifier.fillMaxSize()) } }
        waitForIdle()
        onNodeWithTag(ReviewTags.REPLY_FIELD).performClick()
        onNodeWithTag(ReviewTags.REPLY_FIELD).performTextInput("not sent yet")
        waitForIdle()
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        assertEquals("not sent yet", onNodeWithTag(ReviewTags.REPLY_FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
}

