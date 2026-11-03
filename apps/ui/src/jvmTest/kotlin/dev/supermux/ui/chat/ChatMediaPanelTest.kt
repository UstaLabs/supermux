package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import kotlin.test.Test

/**
 * The viewer's set is the whole chat, not the tapped message: in the real [ChatPanel], a picture in
 * one message opens a viewer that steps on into the next message's media, and an agent reply's
 * `![](…)` host picture is part of the same set.
 */
@OptIn(ExperimentalTestApi::class)
class ChatMediaPanelTest {

    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/repo", agent = "claude")

    private fun photo(id: String) = Attachment(file_id = id, kind = "photo", mime = "image/png", name = "$id.png")

    private fun ComposeUiTest.counter() = onNodeWithTag("chat_media_viewer_counter", useUnmergedTree = true)

    @Test fun the_viewer_steps_across_messages() = runComposeUiTest {
        val messages = listOf(
            LogEntry(id = "in:1", ts = "2026-11-02T10:00:01Z", direction = "inbound", text = "one", attachments = listOf(photo("a"))),
            LogEntry(id = "out:1", ts = "2026-11-02T10:00:02Z", direction = "outbound", text = "two", attachments = listOf(photo("b"), photo("c"))),
        )
        setPlatformContent {
            ChatPanel(
                session = session,
                state = ChatState(messages = messages),
                actions = ChatActions(media = AttachmentMedia.fromBytes { TINY_PNG_BYTES }),
                draft = "",
                onDraftChange = {},
                showHeader = true,
            )
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("attachment_image").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("attachment_image").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("chat_media_viewer").fetchSemanticsNodes().isNotEmpty() }
        counter().assertTextEquals("1 / 3")
        onNodeWithTag("chat_media_viewer").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        counter().assertTextEquals("2 / 3")
    }

    @Test fun a_replys_markdown_picture_is_in_the_same_set() = runComposeUiTest {
        val messages = listOf(
            LogEntry(id = "out:1", ts = "2026-11-02T10:00:01Z", direction = "outbound", text = "Done:\n\n![shot](shots/a.png)"),
            LogEntry(id = "in:1", ts = "2026-11-02T10:00:02Z", direction = "inbound", text = "and mine", attachments = listOf(photo("b"))),
        )
        val files = MarkdownFiles("/repo") { Result.success(TINY_PNG_BYTES) }
        setPlatformContent {
            CompositionLocalProvider(LocalMarkdownFiles provides files) {
                ChatPanel(
                    session = session,
                    state = ChatState(messages = messages),
                    actions = ChatActions(media = AttachmentMedia.fromBytes { TINY_PNG_BYTES }),
                    draft = "",
                    onDraftChange = {},
                    showHeader = true,
                )
            }
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("md_image").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("md_image").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("chat_media_viewer").fetchSemanticsNodes().isNotEmpty() }
        counter().assertTextEquals("1 / 2")
        onNodeWithTag("chat_media_viewer").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        counter().assertTextEquals("2 / 2")
    }
}
