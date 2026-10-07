package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.LogEntry
import dev.supermux.ui.TestIds
import kotlin.test.Test

/**
 * Every transcript row carries `chat-message:<direction>:<id>`.
 *
 * This is what the Playwright journeys select on: Compose-for-Web publishes a `testTag` as the DOM
 * element's `id`, so `[id^="chat-message:outbound:"]` is how a journey waits for the agent's reply
 * without knowing the id the broker minted. Without the tag the web journey can only assert on
 * text, which cannot tell the user's echo apart from a reply that quotes it.
 *
 * The tag sits on the row CONTAINER, so the message text is inside the tagged node — asserted here
 * by finding the text under the tagged subtree's own node hierarchy.
 */
@OptIn(ExperimentalTestApi::class)
class TimelineMessageTagTest {

    private fun entry(id: String, direction: String, text: String) =
        LogEntry(id = id, ts = "2026-01-01T00:00:01Z", direction = direction, text = text)

    @Test fun inbound_and_outbound_rows_each_carry_their_direction_tag() = runComposeUiTest {
        setPlatformContent {
            TimelineItemRow(TimelineItem.Msg(entry("m1", "inbound", "hello agent")))
            TimelineItemRow(TimelineItem.Msg(entry("m2", "outbound", "Fixture reply: hello agent")))
        }

        onNodeWithTag(TestIds.chatMessage("inbound", "m1")).assertIsDisplayed()
        onNodeWithTag(TestIds.chatMessage("outbound", "m2")).assertIsDisplayed()
        // The literal shape the web selector depends on.
        onNodeWithTag("chat-message:inbound:m1").assertIsDisplayed()
        onNodeWithTag("chat-message:outbound:m2").assertIsDisplayed()
        // Text lives INSIDE the tagged row, not beside it — the web assertion
        // `byTag(page, "chat-message:outbound:…").innerText()` depends on that nesting.
        onNode(
            hasText("Fixture reply: hello agent", substring = true) and
                hasAnyAncestor(hasTestTag(TestIds.chatMessage("outbound", "m2"))),
        ).assertExists()
    }
}
