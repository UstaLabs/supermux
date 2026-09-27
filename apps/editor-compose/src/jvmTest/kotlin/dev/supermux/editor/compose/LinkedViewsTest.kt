package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two editors linked for a side-by-side diff: A the base, B the working copy with a deleted run, an
 * inserted run and a changed run of another length, gap widgets making every changed run as tall
 * on both sides. Corresponding lines must stay at the same y whichever side scrolls.
 */
@OptIn(ExperimentalTestApi::class)
class LinkedViewsTest {
    private val aLines = (0 until 300).map { "a $it" }
    private val bLines = aLines.subList(0, 50) + aLines.subList(55, 101) + listOf("ins 0", "ins 1", "ins 2") +
        aLines.subList(101, 150) + listOf("chg 0", "chg 1", "chg 2", "chg 3") + aLines.subList(152, 300)
    private val mapping = LineMapping(listOf(
        LineMapping.Hunk(50, 55, 50, 50),
        LineMapping.Hunk(101, 101, 96, 99),
        LineMapping.Hunk(150, 152, 148, 152),
    ))

    private fun gap(state: EditorState, line: Int, lines: Int, id: String) =
        Ranged(state.doc.lineStart(line), state.doc.lineStart(line), Decoration.BlockWidget(WidgetKey("gap", id), above = true, estimatedHeightLines = lines.toFloat()) as Decoration)

    private class Pair2(val a: EditorView, val b: EditorView, val link: LinkedScroll, val gapsA: RangePlugin<Decoration>, val gapsB: RangePlugin<Decoration>) {
        val ca: EditorController get() = a.surface as EditorController
        val cb: EditorController get() = b.surface as EditorController
    }

    private fun linkedTest(body: ComposeUiTest.(Pair2) -> Unit) = runComposeUiTest {
        DrawGuard.strict = true
        val stA = EditorState.create(aLines.joinToString("\n"))
        val stB = EditorState.create(bLines.joinToString("\n"))
        val gapsA = RangePlugin("gapsA", decorationsFacet, listOf(gap(stA, 101, 3, "h2"), gap(stA, 152, 2, "h3")))
        val gapsB = RangePlugin("gapsB", decorationsFacet, listOf(gap(stB, 50, 5, "h1")))
        val a = EditorView(EditorState.create(stA.doc.toString(), extensions = gapsA.extension))
        val b = EditorView(EditorState.create(stB.doc.toString(), extensions = gapsB.extension))
        val link = LinkedScroll(mapping)
        setContent {
            CompositionLocalProvider(LocalEditorCursorBlink provides false) {
                Row(Modifier.size(600.dp, 300.dp)) {
                    Editor(a, Modifier.weight(1f).fillMaxHeight().testTag("a"), linked = link, linkedSide = LinkedSide.A)
                    Editor(b, Modifier.weight(1f).fillMaxHeight().testTag("b"), linked = link, linkedSide = LinkedSide.B)
                }
            }
        }
        waitForIdle()
        try { body(Pair2(a, b, link, gapsA, gapsB)) } finally { DrawGuard.strict = false }
    }

    /** Every pair of corresponding lines both editors show sits at the same y (within 1 px); how many were compared. */
    private fun assertAligned(p: Pair2, what: String, m: LineMapping = p.link.mapping): Int {
        val ca = p.ca
        val cb = p.cb
        var compared = 0
        val shownA = ca.geometry.shownLines(ca.drawnLines)
        val drawnB = cb.drawnLines
        for (la in shownA) {
            val (a, lb, hunk) = m.pair(LinkedSide.A, la)
            if (hunk || a != la || lb !in drawnB) continue
            val ya = ca.caretRectOnScreen(p.a.state.doc.lineStart(la)).top
            val yb = cb.caretRectOnScreen(p.b.state.doc.lineStart(lb)).top
            if (ya < -50f || ya > 400f * ca.densityValue) continue
            assertTrue(abs(ya - yb) <= 1f, "$what: A line $la at $ya, B line $lb at $yb")
            compared++
        }
        return compared
    }

