package dev.supermux.desktop.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.FinishReadiness
import dev.supermux.net.FinishResult
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FinishButton + FinishDialog (M4b Task 3) — two layers:
 *
 *  1. PURE cores: [canSkipTests] (FinishChoices) and [issueMessage] are unit-tested directly.
 *  2. Compose: the state machine is exercised via the WINDOWLESS [FinishDialogContent] seam under
 *     [runComposeUiTest] (the real [FinishDialog] Dialog window is awkward headless — the body is
 *     extracted so the menu/running/outcome branches render directly). [FinishButton] visibility of
 *     the unacked dot is asserted the same way.
 */
@OptIn(ExperimentalTestApi::class)
class FinishDialogTest {

    private val session = SessionInfo(
        id = "s1", name = "feature", workdir = "/w", agent = "claude", session_branch = "feat/x",
    )

    private val readiness = FinishReadiness(
        branch = "feat/x", base = "main", ahead = 3, behind = 1, filesChanged = 4,
        insertions = 20, deletions = 5, hasRemote = true, ghAvailable = true, recommended = "merge",
    )

    // ── (1) pure cores ───────────────────────────────────────────────────────────────────────────

    @Test fun can_skip_tests_merge_always_skippable() {
        assertTrue(canSkipTests("merge", prRequiresGreen = false))
        assertTrue(canSkipTests("merge", prRequiresGreen = true))
    }

    @Test fun can_skip_tests_pr_skippable_unless_requires_green() {
        assertTrue(canSkipTests("pr", prRequiresGreen = false))
        assertFalse(canSkipTests("pr", prRequiresGreen = true))
    }

    @Test fun can_skip_tests_keep_and_discard_always_skippable() {
        assertTrue(canSkipTests("keep", prRequiresGreen = true))
        assertTrue(canSkipTests("discard", prRequiresGreen = true))
    }

    @Test fun issue_message_tests_failed_wraps_command_and_output() {
        val msg = issueMessage(FinishResult(status = "tests_failed", command = "npm test", output = "boom"))
        assertTrue(msg.contains("npm test"))
        assertTrue(msg.contains("boom"))
        assertTrue(msg.contains("green"))
    }

    @Test fun issue_message_sync_conflict_lists_files() {
        val msg = issueMessage(FinishResult(status = "sync_conflict", files = listOf("a.kt", "b.kt")))
        assertTrue(msg.contains("- a.kt"))
        assertTrue(msg.contains("- b.kt"))
        assertTrue(msg.contains("conflicted merge state"))
    }

    @Test fun issue_message_fallback_uses_message_then_status() {
        assertEquals("Finish reported: nope", issueMessage(FinishResult(status = "weird", message = "nope")))
        assertEquals("Finish reported: weird", issueMessage(FinishResult(status = "weird")))
    }

    // ── (2) state machine (windowless body seam) ──────────────────────────────────────────────────

    @Test fun null_job_shows_menu_with_readiness_and_four_action_rows() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                FinishDialogContent(
                    session = session,
                    finishJob = null,
                    onReadiness = { readiness },
                    onFinish = { _, _, _, _, _ -> },
                    onClearJob = {},
                    onVerifySuggest = { null },
                    onVerifySave = { null },
                    onSendToAgent = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("finish_readiness_card").assertIsDisplayed()
        onNodeWithText("Merge locally").assertIsDisplayed()
        onNodeWithText("Open PR").assertIsDisplayed()
        onNodeWithText("Keep").assertIsDisplayed()
        onNodeWithText("Discard").assertIsDisplayed()
    }

    @Test fun running_job_shows_running_body() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                FinishDialogContent(
                    session = session,
                    finishJob = FinishJobDto(status = "running", stage = "Merging…"),
                    onReadiness = { readiness },
                    onFinish = { _, _, _, _, _ -> },
                    onClearJob = {},
                    onVerifySuggest = { null },
                    onVerifySave = { null },
                    onSendToAgent = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("finish_running").assertIsDisplayed()
        onNodeWithText("Merging…").assertIsDisplayed()
    }

