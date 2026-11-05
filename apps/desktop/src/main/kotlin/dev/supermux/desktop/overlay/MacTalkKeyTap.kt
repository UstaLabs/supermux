// macOS: the overlay's hold-to-talk key from any app (#62), through a listen-only Quartz event tap. It
// only reads keys going by (never changes or stops them) and needs Input Monitoring.
package dev.supermux.desktop.overlay

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Reports the right ⌥ key going [onKey] down and up, and [onOther] for any other key or modifier, from
 * whichever app has focus. [start] is false when macOS doesn't allow it (no Input Monitoring yet).
 */
internal class MacTalkKeyTap(private val onKey: (down: Boolean) -> Unit, private val onOther: () -> Unit) {
    private val graphics = NativeLibrary.getInstance("CoreGraphics")
    private val foundation = NativeLibrary.getInstance("CoreFoundation")

    private fun interface TapCallback : Callback {
        fun invoke(proxy: Pointer?, type: Int, event: Pointer?, refcon: Pointer?): Pointer?
    }

    private var tap: Pointer? = null
    private var loop: Pointer? = null
    private var thread: Thread? = null

    // Kept in a field: the tap calls it for as long as it lives.
    private val callback = TapCallback { _, type, event, _ ->
        when (type) {
            // macOS turns a slow tap off; turn it back on.
            DISABLED_BY_TIMEOUT, DISABLED_BY_USER_INPUT -> tap?.let { enable(it, true) }
            FLAGS_CHANGED -> if (event != null) {
                val code = graphics.getFunction("CGEventGetIntegerValueField").invokeLong(arrayOf(event, KEYCODE_FIELD))
                val flags = graphics.getFunction("CGEventGetFlags").invokeLong(arrayOf(event))
                if (code == RIGHT_OPTION) onKey(flags and RIGHT_ALT_FLAG != 0L) else onOther()
            }
            KEY_DOWN -> onOther()
        }
        event
    }

    fun start(): Boolean {
        if (thread != null) return true
        val mask = (1L shl KEY_DOWN) or (1L shl FLAGS_CHANGED)
        val created = graphics.getFunction("CGEventTapCreate")
            .invokePointer(arrayOf(SESSION_TAP, HEAD_INSERT, LISTEN_ONLY, mask, callback, null)) ?: return false
        tap = created
        val source = foundation.getFunction("CFMachPortCreateRunLoopSource").invokePointer(arrayOf(null, created, 0L))
        val commonModes = foundation.getGlobalVariableAddress("kCFRunLoopCommonModes").getPointer(0)
        thread = Thread({
            val current = foundation.getFunction("CFRunLoopGetCurrent").invokePointer(emptyArray())
            loop = current
            foundation.getFunction("CFRunLoopAddSource").invokeVoid(arrayOf(current, source, commonModes))
            enable(created, true)
            foundation.getFunction("CFRunLoopRun").invokeVoid(emptyArray())
        }, "overlay-talk-key").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        tap?.let { enable(it, false) }
        loop?.let { foundation.getFunction("CFRunLoopStop").invokeVoid(arrayOf(it)) }
        tap?.let { foundation.getFunction("CFMachPortInvalidate").invokeVoid(arrayOf(it)) }
        tap = null
        loop = null
        thread = null
    }

    private fun enable(tap: Pointer, on: Boolean) {
        graphics.getFunction("CGEventTapEnable").invokeVoid(arrayOf(tap, on))
    }

    private companion object {
        const val SESSION_TAP = 1 // kCGSessionEventTap
        const val HEAD_INSERT = 0 // kCGHeadInsertEventTap
        const val LISTEN_ONLY = 1 // kCGEventTapOptionListenOnly
        const val KEY_DOWN = 10 // kCGEventKeyDown
        const val FLAGS_CHANGED = 12 // kCGEventFlagsChanged
        const val DISABLED_BY_TIMEOUT = -2 // kCGEventTapDisabledByTimeout (0xFFFFFFFE)
        const val DISABLED_BY_USER_INPUT = -1 // kCGEventTapDisabledByUserInput (0xFFFFFFFF)
        const val KEYCODE_FIELD = 9 // kCGKeyboardEventKeycode
        const val RIGHT_OPTION = 61L // kVK_RightOption
        const val RIGHT_ALT_FLAG = 0x40L // NX_DEVICERALTKEYMASK
    }
}
