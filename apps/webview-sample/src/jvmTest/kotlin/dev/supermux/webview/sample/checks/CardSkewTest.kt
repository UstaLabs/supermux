package dev.supermux.webview.sample.checks

import dev.supermux.webview.sample.chat.PreviewMarkers
import dev.supermux.webview.testing.Copied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The skew checks' reading of copies: where each live page's lines are against its card's caption line. */
class CardSkewTest {
    private val width = 400
    private val height = 700

    /** A dark copy; [lines]: (top row, rgb) of 3-row lines across columns 20..379. */
    private fun copy(vararg lines: Pair<Int, Int>): Copied {
        val rgba = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            rgba[i * 4] = 0x13
            rgba[i * 4 + 1] = 0x13
            rgba[i * 4 + 2] = 0x18
            rgba[i * 4 + 3] = 0xff.toByte()
        }
        for ((top, rgb) in lines) {
            for (y in top until top + PreviewMarkers.THICKNESS) {
                for (x in 20 until 380) {
                    val i = (y * width + x) * 4
                    rgba[i] = (rgb shr 16).toByte()
                    rgba[i + 1] = (rgb shr 8).toByte()
                    rgba[i + 2] = rgb.toByte()
                }
            }
        }
        return Copied(width, height, rgba)
    }

    private val cyan = 0x00ffff
    private val blue = 0x0000ff
    private val magenta = 0xff00ff
    private val yellow = 0xffff00

    /** A card whose slot ends at [bottom], its page [skew] points lower; its caption [live] or a picture. */
    private fun card(bottom: Int, skew: Int = 0, live: Boolean = true) = arrayOf(
        bottom to if (live) cyan else blue,
        bottom - PreviewMarkers.MAGENTA + skew to magenta,
        bottom - PreviewMarkers.YELLOW + skew to yellow,
    )

    @Test fun aPageInItsCardHasNoSkew() {
        val marks = CardSkew.marks(copy(*card(300)), minRun = 200)
        assertEquals(1, marks.size)
        assertEquals(0f, marks.single().skew)
    }

    @Test fun aPageAheadOfItsCardIsMeasuredInPoints() {
        assertEquals(-12f, CardSkew.marks(copy(*card(300, skew = -12)), 200).single().skew)
        assertEquals(30f, CardSkew.marks(copy(*card(300, skew = 30)), 200).single().skew)
    }

    /** Far enough that the magenta line is under the caption, the yellow one still says where the page is. */
    @Test fun theYellowLineCountsWhenTheMagentaOneIsHidden() {
        val marks = CardSkew.marks(copy(300 to cyan, 300 - PreviewMarkers.YELLOW + 60 to yellow), 200)
        assertEquals(60f, marks.single().skew)
    }

    @Test fun aPictureOfThePageIsNotMeasured() {
        assertTrue(CardSkew.marks(copy(*card(300, skew = 20, live = false)), 200).isEmpty())
    }

    /** Each card's page lines are its own: none is taken for the card below it. */
    @Test fun twoCardsAreMeasuredApart() {
        val marks = CardSkew.marks(copy(*card(250, skew = 8), *card(600, skew = -5)), 200).sortedBy { it.captionY }
        assertEquals(listOf(8f, -5f), marks.map { it.skew })
    }

    @Test fun shortLinesAreNotLines() {
        assertTrue(CardSkew.marks(copy(*card(300)), minRun = 400).isEmpty())
    }

    /**
     * A card moving 6 points a frame, copied every 8 ms: its page one presented frame ahead (what a copy shows between a
     * commit and the next refresh) is within one; three frames ahead, as without frame sync, is beyond it.
     */
    @Test fun aPageIsJudgedInFramesItsCardPresented() {
        fun run(framesAhead: Int): SkewRecord.Summary {
            val record = SkewRecord(framePeriodNanos = 16_000_000.0)
            for (copy in 0 until 120) {
                val nanos = copy * 8_000_000L
                val frame = (nanos / 16_000_000L).toInt()
                val captionY = 100f + 6f * frame
                record.add(nanos, listOf(CardSkew.Mark(captionY, 20, 380, skew = 6f * framesAhead)))
            }
            return record.summary()
        }
        val one = run(1)
        assertTrue(one.moving > 50, "$one")
        assertEquals(0, one.beyondOne, "$one")
        assertEquals(one.moving, one.ahead, "$one")
        val three = run(3)
        assertTrue(three.beyondOne * 2 > three.moving, "$three")
        assertEquals(3.0, three.steps.p50, "$three")
        val still = run(0)
        assertEquals(0, still.off, "$still")
        assertEquals(0.0, still.px.max, "$still")
    }
}
