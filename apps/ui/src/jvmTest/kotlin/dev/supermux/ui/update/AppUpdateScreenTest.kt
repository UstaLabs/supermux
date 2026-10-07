package dev.supermux.ui.update

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.update.ClientUpdateStatus
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakeAppUpdater
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared "Check for updates" screen (cluster G5): desktop's `AppUpdateUi` screen and Android's
 * `AppUpdatePage`, unioned over the G1 [dev.supermux.ui.platform.AppUpdater] seam.
 *
 * Every case drives the seam's PHASES (the fake is the same reference implementation
 * `AppUpdaterContractTest` pins both hosts against) and asserts what the page shows for them, plus
 * the cluster-E chrome gate `(standalone || compact) && !topBarShown` — desktop's full-pane route
 * passes `standalone = true`, which is the only reason a wide window still has a Back button.
 */
@OptIn(ExperimentalTestApi::class)
class AppUpdateScreenTest {

    private val caps = NO_CAPS.copy(appUpdate = true)

    private fun release(
        latest: String? = "1.1.0",
        available: Boolean = true,
        download: String? = "https://example.test/supermux-1.1.0.apk",
        notes: String? = "https://example.test/notes",
        currentCode: Int? = null,
        lastError: String? = null,
    ) = ClientUpdateStatus(
        currentVersion = "1.0.0",
        currentVersionCode = currentCode,
        latestVersion = latest,
        updateAvailable = available,
        notesUrl = notes,
        downloadUrl = download,
        canInstall = download != null,
        lastError = lastError,
    )

    private fun platform(updater: FakeAppUpdater) =
        FakePlatform(caps = caps).also { it.updates = updater }

    // ── phases → what the page shows ──────────────────────────────────────────────────────────

