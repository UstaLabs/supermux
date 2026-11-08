package dev.supermux.webview.engine.windows

import java.awt.Component
import java.awt.EventQueue
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Accelerators to the app first (D15). On the glue's UI thread, [offer] asks the EDT to dispatch an AWT `KEY_PRESSED` to
 * the window's canvas, where Compose runs its `onPreviewKeyEvent` and `onKeyEvent` handlers; if one consumed it, the
 * app took the key (and gets its release), and WebView2 keeps it from the page. The UI thread waits
 * [WinDefaults.KEY_OFFER_MILLIS] at most: past it, the page has the key.
 */
internal class KeyRouter(
    private val target: () -> Component?,
    private val onEdt: (Runnable) -> Unit = EventQueue::invokeLater,
    private val dispatch: (Component, KeyEvent) -> Unit = { component, event -> component.dispatchEvent(event) },
    private val waitMillis: Long = WinDefaults.KEY_OFFER_MILLIS,
) {
    /** [flags]: 1 Ctrl, 2 Shift, 4 Alt, 8 Win, 16 a repeat (the glue's). True if the app took the key. Any thread. */
    fun offer(vk: Int, scan: Int, flags: Int): Boolean {
        val component = target() ?: return false
        val answer = CompletableFuture<Boolean>()
        onEdt { answer.complete(runCatching { press(component, vk, flags) }.getOrDefault(false)) }
        return try {
            answer.get(waitMillis, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
    }

    private fun press(component: Component, vk: Int, flags: Int): Boolean {
        val modifiers = (if (flags and 1 != 0) InputEvent.CTRL_DOWN_MASK else 0) or (if (flags and 2 != 0) InputEvent.SHIFT_DOWN_MASK else 0) or
            (if (flags and 4 != 0) InputEvent.ALT_DOWN_MASK else 0) or (if (flags and 8 != 0) InputEvent.META_DOWN_MASK else 0)
        val code = awtKeyCode(vk)
        val pressed = KeyEvent(component, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED)
        dispatch(component, pressed)
        if (!pressed.isConsumed) return false
        dispatch(component, KeyEvent(component, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED))
        return true
    }

    companion object {
        /** A Win32 virtual key as AWT's key code: the same for letters, digits, arrows and F1–F12; mapped otherwise. */
        fun awtKeyCode(vk: Int): Int = when (vk) {
            0x0D -> KeyEvent.VK_ENTER
            0x2D -> KeyEvent.VK_INSERT
            0x2E -> KeyEvent.VK_DELETE
            0xBA -> KeyEvent.VK_SEMICOLON
            0xBB -> KeyEvent.VK_EQUALS
            0xBC -> KeyEvent.VK_COMMA
            0xBD -> KeyEvent.VK_MINUS
            0xBE -> KeyEvent.VK_PERIOD
            0xBF -> KeyEvent.VK_SLASH
            0xC0 -> KeyEvent.VK_BACK_QUOTE
            0xDB -> KeyEvent.VK_OPEN_BRACKET
            0xDC -> KeyEvent.VK_BACK_SLASH
            0xDD -> KeyEvent.VK_CLOSE_BRACKET
            0xDE -> KeyEvent.VK_QUOTE
            in 0x7C..0x87 -> KeyEvent.VK_F13 + (vk - 0x7C)
            else -> vk
        }
    }
}

/**
 * The direction of the last Tab, for every engine (D15): a process-wide KeyEventDispatcher installed once, which only
 * looks (it never consumes).
 */
internal object TabWatcher {
    @Volatile var shift: Boolean = false
        private set
    @Volatile var at: Long = Long.MIN_VALUE / 2
        private set
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_TAB) {
                shift = e.isShiftDown
                at = System.nanoTime()
            }
            false
        }
    }
}
