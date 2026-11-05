package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.parseMarkdownBlocks
import dev.supermux.ui.theme.LocalReduceMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Card #76 (B3, the ink edge): the live reply's reveal in Compose. */
@OptIn(ExperimentalTestApi::class)
class LiveRevealTest {
    private val text = (1..6).joinToString(" ") { "Sentence $it of the live reply goes on for a while." } + " The end."

    @Test fun blockEndsAreFoundFromTheEnd() {
        val src = "Intro **bold** text.\n\nSecond para."
        assertEquals(listOf<Int?>(20, 34), blockSourceEnds(src, parseMarkdownBlocks(src)))
    }

    @Test fun headingsAndListItemsEndWhereTheirTextEnds() {
        val src = "# Title\n\n- one\n- two"
        assertEquals(listOf<Int?>(7, 14, 20), blockSourceEnds(src, parseMarkdownBlocks(src)))
    }

    @Test fun codeBlocksGetNoEdge() {
        val src = "Run this:\n\n```sh\nls -l\n```\n\nThen look."
        assertEquals(listOf<Int?>(9, null, src.length), blockSourceEnds(src, parseMarkdownBlocks(src)))
    }

    @Test fun theTextAppearsProgressivelyAndEndsWhole() = runComposeUiTest {
        val reveal = LiveRevealState()
        mainClock.autoAdvance = false
        setPlatformContent {
            RevealDriver(reveal, text, done = false)
            MarkdownBody(text = text.take(reveal.laidOut), reveal = reveal)
        }
        repeat(5) { mainClock.advanceTimeByFrame() }
        assertTrue(reveal.laidOut in 1 until text.length, "a few words in after 80 ms: ${reveal.laidOut}")
        assertTrue(reveal.masking, "the newest words are still inking in")
        assertTrue(onAllNodesWithText("The end.", substring = true).fetchSemanticsNodes().isEmpty())
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(text.length, reveal.laidOut)
        assertFalse(reveal.masking, "dry once idle")
        onNodeWithText("The end.", substring = true).assertExists()
    }

    @Test fun reducedMotionShowsWholeWordsWithNoEdge() = runComposeUiTest {
        val reveal = LiveRevealState()
        mainClock.autoAdvance = false
        setPlatformContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                RevealDriver(reveal, text, done = false)
                MarkdownBody(text = text.take(reveal.laidOut), reveal = reveal)
            }
        }
        repeat(6) { mainClock.advanceTimeByFrame() }
        val n = reveal.laidOut
        assertTrue(n == 0 || text[n - 1].isWhitespace() || text[n].isWhitespace(), "a word boundary: '${text.take(n)}'")
        assertFalse(reveal.masking, "no ink edge under reduced motion")
    }
}
