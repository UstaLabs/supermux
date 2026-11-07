// Whether anyone can see this computer's screen (card #86 part 2). A focused window on a locked
// screen, or behind sleeping displays, counted as someone reading the chat open there, and muted
// that chat's push on his phone. macOS and Windows are asked; Linux cannot tell and counts as seen.
package dev.supermux.desktop.platform

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** This computer's screen: locked, or its displays asleep. */
internal data class ScreenState(val locked: Boolean = false, val displaysAsleep: Boolean = false) {
    /** Whether anyone can be looking at it. */
    val seen: Boolean get() = !locked && !displaysAsleep
}

/** Reads the screen's state; null when this OS cannot tell. */
internal fun interface ScreenProbe {
    fun read(): ScreenState?
}

/**
 * Whether the screen is seen, read every [periodMs]. A probe that cannot tell, or that throws,
 * leaves the screen counted as seen: detection must never hide the desktop for good, and that is
 * how it behaved before this existed.
 */
internal class ScreenWatch(private val probe: ScreenProbe, private val periodMs: Long = 2_000) {
    private val _seen = MutableStateFlow(true)
    val seen: StateFlow<Boolean> = _seen.asStateFlow()

    fun pollOnce() {
        _seen.value = runCatching { probe.read() }.getOrNull()?.seen ?: true
    }

    /** Polls until cancelled, off the UI thread. */
    suspend fun run() = withContext(Dispatchers.IO) {
        while (isActive) {
            pollOnce()
            delay(periodMs)
        }
    }
}

/** The probe for [os]. */
internal fun screenProbe(os: DesktopOs = DesktopOs.current): ScreenProbe = when (os) {
    DesktopOs.MACOS -> ScreenProbe { MacScreenProbe.readOrThrow() }
    DesktopOs.WINDOWS -> ScreenProbe { WindowsScreenProbe.readOrThrow() }
    DesktopOs.LINUX -> ScreenProbe { null }
}

/** WTSSessionInfoEx's SessionFlags: WTS_SESSIONSTATE_LOCK (0), _UNLOCK (1); anything else (_UNKNOWN, -1) cannot tell. */
internal fun windowsSessionLocked(sessionFlags: Int): Boolean? = when (sessionFlags) {
    0 -> true
    1 -> false
    else -> null
}

/** GUID_CONSOLE_DISPLAY_STATE's data: 0 off, 1 on, 2 dimmed. A dimmed display still shows. */
internal fun windowsDisplayAsleep(state: Int): Boolean = state == 0

/** macOS: the login session's lock flag and the main display's sleep, both read on each poll. */
internal object MacScreenProbe {
    private const val CF_STRING_ENCODING_UTF8 = 0x08000100
    private val graphics by lazy { NativeLibrary.getInstance("CoreGraphics") }
    private val foundation by lazy { NativeLibrary.getInstance("CoreFoundation") }
    private val lockedKey: Pointer by lazy {
        foundation.getFunction("CFStringCreateWithCString")
            .invokePointer(arrayOf<Any?>(null, "CGSSessionScreenIsLocked", CF_STRING_ENCODING_UTF8))
    }

    /** Null without a window-server session (over SSH, say). */
    fun readOrThrow(): ScreenState? {
        val session = graphics.getFunction("CGSessionCopyCurrentDictionary").invokePointer(arrayOf()) ?: return null
        val locked = try {
            // The key is there only while the screen is locked.
            val value = foundation.getFunction("CFDictionaryGetValue").invokePointer(arrayOf(session, lockedKey))
            value != null && (foundation.getFunction("CFBooleanGetValue").invokeInt(arrayOf(value)) and 0xFF) != 0
        } finally {
            foundation.getFunction("CFRelease").invokeVoid(arrayOf(session))
        }
        val display = graphics.getFunction("CGMainDisplayID").invokeInt(arrayOf())
        val asleep = graphics.getFunction("CGDisplayIsAsleep").invokeInt(arrayOf(display)) != 0
        return ScreenState(locked = locked, displaysAsleep = asleep)
    }
}

/**
 * Windows: the session's lock flag, read on each poll, and the console display's state, which the
 * power manager pushes to a callback (Windows 8+; no window or message loop needed).
 */
internal object WindowsScreenProbe {
    private const val WTS_CURRENT_SESSION = -1
    private const val WTS_SESSION_INFO_EX = 25
    private const val DEVICE_NOTIFY_CALLBACK = 2
    private const val PBT_POWERSETTINGCHANGE = 0x8013
    private val wtsapi by lazy { NativeLibrary.getInstance("wtsapi32") }

    @Volatile private var displaysAsleep = false

    /** The callback and the memory it was registered with, held so the GC never frees them. */
    private var subscription: List<Any>? = null

    private fun interface PowerSettingCallback : Callback {
        fun invoke(context: Pointer?, type: Int, setting: Pointer?): Int
    }

    fun readOrThrow(): ScreenState {
        subscribeToDisplayState()
        return ScreenState(locked = sessionLocked() ?: false, displaysAsleep = displaysAsleep)
    }

    private fun sessionLocked(): Boolean? {
        val buffer = PointerByReference()
        val ok = wtsapi.getFunction("WTSQuerySessionInformationW")
            .invokeInt(arrayOf<Any?>(null, WTS_CURRENT_SESSION, WTS_SESSION_INFO_EX, buffer, IntByReference())) != 0
        val info = buffer.value
        if (!ok || info == null) return null
        try {
            // WTSINFOEXW: DWORD Level, then WTSINFOEX_LEVEL1_W
            // { ULONG SessionId; WTS_CONNECTSTATE_CLASS SessionState; LONG SessionFlags; ... }.
            return windowsSessionLocked(info.getInt(12))
        } finally {
            wtsapi.getFunction("WTSFreeMemory").invokeVoid(arrayOf(info))
        }
    }

    @Synchronized
    private fun subscribeToDisplayState() {
        if (subscription != null) return
        val callback = PowerSettingCallback { _, type, setting ->
            // POWERBROADCAST_SETTING: GUID PowerSetting (16 bytes), DWORD DataLength, UCHAR Data[].
            if (type == PBT_POWERSETTINGCHANGE && setting != null) {
                displaysAsleep = windowsDisplayAsleep(setting.getByte(20).toInt() and 0xFF)
            }
            0
        }
        // GUID_CONSOLE_DISPLAY_STATE {6FE69556-704A-47A0-8F24-C28D936FDA47}.
        val guid = Memory(16).apply {
            setInt(0, 0x6FE69556)
            setShort(4, 0x704A.toShort())
            setShort(6, 0x47A0.toShort())
            write(8, byteArrayOf(0x8F.toByte(), 0x24, 0xC2.toByte(), 0x8D.toByte(), 0x93.toByte(), 0x6F, 0xDA.toByte(), 0x47), 0, 8)
        }
        // DEVICE_NOTIFY_SUBSCRIBE_PARAMETERS { Callback, Context }.
        val params = Memory(Native.POINTER_SIZE * 2L).apply {
            setPointer(0, CallbackReference.getFunctionPointer(callback))
            setPointer(Native.POINTER_SIZE.toLong(), Pointer.NULL)
        }
        val handle = PointerByReference()
        NativeLibrary.getInstance("powrprof").getFunction("PowerSettingRegisterNotification")
            .invokeInt(arrayOf<Any?>(guid, DEVICE_NOTIFY_CALLBACK, params, handle))
        // Held whether or not it worked: one attempt, not one per poll.
        subscription = listOf(callback, guid, params, handle)
    }
}
