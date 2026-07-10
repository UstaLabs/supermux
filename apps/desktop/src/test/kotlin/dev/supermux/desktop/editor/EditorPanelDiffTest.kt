package dev.supermux.desktop.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.DiffFile
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import dev.supermux.net.RepoDiff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EditorPanel-level tests for the M4g-2 diff-mode SWAP gate + "View changes" button (plan Task 3/4):
 * clicking the toolbar button fires [EditorState.loadDiff], and while [EditorState.showDiff] is true
 * the DiffView fully REPLACES the tabs/tree/editor column (never composed alongside it) — the same
 * swap discipline the M4g-1 markdown-preview fix uses, extended to the whole panel. Uses the same
 * KCEF-free seam as [EditorPanelTest] (`kcefStateFlow` never Ready, `onEnsureInit = {}`).
 */
@OptIn(ExperimentalTestApi::class)
class EditorPanelDiffTest {

    private val tree = listOf(FsEntry(name = "notes.txt", type = "file"))

    private val fakeDiff = FsDiffResult(
        repos = listOf(
            RepoDiff(repo = "", files = listOf(DiffFile(path = "a.txt", status = "modified", diff = "@@ -1 +1 @@\n-old\n+new\n"))),
        ),
        comments = emptyList(),
    )

    private fun ComposeContent(
        fsDiff: suspend () -> FsDiffResult? = { fakeDiff },
    ): @androidx.compose.runtime.Composable () -> Unit = {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            EditorPanel(
                sessionId = "s1",
                workdir = "/w/s1",
                fsList = { tree },
                fsRead = { path -> Result.success("hello // $path") },
                fsWrite = { _, _ -> true },
                fsSearch = { emptyList<FsSearchResult>() },
                fsDiff = fsDiff,
                kcefStateFlow = MutableStateFlow(KcefState.Idle),
                onEnsureInit = {},
            )
        }
    }

    @Test
    fun clicking_view_changes_opens_the_diff_view_and_hides_the_tree() = runComposeUiTest {
        setContent(ComposeContent())

        onNodeWithTag("editor_tree").assertIsDisplayed() // normal editor column, pre-click
        onNodeWithTag("diff_view").assertDoesNotExist()

        onNodeWithTag("editor_view_changes").performClick()
        waitForIdle()

        onNodeWithTag("diff_view").assertIsDisplayed()
        onNodeWithText("a.txt").assertIsDisplayed()
        // The swap is total: tree/tabs are gone, not just covered.
        onNodeWithTag("editor_tree").assertDoesNotExist()
        onNodeWithTag("editor_save").assertDoesNotExist()
    }

    @Test
    fun a_null_fs_diff_result_never_opens_the_diff_view() = runComposeUiTest {
        setContent(ComposeContent(fsDiff = { null }))

        onNodeWithTag("editor_view_changes").performClick()
        waitForIdle()

        onNodeWithTag("diff_view").assertDoesNotExist()
        onNodeWithTag("editor_tree").assertIsDisplayed() // still the normal column
    }

    @Test
    fun the_diff_view_close_button_swaps_back_to_the_editor_column() = runComposeUiTest {
        setContent(ComposeContent())

        onNodeWithTag("editor_view_changes").performClick()
        waitForIdle()
        onNodeWithTag("diff_view").assertIsDisplayed()

        onNodeWithTag("diff_back").performClick()
        waitForIdle()

        onNodeWithTag("diff_view").assertDoesNotExist()
        onNodeWithTag("editor_tree").assertIsDisplayed()
    }

    @Test
    fun a_spinner_replaces_the_view_changes_button_while_the_fetch_is_in_flight() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent(ComposeContent(fsDiff = { gate.await(); fakeDiff }))

        onNodeWithTag("editor_view_changes").performClick()
        waitForIdle()

        onNodeWithTag("editor_view_changes").assertDoesNotExist() // replaced by the spinner
        onNodeWithTag("diff_view").assertDoesNotExist() // not yet shown — fetch still in flight

        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("diff_view").assertIsDisplayed()
    }

    // ── Restored `!showDiff` preview-gate clauses (Android EditorScreen.kt:177-178 parity) ──────

    @Test
    fun the_markdown_preview_toggle_is_hidden_while_diff_mode_is_active() = runComposeUiTest {
        var fired = 0
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorPanel(
                    sessionId = "s1",
                    workdir = "/w/s1",
                    fsList = { listOf(FsEntry(name = "notes.md", type = "file")) },
                    fsRead = { path -> Result.success("# hi // $path") },
                    fsWrite = { _, _ -> true },
                    fsSearch = { emptyList<FsSearchResult>() },
                    fsDiff = { fakeDiff },
                    kcefStateFlow = MutableStateFlow(KcefState.Idle),
                    onEnsureInit = {},
                )
            }
        }
        onNodeWithText("notes.md").performClick() // open a markdown tab
        onNodeWithTag("editor_preview_toggle").assertIsDisplayed() // normally visible on .md

        onNodeWithTag("editor_view_changes").performClick()
        waitForIdle()
        onNodeWithTag("editor_preview_toggle").assertDoesNotExist() // gone under diff mode
        fired += 1
        assertEquals(1, fired)
    }
}
