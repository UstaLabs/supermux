package dev.supermux.ui.keys

import dev.supermux.editor.core.KeyChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyResolverTest {
    private class Scope(override val opaque: Boolean = false, vararg commands: AppCommand) : ScopeView {
        private val ids = commands.map { it.id }.toSet()
        override fun handles(commandId: String) = commandId in ids
    }

    private val window = Scope(false, AppCommands.NewSession, AppCommands.NewTerminal, AppCommands.ToggleSidebar)
    private val terminal = Scope(opaque = true)

    @Test fun aWindowKeyWaitsForTheBubbleWhenNoOwnerHasTheFocus() {
        val cmdN = KeyChord("n", meta = true)
        assertNull(resolveKey(cmdN, listOf(window), KeyPhase.Preview, KeyOs.Apple))
        assertEquals("session.new", resolveKey(cmdN, listOf(window), KeyPhase.Bubble, KeyOs.Apple)?.binding?.command)
    }

    @Test fun onAppleEveryCmdKeyPassesAFocusedTerminal() {
        val hit = resolveKey(KeyChord("t", meta = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple)
        assertEquals("workspace.newTerminal", hit?.binding?.command)
        assertEquals(1, hit?.scopeIndex)
    }

    @Test fun offAppleAFocusedTerminalKeepsCtrlT() {
        assertNull(resolveKey(KeyChord("t", ctrl = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Other))
    }

    @Test fun offAppleCtrlShiftTPassesAFocusedTerminal() {
        val hit = resolveKey(KeyChord("t", ctrl = true, shift = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Other)
        assertEquals("workspace.newTerminal", hit?.binding?.command)
    }

    @Test fun onAppleCtrlTIsNeverAnAppKey() {
        val ctrlT = KeyChord("t", ctrl = true)
        assertNull(resolveKey(ctrlT, listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple))
        assertNull(resolveKey(ctrlT, listOf(window), KeyPhase.Bubble, KeyOs.Apple))
    }

    @Test fun theTerminalsOwnCmdKeysStayWithIt() {
        assertNull(resolveKey(KeyChord("c", meta = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple))
        assertNull(resolveKey(KeyChord("f", meta = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple))
    }

    @Test fun anAppFirstKeyRunsBeforeTheFocusedElement() {
        val composer = Scope(false, AppCommands.PasteImage)
        val hit = resolveKey(KeyChord("v", ctrl = true), listOf(composer, window), KeyPhase.Preview, KeyOs.Other)
        assertEquals("composer.pasteImage", hit?.binding?.command)
    }

    @Test fun theDeepestHandlerWins() {
        val keymap = Keymap(listOf(bind("Mod-k", AppCommands.FindInChat), bind("Mod-k", AppCommands.GoToFile)))
        val chat = Scope(false, AppCommands.FindInChat)
        val files = Scope(false, AppCommands.GoToFile)
        val ctrlK = KeyChord("k", ctrl = true)
        assertEquals("chat.find", resolveKey(ctrlK, listOf(chat, files), KeyPhase.Bubble, KeyOs.Other, keymap)?.binding?.command)
        assertEquals("files.goTo", resolveKey(ctrlK, listOf(files, chat), KeyPhase.Bubble, KeyOs.Other, keymap)?.binding?.command)
    }

    @Test fun aDeeperHandlerThatWaitsBlocksAShallowerAppFirstOne() {
        val keymap = Keymap(listOf(bind("Mod-k", AppCommands.FindInChat), bind("Mod-k", AppCommands.GoToFile, appFirst = true)))
        val chat = Scope(false, AppCommands.FindInChat)
        val outer = Scope(false, AppCommands.GoToFile)
        val ctrlK = KeyChord("k", ctrl = true)
        assertNull(resolveKey(ctrlK, listOf(chat, outer), KeyPhase.Preview, KeyOs.Other, keymap))
        assertEquals("chat.find", resolveKey(ctrlK, listOf(chat, outer), KeyPhase.Bubble, KeyOs.Other, keymap)?.binding?.command)
    }

    @Test fun noHandlerMeansNoHit() {
        assertNull(resolveKey(KeyChord("p", meta = true), listOf(window), KeyPhase.Bubble, KeyOs.Apple))
        assertNull(resolveKey(KeyChord("n", meta = true), emptyList(), KeyPhase.Bubble, KeyOs.Apple))
    }
}
