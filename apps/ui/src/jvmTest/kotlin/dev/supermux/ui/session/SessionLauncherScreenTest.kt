package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import dev.supermux.chat.DEFAULT_MODEL_ID
import dev.supermux.net.AgentModels
import dev.supermux.net.AgentModelsResponse
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningOptions
import dev.supermux.net.GitBranches
import dev.supermux.net.GitLocalBranch
import dev.supermux.net.GitOpenIn
import dev.supermux.net.GitRemoteBranch
import dev.supermux.net.GitSwitchResult
import dev.supermux.net.OpenPr
import dev.supermux.net.OpenPrList
import dev.supermux.net.OpenPrRead
import dev.supermux.net.ProjectFolderState
import dev.supermux.net.ProjectPrefs
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RepoBranches
import dev.supermux.net.RepoInfo
import dev.supermux.net.SessionSourceBody
import dev.supermux.proto.PermissionModeInfo
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ProjectLocationDto
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import dev.supermux.state.StagedUpload
import dev.supermux.state.worktreeChoiceKey
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The shared [SessionLauncherScreen] (cluster F6) — three layers:
 *
 *  1. The PURE settle-vs-change helper ([shouldResetModelOnAgentChange]) is unit-tested (the row's own data in LauncherWorkTest)
 *     directly (no Compose). These encode the subtle draft-restore-vs-genuine-change logic that
 *     caused a real device bug on iOS/Android — a restore-settle must NEVER reset the model or the
 *     base branch, a genuine later change MUST.
 *  2. The composer card under a POINTER — desktop's suite, moved verbatim: it renders the
 *     pills/message/attach/submit, an agent change resets the model to Default, a submit assembles
 *     the right onSubmit args, a cleared draft is not resurrected, a refusal shows verbatim.
 *  3. The TOUCH branch Android used to own plus the unions: the "/" menu under both input modes,
 *     camera rows gated on `Caps.camera`, the worktree bottom sheet, the standalone chrome + Back,
 *     the physical-Enter policy and the draft round-trip through [UiPrefs].
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
        /** Per-workdir repo info; overrides [repoInfo] when set. */
        repoInfoAt: (suspend (String) -> RepoInfo?)? = null,
        commands: List<SlashCommand> = emptyList(),
        /** Every workdir the screen asked repo info or slash commands for. */
        asked: MutableList<String>? = null,
        /** The host's cached model catalog; non-null means no per-call model requests. */
        agentModels: kotlinx.coroutines.flow.Flow<AgentModelsResponse?> = kotlinx.coroutines.flow.flowOf(null),
        /** Every per-call agents/models/reasoning request, by name. */
        modelCalls: MutableList<String>? = null,
        // A restored draft workdir survives an EMPTY project list now — that reset
        // used to fire on "could not enumerate projects" and silently rewrite the
        // workdir to "~". Kept parameterised so a test can still exercise the
        // genuine "workdir is not among the known projects" reset.
        projects: List<String> = emptyList(),
        standalone: Boolean = false,
        workspaceWorkdir: String? = null,
        workspaceLabel: String? = null,
        onBack: () -> Unit = {},
        onPrefsChange: (LauncherPrefs) -> Unit = {},
        onDraftChange: (LauncherDraft) -> Unit = {},
        onClearDraft: () -> Unit = {},
        onOpenSession: ((String) -> Unit)? = null,
        onSubmit: suspend (String, String, String?, String?, String, List<StagedUpload>, Boolean, String?, String?, String?, String?, SessionSourceBody?) -> String? =
            { _, _, _, _, _, _, _, _, _, _, _, _ -> null },
        permissionCatalog: Map<String, List<PermissionModeInfo>> = emptyMap(),
        accounts: kotlinx.coroutines.flow.Flow<List<dev.supermux.net.AccountDto>?> = kotlinx.coroutines.flow.flowOf(null),
        /** #152's broker: null = one that predates it (the row falls back to the worktree flag + base). */
        projectPrefs: ProjectPrefs? = null,
        branches: GitBranches? = null,
        folder: ProjectFolderState? = null,
        /** Every PUT /projects/prefs, as "dir=workIn". */
        prefsPut: MutableList<String>? = null,
        /** The broker can't keep a pick (PUT /projects/prefs fails). */
        prefsSaveFails: Boolean = false,
        /** Origin's open PRs (GET /projects/prs), one read by ref, and every POST /projects/switch as "dir=name". */
        prs: OpenPrList? = null,
        prRead: (String) -> OpenPrRead? = { null },
        switched: MutableList<String>? = null,
        onOpenSessionFromCard: ((String) -> Unit)? = null,
        /** The host's projects, and the one the launcher opens on (the sidebar's "+", #72's "Check Out Pull Request…"). */
        catalog: List<ProjectDto> = emptyList(),
        initialProjectId: String? = null,
        initialPrNumber: Int? = null,
    ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            SessionLauncherScreen(
                sessions = sessions,
                home = "/home/u",
                onBack = onBack,
                actions = LauncherActions(
                    listProjects = { projects },
                    validatePath = { null },
                    agentModels = agentModels,
                    launcherAgents = { modelCalls?.add("agents"); emptyList() },
                    launcherModels = { modelCalls?.add("models:$it"); models(it) },
                    launcherReasoning = { a, m -> modelCalls?.add("reasoning:$a"); reasoning(a, m) },
                    launcherRepoInfo = { w, _ -> asked?.add(w); repoInfoAt?.invoke(w) ?: repoInfo },
                    launcherCommands = { _, w -> asked?.add(w); commands },
                    accounts = accounts,
                    projectPrefs = { projectPrefs },
                    projectBranches = { branches },
                    folderState = { folder },
                    setProjectPrefs = { d, w -> prefsPut?.add("$d=$w"); if (prefsSaveFails) null else projectPrefs?.copy(workIn = w) },
                    projectPrs = { _, _ -> prs },
                    projectPr = { _, ref -> prRead(ref) },
                    switchFolder = { d, n -> switched?.add("$d=$n"); GitSwitchResult(status = "switched", branch = n) },
                    projectCatalog = kotlinx.coroutines.flow.flowOf(catalog),
                ),
                loadPrefs = { prefs },
                onPrefsChange = onPrefsChange,
                loadDraft = { draft },
                onDraftChange = onDraftChange,
                onClearDraft = onClearDraft,
                onSubmit = onSubmit,
                permissionCatalog = permissionCatalog,
                onOpenSession = onOpenSession ?: onOpenSessionFromCard,
                standalone = standalone,
                workspaceWorkdir = workspaceWorkdir,
                workspaceLabel = workspaceLabel,
                initialProjectId = initialProjectId,
                initialPrNumber = initialPrNumber,
            )
        }
    }

    /** The pointer (desktop) mount: no camera, a real keyboard, a wide window. */
    private fun ComposeUiTest.pointerContent(
        platform: FakePlatform = FakePlatform(),
        content: @Composable () -> Unit,
    ) = setPlatformContent(
        platform = platform,
        pointer = true,
        widthClass = WindowWidthClass.Expanded,
        inputMode = InputMode.Pointer,
        content = content,
    )

    /** The touch (phone) mount: a camera, no pointer, a compact window. */
    private fun ComposeUiTest.touchContent(
        platform: FakePlatform = FakePlatform(caps = PHONE_CAPS),
        content: @Composable () -> Unit,
    ) = setPlatformContent(
        platform = platform,
        pointer = false,
        widthClass = WindowWidthClass.Compact,
        inputMode = InputMode.Touch,
        content = content,
    )

    @Test fun card_renders_pills_message_attach_and_submit() = runComposeUiTest {
        pointerContent { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_project_field").assertIsDisplayed()
        // ONE chip for agent · model · effort (the agent row lives in its panel).
        onNodeWithTag("composer-model-pill").assertIsDisplayed()
        onNodeWithTag("launcher_model_picker").assertIsDisplayed()
        onNodeWithTag("launcher_agent_pill").assertDoesNotExist()
        onNodeWithTag("launcher_message").assertIsDisplayed()
        onNodeWithTag("launcher_attach").assertIsDisplayed()
        onNodeWithTag("launcher_submit").assertIsDisplayed()
        // Empty draft → send is disabled (no text, no attachments).
        onNodeWithTag("launcher_submit").assertIsNotEnabled()
    }

    @Test fun the_card_is_the_shared_composer_under_both_input_modes() = runComposeUiTest {
        // The capsule, the field, the staged strip and the attach/mic/send controls are
        // `ui/chat/Composer`'s now (cluster F7) — wearing the launcher's tags via ComposerTags, so
        // every one of these nodes is the shared composer's under BOTH input modes.
        pointerContent { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_composer_card").assertIsDisplayed()
        onNodeWithTag("launcher_message").assertIsDisplayed()
        onNodeWithTag("launcher_attach").assertIsDisplayed()
        onNodeWithTag("launcher_mic").assertIsDisplayed()
        onNodeWithTag("launcher_submit").assertIsDisplayed()
    }

    @Test fun the_card_is_the_shared_composer_under_touch_too() = runComposeUiTest {
        touchContent { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_composer_card").assertIsDisplayed()
        onNodeWithTag("launcher_message").assertIsDisplayed()
        onNodeWithTag("launcher_attach").assertIsDisplayed()
        onNodeWithTag("launcher_mic").assertIsDisplayed()
        onNodeWithTag("launcher_submit").assertIsDisplayed()
    }

    @Test fun submit_calls_onSubmit_with_the_assembled_args() = runComposeUiTest {
        var captured: Submitted? = null
        var cleared = false
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                onClearDraft = { cleared = true },
                onSubmit = { w, a, m, r, t, s, wt, b, _replaceDraftId, _perm, _, _ ->
                    captured = Submitted(w, a, m, r, t, s.size, wt, b)
                    null
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

    @Test fun account_picker_defaults_to_auto_and_sends_no_account() = runComposeUiTest {
        val sent = mutableListOf<String?>()
        val accounts = kotlinx.coroutines.flow.MutableStateFlow<List<dev.supermux.net.AccountDto>?>(
            listOf(
                dev.supermux.net.AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true),
                dev.supermux.net.AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work"),
                dev.supermux.net.AccountDto(id = "codex:system", agent = "codex", method = "system", label = "System login", system = true),
            ),
        )
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                accounts = accounts,
                onSubmit = { _, _, _, _, _, _, _, _, _, _, account, _ -> sent += account; null },
            )
        }
        waitForIdle()
        // Default: Auto, so nothing is sent and the broker picks.
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<String?>(null), sent)
    }

    @Test fun picking_the_system_login_sends_its_id() = runComposeUiTest {
        val sent = mutableListOf<String?>()
        val accounts = kotlinx.coroutines.flow.MutableStateFlow<List<dev.supermux.net.AccountDto>?>(
            listOf(
                dev.supermux.net.AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true),
                dev.supermux.net.AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work", auto = true),
            ),
        )
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                accounts = accounts,
                onSubmit = { _, _, _, _, _, _, _, _, _, _, account, _ -> sent += account; null },
            )
        }
        waitForIdle()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        // Auto is listed first and shows the account it would pick now.
        onNodeWithTag("run_settings_account").assertTextContains("Auto", substring = true)
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
        onNodeWithTag("account-picker-item:auto").assertTextContains("Work", substring = true)
        onNodeWithTag("account-picker-item:claude:system").performClick()
        waitForIdle()
        // The system login is a choice of its own: the row names it, not Auto.
        onNodeWithTag("run_settings_account").assertTextContains("System login", substring = true)
        onNodeWithTag("composer-model-$DEFAULT_MODEL_ID").performClick()
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<String?>("claude:system"), sent)
    }

    @Test fun picking_auto_again_after_an_added_account_sends_no_account() = runComposeUiTest {
        val sent = mutableListOf<String?>()
        val accounts = kotlinx.coroutines.flow.MutableStateFlow<List<dev.supermux.net.AccountDto>?>(
            listOf(
                dev.supermux.net.AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true),
                dev.supermux.net.AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work", auto = true),
            ),
        )
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                accounts = accounts,
                onSubmit = { _, _, _, _, _, _, _, _, _, _, account, _ -> sent += account; null },
            )
        }
        waitForIdle()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
        onNodeWithTag("account-picker-item:claude-work").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("Work", substring = true)
        // Back to Auto, which happens to pick the very same account now: still nothing is sent.
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
        onNodeWithTag("account-picker-item:auto").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("Auto", substring = true)
        onNodeWithTag("composer-model-$DEFAULT_MODEL_ID").performClick()
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<String?>(null), sent)
    }

    @Test fun a_chosen_account_that_goes_away_drops_its_mark_and_sends_auto() = runComposeUiTest {
        val sent = mutableListOf<String?>()
        val system = dev.supermux.net.AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true)
        val accounts = kotlinx.coroutines.flow.MutableStateFlow<List<dev.supermux.net.AccountDto>?>(
            listOf(
                system,
                dev.supermux.net.AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work"),
                dev.supermux.net.AccountDto(id = "claude-home", agent = "claude", method = "subscription", label = "Home"),
            ),
        )
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                accounts = accounts,
                onSubmit = { _, _, _, _, _, _, _, _, _, _, account, _ -> sent += account; null },
            )
        }
        waitForIdle()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
        onNodeWithTag("account-picker-item:claude-work").performClick()
        waitForIdle()
        onNodeWithTag("composer-model-$DEFAULT_MODEL_ID").performClick()
        waitForIdle()
        // The account is removed elsewhere: the launcher is back on Auto.
        accounts.value = listOf(system, dev.supermux.net.AccountDto(id = "claude-home", agent = "claude", method = "subscription", label = "Home"))
        waitForIdle()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("Auto", substring = true)
        onNodeWithTag("composer-model-$DEFAULT_MODEL_ID").performClick()
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<String?>(null), sent)
    }

    @Test fun account_picker_choice_reaches_onSubmit() = runComposeUiTest {
        val sent = mutableListOf<String?>()
        val accounts = kotlinx.coroutines.flow.MutableStateFlow<List<dev.supermux.net.AccountDto>?>(
            listOf(
                dev.supermux.net.AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true),
                dev.supermux.net.AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work"),
            ),
        )
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                accounts = accounts,
                onSubmit = { _, _, _, _, _, _, _, _, _, _, account, _ -> sent += account; null },
            )
        }
        waitForIdle()
        // No separate account pill: the account is a row of the run-settings panel.
        onNodeWithTag("account-picker").assertDoesNotExist()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
        onNodeWithTag("account-picker-item:claude-work").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("Work", substring = true)
        // Close the panel the way a user would: pick a model (Default).
        onNodeWithTag("composer-model-$DEFAULT_MODEL_ID").performClick()
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<String?>("claude-work"), sent)
    }

    @Test fun compact_launcher_carries_the_account_in_the_run_settings_sheet() = runComposeUiTest {
        val sent = mutableListOf<String?>()
        val accounts = kotlinx.coroutines.flow.MutableStateFlow<List<dev.supermux.net.AccountDto>?>(
            listOf(
                dev.supermux.net.AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true),
                dev.supermux.net.AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work"),
            ),
        )
        touchContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                accounts = accounts,
                onSubmit = { _, _, _, _, _, _, _, _, _, _, account, _ -> sent += account; null },
            )
        }
        waitForIdle()
        onNodeWithTag("account-picker").assertDoesNotExist()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
        onNodeWithTag("account-picker-item:claude-work").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("Work", substring = true)
        onNodeWithTag("composer-model-$DEFAULT_MODEL_ID").performClick()
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<String?>("claude-work"), sent)
    }

    @Test fun no_account_picker_without_a_choice() = runComposeUiTest {
        pointerContent { Harness(accounts = kotlinx.coroutines.flow.flowOf(listOf(
            dev.supermux.net.AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true),
        ))) }
        waitForIdle()
        onNodeWithTag("account-picker").assertDoesNotExist()
    }

    @Test fun submit_sends_sticky_permission_mode() = runComposeUiTest {
        var perm: String? = "unset"
        val catalog = mapOf(
            "claude" to listOf(
                PermissionModeInfo("bypass", "Bypass", "skip", default = true),
                PermissionModeInfo("ask", "Ask", "ask"),
            ),
        )
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                prefs = LauncherPrefs(permissionModes = mapOf("claude" to "ask")),
                permissionCatalog = catalog,
                onSubmit = { _, _, _, _, _, _, _, _, _, p, _, _ ->
                    perm = p
                    null
                },
            )
        }
        waitForIdle()
        // "ask" is not claude's default, so the mode shows itself next to the pill.
        onNodeWithTag("launcher_permissions_picker").assertIsDisplayed()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals("ask", perm)
    }

    @Test fun submit_clear_cancels_the_pending_debounce_no_draft_resurrection() = runComposeUiTest {
        // Deterministic clock: the restored draft schedules a debounced save at t+400ms. A submit
        // BEFORE that elapses clears the draft; the pending save must be CANCELLED, not fire late and
        // resurrect the just-cleared draft. Freeze the clock so submit lands inside the debounce
        // window, then advance past 400ms and assert onDraftChange never re-saved after the clear.
        mainClock.autoAdvance = false
        val drafts = mutableListOf<LauncherDraft>()
        var cleared = false
        pointerContent {
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

    @Test fun a_workspace_tab_is_locked_to_its_workspace_and_restores_only_the_text() = runComposeUiTest {
        var captured: Submitted? = null
        pointerContent {
            Harness(
                // Everything but the text belongs to some OTHER launcher and must not leak in.
                draft = LauncherDraft(workdir = "/other/proj", useWorktree = true, baseBranch = "dev", text = "tab text"),
                repoInfo = repo,
                workspaceWorkdir = "/ws/tree",
                onSubmit = { w, a, m, r, t, s, wt, b, _, _perm, _, _ ->
                    captured = Submitted(w, a, m, r, t, s.size, wt, b)
                    null
                },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_project_field").assertDoesNotExist()
        onNodeWithTag(WorkRowTags.WORK_IN).assertDoesNotExist()
        onNodeWithTag("launcher_save_draft").assertDoesNotExist()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(Submitted("/ws/tree", "claude", null, null, "tab text", 0, false, null), captured)
    }

    @Test fun a_workspace_tab_keeps_its_workdir_when_it_is_not_a_known_project() = runComposeUiTest {
        // A workspace's workdir is usually a worktree, which GET /projects never lists. The
        // "not a known project" correction used to swap it for the most-recent project.
        var captured: Submitted? = null
        pointerContent {
            Harness(
                draft = LauncherDraft(text = "hi"),
                projects = listOf("/proj/a"),
                workspaceWorkdir = "/ws/tree",
                onSubmit = { w, a, m, r, t, s, wt, b, _, _perm, _, _ ->
                    captured = Submitted(w, a, m, r, t, s.size, wt, b)
                    null
                },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals("/ws/tree", captured?.workdir)
    }

    @Test fun a_workspace_tab_captions_its_repo_not_the_worktree_dir() = runComposeUiTest {
        pointerContent {
            Harness(
                workspaceWorkdir = "/home/u/.mux/worktrees/app-1a2b/0f9e-uuid",
                workspaceLabel = "~/projects/app · mux/app-3",
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_workdir_caption").assertTextEquals("~/projects/app · mux/app-3")
    }

    @Test fun a_failed_submit_saves_the_draft_again() = runComposeUiTest {
        // The draft is cleared BEFORE the spawn (a workspace tab is disposed the moment the broker
        // binds it), so a refusal must re-arm the save — the text is still the user's.
        mainClock.autoAdvance = false
        val drafts = mutableListOf<LauncherDraft>()
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "keep me"),
                onDraftChange = { drafts.add(it) },
                onSubmit = { _, _, _, _, _, _, _, _, _, _, _, _ -> throw IllegalStateException("nope") },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        assertEquals("keep me", drafts.lastOrNull()?.text)
    }

    @Test fun agent_change_brings_back_that_agents_remembered_model() = runComposeUiTest {
        pointerContent {
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

        // Switch agent (in the panel, which stays open) → the model resets to Default.
        onNodeWithTag("composer-model-pill").performClick()
        onNodeWithTag("agent_codex").performClick()
        waitForIdle()
        onNodeWithTag("composer-model-pill").assertTextContains("Default", substring = true)
        onNodeWithTag("composer-model-pill").assert(!hasText("Claude X", substring = true))

        // …and back: claude's remembered pick returns instead of staying on Default.
        onNodeWithTag("agent_claude").performClick()
        waitForIdle()
        onNodeWithTag("composer-model-pill").assertTextContains("Claude X", substring = true)
    }

    // ── the host's cached model catalog ─────────────────────────────────────────────────────────

    @Test fun a_cached_catalog_means_no_model_requests_at_all() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val catalog = AgentModelsResponse(
            agents = listOf(
                AgentModels(kind = "claude", models = listOf(ModelInfo("claude-x", "Claude X"))),
                AgentModels(
                    kind = "codex",
                    models = listOf(ModelInfo("gpt-5", "GPT-5")),
                    modelReasoning = mapOf(
                        "gpt-5" to ReasoningOptions(listOf(ReasoningLevel("low"), ReasoningLevel("high")), visible = true),
                    ),
                ),
            ),
        )
        pointerContent {
            Harness(
                prefs = LauncherPrefs(agent = "codex", models = mapOf("codex" to "gpt-5")),
                agentModels = kotlinx.coroutines.flow.flowOf(catalog),
                modelCalls = calls,
            )
        }
        waitForIdle()
        onNodeWithText("GPT-5").assertIsDisplayed()
        // Effort is offered (in the panel the one pill opens).
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("composer-reasoning-high").assertIsDisplayed()
        // Only the installed agents are offered, and switching is a lookup, not a request.
        onNodeWithTag("agent_cursor").assertDoesNotExist()
        onNodeWithTag("agent_claude").performClick()
        waitForIdle()
        onNodeWithTag("composer-model-pill").assertTextContains("Default", substring = true)
        assertTrue(calls.isEmpty(), "no per-call model traffic with a cached catalog; got $calls")
    }

    @Test fun a_catalog_update_replaces_the_list_in_place() = runComposeUiTest {
        val flow = kotlinx.coroutines.flow.MutableStateFlow<AgentModelsResponse?>(
            AgentModelsResponse(listOf(AgentModels("claude", listOf(ModelInfo("old", "Old model"))))),
        )
        pointerContent { Harness(prefs = LauncherPrefs(models = mapOf("claude" to "old")), agentModels = flow) }
        waitForIdle()
        onNodeWithText("Old model").assertIsDisplayed()
        // agent_models_changed → the host refetched: "old" is gone, so the pick falls back to Default.
        flow.value = AgentModelsResponse(listOf(AgentModels("claude", listOf(ModelInfo("new", "New model")))))
        waitForIdle()
        onNodeWithText("Default").assertIsDisplayed()
        onNodeWithTag("launcher_model_picker").performClick()
        waitForIdle()
        onNodeWithTag("composer-model-new").assertIsDisplayed()
    }

    // ── no project is a state, not `~` ──────────────────────────────────────────────────────────

    @Test fun nothing_to_pick_from_asks_for_a_project_and_loads_nothing() = runComposeUiTest {
        val asked = mutableListOf<String>()
        pointerContent { Harness(draft = LauncherDraft(text = "go"), repoInfo = repo, asked = asked) }
        waitForIdle()
        onNodeWithTag("launcher_project_label", useUnmergedTree = true).assertTextEquals("Choose a project")
        onNodeWithTag("launcher_workdir_caption").assertDoesNotExist()
        onNodeWithTag(WorkRowTags.WORK_IN).assertDoesNotExist()
        onNodeWithTag("launcher_submit").assertIsNotEnabled()
        assertTrue(asked.isEmpty(), "no repo info / commands for a project nobody chose; got $asked")
    }

    @Test fun the_recent_project_is_the_first_and_only_thing_loaded() = runComposeUiTest {
        val asked = mutableListOf<String>()
        pointerContent {
            Harness(sessions = listOf(session("s1", "/proj/x")), repoInfo = repo, asked = asked)
        }
        waitForIdle()
        // The row under the composer says where the work lands, so the heading is what names the folder here.
        onNodeWithTag("launcher_project_label", useUnmergedTree = true).assertTextEquals(dev.supermux.session.formatWorkdir("/proj/x", "/home/u"))
        assertEquals(setOf("/proj/x"), asked.toSet())
    }

    // ── worktree + base branch follow the project ───────────────────────────────────────────────

    @Test fun a_project_switch_never_submits_the_old_projects_branch() = runComposeUiTest {
        var sessions by mutableStateOf(listOf(session("s1", "/proj/x")))
        val gate = CompletableDeferred<RepoInfo?>()
        val repoY = RepoInfo(
            eligible = true,
            currentBranch = "trunk",
            repoRoot = "/proj/y",
            branches = RepoBranches(local = listOf("trunk"), remote = emptyList()),
        )
        var captured: Submitted? = null
        pointerContent {
            Harness(
                // No text yet: typing freezes the project, and this one must follow recency.
                sessions = sessions,
                repoInfoAt = { w -> if (w == "/proj/y") gate.await() else repo },
                onSubmit = { w, a, m, r, t, st, wt, b, _, _, _, _ -> captured = Submitted(w, a, m, r, t, st.size, wt, b); null },
            )
        }
        waitForIdle()
        onNodeWithText("main").assertIsDisplayed()

        // The most recent project moves to /proj/y while its repo info is still loading.
        sessions = listOf(session("s2", "/proj/y"), session("s1", "/proj/x"))
        waitForIdle()
        onNodeWithTag(WorkRowTags.WORK_IN).assertDoesNotExist()
        onNodeWithTag("launcher_submit").assertIsNotEnabled()

        gate.complete(repoY)
        waitForIdle()
        onNodeWithText("trunk").assertIsDisplayed()
        onNodeWithTag("launcher_message").performTextInput("go")
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals("/proj/y", captured?.workdir)
        assertEquals(true, captured?.worktree)
        assertEquals("trunk", captured?.baseBranch)
    }

    @Test fun a_drafts_branch_stays_with_the_drafts_folder() = runComposeUiTest {
        // No draft workdir → the stored branch belonged to some earlier default, not this project.
        pointerContent {
            Harness(
                sessions = listOf(session("s1", "/proj/x")),
                draft = LauncherDraft(baseBranch = "stale/branch"),
                repoInfo = repo,
            )
        }
        waitForIdle()
        onNodeWithText("main").assertIsDisplayed()
        onNodeWithText("stale/branch").assertDoesNotExist()
    }

    @Test fun local_is_remembered_for_that_folder_only_on_a_broker_before_152() = runComposeUiTest {
        var saved: LauncherPrefs? = null
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x"),
                repoInfo = repo,
                onPrefsChange = { saved = it },
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.WORK_IN).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.WORK_IN_LOCAL).performClick()
        waitForIdle()
        assertEquals(setOf(worktreeChoiceKey("", "/proj/x")), saved?.worktreeOff)
        onNodeWithTag(WorkRowTags.WORK_IN).assertTextContains("Local")
    }

    @Test fun a_remembered_opt_out_spawns_that_folder_without_a_worktree() = runComposeUiTest {
        var captured: Submitted? = null
        pointerContent {
            Harness(
                prefs = LauncherPrefs(worktreeOff = setOf(worktreeChoiceKey("", "/proj/x"))),
                draft = LauncherDraft(workdir = "/proj/x", text = "go"),
                repoInfo = repo,
                onSubmit = { w, a, m, r, t, st, wt, b, _, _, _, _ -> captured = Submitted(w, a, m, r, t, st.size, wt, b); null },
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.WORK_IN).assertTextContains("Local")
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(false, captured?.worktree)
        assertEquals(null, captured?.baseBranch)
    }

    @Test fun a_remembered_opt_out_applies_only_to_its_folder() = runComposeUiTest {
        val prefs = LauncherPrefs(worktreeOff = setOf(worktreeChoiceKey("", "/proj/other")))
        pointerContent { Harness(prefs = prefs, draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo) }
        waitForIdle()
        onNodeWithText("main").assertIsDisplayed()
        onNodeWithTag(WorkRowTags.WORK_IN).assertTextContains("Worktree")
    }

    /**
     * A failed spawn must show the BROKER's own refusal, verbatim — not a generic
     * "Failed to create session". `AppShell` throws the broker's message out of
     * `onSubmit`; this pins that the launcher renders that message in `launcher_error`.
     */
    @Test fun failed_submit_shows_the_brokers_refusal_text() = runComposeUiTest {
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "do it"),
                onSubmit = { _, _, _, _, _, _, _, _, _, _, _, _ ->
                    throw IllegalStateException("spawn refused: workdir is not a directory")
                },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("launcher_error").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("launcher_error")
            .assertTextEquals("spawn refused: workdir is not a directory")
    }

    // ── (3) the unions: slash menu, camera, worktree sheet, chrome, Enter, drafts ────────────────

    private fun cmd(name: String, insert: String? = null) =
        SlashCommand(id = name, family = "agent", name = name, insertText = insert)

    @Test fun slash_menu_offers_matches_and_inserts_under_a_pointer() = runComposeUiTest {
        // Commands load for a chosen project only.
        pointerContent { Harness(draft = LauncherDraft(workdir = "/proj/x"), commands = listOf(cmd("review"), cmd("refactor"))) }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("/re")
        waitForIdle()
        onNodeWithTag("launcher_slash_item_review").assertIsDisplayed()
        onNodeWithTag("launcher_slash_item_refactor").assertIsDisplayed()
        onNodeWithTag("launcher_slash_item_refactor").performClick()
        waitForIdle()
        // The token is replaced by the command's insert text — the menu closes with it.
        onNodeWithTag("launcher_slash_item_refactor").assertDoesNotExist()
        onNodeWithText("/refactor ").assertIsDisplayed()
    }

    @Test fun slash_menu_offers_matches_under_touch_too() = runComposeUiTest {
        touchContent { Harness(draft = LauncherDraft(workdir = "/proj/x"), commands = listOf(cmd("review"))) }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("/rev")
        waitForIdle()
        onNodeWithTag("launcher_slash_item_review").assertIsDisplayed()
    }

    @Test fun escape_dismisses_the_slash_menu_without_touching_the_draft() = runComposeUiTest {
        pointerContent { Harness(draft = LauncherDraft(workdir = "/proj/x"), commands = listOf(cmd("review"))) }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("/rev")
        waitForIdle()
        onNodeWithTag("launcher_slash_item_review").assertIsDisplayed()
        onNodeWithTag("launcher_message").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithTag("launcher_slash_item_review").assertDoesNotExist()
        onNodeWithText("/rev").assertIsDisplayed()
    }

    @Test fun a_physical_enter_submits_and_the_slash_menu_takes_it_first() = runComposeUiTest {
        var submits = 0
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = ""),
                commands = listOf(cmd("review")),
                onSubmit = { _, _, _, _, _, _, _, _, _, _, _, _ -> submits++; null },
            )
        }
        waitForIdle()
        // With the menu open, Enter PICKS the highlighted command instead of submitting.
        onNodeWithTag("launcher_message").performTextInput("/rev")
        waitForIdle()
        onNodeWithTag("launcher_message").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(0, submits, "Enter with the slash menu open must pick, not send")
        onNodeWithText("/review ").assertIsDisplayed()

        // Menu closed → the same key sends (jvm answers isFromPhysicalKeyboard = true).
        onNodeWithTag("launcher_message").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, submits)
    }

    @Test fun camera_rows_are_offered_only_where_the_platform_has_one() = runComposeUiTest {
        val phone = FakePlatform(caps = PHONE_CAPS)
        touchContent(platform = phone) { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_attach").performClick()
        waitForIdle()
        onNodeWithTag("attach_menu_photos").assertIsDisplayed()
        onNodeWithTag("attach_menu_files").assertIsDisplayed()
        onNodeWithTag("attach_menu_camera").assertIsDisplayed()
        onNodeWithTag("attach_menu_record_video").performClick()
        waitForIdle()
        assertEquals(listOf("video:$LAUNCHER_PICK_REQUESTER"), phone.captures)
    }

    @Test fun a_camera_less_touch_host_gets_photos_and_files_only() = runComposeUiTest {
        touchContent(platform = FakePlatform(caps = PHONE_CAPS.copy(camera = false))) { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_attach").performClick()
        waitForIdle()
        onNodeWithTag("attach_menu_files").assertIsDisplayed()
        onNodeWithTag("attach_menu_camera").assertDoesNotExist()
        onNodeWithTag("attach_menu_record_video").assertDoesNotExist()
    }

    @Test fun a_pointer_host_opens_the_file_dialog_with_no_menu() = runComposeUiTest {
        val desk = FakePlatform()
        pointerContent(platform = desk) { Harness() }
        waitForIdle()
        onNodeWithTag("launcher_attach").performClick()
        waitForIdle()
        onNodeWithTag("attach_menu_photos").assertDoesNotExist()
        assertEquals(LAUNCHER_PICK_REQUESTER, desk.pickedRequester)
    }

    @Test fun a_recreation_stashed_pick_is_staged_by_the_screen_that_asked() = runComposeUiTest {
        val phone = FakePlatform(caps = PHONE_CAPS)
        touchContent(platform = phone) { Harness() }
        waitForIdle()
        runBlocking { phone.pendingPicks.emit(dev.supermux.ui.platform.PickedFile("shot.jpg", "image/jpeg", EMPTY_SOURCE)) }
        waitForIdle()
        onNodeWithTag("launcher_staged_shot.jpg").assertIsDisplayed()
        // A staged file alone enables Send (no typed text needed).
        onNodeWithTag("launcher_submit").assertIsDisplayed()
    }

    // ── worktree: Android's sheet on touch, desktop's dialog under a pointer ─────────────────────

    private fun session(id: String, workdir: String) =
        SessionInfo(id = id, name = id, workdir = workdir, agent = "claude")

    private val repo = RepoInfo(
        eligible = true,
        currentBranch = "main",
        repoRoot = "/proj/x",
        branches = RepoBranches(local = listOf("main", "feat/one"), remote = emptyList()),
    )

    @Test fun the_touch_branch_sheet_picks_a_base_branch() = runComposeUiTest {
        touchContent { Harness(draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo) }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.SHEET).assertIsDisplayed()
        onNodeWithTag(WorkRowTags.BASE).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.row("feat/one")).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.USE_NEW).performClick()
        waitForIdle()
        // Using it dismisses the sheet, and the pill now reads the chosen base.
        onNodeWithTag(WorkRowTags.SHEET).assertDoesNotExist()
        onNodeWithTag(WorkRowTags.BRANCH).assertTextContains("feat/one")
    }

    @Test fun a_swipe_down_dismisses_the_branch_sheet_and_the_pill_reopens_it() = runComposeUiTest {
        // M3 hides the sheet BEFORE reporting the dismiss, so a guarded onDismissRequest would
        // strand it composed-but-invisible and the pill would become a no-op. Nothing guards it.
        touchContent { Harness(draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo) }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.SHEET).assertIsDisplayed()
        onNodeWithTag(WorkRowTags.SHEET).performTouchInput { swipeDown() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(WorkRowTags.SHEET).fetchSemanticsNodes().isEmpty() }
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(WorkRowTags.SHEET).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun a_pointer_host_gets_a_popover_under_the_pill_instead() = runComposeUiTest {
        pointerContent { Harness(draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo) }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.POPOVER).assertIsDisplayed()
        onNodeWithTag(WorkRowTags.SHEET).assertDoesNotExist()
    }

    // ── #152: the row on a broker that takes a source ──────────────────────────────────────────────

    /** The prototype's sample: the folder on feat/checkout-v2, a session with fix/cart-total, origin's feat/login-redirect. */
    private val storefront = GitBranches(
        current = "feat/checkout-v2",
        local = listOf(
            GitLocalBranch("main", isDefault = true, upstream = "origin/main", author = "deniz", at = 1),
            GitLocalBranch("feat/checkout-v2", openIn = GitOpenIn("this"), author = "you", at = 3),
            GitLocalBranch("fix/cart-total", openIn = GitOpenIn("session", "s-2", "Cart total off by one"), author = "you", at = 2),
        ),
        remote = listOf(GitRemoteBranch("origin/main", isDefault = true), GitRemoteBranch("origin/feat/login-redirect")),
    )
    private val clean = ProjectFolderState(branch = "feat/checkout-v2")

    private fun captureSource(into: MutableList<SessionSourceBody?>): suspend (String, String, String?, String?, String, List<StagedUpload>, Boolean, String?, String?, String?, String?, SessionSourceBody?) -> String? =
        { _, _, _, _, _, _, _, _, _, _, _, src -> into += src; null }

    @Test fun a_new_branch_named_from_the_message_from_the_default_base_is_what_start_sends() = runComposeUiTest {
        val sent = mutableListOf<SessionSourceBody?>()
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean,
                onSubmit = captureSource(sent),
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).assertTextContains("main")
        onNodeWithTag("launcher_message").performTextInput("Fix the login redirect loop")
        waitForIdle()
        onNodeWithTag(WorkRowTags.SUMMARY).assertTextContains("ahmet/fix-the-login-redirect", substring = true)
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<SessionSourceBody?>(SessionSourceBody(kind = "new", base = "main", name = "ahmet/fix-the-login-redirect", auto = true)), sent)
    }

    @Test fun the_new_branch_hint_says_where_the_prefix_is_set() = runComposeUiTest {
        pointerContent {
            Harness(draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo, projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean)
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        // One paragraph, as the prototype's: where the base comes from, then where the prefix is set.
        onNode(
            hasText("The base is fetched from origin first, so the branch starts from the latest main. Prefix ahmet/ is set in Settings › Machine › Chat worktrees."),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test fun no_prefix_says_it_is_off_and_where() = runComposeUiTest {
        pointerContent {
            Harness(draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo, projectPrefs = ProjectPrefs("worktree", ""), branches = storefront, folder = clean)
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNode(hasText("The branch prefix is off in Settings › Machine › Chat worktrees.", substring = true), useUnmergedTree = true).assertExists()
    }

    @Test fun a_branch_only_origin_has_is_tracked() = runComposeUiTest {
        val sent = mutableListOf<SessionSourceBody?>()
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "go"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean,
                onSubmit = captureSource(sent),
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.TAB_EXISTING).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.row("origin/feat/login-redirect")).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).assertTextContains("feat/login-redirect")
        onNodeWithTag(WorkRowTags.SUMMARY).assertTextContains("origin/feat/login-redirect", substring = true)
        onNodeWithTag("launcher_submit").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(listOf<SessionSourceBody?>(SessionSourceBody(kind = "existing", branch = "origin/feat/login-redirect")), sent)
    }

    @Test fun local_is_the_users_remembered_pick_and_a_new_pick_is_kept_on_the_broker() = runComposeUiTest {
        val put = mutableListOf<String>()
        val sent = mutableListOf<SessionSourceBody?>()
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "go"), repoInfo = repo,
                projectPrefs = ProjectPrefs("local", "ahmet/"), branches = storefront, folder = clean,
                prefsPut = put, onSubmit = captureSource(sent),
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.WORK_IN).assertTextContains("Local")
        onNodeWithTag(WorkRowTags.LOCAL_BRANCH).assertTextContains("feat/checkout-v2")
        // The folder switches at Start; a branch another session has open is worked on in that session's copy.
        onNodeWithTag(WorkRowTags.LOCAL_BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.row("fix/cart-total")).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.SUMMARY).assertTextContains("Cart total off by one", substring = true)
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<SessionSourceBody?>(SessionSourceBody(kind = "reuse", sessionId = "s-2")), sent)
        onNodeWithTag(WorkRowTags.WORK_IN).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.WORK_IN_WORKTREE).performClick()
        waitForIdle()
        assertEquals(listOf("/proj/x=worktree"), put)
    }

    @Test fun a_pick_the_broker_cannot_keep_is_said_under_the_row() = runComposeUiTest {
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean, prefsSaveFails = true,
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.NOT_REMEMBERED).assertDoesNotExist()
        onNodeWithTag(WorkRowTags.WORK_IN).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.WORK_IN_LOCAL).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.WORK_IN).assertTextContains("Local")
        onNodeWithTag(WorkRowTags.NOT_REMEMBERED).assertIsDisplayed()
    }

    @Test fun a_folder_with_changes_says_why_and_only_its_branch_and_worktree_ones_stay_pickable() = runComposeUiTest {
        val sent = mutableListOf<SessionSourceBody?>()
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "go"), repoInfo = repo,
                projectPrefs = ProjectPrefs("local", "ahmet/"), branches = storefront,
                folder = ProjectFolderState(branch = "feat/checkout-v2", clean = false, changed = 2),
                onSubmit = captureSource(sent),
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.LOCAL_BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.DIRTY).assertTextContains("2 uncommitted changes", substring = true)
        onNodeWithTag(WorkRowTags.row("main")).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.POPOVER).assertIsDisplayed()
        onNodeWithTag(WorkRowTags.row("feat/checkout-v2")).performClick()
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<SessionSourceBody?>(SessionSourceBody(kind = "local")), sent)
    }

    // ── #152 C2 (Pull request) + C3 (the ways out) ──────────────────────────────────────────────────

    private val pr123 = OpenPr(number = 123, title = "Fix login redirect loop", head = "feat/login-redirect", base = "main", author = "deniz", headRepo = "acme/storefront", checks = "ok")
    private val pr125 = OpenPr(number = 125, title = "Speed up product search", head = "fix/cart-total", base = "main", headRepo = "acme/storefront", localAhead = 2)
    private val prs = OpenPrList(status = "ok", forge = "github", repo = "github.com/acme/storefront", prs = listOf(pr123))

    @Test fun a_pull_request_is_picked_from_the_list_or_by_its_number_and_start_sends_it() = runComposeUiTest {
        val sent = mutableListOf<SessionSourceBody?>()
        val asked = mutableListOf<String>()
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "go"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean, prs = prs,
                prRead = { ref -> asked += ref; OpenPrRead(status = "not_open", state = "merged") },
                onSubmit = captureSource(sent),
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.TAB_PR).assertTextContains("Pull request").performClick()
        waitForIdle()
        // A number the list doesn't have is read from the forge, and what it says is said.
        onNodeWithTag(WorkRowTags.PR_SEARCH).performTextInput("#5")
        waitUntil(timeoutMillis = 5_000) { asked.isNotEmpty() }
        waitForIdle()
        onNodeWithTag(WorkRowTags.PR_NOTE).assertTextContains("PR #5 is already merged.")
        onNodeWithTag(WorkRowTags.PR_SEARCH).performTextClearance()
        waitForIdle()
        onNodeWithTag(WorkRowTags.pr(123)).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).assertTextContains("#123")
        onNodeWithTag(WorkRowTags.SUMMARY).assertTextContains("update PR #123", substring = true)
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<SessionSourceBody?>(SessionSourceBody(kind = "pr", number = 123)), sent)
    }

    // ── #152 C4: #72's "Check Out Pull Request…" opens the launcher on a project with a PR's number ────────

    private val storefrontProject = ProjectDto(id = "sf", name = "Storefront", locations = listOf(ProjectLocationDto("sf-l0", "/proj/x")))

    @Test fun opened_for_a_pull_request_the_launcher_reads_it_again_and_picks_it_in_a_worktree() = runComposeUiTest {
        val sent = mutableListOf<SessionSourceBody?>()
        val asked = mutableListOf<String>()
        val put = mutableListOf<String>()
        pointerContent {
            Harness(
                draft = LauncherDraft(text = "Address the review comments"), repoInfo = repo,
                projectPrefs = ProjectPrefs("local", "ahmet/"), branches = storefront, folder = clean, prs = prs,
                prRead = { ref -> asked += ref; OpenPrRead(status = "found", pr = pr123, forge = "github") },
                prefsPut = put, catalog = listOf(storefrontProject), initialProjectId = "sf", initialPrNumber = 123,
                onSubmit = captureSource(sent),
            )
        }
        waitUntil(timeoutMillis = 5_000) { asked.isNotEmpty() }
        waitForIdle()
        assertEquals(listOf("#123"), asked)
        // Local is the user's pick for this project; a PR is worked on in a worktree, and the pick is left as it was.
        onNodeWithTag(WorkRowTags.WORK_IN).assertTextContains("Worktree")
        onNodeWithTag(WorkRowTags.BRANCH).assertTextContains("#123")
        onNodeWithTag(WorkRowTags.SUMMARY).assertTextContains("update PR #123", substring = true)
        assertEquals(emptyList(), put)
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<SessionSourceBody?>(SessionSourceBody(kind = "pr", number = 123)), sent)
    }

    @Test fun a_pull_request_that_cannot_be_opened_says_why_under_the_row() = runComposeUiTest {
        pointerContent {
            Harness(
                draft = LauncherDraft(text = "go"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean, prs = prs,
                prRead = { OpenPrRead(status = "not_open", state = "merged", forge = "github") },
                catalog = listOf(storefrontProject), initialProjectId = "sf", initialPrNumber = 118,
            )
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(WorkRowTags.PR_LINK_NOTE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(WorkRowTags.PR_LINK_NOTE).assertTextContains("PR #118 is already merged.")
        // Nothing is picked for it: the row is as the project left it.
        onNodeWithTag(WorkRowTags.BRANCH).assertTextContains("New branch", substring = true)
    }

    @Test fun a_pull_request_the_host_could_not_read_says_so() = runComposeUiTest {
        pointerContent {
            Harness(
                draft = LauncherDraft(text = "go"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean, prs = prs,
                prRead = { null }, catalog = listOf(storefrontProject), initialProjectId = "sf", initialPrNumber = 118,
            )
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(WorkRowTags.PR_LINK_NOTE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(WorkRowTags.PR_LINK_NOTE).assertTextContains("Couldn’t read PR #118: the host didn’t answer.")
    }

    @Test fun no_remote_means_no_pull_request_tab() = runComposeUiTest {
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean,
                prs = OpenPrList(status = "unavailable", reason = "no_remote"),
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.TAB_EXISTING).assertIsDisplayed()
        onNodeWithTag(WorkRowTags.TAB_PR).assertDoesNotExist()
    }

    @Test fun the_folders_branch_waits_for_a_way_out_and_moving_the_folder_happens_at_start() = runComposeUiTest {
        val sent = mutableListOf<SessionSourceBody?>()
        val switched = mutableListOf<String>()
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "go"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean, prs = prs,
                switched = switched, onSubmit = captureSource(sent),
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.TAB_EXISTING).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.row("feat/checkout-v2")).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.CARD_HEAD).assertTextContains("is checked out in your folder", substring = true)
        onNodeWithTag("launcher_submit").assertIsNotEnabled()
        onNodeWithTag(WorkRowTags.CARD_MOVE).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.CARD).assertDoesNotExist()
        assertTrue(switched.isEmpty(), "nothing moves before Start")
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf("/proj/x=main"), switched)
        assertEquals(listOf<SessionSourceBody?>(SessionSourceBody(kind = "existing", branch = "feat/checkout-v2")), sent)
    }

    @Test fun a_branch_another_session_has_offers_that_session_or_a_new_branch_from_it() = runComposeUiTest {
        val opened = mutableListOf<String>()
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "go"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean,
                onOpenSessionFromCard = { opened += it },
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.TAB_EXISTING).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.row("fix/cart-total")).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.CARD_OPEN).performClick()
        assertEquals(listOf("s-2"), opened)
        onNodeWithTag(WorkRowTags.CARD_NEW_FROM).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.CARD).assertDoesNotExist()
        onNodeWithTag(WorkRowTags.BRANCH).assertTextContains("fix/cart-total")
        onNodeWithTag(WorkRowTags.SUMMARY).assertTextContains("ahmet/cart-total-2", substring = true)
    }

    @Test fun local_commits_the_pr_lacks_wait_for_whose_version_wins() = runComposeUiTest {
        val sent = mutableListOf<SessionSourceBody?>()
        val free = storefront.copy(local = storefront.local.map { if (it.name == "fix/cart-total") it.copy(openIn = null) else it })
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "go"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = free, folder = clean,
                prs = prs.copy(prs = listOf(pr123, pr125)), onSubmit = captureSource(sent),
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.BRANCH).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.TAB_PR).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.pr(125)).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.CARD_HEAD).assertTextContains("has 2 commits that aren't on #125", substring = true)
        onNodeWithTag("launcher_submit").assertIsNotEnabled()
        onNodeWithTag(WorkRowTags.CARD_USE_PR).performClick()
        waitForIdle()
        onNodeWithTag(WorkRowTags.SUMMARY).assertTextContains("fix/cart-total-backup", substring = true)
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf<SessionSourceBody?>(SessionSourceBody(kind = "pr", number = 125, divergedPolicy = "pr")), sent)
    }

    @Test fun an_old_client_side_opt_out_moves_to_the_broker_once() = runComposeUiTest {
        val put = mutableListOf<String>()
        var saved: LauncherPrefs? = null
        pointerContent {
            Harness(
                prefs = LauncherPrefs(worktreeOff = setOf(worktreeChoiceKey("", "/proj/x"))),
                draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo,
                projectPrefs = ProjectPrefs("worktree", "ahmet/"), branches = storefront, folder = clean,
                prefsPut = put, onPrefsChange = { saved = it },
            )
        }
        waitForIdle()
        onNodeWithTag(WorkRowTags.WORK_IN).assertTextContains("Local")
        assertEquals(listOf("/proj/x=local"), put)
        assertEquals(emptySet(), saved?.worktreeOff)
    }

    // ── chrome (cluster E's gate) ───────────────────────────────────────────────────────────────

    @Test fun a_standalone_compact_mount_paints_the_bar_and_backs_out() = runComposeUiTest {
        var backs = 0
        touchContent { Harness(standalone = true, onBack = { backs++ }) }
        waitForIdle()
        onNodeWithTag("launcher_top_bar").assertIsDisplayed()
        onNodeWithText("New session").assertIsDisplayed()
        onNodeWithTag("launcher_back").performClick()
        assertEquals(1, backs)
    }

    @Test fun a_pane_mount_paints_no_bar_even_when_the_window_is_compact() = runComposeUiTest {
        // Desktop's launcher pane: the shell painted the chrome, so `topBarShown` keeps the screen
        // bar-less however narrow the window gets.
        setPlatformContent(pointer = true, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Pointer) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionLauncherScreen(
                    sessions = emptyList(),
                    home = "/home/u",
                    onBack = {},
                    loadPrefs = { LauncherPrefs() },
                    loadDraft = { LauncherDraft() },
                    onSubmit = { _, _, _, _, _, _, _, _, _, _, _, _ -> null },
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_top_bar").assertDoesNotExist()
        onNodeWithTag("launcher_message").assertIsDisplayed()
    }

    // ── drafts through UiPrefs (the F1 seam both hosts now share) ────────────────────────────────

    @Test fun the_draft_round_trips_through_ui_prefs() = runComposeUiTest {
        val prefs = UiPrefs(dev.supermux.ui.chat.FakeSettingsStore())
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "seed"),
                onDraftChange = { runBlocking { prefs.putLauncherDraft(it) } },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput(" more")
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { prefs.launcherDraft.first().text }.contains("more")
        }
        val stored = runBlocking { prefs.launcherDraft.first() }
        assertEquals("/proj/x", stored.workdir)
        assertTrue(stored.text.endsWith(" more"), "got '${stored.text}'")
    }

    @Test fun a_created_session_is_handed_to_on_open_session() = runComposeUiTest {
        val opened = mutableListOf<String>()
        pointerContent {
            Harness(
                draft = LauncherDraft(workdir = "/proj/x", text = "go"),
                onOpenSession = { opened.add(it) },
                onSubmit = { _, _, _, _, _, _, _, _, _, _, _, _ -> "s-new" },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        assertEquals(listOf("s-new"), opened)
    }

    private companion object {
        /** A phone: camera, push, no file system — enough for the camera gate. */
        val PHONE_CAPS = Caps(
            push = true,
            camera = true,
            tray = false,
            externalDisplay = true,
            hardwareVideoDecode = true,
            localBroker = false,
            multiWindow = false,
            fileSystem = false,
        )

        val EMPTY_SOURCE = dev.supermux.net.ByteArrayChunkSource(byteArrayOf(1, 2))
    }
}
