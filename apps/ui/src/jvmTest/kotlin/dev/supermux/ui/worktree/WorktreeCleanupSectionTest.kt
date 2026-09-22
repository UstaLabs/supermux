package dev.supermux.ui.worktree

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.WorktreeChangesDto
import dev.supermux.net.WorktreeFileDto
import dev.supermux.net.WorktreeForWorkdirDto
import dev.supermux.net.WorktreeOwnerDto
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 2026-09-22-explicit-worktree-cleanup §4.2. */
@OptIn(ExperimentalTestApi::class)
class WorktreeCleanupSectionTest {
    private fun wt(owners: List<WorktreeOwnerDto>) = WorktreeForWorkdirDto(
        id = "s/u", branch = "mux/a", owners = owners, bytes = 1_048_576,
        changes = WorktreeChangesDto(id = "s/u", files = listOf(WorktreeFileDto("M", "a.kt"))),
    )

    @Test fun uncheckedByDefaultAndReportsTheChoiceWithTheDisplayedIds() = runComposeUiTest {
        var checked: Boolean? = null
        var ids: List<String>? = null
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreeCleanupSection(
                workdirs = listOf("/w"), archivingSessionIds = setOf("me"),
                load = { wt(listOf(WorktreeOwnerDto("me", "Me", "live"))) },
                onDeleteChange = { c, shown -> checked = c; ids = shown },
            )
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_cleanup_checkbox").assertIsOff(); true }.getOrDefault(false) }
        onNodeWithText("mux/a", substring = true).assertIsDisplayed()
        onNodeWithText("1 MB", substring = true).assertIsDisplayed()
        onNodeWithTag("wt_cleanup_checkbox").performClick()
        assertEquals(true, checked)
        assertEquals(listOf("s/u"), ids)
    }

    @Test fun sharedWithALiveSessionShowsTheNoteAndNoCheckbox() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreeCleanupSection(
                workdirs = listOf("/w"), archivingSessionIds = setOf("me"),
                load = { wt(listOf(WorktreeOwnerDto("me", "Me", "live"), WorktreeOwnerDto("p", "Parent", "live"))) },
                onDeleteChange = { _, _ -> },
            )
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Worktree kept — also used by Parent").assertIsDisplayed(); true }.getOrDefault(false) }
        onNodeWithTag("wt_cleanup_checkbox").assertDoesNotExist()
    }

    @Test fun notAWorktreeShowsNothing() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreeCleanupSection(workdirs = listOf("/home/x"), archivingSessionIds = setOf("me"), load = { null }, onDeleteChange = { _, _ -> })
        } }
        waitForIdle()
        onNodeWithTag("wt_cleanup_checkbox").assertDoesNotExist()
        onNodeWithTag("wt_cleanup_kept").assertDoesNotExist()
    }

    /** A workspace spanning two worktrees: both are shown; the kept one gets its note and is
     *  never reported, only the deletable one's id goes to the broker. */
    @Test fun everyWorktreeIsShownAndOnlyDeletableIdsAreReported() = runComposeUiTest {
        var ids: List<String>? = null
        val kept = WorktreeForWorkdirDto(
            id = "s/kept", branch = "mux/kept", owners = listOf(WorktreeOwnerDto("me", "Me", "live"), WorktreeOwnerDto("p", "Parent", "live")),
            changes = WorktreeChangesDto(id = "s/kept"),
        )
        val gone = WorktreeForWorkdirDto(id = "s/gone", branch = "mux/gone", owners = listOf(WorktreeOwnerDto("me2", "Me2", "live")), changes = WorktreeChangesDto(id = "s/gone"))
        setPlatformContent(platform = FakePlatform()) { SupermuxTheme(appearance = AppearanceMode.DARK) {
            WorktreeCleanupSection(
                workdirs = listOf("/a", "/b"), archivingSessionIds = setOf("me", "me2"),
                load = { if (it == "/a") kept else gone },
                onDeleteChange = { _, shown -> ids = shown },
            )
        } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("wt_cleanup_checkbox").assertExists(); true }.getOrDefault(false) }
        onNodeWithText("Worktree kept — also used by Parent").assertIsDisplayed()
        onNodeWithText("mux/gone", substring = true).assertIsDisplayed()
        onNodeWithTag("wt_cleanup_checkbox").performClick()
        assertEquals(listOf("s/gone"), ids)
    }
}
