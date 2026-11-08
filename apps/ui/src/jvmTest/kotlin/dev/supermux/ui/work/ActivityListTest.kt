package dev.supermux.ui.work

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The downloads list (#95, design A: round 1's palette, downloads only). Pure — the caller hands in the items and
 * the callbacks — so these drive it with plain [Download] values, in the app theme so nothing depends on the
 * machine's fonts. A phone's sheet draws two lines a row (name and size or %; what became of it and its quiet
 * words); a desktop's popover one line a row, with the key hints under them.
 */
@OptIn(ExperimentalTestApi::class)
class ActivityListTest {
    private fun item(key: String, name: String, state: DownloadState, purpose: DownloadPurpose = DownloadPurpose.File) =
        Download(key, name, "application/pdf", purpose, state)

    private fun running(key: String, name: String, received: Long, total: Long?) =
        item(key, name, DownloadState.Running(received, total))

    private fun done(key: String, name: String, size: Long = 4_404_019) =
        item(key, name, DownloadState.Done(DownloadedFile(name, "application/pdf", size, "/cache/$name")))

    private fun installer(key: String, name: String) = item(
        key,
        name,
        DownloadState.Done(DownloadedFile(name, "application/octet-stream", 100_663_296, "/cache/update")),
        DownloadPurpose.Installer,
    )

    private fun failed(key: String, name: String, message: String = "Download failed") = item(key, name, DownloadState.Failed(message))

    private fun cancelled(key: String, name: String) = item(key, name, DownloadState.Cancelled)

    private fun ComposeUiTest.list(
        items: List<Download>,
        actions: Set<FileAction> = setOf(FileAction.Open, FileAction.Save, FileAction.Share),
        onCancel: (Download) -> Unit = {},
        onRetry: (Download) -> Unit = {},
        onAction: (Download, FileAction) -> Unit = { _, _ -> },
        onInstall: (Download) -> Unit = {},
        onClearFinished: () -> Unit = {},
        popover: Boolean = false,
    ) = setPlatformContent(
        FakePlatform(),
        pointer = popover,
        widthClass = if (popover) WindowWidthClass.Expanded else WindowWidthClass.Compact,
    ) {
        SupermuxTheme(appearance = AppearanceMode.LIGHT) {
            ActivityList(items, actions, onCancel, onRetry, onAction, onInstall, onClearFinished = onClearFinished, popover = popover)
        }
    }

    /** 42 of 100 MiB. */
    private val video = running("r", "big-video.mp4", received = 44_040_192, total = 104_857_600)

    private val everyState = listOf(
        video,
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

    // ── the header ─────────────────────────────────────────────────────────────────────────────

    @Test fun theHeaderCountsTheDownloadsAndClearsWhatHasEnded() = runDesktopComposeUiTest {
        var cleared = 0
        list(everyState, onClearFinished = { cleared++ })
        onNodeWithText("Downloads").assertIsDisplayed()
        onNodeWithTag("activity_count").assertTextEquals("5")
        onNodeWithTag("activity_clear").assertTextEquals("Clear finished").performClick()
        waitForIdle()
        assertEquals(1, cleared)
    }

    @Test fun whileEverythingStillRunsThereIsNothingToClear() = runDesktopComposeUiTest {
        list(listOf(video))
        onNodeWithTag("activity_count").assertTextEquals("1")
        onNodeWithTag("activity_clear").assertDoesNotExist()
    }

    // ── a phone's sheet: two lines a row ───────────────────────────────────────────────────────

    @Test fun onAPhoneEachRowReadsItsNameItsSizeOrPercentThenWhatBecameOfIt() = runDesktopComposeUiTest {
        list(everyState + failed("g", "logs.zip", message = "connection lost"))
        onNode(hasItemKey("r")).assertTextEquals("big-video.mp4", "42%", "42 of 100 MB")
        onNode(hasItemKey("d")).assertTextEquals("notes.pdf", "4.2 MB", "Downloaded")
        onNode(hasItemKey("u")).assertTextEquals("Supermux 1.2.0", "96 MB", "Ready to install")
        onNode(hasItemKey("f")).assertTextEquals("a.zip", "Failed")
        onNode(hasItemKey("g")).assertTextEquals("logs.zip", "Failed · connection lost")
        onNode(hasItemKey("c")).assertTextEquals("b.zip", "Cancelled")
    }

    @Test fun whatRunsComesFirst() = runDesktopComposeUiTest {
        // Newest first, as the center lists them: the running one is older than two that have ended.
        list(listOf(done("d", "notes.pdf"), failed("f", "a.zip"), video))
        val rows = onAllNodesWithTag("activity_item").fetchSemanticsNodes().map { it.config[ActivityItemKey] }
        assertEquals(listOf("r", "d", "f"), rows)
    }

    @Test fun withoutATotalItSaysHowMuchHasArrived() = runDesktopComposeUiTest {
        list(listOf(running("r", "big-video.mp4", received = 12_897_485, total = null)))
        onNode(hasItemKey("r")).assertTextEquals("big-video.mp4", "12 MB so far")
        onNode(
            hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate) and hasAnyAncestor(hasItemKey("r")),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test fun aRunningItemFillsAHairlineAlongTheRow() = runDesktopComposeUiTest {
        list(listOf(video))
        onNode(
            hasTestTag("activity_progress") and hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f)) and
                hasAnyAncestor(hasItemKey("r")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test fun anEndedItemHasNoHairline() = runDesktopComposeUiTest {
        list(listOf(done("d", "notes.pdf"), cancelled("c", "b.zip")))
        onAllNodesWithTag("activity_progress", useUnmergedTree = true).assertCountEquals(0)
    }

    // ── a desktop's popover: one line a row, and the keys ──────────────────────────────────────

    @Test fun inThePopoverEachRowIsOneLine() = runDesktopComposeUiTest {
        list(everyState, popover = true)
        onNode(hasItemKey("r")).assertTextEquals("big-video.mp4", "42 of 100 MB", "42%")
        onNode(hasItemKey("d")).assertTextEquals("notes.pdf", "4.2 MB")
        onNode(hasItemKey("u")).assertTextEquals("Supermux 1.2.0", "ready to install")
        onNode(hasItemKey("f")).assertTextEquals("a.zip", "failed")
        onNode(hasItemKey("c")).assertTextEquals("b.zip", "cancelled")
        onNodeWithTag("activity_hints").assertTextContains("esc to close", substring = true)
        // Each row is one line: no taller than the prototype's 38 dp.
        val row = onNode(hasItemKey("d")).getUnclippedBoundsInRoot()
        assertTrue(row.bottom - row.top <= 38.dp, "a popover row is ${row.bottom - row.top} tall")
    }

    @Test fun aPhonesSheetHasNoKeyHints() = runDesktopComposeUiTest {
        list(everyState)
        onNodeWithTag("activity_hints").assertDoesNotExist()
    }

    @Test fun inThePopoverArrowsMoveAndEnterDoesTheRowsFirstAction() = runDesktopComposeUiTest {
        val calls = mutableListOf<String>()
        list(
            listOf(video, failed("f", "a.zip"), done("d", "notes.pdf")),
            popover = true,
            onRetry = { calls += "retry:${it.key}" },
            onAction = { item, action -> calls += "${action.name}:${item.key}" },
        )
        onNodeWithTag("activity_list").requestFocus()
        onNodeWithTag("activity_list").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("activity_hints").assertTextContains("↵ to retry", substring = true)
        onNodeWithTag("activity_list").performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals(listOf("retry:f", "Open:d"), calls)
    }

    // ── what each item offers ──────────────────────────────────────────────────────────────────

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

    @Test fun thePopoverOffersTheSame() = runDesktopComposeUiTest {
        list(everyState, popover = true)
        assertEquals(listOf("activity_cancel"), buttonsOf("r"))
        assertEquals(listOf("activity_retry"), buttonsOf("f"))
        assertEquals(listOf("activity_retry"), buttonsOf("c"))
        assertEquals(listOf("activity_open", "activity_save", "activity_share"), buttonsOf("d"))
        assertEquals(listOf("activity_install"), buttonsOf("u"))
    }

    @Test fun onlyTheHostsActionsAreOffered() = runDesktopComposeUiTest {
        list(listOf(done("d", "notes.pdf")), actions = setOf(FileAction.Save, FileAction.Share))
        onNodeWithTag("activity_open").assertDoesNotExist()
        onNodeWithTag("activity_save").assertIsDisplayed()
        onNodeWithTag("activity_share").assertIsDisplayed()
    }

    @Test fun eachActionCallsBackWithItsItem() = runDesktopComposeUiTest {
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

    // B2 put each row's key in its content description, which TalkBack and VoiceOver read aloud
    // ("att:host/0123…"). A row is one stop that says what it shows; its quiet words stay stops of their own.
    @Test fun aRowReadsAsWhatItShowsNeverAsItsKey() = runDesktopComposeUiTest {
        list(listOf(done("att:host/0123", "notes.pdf")))
        onAllNodes(hasContentDescription("att:host", substring = true), useUnmergedTree = true).assertCountEquals(0)
        onNode(hasItemKey("att:host/0123")).assertTextEquals("notes.pdf", "4.2 MB", "Downloaded")
        onNode(hasTestTag("activity_open") and hasAnyAncestor(hasItemKey("att:host/0123"))).assertTextEquals("Open")
    }

    // ── long names ─────────────────────────────────────────────────────────────────────────────

    // A desktop does not keep a one-Text middle ellipsis (B3's render lost the percentage): the name gives way in the
    // middle, by our own measure, and the percentage stays in the row.
    @Test fun aLongNameNeverPushesThePercentageOutOfARow() = runDesktopComposeUiTest {
        setPlatformContent(FakePlatform()) {
            SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                Box(Modifier.width(240.dp)) {
                    ActivityList(
                        listOf(running("r", LONG_NAME, received = 44_040_192, total = 104_857_600)),
                        actions = emptySet(),
                        onCancel = {},
                        onRetry = {},
                        onAction = { _, _ -> },
                        onInstall = {},
                    )
                }
            }
        }
        val row = onNodeWithTag("activity_item").assertIsDisplayed().getUnclippedBoundsInRoot()
        val status = onNodeWithText("42%", useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(status.left >= row.left && status.right <= row.right, "the status ($status) runs out of the row ($row)")
        // It still reads as the whole name.
        onNode(hasItemKey("r")).assertTextContains(LONG_NAME)
    }

    @Test fun aNameGivesWayInTheMiddleAndKeepsItsExtension() {
        val name = "big-video-from-the-demo-day.mp4"
        assertEquals(name, middleEllipsis(name) { true }, "a name that fits is left alone")
        val cut = middleEllipsis(name) { it.length <= 20 }
        assertTrue(cut.length <= 20, "\"$cut\" does not fit")
        assertTrue(cut.startsWith("big-video") && cut.endsWith("day.mp4") && "…" in cut, "\"$cut\" lost its ends")
        // No extension to keep (or words): it gives way at the end.
        assertEquals("Supermux 1.2.0 re…", middleEllipsis("Supermux 1.2.0 release notes") { it.length <= 18 })
    }

    // ── how long is left ───────────────────────────────────────────────────────────────────────

    @Test fun theTimeLeftReadsAsThePrototypeSaysIt() {
        val mb = 1_048_576L
        assertEquals("about a minute left", etaText(remaining = 16 * mb, bytesPerSecond = 0.26 * mb))
        assertEquals("about 3 min left", etaText(remaining = 200 * mb, bytesPerSecond = 1.0 * mb))
        assertEquals("15 s left", etaText(remaining = 12 * mb, bytesPerSecond = 1.0 * mb))
        assertEquals("5 s left", etaText(remaining = 2 * mb, bytesPerSecond = 1.0 * mb))
    }

    @Test fun theRateIsKnownOnceASecondOfProgressIsIn() {
        val rate = RateSamples()
        assertNull(rate.add(atMs = 0, received = 0))
        assertNull(rate.add(atMs = 500, received = 100_000), "half a second says nothing yet")
        assertEquals(200_000.0, rate.add(atMs = 2_000, received = 400_000))
        // Only the last few seconds count.
        assertEquals(200_000.0, rate.add(atMs = 20_000, received = 4_000_000))
        // A download that starts again from less (a Retry from the start) is measured afresh.
        assertNull(rate.add(atMs = 21_000, received = 0))
    }

    private companion object {
        /** Wider than a phone's row, as a real recording's name is. */
        const val LONG_NAME = "big-video-from-the-demo-day-and-the-whole-of-the-afternoon-session.mp4"
    }
}
