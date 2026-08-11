package dev.supermux.desktop.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.AddCommentBody
import dev.supermux.net.DiffFile
import dev.supermux.net.RefCommit
import dev.supermux.net.RepoDiff
import dev.supermux.net.RepoRefs
import dev.supermux.net.ReviewComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for [DiffView] (M4g-2 Task 4). Unlike the rest of the editor, DiffView is pure
 * Compose (no JCEF) so it hosts cleanly under [runComposeUiTest] with no engine seam needed — every
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
    ): @androidx.compose.runtime.Composable () -> Unit = {
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
            )
        }
    }

    // ── Diff-base selector (DropdownMenu, desktop convention — NOT a bottom sheet) ─────

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
}
