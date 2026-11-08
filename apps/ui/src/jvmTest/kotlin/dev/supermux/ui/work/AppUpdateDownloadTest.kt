package dev.supermux.ui.work

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakeAppUpdater
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.session.SessionListFooter
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.ui.update.AppUpdateCard
import dev.supermux.ui.update.AppUpdateScreen
import dev.supermux.update.ClientUpdateStatus
import dev.supermux.work.Download
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The app update on the fleet's downloads (#95). The update page's button and the sidebar card's Install used to
 * download on their own coroutine, cancelled on purpose when he left. Now they start `app-update:<version>` in the
 * center and only wait for it: leaving the page, or the card going away, no longer cancels the download — it
 * finishes in the bar, announced, and the bar's Install hands the same installer to the updater. Still on screen
 * when it finishes, the installer goes to the OS at once, as before (R1). The updater keeps publishing its own
 * status throughout, so the page still shows its percentage.
 */
@OptIn(ExperimentalTestApi::class)
class AppUpdateDownloadTest {
    // The center runs here: unconfined, so a start has run up to its first suspension by the time it returns.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest fun tearDown() {
        scope.cancel()
    }

    /** Holds the update's download at 50 of its 100 bytes until a test opens it. */
    private val gate = CompletableDeferred<Unit>()

    private val release = ClientUpdateStatus(
        currentVersion = "1.0.0",
        latestVersion = "1.1.0",
        updateAvailable = true,
        notesUrl = "https://example.test/notes",
        downloadUrl = "https://example.test/supermux-1.1.0.apk",
        canInstall = true,
    )

    /** An updater whose check found 1.1.0 (the shell checks once per launch; the card never does). */
    private fun updater() = FakeAppUpdater(release = release, midDownload = gate).also { runBlocking { it.check() } }

    private fun platform(updater: FakeAppUpdater) =
        FakePlatform(caps = NO_CAPS.copy(appUpdate = true)).also { it.updates = updater }

    /** A phone, in the app theme, in [center]'s fleet. */
    private fun ComposeUiTest.phone(center: DownloadCenter, platform: FakePlatform, content: @Composable () -> Unit) =
        setPlatformContent(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                CompositionLocalProvider(LocalDownloads provides center, content = content)
            }
        }

    /** A desktop (a pointer, an Expanded window), in the app theme, in [center]'s fleet. */
    private fun ComposeUiTest.wide(center: DownloadCenter, platform: FakePlatform, content: @Composable () -> Unit) =
        setPlatformContent(platform) {
            SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                CompositionLocalProvider(LocalDownloads provides center, content = content)
            }
        }

    /** What [center] announces as finished with nobody waiting (the shell's "<name> downloaded" notice). */
    private fun announced(center: DownloadCenter): List<Download> {
        val heard = CopyOnWriteArrayList<Download>()
        scope.launch { center.unclaimedCompletions.collect { heard += it } }
        return heard
    }

    /** Up to [ms] for [done]; returns either way: the assertion after it says what was wanted. */
    private fun ComposeUiTest.settle(ms: Long = 5_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) {
            waitForIdle()
            Thread.sleep(10)
        }
    }

    /** A sheet is up only once its animation has run. */
    private fun ComposeUiTest.waitFor(block: () -> Unit) =
        waitUntil(timeoutMillis = 5_000) {
            try {
                block()
                true
            } catch (_: Throwable) {
                false
            }
        }

    /** Home's bottom bar, where the downloads are (#95, design A): the ring opens their list. */
    @Composable
    private fun BottomBar() =
        SessionListFooter(appearance = AppearanceMode.LIGHT, onToggleTheme = {}, onUsage = {}, onDevices = {}, onSettings = {})

    /** On [center]'s phone: the page's button pressed, the page left mid-download, then the download let finish. */
    private fun ComposeUiTest.startOnThePageThenLeave(updater: FakeAppUpdater, center: DownloadCenter): () -> Unit {
        var shown by mutableStateOf(true)
        phone(center, platform(updater)) { if (shown) AppUpdateScreen() else BottomBar() }
        onNodeWithTag("app_update_install").performClick()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("app-update:1.1.0"), center.items.value.map { it.key })

        // He goes back; the download goes on without the page.
        shown = false
        waitForIdle()
        onNodeWithTag("app_update_overlay").assertDoesNotExist()
        gate.complete(Unit)
        settle { center.items.value.firstOrNull()?.state is DownloadState.Done }
        return { shown = true }
    }

    // ── leaving ────────────────────────────────────────────────────────────────────────────────

    @Test fun leavingThePageMidDownloadDoesNotCancelIt() = runDesktopComposeUiTest {
        val updater = updater()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        startOnThePageThenLeave(updater, center)
        settle { heard.isNotEmpty() }

        val item = center.items.value.single()
        assertEquals("Supermux 1.1.0", item.name)
        assertEquals(DownloadPurpose.Installer, item.purpose)
        val done = assertIs<DownloadState.Done>(item.state)
        assertEquals(listOf("app-update:1.1.0"), heard.map { it.key }, "announced: it waits in the bar")
        assertEquals(0, updater.cancelledDownloads, "leaving the page cancelled the download")
        assertEquals(1, updater.downloadCalls)
        assertEquals(emptyList(), updater.installed.toList(), "nothing installs behind his back (2a)")
        assertSame(updater.downloaded.single(), done.file.handle, "the bar's item holds the updater's installer")
        assertEquals(100L, done.file.size, "its size from the last progress")
    }

    @Test fun theBarsInstallHandsTheSameInstallerToTheUpdater() = runDesktopComposeUiTest {
        val updater = updater()
        val center = DownloadCenter(scope)
        startOnThePageThenLeave(updater, center)
        assertIs<DownloadState.Done>(center.items.value.single().state)

        onNodeWithTag("sidebar_footer_downloads").performClick()
        waitFor { onNodeWithTag("activity_install").assertIsDisplayed() }
        onNodeWithTag("activity_install").performClick()
        settle { updater.installed.isNotEmpty() }

        assertEquals(1, updater.installed.size)
        assertSame(updater.downloaded.single(), updater.installed.single())
    }

    @Test fun comingBackToThePageInstallsWhatTheBarHoldsWithoutASecondDownload() = runDesktopComposeUiTest {
        val updater = updater()
        val center = DownloadCenter(scope)
        val comeBack = startOnThePageThenLeave(updater, center)
        assertIs<DownloadState.Done>(center.items.value.single().state)

        comeBack()
        waitForIdle()
        onNodeWithTag("app_update_install").performClick()
        settle { updater.installed.isNotEmpty() }

        assertEquals(1, updater.downloadCalls, "the installer the bar holds, not a second download")
        assertSame(updater.downloaded.single(), updater.installed.single())
    }

    @Test fun theCardsDownloadGoesOnWhenTheCardGoesAway() = runDesktopComposeUiTest {
        val updater = updater()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        var opens = 0
        var shown by mutableStateOf(true)
        wide(center, platform(updater)) { if (shown) AppUpdateCard(onOpenPage = { opens++ }) }
        onNodeWithTag("app_update_card_update").performClick()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("app-update:1.1.0"), center.items.value.map { it.key })

        // The sidebar goes (a phone opens a chat over it); the download does not.
        shown = false
        waitForIdle()
        gate.complete(Unit)
        settle { heard.isNotEmpty() }

        assertIs<DownloadState.Done>(center.items.value.single().state)
        assertEquals(listOf("app-update:1.1.0"), heard.map { it.key }, "announced: it waits in the bar")
        assertEquals(0, updater.cancelledDownloads)
        assertEquals(emptyList(), updater.installed.toList())
        assertEquals(0, opens, "a download that went on is no failure to read about on the page")
    }

    // ── staying ────────────────────────────────────────────────────────────────────────────────

    @Test fun stayingOnThePageInstallsItAtOnce() = runDesktopComposeUiTest {
        val updater = updater()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        phone(center, platform(updater)) { AppUpdateScreen() }
        onNodeWithTag("app_update_install").performClick()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("app-update:1.1.0"), center.items.value.map { it.key }, "listed in the bar while it runs")
        gate.complete(Unit)
        settle { updater.installed.isNotEmpty() }

        assertEquals(1, updater.installed.size, "the page that waited installs it, as before (R1)")
        assertSame(updater.downloaded.single(), updater.installed.single())
        assertEquals(emptyList(), heard.toList(), "the page acted on it: no notice")
        assertIs<DownloadState.Done>(center.items.value.single().state)
    }

    // The bar's Cancel ends the job, and with it the updater's own transfer — which settles its phase, so the
    // buttons work again. His Cancel is no failure: the card does not send him to the page to read about it.
    @Test fun theBarsCancelStopsTheUpdatersDownloadAndIsNoFailure() = runDesktopComposeUiTest {
        val updater = updater()
        val platform = platform(updater)
        val center = DownloadCenter(scope)
        var opens = 0
        wide(center, platform) { AppUpdateCard(onOpenPage = { opens++ }) }
        onNodeWithTag("app_update_card_update").performClick()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("app-update:1.1.0"), center.items.value.map { it.key })

        center.cancel("app-update:1.1.0")
        waitForIdle()

        assertEquals(DownloadState.Cancelled, center.items.value.single().state)
        assertEquals(1, updater.cancelledDownloads, "the bar's Cancel reaches the updater's transfer")
        assertFalse(updater.status.value.busy, "the updater settled its phase")
        assertEquals(0, opens)
        assertEquals(0, updater.permissionSettingsOpened)
        assertEquals(emptyList(), platform.notices.shown)
        onNodeWithTag("app_update_card_update").assertIsEnabled()
    }
}
