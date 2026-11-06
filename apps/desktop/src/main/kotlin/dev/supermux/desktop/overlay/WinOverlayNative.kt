// Windows: what AWT can't say about the assistant overlay's windows (#62), said to user32: on every virtual
// desktop and out of Alt+Tab (a tool window), above other windows, never taking focus, and kept out of
// screenshots and screen sharing (its own picture of the screen included).
package dev.supermux.desktop.overlay

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

internal object WinOverlayNative {
    private val user32 by lazy { NativeLibrary.getInstance("user32") }

    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TOPMOST = 0x00000008L
    private const val WS_EX_TOOLWINDOW = 0x00000080L
    private const val WS_EX_NOACTIVATE = 0x08000000L
    private const val WS_EX_APPWINDOW = 0x00040000L
    private val HWND_TOPMOST = Pointer(-1)
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
    /** WDA_EXCLUDEFROMCAPTURE (Windows 10 2004+): left out of every capture, as if not there. */
    private const val WDA_EXCLUDEFROMCAPTURE = 0x11

    /**
     * Makes [window] a tool window on every virtual desktop (and out of Alt+Tab and the taskbar), topmost,
     * never activated by a click unless [focusable], and left out of captures. Call once it is showing.
     */
    fun floatEverywhere(window: java.awt.Window, focusable: Boolean) {
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        val style = user32.getFunction("GetWindowLongPtrW").invokeLong(arrayOf(hwnd, GWL_EXSTYLE))
        var next = (style or WS_EX_TOOLWINDOW or WS_EX_TOPMOST) and WS_EX_APPWINDOW.inv()
        if (!focusable) next = next or WS_EX_NOACTIVATE
        if (next != style) user32.getFunction("SetWindowLongPtrW").invokeLong(arrayOf(hwnd, GWL_EXSTYLE, next))
        user32.getFunction("SetWindowPos").invokeInt(
            arrayOf(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE or SWP_FRAMECHANGED),
        )
        if (user32.getFunction("SetWindowDisplayAffinity").invokeInt(arrayOf(hwnd, WDA_EXCLUDEFROMCAPTURE)) == 0) {
            System.err.println("[overlay] Windows would not keep ${window.name} out of captures (error ${Native.getLastError()})")
        }
    }

    /** Logs how Windows sees [window] (diagnostics): its extended style, and whether it is showing. */
    fun describe(window: java.awt.Window, title: String) {
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        val style = user32.getFunction("GetWindowLongPtrW").invokeLong(arrayOf(hwnd, GWL_EXSTYLE))
        val visible = user32.getFunction("IsWindowVisible").invokeInt(arrayOf(hwnd)) != 0
        val affinity = com.sun.jna.Memory(4).also { user32.getFunction("GetWindowDisplayAffinity").invokeInt(arrayOf(hwnd, it)) }.getInt(0)
        println(
            "[OverlayWindow] $title: visible=$visible tool=${style and WS_EX_TOOLWINDOW != 0L} " +
                "topmost=${style and WS_EX_TOPMOST != 0L} noActivate=${style and WS_EX_NOACTIVATE != 0L} " +
                "outOfCaptures=${affinity == WDA_EXCLUDEFROMCAPTURE}",
        )
    }
}
