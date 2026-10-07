package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BlobText
import dev.supermux.net.DiffFile
import dev.supermux.net.FsException
import dev.supermux.net.RepoDiff
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** A lazy file (the `/changes` list: counts and a base blob, no patch) in the Changes pane. */
@OptIn(ExperimentalTestApi::class)
class LazyDiffFileTest {

    private val sha = "a".repeat(40)

    private fun lazyFile(path: String = "a.txt", status: String = "modified", baseBlob: String? = sha) =
        DiffFile(path = path, status = status, added = 1, removed = 1, baseBlob = baseBlob, lazy = true)

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            dev.supermux.ui.platform.LocalPlatform provides dev.supermux.ui.platform.FakePlatform(),
            LocalWindowWidthClass provides WindowWidthClass.Expanded,
            LocalInputMode provides InputMode.Pointer,
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
        }
    }

    @Composable
    private fun Pane(
        repos: List<RepoDiff>,
        readFile: (suspend (String, String) -> Result<String>)?,
        baseText: (suspend (String, String, Boolean) -> BlobText)?,
    ) {
        Host {
            DiffView(
                repos = repos,
                comments = emptyList(),
                onAddComment = { _, _, _, _, _, _ -> }, onResolve = {}, onSubmit = {}, onReload = {}, onClose = {},
                autoExpandAll = true,
                readFile = readFile,
                baseText = baseText,
            )
        }
    }

    /** An editor (its accessibility text: the visible lines) showing [s]. */
    private fun editorShowing(s: String) = SemanticsMatcher("an editor showing \"$s\"") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(s) == true
    }

    private fun ComposeUiTest.waitForTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.waitForEditor(s: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodes(editorShowing(s), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun a_lazy_file_renders_from_the_blob_and_the_working_copy() = runComposeUiTest {
        var blobCalls = 0
        setContent {
            Pane(listOf(RepoDiff(repo = "", files = listOf(lazyFile()))), { _, _ -> Result.success("two\n") }, { _, _, _ -> blobCalls++; BlobText.Text("one\n") })
        }
        waitForTag("diff_native_0")
        onNodeWithText("+1").assertExists()
        onNodeWithText("-1").assertExists()
        assertEquals(1, blobCalls)
    }

    @Test fun the_base_side_is_the_blob_text() = runComposeUiTest {
        setContent {
            Pane(listOf(RepoDiff(repo = "", files = listOf(lazyFile()))), { _, _ -> Result.success("two\n") }, { _, _, _ -> BlobText.Text("one\n") })
        }
        waitForTag("diff_native_0")
        onNodeWithTag("diff_side_by_side_toggle").performClick()
        // Side by side: the base editor holds the blob, the working one the file.
        waitForEditor("one")
        waitForEditor("two")
        // What a comment on line 1 carries as its hunk header (no patch: from these two texts).
        assertEquals("@@ -1,1 +1,1 @@", hunkHeaderAt("one\n", "two\n", 1))
    }

    @Test fun a_too_large_base_offers_load_anyway() = runComposeUiTest {
        val forced = mutableListOf<Boolean>()
        setContent {
            Pane(
                listOf(RepoDiff(repo = "", files = listOf(lazyFile(path = "big.txt")))),
                { _, _ -> Result.success("x\n") },
                { _, _, force -> forced += force; if (force) BlobText.Text("y\n") else BlobText.TooLarge(2_000_000) },
            )
        }
        waitForTag("diff_lazy_card_0")
        onNodeWithText("Load anyway").performClick()
        waitForTag("diff_native_0")
        assertEquals(listOf(false, true), forced)
    }

    @Test fun a_binary_base_shows_the_binary_card() = runComposeUiTest {
        setContent {
            Pane(listOf(RepoDiff(repo = "", files = listOf(lazyFile()))), { _, _ -> Result.success("x\n") }, { _, _, _ -> BlobText.Binary })
        }
        waitForTag("diff_lazy_card_0")
        onNodeWithText("Binary file changed").assertExists()
        onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun a_failed_base_retries() = runComposeUiTest {
        var calls = 0
        setContent {
            Pane(
                listOf(RepoDiff(repo = "", files = listOf(lazyFile()))),
                { _, _ -> Result.success("two\n") },
                { _, _, _ -> calls++; if (calls == 1) BlobText.Failed("boom") else BlobText.Text("one\n") },
            )
        }
        waitForTag("diff_lazy_card_0")
        onNodeWithText("Couldn't load the base: boom").assertExists()
        onNodeWithText("Retry").performClick()
        waitForTag("diff_native_0")
        assertEquals(2, calls)
    }

    @Test fun without_a_blob_fetcher_the_failed_card_offers_no_retry() = runComposeUiTest {
        setContent { Pane(listOf(RepoDiff(repo = "", files = listOf(lazyFile()))), { _, _ -> Result.success("two\n") }, null) }
        waitForTag("diff_lazy_card_0")
        onNodeWithText("Couldn't load the base: Base text unavailable").assertExists()
        onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun a_new_file_fetches_no_blob() = runComposeUiTest {
        var blobCalls = 0
        setContent {
            Pane(listOf(RepoDiff(repo = "", files = listOf(lazyFile(status = "added", baseBlob = null)))), { _, _ -> Result.success("new\n") }, { _, _, _ -> blobCalls++; BlobText.Text("") })
        }
        waitForTag("diff_native_0")
        assertEquals(0, blobCalls)
    }

    @Test fun a_deleted_file_renders_without_reading_the_working_copy() = runComposeUiTest {
        var reads = 0
        setContent {
            Pane(listOf(RepoDiff(repo = "", files = listOf(lazyFile(status = "deleted")))), { _, _ -> reads++; Result.failure(IllegalStateException("gone")) }, { _, _, _ -> BlobText.Text("one\n") })
        }
        waitForTag("diff_native_0")
        assertEquals(0, reads)
    }

    @Test fun a_failed_read_retries() = runComposeUiTest {
        var reads = 0
        setContent {
            Pane(
                listOf(RepoDiff(repo = "", files = listOf(lazyFile()))),
                { _, _ -> reads++; if (reads == 1) Result.failure(IllegalStateException("offline")) else Result.success("two\n") },
                { _, _, _ -> BlobText.Text("one\n") },
            )
        }
        waitForTag("diff_lazy_card_0")
        onNodeWithText("offline").assertExists()
        onNodeWithText("Retry").performClick()
        waitForTag("diff_native_0")
        assertEquals(2, reads)
    }

    @Test fun a_working_copy_too_large_to_read_says_large_file() = runComposeUiTest {
        setContent {
            Pane(listOf(RepoDiff(repo = "", files = listOf(lazyFile()))), { _, _ -> Result.failure(FsException(413, "file too large")) }, { _, _, _ -> BlobText.Text("one\n") })
        }
        waitForTag("diff_lazy_card_0")
        onNodeWithText("Large file").assertExists()
        onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun a_reload_of_an_equal_list_re_reads_the_working_copy() = runComposeUiTest {
        val state = DiffState()
        state.diffRepos = listOf(RepoDiff(repo = "", files = listOf(lazyFile())))
        var disk = "two\n"
        setContent { Pane(state.diffRepos, { _, _ -> Result.success(disk) }, { _, _, _ -> BlobText.Text("one\n") }) }
        waitForEditor("two")
        // A same-size edit: the list the reload brings is equal (same counts, status, blob).
        disk = "tw0\n"
        runOnIdle { state.diffRepos = listOf(RepoDiff(repo = "", files = listOf(lazyFile()))) }
        waitForEditor("tw0")
    }

    @Test fun a_truncated_list_says_how_many_files_it_shows() = runComposeUiTest {
        val files = (0 until 3000).map { lazyFile(path = "f$it.txt") }
        setContent {
            Host {
                DiffView(
                    repos = listOf(RepoDiff(repo = "", files = files, truncated = true, total = 5000)),
                    comments = emptyList(),
                    onAddComment = { _, _, _, _, _, _ -> }, onResolve = {}, onSubmit = {}, onReload = {}, onClose = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Showing 3000 of 5000 files").assertExists()
    }

    @Test fun a_failed_listing_shows_its_error_and_not_no_changes() = runComposeUiTest {
        setContent { Pane(listOf(RepoDiff(repo = "", error = "not a git repository")), null, null) }
        waitForIdle()
        onNodeWithText("Couldn't list changes: not a git repository").assertExists()
        onNodeWithText("No changes found").assertDoesNotExist()
    }
}
