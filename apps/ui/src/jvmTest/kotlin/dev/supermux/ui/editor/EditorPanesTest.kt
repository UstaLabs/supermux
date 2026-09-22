package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.DiffFile
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsEntry
import dev.supermux.net.RepoDiff
import dev.supermux.net.Walkthrough
import dev.supermux.net.WalkthroughStep
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * The three workspace panes — the editor cut into parts a pane group can hold as tabs. Each case
 * asserts the pane's own tag on its own semantics node (the host tags the modifier, and a merged
 * duplicate would hide the pane's tag), plus the two conditional rows a caller can hit: the
 * "no code intelligence" note and the walkthrough toggle.
 */
@OptIn(ExperimentalTestApi::class)
class EditorPanesTest {

    private fun host(
        caps: Caps = NO_CAPS,
        content: @Composable () -> Unit,
    ): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalPlatform provides FakePlatform(caps = caps, editorEngine = FakeEditorEngineFactory()),
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
        }
    }

    @Test
    fun the_explorer_pane_draws_its_tree() = runComposeUiTest {
        setContent(
            host {
                ExplorerPane(
                    fsList = { Result.success(listOf(FsEntry(name = "a.kt", type = "file"))) },
                    explorer = ExplorerState(),
                    workdir = "/w",
                    onOpenFile = {},
                    modifier = Modifier,
                )
            },
        )
        waitForIdle()

        onNodeWithTag("editor_explorer_pane").assertIsDisplayed()
        onNodeWithTag("editor_tree").assertIsDisplayed()
    }

    @Test
    fun a_file_pane_without_a_session_says_code_intelligence_is_off() = runComposeUiTest {
        setContent(
            host {
                FilePane(
                    path = "a.kt",
                    documents = DocumentStore(
                        fsRead = { Result.success("hello") },
                        fsWrite = { _, _ -> true },
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    ),
                    lspSessionId = null,
                    modifier = Modifier,
                )
            },
        )
        waitForIdle()

        onNodeWithTag("editor_file_pane").assertIsDisplayed()
        onNodeWithTag("editor-no-lsp").assertIsDisplayed()
    }

    @Test
    fun a_file_pane_with_a_session_drops_the_no_lsp_note() = runComposeUiTest {
        setContent(
            host {
                FilePane(
                    path = "a.kt",
                    documents = DocumentStore(
                        fsRead = { Result.success("hello") },
                        fsWrite = { _, _ -> true },
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    ),
                    lspSessionId = "s1",
                    modifier = Modifier,
                )
            },
        )
        waitForIdle()

        onNodeWithTag("editor_file_pane").assertIsDisplayed()
        onNodeWithTag("editor-no-lsp").assertDoesNotExist()
    }

    private fun diffResult() = FsDiffResult(
        repos = listOf(
            RepoDiff(repo = "", files = listOf(DiffFile(path = "a.kt", status = "modified", diff = "@@ -1 +1 @@\n+new\n"))),
        ),
    )

    private fun walkthroughState() = WalkthroughState("s1").apply {
        applyWalkthrough(
            Walkthrough(
                id = "w1", sessionId = "s1", title = "Tour", revision = 1,
                steps = listOf(WalkthroughStep(id = "a", ord = 0, title = "One", bodyMd = "A")),
            ),
        )
    }

    @Test
    fun the_diff_pane_draws_and_offers_the_walkthrough_where_the_platform_has_the_seam() = runComposeUiTest {
        setContent(
            host(caps = NO_CAPS.copy(walkthrough = true)) {
                DiffPane(
                    diff = DiffState(),
                    walkthrough = walkthroughState(),
                    fsDiff = { diffResult() },
                    fsRefs = { null },
                    modifier = Modifier,
                )
            },
        )
        waitForIdle()

        onNodeWithTag("editor_diff_pane").assertIsDisplayed()
        onNodeWithTag("walkthrough_toggle").assertIsDisplayed()
    }

    @Test
    fun a_platform_without_the_walkthrough_seam_has_no_toggle() = runComposeUiTest {
        setContent(
            host {
                DiffPane(
                    diff = DiffState(),
                    walkthrough = walkthroughState(),
                    fsDiff = { diffResult() },
                    fsRefs = { null },
                    modifier = Modifier,
                )
            },
        )
        waitForIdle()

        onNodeWithTag("editor_diff_pane").assertIsDisplayed()
        onNodeWithTag("walkthrough_toggle").assertDoesNotExist()
    }
}
