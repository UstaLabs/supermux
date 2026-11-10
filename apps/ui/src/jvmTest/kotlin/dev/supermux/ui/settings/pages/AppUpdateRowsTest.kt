package dev.supermux.ui.settings.pages

import dev.supermux.ui.platform.VersionOnlyUpdater

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakeAppUpdater
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.update.formatUpdateBytes
import dev.supermux.ui.update.formatUpdateProgress
import dev.supermux.ui.update.installCaption
import dev.supermux.ui.update.installerKindFrom
import dev.supermux.update.ClientUpdateStatus
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * General ▸ This app ▸ App version (#97 spec §11): the app updater inline — check, download with its progress in
 * words, install, the Android install-permission step, release notes as a text action — restyled from the deleted
 * update page. Every case drives the seam's PHASES (the fake is the reference implementation
 * `AppUpdaterContractTest` pins both hosts against) and asserts what the row says and offers for them.
 */
@OptIn(ExperimentalTestApi::class)
class AppUpdateRowsTest {

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

    // ── phases → what the row says ────────────────────────────────────────────────────────────

    @Test fun the_first_check_says_checking_until_it_has_something_to_show() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val updater = FakeAppUpdater(release = release(), midCheck = gate)
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 · Checking…")
        onNodeWithTag("app_update_install").assertDoesNotExist()
        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 · 1.1.0 is ready")
    }

    @Test fun a_check_that_produced_no_status_at_all_says_so_with_try_again() = runComposeUiTest {
        val updater = FakeAppUpdater(release = null)
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_message").assertTextEquals("Couldn't check for updates.")
        onNodeWithTag("app_update_install").assertDoesNotExist()
        // Said once, not again as a second error line.
        onNodeWithTag("app_update_error").assertDoesNotExist()
        updater.release = release(available = false)
        onNodeWithTag("app_update_retry").performClick()
        waitForIdle()
        assertEquals(2, updater.checks)
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 · up to date")
    }

    @Test fun a_status_carrying_its_own_error_shows_that_error() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(latest = null, available = false, lastError = "feed offline"))
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_message").assertTextEquals("feed offline")
        onNodeWithTag("app_update_retry").assertExists()
    }

    @Test fun being_current_says_up_to_date_and_offers_a_check() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(available = false))
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 · up to date")
        onNodeWithTag("app_update_install").assertDoesNotExist()
        onNodeWithTag("app_update_notes").assertDoesNotExist()
        onNodeWithTag("general_check_updates").assertIsDisplayed()
    }

    @Test fun check_for_updates_polls_the_seam_again() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(available = false))
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        assertEquals(1, updater.checks)
        onNodeWithTag("general_check_updates").performClick()
        waitForIdle()
        assertEquals(2, updater.checks)
    }

    @Test fun a_page_opened_after_the_shells_check_does_not_check_again() = runComposeUiTest {
        // The shell checks once per launch; General is the default page, and opening it is no reason to poll.
        val updater = FakeAppUpdater(release = release(available = false))
        kotlinx.coroutines.runBlocking { updater.check() }
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        assertEquals(1, updater.checks)
    }

    @Test fun an_available_release_offers_notes_and_the_download() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 · 1.1.0 is ready")
        onNodeWithText("Download").assertExists()

        onNodeWithTag("app_update_notes").performClick()
        waitForIdle()
        assertEquals(1, updater.notesOpened)

        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        assertEquals(1, updater.installed.size)
    }

    @Test fun an_available_release_with_no_installer_url_says_none_was_published() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(download = null))
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_install").assertDoesNotExist()
        onNodeWithText("Update is available but no installer file was published for this release.").assertIsDisplayed()
    }

    @Test fun the_page_lists_every_highlight() = runComposeUiTest {
        val notes = listOf("Agent lanes", "Windows sessions start", "Faster sidebar")
        val updater = FakeAppUpdater(release = release().copy(highlights = notes))
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_whats_new").assertIsDisplayed()
        notes.forEach { onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun the_download_shows_its_progress_in_words() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val updater = FakeAppUpdater(release = release(), midDownload = gate)
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        // The seam's first tick is 50/100. Busy is a word, not a spinner, and there is nothing to press meanwhile.
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 · Downloading 50%…")
        onNodeWithTag("app_update_install").assertDoesNotExist()
        onNodeWithTag("general_check_updates").assertDoesNotExist()
        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag("app_update_install").assertExists()
        assertEquals(1, updater.installed.size)
    }

    @Test fun a_failed_download_shows_its_error_and_try_again_downloads_again() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(), downloadError = "connection reset")
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        onNodeWithTag("app_update_error").assertTextEquals("connection reset")
        assertTrue(updater.installed.isEmpty())
        updater.downloadError = null
        onNodeWithTag("app_update_retry").performClick()
        waitForIdle()
        assertEquals(1, updater.installed.size)
    }

    // ── a download already waiting (#113) ─────────────────────────────────────────────────────

    @Test fun a_release_downloaded_in_the_background_offers_install_and_restart() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release()).also { it.restartsToUpdate = true }
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        updater.becomeReady()
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 · 1.1.0 is downloaded")
        onNodeWithText("Install and restart").assertExists()
        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        assertEquals(1, updater.installed.size)
        assertEquals(0, updater.downloadCalls, "no second download")
    }

    // ── the unknown-sources gate (Android only; status-driven) ────────────────────────────────

    @Test fun a_refused_install_jumps_to_settings_and_leaves_a_row_to_try_again() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(), refuseInstall = true)
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_permission").assertDoesNotExist()

        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        // The refusal jumps straight to the system page…
        assertEquals(1, updater.permissionSettingsOpened)
        // …and leaves a row for the user who came back without granting it.
        onNodeWithText("Allow installs from supermux").assertIsDisplayed()
        onNodeWithTag("app_update_permission_action").performClick()
        waitForIdle()
        assertEquals(2, updater.permissionSettingsOpened)
        // The row IS the error text; it must not also be printed a second time.
        onNodeWithTag("app_update_error").assertDoesNotExist()
    }

    @Test fun a_platform_without_the_gate_never_shows_the_permission_row() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(), downloadError = "boom")
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_install").performClick()
        waitForIdle()
        onNodeWithTag("app_update_permission").assertDoesNotExist()
        assertEquals(0, updater.permissionSettingsOpened)
    }

    // ── version ───────────────────────────────────────────────────────────────────────────────

    @Test fun the_version_comes_from_the_check_and_falls_back_to_the_updater() = runComposeUiTest {
        val updater = FakeAppUpdater(currentVersion = "9.9.9", release = null)
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 9.9.9")
    }

    @Test fun a_build_number_is_printed_only_where_the_platform_has_one() = runComposeUiTest {
        val withCode = FakeAppUpdater(currentVersionCode = 42, release = release(currentCode = 42, available = false))
        setPlatformContent(platform(withCode)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 (build 42) · up to date")
    }

    @Test fun desktop_prints_no_build_number() = runComposeUiTest {
        // `currentVersionCode == null` is desktop's contract — packages carry no build counter.
        val updater = FakeAppUpdater(currentVersionCode = null, release = release(available = false))
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0 · up to date")
        assertNull(updater.currentVersionCode)
    }

    @Test fun without_the_updater_only_the_version_shows() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release())
        setPlatformContent(FakePlatform(caps = NO_CAPS).also { it.updates = updater }) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.0.0")
        onNodeWithTag("general_check_updates").assertDoesNotExist()
        onNodeWithTag("app_update_install").assertDoesNotExist()
        assertEquals(0, updater.checks, "a device that does not update itself never polls")
    }

    // #113: "Download updates in the background", where the host offers it (desktop); on unless turned off.
    @Test fun the_background_switch_shows_where_offered_and_turns_background_updates_off() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(available = false)).also { it.offersBackgroundUpdates = true; it.setBackgroundUpdates(true) }
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithText("Download updates in the background").assertIsDisplayed()
        onNodeWithTag("app_update_background").performClick()
        waitForIdle()
        assertEquals(false, updater.status.value.background)
        onNodeWithTag("app_update_background").assertIsOff()
    }

    @Test fun no_background_switch_where_the_host_does_not_offer_it() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release(available = false))
        setPlatformContent(platform(updater)) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_background").assertDoesNotExist()
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
        assertEquals(
            "One-tap installs the latest release APK over this build. " +
                "Progress also appears in the notification bar.",
            installCaption("apk"),
        )
        assertEquals("Downloads the latest .deb and opens it.", installCaption("deb"))
        assertEquals("Downloads the latest installer and opens it.", installCaption(null))
    }

    /** iOS and the web app do not update themselves, but still know their version (#97, Unit E). */
    @Test fun a_build_that_does_not_update_itself_still_shows_its_version() = runComposeUiTest {
        setPlatformContent(FakePlatform(caps = NO_CAPS).also { it.updates = VersionOnlyUpdater("1.4.0", 41) }) { AppUpdateRows() }
        waitForIdle()
        onNodeWithTag("app_update_version").assertTextEquals("supermux 1.4.0 (build 41)")
    }
}