    @Test fun the_first_check_spins_until_it_has_something_to_show() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val updater = FakeAppUpdater(release = release(), midCheck = gate)
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        // Checking with no release yet: the body is not drawn at all.
        onNodeWithTag("app_update_screen").assertDoesNotExist()
        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("app_update_screen").assertIsDisplayed()
        onNodeWithTag("app_update_message").assertTextEquals("Update available: 1.1.0")
    }

    @Test fun a_check_that_produced_no_status_at_all_says_so() = runComposeUiTest {
        // `release = null` makes the fake's check FAIL — desktop's "Couldn't check" case, which is
        // deliberately distinct from a status that came back carrying its own `lastError`.
        val updater = FakeAppUpdater(release = null)
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_message").assertTextEquals("Couldn't check for updates.")
        onNodeWithTag("app_update_install").assertDoesNotExist()
    }

    @Test fun a_status_carrying_its_own_error_shows_that_error() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(latest = null, available = false, lastError = "feed offline"))
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_message").assertTextEquals("feed offline")
    }

    @Test fun being_current_says_up_to_date_and_offers_no_cta() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(available = false))
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_message").assertTextEquals("You're up to date")
        onNodeWithTag("app_update_install").assertDoesNotExist()
        onNodeWithTag("app_update_notes").assertDoesNotExist()
    }

    @Test fun an_available_release_offers_notes_and_the_install_cta() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_message").assertTextEquals("Update available: 1.1.0")

        onNodeWithTag("app_update_notes").performClick()
        waitForIdle()
        assertEquals(1, updater.notesOpened)

        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        assertEquals(1, updater.installed.size)
    }

    @Test fun an_available_release_with_no_installer_url_says_none_was_published() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(download = null))
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_install").assertDoesNotExist()
        onNodeWithText("Update is available but no installer file was published for this release.")
            .assertIsDisplayed()
    }

    @Test fun the_cta_shows_live_progress_and_is_disabled_while_downloading() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val updater = FakeAppUpdater(release = release(), midDownload = gate)
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_install").assertIsEnabled().performClick()
        waitForIdle()
        // The seam's first tick is 50/100 — Android's live label, which desktop never had.
        onNodeWithTag("app_update_install").assertIsNotEnabled().assertTextContains("Downloading 50%…")
        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("app_update_install").assertIsEnabled()
        assertEquals(1, updater.installed.size)
    }

    @Test fun a_failed_download_shows_its_error_and_re_enables_the_cta() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(), downloadError = "connection reset")
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        onNodeWithTag("app_update_error").assertTextEquals("connection reset")
        onNodeWithTag("app_update_install").assertIsEnabled()
        assertTrue(updater.installed.isEmpty())
    }

    // ── the unknown-sources gate (Android only; status-driven, so no host check here) ──────────

    @Test fun a_refused_install_jumps_to_settings_and_leaves_a_row_to_try_again() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(), refuseInstall = true)
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_permission").assertDoesNotExist()

        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        // Android's behaviour, kept verbatim: the refusal jumps straight to the settings page…
        assertEquals(1, updater.permissionSettingsOpened)
        // …and, new here, leaves a row for the user who came back without granting it.
        onNodeWithTag("app_update_permission").assertIsDisplayed()
        onNodeWithTag("app_update_permission_action").performClick()
        waitForIdle()
        assertEquals(2, updater.permissionSettingsOpened)
        // The row IS the error text; it must not also be printed a second time below.
        onNodeWithTag("app_update_error").assertDoesNotExist()
    }

    @Test fun a_platform_without_the_gate_never_shows_the_permission_row() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(), downloadError = "boom")
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        onNodeWithTag("app_update_permission").assertDoesNotExist()
        assertEquals(0, updater.permissionSettingsOpened)
    }

    // ── version block ─────────────────────────────────────────────────────────────────────────

    @Test fun the_version_line_comes_from_the_check_and_falls_back_to_the_updater() = runComposeUiTest {
        val updater = FakeAppUpdater(currentVersion = "9.9.9", release = null)
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 9.9.9")
    }

    @Test fun a_version_code_is_printed_only_where_the_platform_has_one() = runComposeUiTest {
        val withCode = FakeAppUpdater(currentVersionCode = 42, release = release(currentCode = 42))
        setPlatformContent(platform(withCode)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithText("versionCode 42").assertIsDisplayed()
    }

    @Test fun desktop_prints_no_version_code() = runComposeUiTest {
        // `currentVersionCode == null` is desktop's contract — packages carry no build counter.
        val updater = FakeAppUpdater(currentVersionCode = null, release = release())
        setPlatformContent(platform(updater)) { AppUpdateScreen() }
        waitForIdle()
        onNodeWithText("versionCode 0").assertDoesNotExist()
        assertNull(updater.currentVersionCode)
    }

    // ── chrome: (standalone || compact) && !topBarShown ────────────────────────────────────────

    @Test fun compact_paints_its_own_bar_with_back_and_recheck() = runComposeUiTest {
        var backs = 0
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Compact) {
            AppUpdateScreen(onBack = { backs++ })
        }
        waitForIdle()
        onNodeWithText("Check for updates").assertIsDisplayed()
        onNodeWithTag("app_update_recheck").assertIsDisplayed()
        onNodeWithTag("app_update_back").performClick()
        assertEquals(1, backs)
    }

    @Test fun a_hub_detail_that_already_has_a_bar_paints_none() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Compact) {
            AppUpdateScreen(topBarShown = true)
        }
        waitForIdle()
        onNodeWithTag("app_update_back").assertDoesNotExist()
        // Recheck still has to be reachable, so it rides in the body instead.
        onNodeWithTag("app_update_recheck").assertIsDisplayed()
    }

    @Test fun expanded_as_a_hub_detail_paints_no_bar_either() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Expanded) {
            AppUpdateScreen()
        }
        waitForIdle()
        onNodeWithTag("app_update_back").assertDoesNotExist()
        onNodeWithTag("app_update_recheck").assertIsDisplayed()
    }

    @Test fun standalone_paints_its_own_bar_at_every_width() = runComposeUiTest {
        // Desktop's full-pane `Route.AppUpdate` overlay: without this the wide window would have no
        // Back button at all (the route is not a hub detail and paints nothing above the page).
        var backs = 0
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Expanded) {
            AppUpdateScreen(onBack = { backs++ }, standalone = true)
        }
        waitForIdle()
        onNodeWithTag("app_update_back").performClick()
        assertEquals(1, backs)
    }

    @Test fun recheck_polls_the_seam_again() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Compact) {
            AppUpdateScreen()
        }
        waitForIdle()
        assertEquals(1, updater.checks)
        onNodeWithTag("app_update_recheck").performClick()
        waitForIdle()
        assertEquals(2, updater.checks)
    }

    @Test fun recheck_is_disabled_while_a_download_owns_the_phase() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val updater = FakeAppUpdater(release = release(), midDownload = gate)
        setPlatformContent(platform(updater), widthClass = WindowWidthClass.Compact) {
            AppUpdateScreen()
        }
        waitForIdle()
        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        onNodeWithTag("app_update_recheck").assertIsNotEnabled()
        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("app_update_recheck").assertIsEnabled()
    }

    // ── pure labels ───────────────────────────────────────────────────────────────────────────

    @Test fun the_progress_label_prefers_a_percentage() {
        assertEquals("Downloading…", formatUpdateProgress(0, null))
        assertEquals("Downloading 1.0 KB…", formatUpdateProgress(1024, null))
        assertEquals("Downloading 42%…", formatUpdateProgress(42, 100))
        assertEquals("Downloading 100%…", formatUpdateProgress(120, 100))
    }

    @Test fun bytes_scale_the_way_the_status_bar_has_always_shown_them() {
        assertEquals("512 B", formatUpdateBytes(512))
        assertEquals("1.5 KB", formatUpdateBytes(1536))
        assertEquals("2.0 MB", formatUpdateBytes(2L * 1024 * 1024))
    }

    @Test fun the_installer_kind_comes_off_the_download_url() {
        assertEquals("deb", installerKindFrom("https://example.test/supermux_1.1.0_amd64.deb"))
        assertEquals("msi", installerKindFrom("https://example.test/supermux-1.1.0.MSI"))
        assertEquals("dmg", installerKindFrom("https://example.test/a/b/supermux.dmg?token=1"))
        assertEquals("apk", installerKindFrom("https://example.test/supermux.apk#frag"))
        assertNull(installerKindFrom("https://example.test/latest"))
        assertNull(installerKindFrom(null))
    }

    @Test fun the_install_caption_names_the_file_it_is_about_to_open() {
        // Only the APK kind gets Android's notification-bar sentence; every other kind names its
        // own extension, and an unrecognised URL falls back to the generic line — so no desktop
        // user is ever told about an APK.
        assertEquals(
            "One-tap installs the latest release APK over this build. " +
                "Progress also appears in the notification bar.",
            installCaption("apk"),
        )
        assertEquals("Downloads the latest .deb and opens it.", installCaption("deb"))
        assertEquals("Downloads the latest .msi and opens it.", installCaption("msi"))
        assertEquals("Downloads the latest .dmg and opens it.", installCaption("dmg"))
        assertEquals("Downloads the latest .appimage and opens it.", installCaption("appimage"))
        assertEquals("Downloads the latest installer and opens it.", installCaption(null))
    }
}
