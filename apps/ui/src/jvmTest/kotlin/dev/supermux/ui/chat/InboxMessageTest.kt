package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import dev.supermux.proto.ServerFrame
import androidx.compose.ui.semantics.getOrNull
import dev.supermux.chat.TimelineItem
import dev.supermux.net.MessageSearchHit
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.TestIds
import dev.supermux.ui.session.sessionRowPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decision #19: a message one session sends another is not the user's. It is a card on the left
 * headed by the sender; supermux's own notices are a centred line; error replies look like errors;
 * the walkthrough card shows whatever the row's direction.
 */
@OptIn(ExperimentalTestApi::class)
class InboxMessageTest {

    private fun inbox(
        id: String,
        text: String,
        from: String = "s-4",
        name: String? = "personal-4",
        agent: String? = "claude",
    ) = LogEntry(
        id = id, ts = "2026-10-29T20:51:44Z", direction = "inbound", channel = "inbox", chat_id = "",
        text = text, from_session_id = from, from_session_name = name, from_agent = agent,
    )

    private val notice = LogEntry(
        id = "in:inbox:m2", ts = "2026-10-29T20:52:00Z", direction = "inbound", channel = "inbox",
        from_session_id = "supermux", from_session_name = "supermux",
        text = "Your subagent [Grok Search Test:6b084ccf-107c-4078-9ba9-bdef08de7949] stopped with an error:\n" +
            "API error (status 402 Payment Required): Grok Build usage balance exhausted",
    )

    private val council = LogEntry(
        id = "in:inbox:m3", ts = "2026-10-29T20:53:00Z", direction = "inbound", channel = "inbox",
        text = "council-1 · round 1 · B — Grok [council-1 B:7f3a2c41-0d9e-4b8a-9c11-52e0b7d1a6f3]:\nYes, with rotation.",
        council_id = "c-1", council_round = 1, council_letter = "B", from_session_id = "c-1", from_session_name = "council-1",
    )

    private fun headerText(test: androidx.compose.ui.test.ComposeUiTest, id: String): String =
        test.onNodeWithTag("inbox-sender:$id").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    @Test fun a_message_from_another_session_is_a_card_naming_the_sender_not_the_users_bubble() = runComposeUiTest {
        setPlatformContent {
            TimelineItemRow(TimelineItem.Msg(inbox("in:inbox:m1", "Hi from personal-4")))
        }
        onNodeWithTag("inbox-message:in:inbox:m1").assertExists()
        onNodeWithTag("inbox-sender:in:inbox:m1").assertTextContains("personal-4")
        onNode(hasText("Hi from personal-4", substring = true) and hasAnyAncestor(hasTestTag("inbox-message:in:inbox:m1"))).assertExists()
        // On the left, like the agent's replies (a user bubble hugs the right edge).
        val row = onNodeWithTag(TestIds.chatMessage("inbound", "in:inbox:m1")).fetchSemanticsNode().boundsInRoot
        val card = onNodeWithTag("inbox-message:in:inbox:m1").fetchSemanticsNode().boundsInRoot
        assertEquals(row.left, card.left, 1f)
        assertTrue(card.right < row.right - 1f, "the card leaves the right side free: $card in $row")
    }

