package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TooltipPlacementTest {
    private val bounds = Rect(0f, 0f, 400f, 300f)
    private fun row(top: Float, left: Float = 50f) = Rect(left, top, left + 2f, top + 20f)
    private val gap = 2f

    @Test fun belowByDefaultLeftAlignedWithTheAnchor() {
        val p = TooltipLayout.place(row(40f), row(40f), 100, 60, bounds, above = false, gap = gap)
        assertFalse(p.above)
        assertEquals(62f, p.y)
        assertEquals(50f, p.x)
    }

    @Test fun flipsAboveAtTheBottomEdge() {
        val p = TooltipLayout.place(row(250f), row(250f), 100, 60, bounds, above = false, gap = gap)
        assertTrue(p.above, "no room below: above")
        assertEquals(250f - gap - 60f, p.y)
    }

    @Test fun flipsBelowAtTheTopEdge() {
        val p = TooltipLayout.place(row(10f), row(10f), 100, 60, bounds, above = true, gap = gap)
        assertFalse(p.above, "no room above: below")
        assertEquals(32f, p.y)
    }

    @Test fun tooTallForBothSidesTakesTheBiggerOneAndMeasuresToFit() {
        val a = row(100f)
        assertEquals(178f, TooltipLayout.maxHeight(a, a, bounds, gap))
        val p = TooltipLayout.place(a, a, 100, 178, bounds, above = true, gap = gap)
        assertFalse(p.above)
        assertTrue(p.y + 178 <= bounds.bottom)
    }

    @Test fun clampedHorizontallyIntoTheEditor() {
        val p = TooltipLayout.place(row(40f, left = 380f), null, 100, 30, bounds, above = false, gap = gap)
        assertEquals(300f, p.x)
        val wide = TooltipLayout.place(row(40f, left = 380f), null, 900, 30, bounds, above = false, gap = gap)
        assertEquals(0f, wide.x, "wider than the editor: from its left edge")
    }

    @Test fun neverCoversTheCaretLineNextToTheAnchor() {
        // A completion anchored at the word start, the caret on the next line (a wrapped row).
        val anchor = row(100f)
        val caret = row(120f, left = 10f)
        val p = TooltipLayout.place(anchor, caret, 100, 50, bounds, above = false, gap = gap)
        assertTrue(p.y >= caret.bottom, "below the caret's row too: ${p.y}")
        val up = TooltipLayout.place(caret, anchor, 100, 50, bounds, above = true, gap = gap)
        assertTrue(up.y + 50 <= anchor.top, "above both rows")
    }

    @Test fun aFarCaretMakesItPickTheOtherSideWhenBothFit() {
        // Signature help above its call; the caret two lines further down is far, but below the
        // anchor where the tooltip would go.
        val anchor = row(100f)
        val caret = row(170f)
        val p = TooltipLayout.place(anchor, caret, 100, 60, bounds, above = false, gap = gap)
        assertTrue(p.above, "it would cover the caret's line below")
    }

    @Test fun theSoftKeyboardShrinksTheBounds() {
        val kb = Rect(0f, 0f, 400f, 180f) // 120 px of keyboard over the editor's bottom
        val p = TooltipLayout.place(row(140f), row(140f), 100, 60, kb, above = false, gap = gap)
        assertTrue(p.above, "no room between the line and the keyboard")
    }
}