    @Test fun scrollingEitherSideKeepsCorrespondingLinesAligned() = linkedTest { p ->
        assertTrue(assertAligned(p, "at the top") > 5)
        val step = 37f
        var compared = 0
        repeat(120) { i ->
            p.ca.scroll.scrollBy(0f, step)
            waitForIdle()
            compared += assertAligned(p, "A scrolled, step $i")
        }
        assertTrue(p.cb.scroll.y > 1000f, "B did not follow A (${p.cb.scroll.y})")
        repeat(120) { i ->
            p.cb.scroll.scrollBy(0f, -step)
            waitForIdle()
            compared += assertAligned(p, "B scrolled, step $i")
        }
        assertTrue(compared > 1000, "too few lines compared: $compared")
    }

    @Test fun aChangedRunsGapFillsTheOtherSidesExtraLines() = linkedTest { p ->
        // Put B's inserted lines on screen: they stand where A's gap is.
        val lh = p.ca.layouts.lineHeightPx
        p.cb.scroll.scrollTo(y = 90 * lh)
        waitForIdle()
        val gapTop = p.ca.geometry.heights.top(101) - p.ca.scroll.y
        val inserted = p.cb.caretRectOnScreen(p.b.state.doc.lineStart(96)).top
        assertEquals(gapTop, inserted, 1f, "B's inserted run does not start at A's gap")
        assertEquals(3 * lh, p.ca.geometry.heights.blockAbove(101), 0.01f)
        assertAligned(p, "around the insertion")
    }

    @Test fun aFlingOnEitherSideCarriesTheOther() = linkedTest { p ->
        mainClock.autoAdvance = false
        onNodeWithTag("a").performTouchInput { swipeWithVelocity(Offset(150f, 250f), Offset(150f, 80f), endVelocity = 3000f, durationMillis = 100) }
        mainClock.advanceTimeBy(3000)
        waitForIdle()
        val afterA = p.cb.scroll.y
        assertTrue(p.ca.scroll.y > 500f && afterA > 500f, "the fling on A (A ${p.ca.scroll.y}, B $afterA)")
        assertTrue(assertAligned(p, "after A's fling") > 5)
        onNodeWithTag("b").performTouchInput { swipeWithVelocity(Offset(150f, 80f), Offset(150f, 250f), endVelocity = 3000f, durationMillis = 100) }
        mainClock.advanceTimeBy(3000)
        waitForIdle()
        assertTrue(p.ca.scroll.y < afterA - 300f, "the fling on B did not carry A back (${p.ca.scroll.y})")
        assertTrue(assertAligned(p, "after B's fling") > 5)
    }

    @Test fun editingTheWorkingCopyKeepsAlignmentOnceTheMappingFollows() = linkedTest { p ->
        val lh = p.ca.layouts.lineHeightPx
        p.ca.scroll.scrollTo(y = 60 * lh)
        waitForIdle()
        val before = p.ca.caretRectOnScreen(p.a.state.doc.lineStart(70)).top
        // A line typed into B above the viewport (line 20): an insertion hunk, a gap in A.
        val at = p.b.state.doc.lineStart(20)
        p.b.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "typed\n")), userEvent = "input"))
        waitForIdle()
        val shifted = LineMapping(listOf(LineMapping.Hunk(20, 20, 20, 21)) + mapping.hunks.map { it.copy(bFrom = it.bFrom + 1, bTo = it.bTo + 1) })
        val gaps = p.gapsA.value(p.a.state).toList() + gap(p.a.state, 20, 1, "h0")
        p.gapsA.replace(p.a, gaps)
        p.link.mapping = shifted
        waitForIdle()
        assertTrue(assertAligned(p, "after the edit", shifted) > 5)
        // A (the base) did not move: its new gap is above its view, and the position is lines.
        assertEquals(before, p.ca.caretRectOnScreen(p.a.state.doc.lineStart(70)).top, 1f)
        // And it keeps aligning while scrolling.
        repeat(40) { i ->
            p.cb.scroll.scrollBy(0f, 29f)
            waitForIdle()
            assertAligned(p, "after the edit, step $i", shifted)
        }
    }

    @Test fun keyboardScrollIntoViewOnOneSideMovesTheOther() = linkedTest { p ->
        val far = p.b.state.doc.lineStart(250)
        p.b.dispatch(TransactionSpec(selection = dev.supermux.editor.core.EditorSelection.cursor(far), scrollIntoView = true))
        waitForIdle()
        assertTrue(p.ca.scroll.y > 1000f, "A did not follow B's scroll into view")
        assertTrue(assertAligned(p, "after scroll into view") > 5)
        assertNotNull(p.link)
    }
}
