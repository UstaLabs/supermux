package dev.supermux.desktop.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.ForgeAccount
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeSearchResponse
import dev.supermux.net.ModelInfo
import dev.supermux.net.PathValidation
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RemoteRepo
import dev.supermux.net.RepoBranches
import dev.supermux.net.RepoInfo
import dev.supermux.proto.SessionInfo
import dev.supermux.session.formatWorkdir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
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
        // A restored draft workdir survives an EMPTY project list now — that reset
        // used to fire on "could not enumerate projects" and silently rewrite the
        // workdir to "~". Kept parameterised so a test can still exercise the
        // genuine "workdir is not among the known projects" reset.
        projects: List<String> = emptyList(),
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
                loadProjects = { projects },
                validatePath = { null },
                loadModels = { models(it) },
                loadReasoningLevels = { a, m -> reasoning(a, m) },
                loadRepoInfo = { _, _ -> repoInfo },
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

    // ── Forge omnibox (desktop-parity Task 4) ───────────────────────────────────────────────────

    private fun forgeConn(
        id: String = "c1",
        host: String = "github.com",
        login: String = "alice",
    ) = ForgeConnection(id = id, host = host, account = ForgeAccount(login = login))

    private fun remote(
        connectionId: String = "c1",
        owner: String = "alice",
        name: String = "widget",
    ) = RemoteRepo(
        connectionId = connectionId,
        owner = owner,
        name = name,
        fullName = "$owner/$name",
    )

    private fun searchOk(vararg repos: RemoteRepo) = ForgeSearchResponse(repos = repos.toList())

    /**
     * Mirrors SessionLauncherScreen's workdir field + ProjectPicker wiring:
     * onPick updates the displayed workdir label (formatWorkdir), not a bare capture.
     */
    @Composable
    private fun WorkdirPickerHarness(
        loadForges: suspend () -> List<ForgeConnection> = { emptyList() },
        searchForge: suspend (String) -> ForgeSearchResponse? = { ForgeSearchResponse() },
        cloneForge: suspend (String, String, String) -> String? = { _, _, _ -> null },
        createLocalRepo: suspend (String) -> String? = { null },
        createForge: suspend (String, String) -> String? = { _, _ -> null },
        projects: List<String> = emptyList(),
    ) {
        val home = "/home/u"
        var workdir by remember { mutableStateOf("~") }
        var menu by remember { mutableStateOf(true) }
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            Column {
                Text(
                    formatWorkdir(workdir, home),
                    modifier = Modifier.testTag("launcher_workdir_label"),
                )
                Box {
                    ProjectPicker(
                        expanded = menu,
                        current = workdir,
                        projects = projects,
                        home = home,
                        validatePath = { null },
                        loadForges = loadForges,
                        searchForge = searchForge,
                        cloneForge = cloneForge,
                        createLocalRepo = createLocalRepo,
                        createForge = createForge,
                        onPick = { workdir = it },
                        onDismiss = { menu = false },
                    )
                }
            }
        }
    }

    @Test fun project_picker_create_local_lands_path_in_launcher_workdir() = runComposeUiTest {
        setContent {
            WorkdirPickerHarness(createLocalRepo = { name -> "/home/u/$name" })
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("brand-new")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_create_local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("forge_create_local").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("launcher_workdir_label").assertTextEquals("~/brand-new")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Test fun project_picker_clone_success_lands_path_in_launcher_workdir() = runComposeUiTest {
        val cloned = AtomicBoolean(false)
        setContent {
            WorkdirPickerHarness(
                loadForges = { listOf(forgeConn()) },
                searchForge = { searchOk(remote()) },
                cloneForge = { cid, owner, name ->
                    cloned.set(true)
                    assertEquals("c1", cid)
                    assertEquals("alice", owner)
                    assertEquals("widget", name)
                    "/home/u/widget"
                },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("widget")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_clone_alice/widget").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("forge_clone_alice/widget").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("launcher_workdir_label").assertTextEquals("~/widget")
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(cloned.get())
    }

    @Test fun project_picker_clone_failure_surfaces_error_keeps_query_and_progress_label() = runComposeUiTest {
        var dismissed = false
        val gate = CompletableDeferred<Unit>()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = emptyList(),
                        home = "/home/u",
                        validatePath = { null },
                        loadForges = { listOf(forgeConn()) },
                        searchForge = { searchOk(remote(name = "failme")) },
                        cloneForge = { _, _, _ ->
                            gate.await()
                            null
                        },
                        onPick = {},
                        onDismiss = { dismissed = true },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("failme")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_clone_alice/failme").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("forge_clone_alice/failme").performClick()
        waitForIdle()
        // Progress overlay + specific wording while clone is in flight.
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("launcher_forge_resolving").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("launcher_forge_resolving_label").assertIsDisplayed()
        onNodeWithText("Cloning alice/failme…").assertIsDisplayed()
        onNodeWithTag("launcher_forge_cancel").assertIsDisplayed()
        // Query retained during resolve.
        onNodeWithTag("launcher_project_search").assertIsDisplayed()

        gate.complete(Unit)
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("launcher_forge_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertFalse(dismissed)
        onNodeWithTag("launcher_project_menu").assertIsDisplayed()
        // Search query retained after failure (tag is unique; text nodes also contain "failme").
        onNodeWithTag("launcher_project_search").assertTextEquals("failme")
    }

    @Test fun project_picker_clone_cancel_aborts_keeps_picker_and_query() = runComposeUiTest {
        val finished = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = emptyList(),
                        home = "/home/u",
                        validatePath = { null },
                        loadForges = { listOf(forgeConn()) },
                        searchForge = { searchOk(remote(name = "slow")) },
                        cloneForge = { _, _, _ ->
                            started.complete(Unit)
                            hold.await()
                            finished.set(true)
                            "/home/u/slow"
                        },
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("slow")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_clone_alice/slow").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("forge_clone_alice/slow").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { started.isCompleted }
        onNodeWithTag("launcher_forge_resolving").assertIsDisplayed()
        onNodeWithTag("launcher_forge_cancel").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("launcher_forge_resolving").assertDoesNotExist()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // Cancel must not land a path and must keep the menu + query.
        onNodeWithTag("launcher_project_menu").assertIsDisplayed()
        assertFalse(finished.get())
        hold.complete(Unit) // release any leftover coroutine
    }

    @Test fun project_picker_search_5xx_shows_error_not_empty() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = emptyList(),
                        home = "/home/u",
                        validatePath = { null },
                        loadForges = { listOf(forgeConn()) },
                        searchForge = { null }, // transport/5xx
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("widget")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("launcher_forge_search_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Couldn't search repositories — check the connection and try again.")
            .assertIsDisplayed()
        // Must NOT look like a successful empty search.
        onNodeWithTag("launcher_forge_empty").assertDoesNotExist()
    }

    @Test fun project_picker_empty_search_shows_no_repos_message() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = emptyList(),
                        home = "/home/u",
                        validatePath = { null },
                        loadForges = { listOf(forgeConn()) },
                        searchForge = { searchOk() },
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("zzzz")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("launcher_forge_empty").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("No repos match \"zzzz\".").assertIsDisplayed()
        onNodeWithTag("launcher_forge_search_error").assertDoesNotExist()
    }

    @Test fun project_picker_slow_search_shows_searching_indicator() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = emptyList(),
                        home = "/home/u",
                        validatePath = { null },
                        loadForges = { listOf(forgeConn()) },
                        searchForge = {
                            gate.await()
                            searchOk(remote())
                        },
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("widget")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("launcher_forge_searching").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Searching repos…").assertIsDisplayed()
        gate.complete(Unit)
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_clone_alice/widget").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Test fun project_picker_paging_load_more_reveals_remaining_repos() = runComposeUiTest {
        val many = (1..FORGE_OMNIBOX_PAGE_SIZE + 3).map { i ->
            remote(name = "repo$i")
        }
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = emptyList(),
                        home = "/home/u",
                        validatePath = { null },
                        loadForges = { listOf(forgeConn()) },
                        searchForge = { searchOk(*many.toTypedArray()) },
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("repo")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_clone_alice/repo1").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // Page 1 only — last items hidden until Load more.
        onNodeWithTag("forge_clone_alice/repo${FORGE_OMNIBOX_PAGE_SIZE + 1}").assertDoesNotExist()
        onNodeWithTag("launcher_forge_load_more").performScrollTo().assertIsDisplayed()
        onNodeWithTag("launcher_forge_load_more").performClick()
        waitForIdle()
        onNodeWithTag("forge_clone_alice/repo${FORGE_OMNIBOX_PAGE_SIZE + 1}").performScrollTo().assertIsDisplayed()
    }

    @Test fun project_picker_omnibox_key_actions_escape_enter_arrows() {
        // Pure decision table for the omnibox key handler — UI injection of arrow keys into a
        // focused OutlinedTextField is unreliable under skiko; the handler itself is unit-tested.
        assertEquals(
            OmniboxKeyAction.Dismiss,
            omniboxKeyAction(Key.Escape, highlight = 0, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.CancelResolve,
            omniboxKeyAction(Key.Escape, highlight = 0, count = 2, resolving = true),
        )
        assertEquals(
            OmniboxKeyAction.Activate(0),
            omniboxKeyAction(Key.Enter, highlight = 0, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.Activate(1),
            omniboxKeyAction(Key.Enter, highlight = 1, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.MoveHighlight(1),
            omniboxKeyAction(Key.DirectionDown, highlight = 0, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.MoveHighlight(0),
            omniboxKeyAction(Key.DirectionUp, highlight = 1, count = 2, resolving = false),
        )
        assertEquals(
            OmniboxKeyAction.MoveHighlight(0),
            omniboxKeyAction(Key.DirectionDown, highlight = 1, count = 2, resolving = false),
        ) // wraps
        assertNull(omniboxKeyAction(Key.A, highlight = 0, count = 2, resolving = false))
        assertNull(omniboxKeyAction(Key.Enter, highlight = 0, count = 0, resolving = false))
    }

    @Test fun project_picker_search_is_entry_point_when_opened() = runComposeUiTest {
        // FocusRequester.requestFocus() runs on open; under DropdownMenu + headless skiko the
        // popup often won't report IsFocused, so we assert the search field is the interactive
        // entry (present + enabled) rather than assertIsFocused.
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = listOf("/home/u/alpha"),
                        home = "/home/u",
                        validatePath = { null },
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").assertIsDisplayed()
        onNodeWithTag("launcher_project_search").assertIsEnabled()
        onNodeWithTag("launcher_omnibox_root").assertIsDisplayed()
    }

    @Test fun project_picker_create_on_forge_uses_connection_id() = runComposeUiTest {
        val picked = AtomicReference<String?>(null)
        val target = AtomicReference<String?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box {
                    ProjectPicker(
                        expanded = true,
                        current = "~",
                        projects = emptyList(),
                        home = "/home/u",
                        validatePath = { null },
                        loadForges = { listOf(forgeConn(id = "conn-9", login = "bob")) },
                        searchForge = { searchOk() },
                        createForge = { cid, name ->
                            target.set(cid)
                            "/home/u/$name"
                        },
                        onPick = { picked.set(it) },
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("solo-proj")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("forge_create_conn-9").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("forge_create_conn-9").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { picked.get() != null }
        assertEquals("conn-9", target.get())
        assertEquals("/home/u/solo-proj", picked.get())
    }
}
