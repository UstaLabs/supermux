// macOS: the overlay's hold-to-talk combo from any app (#62), as a Carbon hot key (RegisterEventHotKey).
// Like Spotlight's and Raycast's, it needs no permission: macOS hands the app this one combination, and
// nothing else typed anywhere.
package dev.supermux.desktop.overlay

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

/** Reports the combo [onPress]ed and [onRelease]d. [register] again to change it; [unregister] when done. */
internal class MacHotKey(private val onPress: () -> Unit, private val onRelease: () -> Unit) {
    private val carbon = NativeLibrary.getInstance("Carbon")

    /** EventHotKeyID: who the hot key belongs to, passed by value. */
    @Structure.FieldOrder("signature", "id")
    class HotKeyId(@JvmField var signature: Int = 0, @JvmField var id: Int = 0) : Structure(), Structure.ByValue

    private fun interface Handler : Callback {
        fun invoke(callRef: Pointer?, event: Pointer?, userData: Pointer?): Int
    }

    // Kept in a field: Carbon calls it for as long as it is installed.
    private val handler = Handler { _, event, _ ->
        when (carbon.getFunction("GetEventKind").invokeInt(arrayOf(event))) {
            HOT_KEY_PRESSED -> onPress()
            HOT_KEY_RELEASED -> onRelease()
        }
        NO_ERR
    }

    private var handlerRef: Pointer? = null
    private var hotKeyRef: Pointer? = null

    /** Makes [keyCode] with [modifiers] (Carbon's bits) the combo, replacing any before it. */
    fun register(keyCode: Int, modifiers: Int) = MacOverlayNative.onMain {
        val target = carbon.getFunction("GetApplicationEventTarget").invokePointer(emptyArray())
        if (handlerRef == null) {
            // EventTypeSpec[2]: the keyboard class's hot-key pressed and released events.
            val specs = Memory(16).apply {
                setInt(0, KEYBOARD_CLASS); setInt(4, HOT_KEY_PRESSED)
                setInt(8, KEYBOARD_CLASS); setInt(12, HOT_KEY_RELEASED)
            }
            val out = PointerByReference()
            val status = carbon.getFunction("InstallEventHandler").invokeInt(arrayOf(target, handler, 2L, specs, null, out))
            if (status == NO_ERR) handlerRef = out.value
        }
        unregisterKey()
        val out = PointerByReference()
        val status = carbon.getFunction("RegisterEventHotKey")
            .invokeInt(arrayOf(keyCode, modifiers, HotKeyId(SIGNATURE, 1), target, 0, out))
        if (status == NO_ERR) hotKeyRef = out.value
        else System.err.println("[overlay] the hold-to-talk combo was refused (OSStatus $status); another app may hold it")
    }

    fun unregister() = MacOverlayNative.onMain {
        unregisterKey()
        handlerRef?.let { carbon.getFunction("RemoveEventHandler").invokeInt(arrayOf(it)) }
        handlerRef = null
    }

    private fun unregisterKey() {
        hotKeyRef?.let { carbon.getFunction("UnregisterEventHotKey").invokeInt(arrayOf(it)) }
        hotKeyRef = null
    }

    private companion object {
        const val NO_ERR = 0
        const val KEYBOARD_CLASS = 0x6B657962 // 'keyb'
        const val HOT_KEY_PRESSED = 5 // kEventHotKeyPressed
        const val HOT_KEY_RELEASED = 6 // kEventHotKeyReleased
        const val SIGNATURE = 0x534D5558 // 'SMUX'
    }
}
