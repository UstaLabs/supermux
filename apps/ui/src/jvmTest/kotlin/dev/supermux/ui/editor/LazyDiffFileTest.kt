package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BlobText
import dev.supermux.net.DiffFile
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

    @Test fun a_lazy_file_renders_from_the_blob_and_the_working_copy() = runComposeUiTest {
        var blobCalls = 0
        setContent {
            Host {
                DiffView(
                    repos = listOf(RepoDiff(repo = "", files = listOf(DiffFile(path = "a.txt", status = "modified", added = 1, removed = 1, baseBlob = "a".repeat(40), lazy = true)))),
                    comments = emptyList(),
                    onAddComment = { _, _, _, _, _, _ -> }, onResolve = {}, onSubmit = {}, onReload = {}, onClose = {},
                    autoExpandAll = true,
                    readFile = { _, _ -> Result.success("two\n") },
                    baseText = { _, _, _ -> blobCalls++; BlobText.Text("one\n") },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("diff_native_0").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("+1").assertExists()
        onNodeWithText("-1").assertExists()
        assertEquals(1, blobCalls)
    }

    @Test fun a_too_large_base_offers_load_anyway() = runComposeUiTest {
        val forced = mutableListOf<Boolean>()
        setContent {
            Host {
                DiffView(
                    repos = listOf(RepoDiff(repo = "", files = listOf(DiffFile(path = "big.txt", status = "modified", added = 1, removed = 1, baseBlob = "a".repeat(40), lazy = true)))),
                    comments = emptyList(),
                    onAddComment = { _, _, _, _, _, _ -> }, onResolve = {}, onSubmit = {}, onReload = {}, onClose = {},
                    autoExpandAll = true,
                    readFile = { _, _ -> Result.success("x\n") },
                    baseText = { _, _, force -> forced += force; if (force) BlobText.Text("y\n") else BlobText.TooLarge(2_000_000) },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("diff_lazy_card_0").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Load anyway").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("diff_native_0").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(false, true), forced)
    }
}
