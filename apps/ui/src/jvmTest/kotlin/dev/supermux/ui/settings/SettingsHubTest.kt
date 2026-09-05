package dev.supermux.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
        assertEquals(SettingsSection.Devices, picked)
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

    @Test fun a_host_that_paints_its_own_page_chrome_gets_no_hub_top_bar() = runComposeUiTest {
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) {
            SettingsHub(
                section = SettingsSection.Agents,
                onSectionChange = {},
                onBack = {},
                compactTopBar = false,
                content = sectionStub(),
            )
        }
        onNodeWithTag("settings_row_system").performClick()
        waitForIdle()
        onNodeWithTag("section_system").assertExists()
        onNodeWithTag("settings_detail_title").assertDoesNotExist()
        onNodeWithTag("settings_detail_back").assertDoesNotExist()
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

    @Test fun every_section_has_an_index_description() {
        SettingsSection.entries.forEach { assertTrue(it.desc().isNotBlank(), "no desc for $it") }
    }
}
