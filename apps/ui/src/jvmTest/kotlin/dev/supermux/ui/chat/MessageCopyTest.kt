package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.LogEntry
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals

/** An agent reply's copy button puts the reply on the clipboard through the app's [dev.supermux.ui.platform.Platform]. */
@OptIn(ExperimentalTestApi::class)
class MessageCopyTest {
    @Test fun a_replys_copy_button_copies_its_text_through_the_platform() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform = platform) {
            TimelineItemRow(
                TimelineItem.Msg(LogEntry(id = "m1", ts = "2026-01-01T00:00:01Z", direction = "outbound", text = "Here is the plan.")),
            )
        }
        onNodeWithTag("message_copy").performClick()
        waitForIdle()
        assertEquals(listOf("Here is the plan."), platform.copied)
    }
}
