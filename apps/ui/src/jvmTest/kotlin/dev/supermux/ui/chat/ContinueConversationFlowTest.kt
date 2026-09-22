package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ModelInfo
import dev.supermux.proto.SessionInfo
import dev.supermux.state.ContinueHandoff
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared "Continue in new conversation" flow (cluster D4): Android's bottom sheet is now the
 * Compact branch and desktop's dialog the wider branch of ONE composable, with the pickers keyed on
 * `LocalPointerAvailable` (dropdown pills under a pointer, `PickerSheet`s under touch). Every test
 * tag both apps used (`overflow_continue*`) is preserved on both branches.
 */
@OptIn(ExperimentalTestApi::class)
class ContinueConversationFlowTest {

    private val session =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "claude", model = "opus")

    @Test fun the_pointer_branch_is_a_dialog_with_the_desktop_picker_pills() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            ContinueConversationFlow(
                session = session,
                onContinue = { null },
                onContinued = {},
                loadAgents = { listOf("claude", "codex") },
                loadModels = { listOf(ModelInfo(id = "opus", displayName = "Opus")) },
                loadReasoning = { _, _ -> null },
                onDismiss = {},
            )
        }
        waitForIdle()
        onNodeWithTag("continue_sheet").assertDoesNotExist()
        onNodeWithTag(ContinueTestIds.CONTINUE_PICKERS).assertIsDisplayed()
        onNodeWithTag(ContinueTestIds.CONTINUE_FIELD).assertIsDisplayed()
        onNodeWithTag(ContinueTestIds.CONTINUE_CONFIRM).assertIsDisplayed()
        // The desktop pill exposes its agent dropdown; the touch branch has no such node.
        onNodeWithTag(ContinueTestIds.CONTINUE_AGENT).performClick()
        waitForIdle()
        onNodeWithTag("${ContinueTestIds.CONTINUE_AGENT}_codex").assertIsDisplayed()
    }

    @Test fun the_compact_branch_is_a_bottom_sheet_with_the_same_tags() = runComposeUiTest {
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            ContinueConversationFlow(
                session = session,
                onContinue = { null },
                onContinued = {},
                loadAgents = { listOf("claude", "codex") },
                loadModels = { emptyList() },
                loadReasoning = { _, _ -> null },
                onDismiss = {},
            )
        }
        waitForIdle()
        onNodeWithTag("continue_sheet").assertIsDisplayed()
        onNodeWithTag(ContinueTestIds.CONTINUE_PICKERS).assertIsDisplayed()
        // The sheet scrolls, so the field and the Continue button exist below the fold in a
        // test-sized window rather than being on screen.
        onNodeWithTag(ContinueTestIds.CONTINUE_FIELD).assertExists()
        onNodeWithTag(ContinueTestIds.CONTINUE_CONFIRM).assertExists()
        // The pointer-only dropdown row must NOT be there — this branch opens a PickerSheet.
        onNodeWithTag("${ContinueTestIds.CONTINUE_AGENT}_codex").assertDoesNotExist()
    }

    @Test fun starting_hands_the_handoff_over_and_reports_the_new_session() = runComposeUiTest {
        var handoff: ContinueHandoff? = null
        var continued: String? = null
        var dismissed = false
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            ContinueConversationFlow(
                session = session,
                onContinue = { h -> handoff = h; "s2" },
                onContinued = { continued = it },
                loadAgents = { listOf("claude") },
                loadModels = { listOf(ModelInfo(id = "opus", displayName = "Opus")) },
                loadReasoning = { _, _ -> null },
                onDismiss = { dismissed = true },
            )
        }
        waitForIdle()
        onNodeWithTag(ContinueTestIds.CONTINUE_CONFIRM).performClick()
        waitForIdle()
        assertEquals("s2", continued)
        assertTrue(dismissed)
        // Same agent → the source session's model is carried over as the default (desktop's seed).
        assertEquals("claude", handoff?.agent)
        assertEquals("opus", handoff?.model)
        assertTrue(handoff?.message?.contains("demo") == true)
    }

    @Test fun a_failed_spawn_shows_the_error_and_keeps_the_flow_open() = runComposeUiTest {
        var dismissed = false
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            ContinueConversationFlow(
                session = session,
                onContinue = { null },
                onContinued = {},
                loadAgents = { listOf("claude") },
                loadModels = { emptyList() },
                loadReasoning = { _, _ -> null },
                onDismiss = { dismissed = true },
            )
        }
        waitForIdle()
        onNodeWithTag(ContinueTestIds.CONTINUE_CONFIRM).performClick()
        waitForIdle()
        onNodeWithTag(ContinueTestIds.CONTINUE_ERROR).assertIsDisplayed()
        runOnIdle { assertEquals(false, dismissed) }
    }
}
