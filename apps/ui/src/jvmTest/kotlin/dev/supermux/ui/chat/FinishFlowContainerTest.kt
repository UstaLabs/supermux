package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.FinishReadiness
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The CONTAINER half of the adaptive Finish flow (cluster D4): the SAME state machine is a
 * [androidx.compose.material3.ModalBottomSheet] on a Compact window (Android's phone shape, tagged
 * `finish_sheet`) and a dialog window anywhere else (desktop's shape). Also proves Android's
 * `onAck` fires on open on both.
 */
@OptIn(ExperimentalTestApi::class)
class FinishFlowContainerTest {

    private val session = SessionInfo(
        id = "s1", name = "feature", workdir = "/w", agent = "claude", session_branch = "feat/x",
    )
    private val readiness = FinishReadiness(
        branch = "feat/x", base = "main", ahead = 1, behind = 0, filesChanged = 1,
        insertions = 1, deletions = 0, hasRemote = true, ghAvailable = true, recommended = "merge",
    )

    private fun flow(acks: MutableList<Unit>): @androidx.compose.runtime.Composable () -> Unit = {
        FinishFlow(
            session = session,
            finishJob = null,
            onReadiness = { readiness },
            onFinish = { _, _, _, _, _ -> },
            onClearJob = {},
            onVerifySuggest = { null },
            onVerifySave = { null },
            onSendToAgent = {},
            onDismiss = {},
            onAck = { acks.add(Unit) },
        )
    }

    @Test fun compact_window_presents_the_flow_as_a_bottom_sheet() = runComposeUiTest {
        val acks = mutableListOf<Unit>()
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
            content = flow(acks),
        )
        waitForIdle()
        onNodeWithTag("finish_sheet").assertIsDisplayed()
        onNodeWithTag("finish_dialog").assertIsDisplayed()
        runOnIdle { assertEquals(1, acks.size) }
    }

    @Test fun a_wider_window_presents_the_flow_as_a_dialog() = runComposeUiTest {
        val acks = mutableListOf<Unit>()
        setPlatformContent(widthClass = WindowWidthClass.Expanded, content = flow(acks))
        waitForIdle()
        onNodeWithTag("finish_sheet").assertDoesNotExist()
        onNodeWithTag("finish_dialog").assertIsDisplayed()
        runOnIdle { assertEquals(1, acks.size) }
    }

    @Test fun the_header_button_only_appears_for_a_worktree_backed_session() = runComposeUiTest {
        setPlatformContent {
            FinishHeaderButton(
                session = session.copy(session_branch = null),
                bindings = FinishBindings(
                    job = FinishJobDto(status = "failed"),
                    readiness = { readiness },
                    finish = { _, _, _, _, _ -> },
                    clearJob = {},
                    verifySuggest = { null },
                    verifySave = { null },
                    sendToAgent = {},
                ),
            )
        }
        onNodeWithTag("finish_button").assertDoesNotExist()
    }
}
