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
