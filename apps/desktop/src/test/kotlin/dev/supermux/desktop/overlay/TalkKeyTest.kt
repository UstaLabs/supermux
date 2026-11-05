package dev.supermux.desktop.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TalkKeyTest {
    @Test fun theDefaultIsCommandShiftSpace() = assertEquals(TalkKey.COMMAND_SHIFT_SPACE, TalkKey.parse(null))

    @Test fun anUnknownChoiceFallsBackToTheDefault() = assertEquals(TalkKey.COMMAND_SHIFT_SPACE, TalkKey.parse("hyper+f5"))

    @Test fun aStoredChoiceIsKept() = assertEquals(TalkKey.RIGHT_OPTION, TalkKey.parse("RIGHT_OPTION"))

    // macOS (15+) doesn't deliver a hot key held with ⌥ or ⌥⇧ alone: each combo holds ⌘ or ⌃.
    @Test fun everyComboHoldsCommandOrControl() {
        for (k in TalkKey.entries.filter { it.combo }) {
            assertTrue(k.carbonModifiers and (TalkKey.CMD or TalkKey.CTRL) != 0, k.name)
        }
    }

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
