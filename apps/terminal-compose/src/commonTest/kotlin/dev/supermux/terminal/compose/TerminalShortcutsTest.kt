package dev.supermux.terminal.compose

import androidx.compose.ui.input.key.Key
import dev.supermux.terminal.TerminalSearchMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shortcut table ([terminalShortcutOf]) and the find state's stepping ([TerminalSearchState]). */
class TerminalShortcutsTest {
    private fun shortcut(
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        selection: Boolean = false,
    ) = terminalShortcutOf(key, ctrl, shift, alt, meta, selection)

    @Test fun cmdIsTheMacFamily() {
        assertEquals(TerminalShortcut.COPY, shortcut(Key.C, meta = true))
        assertEquals(TerminalShortcut.PASTE, shortcut(Key.V, meta = true))
        assertEquals(TerminalShortcut.SELECT_ALL, shortcut(Key.A, meta = true))
        assertEquals(TerminalShortcut.FIND, shortcut(Key.F, meta = true))
        assertEquals(TerminalShortcut.ZOOM_IN, shortcut(Key.Equals, meta = true))
        assertEquals(TerminalShortcut.ZOOM_OUT, shortcut(Key.Minus, meta = true))
        assertEquals(TerminalShortcut.ZOOM_RESET, shortcut(Key.Zero, meta = true))
    }

    @Test fun ctrlShiftIsTheLinuxAndWindowsFamily() {
        assertEquals(TerminalShortcut.COPY, shortcut(Key.C, ctrl = true, shift = true))
        assertEquals(TerminalShortcut.PASTE, shortcut(Key.V, ctrl = true, shift = true))
        assertEquals(TerminalShortcut.FIND, shortcut(Key.F, ctrl = true, shift = true))
        assertEquals(TerminalShortcut.PASTE, shortcut(Key.Insert, shift = true))
        assertEquals(TerminalShortcut.COPY, shortcut(Key.Insert, ctrl = true))
    }

    @Test fun plainCtrlLettersStayWithTheProgram() {
        // ^C interrupts, ^V is readline's quoted-insert, ^A is start-of-line, ^F moves forward.
        assertNull(shortcut(Key.C, ctrl = true))
        assertNull(shortcut(Key.V, ctrl = true))
        assertNull(shortcut(Key.A, ctrl = true))
        assertNull(shortcut(Key.F, ctrl = true))
    }

    @Test fun ctrlCCopiesOnlyWhileSomethingIsSelected() {
        assertEquals(TerminalShortcut.COPY, shortcut(Key.C, ctrl = true, selection = true))
        assertNull(shortcut(Key.C, ctrl = true, selection = false))
    }

    @Test fun altNeverMakesAShortcut() {
        // Alt+Cmd+C and friends belong to the program (and to Option-as-Meta users).
        assertNull(shortcut(Key.C, meta = true, alt = true))
        assertNull(shortcut(Key.V, ctrl = true, shift = true, alt = true))
        assertNull(shortcut(Key.Minus, ctrl = true, alt = true))
    }

    @Test fun ctrlZoomChordsAreTaken() {
        assertEquals(TerminalShortcut.ZOOM_IN, shortcut(Key.Equals, ctrl = true))
        assertEquals(TerminalShortcut.ZOOM_IN, shortcut(Key.Equals, ctrl = true, shift = true))
        assertEquals(TerminalShortcut.ZOOM_OUT, shortcut(Key.Minus, ctrl = true))
        assertEquals(TerminalShortcut.ZOOM_RESET, shortcut(Key.Zero, ctrl = true))
    }

    @Test fun ordinaryKeysAreNotShortcuts() {
        assertNull(shortcut(Key.C))
        assertNull(shortcut(Key.C, shift = true))
        assertNull(shortcut(Key.Insert))
    }

    // ------------------------------------------------------------------ find ----

    private fun match(row: Long) = TerminalSearchMatch(row, 0, 2)

    @Test fun aNewSearchStartsAtTheNewestMatchOnOrAboveTheScreenBottom() {
        val search = TerminalSearchState()
        search.onResult(listOf(match(1), match(5), match(9), match(40)), truncated = false, bottomRow = 20)
        assertEquals(2, search.current, "row 9 is the newest match at or above row 20")
        search.onResult(listOf(match(30), match(40)), truncated = false, bottomRow = 20)
        assertEquals(1, search.current, "nothing above the bottom: the newest match overall")
    }

    @Test fun previousWalksUpAndWraps() {
        val search = TerminalSearchState()
        search.onResult(listOf(match(1), match(5), match(9)), truncated = false, bottomRow = 100)
        search.previous()
        assertEquals(1, search.current)
        search.previous()
        search.previous()
        assertEquals(2, search.current, "wrapped from the oldest to the newest")
        search.next()
        assertEquals(0, search.current, "wrapped from the newest to the oldest")
    }

    @Test fun aRefreshKeepsTheCurrentMatchWhenItIsStillThere() {
        val search = TerminalSearchState()
        search.onResult(listOf(match(1), match(5), match(9)), truncated = false, bottomRow = 100)
        search.previous()
        assertEquals(match(5), search.currentMatch)
        search.onResult(listOf(match(0), match(1), match(5), match(9), match(12)), truncated = false, bottomRow = 100)
        assertEquals(match(5), search.currentMatch)
    }

    @Test fun clearingTheQueryDropsTheMatches() {
        val search = TerminalSearchState()
        search.open()
        search.updateQuery("x")
        search.onResult(listOf(match(1)), truncated = false, bottomRow = 100)
        search.updateQuery("")
        assertEquals(emptyList(), search.matches)
        assertEquals(-1, search.current)
    }

    @Test fun onlyTheMatchesOnTheAskedRowsAreReturned() {
        val all = listOf(match(1), match(3), match(3), match(7), match(10))
        assertEquals(listOf(match(3), match(3), match(7)), matchesOnRows(all, 2, 8))
        assertEquals(emptyList(), matchesOnRows(all, 11, 20))
        assertEquals(all, matchesOnRows(all, 0, 10))
    }
}
