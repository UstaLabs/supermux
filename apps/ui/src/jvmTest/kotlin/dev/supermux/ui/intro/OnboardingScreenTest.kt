package dev.supermux.ui.intro

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.pairing.InMemoryPairingTokenStore
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ONE first-connect pairing screen (cluster G6): desktop's `OnboardingScreen` unioned with
 * Android's third (Scan) mode.
 *
 * The cases below are the union's seams — the mode row's shape follows `Caps.camera`, the scan
 * button goes through `Platform.scanQr()`, and every path lands on the same TOFU dialog before a
 * single byte is persisted.
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingScreenTest {

    private fun cameraPlatform(qr: String? = null) =
        FakePlatform(caps = NO_CAPS.copy(camera = true), qrResult = qr)

    // ── the mode row follows the camera cap ───────────────────────────────────────────────────

    @Test fun a_machine_with_no_camera_offers_paste_and_manual_only() = runComposeUiTest {
        setPlatformContent(FakePlatform(), widthClass = WindowWidthClass.Expanded) {
            OnboardingScreen(pairing = testPairing(), onPaired = {})
        }
        waitForIdle()
        onNodeWithTag("onboarding_mode_paste").assertIsDisplayed()
        onNodeWithTag("onboarding_mode_manual").assertIsDisplayed()
        onNodeWithTag("onboarding_mode_scan").assertDoesNotExist()
        onNodeWithTag("onboarding_paste_field").assertIsDisplayed()
    }

    @Test fun a_machine_with_a_camera_offers_scan_too() = runComposeUiTest {
        setPlatformContent(cameraPlatform(), pointer = false, widthClass = WindowWidthClass.Compact) {
            OnboardingScreen(pairing = testPairing(), onPaired = {})
        }
        waitForIdle()
        onNodeWithTag("onboarding_mode_scan").assertIsDisplayed()
        onNodeWithTag("onboarding_mode_paste").assertIsDisplayed()
        onNodeWithTag("onboarding_mode_manual").assertIsDisplayed()
    }

    @Test fun the_screen_renders_at_both_widths() = runComposeUiTest {
        setPlatformContent(FakePlatform(), widthClass = WindowWidthClass.Compact, pointer = false) {
            OnboardingScreen(pairing = testPairing(), onPaired = {})
        }
        waitForIdle()
        onNodeWithTag("onboarding_screen").assertIsDisplayed()
        onNodeWithTag("onboarding_hint").assertIsDisplayed()
        onNodeWithTag("onboarding_paste_submit").assertIsDisplayed()
    }

    // ── QR ────────────────────────────────────────────────────────────────────────────────────

    @Test fun the_scan_button_pairs_through_the_platform_seam() = runComposeUiTest {
        val platform = cameraPlatform(qr = "https://host:9898/pair?t=abc123")
        setPlatformContent(platform, pointer = false, widthClass = WindowWidthClass.Compact) {
            OnboardingScreen(pairing = testPairing(), onPaired = {})
        }
        waitForIdle()
        onNodeWithTag("onboarding_mode_scan").performClick()
        waitForIdle()
        onNodeWithTag("onboarding_scan").performClick()
        waitForIdle()
        assertEquals(1, platform.scans)
        // A decoded QR goes straight to the trust gate — never straight to storage.
        onNodeWithTag("pair_tofu_dialog").assertIsDisplayed()
    }

    // ── TOFU ──────────────────────────────────────────────────────────────────────────────────

    @Test fun a_pasted_link_validates_into_the_tofu_dialog_and_persists_only_on_confirm() = runComposeUiTest {
        val store = InMemoryPairingTokenStore()
        var paired = 0
        setPlatformContent(FakePlatform(), widthClass = WindowWidthClass.Expanded) {
            OnboardingScreen(pairing = testPairing(store), onPaired = { paired++ })
        }
        waitForIdle()
        onNodeWithTag("onboarding_paste_field").performTextInput("https://host:9898/pair?t=abc123")
        onNodeWithTag("onboarding_paste_submit").performClick()
        waitForIdle()
        onNodeWithTag("pair_tofu_dialog").assertIsDisplayed()
        // Nothing is stored while the dialog is up.
        assertEquals(null, store.load())
        onNodeWithTag("pair_tofu_confirm").performClick()
        waitForIdle()
        assertEquals("abc123", store.load())
        assertEquals("wss://host:9898", store.loadBaseUrl())
        assertEquals(1, paired)
    }

    @Test fun cancelling_the_tofu_dialog_stores_nothing_and_returns_to_the_form() = runComposeUiTest {
        val store = InMemoryPairingTokenStore()
        var paired = 0
        setPlatformContent(FakePlatform(), widthClass = WindowWidthClass.Expanded) {
            OnboardingScreen(pairing = testPairing(store), onPaired = { paired++ })
        }
        waitForIdle()
        onNodeWithTag("onboarding_paste_field").performTextInput("https://host:9898/pair?t=abc123")
        onNodeWithTag("onboarding_paste_submit").performClick()
        waitForIdle()
        onNodeWithTag("pair_tofu_cancel").performClick()
        waitForIdle()
        onNodeWithTag("pair_tofu_dialog").assertDoesNotExist()
        onNodeWithTag("onboarding_paste_field").assertIsDisplayed()
        assertEquals(null, store.load())
        assertEquals(0, paired)
    }

    @Test fun a_rejected_token_shows_the_error_instead_of_the_dialog() = runComposeUiTest {
        setPlatformContent(FakePlatform(), widthClass = WindowWidthClass.Expanded) {
            OnboardingScreen(pairing = testPairing(probe = { null }), onPaired = {})
        }
        waitForIdle()
        onNodeWithTag("onboarding_paste_field").performTextInput("https://host:9898/pair?t=abc123")
        onNodeWithTag("onboarding_paste_submit").performClick()
        waitForIdle()
        onNodeWithTag("pair_tofu_dialog").assertDoesNotExist()
        onNodeWithTag("onboarding_error").assertIsDisplayed()
    }

    // ── manual ────────────────────────────────────────────────────────────────────────────────

    @Test fun manual_entry_pairs_a_bare_token_against_the_typed_host() = runComposeUiTest {
        val store = InMemoryPairingTokenStore()
        setPlatformContent(FakePlatform(), widthClass = WindowWidthClass.Expanded) {
            OnboardingScreen(pairing = testPairing(store), onPaired = {})
        }
        waitForIdle()
        onNodeWithTag("onboarding_mode_manual").performClick()
        waitForIdle()
        onNodeWithTag("onboarding_manual_host").performTextInput("ws://10.0.2.2:9898")
        onNodeWithTag("onboarding_manual_token").performTextInput("tok-1")
        onNodeWithTag("onboarding_manual_submit").performClick()
        waitForIdle()
        onNodeWithTag("pair_tofu_confirm").performClick()
        waitForIdle()
        assertEquals("tok-1", store.load())
        assertEquals("ws://10.0.2.2:9898", store.loadBaseUrl())
    }

    // ── deep link ─────────────────────────────────────────────────────────────────────────────

    @Test fun a_cold_start_deep_link_goes_straight_to_the_trust_gate() = runComposeUiTest {
        setPlatformContent(FakePlatform(), widthClass = WindowWidthClass.Compact, pointer = false) {
            OnboardingScreen(
                pairing = testPairing(),
                onPaired = {},
                initialDeepLink = dev.supermux.net.PairUrl("wss://host:9898", "abc123"),
            )
        }
        waitForIdle()
        onNodeWithTag("pair_tofu_dialog").assertIsDisplayed()
    }
}
