package dev.supermux.desktop.session

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.ModelInfo
import dev.supermux.net.PathValidation
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RepoBranches
import dev.supermux.net.RepoInfo
import dev.supermux.proto.SessionInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SessionLauncherScreen (M4a Task 4) — two layers:
 *
 *  1. The PURE settle-vs-change helpers ([shouldResetModelOnAgentChange] /
 *     [shouldResetBaseBranchOnWorkdirChange]) + [filterBranches] + [stagedUploadFor] are unit-tested
 *     directly (no Compose). These encode the subtle draft-restore-vs-genuine-change logic that
 *     caused a real device bug on iOS/Android — a restore-settle must NEVER reset the model or the
 *     base branch, a genuine later change MUST.
 *  2. The composer card is exercised via [runComposeUiTest] with faked suspend lambdas (canned
 *     lists — no live broker): it renders the pills/message/attach/submit, an agent change resets
 *     the model to Default, a submit assembles the right onSubmit args, and an invalid typed path
 *     shows the validation state without picking.
 */
@OptIn(ExperimentalTestApi::class)
class SessionLauncherScreenTest {

    // ── (1) pure settle helpers ─────────────────────────────────────────────────────────────────

    @Test fun model_reset_first_observation_never_resets() {
        // lastSeen == null means "never recorded yet" — must not count as a difference.
        assertFalse(shouldResetModelOnAgentChange(lastSeen = null, current = "claude", restoring = false))
    }

    @Test fun model_reset_restore_settle_never_resets() {
        // Even a "different" agent must not reset while restoring — the draft is still settling.
        assertFalse(shouldResetModelOnAgentChange(lastSeen = "claude", current = "codex", restoring = true))
    }

    @Test fun model_reset_same_agent_does_not_reset() {
        assertFalse(shouldResetModelOnAgentChange(lastSeen = "claude", current = "claude", restoring = false))
    }

    @Test fun model_reset_genuine_change_resets() {
        assertTrue(shouldResetModelOnAgentChange(lastSeen = "claude", current = "codex", restoring = false))
    }

    @Test fun base_branch_first_observation_seeds_when_blank() {
        // No base branch yet → seed it from the repo's current branch (even on the first workdir).
        assertTrue(shouldResetBaseBranchOnWorkdirChange(lastSeen = null, current = "/w", baseBranch = "", restoring = false))
    }

    @Test fun base_branch_first_observation_keeps_nonblank() {
        // A restored non-blank base branch on the first workdir must survive.
        assertFalse(shouldResetBaseBranchOnWorkdirChange(lastSeen = null, current = "/w", baseBranch = "main", restoring = false))
    }

    @Test fun base_branch_restore_settle_never_resets() {
        assertFalse(shouldResetBaseBranchOnWorkdirChange(lastSeen = "/w", current = "/x", baseBranch = "", restoring = true))
    }

    @Test fun base_branch_genuine_workdir_change_resets() {
        assertTrue(shouldResetBaseBranchOnWorkdirChange(lastSeen = "/w", current = "/x", baseBranch = "main", restoring = false))
    }

    @Test fun base_branch_same_workdir_keeps_nonblank() {
        assertFalse(shouldResetBaseBranchOnWorkdirChange(lastSeen = "/w", current = "/w", baseBranch = "main", restoring = false))
    }

    // ── filterBranches ──────────────────────────────────────────────────────────────────────────

    @Test fun filter_branches_combines_local_and_remote_and_is_case_insensitive() {
        val info = RepoInfo(branches = RepoBranches(local = listOf("main", "feat/Login"), remote = listOf("origin/main")))
        assertEquals(listOf("main", "feat/Login", "origin/main"), filterBranches(info, ""))
        assertEquals(listOf("feat/Login"), filterBranches(info, "login"))
        assertEquals(listOf("main", "origin/main"), filterBranches(info, "MAIN"))
        assertTrue(filterBranches(null, "x").isEmpty())
    }

    // ── filterProjects (project-picker search) ──────────────────────────────────────────────────

    @Test fun filter_projects_empty_query_returns_all() {
        val all = listOf("/home/u/alpha", "/home/u/beta", "/home/u/gamma")
        assertEquals(all, filterProjects(all, home = "/home/u", query = ""))
    }

