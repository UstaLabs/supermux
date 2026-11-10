package dev.supermux.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.retain.LocalRetainedPage
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.KitMachineScope
import dev.supermux.ui.settings.kit.SettingsMachines
import dev.supermux.ui.settings.kit.THIS_COMPUTER_ID
import dev.supermux.ui.settings.search.SettingsSearchEntry
import dev.supermux.ui.settings.search.SettingsSearchIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shared Settings shell (cluster E1): desktop's rail + detail on a roomy window, Android's
 * index + push on a phone, ONE composable. Sections are slots, so this suite drives a stub slot
 * rather than any real settings screen — what is under test is navigation, the dirty-close guard,
 * the host scoping of the compact push stack and the `Caps` gate on the extra rows.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsHubTest {

    private val gatedCaps: Caps = NO_CAPS.copy(appearanceControls = true, appUpdate = true)

    /** Renders `section_<name>` for whatever the hub selected, plus a dirty toggle. */
    @Suppress("TestFunctionName")
    private fun sectionStub(dirty: Boolean = false): @androidx.compose.runtime.Composable (
        SettingsPlace,
        SettingsSlotScope,
    ) -> Unit = { p, scope ->
        if (dirty) scope.onDirtyChange(true)
        Text("body", modifier = Modifier.testTag("section_${p.section.name.lowercase()}"))
    }

    /** The rail and the index: every page but [SettingsSection.Machine], which machine cards open. */
    private val listed = SettingsSection.entries.filter { it != SettingsSection.Machine }

    private val extraStub: @androidx.compose.runtime.Composable (
        SettingsExtra,
        SettingsSlotScope,
    ) -> Unit = { e, _ ->
        Text("extra", modifier = Modifier.testTag("extra_${e.name.lowercase()}"))
    }

    // ── rail + detail (Expanded) ───────────────────────────────────────────────────────────────

    @Test fun the_rail_lists_every_section_and_the_detail_renders_the_selected_one() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            SettingsHub(
                section = SettingsSection.Providers,
                onSectionChange = {},
                onBack = {},
                content = sectionStub(),
            )
        }
        waitForIdle()
        onNodeWithTag("settings_hub").assertIsDisplayed()
        onNodeWithTag("settings_hub_rail").assertIsDisplayed()
        listed.forEach {
            onNodeWithTag("settings_section_${it.name.lowercase()}").assertExists()
        }
        onNodeWithTag("settings_section_machine").assertDoesNotExist()
        onNodeWithTag("settings_hub_detail").assertIsDisplayed()
        onNodeWithTag("section_providers").assertIsDisplayed()
        // The compact chrome is NOT on a wide window.
        onNodeWithTag("settings_index").assertDoesNotExist()
        onNodeWithTag("settings_row_providers").assertDoesNotExist()
    }

    @Test fun a_rail_click_reports_the_new_section_to_the_host() = runComposeUiTest {
        var picked: SettingsSection? = null
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            SettingsHub(
                section = SettingsSection.Providers,
                onSectionChange = { picked = it },
                onBack = {},
                content = sectionStub(),
            )
        }
        onNodeWithTag("settings_section_voice").performClick()
        waitForIdle()
        assertEquals(SettingsSection.Voice, picked)
    }

    @Test fun the_hub_back_button_closes_when_nothing_is_dirty() = runComposeUiTest {
        var closed = 0
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            SettingsHub(
                section = SettingsSection.Providers,
                onSectionChange = {},
                onBack = { closed++ },
                content = sectionStub(),
            )
        }
        onNodeWithTag("settings_hub_back").performClick()
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun a_dirty_section_blocks_the_switch_until_the_edits_are_discarded() = runComposeUiTest {
        var picked: SettingsSection? = null
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            SettingsHub(
                section = SettingsSection.Providers,
                onSectionChange = { picked = it },
                onBack = {},
                content = sectionStub(dirty = true),
            )
        }
        onNodeWithTag("settings_section_voice").performClick()
        waitForIdle()
        onNodeWithTag("settings_discard_dialog").assertExists()
        assertEquals(null, picked)

        // Keep editing → nothing moves.
        onNodeWithTag("settings_discard_cancel").performClick()
        waitForIdle()
        onNodeWithTag("settings_discard_dialog").assertDoesNotExist()
        assertEquals(null, picked)

        // Discard → the pending switch runs.
        onNodeWithTag("settings_section_voice").performClick()
        waitForIdle()
        onNodeWithTag("settings_discard_confirm").performClick()
        waitForIdle()
        assertEquals(SettingsSection.Voice, picked)
    }

    @Test fun the_registered_close_handler_is_the_escape_path_and_honors_the_dirty_guard() = runComposeUiTest {
        var handler: (() -> Unit)? = null
        var closed = 0
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            SettingsHub(
                section = SettingsSection.Voice,
                onSectionChange = {},
                onBack = { closed++ },
                onRegisterCloseHandler = { handler = it },
                content = sectionStub(dirty = true),
            )
        }
        waitForIdle()
        // Escape (desktop's overlay) and system back both run this closure.
        assertNotNull(handler)
        runOnIdle { handler!!() }
        waitForIdle()
        onNodeWithTag("settings_discard_dialog").assertExists()
        assertEquals(0, closed)

        onNodeWithTag("settings_discard_confirm").performClick()
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun escape_closes_straight_away_when_no_section_is_dirty() = runComposeUiTest {
        var handler: (() -> Unit)? = null
        var closed = 0
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            SettingsHub(
                section = SettingsSection.Providers,
                onSectionChange = {},
                onBack = { closed++ },
                onRegisterCloseHandler = { handler = it },
                content = sectionStub(),
            )
        }
        waitForIdle()
        runOnIdle { handler!!() }
        waitForIdle()
        onNodeWithTag("settings_discard_dialog").assertDoesNotExist()
        assertEquals(1, closed)
    }

    // ── index + push (Compact) ─────────────────────────────────────────────────────────────────

    @Test fun a_compact_window_opens_on_the_index_of_rows_instead_of_the_rail() = runComposeUiTest {
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            SettingsHub(
                section = null,
                onSectionChange = {},
                onBack = {},
                content = sectionStub(),
            )
        }
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
        onNodeWithTag("settings_hub_rail").assertDoesNotExist()
        listed.forEach {
            onNodeWithTag("settings_row_${it.name.lowercase()}").assertExists()
        }
        onNodeWithTag("settings_row_machine").assertDoesNotExist()
        // Nothing is pushed yet, so no detail and no section body.
        onNodeWithTag("settings_hub_detail").assertDoesNotExist()
        onNodeWithTag("section_providers").assertDoesNotExist()
    }

    @Test fun a_row_pushes_the_section_detail_and_back_returns_to_the_index() = runComposeUiTest {
        var picked: SettingsSection? = null
        var closed = 0
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            SettingsHub(
                section = null,
                onSectionChange = { picked = it },
                onBack = { closed++ },
                content = sectionStub(),
            )
        }
        onNodeWithTag("settings_row_coding").performClick()
        waitForIdle()
        // The compact push does NOT report the section, and this assertion is the fix for a real
        // bug rather than an accommodation of one. The host writes what it is told into
        // `Route.Settings`, that route is NavDisplay's content key for the settings entry, and a
        // changed key disposes the entry — so reporting the push destroyed the hub that had just
        // pushed, and every section row on a phone looked dead. The rail path still reports,
        // because there the section IS the destination.
        //
        // This suite could not have caught that: it drives `SettingsHub` directly, with no
        // NavDisplay above it, so the hub survived its own report here while failing in the app.
        // `SupermuxAppNavTest.compact_opening_a_settings_section_stays_open` is the one that runs
        // the whole shell and would have.
        assertEquals(null, picked)
        onNodeWithTag("settings_hub_detail").assertExists()
        onNodeWithTag("section_coding").assertExists()
        onNodeWithTag("settings_detail_title").assertTextEquals("Coding")
        onNodeWithTag("settings_index").assertDoesNotExist()

        onNodeWithTag("settings_detail_back").performClick()
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
        onNodeWithTag("section_coding").assertDoesNotExist()
        // Back inside the hub is NOT a close of the hub.
        assertEquals(0, closed)

        // From the index, back closes.
        onNodeWithTag("settings_hub_back").performClick()
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun a_pushed_detail_returns_to_the_index_when_the_active_host_changes() = runComposeUiTest {
        var host by mutableStateOf("h1")
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            SettingsHub(
                section = null,
                onSectionChange = {},
                onBack = {},
                hostKey = host,
                content = sectionStub(),
            )
        }
        onNodeWithTag("settings_row_memory").performClick()
        waitForIdle()
        onNodeWithTag("section_memory").assertExists()

        runOnIdle { host = "h2" }
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
        onNodeWithTag("section_memory").assertDoesNotExist()
    }

    /**
     * Cluster E7 removed the `compactTopBar` escape hatch: every section and extra is a shared
     * screen that reads `scope.topBarShown`, so the hub paints the pushed detail's bar for ALL of
     * them and a phone sees exactly one — never a page's own bar under the hub's.
     */
    @Test fun the_hub_paints_exactly_one_bar_for_every_compact_detail() = runComposeUiTest {
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            SettingsHub(
                section = null,
                onSectionChange = {},
                onBack = {},
                content = { p, scope ->
                    // A real screen's contract: draw a bar only when the hub did not.
                    if (!scope.topBarShown) {
                        Text("own bar", modifier = Modifier.testTag("page_own_bar"))
                    }
                    Text("body", modifier = Modifier.testTag("section_${p.section.name.lowercase()}"))
                },
            )
        }
        onNodeWithTag("settings_row_privacylabs").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("section_privacylabs").assertExists()
        onNodeWithTag("settings_detail_title").assertIsDisplayed()
        onNodeWithTag("settings_detail_back").assertIsDisplayed()
        onNodeWithTag("page_own_bar").assertDoesNotExist()
    }

    /**
     * The wide rail is the mirror image: the hub paints its own header + Back ABOVE the rail and
     * nothing over the detail, so `topBarShown` is false there. That is not an invitation for the
     * page to draw a bar — the shared screens deliberately draw none above Compact, since the rail
     * owns navigation — it is the flag telling each page which side of that line it is on.
     */
    @Test fun a_wide_detail_pane_reports_that_the_hub_painted_no_detail_bar() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            SettingsHub(
                section = SettingsSection.Coding,
                onSectionChange = {},
                onBack = {},
                content = { _, scope ->
                    Text(
                        if (scope.topBarShown) "hub-detail-bar" else "no-detail-bar",
                        modifier = Modifier.testTag("bar_owner"),
                    )
                },
            )
        }
        waitForIdle()
        onNodeWithTag("bar_owner").assertTextEquals("no-detail-bar")
        onNodeWithTag("settings_detail_title").assertDoesNotExist()
    }

    @Test fun a_dirty_section_blocks_the_compact_back_until_discarded() = runComposeUiTest {
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            SettingsHub(
                section = null,
                onSectionChange = {},
                onBack = {},
                content = sectionStub(dirty = true),
            )
        }
        onNodeWithTag("settings_row_voice").performClick()
        waitForIdle()
        onNodeWithTag("settings_detail_back").performClick()
        waitForIdle()
        onNodeWithTag("settings_discard_dialog").assertExists()
        onNodeWithTag("section_voice").assertExists()

        onNodeWithTag("settings_discard_confirm").performClick()
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
    }

    // ── Caps-gated extra rows ──────────────────────────────────────────────────────────────────

    @Test fun the_appearance_and_update_rows_are_absent_on_a_host_without_those_caps() = runComposeUiTest {
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            SettingsHub(
                section = null,
                onSectionChange = {},
                onBack = {},
                extraContent = extraStub,
                content = sectionStub(),
            )
        }
        waitForIdle()
        onNodeWithTag("settings_row_appearance").assertDoesNotExist()
        onNodeWithTag("settings_row_appupdate").assertDoesNotExist()
    }

    @Test fun a_host_with_the_caps_gets_the_extra_rows_and_they_push_the_extra_page() = runComposeUiTest {
        setPlatformContent(
            platform = FakePlatform(caps = gatedCaps),
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            SettingsHub(
                section = null,
                onSectionChange = {},
                onBack = {},
                extraContent = extraStub,
                content = sectionStub(),
            )
        }
        waitForIdle()
        onNodeWithTag("settings_row_appearance").assertExists()
        onNodeWithTag("settings_row_appupdate").assertExists()

        // Scroll first: the index is a plain scrolled Column, and with enough sections above it
        // (e.g. Worktrees) the Appearance row sits below the test window's fixed viewport —
        // performClick() dispatches at the node's actual position, so an off-screen click is a
        // silent no-op rather than a failure at the click site itself.
        onNodeWithTag("settings_row_appearance").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("extra_appearance").assertExists()

        onNodeWithTag("settings_detail_back").performClick()
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
    }

    /**
     * The Hosting extra row is gone (#97): this computer's hosting is the Machine page's "This computer" sub-page,
     * whose row `InterimMachinePageTest` gates on `Caps.localBroker`. The Machine page itself is never a rail row —
     * a machine card opens it — and its sub-pages go back to it before Settings closes.
     */
    @Test fun the_machine_page_is_not_a_rail_row_and_its_sub_pages_go_back_to_it() = runComposeUiTest {
        var handler: (() -> Unit)? = null
        var closed = 0
        setPlatformContent(
            platform = FakePlatform(caps = gatedCaps.copy(localBroker = true)),
            widthClass = WindowWidthClass.Expanded,
        ) {
            SettingsHub(
                section = SettingsSection.Machine,
                sub = MachineSub.COMPUTER,
                onSectionChange = {},
                onBack = { closed++ },
                onRegisterCloseHandler = { handler = it },
                extraContent = extraStub,
            ) { place, _ -> Text("page ${place.section} ${place.sub}") }
        }
        waitForIdle()
        onNodeWithTag("settings_section_machine").assertDoesNotExist()
        onNodeWithTag("settings_section_hosting").assertDoesNotExist()
        onNodeWithText("page Machine computer").assertIsDisplayed()

        runOnIdle { handler!!() }
        waitForIdle()
        onNodeWithText("page Machine null").assertIsDisplayed()
        assertEquals(0, closed)

        runOnIdle { handler!!() }
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun the_gated_rows_join_the_rail_on_a_wide_window_too() = runComposeUiTest {
        setPlatformContent(
            platform = FakePlatform(caps = gatedCaps),
            widthClass = WindowWidthClass.Expanded,
        ) {
            SettingsHub(
                section = SettingsSection.Providers,
                onSectionChange = {},
                onBack = {},
                extraContent = extraStub,
                content = sectionStub(),
            )
        }
        waitForIdle()
        onNodeWithTag("settings_section_appupdate").assertExists()
        onNodeWithTag("section_providers").assertIsDisplayed()

        onNodeWithTag("settings_section_appupdate").performClick()
        waitForIdle()
        onNodeWithTag("extra_appupdate").assertIsDisplayed()
        // The extra replaces the section detail without touching the host's section selection.
        onNodeWithTag("section_providers").assertDoesNotExist()
    }

    @Test fun a_pushed_detail_survives_the_host_rewriting_its_section_state() = runComposeUiTest {
        // Desktop re-supplies `section` from `onSectionChange` (it rewrites Route.Settings), so a
        // compact row-tap immediately recomposes the hub with a NEW section value. The push stack
        // has to live through that — before the E1 review it did not (the host also keyed on the
        // section, and the remount dropped the detail straight back to the index).
        var closed = 0
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            var current by remember { mutableStateOf<SettingsSection?>(null) }
            SettingsHub(
                section = current,
                onSectionChange = { current = it },
                onBack = { closed++ },
                hostKey = "h1",
                content = sectionStub(),
            )
        }
        onNodeWithTag("settings_row_voice").performClick()
        waitForIdle()
        onNodeWithTag("section_voice").assertExists()
        onNodeWithTag("settings_index").assertDoesNotExist()

        // And a second row, reached after going back, behaves the same.
        onNodeWithTag("settings_detail_back").performClick()
        waitForIdle()
        onNodeWithTag("settings_row_coding").performClick()
        waitForIdle()
        onNodeWithTag("section_coding").assertExists()
        assertEquals(0, closed)
    }

    // Compose Multiplatform's BackHandler listens on its OWN dispatcher local, and injecting one is
    // @InternalComposeUiApi — opted into here, in a test, so the back GESTURE is exercised for real
    // rather than by calling the hub's close closure and trusting it is the same line.
    @OptIn(InternalComposeUiApi::class)
    @Test fun the_system_back_gesture_pops_the_detail_then_closes_the_hub() = runComposeUiTest {
        val input = TestBackInput()
        val dispatcher = NavigationEventDispatcher()
        dispatcher.addInput(input)
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        var closed = 0
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                SettingsHub(
                    section = null,
                    onSectionChange = {},
                    onBack = { closed++ },
                    content = sectionStub(),
                )
            }
        }
        onNodeWithTag("settings_row_voice").performClick()
        waitForIdle()
        onNodeWithTag("section_voice").assertExists()

        // Back out of the detail: the index, NOT a close.
        runOnIdle { input.back() }
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
        onNodeWithTag("section_voice").assertDoesNotExist()
        assertEquals(0, closed)

        // Back from the index closes the hub.
        runOnIdle { input.back() }
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun every_section_has_an_index_description() {
        SettingsSection.entries.forEach { assertTrue(it.desc(labs = false).isNotBlank(), "no desc for $it") }
        assertEquals("Usage statistics", SettingsSection.PrivacyLabs.desc(labs = false))
    }

    // ── #97: plain Settings, sub-pages and anchors ─────────────────────────────────────────────

    @Test fun a_phone_opened_on_plain_settings_shows_the_index() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            SettingsHub(section = null, onSectionChange = {}, onBack = {}) { place, _ -> Text("page ${place.section}") }
        }
        onNodeWithTag("settings_index").assertExists()
    }

    @Test fun a_wide_window_opened_on_plain_settings_shows_general() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            SettingsHub(section = null, onSectionChange = {}, onBack = {}) { place, _ -> Text("page ${place.section}") }
        }
        onNodeWithText("page General").assertIsDisplayed()
    }

    @Test fun a_phone_opened_on_general_lands_on_general() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            SettingsHub(section = SettingsSection.General, onSectionChange = {}, onBack = {}) { place, _ -> Text("page ${place.section}") }
        }
        onNodeWithText("page General").assertExists()
    }

    @Test fun a_sub_page_goes_back_to_its_page_first() = runComposeUiTest {
        var closed = 0
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            SettingsHub(section = SettingsSection.Providers, sub = "claude", onSectionChange = {}, onBack = { closed++ }) { place, _ ->
                Text("page ${place.section} ${place.sub}")
            }
        }
        onNodeWithText("page Providers claude").assertExists()
        onNodeWithTag("settings_detail_title").assertTextEquals("Claude Code")
        onNodeWithTag("settings_detail_back").performClick()
        onNodeWithText("page Providers null").assertExists()
        onNodeWithTag("settings_detail_title").assertTextEquals("Providers & accounts")
        assertEquals(0, closed)
    }

    @Test fun the_anchor_reaches_the_page_once() = runComposeUiTest {
        val seen = mutableListOf<String?>()
        setPlatformContent {
            SettingsHub(section = SettingsSection.Coding, anchor = "github", onSectionChange = {}, onBack = {}) { place, scope ->
                seen += place.anchor
                LaunchedEffect(place.anchor) { if (place.anchor != null) scope.onAnchorShown() }
            }
        }
        waitForIdle()
        assertEquals("github", seen.first())
        assertEquals(null, seen.last())
    }

    @Test fun a_new_anchor_on_the_page_on_display_still_lands() = runComposeUiTest {
        var anchor by mutableStateOf<String?>(null)
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            SettingsHub(section = SettingsSection.Coding, anchor = anchor, onSectionChange = {}, onBack = {}) { place, _ ->
                Text("page ${place.section} ${place.anchor}")
            }
        }
        onNodeWithText("page Coding null").assertExists()
        anchor = "github"
        waitForIdle()
        onNodeWithText("page Coding github").assertExists()
    }

    @Test fun the_system_back_gesture_pops_a_sub_page_then_the_page_then_closes() = runComposeUiTest {
        val input = TestBackInput()
        val dispatcher = NavigationEventDispatcher()
        dispatcher.addInput(input)
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        var closed = 0
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                SettingsHub(section = SettingsSection.Machine, sub = MachineSub.DEVICES, onSectionChange = {}, onBack = { closed++ }) { place, _ ->
                    Text("page ${place.section} ${place.sub}")
                }
            }
        }
        onNodeWithText("page Machine devices").assertExists()
        onNodeWithTag("settings_detail_title").assertTextEquals("Devices")

        runOnIdle { input.back() }
        waitForIdle()
        onNodeWithText("page Machine null").assertExists()
        assertEquals(0, closed)

        runOnIdle { input.back() }
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
        assertEquals(0, closed)

        runOnIdle { input.back() }
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun opening_a_sub_page_of_the_page_on_display_keeps_what_the_page_retains() = runComposeUiTest {
        val keys = mutableListOf<String?>()
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            SettingsHub(section = SettingsSection.Machine, onSectionChange = {}, onBack = {}) { place, scope ->
                keys += LocalRetainedPage.current
                Text(
                    "page ${place.section} ${place.sub}",
                    Modifier.testTag("open_devices").clickable { scope.onOpen(SettingsPlace(SettingsSection.Machine, MachineSub.DEVICES)) },
                )
            }
        }
        onNodeWithText("page Machine null").assertExists()
        onNodeWithTag("open_devices").performClick()
        waitForIdle()
        onNodeWithText("page Machine devices").assertExists()
        assertEquals(1, keys.distinct().size, "the sub-page must not start a new opening: $keys")
    }

    @Test fun the_compact_to_wide_swap_keeps_the_page_its_sub_page_and_the_pending_anchor() = runComposeUiTest {
        var width by mutableStateOf(WindowWidthClass.Compact)
        val reported = mutableListOf<SettingsSection>()
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            CompositionLocalProvider(LocalWindowWidthClass provides width) {
                SettingsHub(
                    section = SettingsSection.Providers,
                    sub = "codex",
                    anchor = "auto-switch",
                    onSectionChange = { reported += it },
                    onBack = {},
                ) { place, _ -> Text("page ${place.section} ${place.sub} ${place.anchor}") }
            }
        }
        onNodeWithText("page Providers codex auto-switch").assertExists()
        width = WindowWidthClass.Expanded
        waitForIdle()
        onNodeWithTag("settings_hub_rail").assertExists()
        onNodeWithText("page Providers codex auto-switch").assertExists()
        width = WindowWidthClass.Compact
        waitForIdle()
        onNodeWithText("page Providers codex auto-switch").assertExists()
        onNodeWithTag("settings_index").assertDoesNotExist()
        assertEquals(emptyList(), reported)
    }
    // ── #97 Task 10: the hub in our look ───────────────────────────────────────────────────────

    private val mac = KitMachine("m1", "Ahmet's MacBook", local = true, online = true, status = "Running")
    private val mini = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline · last seen 2h ago", lastSeen = "2h ago")

    /** The field's own text, not its "Search settings" placeholder. */
    private val emptyQuery = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))

    /** General's Theme row, which the General page (a later task) adds to the real index. */
    private val index = SettingsSearchIndex + SettingsSearchEntry(SettingsSection.General, "theme", "Theme", "dark light mode")

    /**
     * The hub as the shell drives it: what the wide hub reports (a page, a sub-page) is written back into the
     * route it reads, as `ShellUiState.moveSettings` does.
     */
    @Composable
    private fun RoutedHub(
        machines: SettingsMachines? = null,
        start: SettingsSection? = null,
        reported: MutableList<Pair<SettingsSection, String?>> = mutableListOf(),
        content: @Composable (SettingsPlace, SettingsSlotScope) -> Unit,
    ) {
        var section by remember { mutableStateOf(start) }
        var sub by remember { mutableStateOf<String?>(null) }
        SettingsHub(
            section = section,
            sub = sub,
            onSectionChange = { section = it; sub = null },
            onPlaceChange = { s, p -> reported += s to p; section = s; sub = p },
            onBack = {},
            machines = machines,
            searchIndex = index,
            content = content,
        )
    }

    @Test fun the_wide_rail_has_search_a_card_per_machine_and_the_six_pages() = runComposeUiTest {
        setPlatformContent {
            SettingsHub(section = null, onSectionChange = {}, onBack = {}, machines = SettingsMachines(listOf(mac, mini), "m1") {}) { p, _ -> Text("page ${p.section}") }
        }
        onNodeWithTag("settings_search").assertExists()
        onNodeWithTag("settings_machine_card_m1").assertExists()
        onNodeWithTag("settings_machine_card_m2").assertExists()
        for (s in listOf("general", "providers", "voice", "coding", "memory", "privacylabs")) onNodeWithTag("settings_section_$s").assertExists()
        onNodeWithTag("settings_section_machine").assertDoesNotExist()
        onNodeWithText("page General").assertExists()
    }

    @Test fun a_search_result_opens_its_page_at_its_row_and_clears_the_query_when_wide() = runComposeUiTest {
        var lastAnchor: String? = "unset"
        setPlatformContent {
            SettingsHub(section = null, onSectionChange = {}, onBack = {}, searchIndex = index) { p, _ ->
                lastAnchor = p.anchor
                Text("page ${p.section}")
            }
        }
        onNodeWithTag("settings_search").performTextInput("theme")
        onNodeWithTag("settings_result_general_theme").performClick()
        onNodeWithText("page General").assertExists()
        assertEquals("theme", lastAnchor)
        onNodeWithTag("settings_search").assert(emptyQuery)
    }

    @Test fun a_search_with_no_hits_says_so() = runComposeUiTest {
        setPlatformContent {
            SettingsHub(section = null, onSectionChange = {}, onBack = {}, searchIndex = index) { p, _ -> Text("page ${p.section}") }
        }
        onNodeWithTag("settings_search").performTextInput("zzqx")
        onNodeWithTag("settings_search_empty").assertExists()
        onNodeWithTag("settings_section_voice").assertDoesNotExist()
    }

    @Test fun on_a_phone_back_from_a_result_returns_to_the_results() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            SettingsHub(section = null, onSectionChange = {}, onBack = {}, searchIndex = index) { p, _ -> Text("page ${p.section}") }
        }
        onNodeWithTag("settings_search").performTextInput("theme")
        onNodeWithTag("settings_result_general_theme").performClick()
        onNodeWithText("page General").assertExists()
        onNodeWithTag("settings_detail_back").performClick()
        onNodeWithTag("settings_search_results").assertExists()
    }

    @Test fun the_shortcut_focuses_search() = runComposeUiTest {
        setPlatformContent {
            SettingsHub(section = null, onSectionChange = {}, onBack = {}, searchShortcut = "Ctrl+F") { _, _ -> Text("page") }
        }
        onNodeWithTag("settings_hub").requestFocus()
        onNodeWithTag("settings_hub").performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft)
        }
        onNodeWithTag("settings_search").assertIsFocused()
    }

    @Test fun escape_in_the_search_field_clears_the_query_before_it_closes() = runComposeUiTest {
        var handler: (() -> Unit)? = null
        var closed = 0
        setPlatformContent {
            SettingsHub(
                section = null,
                onSectionChange = {},
                onBack = { closed++ },
                onRegisterCloseHandler = { handler = it },
                searchIndex = index,
            ) { _, _ -> Text("page") }
        }
        onNodeWithTag("settings_search").performTextInput("theme")
        // The shell's Escape (an ancestor's key handler) runs the registered close handler before the field sees the key.
        runOnIdle { handler!!() }
        waitForIdle()
        onNodeWithTag("settings_search").assert(emptyQuery)
        assertEquals(0, closed)
        runOnIdle { handler!!() }
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun a_machine_card_picks_that_machine_and_opens_its_page() = runComposeUiTest {
        var picked: String? = null
        setPlatformContent {
            RoutedHub(machines = SettingsMachines(listOf(mac, mini), "m1") { picked = it }) { p, _ -> Text("page ${p.section}") }
        }
        onNodeWithTag("settings_machine_card_m2").performClick()
        assertEquals("m2", picked)
        onNodeWithText("page Machine").assertExists()
    }

    @Test fun picking_another_machine_on_a_phone_page_stays_on_the_page() = runComposeUiTest {
        var current by mutableStateOf("m1")
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            SettingsHub(section = SettingsSection.Memory, onSectionChange = {}, onBack = {}, machines = SettingsMachines(listOf(mac, mini), current) { current = it }) { p, _ ->
                Column { Text("page ${p.section}"); KitMachineScope() }
            }
        }
        onNodeWithTag("settings_scope_picker").performClick()
        onNodeWithTag("settings_machine_pick_m2").performClick()
        onNodeWithText("page Memory").assertExists()
        assertEquals("m2", current)
    }

    @Test fun picking_a_machine_from_the_scope_line_asks_first_when_the_page_has_unsaved_edits() = runComposeUiTest {
        var current by mutableStateOf("m1")
        setPlatformContent {
            SettingsHub(section = SettingsSection.Memory, onSectionChange = {}, onBack = {}, machines = SettingsMachines(listOf(mac, mini), current) { current = it }) { p, scope ->
                scope.onDirtyChange(true)
                Column { Text("page ${p.section}"); KitMachineScope() }
            }
        }
        onNodeWithTag("settings_scope_picker").performClick()
        onNodeWithTag("settings_machine_pick_m2").performClick()
        waitForIdle()
        onNodeWithTag("settings_discard_dialog").assertExists()
        assertEquals("m1", current)
        onNodeWithTag("settings_discard_confirm").performClick()
        waitForIdle()
        assertEquals("m2", current)
    }

    @Test fun the_this_computer_card_opens_hosting_when_no_local_host_is_paired() = runComposeUiTest {
        val computer = KitMachine(THIS_COMPUTER_ID, "This computer", local = true, online = false, status = "Not hosting")
        var picked: String? = null
        setPlatformContent(platform = FakePlatform(caps = NO_CAPS.copy(localBroker = true))) {
            RoutedHub(machines = SettingsMachines(listOf(computer, mini), "m2") { picked = it }) { p, _ -> Text("page ${p.section} ${p.sub}") }
        }
        onNodeWithTag("settings_machine_card_this-computer").performClick()
        onNodeWithText("page Machine computer").assertExists()
        assertEquals(null, picked)
        // Leaving it goes back to General, not to studio-mini's page.
        onNodeWithTag("settings_hub_back").performClick()
        onNodeWithText("page General null").assertExists()
    }

    @Test fun this_computer_s_hosting_is_not_shown_under_another_machine() = runComposeUiTest {
        setPlatformContent {
            SettingsHub(
                section = SettingsSection.Machine,
                sub = MachineSub.COMPUTER,
                onSectionChange = {},
                onBack = {},
                machines = SettingsMachines(listOf(mac, mini), "m2") {},
            ) { p, _ -> Text("page ${p.section} ${p.sub}") }
        }
        onNodeWithText("page Machine null").assertExists()
    }

    @Test fun the_phone_index_has_search_cards_and_described_rows() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            SettingsHub(section = null, onSectionChange = {}, onBack = {}, machines = SettingsMachines(listOf(mac, mini), "m1") {}) { p, _ -> Text("page ${p.section}") }
        }
        onNodeWithTag("settings_index_title").assertTextEquals("Settings")
        onNodeWithTag("settings_search").assertExists()
        onNodeWithTag("settings_machine_card_m2").assertExists()
        onNodeWithText("Dictation, languages, read aloud").assertExists()
        // A machine card opens that machine's page, named after it.
        onNodeWithTag("settings_machine_card_m1").performClick()
        onNodeWithTag("settings_detail_title").assertTextEquals("Ahmet's MacBook")
    }

    // ── Unit B review fixes ────────────────────────────────────────────────────────────────────

    @Test fun a_rail_click_on_the_page_on_display_clears_a_pending_anchor() = runComposeUiTest {
        var lastAnchor: String? = "unset"
        setPlatformContent {
            // A page that never reports the anchor shown (the interim Git hosting screen).
            SettingsHub(section = SettingsSection.Coding, anchor = "github", onSectionChange = {}, onBack = {}) { p, _ ->
                lastAnchor = p.anchor
                Text(if (p.anchor == "github") "git hosting" else "editor")
            }
        }
        onNodeWithText("git hosting").assertExists()
        onNodeWithTag("settings_section_coding").performClick()
        waitForIdle()
        assertEquals(null, lastAnchor)
        onNodeWithText("editor").assertExists()
    }

    @Test fun a_wide_hub_reports_its_sub_pages_to_the_route() = runComposeUiTest {
        val reported = mutableListOf<Pair<SettingsSection, String?>>()
        setPlatformContent {
            RoutedHub(start = SettingsSection.Providers, reported = reported) { p, scope ->
                Text(
                    "page ${p.section} ${p.sub}",
                    Modifier.testTag("open_claude").clickable { scope.onOpen(SettingsPlace(SettingsSection.Providers, "claude")) },
                )
            }
        }
        onNodeWithTag("open_claude").performClick()
        waitForIdle()
        onNodeWithText("page Providers claude").assertExists()
        assertEquals(SettingsSection.Providers to "claude", reported.last())
        onNodeWithTag("settings_hub_back").performClick()
        waitForIdle()
        onNodeWithText("page Providers null").assertExists()
        assertEquals(SettingsSection.Providers to null, reported.last())
        assertEquals(2, reported.size)
    }

    @Test fun a_hub_saved_before_sub_pages_and_anchors_restores() {
        val restored = HubPlace.Saver.restore(listOf("s:Voice", "", "Voice||", true, "op-1"))!!
        assertEquals(SettingsSection.Voice, (restored.opened as HubTarget.Sec).section)
        assertEquals(null, restored.sub)
        assertEquals(null, restored.anchor)
        assertEquals("op-1", restored.opening)
    }

    // ── Unit C review fixes ────────────────────────────────────────────────────────────────────

    @Test fun hosting_opened_from_the_this_computer_card_follows_the_local_host_it_pairs() = runComposeUiTest {
        val computer = KitMachine(THIS_COMPUTER_ID, "This computer", local = true, online = false, status = "Not hosting")
        val paired = KitMachine("m3", "Ahmet's PC", local = true, online = true, status = "Running")
        var all by mutableStateOf(listOf(computer, mini))
        var current by mutableStateOf("m2")
        val picks = mutableListOf<String>()
        setPlatformContent(platform = FakePlatform(caps = NO_CAPS.copy(localBroker = true))) {
            RoutedHub(machines = SettingsMachines(all, current) { picks += it; current = it }) { p, _ -> Text("page ${p.section} ${p.sub}") }
        }
        onNodeWithTag("settings_machine_card_this-computer").performClick()
        onNodeWithText("page Machine computer").assertExists()
        // Starting hosting pairs this computer's host: the synthetic card gives way to the real one.
        all = listOf(paired, mini)
        waitForIdle()
        onNodeWithText("page Machine computer").assertExists()
        assertEquals(listOf("m3"), picks)
        assertEquals("m3", current)
    }

    @Test fun a_host_that_cannot_host_has_no_computer_sub_page_to_eat_a_back() = runComposeUiTest {
        var handler: (() -> Unit)? = null
        var closed = 0
        setPlatformContent {
            SettingsHub(
                section = SettingsSection.Machine,
                sub = MachineSub.COMPUTER,
                onSectionChange = {},
                onBack = { closed++ },
                onRegisterCloseHandler = { handler = it },
            ) { p, _ -> Text("page ${p.section} ${p.sub}") }
        }
        onNodeWithText("page Machine null").assertExists()
        runOnIdle { handler!!() }
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun escape_clears_a_wide_search_even_when_the_field_has_lost_focus() = runComposeUiTest {
        var handler: (() -> Unit)? = null
        var closed = 0
        setPlatformContent {
            SettingsHub(
                section = null,
                onSectionChange = {},
                onBack = { closed++ },
                onRegisterCloseHandler = { handler = it },
                searchIndex = index,
            ) { _, _ -> Box(Modifier.size(10.dp).testTag("page_focus").focusable()) }
        }
        onNodeWithTag("settings_search").performTextInput("theme")
        onNodeWithTag("page_focus").requestFocus()
        onNodeWithTag("settings_search").assertIsNotFocused()
        runOnIdle { handler!!() }
        waitForIdle()
        onNodeWithTag("settings_search").assert(emptyQuery)
        assertEquals(0, closed)
    }

    @Test fun keep_editing_on_a_wide_result_pick_keeps_the_query() = runComposeUiTest {
        setPlatformContent {
            RoutedHub(start = SettingsSection.Memory) { p, scope ->
                scope.onDirtyChange(p.section == SettingsSection.Memory)
                Text("page ${p.section}")
            }
        }
        onNodeWithTag("settings_search").performTextInput("theme")
        onNodeWithTag("settings_result_general_theme").performClick()
        waitForIdle()
        onNodeWithTag("settings_discard_cancel").performClick()
        waitForIdle()
        onNodeWithText("page Memory").assertExists()
        onNodeWithTag("settings_search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("theme")))
        // Leaving for real clears it.
        onNodeWithTag("settings_result_general_theme").performClick()
        waitForIdle()
        onNodeWithTag("settings_discard_confirm").performClick()
        waitForIdle()
        onNodeWithText("page General").assertExists()
        onNodeWithTag("settings_search").assert(emptyQuery)
    }

    @Test fun the_hub_itself_is_no_tab_stop_once_focus_is_inside() = runComposeUiTest {
        setPlatformContent {
            SettingsHub(section = null, onSectionChange = {}, onBack = {}, searchShortcut = "Ctrl+F") { _, _ -> Text("page") }
        }
        waitForIdle()
        onNodeWithTag("settings_hub").assertIsFocused()
        onNodeWithTag("settings_hub").performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithTag("settings_hub_back").assertIsFocused()
        onNodeWithTag("settings_hub_back").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
        waitForIdle()
        onNodeWithTag("settings_hub").assertIsNotFocused()
    }
}

/** Fires a real completed back gesture at whatever `BackHandler`s the composition registered. */
private class TestBackInput : NavigationEventInput() {
    /** A whole gesture: a real predictive-back sequence ends with `completed`. */
    fun back() {
        dispatchOnBackStarted(NavigationEvent())
        dispatchOnBackProgressed(NavigationEvent(progress = 1f))
        dispatchOnBackCompleted()
    }
}
