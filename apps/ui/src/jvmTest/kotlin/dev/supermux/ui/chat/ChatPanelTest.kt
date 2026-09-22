package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.TestIds
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The header-optional shared [ChatPanel] (cluster D4): desktop renders it WITH its header, both
 * Android surfaces render it without (their own identity bar sits above). The live agent state
 * follows the header — a header carries it on the status line, a headerless panel carries it in the
 * transcript's own working/sending/waiting rows (Android's shape).
 */
@OptIn(ExperimentalTestApi::class)
class ChatPanelTest {

    private val session =
        SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")

    @Test fun the_header_shows_the_breadcrumb_and_the_live_status_line() = runComposeUiTest {
        setPlatformContent {
            ChatPanel(
                session = session,
                state = ChatState(agent = AgentStatus(phase = "running", state = "working", working = true, detail = "running", tool = "Bash")),
                actions = ChatActions(),
                draft = "",
                onDraftChange = {},
                showHeader = true,
            )
        }
        waitForIdle()
        onNodeWithText("demo").assertIsDisplayed()
        onNodeWithText("proj").assertIsDisplayed()
        onNodeWithText("running Bash…").assertIsDisplayed()
        // With a header there is no duplicate status row in the transcript.
        onNodeWithTag("working_stop").assertDoesNotExist()
    }

    @Test fun a_headerless_panel_draws_no_header_and_moves_the_status_into_the_transcript() =
        runComposeUiTest {
            var interrupts = 0
            setPlatformContent(
                pointer = false,
                widthClass = WindowWidthClass.Compact,
                inputMode = InputMode.Touch,
            ) {
                ChatPanel(
                    session = session,
                    state = ChatState(
                        agent = AgentStatus(phase = "running", state = "working", working = true, detail = "running", tool = "Bash"),
                    ),
                    actions = ChatActions(interrupt = { interrupts++ }),
                    draft = "",
                    onDraftChange = {},
                    showHeader = false,
                )
            }
            waitForIdle()
            onNodeWithTag(TestIds.CHAT_VIEW).assertIsDisplayed()
            onNodeWithText("proj").assertDoesNotExist()
            onNodeWithText("running Bash…").assertDoesNotExist()
            onNodeWithTag("working_stop").assertIsDisplayed()
            onNodeWithTag("working_stop").performClick()
            runOnIdle { assertEquals(1, interrupts) }
        }

    @Test fun an_empty_session_offers_starter_prompts_that_send() = runComposeUiTest {
        val sent = mutableListOf<String>()
        setPlatformContent {
            ChatPanel(
                session = session,
                state = ChatState(),
                actions = ChatActions(send = { text, _ -> sent.add(text) }),
                draft = "",
                onDraftChange = {},
                showHeader = false,
            )
        }
        waitForIdle()
        onNodeWithTag("chat_starter_0").assertIsDisplayed()
        onNodeWithTag("chat_starter_0").performClick()
        runOnIdle { assertEquals(listOf("What's the current state?"), sent) }
    }

    @Test fun a_dead_agent_shows_the_banner_with_or_without_a_header() = runComposeUiTest {
        setPlatformContent {
            ChatPanel(
                session = session,
                state = ChatState(agent = AgentStatus(phase = "idle", state = "dead")),
                actions = ChatActions(),
                draft = "",
                onDraftChange = {},
                showHeader = false,
            )
        }
        waitForIdle()
        onNodeWithText("Not responding").assertIsDisplayed()
    }
}
