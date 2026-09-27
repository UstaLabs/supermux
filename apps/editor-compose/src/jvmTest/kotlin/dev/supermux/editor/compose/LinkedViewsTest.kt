package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.LineMapping
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.lineMappingFacet
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two editors linked for a side-by-side diff: A the base, B the working copy with a deleted run, an
 * inserted run and a changed run of another length. The mapping comes from B's state (a diff
 * plugin's `lineMappingFacet`); no gap widgets: the surface pads from both sides' MEASURED heights,
 * so corresponding lines stay at the same y whatever each side measured (wrapping, zoom, widgets).
 */
@OptIn(ExperimentalTestApi::class)
class LinkedViewsTest {
    private val aLines = (0 until 300).map { "a $it" + if (it % 7 == 3) " " + "long words wrap here ".repeat(8) else "" }
    private val bLines = aLines.subList(0, 50) + aLines.subList(55, 101) + listOf("ins 0", "ins 1", "ins 2") +
        aLines.subList(101, 150) + listOf("chg 0", "chg 1", "chg 2", "chg 3") + aLines.subList(152, 300)
    private val mapping = LineMapping(listOf(
        LineMapping.Hunk(50, 55, 50, 50),
        LineMapping.Hunk(101, 101, 96, 99),
        LineMapping.Hunk(150, 152, 148, 152),
    ))

    /** The diff plugin, as far as linking goes: its mapping in a state field, set by an effect. */
    private val setMapping = StateEffectType<LineMapping>("setMapping")
    private val mappingField: StateField<LineMapping> = StateField(
        "mapping", { mapping }, { v, tr -> tr.effects.firstNotNullOfOrNull { it.valueIf(setMapping) } ?: v },
        { f -> lineMappingFacet.compute(FacetDep.field(f)) { it.field(f) } },
    )

    private class Pair2(val a: EditorView, val b: EditorView, val link: LinkedScroll) {
        val ca: EditorController get() = a.surface as EditorController
        val cb: EditorController get() = b.surface as EditorController
    }

    private fun block(st: EditorState, line: Int, id: String, above: Boolean = false) =
        Ranged(st.doc.lineStart(line), st.doc.lineStart(line), Decoration.BlockWidget(WidgetKey("thread", id), above = above) as Decoration)

    private fun linkedTest(
        wrapA: Boolean = false, wrapB: Boolean = false, fontB: Float? = null,
        decoA: (EditorState) -> List<Ranged<Decoration>> = { emptyList() },
        decoB: (EditorState) -> List<Ranged<Decoration>> = { emptyList() },
        bText: String = bLines.joinToString("\n"),
        body: ComposeUiTest.(Pair2) -> Unit,
    ) = runComposeUiTest {
        DrawGuard.strict = true
        fun make(text: String, deco: (EditorState) -> List<Ranged<Decoration>>, extra: Extension): EditorView {
            val plain = EditorState.create(text)
            val d = deco(plain)
            val ext = if (d.isEmpty()) extra else extensionOf(extra, decorationsFacet.of(dev.supermux.editor.core.RangeSet.of(d)))
            return EditorView(EditorState.create(text, extensions = ext))
        }
        val a = make(aLines.joinToString("\n"), decoA, extensionOf())
        val b = make(bText, decoB, mappingField)
        b.fontSize = fontB
        val link = LinkedScroll()
        val registry = WidgetRegistry().apply {
            register("thread") { key -> Box(Modifier.fillMaxWidth().height(if (key.id.startsWith("big")) 100.dp else 60.dp)) }
        }
        setContent {
            CompositionLocalProvider(LocalEditorCursorBlink provides false) {
                Row(Modifier.size(700.dp, 300.dp)) {
                    Editor(a, Modifier.weight(1f).fillMaxHeight().testTag("a"), lineWrap = wrapA, widgets = registry, linked = link, linkedSide = LinkedSide.A)
                    Editor(b, Modifier.weight(1f).fillMaxHeight().testTag("b"), lineWrap = wrapB, widgets = registry, linked = link, linkedSide = LinkedSide.B)
                }
            }
        }
        waitForIdle()
        try { body(Pair2(a, b, link)) } finally { DrawGuard.strict = false }
    }

    /** Every pair of corresponding lines on screen on both sides sits at the same y (within 1 px); how many were compared. */
    private fun assertAligned(p: Pair2, what: String, m: LineMapping = mapping): Int {
        val ca = p.ca
        val cb = p.cb
        val vh = ca.viewportSize.height
        var compared = 0
        var a = 0
        var b = 0
        val nA = p.a.state.doc.lineCount
        val nB = p.b.state.doc.lineCount
        fun check(la: Int, lb: Int, text: Boolean) {
            if (la >= nA || lb >= nB) return
            val ya = if (text) ca.geometry.lineTop(la) - ca.scroll.y else ca.geometry.heights.boxTop(la) - ca.scroll.y
            val yb = if (text) cb.geometry.lineTop(lb) - cb.scroll.y else cb.geometry.heights.boxTop(lb) - cb.scroll.y
            if (ya < 0f || ya > vh || yb < 0f || yb > vh) return
            assertTrue(abs(ya - yb) <= 1f, "$what: A line $la at $ya, B line $lb at $yb")
            compared++
        }
        for (h in m.hunks + LineMapping.Hunk(nA, nA, nB, nB)) {
            while (a < h.aFrom && b < h.bFrom) { check(a, b, true); a++; b++ }
            if (h.aTo - h.aFrom == h.bTo - h.bFrom) { while (a < h.aTo) { check(a, b, true); a++; b++ } }
            else if (h.aTo > h.aFrom && h.bTo > h.bFrom) check(h.aFrom, h.bFrom, false)
            a = h.aTo; b = h.bTo
        }
        return compared
    }

