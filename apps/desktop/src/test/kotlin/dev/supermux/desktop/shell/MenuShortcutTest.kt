package dev.supermux.desktop.shell

import androidx.compose.ui.input.key.Key
import dev.supermux.editor.core.KeyChord
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.KeyOs
import kotlin.test.Test
import kotlin.test.assertEquals

class MenuShortcutTest {
    @Test fun aMacMenuShowsCmdNForNewSession() {
        assertEquals(MenuShortcut(Key.N, meta = true), menuShortcutFor(AppCommands.NewSession, KeyOs.Apple))
    }

    @Test fun elsewhereItIsCtrlN() {
        assertEquals(MenuShortcut(Key.N, ctrl = true), menuShortcutFor(AppCommands.NewSession, KeyOs.Other))
    }

    @Test fun aShortcutKnowsItsChord() {
        assertEquals(KeyChord(",", meta = true), MenuShortcut(Key.Comma, meta = true).chord())
    }

    @Test fun theCustomMenuWritesKeysTheDevicesWay() {
        assertEquals("Ctrl+Shift+N", MenuShortcut(Key.N, ctrl = true, shift = true).label(KeyOs.Other))
        assertEquals("⌘B", MenuShortcut(Key.B, meta = true).label(KeyOs.Apple))
    }
}

/** #134 (ADEMark): the Edit menu had only "Paste image". It has the standard set now, the native way. */
class EditMenuTest {
    private fun edit(os: KeyOs, echo: Boolean = false, sent: MutableList<MenuShortcut> = mutableListOf(), pasted: () -> Unit = {}) =
        editMenu(os, isEcho = { echo }, sendKey = { sent += it }, pasteImage = pasted)

    private fun MainMenuGroup.labels() = entries.map { (it as? MainMenuEntry.Action)?.label ?: "—" }

    @Test fun theStandardSetThenPasteImage() {
        assertEquals(listOf("Undo", "Redo", "—", "Cut", "Copy", "Paste", "Select All", "—", "Paste image"), edit(KeyOs.Apple).labels())
    }

    @Test fun aMacShowsCommandKeysAndElsewhereCtrl() {
        val mac = edit(KeyOs.Apple).entries.filterIsInstance<MainMenuEntry.Action>().associate { it.label to it.shortcut }
        assertEquals(MenuShortcut(Key.Z, meta = true), mac["Undo"])
        assertEquals(MenuShortcut(Key.Z, meta = true, shift = true), mac["Redo"])
        assertEquals(MenuShortcut(Key.V, meta = true), mac["Paste"])
        assertEquals(null, mac["Paste image"], "the composer's own paste takes images; the item has no key")
        val other = edit(KeyOs.Other).entries.filterIsInstance<MainMenuEntry.Action>().associate { it.label to it.shortcut }
        assertEquals(MenuShortcut(Key.C, ctrl = true), other["Copy"])
    }

    @Test fun aClickSendsTheKeyToTheFieldAndAnEchoDoesNot() {
        val sent = mutableListOf<MenuShortcut>()
        edit(KeyOs.Apple, sent = sent).entries.filterIsInstance<MainMenuEntry.Action>().first { it.label == "Copy" }.onClick()
        assertEquals(listOf(MenuShortcut(Key.C, meta = true)), sent)
        sent.clear()
        edit(KeyOs.Apple, echo = true, sent = sent).entries.filterIsInstance<MainMenuEntry.Action>().first { it.label == "Paste" }.onClick()
        assertEquals(emptyList(), sent, "the field already pasted once; the menu's echo must not paste again")
    }
}
