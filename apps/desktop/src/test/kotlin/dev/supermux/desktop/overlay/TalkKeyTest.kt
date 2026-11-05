package dev.supermux.desktop.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TalkKeyTest {
    // Two keys, one hand, no permission: delivered on macOS 26 (Ahmet's Mac, 2026-11-05, press and release).
    @Test fun theDefaultIsOptionSpace() = assertEquals(TalkKey.OPTION_SPACE, TalkKey.parse(null))

    @Test fun anUnknownChoiceFallsBackToTheDefault() = assertEquals(TalkKey.OPTION_SPACE, TalkKey.parse("hyper+f5"))

    @Test fun aStoredChoiceIsKept() = assertEquals(TalkKey.RIGHT_OPTION, TalkKey.parse("RIGHT_OPTION"))

    // A bare key as a hot key would take that key from typing in every app.
    @Test fun everyComboHoldsAModifier() {
        for (k in TalkKey.entries.filter { it.combo }) assertTrue(k.carbonModifiers != 0, k.name)
    }

    // ⌥ with a letter or digit types a character (and macOS 15+ may hold such hot keys back): only ⌥Space,
    // which was checked on the Mac, goes with ⌥ alone.
    @Test fun optionAloneGoesOnlyWithSpace() {
        for (k in TalkKey.entries.filter { it.combo && it.carbonModifiers == TalkKey.OPT }) assertEquals(TalkKey.SPACE, k.carbonKey, k.name)
    }

    @Test fun optionSpaceIsFirstInTheMenu() = assertEquals(TalkKey.OPTION_SPACE, TalkKey.entries.first())

    // ⌘Space Spotlight, ⌃Space / ⌃⌥Space input sources, ⌃⌘Space Emoji & Symbols, ⌥⌘Space Finder search.
    @Test fun noComboIsOneOfMacOsOwnSpaceShortcuts() {
        val taken = setOf(TalkKey.CMD, TalkKey.CTRL, TalkKey.CTRL or TalkKey.OPT, TalkKey.CTRL or TalkKey.CMD, TalkKey.OPT or TalkKey.CMD)
        for (k in TalkKey.entries.filter { it.combo && it.carbonKey == TalkKey.SPACE }) {
            assertTrue(k.carbonModifiers !in taken, k.name)
        }
    }

    @Test fun onlyRightOptionAsksForInputMonitoring() =
        assertEquals(listOf(TalkKey.RIGHT_OPTION), TalkKey.entries.filter { it.needsInputMonitoring })
}
