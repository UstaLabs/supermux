// Linux X11: the overlay's hold-to-talk combo from any app (#62), as a passive key grab on the root window.
// X11 asks no permission for it. A combo the window manager already holds (Alt+Space is the window menu on
// GNOME, KDE and Xfce) is refused, and said so. Its own XCB connection: a refused grab comes back as that
// request's error, with no process-wide X error handler to fight AWT's.
package dev.supermux.desktop.overlay

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/** Reports the combo [onPress]ed and [onRelease]d, on its own thread; [onRefused] when X says someone holds it. */
internal class X11HotKey(
    private val onPress: () -> Unit,
    private val onRelease: () -> Unit,
    private val onRefused: (KeyCombo) -> Unit,
) {
    private val xcb by lazy { NativeLibrary.getInstance("xcb") }
    private val x11 by lazy { NativeLibrary.getInstance("X11") }
    private val libc by lazy { NativeLibrary.getInstance("c") }
    @Volatile private var running = false
    private var thread: Thread? = null

    fun register(combo: KeyCombo) {
        unregister()
        running = true
        val ready = java.util.concurrent.CountDownLatch(1)
        thread = Thread({ loop(combo, ready) }, "overlay-talk-hotkey").apply {
            isDaemon = true
            start()
        }
        ready.await()
    }

    fun unregister() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    private fun loop(combo: KeyCombo, ready: java.util.concurrent.CountDownLatch) {
        // The root window and the key's code, from Xlib (neither can fail with an X error).
        val dpy = x11.getFunction("XOpenDisplay").invokePointer(arrayOf<Any?>(null))
        if (dpy == null) {
            ready.countDown()
            return
        }
        val root = x11.getFunction("XDefaultRootWindow").invokeLong(arrayOf(dpy))
        val keycode = x11.getFunction("XKeysymToKeycode").invokeInt(arrayOf(dpy, combo.key.toLong())) and 0xff
        x11.getFunction("XCloseDisplay").invokeInt(arrayOf(dpy))
        val conn = xcb.getFunction("xcb_connect").invokePointer(arrayOf<Any?>(null, null))
        if (conn == null || xcb.getFunction("xcb_connection_has_error").invokeInt(arrayOf(conn)) != 0 || keycode == 0) {
            ready.countDown()
            return
        }
        try {
            // The combo, and the same with CapsLock and NumLock on (X counts them as modifiers too).
            val variants = listOf(0, LOCK, MOD2, LOCK or MOD2).map { combo.modifiers or it }
            val refused = variants.any { grab(conn, root.toInt(), keycode, it) }
            xcb.getFunction("xcb_flush").invokeInt(arrayOf(conn))
            ready.countDown()
            if (refused) {
                System.err.println("[overlay] the hold-to-talk combo ${combo.label} was refused: another app or the window manager holds it")
                onRefused(combo)
                return
            }
            listen(conn, keycode)
        } finally {
            xcb.getFunction("xcb_disconnect").invokeVoid(arrayOf(conn))
        }
    }

    /** One passive grab, checked: true when X refused it. */
    private fun grab(conn: Pointer, root: Int, keycode: Int, modifiers: Int): Boolean {
        val cookie = xcb.getFunction("xcb_grab_key_checked")
            .invokeInt(arrayOf(conn, 1.toByte(), root, modifiers.toShort(), keycode.toByte(), GRAB_ASYNC.toByte(), GRAB_ASYNC.toByte()))
        val error = xcb.getFunction("xcb_request_check").invokePointer(arrayOf(conn, cookie)) ?: return false
        libc.getFunction("free").invokeVoid(arrayOf(error))
        return true
    }

    private fun listen(conn: Pointer, keycode: Int) {
        val fd = xcb.getFunction("xcb_get_file_descriptor").invokeInt(arrayOf(conn))
        val pollFd = Memory(8)
        var held = false
        while (running) {
            val event = xcb.getFunction("xcb_poll_for_event").invokePointer(arrayOf(conn))
            if (event == null) {
                if (xcb.getFunction("xcb_connection_has_error").invokeInt(arrayOf(conn)) != 0) return
                // Nothing yet: sleep on the connection until X writes, or check `running` again soon.
                pollFd.setInt(0, fd); pollFd.setShort(4, POLLIN); pollFd.setShort(6, 0)
                libc.getFunction("poll").invokeInt(arrayOf(pollFd, 1L, POLL_MS))
                continue
            }
            val type = event.getByte(0).toInt() and 0x7f
            val code = event.getByte(1).toInt() and 0xff
            val time = event.getInt(4)
            libc.getFunction("free").invokeVoid(arrayOf(event))
            if (code != keycode) continue
            when (type) {
                KEY_PRESS -> if (!held) {
                    held = true
                    onPress()
                }
                KEY_RELEASE -> if (held && !repeated(conn, keycode, time)) {
                    held = false
                    onRelease()
                }
            }
        }
    }

    /** Auto-repeat sends a release and a press with the same time: a press right behind this release means held. */
    private fun repeated(conn: Pointer, keycode: Int, time: Int): Boolean {
        val until = System.currentTimeMillis() + REPEAT_WINDOW_MS
        while (System.currentTimeMillis() < until) {
            val next = xcb.getFunction("xcb_poll_for_event").invokePointer(arrayOf(conn))
            if (next == null) {
                Thread.sleep(2)
                continue
            }
            val type = next.getByte(0).toInt() and 0x7f
            val code = next.getByte(1).toInt() and 0xff
            val at = next.getInt(4)
            libc.getFunction("free").invokeVoid(arrayOf(next))
            return type == KEY_PRESS && code == keycode && at == time
        }
        return false
    }

    private companion object {
        const val LOCK = 0x02 // LockMask (CapsLock)
        const val MOD2 = 0x10 // Mod2Mask (NumLock)
        const val GRAB_ASYNC = 1 // XCB_GRAB_MODE_ASYNC
        const val KEY_PRESS = 2
        const val KEY_RELEASE = 3
        const val POLLIN: Short = 0x0001
        const val POLL_MS = 250
        const val REPEAT_WINDOW_MS = 20L
    }
}
