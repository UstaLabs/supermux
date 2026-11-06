package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.LogEntry
import dev.supermux.state.LiveReply
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

    @Test fun theStoredReplyFinishesAnUnfinishedReveal() = runComposeUiTest {
        val reveals = LiveReveals()
        var live by mutableStateOf(true)
        mainClock.autoAdvance = false
        setPlatformContent {
            CompositionLocalProvider(LocalLiveReveals provides reveals) {
                if (live) LiveReplyRow(LiveReply("k1", "reply", text), reveals.of("k1"), onOpenFile = {})
                else TimelineItemRow(TimelineItem.Msg(LogEntry(id = "m1", ts = "2026-01-01T00:00:00Z", direction = "outbound", text = text, stream = "k1")))
            }
        }
        repeat(5) { mainClock.advanceTimeByFrame() }
        val before = reveals.of("k1").laidOut
        assertTrue(before in 1 until text.length, "mid-reveal: $before")
        live = false
        mainClock.advanceTimeByFrame()
        val after = reveals.of("k1").laidOut
        assertTrue(after in before until text.length, "the stored row picks up where the live row was: $before -> $after")
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(reveals.of("k1").finished)
        onNodeWithText("The end.", substring = true).assertExists()
    }
}
