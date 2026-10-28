package dev.supermux.ui.session

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.MessageSearchHit
import dev.supermux.ui.chat.ChatSearchHost
import dev.supermux.ui.chat.LocalChatSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sidebar search: the header becomes a field, names rank first, then chat history. */
@OptIn(ExperimentalTestApi::class)
class SidebarSearchTest {

    private fun ws(id: String, name: String, sid: String) =
        workspaceDto(id = id, name = name, views = listOf(workspaceChatView("$id-v", sid, id)), primarySessionId = sid)

    @Test fun rank_prefers_a_name_prefix_then_a_word_then_anywhere_then_extras() {
        assertEquals(0, sidebarMatchRank("Sidebar search", "side"))
        assertEquals(1, sidebarMatchRank("Fix sidebar", "side"))
        assertEquals(2, sidebarMatchRank("Bedside", "side"))
        assertEquals(3, sidebarMatchRank("Fix it", "super", extras = listOf("supermux")))
        assertNull(sidebarMatchRank("Fix it", "side"))
        assertNull(sidebarMatchRank("anything", "  "))
    }

    @Test fun every_query_word_must_land_somewhere() {
        assertEquals(3, sidebarMatchRank("Sidebar polish", "super side", extras = listOf("supermux")))
        assertNull(sidebarMatchRank("Sidebar polish", "super side"))
    }

    @Test fun matches_keep_list_order_within_a_rank() {
        val list = listOf(ws("a", "Old docs", "s1"), ws("b", "docs", "s2"), ws("c", "Docs site", "s3"), ws("d", "x", "s4"))
        assertEquals(listOf("b", "c", "a"), matchWorkspaces(list, "docs").map { it.id })
    }

    @Test fun highlight_picks_out_the_query_or_else_each_word() {
        val c = Color.Red
        fun picked(text: String, q: String) = highlightSearchMatches(text, q, c).let { a ->
            a.spanStyles.map { a.text.substring(it.start, it.end) }
        }
        assertEquals(listOf("Side", "side"), picked("Sidebar bedside", "side"))
        assertEquals(listOf("super", "side"), picked("supermux sidebar", "super side"))
        assertEquals(emptyList(), picked("Docs", ""))
    }

    @Test fun history_keeps_the_newest_hit_per_chat_not_already_listed() {
        val hits = listOf(
            MessageSearchHit(session_id = "s1", message_id = "m1"),
            MessageSearchHit(session_id = "s2", message_id = "m2"),
            MessageSearchHit(session_id = "s2", message_id = "m3"),
        )
        assertEquals(listOf("m2"), historyHitsByChat(hits, shown = setOf("s1")).map { it.message_id })
    }

    @Test fun search_filters_by_name_then_lists_history_and_closes_on_escape() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val hit = MessageSearchHit(session_id = "s3", session_name = "Billing", message_id = "m9", snippet = "the deploy broke")
        val host = ChatSearchHost(searchAll = { listOf(hit) }, openSession = { opened += it })
        setContent {
            CompositionLocalProvider(LocalChatSearch provides host) {
                SessionListScreen(
                    workspaces = listOf(ws("w1", "Deploy script", "s1"), ws("w2", "Docs", "s2"), ws("w3", "Billing", "s3")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = { opened += it },
                )
            }
        }
        onNodeWithTag(SidebarSearchTestIds.OPEN).performClick()
        onNodeWithTag(SidebarSearchTestIds.FIELD).assertIsDisplayed().performTextInput("deploy")
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        // The name match first; Docs is filtered out; Billing comes back through its history.
        onNodeWithTag(WorkspaceListTestIds.row("w1")).assertIsDisplayed()
        onNodeWithTag(WorkspaceListTestIds.row("w2")).assertDoesNotExist()
        onNodeWithTag(WorkspaceListTestIds.row("w3")).assertDoesNotExist()
        onNodeWithTag("chat_search_hit:m9").assertIsDisplayed()

        // The cursor starts on the best match; Enter opens it.
        onNode(hasTestTag(SidebarSearchTestIds.CURSOR) and hasAnyDescendant(hasTestTag(WorkspaceListTestIds.row("w1"))))
            .assertExists()
        onNodeWithTag(SidebarSearchTestIds.FIELD).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(listOf("w1"), opened) }

        // ↓ walks on into the history hits (and stops at the last); Enter opens that chat.
        onNodeWithTag(SidebarSearchTestIds.FIELD).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        onNode(hasTestTag(SidebarSearchTestIds.CURSOR) and hasAnyDescendant(hasTestTag("chat_search_hit:m9")))
            .assertExists()
        onNodeWithTag(SidebarSearchTestIds.FIELD).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(listOf("w1", "s3"), opened) }

        // Esc puts the header and the whole list back.
        onNodeWithTag(SidebarSearchTestIds.FIELD).performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithTag(SidebarSearchTestIds.BAR).assertDoesNotExist()
        onNodeWithTag(SidebarSearchTestIds.OPEN).assertIsDisplayed()
        onNodeWithTag(WorkspaceListTestIds.row("w2")).assertIsDisplayed()
    }
}
