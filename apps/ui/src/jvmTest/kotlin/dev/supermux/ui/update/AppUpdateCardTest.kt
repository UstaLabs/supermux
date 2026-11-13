package dev.supermux.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.update.ClientUpdateStatus
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakeAppUpdater
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.platform.UpdateStatus
import dev.supermux.ui.settings.pages.AppUpdateRows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sidebar's update card (cluster G5) — desktop's and Android's, over one
 * [dev.supermux.ui.platform.AppUpdater].
 *
 * It sits on the sidebar's footer rail on both hosts, so the cases below are about what a card must
 * get right: it appears only when there is genuinely something to install, it says what is new,
 * and it can always be got rid of.
 */
@OptIn(ExperimentalTestApi::class)
class AppUpdateCardTest {

    private fun release(
        latest: String? = "1.1.0",
        available: Boolean = true,
        download: String? = "https://example.test/supermux-1.1.0.apk",
        highlights: List<String> = emptyList(),
    ) = ClientUpdateStatus(
        currentVersion = "1.0.0",
        latestVersion = latest,
        updateAvailable = available,
        notesUrl = "https://example.test/notes",
        downloadUrl = download,
        canInstall = download != null,
        highlights = highlights,
    )

    private fun platform(updater: FakeAppUpdater) =
        FakePlatform(caps = NO_CAPS.copy(appUpdate = true)).also { it.updates = updater }

    /** The card never checks on its own (the shell does, once per launch); the tests stand in for it. */
    @Composable
    private fun CheckedCard(updater: FakeAppUpdater, onOpenPage: () -> Unit = {}) {
        LaunchedEffect(updater) { updater.check() }
        AppUpdateCard(onOpenPage = onOpenPage)
    }

    // ── when it shows at all ──────────────────────────────────────────────────────────────────

