// Windows: the overlay's hold-to-talk combo from any app (#62), as a RegisterHotKey hot key. Like PowerToys
// Run's Alt+Space it needs no permission. Windows reports only the press, so the release is read from the
// key's state while it is held, which asks for nothing either.
package dev.supermux.desktop.overlay

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Reports the combo [onPress]ed and [onRelease]d, on its own thread (a hot key belongs to the thread that
 * registered it, and arrives in that thread's message queue). [register] again to change it; [unregister]
 * when done. [onRefused] says the combo is someone else's (Windows knows, unlike macOS).
 */
internal class WinHotKey(
    private val onPress: () -> Unit,
    private val onRelease: () -> Unit,
    private val onRefused: (KeyCombo) -> Unit,
) {
    private val user32 = NativeLibrary.getInstance("user32")
    private val kernel32 = NativeLibrary.getInstance("kernel32")
    private var thread: Thread? = null
    @Volatile private var threadId = 0

    fun register(combo: KeyCombo) {
        unregister()
        val ready = java.util.concurrent.CountDownLatch(1)
        thread = Thread({ loop(combo, ready) }, "overlay-talk-hotkey").apply {
            isDaemon = true
            start()
        }
        ready.await()
    }

    fun unregister() {
        val id = threadId
        if (id != 0) user32.getFunction("PostThreadMessageW").invokeInt(arrayOf<Any>(id, WM_QUIT, 0L, 0L))
        thread?.join(1_000)
        thread = null
        threadId = 0
    }

    private fun loop(combo: KeyCombo, ready: java.util.concurrent.CountDownLatch) {
        threadId = kernel32.getFunction("GetCurrentThreadId").invokeInt(emptyArray())
        val ok = user32.getFunction("RegisterHotKey").invokeInt(arrayOf(null, HOT_KEY_ID, combo.modifiers or MOD_NOREPEAT, combo.key)) != 0
        if (!ok) {
            val error = Native.getLastError()
            System.err.println("[overlay] the hold-to-talk combo ${combo.label} was refused (error $error); another app may hold it")
            threadId = 0
            ready.countDown()
            onRefused(combo)
            return
        }
        ready.countDown()
        // MSG on 64-bit Windows: hwnd(8) message(4)+pad(4) wParam(8) lParam(8) time(4) pt(8) lPrivate(4) = 48 bytes.
        val msg = Memory(48)
        try {
            while (user32.getFunction("GetMessageW").invokeInt(arrayOf(msg, null, 0, 0)) > 0) {
                if (msg.getInt(8) != WM_HOTKEY) continue
                onPress()
                // Held: Windows says nothing more, so watch the key itself until it comes up.
                while (isDown(combo.key)) Thread.sleep(POLL_MS)
                onRelease()
            }
        } finally {
            user32.getFunction("UnregisterHotKey").invokeInt(arrayOf(null, HOT_KEY_ID))
        }
    }

    private fun isDown(virtualKey: Int): Boolean =
        user32.getFunction("GetAsyncKeyState").invokeInt(arrayOf(virtualKey)) and 0x8000 != 0

    private companion object {
        const val HOT_KEY_ID = 0x5355 // 'SU'
        const val MOD_NOREPEAT = 0x4000
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
        const val POLL_MS = 15L
    }
}
