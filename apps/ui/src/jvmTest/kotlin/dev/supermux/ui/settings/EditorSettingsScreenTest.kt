package dev.supermux.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.LspServer
import dev.supermux.state.SettingsKeys
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.prefs.EDITOR_FONT_MAX
import dev.supermux.ui.prefs.EDITOR_FONT_MIN
import dev.supermux.ui.prefs.UiPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared Editor settings screen (cluster E7) — Android's `EditorSettingsPage` moved into `:ui`
 * and handed to desktop as its `EditorLsp` hub section.
 *
 * What is under test here is the half that is NEW to the shared world: the two device-local
 * preferences, which now round-trip through `UiPrefs`/`SettingsStore` instead of Android's
 * SharedPreferences. The language-server half below them is `LspSettingsScreenTest`'s.
 */
@OptIn(ExperimentalTestApi::class)
class EditorSettingsScreenTest {

    private val noServers: suspend () -> List<LspServer> = { emptyList() }

    private fun screen(
        topBarShown: Boolean = false,
        standalone: Boolean = false,
    ): @androidx.compose.runtime.Composable () -> Unit = {
        EditorSettingsScreen(
            lspLoad = noServers,
            lspToggle = { _, _ -> null },
            lspInstall = { null },
            lspInstallLog = MutableStateFlow(emptyMap()),
            lspInstallDone = MutableStateFlow(emptyMap()),
            lspAddCustom = { null },
            lspRemoveCustom = { null },
            topBarShown = topBarShown,
            standalone = standalone,
        )
    }

    @Test fun the_font_stepper_persists_through_the_settings_store() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) {
            screen()()
        }
        waitForIdle()
        // No stored value → the shared default (13).
        onNodeWithTag("editor_font_size").assertTextEquals("13")
        onNodeWithTag("editor_font_plus").performClick()
        waitForIdle()
        onNodeWithTag("editor_font_size").assertTextEquals("14")
        assertEquals("14", store.map.value[SettingsKeys.EDITOR_FONT_SIZE])
        onNodeWithTag("editor_font_minus").performClick()
        onNodeWithTag("editor_font_minus").performClick()
        waitForIdle()
        onNodeWithTag("editor_font_size").assertTextEquals("12")
        assertEquals("12", store.map.value[SettingsKeys.EDITOR_FONT_SIZE])
    }

    @Test fun the_font_stepper_clamps_at_both_ends() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.EDITOR_FONT_SIZE to EDITOR_FONT_MAX.toString())
        setPlatformContent(uiPrefs = UiPrefs(store)) { screen()() }
        waitForIdle()
        onNodeWithTag("editor_font_size").assertTextEquals(EDITOR_FONT_MAX.toString())
        // "+" is disabled at the top of the range, so a click cannot push past it.
        onNodeWithTag("editor_font_plus").performClick()
        waitForIdle()
        assertEquals(EDITOR_FONT_MAX.toString(), store.map.value[SettingsKeys.EDITOR_FONT_SIZE])

        store.map.value = mapOf(SettingsKeys.EDITOR_FONT_SIZE to EDITOR_FONT_MIN.toString())
        waitForIdle()
        onNodeWithTag("editor_font_size").assertTextEquals(EDITOR_FONT_MIN.toString())
        onNodeWithTag("editor_font_minus").performClick()
        waitForIdle()
        assertEquals(EDITOR_FONT_MIN.toString(), store.map.value[SettingsKeys.EDITOR_FONT_SIZE])
    }

    @Test fun an_out_of_range_stored_font_size_is_read_back_clamped() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.EDITOR_FONT_SIZE to "99")
        setPlatformContent(uiPrefs = UiPrefs(store)) { screen()() }
        waitForIdle()
        onNodeWithTag("editor_font_size").assertTextEquals(EDITOR_FONT_MAX.toString())
    }

    @Test fun the_wrap_switch_persists() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { screen()() }
        waitForIdle()
        // Wrap defaults ON, so the first click turns it off.
        onNodeWithTag("editor_wrap_switch").performClick()
        waitForIdle()
        assertEquals("false", store.map.value[SettingsKeys.EDITOR_LINE_WRAP])
        onNodeWithTag("editor_wrap_switch").performClick()
        waitForIdle()
        assertEquals("true", store.map.value[SettingsKeys.EDITOR_LINE_WRAP])
    }

    // ── chrome ────────────────────────────────────────────────────────────────────────────────

    @Test fun a_compact_window_paints_its_own_top_bar_when_the_hub_did_not() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            uiPrefs = UiPrefs(store),
        ) { screen()() }
        waitForIdle()
        onNodeWithTag("editor_settings_back").assertIsDisplayed()
    }

    @Test fun the_hub_owns_the_bar_when_it_says_so() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            uiPrefs = UiPrefs(store),
        ) { screen(topBarShown = true)() }
        waitForIdle()
        onNodeWithTag("editor_settings_back").assertDoesNotExist()
        onNodeWithTag("editor_settings_screen").assertIsDisplayed()
    }

    @Test fun a_wide_hub_detail_has_no_nested_chrome() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { screen()() }
        waitForIdle()
        onNodeWithTag("editor_settings_back").assertDoesNotExist()
    }

    @Test fun a_standalone_destination_paints_the_bar_at_every_width() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(widthClass = WindowWidthClass.Expanded, uiPrefs = UiPrefs(store)) {
            screen(standalone = true)()
        }
        waitForIdle()
        onNodeWithTag("editor_settings_back").assertIsDisplayed()
    }
}