    @Test fun filter_projects_matches_path_substring_case_insensitive() {
        val all = listOf("/home/u/alpha", "/home/u/beta", "/home/u/gamma")
        assertEquals(listOf("/home/u/alpha"), filterProjects(all, home = "/home/u", query = "ALPH"))
        assertEquals(listOf("/home/u/beta"), filterProjects(all, home = "/home/u", query = "beta"))
    }

    @Test fun filter_projects_matches_formatted_label_too() {
        // The picker shows formatWorkdir(path, home) — the tilde-prefixed form. Filtering must match
        // against the displayed label, not just the raw path, so typing "~" still narrows the list.
        val all = listOf("/home/u/alpha", "/home/u/beta")
        assertEquals(all, filterProjects(all, home = "/home/u", query = "~"))
    }

    // ── stagedUploadFor (temp file) ─────────────────────────────────────────────────────────────

    @Test fun staged_upload_streams_the_file_and_guesses_mime() {
        val f = File.createTempFile("launcher", ".txt").apply { writeBytes(ByteArray(11) { 7 }) }
        try {
            val up = stagedUploadFor(f)
            assertEquals(f.name, up.name)
            assertEquals(11L, up.source.size) // streams from the file, not buffered
            assertNull(up.kind) // non-audio leaves kind null (broker infers from MIME)
            assertTrue(up.mime.isNotBlank())
        } finally {
            f.delete()
        }
    }

    // ── (2) UI: fakes + harness ─────────────────────────────────────────────────────────────────

    /** Captured onSubmit args (assembled by the screen). */
    private data class Submitted(
        val workdir: String,
        val agent: String,
        val model: String?,
        val reasoning: String?,
        val text: String,
        val stagedCount: Int,
        val worktree: Boolean,
        val baseBranch: String?,
    )

