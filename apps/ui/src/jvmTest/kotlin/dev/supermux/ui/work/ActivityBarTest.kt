package dev.supermux.ui.work

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.FileAction
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.work.Download
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadState
import dev.supermux.work.DownloadedFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The activity bar and its list (#95). Both are pure — the caller hands in the items and the callbacks — so
 * these drive them with plain [Download] values, in the app theme so nothing depends on the machine's fonts.
 */
@OptIn(ExperimentalTestApi::class)
class ActivityBarTest {
    private fun item(key: String, name: String, state: DownloadState, purpose: DownloadPurpose = DownloadPurpose.File) =
        Download(key, name, "application/pdf", purpose, state)

    private fun running(key: String, name: String, received: Long, total: Long?) =
        item(key, name, DownloadState.Running(received, total))

    private fun done(key: String, name: String) =
        item(key, name, DownloadState.Done(DownloadedFile(name, "application/pdf", 3, "/cache/$name")))

    private fun installer(key: String, name: String) = item(
        key,
        name,
        DownloadState.Done(DownloadedFile(name, "application/octet-stream", 3, "/cache/update")),
        DownloadPurpose.Installer,
    )

    private fun failed(key: String, name: String) = item(key, name, DownloadState.Failed("Download failed"))

    private fun cancelled(key: String, name: String) = item(key, name, DownloadState.Cancelled)

    private fun ComposeUiTest.bar(
        items: List<Download>,
        pointer: Boolean = true,
        onOpen: () -> Unit = {},
        onClearFinished: () -> Unit = {},
    ) = setPlatformContent(
        FakePlatform(),
        pointer = pointer,
        widthClass = if (pointer) WindowWidthClass.Expanded else WindowWidthClass.Compact,
    ) {
        SupermuxTheme(appearance = AppearanceMode.LIGHT) { ActivityBar(items, onOpen, onClearFinished) }
    }

    private fun ComposeUiTest.list(
        items: List<Download>,
        actions: Set<FileAction> = setOf(FileAction.Open, FileAction.Save, FileAction.Share),
        onCancel: (Download) -> Unit = {},
        onRetry: (Download) -> Unit = {},
        onAction: (Download, FileAction) -> Unit = { _, _ -> },
        onInstall: (Download) -> Unit = {},
    ) = setPlatformContent(FakePlatform()) {
        SupermuxTheme(appearance = AppearanceMode.LIGHT) {
            ActivityList(items, actions, onCancel, onRetry, onAction, onInstall)
        }
    }

    // ── the bar ────────────────────────────────────────────────────────────────────────────────

    @Test fun nothingListedDrawsNoBar() = runDesktopComposeUiTest {
        bar(emptyList())
        onNodeWithTag("activity_bar").assertDoesNotExist()
    }

    @Test fun aRunningItemShowsItsPercentageOnARingAndNoClear() = runDesktopComposeUiTest {
        bar(listOf(running("r", "big-video.mp4", received = 42, total = 100)))
        onNodeWithTag("activity_bar").assertIsDisplayed()
        onNodeWithText("big-video.mp4 · 42%", useUnmergedTree = true).assertIsDisplayed()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f)), useUnmergedTree = true).assertExists()
        onNodeWithTag("activity_bar_more", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("activity_bar_clear").assertDoesNotExist()
    }

    @Test fun withoutATotalItShowsTheBytesSoFarOnASpinner() = runDesktopComposeUiTest {
        bar(listOf(running("r", "big-video.mp4", received = 12_897_485, total = null)))
        onNodeWithText("big-video.mp4 · 12.3 MB", useUnmergedTree = true).assertIsDisplayed()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true).assertExists()
    }

    @Test fun aRunningItemOutranksANewerFinishedOneAndTheRestAreCounted() = runDesktopComposeUiTest {
        // Newest first: the finished one is newer.
        bar(listOf(done("d", "notes.pdf"), running("r", "big-video.mp4", received = 42, total = 100)))
        onNodeWithText("big-video.mp4 · 42%", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("notes.pdf downloaded", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("activity_bar_more", useUnmergedTree = true).assertTextEquals("+1")
        onNodeWithTag("activity_bar_clear").assertDoesNotExist()
    }

    @Test fun withNothingRunningTheNewestShowsAndClearCallsBack() = runDesktopComposeUiTest {
        var cleared = 0
        var opened = 0
        bar(
            listOf(done("d", "notes.pdf"), failed("f", "a.zip"), cancelled("c", "b.zip")),
            onOpen = { opened++ },
            onClearFinished = { cleared++ },
        )
        onNodeWithText("notes.pdf downloaded", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("activity_bar_more", useUnmergedTree = true).assertTextEquals("+2")
        onNodeWithContentDescription("Clear finished downloads").assertIsDisplayed()
        onNodeWithTag("activity_bar_clear").performClick()
        waitForIdle()
        assertEquals(1, cleared)
        assertEquals(0, opened, "clearing does not open the list")
    }

    @Test fun eachStateReadsAsOneLine() = runDesktopComposeUiTest {
        list(
            listOf(
                running("r", "big-video.mp4", received = 42, total = 100),
                done("d", "notes.pdf"),
                installer("u", "Supermux 1.2.0"),
                failed("f", "a.zip"),
                cancelled("c", "b.zip"),
            ),
        )
        onNodeWithText("big-video.mp4 · 42%").assertIsDisplayed()
        onNodeWithText("notes.pdf downloaded").assertIsDisplayed()
        onNodeWithText("Supermux 1.2.0 is ready to install").assertIsDisplayed()
        onNodeWithText("a.zip — download failed").assertIsDisplayed()
        onNodeWithText("b.zip — cancelled").assertIsDisplayed()
    }

    @Test fun tappingTheBarOpensTheList() = runDesktopComposeUiTest {
        var opened = 0
        bar(listOf(running("r", "big-video.mp4", received = 42, total = 100)), onOpen = { opened++ })
        onNodeWithTag("activity_bar").performClick()
        waitForIdle()
        assertEquals(1, opened)
    }

    @Test fun onTouchTheBarIsAFullTouchTarget() = runDesktopComposeUiTest {
        bar(listOf(done("d", "notes.pdf")), pointer = false)
        onNodeWithTag("activity_bar").assertHeightIsAtLeast(48.dp)
    }

    // ── the list ───────────────────────────────────────────────────────────────────────────────

    private val everyState = listOf(
        running("r", "big-video.mp4", received = 42, total = 100),
        failed("f", "a.zip"),
        cancelled("c", "b.zip"),
        done("d", "notes.pdf"),
        installer("u", "Supermux 1.2.0"),
    )

    private val buttonTags =
        listOf("activity_cancel", "activity_retry", "activity_open", "activity_save", "activity_share", "activity_install")

    /** The buttons drawn in [key]'s row, in [buttonTags] order. */
    private fun ComposeUiTest.buttonsOf(key: String): List<String> = buttonTags.filter { tag ->
        onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("activity_item") and hasItemKey(key)))
            .fetchSemanticsNodes().isNotEmpty()
    }

    /** The list row of download [key]: found by its own semantics property, which nothing reads aloud. */
    private fun hasItemKey(key: String) = SemanticsMatcher.expectValue(ActivityItemKey, key)

    @Test fun eachItemOffersExactlyWhatItsStateAllows() = runDesktopComposeUiTest {
        list(everyState)
        onNodeWithTag("activity_list").assertIsDisplayed()
        onAllNodesWithTag("activity_item").assertCountEquals(5)
        assertEquals(listOf("activity_cancel"), buttonsOf("r"))
        assertEquals(listOf("activity_retry"), buttonsOf("f"))
        assertEquals(listOf("activity_retry"), buttonsOf("c"))
        assertEquals(listOf("activity_open", "activity_save", "activity_share"), buttonsOf("d"))
        assertEquals(listOf("activity_install"), buttonsOf("u"))
    }

    @Test fun aRunningItemShowsItsProgressAlongTheRow() = runDesktopComposeUiTest {
        list(listOf(running("r", "big-video.mp4", received = 42, total = 100)))
        onNode(
            hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f)) and hasAnyAncestor(hasItemKey("r")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    // B2 put each row's key in its content description, which TalkBack and VoiceOver read aloud
    // ("att:host/0123…"). A row is one stop that says what it shows; its buttons stay stops of their own.
    @Test fun aRowReadsAsWhatItShowsNeverAsItsKey() = runDesktopComposeUiTest {
        list(listOf(done("att:host/0123", "notes.pdf")))
        onAllNodes(hasContentDescription("att:host", substring = true), useUnmergedTree = true).assertCountEquals(0)
        onNode(hasItemKey("att:host/0123")).assertTextEquals("notes.pdf downloaded")
        onNode(hasTestTag("activity_open") and hasAnyAncestor(hasItemKey("att:host/0123"))).assertTextEquals("Open")
    }

    @Test fun onlyTheHostsActionsAreOffered() = runDesktopComposeUiTest {
        list(listOf(done("d", "notes.pdf")), actions = setOf(FileAction.Save, FileAction.Share))
        onNodeWithTag("activity_open").assertDoesNotExist()
        onNodeWithTag("activity_save").assertIsDisplayed()
        onNodeWithTag("activity_share").assertIsDisplayed()
    }

    @Test fun eachButtonCallsBackWithItsItem() = runDesktopComposeUiTest {
        val calls = mutableListOf<String>()
        list(
            everyState,
            onCancel = { calls += "cancel:${it.key}" },
            onRetry = { calls += "retry:${it.key}" },
            onAction = { item, action -> calls += "${action.name}:${item.key}" },
            onInstall = { calls += "install:${it.key}" },
        )
        onNodeWithTag("activity_cancel").performClick()
        onNode(hasTestTag("activity_retry") and hasAnyAncestor(hasItemKey("c"))).performClick()
        onNodeWithTag("activity_open").performClick()
        onNodeWithTag("activity_save").performClick()
        onNodeWithTag("activity_share").performClick()
        onNodeWithTag("activity_install").performClick()
        waitForIdle()
        assertEquals(
            listOf("cancel:r", "retry:c", "Open:d", "Save:d", "Share:d", "install:u"),
            calls,
        )
    }
}