    @Test fun nothing_is_drawn_while_the_first_check_is_still_running() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val updater = FakeAppUpdater(release = release(), midCheck = gate)
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card").assertDoesNotExist()
        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("app_update_card").assertIsDisplayed()
    }

    @Test fun being_up_to_date_draws_nothing() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(available = false))
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card").assertDoesNotExist()
    }

    @Test fun a_failed_check_draws_nothing() = runComposeUiTest {
        val updater = FakeAppUpdater(release = null)
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card").assertDoesNotExist()
    }

    @Test fun an_available_release_names_its_version() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card_version", useUnmergedTree = true).assertTextEquals("v1.1.0 is ready to install.")
    }

    // ── what's new ────────────────────────────────────────────────────────────────────────────

    @Test fun no_highlights_means_no_popover() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card").assertIsDisplayed()
        onNodeWithTag("app_update_card_notes", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun whats_new_opens_a_popover_with_every_highlight() = runComposeUiTest {
        val notes = listOf("Agent lanes", "Windows sessions start", "No console windows", "Faster sidebar", "Search")
        val updater = FakeAppUpdater(release = release(highlights = notes))
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card_notes").assertDoesNotExist()
        onNodeWithTag("app_update_card_whats_new").performClick()
        waitForIdle()
        onNodeWithTag("app_update_card_notes").assertIsDisplayed()
        notes.forEach { onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun a_release_with_no_installer_url_still_shows_but_offers_no_button() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(download = null))
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card").assertIsDisplayed()
        onNodeWithTag("app_update_card_update").assertDoesNotExist()
    }

    // ── dismissal ─────────────────────────────────────────────────────────────────────────────

    @Test fun dismissing_hides_it_and_remembers_the_version() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card_dismiss").performClick()
        waitForIdle()
        onNodeWithTag("app_update_card").assertDoesNotExist()
        assertEquals(listOf("1.1.0"), updater.dismissedVersions)
        assertTrue(updater.status.value.dismissed)
    }

    @Test fun a_version_dismissed_on_an_earlier_run_never_reappears() = runComposeUiTest {
        // The host stores the dismissal, so the very first check comes back already dismissed.
        val updater = FakeAppUpdater(release = release())
        updater.dismissedVersions += "1.1.0"
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card").assertDoesNotExist()
    }

    // ── the card's two actions ────────────────────────────────────────────────────────────────

    @Test fun whats_new_with_no_highlights_opens_the_release_notes() = runComposeUiTest {
        // No highlights means nothing to list, so the button goes straight to the full notes.
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card_whats_new").performClick()
        waitForIdle()
        assertEquals(1, updater.notesOpened)
        onNodeWithTag("app_update_card_notes").assertDoesNotExist()
    }

    @Test fun the_popover_links_to_the_full_release_notes() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(highlights = listOf("Agent lanes")))
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card_whats_new").performClick()
        waitForIdle()
        onNodeWithTag("app_update_card_full_notes").performClick()
        waitForIdle()
        assertEquals(1, updater.notesOpened)
        onNodeWithTag("app_update_card_notes").assertDoesNotExist()
    }

    @Test fun update_downloads_and_installs_without_leaving_the_card() = runComposeUiTest {
        var opens = 0
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { CheckedCard(updater, onOpenPage = { opens++ }) }
        waitForIdle()
        onNodeWithTag("app_update_card_update").performClick()
        waitForIdle()
        assertEquals(1, updater.installed.size)
        assertEquals(0, opens)
    }

    @Test fun the_button_shows_progress_and_is_disabled_while_downloading() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val updater = FakeAppUpdater(release = release(), midDownload = gate)
        setPlatformContent(platform(updater)) { CheckedCard(updater) }
        waitForIdle()
        onNodeWithTag("app_update_card_update").assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithTag("app_update_card_update").assertIsNotEnabled()
            .assertTextEquals("Downloading 50%…")
        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("app_update_card_update").assertIsEnabled().assertTextEquals("Install")
    }

    @Test fun a_refused_install_jumps_to_the_permission_settings() = runComposeUiTest {
        var opens = 0
        val updater = FakeAppUpdater(release = release(), refuseInstall = true)
        setPlatformContent(platform(updater)) { CheckedCard(updater, onOpenPage = { opens++ }) }
        waitForIdle()
        onNodeWithTag("app_update_card_update").performClick()
        waitForIdle()
        assertEquals(1, updater.permissionSettingsOpened)
        assertEquals(0, opens)
    }

    @Test fun a_failed_download_opens_the_page_where_the_error_is_readable() = runComposeUiTest {
        var opens = 0
        val updater = FakeAppUpdater(release = release(), downloadError = "connection reset")
        setPlatformContent(platform(updater)) { CheckedCard(updater, onOpenPage = { opens++ }) }
        waitForIdle()
        onNodeWithTag("app_update_card_update").performClick()
        waitForIdle()
        assertEquals(1, opens)
        assertTrue(updater.installed.isEmpty())
    }

    // ── both widths ───────────────────────────────────────────────────────────────────────────

    @Test fun the_card_is_the_same_at_compact_and_expanded() = runComposeUiTest {
        // BOTH widths in one test: the card has no width branch, and the name only means
        // something if the Expanded render is actually asserted too.
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Compact) {
            Column(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                    CheckedCard(updater)
                }
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Expanded) {
                    CheckedCard(updater)
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag("app_update_card").assertCountEquals(2)
        onAllNodesWithTag("app_update_card_update").assertCountEquals(2)
        onAllNodesWithTag("app_update_card_dismiss").assertCountEquals(2)
    }

    @Test fun one_seam_means_the_card_and_the_page_agree() = runComposeUiTest {
        // Both mount the SAME updater (the card sits in the shell that hosts the page), so the
        // page's check is the card's check — one poll, one answer, and a dismissal settles both.
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Expanded) {
            Column(Modifier.fillMaxSize()) {
                CheckedCard(updater)
                AppUpdateRows()
            }
        }
        waitForIdle()
        onNodeWithTag("app_update_card").assertIsDisplayed()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 · 1.1.0 is ready")
        onNodeWithTag("app_update_card_dismiss").performClick()
        waitForIdle()
        onNodeWithTag("app_update_card").assertDoesNotExist()
        // Dismissal is about the CARD; General still offers the update.
        onNodeWithTag("app_update_install").assertIsDisplayed()
    }

    @Test fun showsUpdateCard_is_a_newer_undismissed_release_and_nothing_else() {
        assertFalse(UpdateStatus().showsUpdateCard)
        assertFalse(UpdateStatus(release = release(available = false)).showsUpdateCard)
        assertTrue(UpdateStatus(release = release()).showsUpdateCard)
        assertFalse(UpdateStatus(release = release(), dismissed = true).showsUpdateCard)
    }

    // ── #113: background updates, "Restart to update" ─────────────────────────────────────────

    @Test fun a_background_update_shows_no_card_until_it_is_downloaded() {
        val found = UpdateStatus(release = release(), background = true)
        assertFalse(found.showsUpdateCard, "quiet while it downloads")
        assertTrue(found.copy(ready = dev.supermux.ui.platform.DownloadedInstaller("/tmp/u.msi", "msi")).showsUpdateCard)
        assertTrue(found.copy(background = false).showsUpdateCard, "switched off: today's card")
    }

    @Test fun a_ready_update_restarts_to_update_without_downloading_again() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release()).also { it.restartsToUpdate = true }
        setPlatformContent(platform(updater)) {
            LaunchedEffect(updater) { updater.check(); updater.becomeReady() }
            AppUpdateCard()
        }
        waitForIdle()
        onNodeWithText("Update ready").assertIsDisplayed()
        onNodeWithTag("app_update_card_version", useUnmergedTree = true).assertTextEquals("v1.1.0 is downloaded.")
        onNodeWithTag("app_update_card_update").assertTextEquals("Restart to update")
        onNodeWithTag("app_update_card_update").performClick()
        waitForIdle()
        assertEquals(listOf("/tmp/ready.msi"), updater.installed.map { it.location })
    }

    @Test fun a_ready_update_that_cannot_restart_the_app_says_install() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) {
            LaunchedEffect(updater) { updater.check(); updater.becomeReady(dev.supermux.ui.platform.DownloadedInstaller("/tmp/ready.dmg", "dmg")) }
            AppUpdateCard()
        }
        waitForIdle()
        onNodeWithTag("app_update_card_update").assertTextEquals("Install")
        onNodeWithTag("app_update_card_update").performClick()
        waitForIdle()
        assertEquals(listOf("/tmp/ready.dmg"), updater.installed.map { it.location })
    }

    // The shell's launch check: once, then (desktop) a background prepare now and every 6 hours while the app runs.
    @Test fun the_launch_check_prepares_in_the_background_and_again_every_six_hours() = kotlinx.coroutines.test.runTest {
        val updater = FakeAppUpdater(release = release()).also { it.offersBackgroundUpdates = true }
        val job = launch { updater.keepUpdated() }
        testScheduler.runCurrent()
        assertEquals(1, updater.checks)
        assertEquals(1, updater.prepares)
        testScheduler.advanceTimeBy(6 * 60 * 60 * 1000L + 1)
        assertEquals(2, updater.prepares)
        job.cancel()
    }

    @Test fun where_nothing_downloads_in_the_background_the_launch_check_is_the_only_one() = kotlinx.coroutines.test.runTest {
        val updater = FakeAppUpdater(release = release())
        updater.keepUpdated()
        assertEquals(1, updater.checks)
        assertEquals(0, updater.prepares)
    }

    // ── #113: the updated app says so once, after its sessions are back ───────────────────────

    private class Said : dev.supermux.ui.platform.NoticeChannel {
        val lines = mutableListOf<String>()
        override fun show(text: String) { lines += text }
    }

    @Test fun the_updated_app_says_so_once_its_sessions_are_back() = kotlinx.coroutines.test.runTest {
        val updater = FakeAppUpdater().also { it.justUpdatedTo = "1.1.0" }
        val said = Said()
        val back = kotlinx.coroutines.flow.MutableStateFlow(false)
        val job = launch { updater.announceUpdate(said, back) }
        testScheduler.runCurrent()
        assertTrue(said.lines.isEmpty(), "not before the sessions are back")
        back.value = true
        job.join()
        assertEquals(listOf("Updated to v1.1.0 · all sessions reconnected"), said.lines)
        assertEquals(1, updater.acknowledged)
    }

    @Test fun sessions_that_stay_away_get_the_update_named_without_the_claim() = kotlinx.coroutines.test.runTest {
        val updater = FakeAppUpdater().also { it.justUpdatedTo = "1.1.0" }
        val said = Said()
        updater.announceUpdate(said, kotlinx.coroutines.flow.MutableStateFlow(false))
        assertEquals(listOf("Updated to v1.1.0"), said.lines)
        assertEquals(1, updater.acknowledged)
    }

    @Test fun a_launch_that_was_not_an_update_says_nothing() = kotlinx.coroutines.test.runTest {
        val updater = FakeAppUpdater()
        val said = Said()
        updater.announceUpdate(said, kotlinx.coroutines.flow.MutableStateFlow(true))
        assertTrue(said.lines.isEmpty())
        assertEquals(0, updater.acknowledged)
    }
}
