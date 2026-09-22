package dev.supermux.ui.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.ui.platform.UpdateStatus
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared startup strip (cluster G5) — desktop's `AppUpdateBanner` and Android's, over one
 * [dev.supermux.ui.platform.AppUpdater].
 *
 * It sits above the whole app on both hosts (desktop's `AppShell` column, Android's `MainActivity`
 * column), so the cases below are about the ONE thing a strip must get right: it appears only when
 * there is genuinely something to install, and it can always be got rid of.
 */
@OptIn(ExperimentalTestApi::class)
class AppUpdateBannerTest {

    private fun release(
        latest: String? = "1.1.0",
        available: Boolean = true,
        download: String? = "https://example.test/supermux-1.1.0.apk",
    ) = ClientUpdateStatus(
        currentVersion = "1.0.0",
        latestVersion = latest,
        updateAvailable = available,
        notesUrl = "https://example.test/notes",
        downloadUrl = download,
        canInstall = download != null,
    )

    private fun platform(updater: FakeAppUpdater) =
        FakePlatform(caps = NO_CAPS.copy(appUpdate = true)).also { it.updates = updater }

    // ── when it shows at all ──────────────────────────────────────────────────────────────────

    @Test fun nothing_is_drawn_while_the_first_check_is_still_running() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val updater = FakeAppUpdater(release = release(), midCheck = gate)
        setPlatformContent(platform(updater)) { AppUpdateBanner() }
        waitForIdle()
        onNodeWithTag("app_update_banner").assertDoesNotExist()
        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("app_update_banner").assertIsDisplayed()
    }

    @Test fun being_up_to_date_draws_nothing() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(available = false))
        setPlatformContent(platform(updater)) { AppUpdateBanner() }
        waitForIdle()
        onNodeWithTag("app_update_banner").assertDoesNotExist()
    }

    @Test fun a_failed_check_draws_nothing() = runComposeUiTest {
        val updater = FakeAppUpdater(release = null)
        setPlatformContent(platform(updater)) { AppUpdateBanner() }
        waitForIdle()
        onNodeWithTag("app_update_banner").assertDoesNotExist()
    }

    @Test fun an_available_release_names_its_version() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { AppUpdateBanner() }
        waitForIdle()
        onNodeWithText("Update available: 1.1.0").assertIsDisplayed()
    }

    @Test fun a_release_with_no_installer_url_still_shows_but_offers_no_button() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(download = null))
        setPlatformContent(platform(updater)) { AppUpdateBanner() }
        waitForIdle()
        onNodeWithTag("app_update_banner").assertIsDisplayed()
        onNodeWithTag("app_update_banner_update").assertDoesNotExist()
    }

    // ── dismissal ─────────────────────────────────────────────────────────────────────────────

    @Test fun dismissing_hides_it_and_remembers_the_version() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { AppUpdateBanner() }
        waitForIdle()
        onNodeWithTag("app_update_banner_dismiss").performClick()
        waitForIdle()
        onNodeWithTag("app_update_banner").assertDoesNotExist()
        assertEquals(listOf("1.1.0"), updater.dismissedVersions)
        assertTrue(updater.status.value.dismissed)
    }

    @Test fun a_version_dismissed_on_an_earlier_run_never_reappears() = runComposeUiTest {
        // The host stores the dismissal, so the very first check comes back already dismissed.
        val updater = FakeAppUpdater(release = release())
        updater.dismissedVersions += "1.1.0"
        setPlatformContent(platform(updater)) { AppUpdateBanner() }
        waitForIdle()
        onNodeWithTag("app_update_banner").assertDoesNotExist()
    }

    // ── the strip's two actions ───────────────────────────────────────────────────────────────

    @Test fun tapping_the_strip_opens_the_page() = runComposeUiTest {
        var opens = 0
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { AppUpdateBanner(onOpenPage = { opens++ }) }
        waitForIdle()
        onNodeWithTag("app_update_banner").performClick()
        assertEquals(1, opens)
    }

    @Test fun update_downloads_and_installs_without_leaving_the_strip() = runComposeUiTest {
        var opens = 0
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { AppUpdateBanner(onOpenPage = { opens++ }) }
        waitForIdle()
        onNodeWithTag("app_update_banner_update").performClick()
        waitForIdle()
        assertEquals(1, updater.installed.size)
        assertEquals(0, opens)
    }

    @Test fun the_button_shows_progress_and_is_disabled_while_downloading() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val updater = FakeAppUpdater(release = release(), midDownload = gate)
        setPlatformContent(platform(updater)) { AppUpdateBanner() }
        waitForIdle()
        onNodeWithTag("app_update_banner_update").assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithTag("app_update_banner_update").assertIsNotEnabled()
            .assertTextEquals("Downloading 50%…")
        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("app_update_banner_update").assertIsEnabled().assertTextEquals("Update")
    }

    @Test fun a_refused_install_jumps_to_the_permission_settings() = runComposeUiTest {
        var opens = 0
        val updater = FakeAppUpdater(release = release(), refuseInstall = true)
        setPlatformContent(platform(updater)) { AppUpdateBanner(onOpenPage = { opens++ }) }
        waitForIdle()
        onNodeWithTag("app_update_banner_update").performClick()
        waitForIdle()
        assertEquals(1, updater.permissionSettingsOpened)
        assertEquals(0, opens)
    }

    @Test fun a_failed_download_opens_the_page_where_the_error_is_readable() = runComposeUiTest {
        var opens = 0
        val updater = FakeAppUpdater(release = release(), downloadError = "connection reset")
        setPlatformContent(platform(updater)) { AppUpdateBanner(onOpenPage = { opens++ }) }
        waitForIdle()
        onNodeWithTag("app_update_banner_update").performClick()
        waitForIdle()
        assertEquals(1, opens)
        assertTrue(updater.installed.isEmpty())
    }

    // ── both widths ───────────────────────────────────────────────────────────────────────────

    @Test fun the_strip_is_the_same_at_compact_and_expanded() = runComposeUiTest {
        // BOTH widths in one test: the strip has no width branch, and the name only means
        // something if the Expanded render is actually asserted too.
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Compact) {
            Column(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                    AppUpdateBanner()
                }
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Expanded) {
                    AppUpdateBanner()
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag("app_update_banner").assertCountEquals(2)
        onAllNodesWithTag("app_update_banner_update").assertCountEquals(2)
        onAllNodesWithTag("app_update_banner_dismiss").assertCountEquals(2)
    }

    @Test fun one_seam_means_the_banner_and_the_page_agree() = runComposeUiTest {
        // Both mount the SAME updater (the banner sits above the shell that hosts the page), so the
        // page's check is the banner's check — one poll, one answer, and a dismissal settles both.
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Expanded) {
            Column(Modifier.fillMaxSize()) {
                AppUpdateBanner()
                AppUpdateScreen(standalone = true)
            }
        }
        waitForIdle()
        onNodeWithTag("app_update_banner").assertIsDisplayed()
        onNodeWithTag("app_update_message").assertTextEquals("Update available: 1.1.0")
        onNodeWithTag("app_update_banner_dismiss").performClick()
        waitForIdle()
        onNodeWithTag("app_update_banner").assertDoesNotExist()
        // Dismissal is about the STRIP; the page still offers the update.
        onNodeWithTag("app_update_install").assertIsDisplayed()
    }

    // ── the host: strip + app, one status-bar inset between them ─────────────────────────────

    @Test fun showsBanner_is_a_newer_undismissed_release_and_nothing_else() {
        assertFalse(UpdateStatus().showsBanner)
        assertFalse(UpdateStatus(release = release(available = false)).showsBanner)
        assertTrue(UpdateStatus(release = release()).showsBanner)
        assertFalse(UpdateStatus(release = release(), dismissed = true).showsBanner)
    }

    @Test fun the_host_lays_the_app_under_the_strip_and_hides_the_strip_when_dismissed() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) {
            AppUpdateBannerHost(onOpenPage = {}, updater = updater) {
                Box(Modifier.fillMaxWidth().height(40.dp).testTag("app_body"))
            }
        }
        onNodeWithTag("app_update_banner").assertIsDisplayed()
        onNodeWithTag("app_body").assertIsDisplayed()
        onNodeWithTag("app_update_banner_dismiss").performClick()
        onNodeWithTag("app_update_banner").assertDoesNotExist()
        onNodeWithTag("app_body").assertIsDisplayed()
    }
}
