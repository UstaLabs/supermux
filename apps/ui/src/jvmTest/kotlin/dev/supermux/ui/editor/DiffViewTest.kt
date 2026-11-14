package dev.supermux.ui.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.net.DiffFile
import dev.supermux.net.RefCommit
import dev.supermux.net.RepoDiff
import dev.supermux.net.RepoRefs
import dev.supermux.net.ReviewComment
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for [DiffView] (M4g-2 Task 4). Unlike the rest of the editor, DiffView is pure
 * Compose so it hosts cleanly under [runComposeUiTest] with no engine seam needed — every
 * interactive surface (file expand, comment composer, resolve, submit, wrap toggle) is driven and
 * asserted directly here.
 */
@OptIn(ExperimentalTestApi::class)
class DiffViewTest {

    private fun oneRepoDiff() = listOf(
        RepoDiff(
            repo = "",
            files = listOf(
                DiffFile(path = "a.txt", status = "modified", diff = "@@ -1,2 +1,2 @@\n-old\n+new\n context\n"),
            ),
        ),
    )

    private fun twoRepoDiff() = listOf(
        RepoDiff(repo = "", files = listOf(DiffFile(path = "a.txt", status = "modified", diff = "@@ -1 +1 @@\n+new\n"))),
        RepoDiff(repo = "lib", files = listOf(DiffFile(path = "b.txt", status = "added", diff = "@@ -0,0 +1 @@\n+hi\n"))),
    )

