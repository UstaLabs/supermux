package dev.supermux.ui.chat

import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.CouncilMember
import dev.supermux.proto.CouncilReport
import dev.supermux.proto.CouncilRound
import dev.supermux.proto.CouncilView
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.TestIds
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec §5.1, variant A: a council is one card in its caller's chat, a row per member. */
@OptIn(ExperimentalTestApi::class)
class CouncilCardTest {
    private val council = CouncilView(
        id = "c-3",
        name = "council-3",
        question = "Parakeet or Whisper Small on-device for Turkish?",
        round = 2,
        focus = "background noise",
        callId = "toolu_council",
        createdAt = "2026-10-30T12:30:00.000Z",
        members = listOf(
            CouncilMember(letter = "A", session = "ma", name = "council-3 A", agent = "codex", label = "GPT-6", status = "reported"),
            CouncilMember(letter = "B", session = "mb", name = "council-3 B", agent = "grok", label = "Grok 4.7", status = "working"),
            CouncilMember(letter = "C", session = "mc", name = "council-3 C", agent = "antigravity", label = "Gemini 4 Argon", status = "failed"),
            CouncilMember(letter = "D", session = "md", name = "council-3 D", agent = "claude", label = "Claude", status = "killed"),
        ),
        rounds = listOf(
            CouncilRound(
                round = 1, startedAt = "2026-10-30T12:30:00.000Z", completedAt = "2026-10-30T12:36:00.000Z",
                reports = listOf(
                    CouncilReport(id = "r1", letter = "A", seq = 1, text = "Whisper Small is the only phone-sized option.", at = "2026-10-30T12:33:00.000Z"),
                    CouncilReport(id = "r2", letter = "B", seq = 2, text = "Turkish on phones is weak across the board.", at = "2026-10-30T12:34:00.000Z"),
                ),
            ),
            CouncilRound(
                round = 2, focus = "background noise", startedAt = "2026-10-30T12:36:00.000Z",
                reports = listOf(CouncilReport(id = "r3", letter = "A", seq = 3, text = "With noise, add **VAD**.", at = "2026-10-30T12:40:00.000Z")),
            ),
        ),
    )

    private val card = TimelineItem.CouncilCard(council, failures = mapOf("C" to "API error (status 402 Payment Required)"))

    @Test fun the_card_shows_each_member_with_its_latest_answer_and_no_badges() = runComposeUiTest {
        setPlatformContent { CouncilCard(card) }
        onNode(hasText("Parakeet or Whisper Small", substring = true) and hasAnyAncestor(hasTestTag("council-card:c-3"))).assertExists()
        onNodeWithTag("council-headline:c-3").assertTextContains("council-3 · 1 of 4 answered")
        onNodeWithTag("council-preview:c-3:A", useUnmergedTree = true).assertTextContains("With noise, add VAD.")
        onNodeWithTag("council-preview:c-3:B", useUnmergedTree = true).assertTextContains("Working")
        onNodeWithTag("council-preview:c-3:C", useUnmergedTree = true).assertTextContains("402 Payment Required", substring = true)
        onNodeWithTag("council-preview:c-3:D", useUnmergedTree = true).assertTextContains("Stopped")
        onNodeWithTag("council-row:c-3:A").assertTextContains("Codex").assertTextContains("GPT-6")
        // No status pills.
        onNode(hasText("reported", substring = true)).assertDoesNotExist()
    }

