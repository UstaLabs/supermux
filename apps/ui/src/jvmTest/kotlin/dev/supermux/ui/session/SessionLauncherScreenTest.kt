package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RepoBranches
import dev.supermux.net.RepoInfo
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import dev.supermux.state.StagedUpload
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
 *  1. The PURE settle-vs-change helpers ([shouldResetModelOnAgentChange] /
 *     [shouldResetBaseBranchOnWorkdirChange]) + [filterBranches] are unit-tested
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
        commands: List<SlashCommand> = emptyList(),
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
        onSubmit: suspend (String, String, String?, String?, String, List<StagedUpload>, Boolean, String?, String?) -> String? =
            { _, _, _, _, _, _, _, _, _ -> null },
    ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            SessionLauncherScreen(
                sessions = sessions,
                home = "/home/u",
                onBack = onBack,
                actions = LauncherActions(
                    listProjects = { projects },
                    validatePath = { null },
                    launcherModels = { models(it) },
                    launcherReasoning = { a, m -> reasoning(a, m) },
                    launcherRepoInfo = { _, _ -> repoInfo },
                    launcherCommands = { _, _ -> commands },
                ),
                loadPrefs = { prefs },
                onPrefsChange = onPrefsChange,
                loadDraft = { draft },
                onDraftChange = onDraftChange,
                onClearDraft = onClearDraft,
                onSubmit = onSubmit,
                onOpenSession = onOpenSession,
                standalone = standalone,
                workspaceWorkdir = workspaceWorkdir,
                workspaceLabel = workspaceLabel,
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
        onNodeWithTag("launcher_agent_pill").assertIsDisplayed()
        onNodeWithTag("launcher_model_picker").assertIsDisplayed()
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
                onSubmit = { w, a, m, r, t, s, wt, b, _replaceDraftId ->
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
                onSubmit = { w, a, m, r, t, s, wt, b, _ ->
                    captured = Submitted(w, a, m, r, t, s.size, wt, b)
                    null
                },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_project_field").assertDoesNotExist()
        onNodeWithTag("launcher_worktree").assertDoesNotExist()
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
                onSubmit = { w, a, m, r, t, s, wt, b, _ ->
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
                onSubmit = { _, _, _, _, _, _, _, _, _ -> throw IllegalStateException("nope") },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_submit").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        assertEquals("keep me", drafts.lastOrNull()?.text)
    }

    @Test fun agent_change_resets_model_to_default() = runComposeUiTest {
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

        // Switch agent → the model resets to Default (genuine change, not a restore-settle).
        onNodeWithTag("launcher_agent_pill").performClick()
        onNodeWithTag("agent_codex").performClick()
        waitForIdle()
        onNodeWithText("Default").assertIsDisplayed()
        onNodeWithText("Claude X").assertDoesNotExist()
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
                onSubmit = { _, _, _, _, _, _, _, _, _ ->
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
        pointerContent { Harness(commands = listOf(cmd("review"), cmd("refactor"))) }
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
        touchContent { Harness(commands = listOf(cmd("review"))) }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("/rev")
        waitForIdle()
        onNodeWithTag("launcher_slash_item_review").assertIsDisplayed()
    }

    @Test fun escape_dismisses_the_slash_menu_without_touching_the_draft() = runComposeUiTest {
        pointerContent { Harness(commands = listOf(cmd("review"))) }
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
                onSubmit = { _, _, _, _, _, _, _, _, _ -> submits++; null },
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

    private val repo = RepoInfo(
        eligible = true,
        currentBranch = "main",
        repoRoot = "/proj/x",
        branches = RepoBranches(local = listOf("main", "feat/one"), remote = emptyList()),
    )

    @Test fun the_touch_worktree_sheet_picks_a_base_branch() = runComposeUiTest {
        touchContent { Harness(draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo) }
        waitForIdle()
        onNodeWithTag("launcher_worktree").performClick()
        waitForIdle()
        onNodeWithTag("launcher_worktree_sheet").assertIsDisplayed()
        onNodeWithTag("launcher_branch_search").assertIsDisplayed()
        onNodeWithTag("launcher_branch_feat/one").performClick()
        waitForIdle()
        // Picking dismisses the sheet and the pill now reads the chosen base.
        onNodeWithTag("launcher_worktree_sheet").assertDoesNotExist()
        onNodeWithText("feat/one").assertIsDisplayed()
    }

    @Test fun a_swipe_down_dismisses_the_worktree_sheet_and_the_pill_reopens_it() = runComposeUiTest {
        // M3 hides the sheet BEFORE reporting the dismiss, so a guarded onDismissRequest would
        // strand it composed-but-invisible and the pill would become a no-op. Nothing guards it.
        val gate = CompletableDeferred<RepoInfo?>()
        touchContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionLauncherScreen(
                    sessions = emptyList(),
                    home = "/home/u",
                    onBack = {},
                    actions = LauncherActions(
                        // The first (unfetched) call answers at once; the picker's fetch hangs, so
                        // the swipe below lands while a branch fetch is still in flight.
                        launcherRepoInfo = { _, fetch -> if (fetch) gate.await() else repo },
                    ),
                    loadPrefs = { LauncherPrefs() },
                    loadDraft = { LauncherDraft(workdir = "/proj/x") },
                    onSubmit = { _, _, _, _, _, _, _, _, _ -> null },
                    standalone = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_worktree").performClick()
        waitForIdle()
        onNodeWithTag("launcher_worktree_sheet").assertIsDisplayed()

        onNodeWithTag("launcher_worktree_sheet").performTouchInput { swipeDown() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("launcher_worktree_sheet").fetchSemanticsNodes().isEmpty()
        }
        gate.complete(repo)
        waitForIdle()

        onNodeWithTag("launcher_worktree").performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("launcher_worktree_sheet").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun a_pointer_host_gets_the_worktree_dialog_instead() = runComposeUiTest {
        pointerContent { Harness(draft = LauncherDraft(workdir = "/proj/x"), repoInfo = repo) }
        waitForIdle()
        onNodeWithTag("launcher_worktree").performClick()
        waitForIdle()
        onNodeWithTag("launcher_worktree_dialog").assertIsDisplayed()
        onNodeWithTag("launcher_worktree_sheet").assertDoesNotExist()
        onNodeWithTag("launcher_worktree_toggle").assertIsDisplayed()
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
                    onSubmit = { _, _, _, _, _, _, _, _, _ -> null },
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
                onSubmit = { _, _, _, _, _, _, _, _, _ -> "s-new" },
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
