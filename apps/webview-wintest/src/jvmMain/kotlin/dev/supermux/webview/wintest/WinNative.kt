package dev.supermux.webview.wintest

import java.awt.Component
import java.awt.Toolkit

/** A native event (native/common.h EventKind) as it reached the JVM. */
data class NativeEvent(val kind: Int, val a: Long, val b: Long, val text: String)

/** A cursor image: ARGB pixels row by row, top-down, with its hotspot. */
class CustomCursorImage(val width: Int, val height: Int, val hotX: Int, val hotY: Int, val argb: IntArray) {
    companion object {
        /** systemCursorImage's packing: width, height, hotX, hotY, then the pixels. */
        fun fromPacked(packed: IntArray): CustomCursorImage =
            CustomCursorImage(packed[0], packed[1], packed[2], packed[3], packed.copyOfRange(4, packed.size))
    }
}

/**
 * The JNI surface of native/wv2capture.dll (native/jni_bridge.cpp). Calls that touch WebView2 are posted to the native
 * UI thread and return at once; their results come back through [onEvent] on a native thread. The probes, the clocks,
 * [copyFrame] and [drainArrivals] run on the calling thread.
 */
object WinNative {
    // EventKind, native/common.h
    const val EV_READY = 1
    const val EV_NAV_COMPLETED = 2
    const val EV_FOCUS = 3
    const val EV_MOVE_FOCUS_REQUESTED = 4
    const val EV_CURSOR = 5
    const val EV_WEB_MESSAGE = 6
    const val EV_SCRIPT_RESULT = 7
    const val EV_ACCELERATOR = 8
    const val EV_PROCESS_FAILED = 9
    const val EV_LOG = 10
    const val EV_DOC_SCRIPT_ADDED = 11
    const val EV_CAPTURE_STARTED = 12

    // COREWEBVIEW2_MOUSE_EVENT_KIND: the WM_* message ids
    const val MOUSE_MOVE = 0x0200
    const val LEFT_DOWN = 0x0201
    const val LEFT_UP = 0x0202
    const val LEFT_DOUBLE = 0x0203
    const val RIGHT_DOWN = 0x0204
    const val RIGHT_UP = 0x0205
    const val RIGHT_DOUBLE = 0x0206
    const val MIDDLE_DOWN = 0x0207
    const val MIDDLE_UP = 0x0208
    const val MIDDLE_DOUBLE = 0x0209
    const val WHEEL = 0x020A
    const val MOUSE_LEAVE = 0x02A3

    // COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS: the MK_* flags
    const val MK_LBUTTON = 0x1
    const val MK_RBUTTON = 0x2
    const val MK_SHIFT = 0x4
    const val MK_CONTROL = 0x8
    const val MK_MBUTTON = 0x10

    // COREWEBVIEW2_POINTER_EVENT_KIND: the WM_POINTER* message ids; and POINTER_INPUT_TYPE
    const val POINTER_UPDATE = 0x0245
    const val POINTER_DOWN = 0x0246
    const val POINTER_UP = 0x0247
    const val POINTER_LEAVE = 0x024A
    const val PT_TOUCH = 2
    const val PT_PEN = 3

    // COREWEBVIEW2_MOVE_FOCUS_REASON
    const val FOCUS_PROGRAMMATIC = 0
    const val FOCUS_NEXT = 1
    const val FOCUS_PREVIOUS = 2

    @Volatile var onEventListener: (NativeEvent) -> Unit = {}
    @Volatile var onFrameListener: () -> Unit = {}
    @Volatile var onCursorListener: (CustomCursorImage) -> Unit = {}

    /** Loads the DLL and starts its UI thread. AWT and jawt.dll come first: the DLL links against jawt. */
    fun load(path: String) {
        Toolkit.getDefaultToolkit()
        System.loadLibrary("jawt")
        System.load(path)
        val result = nativeInit()
        check(result == 0) { "wv2capture nativeInit failed: $result" }
    }

    @JvmStatic private external fun nativeInit(): Int
    @JvmStatic external fun hwndOf(component: Component): Long
    @JvmStatic external fun start(
        canvasHwnd: Long,
        parentMode: Int,
        userDataFolder: String,
        browserArguments: String,
        dirtyRegions: Boolean,
        compensateOffset: Boolean,
    )
    @JvmStatic external fun setBounds(x: Int, y: Int, width: Int, height: Int)
    @JvmStatic external fun notifyParentMoved()
    @JvmStatic external fun setVisible(visible: Boolean)
    @JvmStatic external fun addDocumentScript(script: String)
    @JvmStatic external fun navigateToString(html: String)
    @JvmStatic external fun executeScript(requestId: Long, script: String)
    @JvmStatic external fun mouse(kind: Int, virtualKeys: Int, mouseData: Int, x: Int, y: Int)
    @JvmStatic external fun pointer(pointerKind: Int, eventKind: Int, pointerId: Int, x: Int, y: Int, pressure: Int)
    @JvmStatic external fun moveFocus(reason: Int)
    @JvmStatic external fun copyFrame(pixels: Long, rowBytes: Int, width: Int, height: Int, full: Boolean, out: LongArray): Int
    @JvmStatic external fun drainArrivals(out: LongArray): Int
    @JvmStatic external fun captureStats(out: LongArray)
    @JvmStatic external fun nowNanos(): Long
    @JvmStatic external fun epochMicros(): Long
    @JvmStatic external fun browserPid(): Long
    @JvmStatic external fun parentHwnd(): Long
    @JvmStatic external fun focusInfo(): String
    @JvmStatic external fun cursorInfo(): String
    @JvmStatic external fun standardCursorInfo(idc: Int): String
    @JvmStatic external fun systemCursorImage(idc: Int): IntArray?
    @JvmStatic external fun topLevelWindows(pid: Long): String
    @JvmStatic external fun windowTree(root: Long): String
    @JvmStatic external fun sendScan(scan: Int, down: Boolean, extended: Boolean)
    @JvmStatic external fun sendVk(vk: Int, down: Boolean)
    @JvmStatic external fun setInputLanguage(klid: String): String
    @JvmStatic external fun forceFocus(hwnd: Long): Boolean
    @JvmStatic external fun stop()

    // Called from native threads (jni_bridge.cpp); they must not block.
    @JvmStatic fun onFrameReady() = onFrameListener()

    @JvmStatic fun onEvent(kind: Int, a: Long, b: Long, text: String?) = onEventListener(NativeEvent(kind, a, b, text ?: ""))

    @JvmStatic fun onCustomCursor(width: Int, height: Int, hotX: Int, hotY: Int, argb: IntArray) =
        onCursorListener(CustomCursorImage(width, height, hotX, hotY, argb))
}
