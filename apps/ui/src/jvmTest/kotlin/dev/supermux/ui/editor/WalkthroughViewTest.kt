package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.Walkthrough
import dev.supermux.net.WalkthroughStep
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The walkthrough slideshow, now shared (Android gains it in cluster C4). Steps here carry no
 * `path`, so the slide is pure markdown and no code surface is needed; the code half (the diff
 * plugin's walkthrough region) is covered by `ReviewHostsTest`.
 */
@OptIn(ExperimentalTestApi::class)
class WalkthroughViewTest {

    private fun state() = WalkthroughState("s1").apply {
        applyWalkthrough(
            Walkthrough(
                id = "w1", sessionId = "s1", title = "Tour", revision = 1,
                steps = listOf(
                    WalkthroughStep(id = "a", ord = 0, title = "First step", bodyMd = "A"),
                    WalkthroughStep(id = "b", ord = 1, title = "Second step", bodyMd = "B"),
                ),
            ),
        )
    }

    private fun host(
        state: WalkthroughState,
        repos: List<dev.supermux.net.RepoDiff> = emptyList(),
        read: (String, String) -> Result<String> = { _, _ -> Result.success("") },
        baseText: (suspend (String, String, Boolean) -> dev.supermux.net.BlobText)? = null,
    ): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalPlatform provides FakePlatform(),
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WalkthroughView(
                    state = state,
                    repos = repos,
                    readFile = { repo, path -> read(repo, path) },
                    onAddComment = { null },
                    onResolve = { false },
                    onOpenFile = { _, _, _ -> },
                    onClose = {},
                    modifier = Modifier,
                    baseText = baseText,
                )
            }
        }
    }

    @Test
    fun it_shows_the_current_step() = runComposeUiTest {
        setContent(host(state()))
        waitForIdle()

        onNodeWithTag("walkthrough_view").assertIsDisplayed()
        onNodeWithText("First step").assertIsDisplayed()
        onNodeWithText("1 / 2").assertIsDisplayed()
    }

    @Test
    fun left_and_right_page_the_slideshow() = runComposeUiTest {
        val state = state()
        setContent(host(state))
        waitForIdle()

        onNodeWithTag("walkthrough_view").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(1, state.stepIndex)
        onNodeWithText("Second step").assertIsDisplayed()

        onNodeWithTag("walkthrough_view").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertEquals(0, state.stepIndex)
    }

    @Test
    fun the_drawer_lists_every_step_and_jumps_to_one() = runComposeUiTest {
        val state = state()
        setContent(host(state))
        waitForIdle()

        onNodeWithTag("walkthrough_drawer").performClick()
        waitForIdle()
        onNodeWithText("Walkthrough steps").assertIsDisplayed()

        onNodeWithText("Second step").performClick()
        waitForIdle()
        assertEquals(1, state.stepIndex)
        onNodeWithText("Walkthrough steps").assertDoesNotExist()
    }

    /** M5: a step with code is the diff plugin's slice of the file, with the session's threads in it. */
    @Test
    fun a_code_step_shows_the_file_on_the_diff_plugin() = runComposeUiTest {
        val state = WalkthroughState("s1").apply {
            applyWalkthrough(
                Walkthrough(
                    id = "w1", sessionId = "s1", title = "Tour", revision = 1,
                    steps = listOf(WalkthroughStep(id = "a", ord = 0, title = "The change", bodyMd = "Look", repo = "", path = "f.kt", rangeStart = 2, rangeEnd = 2)),
                ),
            )
            seedComments(listOf(dev.supermux.net.ReviewComment(id = "t1", repo = "", path = "f.kt", side = "RIGHT", anchorLine = 2, body = "Why this name?", author = "user", status = "open")))
        }
        val repos = listOf(dev.supermux.net.RepoDiff(repo = "", files = listOf(dev.supermux.net.DiffFile(path = "f.kt", status = "modified", diff = "@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n"))))
        setContent(host(state, repos = repos, read = { _, _ -> Result.success("a\nB\nc\n") }))
        waitForIdle()
        onNodeWithTag("walkthrough_native_region").assertExists()
        onNodeWithText("Why this name?").assertExists()
    }

    /** A lazy Changes file has no patch: the step's base is its blob, so the step still shows a diff. */
    @Test
    fun a_lazy_file_step_diffs_against_its_blob() = runComposeUiTest {
        val state = WalkthroughState("s1").apply {
            applyWalkthrough(
                Walkthrough(
                    id = "w1", sessionId = "s1", title = "Tour", revision = 1,
                    steps = listOf(WalkthroughStep(id = "a", ord = 0, title = "The change", bodyMd = "Look", repo = "", path = "f.txt", rangeStart = 1, rangeEnd = 1)),
                ),
            )
        }
        val sha = "b".repeat(40)
        val repos = listOf(dev.supermux.net.RepoDiff(repo = "", files = listOf(dev.supermux.net.DiffFile(path = "f.txt", status = "modified", added = 1, removed = 1, baseBlob = sha, lazy = true))))
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        setContent(
            host(state, repos = repos, read = { _, _ -> Result.success("two\n") }, baseText = { repo, s, force ->
                calls += Triple(repo, s, force)
                dev.supermux.net.BlobText.Text("one\n")
            }),
        )
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("walkthrough_native_region").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertEquals(listOf(Triple("", sha, false)), calls)
        // The blob's line is on the base side: the inline diff draws it as a deleted line.
        onNodeWithText("one", useUnmergedTree = true).assertExists()
    }

    /** No fetcher (or a failed one): the step still renders, against the working copy, no crash. */
    @Test
    fun a_lazy_file_step_without_a_fetcher_shows_the_file() = runComposeUiTest {
        val state = WalkthroughState("s1").apply {
            applyWalkthrough(
                Walkthrough(
                    id = "w1", sessionId = "s1", title = "Tour", revision = 1,
                    steps = listOf(WalkthroughStep(id = "a", ord = 0, title = "The change", bodyMd = "Look", repo = "", path = "f.txt", rangeStart = 1, rangeEnd = 1)),
                ),
            )
        }
        val repos = listOf(dev.supermux.net.RepoDiff(repo = "", files = listOf(dev.supermux.net.DiffFile(path = "f.txt", status = "modified", added = 1, removed = 1, baseBlob = "b".repeat(40), lazy = true))))
        setContent(host(state, repos = repos, read = { _, _ -> Result.success("two\n") }, baseText = { _, _, _ -> dev.supermux.net.BlobText.Failed("boom") }))
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("walkthrough_native_region").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithText("one", useUnmergedTree = true).assertDoesNotExist()
    }
}