    private fun ComposeUiTest.scrollBoth(p: Pair2, what: String, m: LineMapping = mapping): Int {
        var compared = assertAligned(p, "$what: at the start", m)
        repeat(90) { i ->
            p.ca.scroll.scrollBy(0f, 41f)
            waitForIdle()
            compared += assertAligned(p, "$what: A scrolled, step $i", m)
        }
        assertTrue(p.cb.scroll.y > 1000f, "$what: B did not follow A (${p.cb.scroll.y})")
        repeat(90) { i ->
            p.cb.scroll.scrollBy(0f, -37f)
            waitForIdle()
            compared += assertAligned(p, "$what: B scrolled, step $i", m)
        }
        return compared
    }

    @Test fun scrollingEitherSideKeepsCorrespondingLinesAligned() = linkedTest { p ->
        assertTrue(scrollBoth(p, "plain") > 1000)
    }

    @Test fun aMeasuredThreadOnOneSideIsPaddedOnTheOther() = linkedTest(decoB = { st -> listOf(block(st, 20, "big1"), block(st, 60, "big2")) }) { p ->
        assertTrue(scrollBoth(p, "a 100 dp thread") > 1000)
        // The padding is the thread's measured height (not a whole number of lines).
        val px = 100 * p.cb.densityValue
        assertEquals(px, p.ca.geometry.heights.pad(21, HeightMap.Pad.BEFORE_TEXT), 1f)
    }

    @Test fun wrappingOnOneSideOnly() = linkedTest(wrapA = true) { p ->
        assertTrue(scrollBoth(p, "A wraps") > 500)
    }

    @Test fun aDifferentZoomOnEachSide() = linkedTest(fontB = 19f) { p ->
        assertTrue(scrollBoth(p, "B zoomed") > 500)
    }

    @Test fun widgetsOnBothSidesOfTheSameRow() = linkedTest(
        decoA = { st -> listOf(block(st, 30, "a30"), block(st, 40, "big-a", above = true)) },
        decoB = { st -> listOf(block(st, 30, "big-b30"), block(st, 40, "b40", above = true)) },
    ) { p ->
        assertTrue(scrollBoth(p, "widgets both sides") > 1000)
    }

    @Test fun aChangedRunsShorterSideIsPaddedToTheLongerOne() = linkedTest { p ->
        val lh = p.ca.layouts.lineHeightPx
        p.cb.scroll.scrollTo(y = 90 * lh)
        waitForIdle()
        // B's inserted run (3 lines) stands where A has none: A's line 101 is below the run.
        val inserted = p.cb.caretRectOnScreen(p.b.state.doc.lineStart(96)).top
        val a101 = p.ca.caretRectOnScreen(p.a.state.doc.lineStart(101)).top
        val b99 = p.cb.caretRectOnScreen(p.b.state.doc.lineStart(99)).top
        assertEquals(b99, a101, 1f)
        assertTrue(a101 - inserted >= 3 * lh - 1f, "the gap is not the run's height")
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
        // A line typed into B above the viewport, the plugin's new mapping in the same transaction.
        val at = p.b.state.doc.lineStart(20)
        val shifted = LineMapping(listOf(LineMapping.Hunk(20, 20, 20, 21)) + mapping.hunks.map { it.copy(bFrom = it.bFrom + 1, bTo = it.bTo + 1) })
        p.b.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "typed\n")), effects = listOf(setMapping.of(shifted)), userEvent = "input"))
        waitForIdle()
        assertTrue(assertAligned(p, "after the edit", shifted) > 5)
        // A (the base) did not move: the new padding is above its view, and the position is lines.
        assertEquals(before, p.ca.caretRectOnScreen(p.a.state.doc.lineStart(70)).top, 1f)
        repeat(40) { i ->
            p.cb.scroll.scrollBy(0f, 29f)
            waitForIdle()
            assertAligned(p, "after the edit, step $i", shifted)
        }
    }

    @Test fun aStaleMappingPastTheWorkingCopysEndIsClamped() = linkedTest(bText = bLines.subList(0, 120).joinToString("\n")) { p ->
        // The mapping still describes the 305-line B; this B has 120 lines.
        repeat(60) { p.cb.scroll.scrollBy(0f, 97f); waitForIdle() }
        val b = p.cb.scroll.y
        assertTrue(b > 1000f, "B froze under a stale mapping (${p.cb.scroll.y})")
        repeat(60) { p.ca.scroll.scrollBy(0f, -97f); waitForIdle() }
        assertTrue(p.cb.scroll.y < b, "B stopped following A")
        assertTrue(assertAligned(p, "stale mapping, near the top") > 5)
    }

    @Test fun keyboardScrollIntoViewOnOneSideMovesTheOther() = linkedTest { p ->
        val far = p.b.state.doc.lineStart(250)
        p.b.dispatch(TransactionSpec(selection = EditorSelection.cursor(far), scrollIntoView = true))
        waitForIdle()
        assertTrue(p.ca.scroll.y > 1000f, "A did not follow B's scroll into view")
        assertTrue(assertAligned(p, "after scroll into view") > 5)
    }
}
