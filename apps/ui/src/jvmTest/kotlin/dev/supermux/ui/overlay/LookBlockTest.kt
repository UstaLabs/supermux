package dev.supermux.ui.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LookBlockTest {
    /** A white 100 × 200 screen to draw on. */
    private fun screen() = LumaGrid(100, 200, IntArray(100 * 200) { 255 })

    /** A line of "text" three cells tall: glyph columns with thin spaces between. */
    private fun LumaGrid.line(y: Int, x0: Int, x1: Int) {
        for (yy in y until y + 3) for (x in x0..x1) if (x % 3 != 0) px[yy * w + x] = 30
    }

    private fun LumaGrid.paragraph(top: Int, lines: Int, x0: Int = 20, x1: Int = 80) {
        for (i in 0 until lines) line(top + i * 6, x0, x1)
    }

    @Test
    fun aParagraphIsFoundWholeAcrossItsLineGaps() {
        val g = screen().apply { paragraph(top = 40, lines = 3) }
        assertEquals(LookRect(20f, 40f, 81f, 55f), LookBlock.around(g, 50, 47, minGap = 6))
    }

    @Test
    fun aWideMarginEndsTheBlock() {
        val g = screen().apply { paragraph(top = 40, lines = 3); paragraph(top = 80, lines = 3) }
        assertEquals(LookRect(20f, 40f, 81f, 55f), LookBlock.around(g, 50, 47, minGap = 6))
        assertEquals(LookRect(20f, 80f, 81f, 95f), LookBlock.around(g, 50, 86, minGap = 6))
    }

    @Test
    fun itCrossesTheGapsBetweenWordsButNotBetweenColumns() {
        val g = screen().apply {
            line(100, 5, 40); line(100, 43, 60) // two words, a 2-cell space between
            line(100, 75, 95) // another column, 14 cells away
        }
        assertEquals(LookRect(5f, 100f, 60f, 103f), LookBlock.around(g, 30, 101, minGap = 6)) // its last glyph column is 59
    }

    @Test
    fun blankSpaceIsNoBlock() = assertNull(LookBlock.around(screen().apply { paragraph(top = 40, lines = 3) }, 50, 150, minGap = 6))

    @Test
    fun aPointOffTheScreenIsNoBlock() = assertNull(LookBlock.around(screen(), 150, 50, minGap = 6))

    @Test
    fun theOverlaysOwnHeadIsNotWhatItLooksAt() {
        val g = screen()
        for (y in 120..140) for (x in 40..60) g.px[y * g.w + x] = if ((x + y) % 2 == 0) 0 else 200
        g.blankOut(40, 120, 60, 140)
        assertNull(LookBlock.around(g, 50, 130, minGap = 6, seed = 14))
    }

    @Test
    fun blankingOverTextKeepsTheTextAroundIt() {
        val g = screen().apply { paragraph(top = 40, lines = 5) }
        g.blankOut(45, 44, 55, 56)
        // The start area reaches past the painted-over head (half-size 8 > 5).
        assertEquals(LookRect(20f, 40f, 81f, 67f), LookBlock.around(g, 50, 50, minGap = 6, seed = 8))
    }

    @Test
    fun aScreenshotIsAveragedDownToGrey() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val argb = IntArray(8 * 4) { i -> if (i % 8 < 4) white else black }
        val g = LumaGrid.fromArgb(argb, 8, 4, step = 4)
        assertEquals(2, g.w)
        assertEquals(1, g.h)
        assertEquals(255, g[0, 0])
        assertEquals(0, g[1, 0])
    }
}
