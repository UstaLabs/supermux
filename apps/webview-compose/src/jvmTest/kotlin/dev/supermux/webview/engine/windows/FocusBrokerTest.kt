package dev.supermux.webview.engine.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusBrokerTest {
    private val moves = mutableListOf<Int>()
    private var returned = 0
    private var now = 0L
    private val broker = FocusBroker(moveFocus = { moves += it }, returnToAwt = { returned++ }, now = { now })

    @Test fun everyPressMovesTheKeyboardIn() {
        broker.onPress()
        broker.onComposeFocus(true)
        assertEquals(listOf(WinGlue.FOCUS_PROGRAMMATIC), moves, "one MoveFocus: the press's, not another for Compose's focus")
        broker.onPageFocus(true)
        broker.onPress()
        // AWT took native focus with the press, whatever WebView2 said before it (W11): the keyboard goes back in.
        assertEquals(listOf(WinGlue.FOCUS_PROGRAMMATIC, WinGlue.FOCUS_PROGRAMMATIC), moves)
    }

    @Test fun tabEntersAtTheFirstElementAndShiftTabAtTheLast() {
        broker.lastTab(shift = false, at = 0)
        now = 50_000_000
        broker.onComposeFocus(true)
        broker.onComposeFocus(false)
        broker.onPageFocus(false)
        broker.lastTab(shift = true, at = now)
        broker.onComposeFocus(true)
        assertEquals(listOf(WinGlue.FOCUS_NEXT, WinGlue.FOCUS_PREVIOUS), moves)
    }

    @Test fun focusThatArrivesLongAfterATabIsProgrammatic() {
        broker.lastTab(shift = false, at = 0)
        now = WinDefaults.TAB_WINDOW_NANOS + 1
        broker.onComposeFocus(true)
        assertEquals(listOf(WinGlue.FOCUS_PROGRAMMATIC), moves)
    }

    @Test fun composeFocusLeavingGivesTheKeysBackToAwt() {
        broker.onComposeFocus(true)
        broker.onComposeFocus(false)
        assertEquals(1, returned)
    }

    @Test fun reactivationWaitsForAwtsFocusOwner() {
        broker.onComposeFocus(true)
        moves.clear()
        broker.onWindowActivated()
        assertEquals(emptyList(), moves, "not at windowActivated: AWT restores its owner after and takes the keys back (F5)")
        broker.onAwtFocusOwner(isCanvas = false)
        assertEquals(emptyList(), moves)
        broker.onAwtFocusOwner(isCanvas = true)
        assertEquals(listOf(WinGlue.FOCUS_PROGRAMMATIC), moves)
        broker.onAwtFocusOwner(isCanvas = true)
        assertEquals(1, moves.size, "once per activation")
    }

    @Test fun aDeactivationKeepsThePagesFocusForTheRuntime() {
        broker.onComposeFocus(true)
        broker.onPageFocus(true)
        assertEquals(true, broker.reportedFocus(windowActive = false, pageFocused = false), "Alt+Tab away: still the focused view")
        assertEquals(false, broker.reportedFocus(windowActive = true, pageFocused = false))
    }
}
