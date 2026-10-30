package dev.supermux.webview.wintest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the screen, the surface and the scenarios share: the window and its skiko canvas, the Compose fields, and the
 * keyboard-focus hand-off between AWT and WebView2 (the risk this test is mostly about). UI-thread state unless noted.
 */
class AppState(val window: ComposeWindow, val web: WinWebView, val options: TestOptions, val scope: CoroutineScope) {
    var fieldA by mutableStateOf("")
    var fieldB by mutableStateOf("")
    var buttonClicks by mutableIntStateOf(0)
    val focusA = FocusRequester()
    val focusB = FocusRequester()
    val surfaceFocus = FocusRequester()
    var focusManager: FocusManager? = null

    /** skiko's canvas: Compose's root, a heavyweight AWT component with its own HWND. */
    @Volatile var canvas: Component? = null
        private set
    @Volatile var canvasHwnd = 0L
        private set
    /** Pixel rectangles in the window's (the Compose root's) coordinates, kept up to date by layout. */
    @Volatile var surfaceInWindow = BoundsDebouncer.Rect(0, 0, 0, 0)
    @Volatile var fieldAInWindow = BoundsDebouncer.Rect(0, 0, 0, 0)
    @Volatile var fieldBInWindow = BoundsDebouncer.Rect(0, 0, 0, 0)
    @Volatile var density = 1f
    @Volatile var surfaceHasComposeFocus = false
        private set
    var lastTabShift = false
    /** Timestamped (System.nanoTime) focus events from AWT and Compose, and the hand-off calls made. */
    val focusLog = ConcurrentLinkedQueue<String>()
    /** Every bounds change handed to the engine, with its System.nanoTime. */
    val appliedBounds = ConcurrentLinkedQueue<Pair<Long, BoundsDebouncer.Rect>>()
    private var pressGaveFocus = false

    /** Finds the canvas once the window shows, wires the focus listeners, and starts WebView2 on it. */
    suspend fun attach() {
        while (!window.isShowing) delay(20)
        val found = findCanvas(window) ?: window
        canvas = found
        canvasHwnd = WinNative.hwndOf(found)
        check(canvasHwnd != 0L) { "JAWT gave no HWND for ${found.javaClass.name}" }
        note("attach canvas=${found.javaClass.name} hwnd=${hex(canvasHwnd)}")
        window.addWindowListener(object : WindowAdapter() {
            override fun windowActivated(e: WindowEvent) {
                note("window activated")
                // Windows gives focus back to AWT's own focus owner; if the page had it, hand it back.
                if (surfaceHasComposeFocus) giveWebFocus(WinNative.FOCUS_PROGRAMMATIC)
            }

            override fun windowDeactivated(e: WindowEvent) = note("window deactivated")
        })
        window.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) = note("awt window gained focus")

            override fun windowLostFocus(e: WindowEvent) = note("awt window lost focus")
        })
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner") { e ->
            note("awt focus owner=${e.newValue?.javaClass?.simpleName}")
        }
        web.onMoveFocusRequested = { reason ->
            note("page asks to move focus out, reason=$reason")
            focusManager?.moveFocus(if (reason == WinNative.FOCUS_PREVIOUS) FocusDirection.Previous else FocusDirection.Next)
        }
        web.start(canvasHwnd)
    }

    fun applyBounds(bounds: BoundsDebouncer.Rect) {
        WinNative.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        appliedBounds += System.nanoTime() to bounds
    }

    /** A press on the surface: Compose focus to the surface, and keyboard focus into the page. */
    fun onSurfacePressed() {
        pressGaveFocus = true
        surfaceFocus.requestFocus()
        giveWebFocus(WinNative.FOCUS_PROGRAMMATIC)
    }

    fun onSurfaceFocusChanged(focused: Boolean) {
        val was = surfaceHasComposeFocus
        surfaceHasComposeFocus = focused
        note("compose surface focused=$focused")
        if (focused && !was) {
            // Focus that arrives by Tab enters the page at its first element (Shift+Tab: its last).
            if (pressGaveFocus) pressGaveFocus = false
            else giveWebFocus(if (lastTabShift) WinNative.FOCUS_PREVIOUS else WinNative.FOCUS_NEXT)
        }
        if (!focused && was) returnFocusToAwt()
    }

    /** MoveFocus into WebView2, after [TestOptions.focusDefer] trips through the AWT event queue. */
    fun giveWebFocus(reason: Int) {
        var hops = options.focusDefer
        fun go() {
            if (hops > 0) {
                hops--
                EventQueue.invokeLater { go() }
            } else {
                WinNative.moveFocus(reason)
                note("moveFocus reason=$reason")
            }
        }
        go()
    }

    /** Keyboard focus back to the canvas: AWT's own request, then (returnFocus=force) SetFocus through its input queue. */
    fun returnFocusToAwt() {
        val target = canvas ?: return
        target.requestFocusInWindow()
        note("returnFocus requestFocusInWindow")
        if (!options.forceFocusReturn) return
        scope.launch {
            delay(50)
            if (!WinNative.focusInfo().startsWith(hex(canvasHwnd) + "|")) {
                note("returnFocus forceFocus=${WinNative.forceFocus(canvasHwnd)}")
            }
        }
    }

    fun note(text: String) {
        focusLog += "${System.nanoTime()} $text"
    }

    private fun findCanvas(root: Component): Component? {
        if (root is Canvas) return root
        if (root is Container) for (child in root.components) findCanvas(child)?.let { return it }
        return null
    }
}

/** A handle as native/probes.cpp prints it: 0x plus lowercase hex. */
fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value)