    private fun host(
        repos: List<RepoDiff>,
        comments: List<ReviewComment> = emptyList(),
        base: String = "session-start",
        refs: List<RepoRefs> = emptyList(),
        onSetBase: (String) -> Unit = {},
        onAddComment: suspend (String, String, Int, String, String, String) -> Unit = { _, _, _, _, _, _ -> },
        onResolve: suspend (String) -> Unit = {},
        onSubmit: suspend () -> Unit = {},
        onReload: () -> Unit = {},
        onClose: () -> Unit = {},
        autoExpandAll: Boolean = false,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        readFile: (suspend (String, String) -> Result<String>)? = null,
        prefs: UiPrefs = UiPrefs(InMemorySettingsStore()),
        stage: (suspend (String, List<String>, Boolean) -> Boolean)? = null,
        gitWrite: (suspend (String, dev.supermux.net.CommitRequest) -> dev.supermux.net.GitWriteResult?)? = null,
        baseName: String? = null,
        baseBehind: Int = 0,
        gitLatest: (suspend () -> dev.supermux.net.LatestResult?)? = null,
        askAgent: ((String) -> Unit)? = null,
        aheadOfBase: Int = 0,
        mergeReadiness: (suspend () -> dev.supermux.net.FinishReadiness?)? = null,
        mergeIntoBase: (() -> Unit)? = null,
        mergeJob: dev.supermux.proto.FinishJobDto? = null,
        history: (suspend () -> dev.supermux.net.GitHistory?)? = null,
    ): @androidx.compose.runtime.Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides prefs,
            dev.supermux.ui.platform.LocalPlatform provides dev.supermux.ui.platform.FakePlatform(),
            LocalWindowWidthClass provides widthClass,
            LocalInputMode provides
                if (widthClass == WindowWidthClass.Compact) InputMode.Touch else InputMode.Pointer,
        ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            DiffView(
                repos = repos,
                comments = comments,
                base = base,
                refs = refs,
                onSetBase = onSetBase,
                onAddComment = onAddComment,
                onResolve = onResolve,
                onSubmit = onSubmit,
                onReload = onReload,
                onClose = onClose,
                autoExpandAll = autoExpandAll,
                readFile = readFile,
                stage = stage,
                gitWrite = gitWrite,
                baseName = baseName,
                baseBehind = baseBehind,
                gitLatest = gitLatest,
                askAgent = askAgent,
                aheadOfBase = aheadOfBase,
                mergeReadiness = mergeReadiness,
                mergeIntoBase = mergeIntoBase,
                mergeJob = mergeJob,
                history = history,
            )
        }
        }
    }

    // ── #72: what goes into the next commit ──────────────────────────────────────────────────

    private fun stagedDiff() = listOf(
        RepoDiff(repo = "", files = listOf(
            DiffFile(path = "a.txt", status = "modified", staged = "all"),
            DiffFile(path = "b.txt", status = "modified", staged = "none"),
            DiffFile(path = "c.txt", status = "modified"),
        )),
        RepoDiff(repo = "lib", files = listOf(DiffFile(path = "d.txt", status = "added", staged = "partial"))),
    )

    @Test
    fun a_file_with_something_uncommitted_has_a_box_and_a_click_puts_it_in_or_takes_it_out() = runComposeUiTest {
        val calls = mutableListOf<Triple<String, List<String>, Boolean>>()
        setContent(host(stagedDiff(), stage = { repo, paths, on -> calls += Triple(repo, paths, on); true }))
        waitForIdle()
        onNodeWithTag("diff_include_c.txt").assertDoesNotExist()   // already committed: nothing to include
        onNodeWithText("2 of 3 in the next commit").assertExists()
        onNodeWithTag("diff_include_b.txt").performClick()
        waitForIdle()
        onNodeWithTag("diff_include_a.txt").performClick()
        waitForIdle()
        assertEquals(listOf(Triple("", listOf("b.txt"), true), Triple("", listOf("a.txt"), false)), calls)
    }

    @Test
    fun the_header_box_puts_every_uncommitted_file_in_per_repo() = runComposeUiTest {
        val calls = mutableListOf<Triple<String, List<String>, Boolean>>()
        setContent(host(stagedDiff(), stage = { repo, paths, on -> calls += Triple(repo, paths, on); true }))
        waitForIdle()
        onNodeWithTag("diff_include_all").performClick()
        waitForIdle()
        assertEquals(setOf(Triple("", listOf("a.txt", "b.txt"), true), Triple("lib", listOf("d.txt"), true)), calls.toSet())
    }

    private fun noneTicked() = listOf(
        RepoDiff(repo = "", files = listOf(
            DiffFile(path = "a.txt", status = "modified", staged = "none"),
            DiffFile(path = "b.txt", status = "added", staged = "none"),
        )),
    )

    @Test
    fun with_nothing_ticked_the_button_commits_all_and_says_what_it_did() = runComposeUiTest {
        val calls = mutableListOf<Pair<String, dev.supermux.net.CommitRequest>>()
        setContent(host(noneTicked(), stage = { _, _, _ -> true }, gitWrite = { op, body ->
            calls += op to body
            dev.supermux.net.GitWriteResult(ok = true, subject = "Update a.txt and b.txt")
        }))
        waitForIdle()
        onNodeWithText("Commit all 2").assertExists()
        onNodeWithTag("commit_button").performClick()
        waitForIdle()
        assertEquals(listOf("commit" to dev.supermux.net.CommitRequest("", null, all = true)), calls)
        onNodeWithText("Committed: Update a.txt and b.txt").assertExists()
    }

    @Test
    fun a_ticked_file_makes_it_commit_with_the_message_typed() = runComposeUiTest {
        val calls = mutableListOf<dev.supermux.net.CommitRequest>()
        setContent(host(stagedDiff(), stage = { _, _, _ -> true }, gitWrite = { _, body -> calls += body; dev.supermux.net.GitWriteResult(ok = true, subject = "Fix") }))
        waitForIdle()
        onNodeWithText("Commit").assertExists()
        onNodeWithTag("commit_message").performTextInput("Fix")
        onNodeWithTag("commit_button").performClick()
        waitForIdle()
        assertEquals(listOf(dev.supermux.net.CommitRequest("", "Fix"), dev.supermux.net.CommitRequest("lib", "Fix")), calls)
    }

    @Test
    fun an_undo_the_broker_refuses_says_why() = runComposeUiTest {
        setContent(host(noneTicked(), stage = { _, _, _ -> true }, gitWrite = { _, _ -> dev.supermux.net.GitWriteResult(reason = "pushed") }))
        waitForIdle()
        onNodeWithTag("commit_more").performClick()
        waitForIdle()
        onNodeWithTag("commit_undo").performClick()
        waitForIdle()
        onNodeWithText("That commit is already pushed, so it isn't undone here").assertExists()
    }

    private fun midMerge(clash: Boolean) = listOf(
        RepoDiff(repo = "", merging = true, files = listOf(
            DiffFile(path = "src/App.jsx", status = "modified", staged = if (clash) "conflict" else "all"),
            DiffFile(path = "b.txt", status = "modified", staged = "all"),
        )),
    )

    @Test
    fun a_base_that_moved_on_is_named_and_get_latest_says_what_it_got() = runComposeUiTest {
        var asked = 0
        setContent(host(noneTicked(), stage = { _, _, _ -> true }, gitWrite = { _, _ -> null }, baseName = "release/2.4", baseBehind = 5,
            gitLatest = { asked++; dev.supermux.net.LatestResult(status = "clean", from = "origin/release/2.4", commits = 5) }))
        waitForIdle()
        onNodeWithText("release/2.4 has 5 new commits since this chat started.").assertExists()
        onNodeWithTag("git_get_latest_link").performClick()
        waitForIdle()
        assertEquals(1, asked)
        onNodeWithText("Got 5 commits from release/2.4").assertExists()
    }

    @Test
    fun a_clash_names_the_base_offers_each_side_and_blocks_continue_until_settled() = runComposeUiTest {
        val calls = mutableListOf<Pair<String, dev.supermux.net.CommitRequest>>()
        val asked = mutableListOf<String>()
        setContent(host(midMerge(clash = true), stage = { _, _, _ -> true }, baseName = "dev",
            gitWrite = { op, body -> calls += op to body; dev.supermux.net.GitWriteResult(ok = true) }, askAgent = { asked += it }))
        waitForIdle()
        onNodeWithText("Getting the latest from dev stopped: 1 file was changed on both sides.").assertExists()
        onNodeWithText("Continue merge").assertExists()
        onNodeWithTag("commit_button").performClick()   // still clashing: nothing happens
        waitForIdle()
        assertEquals(emptyList(), calls)
        onNodeWithTag("git_use_base:src/App.jsx").performClick()
        waitForIdle()
        assertEquals(listOf("resolve" to dev.supermux.net.CommitRequest("", path = "src/App.jsx", side = "theirs")), calls)
        onNodeWithTag("git_ask_agent:src/App.jsx").performClick()
        waitForIdle()
        assertTrue(asked.single().contains("dev") && asked.single().contains("src/App.jsx"))
        onNodeWithTag("git_cancel_merge").performClick()
        waitForIdle()
        assertEquals("abort", calls.last().first)
    }

    @Test
    fun once_nothing_clashes_the_button_continues_the_merge() = runComposeUiTest {
        val calls = mutableListOf<Pair<String, dev.supermux.net.CommitRequest>>()
        setContent(host(midMerge(clash = false), stage = { _, _, _ -> true }, baseName = "dev",
            gitWrite = { op, body -> calls += op to body; dev.supermux.net.GitWriteResult(ok = true, subject = "Merge branch 'dev'") }))
        waitForIdle()
        onNodeWithText("Every clash is settled. Continue the merge to finish it.").assertExists()
        onNodeWithTag("commit_button").performClick()
        waitForIdle()
        assertEquals(listOf("commit" to dev.supermux.net.CommitRequest("", null)), calls)
    }

    private fun committedOnly() = listOf(RepoDiff(repo = "", files = listOf(DiffFile(path = "src/App.jsx", status = "modified"))))

    @Test
    fun merge_into_the_base_names_it_and_merges_on_confirm() = runComposeUiTest {
        var merged = 0
        setContent(host(committedOnly(), stage = { _, _, _ -> true }, gitWrite = { _, _ -> null }, baseName = "dev", aheadOfBase = 2,
            mergeReadiness = { dev.supermux.net.FinishReadiness(base = "dev", ahead = 2) }, mergeIntoBase = { merged++ }))
        waitForIdle()
        onNodeWithTag("commit_more").performClick()
        waitForIdle()
        onNodeWithText("Merge into dev…").performClick()
        waitForIdle()
        onNodeWithText("Merge 2 commits into dev").assertExists()
        onNodeWithTag("merge_run_checks").assertDoesNotExist()   // the checks gate is gone (Ahmet)
        onNodeWithTag("merge_confirm").performClick()
        waitForIdle()
        assertEquals(1, merged)
    }

    @Test
    fun a_chat_with_uncommitted_changes_is_asked_to_commit_before_merging() = runComposeUiTest {
        var merged = 0
        setContent(host(committedOnly(), stage = { _, _, _ -> true }, gitWrite = { _, _ -> null }, baseName = "dev", aheadOfBase = 1,
            mergeReadiness = { dev.supermux.net.FinishReadiness(base = "dev", ahead = 1, dirtyFiles = listOf("x.txt")) }, mergeIntoBase = { merged++ }))
        waitForIdle()
        onNodeWithTag("commit_more").performClick()
        waitForIdle()
        onNodeWithTag("commit_merge").performClick()
        waitForIdle()
        onNodeWithText("This chat has changes that aren't committed. Commit or undo them first.").assertExists()
        onNodeWithTag("merge_confirm").performClick()
        waitForIdle()
        assertEquals(0, merged)
    }

    @Test
    fun nothing_ahead_of_the_base_offers_no_merge() = runComposeUiTest {
        setContent(host(committedOnly(), stage = { _, _, _ -> true }, gitWrite = { _, _ -> null }, baseName = "dev", aheadOfBase = 0, mergeIntoBase = {}))
        waitForIdle()
        onNodeWithTag("commit_more").performClick()
        waitForIdle()
        onNodeWithTag("commit_merge").assertDoesNotExist()
    }

    private val sampleHistory = dev.supermux.net.GitHistory(
        branch = "mux/search", base = "dev",
        chat = listOf(dev.supermux.net.LogCommit("c41e9d2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Add search box", "claude", 0, pushed = true)),
        baseCommits = listOf(dev.supermux.net.LogCommit("9f3c2a1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Bump SDK", "deniz", 0)),
    )

    @Test
    fun the_history_tab_lists_the_chats_commits_then_the_bases_and_reverts_one() = runComposeUiTest {
        val calls = mutableListOf<Pair<String, dev.supermux.net.CommitRequest>>()
        setContent(host(committedOnly(), gitWrite = { op, b -> calls += op to b; dev.supermux.net.GitWriteResult(ok = true, sha = "abc") }, history = { sampleHistory }))
        waitForIdle()
        onNodeWithTag("diff_tab_history").performClick()
        waitForIdle()
        onNodeWithText("Add search box").assertExists()
        onNodeWithText("pushed").assertExists()
        onNodeWithText("Bump SDK").assertExists()
        onNodeWithTag("git_commit_c41e9d2").performClick()
        waitForIdle()
        onNodeWithTag("git_revert").performClick()
        waitForIdle()
        assertEquals(listOf("revert" to dev.supermux.net.CommitRequest("", sha = "c41e9d2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")), calls)
        onNodeWithText("Reverted: Add search box").assertExists()
    }

    @Test
    fun a_base_commit_can_be_named_but_not_reverted_here() = runComposeUiTest {
        val calls = mutableListOf<Pair<String, dev.supermux.net.CommitRequest>>()
        setContent(host(committedOnly(), gitWrite = { op, b -> calls += op to b; dev.supermux.net.GitWriteResult(ok = true) }, history = { sampleHistory }))
        waitForIdle()
        onNodeWithTag("diff_tab_history").performClick()
        waitForIdle()
        onNodeWithTag("git_commit_9f3c2a1").performClick()
        waitForIdle()
        onNodeWithTag("git_revert").assertDoesNotExist()
        onNodeWithTag("git_branch_here").performClick()
        waitForIdle()
        onNodeWithTag("git_name_field").performTextInput("try/sdk")
        onNodeWithTag("git_name_create").performClick()
        waitForIdle()
        assertEquals(listOf("branch" to dev.supermux.net.CommitRequest("", sha = "9f3c2a1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", name = "try/sdk")), calls)
        onNodeWithText("Made try/sdk").assertExists()
    }

    @Test
    fun without_a_stage_call_there_are_no_boxes() = runComposeUiTest {
        setContent(host(stagedDiff()))
        waitForIdle()
        onNodeWithTag("diff_include_all").assertDoesNotExist()
        onNodeWithTag("diff_include_a.txt").assertDoesNotExist()
    }

    // ── M5: each file on the native diff plugin ──────────────────────────────────────────────

    @Test
    fun a_file_opens_on_the_diff_plugin_when_its_working_copy_can_be_read() = runComposeUiTest {
        setContent(host(oneRepoDiff(), autoExpandAll = true, readFile = { _, _ -> Result.success("new\ncontext\n") }))
        waitForIdle()
        onNodeWithTag("diff_native_0").assertExists()
        onNodeWithTag("diff_side_by_side_toggle").assertExists()
    }

    @Test
    fun a_file_that_cannot_be_read_keeps_the_patch_rows() = runComposeUiTest {
        setContent(host(oneRepoDiff(), autoExpandAll = true, readFile = { _, _ -> Result.failure(IllegalStateException("gone")) }))
        waitForIdle()
        onNodeWithTag("diff_native_0").assertDoesNotExist()
        onNodeWithText("old").assertExists()
    }

    @Test
    fun without_a_reader_the_pane_is_the_patch_rows_and_offers_no_side_by_side() = runComposeUiTest {
        setContent(host(oneRepoDiff(), autoExpandAll = true))
        waitForIdle()
        onNodeWithTag("diff_native_0").assertDoesNotExist()
        onNodeWithTag("diff_side_by_side_toggle").assertDoesNotExist()
    }

    @Test
    fun the_side_by_side_toggle_is_persisted_and_redraws_the_file_as_a_pair() = runComposeUiTest {
        val prefs = UiPrefs(InMemorySettingsStore())
        setContent(host(oneRepoDiff(), autoExpandAll = true, readFile = { _, _ -> Result.success("new\ncontext\n") }, prefs = prefs))
        waitForIdle()
        onNodeWithTag("diff_side_by_side_toggle").performClick()
        waitForIdle()
        kotlinx.coroutines.runBlocking { kotlin.test.assertTrue(prefs.editorDiffSideBySide.first()) }
        onNodeWithTag("diff_native_0").assertExists()
    }

    // ── Diff-base selector (DropdownMenu on a pointer window, bottom sheet under Compact) ──

    private fun refsWith() = listOf(
        RepoRefs(
            repo = "",
            branches = listOf("main", "feature/x"),
            commits = listOf(
                RefCommit(sha = "abc1234def", subject = "first commit"),
                RefCommit(sha = "9998887ccc", subject = "second commit"),
            ),
        ),
    )

    @Test
    fun the_base_chip_shows_the_current_base_label() = runComposeUiTest {
        setContent(host(oneRepoDiff(), base = "session-start"))

        onNodeWithTag("diff_base_chip").assertIsDisplayed()
        onNodeWithText("Session start").assertIsDisplayed()
    }

    @Test
    fun the_base_chip_shows_a_short_sha_for_a_commit_base() = runComposeUiTest {
        setContent(host(oneRepoDiff(), base = "commit:abc1234def567"))

        onNodeWithText("abc1234").assertIsDisplayed() // baseLabel take(7)
    }

    @Test
    fun opening_the_menu_lists_the_four_base_kinds_from_the_refs() = runComposeUiTest {
        setContent(host(oneRepoDiff(), refs = refsWith()))

        onNodeWithTag("diff_base_chip").performClick()

        onNodeWithTag("diff_base_menu").assertIsDisplayed()
        onNodeWithTag("diff_base_option_session-start").assertIsDisplayed()
        onNodeWithTag("diff_base_option_head").assertIsDisplayed()
        onNodeWithTag("diff_base_option_commit:abc1234def").assertIsDisplayed()
        onNodeWithTag("diff_base_option_branch:feature/x").assertIsDisplayed()
    }

    @Test
    fun picking_uncommitted_fires_on_set_base_with_head() = runComposeUiTest {
        var picked: String? = null
        setContent(host(oneRepoDiff(), refs = refsWith(), onSetBase = { picked = it }))

        onNodeWithTag("diff_base_chip").performClick()
        onNodeWithTag("diff_base_option_head").performClick()

        assertEquals("head", picked)
    }

    @Test
    fun picking_a_commit_fires_on_set_base_with_the_commit_spec() = runComposeUiTest {
        var picked: String? = null
        setContent(host(oneRepoDiff(), refs = refsWith(), onSetBase = { picked = it }))

        onNodeWithTag("diff_base_chip").performClick()
        onNodeWithTag("diff_base_option_commit:9998887ccc").performClick()

        assertEquals("commit:9998887ccc", picked)
    }

    @Test
    fun picking_a_branch_fires_on_set_base_with_the_branch_spec() = runComposeUiTest {
        var picked: String? = null
        setContent(host(oneRepoDiff(), refs = refsWith(), onSetBase = { picked = it }))

        onNodeWithTag("diff_base_chip").performClick()
        onNodeWithTag("diff_base_option_branch:main").performClick()

        assertEquals("branch:main", picked)
    }

    @Test
    fun picking_session_start_fires_on_set_base_with_session_start() = runComposeUiTest {
        var picked: String? = null
        setContent(host(oneRepoDiff(), base = "head", refs = refsWith(), onSetBase = { picked = it }))

        onNodeWithTag("diff_base_chip").performClick()
        onNodeWithTag("diff_base_option_session-start").performClick()

        assertEquals("session-start", picked)
    }

    // ── Repo grouping ────────────────────────────────────────────────────────────────

    @Test
    fun single_repo_hides_the_repo_header_and_shows_the_file_directly() = runComposeUiTest {
        setContent(host(oneRepoDiff()))

        onNodeWithTag("diff_view").assertIsDisplayed()
        onNodeWithText("a.txt").assertIsDisplayed()
        onNodeWithText("workdir").assertDoesNotExist() // no repo header when repos.size == 1
    }

    @Test
    fun multiple_repos_show_a_repo_header_per_repo() = runComposeUiTest {
        setContent(host(twoRepoDiff()))

        onNodeWithText("workdir").assertIsDisplayed() // repo == "" label
        onNodeWithText("lib").assertIsDisplayed()
        onNodeWithText("a.txt").assertIsDisplayed()
        onNodeWithText("b.txt").assertIsDisplayed()
    }

    // ── File expand → parsed diff lines ─────────────────────────────────────────────

    @Test
    fun expanding_a_file_renders_its_parsed_diff_lines() = runComposeUiTest {
        setContent(host(oneRepoDiff()))

        onNodeWithTag("diff_file_0").performClick() // the file header row toggles expansion
        onNodeWithText("old").assertIsDisplayed()
        onNodeWithText("new").assertIsDisplayed()
        onNodeWithText("context").assertIsDisplayed()
    }

    @Test
    fun collapsed_file_does_not_render_its_diff_lines() = runComposeUiTest {
        setContent(host(oneRepoDiff()))

        onNodeWithText("old").assertDoesNotExist()
        onNodeWithText("new").assertDoesNotExist()
    }

    // ── Add-comment composer ────────────────────────────────────────────────────────

    @Test
    fun the_plus_gutter_opens_a_composer_and_add_fires_the_right_args() = runComposeUiTest {
        var captured: List<Any?>? = null
        setContent(
            host(
                oneRepoDiff(),
                onAddComment = { repo, path, anchorLine, anchorContext, hunkHeader, body ->
                    captured = listOf(repo, path, anchorLine, anchorContext, hunkHeader, body)
                },
            ),
        )
        onNodeWithTag("diff_file_0").performClick() // expand

        onAllNodesWithTag("diff_add_comment")[0].performClick() // the first +-gutter (the "new" add row)
        onNodeWithTag("diff_comment_draft").performTextInput("looks good")
        onNodeWithTag("diff_comment_add").performClick()

        waitForIdle()
        assertEquals(listOf("", "a.txt", 1, "new", "@@ -1,2 +1,2 @@", "looks good"), captured)
    }

    @Test
    fun cancel_closes_the_composer_without_firing_add() = runComposeUiTest {
        var fired = false
        setContent(host(oneRepoDiff(), onAddComment = { _, _, _, _, _, _ -> fired = true }))
        onNodeWithTag("diff_file_0").performClick()

        onAllNodesWithTag("diff_add_comment")[0].performClick()
        onNodeWithTag("diff_comment_draft").performTextInput("nope")
        onNodeWithTag("diff_comment_cancel").performClick()

        onNodeWithTag("diff_comment_draft").assertDoesNotExist()
        assertEquals(false, fired)
    }

    // ── Existing comment thread + resolve ───────────────────────────────────────────

    @Test
    fun an_existing_open_comment_renders_as_a_thread_and_resolve_fires_its_id() = runComposeUiTest {
        var resolvedId: String? = null
        val comment = ReviewComment(
            id = "c1", repo = "", path = "a.txt", side = "RIGHT", anchorLine = 1, body = "please fix", status = "open",
        )
        setContent(host(oneRepoDiff(), comments = listOf(comment), onResolve = { resolvedId = it }))
        onNodeWithTag("diff_file_0").performClick()

        onNodeWithTag("diff_comment_thread").assertIsDisplayed()
        onNodeWithText("please fix").assertIsDisplayed()

        onNodeWithTag("diff_resolve").performClick()
        waitForIdle()
        assertEquals("c1", resolvedId)
    }

    @Test
    fun a_resolved_comment_has_no_resolve_button() = runComposeUiTest {
        val comment = ReviewComment(
            id = "c1", repo = "", path = "a.txt", side = "RIGHT", anchorLine = 1, body = "done", status = "resolved",
        )
        setContent(host(oneRepoDiff(), comments = listOf(comment)))
        onNodeWithTag("diff_file_0").performClick()

        onNodeWithTag("diff_comment_thread").assertIsDisplayed()
        onNodeWithTag("diff_resolve").assertDoesNotExist()
    }

    // ── Submit bar ───────────────────────────────────────────────────────────────────

    @Test
    fun the_submit_bar_shows_the_open_comment_count_and_submit_fires() = runComposeUiTest {
        var submitted = false
        val comments = listOf(
            ReviewComment(id = "c1", repo = "", path = "a.txt", side = "RIGHT", anchorLine = 1, body = "x", status = "open"),
            ReviewComment(id = "c2", repo = "", path = "a.txt", side = "RIGHT", anchorLine = 1, body = "y", status = "resolved"),
        )
        setContent(host(oneRepoDiff(), comments = comments, onSubmit = { submitted = true }))

        onNodeWithText("1 open comment").assertIsDisplayed()
        onNodeWithTag("diff_submit").performClick()
        waitForIdle()
        assertTrue(submitted)
    }

    @Test
    fun the_submit_bar_is_absent_when_there_are_no_comments() = runComposeUiTest {
        setContent(host(oneRepoDiff(), comments = emptyList()))

        onNodeWithTag("diff_submit").assertDoesNotExist()
    }

    // ── Wrap toggle + close ──────────────────────────────────────────────────────────

    @Test
    fun the_wrap_toggle_flips_state_without_crashing() = runComposeUiTest {
        setContent(host(oneRepoDiff()))
        onNodeWithTag("diff_file_0").performClick()

        onNodeWithTag("diff_wrap_toggle").performClick() // wrap off
        onNodeWithTag("diff_wrap_toggle").performClick() // wrap back on
        onNodeWithTag("diff_view").assertIsDisplayed()
    }

    @Test
    fun the_close_button_fires_on_close() = runComposeUiTest {
        var closed = false
        setContent(host(oneRepoDiff(), onClose = { closed = true }))

        onNodeWithTag("diff_back").performClick()
        assertTrue(closed)
    }

    // ── Empty diff ───────────────────────────────────────────────────────────────────

    @Test
    fun an_empty_repo_list_shows_the_no_changes_message() = runComposeUiTest {
        setContent(host(emptyList()))

        onNodeWithText("No changes found").assertIsDisplayed()
    }

    // ── autoExpandAll (headless live-verify convenience — no click/xdotool available) ────

    @Test
    fun auto_expand_all_shows_every_files_diff_lines_with_no_click() = runComposeUiTest {
        setContent(host(oneRepoDiff(), autoExpandAll = true))

        // oneRepoDiff's single file's diff has these lines — visible with zero interaction.
        onNodeWithText("old").assertIsDisplayed()
        onNodeWithText("new").assertIsDisplayed()
    }

    @Test
    fun without_auto_expand_all_files_start_collapsed() = runComposeUiTest {
        setContent(host(oneRepoDiff(), autoExpandAll = false))

        onNodeWithText("old").assertDoesNotExist()
    }

    // ── Compact: the same chip, the same options, a bottom sheet instead of a menu ──────

    @Test
    fun under_compact_the_base_chip_opens_a_bottom_sheet_with_the_same_options() = runComposeUiTest {
        var picked: String? = null
        setContent(
            host(
                oneRepoDiff(),
                refs = refsWith(),
                onSetBase = { picked = it },
                widthClass = WindowWidthClass.Compact,
            ),
        )

        onNodeWithTag("diff_base_chip").performClick()
        waitForIdle()

        // The sheet, not a dropdown — but carrying the same `diff_base_menu` + option tags.
        onNodeWithTag("diff_base_sheet").assertExists()
        onNodeWithTag("diff_base_menu").assertExists()
        onNodeWithTag("diff_base_option_head").assertExists()
        onNodeWithTag("diff_base_option_branch:main").performClick()
        waitForIdle()
        assertEquals("branch:main", picked)
    }

    // ── Compact geometry: the phone header fits and the tree indent is bounded ─────────

    /** The diff at a real phone width, so "does it fit" is an assertion and not a guess. */
    private fun phone(
        repos: List<RepoDiff>,
        autoExpandAll: Boolean = false,
    ): @androidx.compose.runtime.Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalWindowWidthClass provides WindowWidthClass.Compact,
            LocalInputMode provides InputMode.Touch,
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box(Modifier.width(360.dp)) {
                    DiffView(
                        repos = repos,
                        comments = emptyList(),
                        onAddComment = { _, _, _, _, _, _ -> },
                        onResolve = {},
                        onSubmit = {},
                        onReload = {},
                        onClose = {},
                        autoExpandAll = autoExpandAll,
                    )
                }
            }
        }
    }

    @Test fun the_compact_tree_indent_is_capped_at_four_levels() {
        // Desktop keeps a full Space.lg per level…
        assertEquals(32.dp, diffTreeIndent(depth = 2, compact = false, multiRepo = false))
        assertEquals(144.dp, diffTreeIndent(depth = 9, compact = false, multiRepo = false))
        // …the phone steps by Space.sm and stops after four, so a depth-9 path costs 32dp, not 144.
        assertEquals(16.dp, diffTreeIndent(depth = 2, compact = true, multiRepo = false))
        assertEquals(32.dp, diffTreeIndent(depth = 9, compact = true, multiRepo = false))
        assertEquals(32.dp, diffTreeIndent(depth = 4, compact = true, multiRepo = false))
    }

    @Test
    fun under_compact_the_header_controls_all_fit_without_overlapping() = runComposeUiTest {
        setContent(phone(oneRepoDiff()))

        onNodeWithTag("diff_base_chip").assertIsDisplayed()
        onNodeWithTag("diff_tree_toggle").assertIsDisplayed()
        onNodeWithTag("diff_wrap_toggle").assertIsDisplayed() // icon-only under Compact, same tag
        onNodeWithTag("diff_back").assertIsDisplayed()

        val chip = onNodeWithTag("diff_base_chip").getBoundsInRoot()
        val wrap = onNodeWithTag("diff_wrap_toggle").getBoundsInRoot()
        val close = onNodeWithTag("diff_back").getBoundsInRoot()
        assertTrue(wrap.left >= chip.right, "wrap toggle overlaps the base chip: $wrap vs $chip")
        assertTrue(close.left >= wrap.right, "close overlaps the wrap toggle: $close vs $wrap")
        assertTrue(close.right.value <= 360f, "the close button is pushed off a 360dp screen: $close")
    }

    @Test
    fun under_compact_the_diff_body_does_not_inherit_the_tree_indent() = runComposeUiTest {
        // `a/` branches, so `a/b/x.txt` really sits one folder deep in the rendered tree.
        val repos = listOf(
            RepoDiff(
                repo = "",
                files = listOf(
                    DiffFile(path = "a/b/x.txt", status = "modified", diff = "@@ -1 +1 @@\n+indented?\n"),
                    DiffFile(path = "a/y.txt", status = "modified", diff = "@@ -1 +1 @@\n+other\n"),
                ),
            ),
        )
        setContent(phone(repos, autoExpandAll = true))

        // Only the 28dp +-gutter is to the left of a diff line, whatever depth its file sits at.
        val line = onNodeWithText("indented?").getBoundsInRoot()
        assertTrue(line.left.value <= 32f, "the diff body inherited the tree indent: $line")
    }
}