    @Test fun tests_failed_outcome_shows_recovery_and_let_agent_fix() = runComposeUiTest {
        var sentToAgent: String? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                FinishDialogContent(
                    session = session,
                    finishJob = FinishJobDto(
                        status = "failed", action = "merge",
                        outcome = FinishResult(status = "tests_failed", command = "npm test", output = "boom"),
                    ),
                    onReadiness = { readiness },
                    onFinish = { _, _, _, _, _ -> },
                    onClearJob = {},
                    onVerifySuggest = { null },
                    onVerifySave = { null },
                    onSendToAgent = { sentToAgent = it },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("finish_outcome").assertIsDisplayed()
        onNodeWithText("Tests failed").assertIsDisplayed()
        onNodeWithText("Merge anyway").assertIsDisplayed()
        onNodeWithText("Let the agent fix it").performClick()
        waitForIdle()
        assertTrue(sentToAgent?.contains("npm test") == true)
    }

    @Test fun merge_run_tests_calls_onFinish_merge_skipVerify_false() = runComposeUiTest {
        var captured: Triple<String, Boolean?, Boolean?>? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                FinishDialogContent(
                    session = session,
                    finishJob = null,
                    onReadiness = { readiness },
                    onFinish = { action, skip, commitFirst, _, _ -> captured = Triple(action, skip, commitFirst) },
                    onClearJob = {},
                    onVerifySuggest = { null },
                    onVerifySave = { null },
                    onSendToAgent = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Merge locally").performClick()   // expand the Run/Skip choice
        waitForIdle()
        onNodeWithTag("finish_run_tests").performClick()
        waitForIdle()
        assertEquals("merge", captured?.first)
        assertEquals(false, captured?.second)
    }

    @Test fun pr_requires_green_hides_skip_row() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                FinishDialogContent(
                    session = session,
                    finishJob = null,
                    onReadiness = { readiness.copy(prRequiresGreen = true) },
                    onFinish = { _, _, _, _, _ -> },
                    onClearJob = {},
                    onVerifySuggest = { null },
                    onVerifySave = { null },
                    onSendToAgent = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Open PR").performClick()   // expand the PR Run/Skip choice
        waitForIdle()
        onNodeWithTag("finish_run_tests").assertIsDisplayed()
        onNodeWithTag("finish_skip_tests").assertDoesNotExist()
    }

    // ── FinishButton unacked dot ───────────────────────────────────────────────────────────────────

    @Test fun finish_button_shows_unacked_dot_only_when_unacked() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                FinishButton(
                    finishJob = FinishJobDto(status = "failed"),
                    isUnacked = true,
                    onClick = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("finish_button").assertIsDisplayed()
        onNodeWithTag("finish_unacked_dot").assertIsDisplayed()
    }

    @Test fun finish_button_hides_dot_when_acked() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                FinishButton(
                    finishJob = FinishJobDto(status = "done"),
                    isUnacked = false,
                    onClick = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("finish_button").assertIsDisplayed()
        onNodeWithTag("finish_unacked_dot").assertDoesNotExist()
    }

    // ── isFinishUnacked / finishDotIsError (the REAL production helpers, not a copy) ─────────────────

    @Test fun is_finish_unacked_derivation() {
        // running → never unacked (even when not acked)
        assertFalse(isFinishUnacked(FinishJobDto(status = "running"), acked = false))
        // terminal + not-yet-acked → unacked
        assertTrue(isFinishUnacked(FinishJobDto(status = "failed"), acked = false))
        assertTrue(isFinishUnacked(FinishJobDto(status = "done"), acked = false))
        // terminal + acked → acked (dot hidden)
        assertFalse(isFinishUnacked(FinishJobDto(status = "done"), acked = true))
        // no job → not unacked
        assertFalse(isFinishUnacked(null, acked = false))
    }

    @Test fun finish_dot_is_error_only_for_failed() {
        assertTrue(finishDotIsError(FinishJobDto(status = "failed")))
        assertFalse(finishDotIsError(FinishJobDto(status = "done")))
        assertFalse(finishDotIsError(null))
    }
}
