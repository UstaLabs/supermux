package dev.supermux.ui.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayStateTest {
    private fun state(
        listening: Boolean = false, speaking: Boolean = false, thinking: Boolean = false, online: Boolean = true,
        working: Boolean = false, unread: Boolean = false, dozing: Boolean = false, awake: Boolean = false,
    ) = overlayAvatarState(listening, speaking, thinking, online, working, unread, dozing, awake)

    @Test fun holdingItAlwaysShowsItListening() = assertEquals("listening", state(listening = true, online = false, working = true))
    @Test fun aStoppedSessionSleeps() = assertEquals("asleep", state(online = false, unread = true))
    @Test fun aReplyTypingOutIsSpoken() = assertEquals("speaking", state(speaking = true, unread = true))
    @Test fun busyBeatsUnread() = assertEquals("working", state(working = true, unread = true))
    @Test fun anUnreadReplyWaitsForYou() = assertEquals("waiting", state(unread = true, dozing = true))
    @Test fun quietForAWhileItDozes() = assertEquals("asleep", state(dozing = true))
    @Test fun touchingOrOpeningItWakesItUp() = assertEquals("idle", state(dozing = true, awake = true))
    @Test fun otherwiseIdle() = assertEquals("idle", state())

    // Letting go: the words are on their way before the Agent reports busy, so it works, never dozes.
    @Test fun afterTalkingItWorksUntilTheAnswer() = assertEquals("working", state(thinking = true, dozing = true))
    @Test fun afterTalkingItWorksEvenIfItWasAsleep() = assertEquals("working", state(thinking = true, online = false))
    @Test fun theAnswerTypingOutIsSpokenNotWorked() = assertEquals("speaking", state(speaking = true, thinking = true))

    @Test fun silenceIsZeroAndLoudIsOne() {
        assertEquals(0f, voiceLevel(0f))
        assertEquals(0f, voiceLevel(0.001f))            // −60 dBFS
        assertEquals(1f, voiceLevel(1f))                // 0 dBFS
        assertEquals(0.5f, voiceLevel(0.031622775f), 0.001f) // −30 dBFS, halfway between −50 and −10
    }

    private data class A(val id: String, val default: Boolean = false)
    private fun pick(agents: List<A>, chosen: String?) = pickOverlayAgent(agents, chosen, { it.id }, { it.default })?.id

    @Test fun theFirstTimeItShowsTheDefaultAgent() = assertEquals("dockie", pick(listOf(A("tally"), A("dockie", true)), null))
    @Test fun aPickedAgentIsShown() = assertEquals("tally", pick(listOf(A("tally"), A("dockie", true)), "tally"))
    @Test fun aPickedAgentThatIsGoneFallsBackToTheDefault() = assertEquals("dockie", pick(listOf(A("tally"), A("dockie", true)), "atlas"))
    @Test fun noDefaultMeansTheFirstAgent() = assertEquals("tally", pick(listOf(A("tally"), A("atlas")), null))
    @Test fun noAgentsNoOverlayAgent() = assertEquals(null, pick(emptyList(), "tally"))

    @Test fun pickingTheDefaultForgetsTheChoice() {
        assertEquals(null, overlayChoiceToStore("dockie", defaultId = "dockie"))
        assertEquals("tally", overlayChoiceToStore("tally", defaultId = "dockie"))
        assertEquals("tally", overlayChoiceToStore("tally", defaultId = null))
    }

    @Test fun aScreenshotShrinksToItsLongSide() {
        assertEquals(686 to 1600, shareImageSize(1080, 2520, 1600))  // the Fold's cover screen
        assertEquals(1600 to 1442, shareImageSize(2184, 1968, 1600)) // and its inner one
    }

    @Test fun aSmallScreenshotStaysAsItIs() = assertEquals(720 to 1280, shareImageSize(720, 1280, 1600))

    @Test fun theShownLevelRisesFastAndFallsSlowly() {
        val up = smoothLevel(0f, 1f, 30f)
        val down = 1f - smoothLevel(1f, 0f, 30f)
        assertEquals(0.632f, up, 0.01f)   // one attack constant: most of the way up
        assertEquals(0.181f, down, 0.01f) // the same time falling: barely down
        assertEquals(0.5f, smoothLevel(0.5f, 0.5f, 16f), 0.0001f)
        assertEquals(0.2f, smoothLevel(0.2f, 0.9f, 0f), 0.0001f) // no time, no change
    }

    @Test fun aTypedChunkOpensTheMouthByItsVowels() {
        assertEquals(0.1f, speechLevel(" , "))                   // a breath
        assertEquals(0.35f, speechLevel("str"), 0.001f)          // consonants: nearly shut
        assertEquals(0.95f, speechLevel("aei"), 0.001f)          // all vowels: wide open
        assertEquals(0.55f, speechLevel("Gün"), 0.001f)          // Turkish vowels count too: ü
    }

    // Glasses on, its eyes read your screen: toward the middle, along a line, then the next line down.
    @Test fun readingLooksTowardTheMiddleOfTheScreen() {
        for (t in 0L until 7_000L step 100) {
            val (x, y) = readingGaze(t, towardMiddle = 1)
            assertTrue(x in 0f..1f && y in -1f..1f, "at $t ms: $x, $y")
            assertTrue(readingGaze(t, towardMiddle = -1).first <= 0f, "at $t ms, from the right edge")
        }
    }

    @Test fun readingMovesAlongALineThenDownALine() {
        val start = readingGaze(0, 1)
        val later = readingGaze(1_500, 1)
        val nextLine = readingGaze(1_750, 1)
        assertTrue(later.first > start.first)
        assertEquals(start.second, later.second)
        assertTrue(nextLine.second > later.second)
    }

    // A tap only waits for a second one when a double-tap could put the glasses on.
    @Test fun aDoubleTapIsWaitedForOnlyWhenItCanPutGlassesOn() {
        assertTrue(doubleTapWanted(screenAllowed = true, chatOpen = false, asking = false, talking = false))
        assertFalse(doubleTapWanted(screenAllowed = false, chatOpen = false, asking = false, talking = false))
        assertFalse(doubleTapWanted(screenAllowed = true, chatOpen = true, asking = false, talking = false))
        assertFalse(doubleTapWanted(screenAllowed = true, chatOpen = false, asking = true, talking = false))
        assertFalse(doubleTapWanted(screenAllowed = true, chatOpen = false, asking = false, talking = true))
    }
}
