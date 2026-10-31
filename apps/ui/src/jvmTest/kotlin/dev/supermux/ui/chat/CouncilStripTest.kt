package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.CouncilMember
import dev.supermux.proto.CouncilReport
import dev.supermux.proto.CouncilRound
import dev.supermux.proto.CouncilView
import kotlin.test.Test
import kotlin.test.assertEquals

/** A council while it runs sits over the composer like the running strip; a tap opens the card. */
@OptIn(ExperimentalTestApi::class)
class CouncilStripTest {
    private val council = CouncilView(
        id = "c-3",
        name = "council-3",
        question = "Parakeet or Whisper Small?",
        round = 1,
        callId = "x",
        createdAt = "2026-10-30T12:30:00.000Z",
        members = listOf(
            CouncilMember(letter = "A", session = "ma", name = "A", agent = "codex", label = "GPT-6", status = "reported"),
            CouncilMember(letter = "B", session = "mb", name = "B", agent = "claude", label = "Opus 5.5", status = "working"),
            CouncilMember(letter = "C", session = "mc", name = "C", agent = "grok", label = "Grok 4.7", status = "working"),
        ),
        rounds = listOf(
            CouncilRound(
                round = 1, startedAt = "2026-10-30T12:30:00.000Z",
                reports = listOf(CouncilReport(id = "r1", letter = "A", seq = 1, text = "Whisper **Small**.", at = "2026-10-30T12:33:00.000Z")),
            ),
        ),
    )

    @Test fun the_strip_seats_the_members_and_says_what_one_is_doing() = runComposeUiTest {
        setPlatformContent {
            CompositionLocalProvider(LocalCouncilActivity provides mapOf("mb" to "Searching the web", "mc" to "")) {
                CouncilStrips(listOf(TimelineItem.CouncilCard(council)))
            }
        }
        onNodeWithTag("council-strip-head:c-3").assertTextContains("council-3 · Round 1 · 1 of 3 answered")
        for (l in listOf("A", "B", "C")) onNodeWithTag("council-strip-avatar:c-3:$l", useUnmergedTree = true).assertExists()
        // C is idle without an answer: it says nothing, so B speaks.
        onNodeWithTag("council-strip-line:c-3", useUnmergedTree = true).assertTextContains("Claude: Searching the web")
        onNodeWithTag("council-row:c-3:A").assertDoesNotExist()
    }

    @Test fun a_tap_opens_the_council_card_and_another_folds_it() = runComposeUiTest {
        setPlatformContent { CouncilStrips(listOf(TimelineItem.CouncilCard(council))) }
        onNodeWithTag("council-strip-head:c-3").performClick()
        waitForIdle()
        onNodeWithTag("council-strip-body:c-3").assertExists()
        onNodeWithTag("council-row:c-3:A").assertExists()
        onNodeWithTag("council-strip-head:c-3").performClick()
        waitForIdle()
        onNodeWithTag("council-row:c-3:A").assertDoesNotExist()
    }

    @Test fun no_strip_once_no_member_works() = runComposeUiTest {
        val done = council.copy(members = council.members.map { it.copy(status = "reported") })
        setPlatformContent { CouncilStrips(listOf(TimelineItem.CouncilCard(done))) }
        onNodeWithTag("council-strip:c-3").assertDoesNotExist()
    }

    @Test fun the_lines_are_what_the_working_members_do_else_the_latest_answer() {
        assertEquals(listOf("Claude: Working", "Grok: Reading"), councilStripLines(council, mapOf("mc" to "Reading")))
        val quiet = council.copy(members = council.members.map { if (it.status == "working") it.copy(status = "reported") else it })
        assertEquals(listOf("Codex: Whisper Small."), councilStripLines(quiet, emptyMap()))
    }
}
