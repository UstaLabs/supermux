package dev.supermux.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Card #76 (B3, the ink edge): the live reply's display clock. */
class RevealClockTest {
    /** 200 chars, every word followed by a space. */
    private val words = "word ".repeat(40)

    /** One 16 ms frame at a time from [fromMs] through [toMs], the text unchanged; returns the last frame's time. */
    private fun RevealClock.frames(text: String, done: Boolean, fromMs: Double, toMs: Double, reduced: Boolean = false): Double {
        var t = fromMs
        while (t <= toMs) { advance(t, text, done, reduced); t += 16.0 }
        return t - 16.0
    }

    private fun atWordBoundary(text: String, i: Int) =
        i == 0 || i == text.length || text[i].isWhitespace() || text[i - 1].isWhitespace()

    @Test fun aBurstSpreadsOverAboutTau() {
        val clock = RevealClock()
        clock.advance(0.0, words, done = false)
        // One frame covers 1 - exp(-16/150) of the 200-char backlog: about 20 characters, not all of them.
        assertTrue(clock.head in 15.0..25.0, "head ${clock.head}")
        clock.frames(words, done = false, fromMs = 16.0, toMs = 150.0)
        assertTrue(clock.head in 110.0..150.0, "after about tau, about 63%: ${clock.head}")
        clock.frames(words, done = false, fromMs = 166.0, toMs = 1200.0)
        assertEquals(200, clock.laidOut)
    }

    @Test fun aTrickleFlowsAtTheFloor() {
        val clock = RevealClock()
        clock.advance(0.0, "ab ", done = false)
        // 3 * (1 - exp(-16/150)) = 0.3 is under the floor: 30 chars/s * 16 ms = 0.48.
        assertEquals(0.48, clock.head, 1e-9)
    }

    @Test fun theTrailingHalfWordWaitsWhileTheStreamFlows() {
        val clock = RevealClock()
        var text = "hello wor"
        val t = clock.frames(text, done = false, fromMs = 0.0, toMs = 250.0)
        assertEquals("hello ", text.take(clock.laidOut))
        text = "hello world and"
        clock.frames(text, done = false, fromMs = t + 16, toMs = t + 250)
        assertEquals("hello world ", text.take(clock.laidOut))
    }

    @Test fun aPauseRevealsTheTrailingWord() {
        val clock = RevealClock()
        clock.frames("Live tail.", done = false, fromMs = 0.0, toMs = 1000.0)
        assertEquals(10, clock.laidOut, "after 300 ms with nothing new, the last word shows")
    }

    @Test fun theEndRevealsTheTrailingWord() {
        val clock = RevealClock()
        clock.advance(0.0, "hello wor", done = false)
        clock.frames("hello world", done = true, fromMs = 16.0, toMs = 400.0)
        assertEquals(11, clock.laidOut)
    }

    @Test fun aLongTokenDoesNotWait() {
        val clock = RevealClock()
        val token = "x".repeat(40)
        var t = 0.0
        // One more character every frame: never a pause, yet a run over 32 chars is not a half word to wait for.
        while (t <= 600.0) { clock.advance(t, token.take(minOf(40, 8 + (t / 16).toInt())), done = false); t += 16.0 }
        assertEquals(40, clock.laidOut)
    }

    @Test fun theWordTheHeadIsInIsLaidOutWhole() {
        val clock = RevealClock()
        clock.advance(0.0, "alpha beta gamma ", done = false)
        // head = 17 * (1 - exp(-16/150)) = 1.7: inside "alpha", which is laid out whole.
        assertTrue(clock.head < 5.0, "head ${clock.head}")
        assertEquals(5, clock.laidOut)
    }

    @Test fun anEdgeFadesBehindTheHeadAndNothingShowsPastTheLayout() {
        val clock = RevealClock()
        val now = clock.frames(words, done = false, fromMs = 0.0, toMs = 96.0)
        val head = clock.head.toInt()
        assertEquals(0f, clock.alphaAt(clock.laidOut, now))
        val near = clock.alphaAt(head - 1, now)
        val far = clock.alphaAt(head - 12, now)
        assertTrue(near < 1f && far < 1f, "both inside the 24-char edge: $near, $far")
        assertTrue(far > near, "further behind the head is more opaque: $far > $near")
    }

    @Test fun aPauseNeverLeavesAFadedTail() {
        val clock = RevealClock()
        val now = clock.frames("word ".repeat(10), done = false, fromMs = 0.0, toMs = 1500.0)
        assertFalse(clock.animating)
        for (i in 0 until clock.laidOut) assertEquals(1f, clock.alphaAt(i, now), "char $i")
        assertEquals(clock.laidOut, clock.firstUnsettled())
    }

    @Test fun itIdlesOnceCaughtUpAndDryAndWakesForNewText() {
        val clock = RevealClock()
        val now = clock.frames("word ".repeat(10), done = false, fromMs = 0.0, toMs = 1500.0)
        assertFalse(clock.animating)
        clock.advance(now + 16, "word ".repeat(12), done = false)
        assertTrue(clock.animating)
    }

    @Test fun reducedMotionShowsWholeWordsAtThePaceWithNoEdge() {
        val clock = RevealClock()
        val text = "alpha beta gamma "
        clock.advance(0.0, text, done = false, reduced = true)
        assertEquals(0, clock.laidOut, "the head is inside alpha: nothing yet, never a fragment")
        val now = clock.frames(text, done = false, fromMs = 16.0, toMs = 120.0, reduced = true)
        assertTrue(atWordBoundary(text, clock.laidOut), "a word boundary: ${clock.laidOut}")
        for (i in 0 until clock.laidOut) assertEquals(1f, clock.alphaAt(i, now))
        clock.frames(text, done = false, fromMs = now + 16, toMs = now + 1500, reduced = true)
        assertEquals(17, clock.laidOut)
        assertFalse(clock.animating)
    }

    @Test fun theEndCatchesUpFaster() {
        val open = RevealClock().apply { advance(0.0, words, done = false) }
        val ended = RevealClock().apply { advance(0.0, words, done = true) }
        assertTrue(ended.head > open.head * 1.5, "tau 75 ms once the stream has ended: ${ended.head} vs ${open.head}")
    }
}
