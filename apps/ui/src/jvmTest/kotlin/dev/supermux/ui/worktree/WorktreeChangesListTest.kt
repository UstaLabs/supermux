package dev.supermux.ui.worktree

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.IgnoredEntryDto
import dev.supermux.net.WorktreeChangesDto
import dev.supermux.net.WorktreeCommitDto
import dev.supermux.net.WorktreeFileDto
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class WorktreeChangesListTest {
    private val changes = WorktreeChangesDto(
        id = "s/u",
        files = listOf(WorktreeFileDto("M", "a.kt"), WorktreeFileDto("??", "b.kt")),
        commits = listOf(WorktreeCommitDto("abc1234", "fix it")),
        ignored = listOf(IgnoredEntryDto("docs", false, 4096), IgnoredEntryDto("node_modules", true, 9_000_000)),
        unmergedKnown = true,
    )

    @Test fun summariesThenExpand() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) { WorktreeChangesList(changes) } }
        onNodeWithText("2 files changed", substring = true).assertIsDisplayed()
        onNodeWithText("1 unmerged commit", substring = true).assertIsDisplayed()
        onNodeWithTag("wt_ignored_summary").assertIsDisplayed()
        onNodeWithText("a.kt").assertDoesNotExist()
        onNodeWithText("2 files changed", substring = true).performClick()
        onNodeWithText("a.kt").assertIsDisplayed()
    }

    @Test fun wellKnownIgnoredIsTaggedGray() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) { WorktreeChangesList(changes) } }
        onNodeWithTag("wt_ignored_summary").performClick()
        onNodeWithTag("wt_ignored_docs").assertIsDisplayed()
        onNodeWithTag("wt_ignored_node_modules_muted").assertIsDisplayed()
    }

    @Test fun emptyShowsNoChanges() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) { WorktreeChangesList(WorktreeChangesDto(id = "s/u", unmergedKnown = true)) } }
        onNodeWithText("No changes").assertIsDisplayed()
    }

    // Final review I-2: "No changes" only when the broker verified it.
    @Test fun inspectionErrorShowsCouldntInspectNeverNoChanges() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { WorktreeChangesList(WorktreeChangesDto(id = "s/u", error = "repo gone")) }
        }
        onNodeWithText("Couldn't inspect: repo gone").assertIsDisplayed()
        onNodeWithText("No changes").assertDoesNotExist()
    }

    @Test fun unknownBaseShowsUnknownUnmergedNeverNoChanges() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { WorktreeChangesList(WorktreeChangesDto(id = "s/u", unmergedKnown = false)) }
        }
        onNodeWithText("Unmerged commits: unknown (base branch not found)").assertIsDisplayed()
        onNodeWithText("No changes").assertDoesNotExist()
    }

    @Test fun unknownBaseStillListsFiles() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { WorktreeChangesList(changes.copy(commits = emptyList(), unmergedKnown = false)) }
        }
        onNodeWithText("2 files changed", substring = true).assertIsDisplayed()
        onNodeWithText("Unmerged commits: unknown (base branch not found)").assertIsDisplayed()
    }
}
