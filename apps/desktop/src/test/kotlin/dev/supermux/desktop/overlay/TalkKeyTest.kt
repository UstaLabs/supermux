package dev.supermux.desktop.overlay

import dev.supermux.desktop.platform.DesktopOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TalkKeyTest {
    // Two keys, one hand, no permission: delivered on macOS 26 (Ahmet's Mac, 2026-11-05, press and release).
    @Test fun theDefaultIsOptionSpace() = assertEquals(TalkKey.OPTION_SPACE, TalkKey.parse(null, DesktopOs.MACOS))

    // The same two keys on Windows.
    @Test fun onWindowsTheDefaultIsAltSpace() {
        assertEquals(TalkKey.OPTION_SPACE, TalkKey.parse(null, DesktopOs.WINDOWS))
        assertEquals("Alt+Space", TalkKey.OPTION_SPACE.on(DesktopOs.WINDOWS)?.label)
    }

    // Linux: Alt+Space is the window menu on GNOME, KDE and Xfce (X refuses the grab there).
    @Test fun onLinuxTheDefaultIsCtrlAltSpace() {
        assertEquals(TalkKey.CONTROL_ALT_SPACE, TalkKey.parse(null, DesktopOs.LINUX))
        assertEquals("Ctrl+Alt+Space", TalkKey.CONTROL_ALT_SPACE.on(DesktopOs.LINUX)?.label)
    }

    @Test fun anUnknownChoiceFallsBackToTheDefault() = assertEquals(TalkKey.OPTION_SPACE, TalkKey.parse("hyper+f5", DesktopOs.MACOS))

    @Test fun aStoredChoiceIsKept() = assertEquals(TalkKey.RIGHT_OPTION, TalkKey.parse("RIGHT_OPTION", DesktopOs.MACOS))

    @Test fun aChoiceThisOsDoesNotOfferFallsBackToTheDefault() {
        assertEquals(TalkKey.OPTION_SPACE, TalkKey.parse("RIGHT_OPTION", DesktopOs.WINDOWS))
        assertEquals(TalkKey.OPTION_SPACE, TalkKey.parse("CONTROL_ALT_SPACE", DesktopOs.MACOS))
    }

    @Test fun eachOsHasItsOwnMenu() {
        assertEquals(listOf("⌥Space", "⌘⇧Space", "⌃⇧Space", "⌃⌥⌘Space", "Right ⌥ alone"), TalkKey.offeredOn(DesktopOs.MACOS).map { it.on(DesktopOs.MACOS)!!.label })
        assertEquals(listOf("Alt+Space", "Ctrl+Shift+Space", "Ctrl+Alt+Space"), TalkKey.offeredOn(DesktopOs.WINDOWS).map { it.on(DesktopOs.WINDOWS)!!.label })
        assertEquals(listOf("Alt+Space", "Ctrl+Shift+Space", "Ctrl+Alt+Space"), TalkKey.offeredOn(DesktopOs.LINUX).map { it.on(DesktopOs.LINUX)!!.label })
        assertNull(TalkKey.RIGHT_OPTION.on(DesktopOs.LINUX))
    }

    @Test fun optionSpaceIsFirstInTheMenu() = assertEquals(TalkKey.OPTION_SPACE, TalkKey.offeredOn(DesktopOs.MACOS).first())

    // A bare key as a hot key would take that key from typing in every app.
    @Test fun everyComboHoldsAModifier() {
        for (os in DesktopOs.entries) for (k in TalkKey.offeredOn(os).filter { it.combo }) assertTrue(k.on(os)!!.modifiers != 0, "${k.name} on $os")
    }

    // ⌥ (Alt) with a letter types a character, and macOS 15+ may hold such hot keys back: ⌥ alone only with Space.
    @Test fun optionOrAltAloneGoesOnlyWithSpace() {
        for (k in TalkKey.offeredOn(DesktopOs.MACOS).filter { it.combo && it.on(DesktopOs.MACOS)!!.modifiers == TalkKey.MAC_OPTION }) {
            assertEquals(TalkKey.MAC_SPACE_KEY, k.on(DesktopOs.MACOS)!!.key, k.name)
        }
        for (k in TalkKey.offeredOn(DesktopOs.WINDOWS).filter { it.on(DesktopOs.WINDOWS)!!.modifiers == TalkKey.WIN_ALT_MOD }) {
            assertEquals(TalkKey.WIN_SPACE_KEY, k.on(DesktopOs.WINDOWS)!!.key, k.name)
        }
    }

    // ⌘Space Spotlight, ⌃Space / ⌃⌥Space input sources, ⌃⌘Space Emoji & Symbols, ⌥⌘Space Finder search.
    @Test fun noComboIsOneOfMacOsOwnSpaceShortcuts() {
        val m = TalkKey
        val taken = setOf(m.MAC_COMMAND, m.MAC_CONTROL, m.MAC_CONTROL or m.MAC_OPTION, m.MAC_CONTROL or m.MAC_COMMAND, m.MAC_OPTION or m.MAC_COMMAND)
        for (k in TalkKey.offeredOn(DesktopOs.MACOS).filter { it.combo && it.on(DesktopOs.MACOS)!!.key == m.MAC_SPACE_KEY }) {
            assertTrue(k.on(DesktopOs.MACOS)!!.modifiers !in taken, k.name)
        }
    }

    // Win+Space switches the keyboard layout (and Win+Shift+Space switches it back); Ctrl+Space toggles a CJK IME.
    @Test fun noComboIsOneOfWindowsOwnSpaceShortcuts() {
        for (k in TalkKey.offeredOn(DesktopOs.WINDOWS)) {
            val c = k.on(DesktopOs.WINDOWS)!!
            assertTrue(c.modifiers and TalkKey.WIN_WINDOWS_MOD == 0, "${k.name} uses the Windows key")
            assertTrue(c.modifiers != TalkKey.WIN_CTRL_MOD, "${k.name} is Ctrl+Space")
        }
    }

    // Super+Space switches input sources on GNOME (and Super+Shift+Space switches back).
    @Test fun noLinuxComboUsesSuper() {
        for (k in TalkKey.offeredOn(DesktopOs.LINUX)) assertTrue(k.on(DesktopOs.LINUX)!!.modifiers and TalkKey.X_SUPER_MOD == 0, k.name)
    }

    @Test fun onlyRightOptionAsksForInputMonitoring() =
        assertEquals(listOf(TalkKey.RIGHT_OPTION), TalkKey.entries.filter { it.needsInputMonitoring })
}
