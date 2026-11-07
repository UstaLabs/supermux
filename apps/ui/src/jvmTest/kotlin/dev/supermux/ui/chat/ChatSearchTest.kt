package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.supermux.chat.TimelineItem
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.net.MessageSearchHit
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.ShortcutDispatcher
import dev.supermux.ui.keys.ShortcutRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatSearchTest {

    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")
    private val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft

    private fun msg(id: String, text: String, direction: String = "outbound") =
        LogEntry(id = id, ts = "2026-10-12T10:00:00.000Z", direction = direction, text = text)

    @Test fun matches_are_message_rows_containing_the_query_case_insensitively() {
        val items = listOf(
            TimelineItem.Msg(msg("a", "Deploy the broker")),
            TimelineItem.Msg(msg("b", "nothing here")),
            TimelineItem.Msg(msg("c", "redeploy done")),
        )
        assertEquals(listOf(0, 2), chatFindMatches(items, "DEPLOY"))
        assertEquals(emptyList(), chatFindMatches(items, "  "))
    }

    @Test fun count_label() {
        assertEquals("", findCountLabel("", 0, -1))
        assertEquals("No results", findCountLabel("x", 0, -1))
        assertEquals("3/7", findCountLabel("x", 7, 2))
    }

    @Test fun every_occurrence_is_styled() {
        val text = highlightMatches("Ab ab AB", "ab", Color.Red)
        assertEquals("Ab ab AB", text.text)
        assertEquals(3, text.spanStyles.size)
    }

    @Test fun find_in_chat_counts_hits_and_steps_from_the_newest() = runComposeUiTest {
        setPlatformContent {
            ChatPanel(
                session = session,
                state = ChatState(messages = listOf(msg("m1", "alpha one"), msg("m2", "beta"), msg("m3", "alpha two"))),
                actions = ChatActions(),
                draft = "",
                onDraftChange = {},
            )
        }
        onNodeWithTag("chat_find_open").performClick()
        onNodeWithTag(CHAT_FIND_FIELD_TAG).performTextInput("ALPHA")
        waitForIdle()
        onNodeWithTag(CHAT_FIND_COUNT_TAG).assertTextEquals("2/2")
        onNodeWithTag("chat_find_prev").performClick()
        onNodeWithTag(CHAT_FIND_COUNT_TAG).assertTextEquals("1/2")
        onNodeWithTag("chat_find_prev").performClick()
        onNodeWithTag(CHAT_FIND_COUNT_TAG).assertTextEquals("2/2") // wraps
        onNodeWithTag("chat_find_close").performClick()
        onNodeWithTag(CHAT_FIND_TAG).assertDoesNotExist()
    }

    @Test fun an_all_chats_hit_opens_its_chat_on_the_message() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val hit = MessageSearchHit(session_id = "s1", session_name = "demo", message_id = "m1", snippet = "alpha one")
        val host = ChatSearchHost(searchAll = { listOf(hit) }, openSession = { opened += it })
        setPlatformContent {
            CompositionLocalProvider(LocalChatSearch provides host) {
                ChatPanel(
                    session = session,
                    state = ChatState(messages = listOf(msg("m1", "alpha one"), msg("m2", "beta"), msg("m3", "alpha two"))),
                    actions = ChatActions(),
                    draft = "",
                    onDraftChange = {},
                )
            }
        }
        onNodeWithTag("chat_find_open").performClick()
        onNodeWithTag("chat_find_scope_all").performClick()
        onNodeWithTag(CHAT_FIND_FIELD_TAG).performTextInput("alpha")
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithTag("chat_search_hit:m1").assertIsDisplayed().performClick()
        waitForIdle()
        runOnIdle {
            assertEquals(listOf("s1"), opened)
            assertNull(host.jump.value) // this panel took it
        }
        // Back in This chat, on the picked (older) hit rather than the newest.
        onNodeWithTag(CHAT_FIND_COUNT_TAG).assertTextEquals("1/2")
    }

    /** A host with no ShortcutRoot (the assistant overlay's own window) has no dispatcher: the chat takes its own find key (#96). */
    @Test fun without_a_shortcut_root_the_find_key_still_opens_the_find_bar() = runComposeUiTest {
        setPlatformContent {
            ChatPanel(
                session = session,
                state = ChatState(messages = listOf(msg("m1", "alpha one"))),
                actions = ChatActions(),
                draft = "",
                onDraftChange = {},
                showHeader = false, // as the overlay shows it
            )
        }
        onNodeWithTag("composer-input").performClick()
        waitForIdle()
        onNodeWithTag(CHAT_FIND_FIELD_TAG).assertDoesNotExist()
        onNodeWithTag("composer-input").performKeyInput { withKeyDown(mod) { pressKey(Key.F) } }
        waitForIdle()
        onNodeWithTag(CHAT_FIND_FIELD_TAG).assertIsDisplayed()
    }

    /** Kept composed under another panel (Android's chat tabs), a chat registers no find: the key is the shown panel's (#96). */
    @Test fun a_hidden_chat_registers_no_find() = runComposeUiTest {
        val dispatcher = ShortcutDispatcher()
        var active by mutableStateOf(false)
        setPlatformContent {
            ShortcutRoot(dispatcher) {
                ChatPanel(
                    session = session,
                    state = ChatState(messages = listOf(msg("m1", "alpha one"))),
                    actions = ChatActions(),
                    draft = "",
                    onDraftChange = {},
                    active = active,
                )
            }
        }
        waitForIdle()
        runOnIdle { assertFalse(dispatcher.canInvoke(AppCommands.FindInChat)) }
        runOnIdle { active = true }
        waitForIdle()
        runOnIdle { assertTrue(dispatcher.canInvoke(AppCommands.FindInChat)) }
    }
}
