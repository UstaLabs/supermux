package dev.supermux.ui.intro

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.pairing.InMemoryPairingTokenStore
import dev.supermux.state.SettingsKeys
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.prefs.UiPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ONE first-run intro (cluster G6) — Android's four-page flow as the base, desktop's "mux
 * boot" cinematic folded in as the Expanded opening act.
 *
 * The cases below pin the two branch keys the fold rests on (width class = which variant plays,
 * pointer = hit targets), the seen flag it writes, and the parts that used to be Android-only
 * platform code: the QR hero now goes through `Platform.scanQr()` and is gated on `Caps.camera`.
 */
@OptIn(ExperimentalTestApi::class)
class IntroTest {

    private fun cameraPlatform(qr: String? = null) =
        FakePlatform(caps = NO_CAPS.copy(camera = true), qrResult = qr)

    /**
     * The intro runs infinite animations by design (the drifting clouds, the ticker's blinking
     * cursor, the cinematic's block cursor), so the auto-advancing clock would never report the
     * composition idle. Every case below drives the clock by hand instead: [settle] advances far
     * enough for the page transition / reveal it just triggered to land.
     */
    private fun ComposeUiTest.settle(ms: Long = 2_000) {
        mainClock.advanceTimeBy(ms)
        waitForIdle()
    }

    // ── the pages, under Compact ──────────────────────────────────────────────────────────────

    @Test fun compact_opens_on_the_hook_page_with_no_cinematic() = runComposeUiTest {
        mainClock.autoAdvance = false
        setPlatformContent(cameraPlatform(), pointer = false, widthClass = WindowWidthClass.Compact) {
            OnboardingFlow(pairing = testPairing(), onPaired = {})
        }
        settle()
        onNodeWithTag("first_run_intro").assertDoesNotExist()
        onNodeWithTag("intro_page_hook").assertIsDisplayed()
        onNodeWithTag("intro_next").assertIsDisplayed()
        onNodeWithTag("intro_day_rail").assertIsDisplayed()
    }

    @Test fun the_next_button_advances_a_page_at_a_time() = runComposeUiTest {
        mainClock.autoAdvance = false
        setPlatformContent(cameraPlatform(), pointer = false, widthClass = WindowWidthClass.Compact) {
            OnboardingFlow(pairing = testPairing(), onPaired = {})
        }
        settle()
        onNodeWithTag("intro_next").performClick()
        settle()
        onNodeWithTag("intro_page_agents").assertIsDisplayed()
        onNodeWithTag("intro_next").performClick()
        settle()
        onNodeWithTag("intro_page_always_on").assertIsDisplayed()
        onNodeWithTag("intro_next").performClick()
        settle()
        // The last page owns the pairing form, so the carousel CTA is gone.
        onNodeWithTag("intro_page_connect").assertIsDisplayed()
        onNodeWithTag("intro_next").assertDoesNotExist()
    }

    @Test fun the_day_rail_skips_straight_to_the_connect_page() = runComposeUiTest {
        mainClock.autoAdvance = false
        setPlatformContent(cameraPlatform(), pointer = false, widthClass = WindowWidthClass.Compact) {
            OnboardingFlow(pairing = testPairing(), onPaired = {})
        }
        settle()
        onNodeWithTag("intro_day_rail_3").performClick()
        settle()
        onNodeWithTag("intro_page_connect").assertIsDisplayed()
    }

    // ── the seen flag ─────────────────────────────────────────────────────────────────────────

    @Test fun the_seen_flag_is_written_once_when_the_reader_reaches_the_end() = runComposeUiTest {
        mainClock.autoAdvance = false
        val store = CountingSettingsStore()
        setPlatformContent(
            cameraPlatform(),
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            uiPrefs = UiPrefs(store),
        ) {
            OnboardingFlow(pairing = testPairing(), onPaired = {})
        }
        settle()
        // Nothing written while the reader is still in the carousel.
        assertTrue(store.writes.none { it.first == SettingsKeys.INTRO_SEEN })
        onNodeWithTag("intro_day_rail_3").performClick()
        settle()
        assertEquals(
            listOf(SettingsKeys.INTRO_SEEN to INTRO_VERSION.toString()),
            store.writes.filter { it.first == SettingsKeys.INTRO_SEEN },
        )
        // Staying on the page (a recomposition per animation frame) must not write it again.
        onNodeWithTag("intro_day_rail_3").performClick()
        settle()
        assertEquals(1, store.writes.count { it.first == SettingsKeys.INTRO_SEEN })
    }

    // ── the cinematic, under Expanded ─────────────────────────────────────────────────────────

    @Test fun expanded_plays_the_cinematic_over_the_pager_first() = runComposeUiTest {
        mainClock.autoAdvance = false
        setPlatformContent(cameraPlatform(), widthClass = WindowWidthClass.Expanded) {
            OnboardingFlow(pairing = testPairing(), onPaired = {}, freezeAt = 0.5f)
        }
        settle()
        onNodeWithTag("first_run_intro").assertIsDisplayed()
        // The pager is already composed beneath it, so the hand-off is a reveal, not a cut.
        onNodeWithTag("intro_pager").assertExists()
    }

    @Test fun a_click_skips_the_cinematic_and_hands_off_to_the_pager() = runComposeUiTest {
        mainClock.autoAdvance = false
        setPlatformContent(cameraPlatform(), widthClass = WindowWidthClass.Expanded) {
            OnboardingFlow(pairing = testPairing(), onPaired = {}, freezeAt = 0.5f)
        }
        settle()
        onNodeWithTag("first_run_intro").performClick()
        settle()
        onNodeWithTag("first_run_intro").assertDoesNotExist()
        onNodeWithTag("intro_page_hook").assertIsDisplayed()
    }