    @Composable
    private fun Harness(
        sessions: List<SessionInfo> = emptyList(),
        prefs: LauncherPrefs = LauncherPrefs(),
        draft: LauncherDraft = LauncherDraft(),
        models: (String) -> List<ModelInfo> = { emptyList() },
        reasoning: (String, String?) -> ReasoningResponse? = { _, _ -> null },
        repoInfo: RepoInfo? = null,
        onPrefsChange: (LauncherPrefs) -> Unit = {},
        onDraftChange: (LauncherDraft) -> Unit = {},
        onClearDraft: () -> Unit = {},
        onSubmit: suspend (String, String, String?, String?, String, List<StagedUpload>, Boolean, String?, String?) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            SessionLauncherScreen(
                sessions = sessions,
                home = "/home/u",
                onBack = {},
                loadProjects = { emptyList() },
                validatePath = { null },
                loadModels = { models(it) },
                loadReasoningLevels = { a, m -> reasoning(a, m) },
                loadRepoInfo = { repoInfo },
                loadPrefs = { prefs },
                onPrefsChange = onPrefsChange,
                loadDraft = { draft },
                onDraftChange = onDraftChange,
                onClearDraft = onClearDraft,
                onSubmit = onSubmit,
            )
        }
    }

    @Test fun card_renders_pills_message_attach_and_submit() = runComposeUiTest {
        setContent { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_project_field").assertIsDisplayed()
        onNodeWithTag("launcher_agent_pill").assertIsDisplayed()
        onNodeWithTag("launcher_model_picker").assertIsDisplayed()
        onNodeWithTag("launcher_message").assertIsDisplayed()
        onNodeWithTag("launcher_attach").assertIsDisplayed()
        onNodeWithTag("launcher_submit").assertIsDisplayed()
        // Empty draft → send is disabled (no text, no attachments).
        onNodeWithTag("launcher_submit").assertIsNotEnabled()
    }

    @Test fun submit_calls_onSubmit_with_the_assembled_args() = runComposeUiTest {
        var captured: Submitted? = null
        var cleared = false
        setContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                onClearDraft = { cleared = true },
                onSubmit = { w, a, m, r, t, s, wt, b, _replaceDraftId ->
                    captured = Submitted(w, a, m, r, t, s.size, wt, b)
                },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(
            Submitted("/proj/x", "claude", null, null, "do it", 0, false, null),
            captured,
        )
        assertTrue(cleared) // successful submit clears the draft
    }

    @Test fun submit_clear_cancels_the_pending_debounce_no_draft_resurrection() = runComposeUiTest {
        // Deterministic clock: the restored draft schedules a debounced save at t+400ms. A submit
        // BEFORE that elapses clears the draft; the pending save must be CANCELLED, not fire late and
        // resurrect the just-cleared draft. Freeze the clock so submit lands inside the debounce
        // window, then advance past 400ms and assert onDraftChange never re-saved after the clear.
        mainClock.autoAdvance = false
        val drafts = mutableListOf<LauncherDraft>()
        var cleared = false
        setContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "seed"),
                onDraftChange = { drafts.add(it) },
                onClearDraft = { cleared = true },
            )
        }
        waitForIdle() // restore + effects settle; the debounce delay(400) is now PENDING (not fired)
        assertTrue(drafts.isEmpty(), "no save should fire before the debounce window elapses")

        onNodeWithTag("launcher_submit").performClick()
        waitForIdle() // onSubmit → onClearDraft + draftCleared=true → debounce effect relaunches + early-returns
        assertTrue(cleared)

        mainClock.advanceTimeBy(600) // any still-pending 400ms save would fire here — with the fix, cancelled
        waitForIdle()
        assertTrue(drafts.isEmpty(), "the cleared draft must not be resurrected by a stale debounce; got $drafts")
    }

    @Test fun agent_change_resets_model_to_default() = runComposeUiTest {
        setContent {
            Harness(
                prefs = LauncherPrefs(agent = "claude", models = mapOf("claude" to "claude-x")),
                models = { agent ->
                    if (agent == "claude") listOf(ModelInfo("claude-x", "Claude X"))
                    else listOf(ModelInfo("gpt-5", "GPT-5"))
                },
            )
        }
        waitForIdle()
        // Restored: claude's sticky model shows.
        onNodeWithText("Claude X").assertIsDisplayed()

        // Switch agent → the model resets to Default (genuine change, not a restore-settle).
        onNodeWithTag("launcher_agent_pill").performClick()
        onNodeWithTag("agent_codex").performClick()
        waitForIdle()
        onNodeWithText("Default").assertIsDisplayed()
        onNodeWithText("Claude X").assertDoesNotExist()
    }

    @Test fun project_picker_search_field_filters_project_list() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "/home/u/alpha",
                        projects = listOf("/home/u/alpha", "/home/u/beta", "/home/u/gamma"),
                        home = "/home/u",
                        validatePath = { null },
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }
        }
        // All three projects visible to start.
        onNodeWithTag("project_row_/home/u/alpha").assertIsDisplayed()
        onNodeWithTag("project_row_/home/u/beta").assertIsDisplayed()
        onNodeWithTag("project_row_/home/u/gamma").assertIsDisplayed()

        // Typing into the search field narrows the list.
        onNodeWithTag("launcher_project_search").performTextInput("beta")
        waitForIdle()
        onNodeWithTag("project_row_/home/u/beta").assertIsDisplayed()
        onNodeWithTag("project_row_/home/u/alpha").assertDoesNotExist()
        onNodeWithTag("project_row_/home/u/gamma").assertDoesNotExist()
    }

    @Test fun project_picker_invalid_path_shows_validation_and_does_not_pick() = runComposeUiTest {
        var picked: String? = null
        var dismissed = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = emptyList(),
                        home = "/home/u",
                        validatePath = { PathValidation(ok = false, path = null, error = "no such directory") },
                        onPick = { picked = it },
                        onDismiss = { dismissed = true },
                    )
                }
            }
        }
        onNodeWithTag("launcher_path_input").performTextInput("/nope")
        onNodeWithTag("launcher_path_confirm").performClick()
        waitForIdle()
        onNodeWithTag("launcher_path_error").assertIsDisplayed()
        onNodeWithText("no such directory").assertIsDisplayed()
        assertNull(picked)        // invalid path never picks
        assertFalse(dismissed)    // ...and keeps the picker open
    }

    @Test fun project_picker_valid_path_picks_resolved_and_dismisses() = runComposeUiTest {
        var picked: String? = null
        var dismissed = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = emptyList(),
                        home = "/home/u",
                        validatePath = { PathValidation(ok = true, path = "/home/u/proj") },
                        onPick = { picked = it },
                        onDismiss = { dismissed = true },
                    )
                }
            }
        }
        onNodeWithTag("launcher_path_input").performTextInput("~/proj")
        onNodeWithTag("launcher_path_confirm").performClick()
        waitForIdle()
        assertEquals("/home/u/proj", picked) // the RESOLVED path, not the typed one
        assertTrue(dismissed)
    }
}
