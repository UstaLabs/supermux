package dev.supermux.ui.keys

import dev.supermux.editor.core.KeyChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyResolverTest {
    private class Scope(vararg commands: AppCommand, override val opaque: Boolean = false, private val kept: Set<KeyChord> = emptySet()) : ScopeView {
        private val ids = commands.map { it.id }.toSet()
        override fun handles(commandId: String) = commandId in ids
        override fun keeps(chord: KeyChord) = chord in kept
    }

    private val window = Scope(AppCommands.NewSession, AppCommands.NewTerminal, AppCommands.ToggleSidebar, AppCommands.FindInChat)
    private val terminal = Scope(opaque = true)

    @Test fun aWindowKeyWaitsForTheBubbleWhenNoOwnerHasTheFocus() {
        val cmdN = KeyChord("n", meta = true)
        assertNull(resolveKey(cmdN, listOf(window), KeyPhase.Preview, KeyOs.Apple))
        assertEquals("session.new", resolveKey(cmdN, listOf(window), KeyPhase.Bubble, KeyOs.Apple)?.binding?.commandId)
    }

    @Test fun onAppleEveryCmdKeyPassesAFocusedTerminal() {
        val hit = resolveKey(KeyChord("t", meta = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple)
        assertEquals("workspace.newTerminal", hit?.binding?.commandId)
        assertEquals(1, hit?.scopeIndex)
    }

    @Test fun offAppleAFocusedTerminalKeepsCtrlT() {
        assertNull(resolveKey(KeyChord("t", ctrl = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Other))
    }

    @Test fun offAppleCtrlShiftTPassesAFocusedTerminal() {
        val hit = resolveKey(KeyChord("t", ctrl = true, shift = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Other)
        assertEquals("workspace.newTerminal", hit?.binding?.commandId)
    }

    @Test fun onAppleCtrlTIsNeverAnAppKey() {
        val ctrlT = KeyChord("t", ctrl = true)
        assertNull(resolveKey(ctrlT, listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple))
        assertNull(resolveKey(ctrlT, listOf(window), KeyPhase.Bubble, KeyOs.Apple))
    }

    @Test fun theTerminalsOwnCmdKeysStayWithIt() {
        val cmdF = KeyChord("f", meta = true)
        val cmdC = KeyChord("c", meta = true)
        val terminal = Scope(opaque = true, kept = setOf(cmdF, cmdC))
        assertNull(resolveKey(cmdC, listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple))
        assertNull(resolveKey(cmdF, listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple), "the window's find in chat must not take the terminal's find")
    }

    @Test fun anAppFirstKeyRunsBeforeTheFocusedElement() {
        val composer = Scope(AppCommands.PasteImage)
        val hit = resolveKey(KeyChord("v", ctrl = true), listOf(composer, window), KeyPhase.Preview, KeyOs.Other)
        assertEquals("composer.pasteImage", hit?.binding?.commandId)
    }

    @Test fun aDeclinedKeyIsNotTriedAgainInTheBubble() {
        val composer = Scope(AppCommands.PasteImage)
        val ctrlV = KeyChord("v", ctrl = true)
        assertEquals("composer.pasteImage", resolveKey(ctrlV, listOf(composer, window), KeyPhase.Preview, KeyOs.Other)?.binding?.commandId)
        assertNull(resolveKey(ctrlV, listOf(composer, window), KeyPhase.Bubble, KeyOs.Other), "the preview already offered it to the handler")
    }

    @Test fun offAppleAFocusedTerminalKeepsAnAppFirstKey() {
        val window = Scope(AppCommands.SplitRight)
        val ctrlBackslash = KeyChord("\\", ctrl = true)
        assertNull(resolveKey(ctrlBackslash, listOf(terminal, window), KeyPhase.Preview, KeyOs.Other))
        // Reached only if the terminal leaves the key.
        assertEquals("workspace.splitRight", resolveKey(ctrlBackslash, listOf(terminal, window), KeyPhase.Bubble, KeyOs.Other)?.binding?.commandId)
    }

    @Test fun aKeyThatPassesTheTerminalRunsOnlyInPreview() {
        val cmdT = KeyChord("t", meta = true)
        assertEquals("workspace.newTerminal", resolveKey(cmdT, listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple)?.binding?.commandId)
        assertNull(resolveKey(cmdT, listOf(terminal, window), KeyPhase.Bubble, KeyOs.Apple))
    }

    @Test fun onAppleOverOwnerWithoutCmdDoesNotPass() {
        val keymap = Keymap(listOf(bind("Ctrl-k", AppCommands.GoToFile, overOwner = true)))
        val window = Scope(AppCommands.GoToFile)
        assertNull(resolveKey(KeyChord("k", ctrl = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple, keymap))
    }

    @Test fun theDeepestHandlerWins() {
        val keymap = Keymap(listOf(bind("Mod-k", AppCommands.FindInChat), bind("Mod-k", AppCommands.GoToFile)))
        val chat = Scope(AppCommands.FindInChat)
        val files = Scope(AppCommands.GoToFile)
        val ctrlK = KeyChord("k", ctrl = true)
        assertEquals("chat.find", resolveKey(ctrlK, listOf(chat, files), KeyPhase.Bubble, KeyOs.Other, keymap)?.binding?.commandId)
        assertEquals("files.goTo", resolveKey(ctrlK, listOf(files, chat), KeyPhase.Bubble, KeyOs.Other, keymap)?.binding?.commandId)
    }

    @Test fun aDeeperHandlerThatWaitsBlocksAShallowerAppFirstOne() {
        val keymap = Keymap(listOf(bind("Mod-k", AppCommands.FindInChat), bind("Mod-k", AppCommands.GoToFile, appFirst = true)))
        val chat = Scope(AppCommands.FindInChat)
        val outer = Scope(AppCommands.GoToFile)
        val ctrlK = KeyChord("k", ctrl = true)
        assertNull(resolveKey(ctrlK, listOf(chat, outer), KeyPhase.Preview, KeyOs.Other, keymap))
        assertEquals("chat.find", resolveKey(ctrlK, listOf(chat, outer), KeyPhase.Bubble, KeyOs.Other, keymap)?.binding?.commandId)
    }

    @Test fun noHandlerMeansNoHit() {
        assertNull(resolveKey(KeyChord("p", meta = true), listOf(window), KeyPhase.Bubble, KeyOs.Apple))
        assertNull(resolveKey(KeyChord("n", meta = true), emptyList(), KeyPhase.Bubble, KeyOs.Apple))
    }
}
