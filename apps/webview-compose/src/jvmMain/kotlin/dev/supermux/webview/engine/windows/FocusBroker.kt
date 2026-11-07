package dev.supermux.webview.engine.windows

/**
 * The keyboard's hand-off between AWT/Compose and WebView2's window (W4, D15). Pure: the engine feeds it Compose's focus,
 * WebView2's focus, presses, Tabs, window activations and AWT's focus owner; it calls [moveFocus] (WebView2's MoveFocus)
 * and [returnToAwt] (the canvas's `requestFocusInWindow`). Main thread.
 */
internal class FocusBroker(
    private val moveFocus: (reason: Int) -> Unit,
    private val returnToAwt: () -> Unit,
    private val now: () -> Long = System::nanoTime,
) {
    private var composeFocused = false
    private var pageFocused = false
    private var pressing = false
    private var reactivating = false
    private var tabShift = false
    private var tabAt = Long.MIN_VALUE / 2

    /** A press on the surface, before Compose's focus request: AWT has just taken native focus, so the keyboard goes back in (W11). */
    fun onPress() {
        pressing = true
        moveFocus(WinGlue.FOCUS_PROGRAMMATIC)
    }

    /** A Tab the process's KeyEventDispatcher saw ([shift]: Shift+Tab), at [at] nanoseconds. */
    fun lastTab(shift: Boolean, at: Long) {
        tabShift = shift
        tabAt = at
    }

    fun onComposeFocus(focused: Boolean) {
        val was = composeFocused
        composeFocused = focused
        if (focused && !was) {
            if (pressing) {
                pressing = false   // the press moved it already
            } else if (!pageFocused) {
                val byTab = now() - tabAt <= WinDefaults.TAB_WINDOW_NANOS
                moveFocus(if (!byTab) WinGlue.FOCUS_PROGRAMMATIC else if (tabShift) WinGlue.FOCUS_PREVIOUS else WinGlue.FOCUS_NEXT)
            }
        }
        if (!focused && was) {
            pressing = false
            reactivating = false
            returnToAwt()
        }
    }

    fun onPageFocus(focused: Boolean) {
        pageFocused = focused
    }

    /** The window came back: AWT restores its own focus owner after this, so MoveFocus waits for it (F5). */
    fun onWindowActivated() {
        if (composeFocused) reactivating = true
    }

    fun onAwtFocusOwner(isCanvas: Boolean) {
        if (!reactivating || !isCanvas) return
        reactivating = false
        if (composeFocused) moveFocus(WinGlue.FOCUS_PROGRAMMATIC)
    }

    /**
     * What the engine reports as the page's focus (EngineEvent.FocusChanged): WebView2's, but a window that merely went
     * inactive (Alt+Tab) keeps a Compose-focused page focused for the runtime.
     */
    fun reportedFocus(windowActive: Boolean, pageFocused: Boolean): Boolean = pageFocused || (!windowActive && composeFocused)
}
