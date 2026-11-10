package dev.supermux.ui.settings.pages

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.CommandGroup
import dev.supermux.ui.keys.KeyOs
import dev.supermux.ui.keys.Keymap
import dev.supermux.ui.keys.bind
import dev.supermux.ui.settings.kit.KitAnchorKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings › Keyboard shortcuts (#97, #96 §6): the app's commands by group, each with its keys as this device writes them. */
@OptIn(ExperimentalTestApi::class)
class KeyboardShortcutsPageTest {

    @Test fun every_group_has_a_head_and_every_command_its_keys() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Expanded) { KeyboardShortcutsPage(os = KeyOs.Other) }
        waitForIdle()
        onNodeWithTag("shortcuts_page").assertExists()
        for (g in CommandGroup.entries) onNodeWithTag("shortcuts_group_${g.name.lowercase()}").assertExists()
        onNodeWithTag("shortcut_session.new").performScrollTo()
            .assert(hasAnyDescendant(hasText("New session")))
            .assert(hasAnyDescendant(hasText("Ctrl+N")))
        // Ctrl+/ opens this very page.
        onNodeWithTag("shortcut_shortcuts.show").performScrollTo().assert(hasAnyDescendant(hasText("Ctrl+/")))
    }

    @Test fun apple_keys_are_drawn_with_apples_symbols() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Expanded) { KeyboardShortcutsPage(os = KeyOs.Apple) }
        waitForIdle()
        onNodeWithTag("shortcut_window.moveToNew").performScrollTo().assert(hasAnyDescendant(hasText("⇧⌘N")))
        onNodeWithTag("shortcut_settings.open").performScrollTo().assert(hasAnyDescendant(hasText("⌘,")))
    }

    @Test fun a_command_with_several_keys_shows_them_all() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Expanded) { KeyboardShortcutsPage(os = KeyOs.Other) }
        waitForIdle()
        onNodeWithTag("shortcut_tab.close").performScrollTo()
            .assert(hasAnyDescendant(hasText("Ctrl+W or Ctrl+Shift+W")))
    }

    @Test fun commands_without_keys_and_empty_groups_are_left_out() = runComposeUiTest {
        val keymap = Keymap(listOf(bind("Mod-n", AppCommands.NewSession)))
        setPlatformContent(widthClass = WindowWidthClass.Expanded) { KeyboardShortcutsPage(os = KeyOs.Other, keymap = keymap) }
        waitForIdle()
        onNodeWithTag("shortcut_session.new").assertExists()
        onNodeWithTag("shortcut_find").assertDoesNotExist()
        onNodeWithTag("shortcuts_group_sessions").assertExists()
        onNodeWithTag("shortcuts_group_files").assertDoesNotExist()
    }

    @Test fun every_row_is_a_search_anchor_and_the_list_says_it_is_read_only() = runComposeUiTest {
        var shown = 0
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            KeyboardShortcutsPage(os = KeyOs.Other, anchor = "key-files.goTo", onAnchorShown = { shown++ })
        }
        waitForIdle()
        for (c in AppCommands.all) onNode(SemanticsMatcher.expectValue(KitAnchorKey, "key-${c.id}")).assertExists()
        onNodeWithText("A read-only list for now; changing keys comes later.").assertExists()
        waitUntil(timeoutMillis = 5_000) { shown == 1 }
        assertEquals(1, shown)
    }

    @Test fun the_anchor_names_are_the_search_indexs() {
        assertEquals("key-session.new", shortcutAnchor(AppCommands.NewSession))
    }
}
