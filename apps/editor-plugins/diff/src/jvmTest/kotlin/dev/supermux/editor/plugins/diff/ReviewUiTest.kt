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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
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

    @Test fun replyAndResolveButtons() = runComposeUiTest {
        val host = RecordingHost()
        show(host)
        onNodeWithTag(ReviewTags.REPLY_FIELD).performClick()
        onNodeWithTag(ReviewTags.REPLY_FIELD).performTextInput("Thanks!")
        onNodeWithTag(ReviewTags.REPLY).performClick()
        waitForIdle()
        onNodeWithTag(ReviewTags.RESOLVE).performClick()
        waitForIdle()
        assertEquals(listOf("reply t1 Thanks!", "resolve t1"), host.log)
        assertEquals("", onNodeWithTag(ReviewTags.REPLY_FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text, "sent: cleared")
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
}
