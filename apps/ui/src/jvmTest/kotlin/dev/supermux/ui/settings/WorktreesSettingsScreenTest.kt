package dev.supermux.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.WorktreeDeleteResultDto
import dev.supermux.net.WorktreeOwnerDto
import dev.supermux.net.WorktreeSummaryDto
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel

@OptIn(ExperimentalTestApi::class)
class WorktreesSettingsScreenTest {
    private fun w(id: String, owners: List<WorktreeOwnerDto> = emptyList(), changes: Boolean = false, bytes: Long = 1_048_576) =
        WorktreeSummaryDto(id = id, path = "/r/$id", repoName = "supermux", branch = "mux/$id", owners = owners,
            mtime = 0, uncommitted = if (changes) 1 else 0, unmerged = 0, hasChanges = changes, bytes = bytes)

    private val list = listOf(
        w("clean1"), w("clean2"),
        w("dirty", changes = true),
        w("live", owners = listOf(WorktreeOwnerDto("s", "Sess", "live"))),
    )

    @Test fun selectAllWithoutChangesSkipsLiveAndChanged() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreesSettingsScreen(actions = WorktreesSettingsActions(load = { list }), topBarShown = true)
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_row_clean1").assertExists(); true }.getOrDefault(false) }
        onNodeWithTag("wt_select_clean").performClick()
        onNodeWithTag("wt_delete_bar").assertTextContains("Delete 2 worktrees", substring = true)
        onNodeWithTag("wt_check_live").assertIsNotEnabled()
        onNodeWithText("In use by Sess", substring = true).assertIsDisplayed()
    }

    @Test fun confirmationWarnsAboutChangesAndDeletes() = runComposeUiTest {
        var deleted: List<String>? = null
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreesSettingsScreen(actions = WorktreesSettingsActions(
                load = { list },
                delete = { ids -> deleted = ids; ids.map { WorktreeDeleteResultDto(it, ok = true) } },
            ), topBarShown = true)
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_row_dirty").assertExists(); true }.getOrDefault(false) }
        onNodeWithTag("wt_check_dirty").performClick()
        onNodeWithTag("wt_check_clean1").performClick()
        onNodeWithTag("wt_delete_bar").performClick()
        onNodeWithText("1 of them has changes that will be lost", substring = true).assertIsDisplayed()
        onNodeWithTag("wt_delete_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { deleted != null }
        assertEquals(setOf("dirty", "clean1"), deleted!!.toSet())
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_row_dirty").assertDoesNotExist(); true }.getOrDefault(false) }
    }

    @Test fun failedDeleteKeepsTheRowWithItsError() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreesSettingsScreen(actions = WorktreesSettingsActions(
                load = { list },
                delete = { ids -> ids.map { WorktreeDeleteResultDto(it, ok = false, error = "boom") } },
            ), topBarShown = true)
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_row_clean1").assertExists(); true }.getOrDefault(false) }
        onNodeWithTag("wt_check_clean1").performClick()
        onNodeWithTag("wt_delete_bar").performClick()
        onNodeWithTag("wt_delete_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("boom", substring = true).assertIsDisplayed(); true }.getOrDefault(false) }
        onNodeWithTag("wt_row_clean1").assertExists()
    }

    private fun ComposeUiTest.top(tag: String) = onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    // Final review I-3: live sizes must not re-sort rows under the user's finger; a manual refresh does.
    @Test fun sizeUpdatesDoNotReorderRowsButRefreshDoes() = runComposeUiTest {
        var sizes by mutableStateOf(mapOf<String, Long>())
        val rows = listOf(w("a", bytes = 3_145_728), w("b", bytes = 2_097_152), w("c", bytes = 1_048_576))
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreesSettingsScreen(actions = WorktreesSettingsActions(load = { rows }, sizes = { sizes }), topBarShown = true)
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_row_c").assertExists(); true }.getOrDefault(false) }
        assertTrue(top("wt_row_a") < top("wt_row_b") && top("wt_row_b") < top("wt_row_c"))
        sizes = mapOf("c" to 10_485_760L)
        waitForIdle()
        assertTrue(top("wt_row_a") < top("wt_row_b") && top("wt_row_b") < top("wt_row_c"), "a size frame must not move rows")
        onNodeWithText("10 MB", substring = true).assertIsDisplayed()   // the live size is still drawn
        onNodeWithContentDescription("Refresh").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { top("wt_row_c") < top("wt_row_a") }.getOrDefault(false) }
    }

    // Final review I-1: big batches go in chunks of 20, rows update after each chunk, progress shows.
    @Test fun deletesInChunksOf20WithProgress() = runComposeUiTest {
        val many = (0 until 45).map { w("r%02d".format(it)) }
        val calls = mutableListOf<List<String>>()
        val release = Channel<Unit>(Channel.UNLIMITED)
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreesSettingsScreen(actions = WorktreesSettingsActions(
                load = { many },
                delete = { ids -> synchronized(calls) { calls += ids }; release.receive(); ids.map { WorktreeDeleteResultDto(it, ok = true) } },
            ), topBarShown = true)
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_row_r00").assertExists(); true }.getOrDefault(false) }
        onNodeWithTag("wt_select_clean").performClick()
        onNodeWithTag("wt_delete_bar").assertTextContains("Delete 45 worktrees", substring = true)
        onNodeWithTag("wt_delete_bar").performClick()
        onNodeWithTag("wt_delete_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Deleting 0 / 45…").assertExists(); true }.getOrDefault(false) }
        release.trySend(Unit)
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Deleting 20 / 45…").assertExists(); true }.getOrDefault(false) }
        onNodeWithTag("wt_row_r00").assertDoesNotExist()   // the first chunk's result is applied already
        release.trySend(Unit); release.trySend(Unit)
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("No worktrees.").assertExists(); true }.getOrDefault(false) }
        assertEquals(listOf(20, 20, 5), synchronized(calls) { calls.map { it.size } })
        assertEquals(many.map { it.id }.toSet(), synchronized(calls) { calls.flatten().toSet() })
    }

    // Final review m7: the bar counts only the VISIBLE selection and hides at 0.
    @Test fun deleteBarFollowsTheVisibleSelection() = runComposeUiTest {
        var removed by mutableStateOf(setOf<String>())
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreesSettingsScreen(actions = WorktreesSettingsActions(load = { list }, removedIds = { removed }), topBarShown = true)
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_row_clean1").assertExists(); true }.getOrDefault(false) }
        onNodeWithTag("wt_check_clean1").performClick()
        onNodeWithTag("wt_delete_bar").assertTextContains("Delete 1 worktrees", substring = true)
        removed = setOf("clean1")   // deleted elsewhere (worktrees_removed)
        waitForIdle()
        onNodeWithTag("wt_delete_bar").assertDoesNotExist()
    }

    // Final review m7: groups are per repository ROOT; same-named repos are told apart.
    @Test fun groupsByRepoRootAndDisambiguatesSameNames() = runComposeUiTest {
        val rows = listOf(
            w("x").copy(repoName = "app", repoRoot = "/home/u/work/app"),
            w("y").copy(repoName = "app", repoRoot = "/home/u/play/app"),
            w("z").copy(repoName = "solo", repoRoot = "/home/u/solo"),
        )
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreesSettingsScreen(actions = WorktreesSettingsActions(load = { rows }), topBarShown = true)
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_row_x").assertExists(); true }.getOrDefault(false) }
        onNodeWithText("app (/home/u/work)", substring = true).assertExists()
        onNodeWithText("app (/home/u/play)", substring = true).assertExists()
        onNodeWithText("solo  1", substring = true).assertExists()
    }
}
