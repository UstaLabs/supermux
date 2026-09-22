package dev.supermux.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
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
        SettingsSection,
        SettingsSlotScope,
    ) -> Unit = { s, scope ->
        if (dirty) scope.onDirtyChange(true)
        Text("body", modifier = Modifier.testTag("section_${s.name.lowercase()}"))
    }

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
                section = SettingsSection.Agents,
                onSectionChange = {},
                onBack = {},
                content = sectionStub(),
            )
        }
        waitForIdle()
        onNodeWithTag("settings_hub").assertIsDisplayed()
        onNodeWithTag("settings_hub_rail").assertIsDisplayed()
        SettingsSection.entries.forEach {
            onNodeWithTag("settings_section_${it.name.lowercase()}").assertExists()
        }
        onNodeWithTag("settings_hub_detail").assertIsDisplayed()
        onNodeWithTag("section_agents").assertIsDisplayed()
        // The compact chrome is NOT on a wide window.
        onNodeWithTag("settings_index").assertDoesNotExist()
        onNodeWithTag("settings_row_agents").assertDoesNotExist()
    }

    @Test fun a_rail_click_reports_the_new_section_to_the_host() = runComposeUiTest {
        var picked: SettingsSection? = null
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            SettingsHub(
                section = SettingsSection.Agents,
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
                section = SettingsSection.Agents,
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
                section = SettingsSection.Agents,
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
                section = SettingsSection.Assistant,
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
                section = SettingsSection.Agents,
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
                section = SettingsSection.Agents,
                onSectionChange = {},
                onBack = {},
                content = sectionStub(),
            )
        }
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
        onNodeWithTag("settings_hub_rail").assertDoesNotExist()
        SettingsSection.entries.forEach {
            onNodeWithTag("settings_row_${it.name.lowercase()}").assertExists()
        }
        // Nothing is pushed yet, so no detail and no section body.
        onNodeWithTag("settings_hub_detail").assertDoesNotExist()
        onNodeWithTag("section_agents").assertDoesNotExist()
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
                section = SettingsSection.Agents,
                onSectionChange = { picked = it },
                onBack = { closed++ },
                content = sectionStub(),
            )
        }
        onNodeWithTag("settings_row_devices").performClick()
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
        onNodeWithTag("section_devices").assertExists()
        onNodeWithTag("settings_detail_title").assertExists()
        onNodeWithTag("settings_index").assertDoesNotExist()

        onNodeWithTag("settings_detail_back").performClick()
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
        onNodeWithTag("section_devices").assertDoesNotExist()
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
                section = SettingsSection.Agents,
                onSectionChange = {},
                onBack = {},
                hostKey = host,
                content = sectionStub(),
            )
        }
        onNodeWithTag("settings_row_curator").performClick()
        waitForIdle()
        onNodeWithTag("section_curator").assertExists()

        runOnIdle { host = "h2" }
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
        onNodeWithTag("section_curator").assertDoesNotExist()
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
                section = SettingsSection.Agents,
                onSectionChange = {},
                onBack = {},
                content = { s, scope ->
                    // A real screen's contract: draw a bar only when the hub did not.
                    if (!scope.topBarShown) {
                        Text("own bar", modifier = Modifier.testTag("page_own_bar"))
                    }
                    Text("body", modifier = Modifier.testTag("section_${s.name.lowercase()}"))
                },
            )
        }
        onNodeWithTag("settings_row_system").performClick()
        waitForIdle()
        onNodeWithTag("section_system").assertExists()
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
                section = SettingsSection.System,
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
                section = SettingsSection.Agents,
                onSectionChange = {},
                onBack = {},
                content = sectionStub(dirty = true),
            )
        }
        onNodeWithTag("settings_row_assistant").performClick()
        waitForIdle()
        onNodeWithTag("settings_detail_back").performClick()
        waitForIdle()
        onNodeWithTag("settings_discard_dialog").assertExists()
        onNodeWithTag("section_assistant").assertExists()

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
                section = SettingsSection.Agents,
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
                section = SettingsSection.Agents,
                onSectionChange = {},
                onBack = {},
                extraContent = extraStub,
                content = sectionStub(),
            )
        }
        waitForIdle()
        onNodeWithTag("settings_row_appearance").assertExists()
        onNodeWithTag("settings_row_appupdate").assertExists()

        onNodeWithTag("settings_row_appearance").performClick()
        waitForIdle()
        onNodeWithTag("extra_appearance").assertExists()

        onNodeWithTag("settings_detail_back").performClick()
        waitForIdle()
        onNodeWithTag("settings_index").assertExists()
    }

    @Test fun the_gated_rows_join_the_rail_on_a_wide_window_too() = runComposeUiTest {
        setPlatformContent(
            platform = FakePlatform(caps = gatedCaps),
            widthClass = WindowWidthClass.Expanded,
        ) {
            SettingsHub(
                section = SettingsSection.Agents,
                onSectionChange = {},
                onBack = {},
                extraContent = extraStub,
                content = sectionStub(),
            )
        }
        waitForIdle()
        onNodeWithTag("settings_section_appupdate").assertExists()
        onNodeWithTag("section_agents").assertIsDisplayed()

        onNodeWithTag("settings_section_appupdate").performClick()
        waitForIdle()
        onNodeWithTag("extra_appupdate").assertIsDisplayed()
        // The extra replaces the section detail without touching the host's section selection.
        onNodeWithTag("section_agents").assertDoesNotExist()
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
            var current by remember { mutableStateOf(SettingsSection.Agents) }
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
        onNodeWithTag("settings_row_system").performClick()
        waitForIdle()
        onNodeWithTag("section_system").assertExists()
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
                    section = SettingsSection.Agents,
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
        SettingsSection.entries.forEach { assertTrue(it.desc().isNotBlank(), "no desc for $it") }
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