    @Test fun a_deep_link_skips_the_cinematic_and_the_carousel_entirely() = runComposeUiTest {
        mainClock.autoAdvance = false
        setPlatformContent(cameraPlatform(), widthClass = WindowWidthClass.Expanded) {
            OnboardingFlow(
                pairing = testPairing(),
                onPaired = {},
                initialDeepLink = dev.supermux.net.PairUrl("wss://host:9898", "abc123"),
            )
        }
        settle()
        onNodeWithTag("first_run_intro").assertDoesNotExist()
        onNodeWithTag("pair_tofu_dialog").assertIsDisplayed()
    }

    @Test fun the_overlay_alone_finishes_on_a_click() = runComposeUiTest {
        mainClock.autoAdvance = false
        var finished = 0
        setPlatformContent(FakePlatform(), widthClass = WindowWidthClass.Expanded) {
            FirstRunIntroOverlay(onFinished = { finished++ }, freezeAt = 0.9f)
        }
        settle()
        onNodeWithTag("first_run_intro").assertIsDisplayed()
        onNodeWithTag("first_run_intro").performClick()
        settle()
        assertEquals(1, finished)
    }

    // ── the connect page's platform seams ─────────────────────────────────────────────────────

    @Test fun the_connect_page_hides_the_qr_hero_where_there_is_no_camera() = runComposeUiTest {
        mainClock.autoAdvance = false
        setPlatformContent(FakePlatform(), widthClass = WindowWidthClass.Compact, pointer = false) {
            OnboardingFlow(pairing = testPairing(), onPaired = {}, showCinematic = false)
        }
        settle()
        onNodeWithTag("intro_day_rail_3").performClick()
        settle()
        onNodeWithTag("intro_scan").assertDoesNotExist()
        onNodeWithTag("intro_paste_field").assertIsDisplayed()
    }

    @Test fun the_connect_page_scans_through_the_platform_seam() = runComposeUiTest {
        mainClock.autoAdvance = false
        val platform = cameraPlatform(qr = "https://host:9898/pair?t=abc123")
        setPlatformContent(platform, pointer = false, widthClass = WindowWidthClass.Compact) {
            OnboardingFlow(pairing = testPairing(), onPaired = {})
        }
        settle()
        onNodeWithTag("intro_day_rail_3").performClick()
        settle()
        onNodeWithTag("intro_scan").performClick()
        settle()
        assertEquals(1, platform.scans)
        onNodeWithTag("pair_tofu_dialog").assertIsDisplayed()
    }

    @Test fun the_connect_page_pairs_a_pasted_link_and_persists_only_on_confirm() = runComposeUiTest {
        mainClock.autoAdvance = false
        val store = InMemoryPairingTokenStore()
        var paired = 0
        setPlatformContent(cameraPlatform(), pointer = false, widthClass = WindowWidthClass.Compact) {
            OnboardingFlow(pairing = testPairing(store), onPaired = { paired++ })
        }
        settle()
        onNodeWithTag("intro_day_rail_3").performClick()
        settle()
        onNodeWithTag("intro_paste_field").performTextInput("https://host:9898/pair?t=abc123")
        onNodeWithTag("intro_paste_submit").performClick()
        settle()
        assertEquals(null, store.load())
        onNodeWithTag("pair_tofu_confirm").performClick()
        settle()
        assertEquals("abc123", store.load())
        assertEquals(1, paired)
    }

    @Test fun manual_entry_stays_one_disclosure_away() = runComposeUiTest {
        mainClock.autoAdvance = false
        val store = InMemoryPairingTokenStore()
        setPlatformContent(cameraPlatform(), pointer = false, widthClass = WindowWidthClass.Compact) {
            OnboardingFlow(pairing = testPairing(store), onPaired = {})
        }
        settle()
        onNodeWithTag("intro_day_rail_3").performClick()
        settle()
        onNodeWithTag("intro_manual_host").assertDoesNotExist()
        onNodeWithTag("intro_manual_toggle").performClick()
        settle()
        onNodeWithTag("intro_manual_host").performTextInput("ws://10.0.2.2:9898")
        onNodeWithTag("intro_manual_token").performTextInput("tok-1")
        onNodeWithTag("intro_manual_submit").performClick()
        settle()
        onNodeWithTag("pair_tofu_confirm").performClick()
        settle()
        assertEquals("tok-1", store.load())
    }

    // ── the show policy ───────────────────────────────────────────────────────────────────────

    @Test fun the_show_policy_is_the_one_desktop_always_had() {
        assertTrue(shouldShowIntro(envIntro = null, envPairToken = null, seen = false))
        assertFalse(shouldShowIntro(envIntro = null, envPairToken = null, seen = true))
        // A forced run always plays, even for someone who has seen it.
        assertTrue(shouldShowIntro(envIntro = "1", envPairToken = null, seen = true))
        assertFalse(shouldShowIntro(envIntro = "0", envPairToken = null, seen = false))
        // A seeded dev/CI pairing never plays it — the overlay would hijack every screenshot.
        assertFalse(shouldShowIntro(envIntro = null, envPairToken = "tok", seen = false))
        assertTrue(shouldShowIntro(envIntro = "1", envPairToken = "tok", seen = false))
    }
}