    @Test fun a_working_member_shows_what_it_is_doing_right_now() = runComposeUiTest {
        setPlatformContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalCouncilActivity provides mapOf("mb" to "Searching the web for Parakeet Turkish WER")) {
                CouncilCard(card)
            }
        }
        onNodeWithTag("council-preview:c-3:B", useUnmergedTree = true).assertTextContains("Searching the web for Parakeet Turkish WER")
    }

    @Test fun tapping_the_question_shows_it_whole() = runComposeUiTest {
        val long = council.copy(question = (1..40).joinToString(" ") { "word$it" } + " the end?")
        setPlatformContent { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(300.dp)) { CouncilCard(TimelineItem.CouncilCard(long)) } }
        val folded = onNodeWithTag("council-question:c-3").fetchSemanticsNode().size.height
        onNodeWithTag("council-question:c-3").performClick()
        waitForIdle()
        val open = onNodeWithTag("council-question:c-3").fetchSemanticsNode().size.height
        kotlin.test.assertTrue(open > folded, "open $open should be taller than folded $folded")
        onNodeWithTag("council-question:c-3").performClick()
        waitForIdle()
        assertEquals(folded, onNodeWithTag("council-question:c-3").fetchSemanticsNode().size.height)
    }

    @Test fun a_member_idle_without_an_answer_says_so() = runComposeUiTest {
        setPlatformContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalCouncilActivity provides mapOf("mb" to "")) { CouncilCard(card) }
        }
        onNodeWithTag("council-preview:c-3:B", useUnmergedTree = true).assertTextContains("Idle, hasn't answered")
    }

    @Test fun a_failed_member_without_a_reason_says_failed() = runComposeUiTest {
        setPlatformContent { CouncilCard(TimelineItem.CouncilCard(council)) }
        onNodeWithTag("council-preview:c-3:C", useUnmergedTree = true).assertTextContains("Failed")
    }

    @Test fun tapping_a_row_opens_all_its_reports_round_by_round() = runComposeUiTest {
        setPlatformContent { CouncilCard(card) }
        onNodeWithTag("council-body:c-3:A").assertDoesNotExist()
        onNodeWithTag("council-row:c-3:A").performClick()
        waitForIdle()
        onNodeWithTag("council-body:c-3:A").assertExists()
        onNode(hasText("Round 1") and hasAnyAncestor(hasTestTag("council-body:c-3:A"))).assertExists()
        onNode(hasText("Round 2 · background noise") and hasAnyAncestor(hasTestTag("council-body:c-3:A"))).assertExists()
        onNode(hasText("Whisper Small is the only phone-sized option.", substring = true) and hasAnyAncestor(hasTestTag("council-body:c-3:A"))).assertExists()
        onNode(hasText("With noise, add", substring = true) and hasAnyAncestor(hasTestTag("council-body:c-3:A"))).assertExists()
        onNodeWithTag("council-row:c-3:A").performClick()
        waitForIdle()
        onNodeWithTag("council-body:c-3:A").assertDoesNotExist()
    }

    @Test fun a_round_chip_switches_the_rows_to_that_round() = runComposeUiTest {
        setPlatformContent { CouncilCard(card) }
        onNodeWithTag("council-chip:c-3:2").assertIsSelected()
        onNodeWithTag("council-chip:c-3:1").assertTextContains("Round 1")
        onNodeWithTag("council-chip:c-3:2").assertTextContains("Round 2 · background noise")
        onNodeWithTag("council-chip:c-3:1").performClick()
        waitForIdle()
        onNodeWithTag("council-chip:c-3:1").assertIsSelected()
        onNodeWithTag("council-preview:c-3:A", useUnmergedTree = true).assertTextContains("Whisper Small is the only phone-sized option.")
        onNodeWithTag("council-preview:c-3:B", useUnmergedTree = true).assertTextContains("Turkish on phones is weak across the board.")
        onNodeWithTag("council-preview:c-3:C", useUnmergedTree = true).assertTextContains("No answer in round 1")
    }

    @Test fun stop_asks_first_then_ends_the_council() = runComposeUiTest {
        val ended = mutableListOf<String>()
        setPlatformContent { CouncilCard(card, endCouncil = { ended += it }) }
        onNodeWithTag("council-stop:c-3").performClick()
        waitForIdle()
        assertEquals(emptyList(), ended)
        onNodeWithTag("council-stop-confirm:c-3").performClick()
        waitForIdle()
        assertEquals(listOf("c-3"), ended)
    }

    @Test fun no_stop_without_the_action_or_once_ended() = runComposeUiTest {
        setPlatformContent {
            androidx.compose.foundation.layout.Column {
                CouncilCard(card)
                CouncilCard(TimelineItem.CouncilCard(council.copy(id = "c-4", status = "ended", endedBy = "caller", verdict = "Whisper Small.")), endCouncil = {})
            }
        }
        onNodeWithTag("council-stop:c-3").assertDoesNotExist()
        onNodeWithTag("council-stop:c-4").assertDoesNotExist()
    }

    @Test fun an_ended_council_shows_its_verdict_or_who_ended_it() = runComposeUiTest {
        setPlatformContent {
            androidx.compose.foundation.layout.Column {
                CouncilCard(TimelineItem.CouncilCard(council.copy(id = "c-5", status = "ended", endedBy = "caller", verdict = "Whisper Small wins.")))
                CouncilCard(TimelineItem.CouncilCard(council.copy(id = "c-6", status = "ended", endedBy = "user")))
            }
        }
        onNodeWithTag("council-verdict:c-5").assertTextContains("Whisper Small wins.")
        onNodeWithTag("council-verdict:c-6").assertTextContains("Stopped by you")
        onNodeWithTag("council-verdict:c-3").assertDoesNotExist()
    }

    // ── in the chat ──

    private val me = SessionInfo(id = "s-1", name = "personal-1", workdir = "/home/u/proj", agent = "claude")
    private fun forwarded(id: String, councilId: String, text: String) = LogEntry(
        id = id, ts = "2026-10-30T12:40:00.000Z", direction = "inbound", channel = "inbox",
        text = "council-3 · round 2 · A — GPT-6 [council-3 A:11111111-1111-4111-8111-111111111111]:\n$text",
        from_session_id = councilId, from_session_name = "council-3", council_id = councilId, council_round = 2, council_letter = "A",
    )

    @Test fun in_the_chat_forwarded_reports_fold_into_the_card_and_unknown_ones_stay() = runComposeUiTest {
        val ask = LogEntry(id = "in:web:1", ts = "2026-10-30T12:29:00.000Z", direction = "inbound", channel = "web", text = "ask a council")
        val call = ActivityEvent(ts = "2026-10-30T12:30:00.000Z", kind = "tool", tool = "council", title = "council", phase = "started", callId = "toolu_council")
        setPlatformContent {
            ChatPanel(
                session = me,
                state = ChatState(
                    messages = listOf(ask, forwarded("in:inbox:k", "c-3", "Known report."), forwarded("in:inbox:u", "c-gone", "Orphan report.")),
                    activity = listOf(call),
                    councils = listOf(council),
                ),
                actions = ChatActions(),
                draft = "",
                onDraftChange = {},
            )
        }
        waitForIdle()
        onNodeWithTag("council-card:c-3").assertExists()
        onNodeWithTag("inbox-message:in:inbox:k").assertDoesNotExist()
        onNodeWithTag(TestIds.chatMessage("inbound", "in:inbox:k")).assertDoesNotExist()
        onNodeWithTag("inbox-message:in:inbox:u").assertExists()
    }
}
