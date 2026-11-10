package dev.supermux.ui.settings

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.SettingsKeys
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.prefs.EDITOR_FONT_MAX
import dev.supermux.ui.prefs.EDITOR_FONT_MIN
import dev.supermux.ui.prefs.TERMINAL_FONT_MAX
import dev.supermux.ui.prefs.TERMINAL_FONT_MIN
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.settings.kit.KitAnchorKey
import dev.supermux.ui.settings.kit.KitPage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coding's Editor & terminal section (#97 spec §2.2, §11): this device's wrap, editor and terminal font sizes,
 * side-by-side diffs and, on touch, the keys above the keyboard — each written to `UiPrefs` the moment it changes.
 */
@OptIn(ExperimentalTestApi::class)
class EditorSettingsScreenTest {

    private fun anchored(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    private fun ComposeUiTest.section(store: FakeSettingsStore, pointer: Boolean = true) =
        setPlatformContent(
            pointer = pointer,
            widthClass = if (pointer) WindowWidthClass.Expanded else WindowWidthClass.Compact,
            uiPrefs = UiPrefs(store),
        ) { KitPage("Coding") { EditorSection() } }

    @Test fun the_rows_carry_their_copy_and_anchors() = runComposeUiTest {
        section(FakeSettingsStore())
        waitForIdle()
        onNodeWithText("Wrap long lines").assertExists()
        onNodeWithText("Wrap instead of scrolling sideways.").assertExists()
        onNodeWithText("Editor font size").assertExists()
        onNodeWithText("Terminal font size").assertExists()
        onNodeWithText("Also ⌘+ and ⌘− in a terminal.").assertExists()
        onNodeWithText("Side-by-side diffs").assertExists()
        onNodeWithText("Each changed file in two columns.").assertExists()
        for (a in listOf("wrap", "editor-font", "terminal-font", "side-by-side")) onNode(anchored(a)).assertExists()
    }

    @Test fun the_keys_above_the_keyboard_are_only_offered_on_touch() = runComposeUiTest {
        section(FakeSettingsStore(), pointer = true)
        waitForIdle()
        onNodeWithText("Keys above the keyboard").assertDoesNotExist()
        onNode(anchored("accessory-bar")).assertDoesNotExist()
    }

    @Test fun on_touch_the_terminal_row_says_pinch_and_the_keys_row_is_there() = runComposeUiTest {
        section(FakeSettingsStore(), pointer = false)
        waitForIdle()
        onNodeWithText("Pinch in a terminal to change it too.").assertExists()
        onNodeWithText("Keys above the keyboard").assertExists()
        onNodeWithText("Tab, arrows, undo and find over the on-screen keyboard.").assertExists()
        onNode(anchored("accessory-bar")).assertExists()
    }

    @Test fun the_editor_font_stepper_persists_through_the_settings_store() = runComposeUiTest {
        val store = FakeSettingsStore()
        section(store)
        waitForIdle()
        // No stored value → the shared default (13).
        onNodeWithTag("editor_font_value").assertTextEquals("13")
        onNodeWithTag("editor_font_plus").performClick()
        waitForIdle()
        onNodeWithTag("editor_font_value").assertTextEquals("14")
        assertEquals("14", store.map.value[SettingsKeys.EDITOR_FONT_SIZE])
        // Two taps with no frame between them: each step reads the CURRENT stored size, so they are 14 → 13 → 12.
        onNodeWithTag("editor_font_minus").performClick()
        onNodeWithTag("editor_font_minus").performClick()
        waitForIdle()
        onNodeWithTag("editor_font_value").assertTextEquals("12")
        assertEquals("12", store.map.value[SettingsKeys.EDITOR_FONT_SIZE])
    }

    @Test fun the_editor_font_stepper_stops_at_both_ends() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.EDITOR_FONT_SIZE to EDITOR_FONT_MAX.toString())
        section(store)
        waitForIdle()
        onNodeWithTag("editor_font_value").assertTextEquals(EDITOR_FONT_MAX.toString())
        onNodeWithTag("editor_font_plus").assertIsNotEnabled()

        store.map.value = mapOf(SettingsKeys.EDITOR_FONT_SIZE to EDITOR_FONT_MIN.toString())
        waitForIdle()
        onNodeWithTag("editor_font_value").assertTextEquals(EDITOR_FONT_MIN.toString())
        onNodeWithTag("editor_font_minus").assertIsNotEnabled()
    }

    @Test fun an_out_of_range_stored_font_size_is_read_back_clamped() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.EDITOR_FONT_SIZE to "99")
        section(store)
        waitForIdle()
        onNodeWithTag("editor_font_value").assertTextEquals(EDITOR_FONT_MAX.toString())
    }

    @Test fun the_terminal_font_stepper_writes_the_terminal_size_and_stops_at_8_and_32() = runComposeUiTest {
        val store = FakeSettingsStore()
        section(store)
        waitForIdle()
        onNodeWithTag("terminal_font_value").assertTextEquals("13")
        onNodeWithTag("terminal_font_plus").performClick()
        waitForIdle()
        assertEquals("14", store.map.value[SettingsKeys.TERMINAL_FONT_SIZE])
        onNodeWithTag("terminal_font_value").assertTextEquals("14")

        store.map.value = mapOf(SettingsKeys.TERMINAL_FONT_SIZE to TERMINAL_FONT_MAX.toString())
        waitForIdle()
        onNodeWithTag("terminal_font_value").assertTextEquals("32")
        onNodeWithTag("terminal_font_plus").assertIsNotEnabled()
        onNodeWithTag("terminal_font_minus").performClick()
        waitForIdle()
        assertEquals("31", store.map.value[SettingsKeys.TERMINAL_FONT_SIZE])

        store.map.value = mapOf(SettingsKeys.TERMINAL_FONT_SIZE to TERMINAL_FONT_MIN.toString())
        waitForIdle()
        onNodeWithTag("terminal_font_value").assertTextEquals("8")
        onNodeWithTag("terminal_font_minus").assertIsNotEnabled()
    }

    @Test fun the_wrap_switch_persists() = runComposeUiTest {
        val store = FakeSettingsStore()
        section(store)
        waitForIdle()
        // Wrap defaults ON, so the first click turns it off.
        onNodeWithTag("editor_wrap_switch").performClick()
        waitForIdle()
        assertEquals("false", store.map.value[SettingsKeys.EDITOR_LINE_WRAP])
        onNodeWithTag("editor_wrap_switch").performClick()
        waitForIdle()
        assertEquals("true", store.map.value[SettingsKeys.EDITOR_LINE_WRAP])
    }

    /** M5: the Changes pane's side-by-side default and the native editor's accessory bar. */
    @Test fun the_side_by_side_and_accessory_bar_switches_persist() = runComposeUiTest {
        val store = FakeSettingsStore()
        section(store, pointer = false)
        waitForIdle()
        // Side by side defaults OFF, the bar ON.
        onNodeWithTag("editor_side_by_side_switch").performScrollTo().performClick()
        waitForIdle()
        assertEquals("true", store.map.value[SettingsKeys.EDITOR_DIFF_SIDE_BY_SIDE])
        onNodeWithTag("editor_accessory_bar_switch").performScrollTo().performClick()
        waitForIdle()
        assertEquals("false", store.map.value[SettingsKeys.EDITOR_ACCESSORY_BAR])
    }
}