    @Test fun the_header_opens_the_childs_card_when_the_sender_is_this_chats_subagent() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val ui = SubagentUi(
            isExpanded = { false },
            onToggle = {},
            actions = SubagentActions(),
            open = { opened += it },
            nameOf = { if (it == "s-4") "Grok Search Test" else null },
        )
        setPlatformContent {
            TimelineItemRow(TimelineItem.Msg(inbox("in:inbox:m1", "Done.", name = null, agent = "grok")), subagentUi = ui)
        }
        // No stored name (an older row): the child's own name stands in.
        onNodeWithTag("inbox-sender:in:inbox:m1").assertTextContains("Grok Search Test").performClick()
        waitForIdle()
        assertEquals(listOf("s-4"), opened)
    }

    @Test fun the_header_opens_a_listed_senders_chat_and_is_not_a_link_otherwise() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val host = ChatSearchHost(searchAll = { emptyList() }, openSession = { opened += it })
        setPlatformContent {
            CompositionLocalProvider(LocalChatSearch provides host) {
                Column {
                    TimelineItemRow(TimelineItem.Msg(inbox("in:inbox:m1", "Hi")), canOpenChat = { it == "s-4" })
                    TimelineItemRow(TimelineItem.Msg(inbox("in:inbox:m5", "Hidden child here", from = "s-hidden", name = "child")), canOpenChat = { it == "s-4" })
                }
            }
        }
        onNodeWithTag("inbox-sender:in:inbox:m5").assertHasNoClickAction()
        onNodeWithTag("inbox-sender:in:inbox:m1").assertHasClickAction().performClick()
        waitForIdle()
        assertEquals(listOf("s-4"), opened)
    }

    @Test fun a_chat_panel_opens_the_senders_chat_when_its_host_lists_it() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val host = ChatSearchHost(searchAll = { emptyList() }, openSession = { opened += it })
        setPlatformContent {
            CompositionLocalProvider(LocalChatSearch provides host) {
                ChatPanel(
                    session = SessionInfo(id = "s-3", name = "personal-3", workdir = "/home/u/proj", agent = "claude"),
                    state = ChatState(messages = listOf(inbox("in:inbox:m1", "Hi from personal-4")), listedSessions = setOf("s-3", "s-4")),
                    actions = ChatActions(),
                    draft = "",
                    onDraftChange = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("inbox-sender:in:inbox:m1").performClick()
        waitForIdle()
        assertEquals(listOf("s-4"), opened)
    }

    @Test fun on_touch_the_header_is_a_48dp_target_with_a_spoken_action() = runComposeUiTest {
        val ui = SubagentUi(isExpanded = { false }, onToggle = {}, actions = SubagentActions(), nameOf = { if (it == "s-4") "Child" else null })
        val host = ChatSearchHost(searchAll = { emptyList() }, openSession = {})
        var density = 1f
        setPlatformContent(pointer = false, widthClass = dev.supermux.ui.adaptive.WindowWidthClass.Compact) {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            CompositionLocalProvider(LocalChatSearch provides host) {
                Column {
                    TimelineItemRow(TimelineItem.Msg(inbox("in:inbox:c", "From the child")), subagentUi = ui)
                    TimelineItemRow(TimelineItem.Msg(inbox("in:inbox:o", "From a chat", from = "s-5")), canOpenChat = { it == "s-5" })
                }
            }
        }
        for (id in listOf("in:inbox:c", "in:inbox:o")) {
            val height = onNodeWithTag("inbox-sender:$id").fetchSemanticsNode().boundsInRoot.height / density
            assertTrue(height >= 48f - 0.5f, "the header of $id is a ${height}dp target")
        }
        assertEquals("Open subagent", onNodeWithTag("inbox-sender:in:inbox:c").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        assertEquals("Open chat", onNodeWithTag("inbox-sender:in:inbox:o").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    @Test fun the_apps_chat_state_lists_the_hosts_sessions_so_a_sender_opens() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val search = ChatSearchHost(searchAll = { emptyList() }, openSession = { opened += it })
        val app = testHostStore()
        val me = SessionInfo(id = "s-3", name = "personal-3", workdir = "/w", agent = "claude")
        app.reduce(ServerFrame.SessionAdded(me))
        app.reduce(ServerFrame.SessionAdded(SessionInfo(id = "s-4", name = "personal-4", workdir = "/w", agent = "claude")))
        app.reduce(ServerFrame.MessageAppend(session = "s-3", entry = inbox("in:inbox:m1", "Hi from personal-4")))
        app.reduce(ServerFrame.MessageAppend(session = "s-3", entry = inbox("in:inbox:m9", "From a hidden child", from = "s-hidden", name = "child")))
        setPlatformContent {
            CompositionLocalProvider(LocalChatSearch provides search) {
                ChatPanel(session = me, state = rememberChatState(app, me.id), actions = ChatActions(), draft = "", onDraftChange = {})
            }
        }
        waitForIdle()
        onNodeWithTag("inbox-sender:in:inbox:m9").assertHasNoClickAction()
        onNodeWithTag("inbox-sender:in:inbox:m1").performClick()
        waitForIdle()
        assertEquals(listOf("s-4"), opened)
    }

    @Test fun a_supermux_notice_is_a_centred_line_without_ids() = runComposeUiTest {
        setPlatformContent {
            TimelineItemRow(TimelineItem.Msg(notice))
        }
        onNodeWithTag("supermux-notice:in:inbox:m2").assertExists()
        onNode(hasText("Grok Search Test", substring = true) and hasAnyAncestor(hasTestTag("supermux-notice:in:inbox:m2"))).assertExists()
        onAllNodesWithText("6b084ccf", substring = true).assertCountEquals(0)
        onNodeWithTag("inbox-message:in:inbox:m2").assertDoesNotExist()
        // Centred: as much room either side.
        val row = onNodeWithTag(TestIds.chatMessage("inbound", "in:inbox:m2")).fetchSemanticsNode().boundsInRoot
        val pill = onNodeWithTag("supermux-notice:in:inbox:m2").fetchSemanticsNode().boundsInRoot
        assertEquals(pill.left - row.left, row.right - pill.right, 1.5f)
    }

    @Test fun a_council_report_is_headed_by_the_council_round_and_letter() = runComposeUiTest {
        setPlatformContent {
            TimelineItemRow(TimelineItem.Msg(council))
        }
        onNodeWithTag("inbox-sender:in:inbox:m3").assertTextContains("council-1 · round 1 · B — Grok")
        assertFalse("[council-1 B:7f3a2c41-0d9e-4b8a-9c11-52e0b7d1a6f3]" in headerText(this, "in:inbox:m3"))
        onNode(hasText("Yes, with rotation.", substring = true) and hasAnyAncestor(hasTestTag("inbox-message:in:inbox:m3"))).assertExists()
        onNodeWithTag("inbox-sender:in:inbox:m3").assertHasNoClickAction()
    }

    @Test fun an_error_reply_has_the_error_look() = runComposeUiTest {
        setPlatformContent {
            Column {
                TimelineItemRow(TimelineItem.Msg(LogEntry(id = "out:1", ts = "2026-10-29T20:54:00Z", direction = "outbound", op = "reply", text = "error: boom", error = true)))
                TimelineItemRow(TimelineItem.Msg(LogEntry(id = "out:2", ts = "2026-10-29T20:54:01Z", direction = "outbound", op = "reply", text = "fine")))
            }
        }
        onNodeWithTag("agent-error:out:1").assertExists()
        onNode(hasText("error: boom", substring = true) and hasAnyAncestor(hasTestTag("agent-error:out:1"))).assertExists()
        onNodeWithTag("agent-error:out:2").assertDoesNotExist()
    }

    @Test fun the_walkthrough_card_shows_for_an_inbound_notice() = runComposeUiTest {
        setPlatformContent {
            TimelineItemRow(TimelineItem.Msg(LogEntry(id = "in:w1", ts = "2026-10-29T20:55:00Z", direction = "inbound", text = "📖 Walkthrough ready — Auth flow (3 steps)")))
        }
        onNodeWithTag("walkthrough_ready_card").assertExists()
    }

    @Test fun notice_text_hides_ids_and_council_headers_split() {
        assertEquals("Your subagent Grok Search Test stopped with an error:", noticePlainText("Your subagent [Grok Search Test:6b084ccf-107c-4078-9ba9-bdef08de7949] stopped with an error:"))
        assertEquals("council-1 · round 1 · B — Grok" to "Yes.", councilHeadAndBody("council-1 · round 1 · B — Grok [council-1 B:7f3a2c41-0d9e-4b8a-9c11-52e0b7d1a6f3]:\nYes."))
        assertEquals("council-1 · round 2 · A — Claude" to "", councilHeadAndBody("council-1 · round 2 · A — Claude:"))
        // A name may hold colons, dashes and spaces; the id is a session's UUID (or "supermux").
        assertEquals("Your subagent Fix: login bug crashed.", noticePlainText("Your subagent [Fix: login bug:7f3a2c41-0d9e-4b8a-9c11-52e0b7d1a6f3] crashed."))
        assertEquals("council-1 AA stopped", noticePlainText("[council-1 AA:7f3a2c41-0d9e-4b8a-9c11-52e0b7d1a6f3] stopped"))
        assertEquals("from supermux", noticePlainText("from [supermux:supermux]"))
        // Not tokens: a markdown link, a time, a short tag.
        assertEquals("See [docs: setup](https://example.com/a)", noticePlainText("See [docs: setup](https://example.com/a)"))
        assertEquals("[12:30] standup", noticePlainText("[12:30] standup"))
        assertEquals("[note:todo]", noticePlainText("[note:todo]"))
        assertEquals("Fix: login bug" to "Body", councilHeadAndBody("Fix: login bug [council-1 C:7f3a2c41-0d9e-4b8a-9c11-52e0b7d1a6f3]:\nBody"))
        assertTrue(isAlarmingNotice("Your subagent x crashed."))
        assertFalse(isAlarmingNotice("Your subagent x was killed."))
        assertTrue(isAlarmingNotice("council-1 · C couldn't start:\nopencode exited at once"))
        assertFalse(isAlarmingNotice("council-1 · B [x:y] was stopped by the user."))
        // Four lines at most, and a cut shows: Text draws no "…" when whole lines after a break are dropped.
        assertEquals("a crashed.\nIts last message:\none\ntwo…", noticeLines("a crashed.\nIts last message:\none\ntwo\nthree"))
        assertEquals("a crashed.\nIt sent no message.", noticeLines("a crashed.\nIt sent no message.\n"))
    }

    @Test fun search_and_the_sidebar_name_an_inbox_messages_sender_instead_of_you() {
        val base = MessageSearchHit(session_id = "s-3", message_id = "m", snippet = "Hi there")
        assertEquals("personal-4: Hi there", chatSearchHitText(base.copy(direction = "inbound", channel = "inbox", from_session_name = "personal-4")))
        assertEquals("Another session: Hi there", chatSearchHitText(base.copy(direction = "inbound", channel = "inbox")))
        assertEquals("You: Hi there", chatSearchHitText(base.copy(direction = "inbound", channel = "web")))
        assertEquals("Hi there", chatSearchHitText(base.copy(direction = "outbound")))
        assertEquals("supermux: Your subagent Grok Search Test crashed.", sessionRowPreview(notice.copy(text = "Your subagent [Grok Search Test:6b084ccf-107c-4078-9ba9-bdef08de7949] crashed.")))
        // A row logged before the broker stored names: supermux's own still reads "supermux".
        assertEquals("supermux: Your subagent X crashed.", sessionRowPreview(notice.copy(from_session_name = null, text = "Your subagent [X:7f3a2c41-0d9e-4b8a-9c11-52e0b7d1a6f3] crashed.")))
        assertEquals("personal-4: Hi from personal-4", sessionRowPreview(inbox("in:inbox:m1", "Hi from **personal-4**")))
        // A council report reads as its header, not "council-1: council-1 · round 1 …".
        assertEquals("council-1 · round 1 · B — Grok: Yes, with rotation.", sessionRowPreview(council))
        assertEquals("plain reply", sessionRowPreview(LogEntry(id = "o", ts = "t", direction = "outbound", text = "plain reply")))
        assertEquals("You wrote this", sessionRowPreview(LogEntry(id = "i", ts = "t", direction = "inbound", channel = "web", text = "You wrote this")))
        assertEquals(null, sessionRowPreview(null))
        assertEquals(80, sessionRowPreview(inbox("in:inbox:long", "word ".repeat(5_000)))!!.length)
    }
    @Test fun a_long_message_shows_three_lines_and_expands_on_show_more() = runComposeUiTest {
        val long = (1..8).joinToString("\n\n") { "Paragraph $it of a long report from another session." }
        val councilLong = council.copy(id = "in:inbox:m6", text = council.text!!.substringBefore('\n') + "\n" + long)
        setPlatformContent {
            Column {
                TimelineItemRow(TimelineItem.Msg(inbox("in:inbox:m1", long)))
                TimelineItemRow(TimelineItem.Msg(inbox("in:inbox:m2", "Just one line.")))
                TimelineItemRow(TimelineItem.Msg(councilLong))
            }
        }
        // A short message looks as it always did: no button.
        onNodeWithTag("inbox-expand:in:inbox:m2").assertDoesNotExist()
        // A council report is the same card, so it folds too.
        onNodeWithTag("inbox-expand:in:inbox:m6").assertTextContains("Show more")
        val collapsed = onNodeWithTag("inbox-message:in:inbox:m1").fetchSemanticsNode().boundsInRoot.height
        onNodeWithTag("inbox-expand:in:inbox:m1").assertTextContains("Show more").performClick()
        waitForIdle()
        val expanded = onNodeWithTag("inbox-message:in:inbox:m1").fetchSemanticsNode().boundsInRoot.height
        assertTrue(expanded > collapsed + 50f, "expanding shows the rest: $collapsed -> $expanded")
        onNodeWithTag("inbox-expand:in:inbox:m1").assertTextContains("Show less").performClick()
        waitForIdle()
        assertEquals(collapsed, onNodeWithTag("inbox-message:in:inbox:m1").fetchSemanticsNode().boundsInRoot.height, 1f)
        // The single-line card is no taller than header + one line + padding.
        val short = onNodeWithTag("inbox-message:in:inbox:m2").fetchSemanticsNode().boundsInRoot.height
        assertTrue(collapsed > short, "the collapsed card still shows three lines: $collapsed vs $short")
    }
}
