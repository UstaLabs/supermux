package dev.supermux.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
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
}
