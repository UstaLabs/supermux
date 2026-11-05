// macOS: what AWT can't say about the assistant overlay's windows (#62), said to AppKit through the
// Objective-C runtime: on every Space and over full-screen apps, above other windows, kept out of
// screenshots and screen sharing (its own picture of the screen included). And the two privacy
// permissions the overlay may ask for: Screen Recording (its glasses) and Input Monitoring (the
// hold-to-talk key).
package dev.supermux.desktop.overlay

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

internal object MacOverlayNative {
    private val objc by lazy { NativeLibrary.getInstance("objc") }
    private val graphics by lazy { NativeLibrary.getInstance("CoreGraphics") }
    private val system by lazy { NativeLibrary.getInstance("System") }
    private val msgSend by lazy { objc.getFunction("objc_msgSend") }

    private fun cls(name: String): Pointer = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
    private fun sel(name: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
    private fun ptr(target: Pointer, selector: String, vararg args: Any): Pointer? = msgSend.invokePointer(arrayOf(target, sel(selector), *args))
    private fun long(target: Pointer, selector: String, vararg args: Any): Long = msgSend.invokeLong(arrayOf(target, sel(selector), *args))
    private fun call(target: Pointer, selector: String, vararg args: Any) { msgSend.invokeVoid(arrayOf(target, sel(selector), *args)) }

    // NSWindowCollectionBehavior: on every Space, out of the window cycle, staying put in Mission
    // Control, and allowed over another app's full-screen window.
    private const val CAN_JOIN_ALL_SPACES = 1L shl 0
    private const val STATIONARY = 1L shl 4
    private const val IGNORES_CYCLE = 1L shl 6
    private const val FULL_SCREEN_AUXILIARY = 1L shl 8
    /** kCGStatusWindowLevel: above other apps' windows and the menu bar, below menus and alerts. */
    private const val STATUS_LEVEL = 25L
    private const val SHARING_NONE = 0L

    /** Work for AppKit's main thread (AppKit must not be touched from AWT's event thread). */
    private fun interface MainWork : Callback {
        fun invoke(context: Pointer?)
    }

    // Kept reachable while queued: a collected callback would crash when AppKit calls it.
    private val queued = java.util.Collections.synchronizedSet(mutableSetOf<MainWork>())

    private fun onMain(work: () -> Unit) {
        lateinit var job: MainWork
        job = MainWork {
            try {
                runCatching(work)
            } finally {
                queued.remove(job)
            }
        }
        queued += job
        val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
        system.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, job))
    }

    /** The NSWindow whose title is [title] (each overlay window has its own title, undecorated as it is). */
    private fun windowTitled(title: String): Pointer? {
        val app = ptr(cls("NSApplication"), "sharedApplication") ?: return null
        val windows = ptr(app, "windows") ?: return null
        val count = long(windows, "count")
        for (i in 0 until count) {
            val w = ptr(windows, "objectAtIndex:", i) ?: continue
            val t = ptr(w, "title")?.let { ptr(it, "UTF8String")?.getString(0, "UTF-8") }
            if (t == title) return w
        }
        return null
    }

    /**
     * Makes the overlay window titled [title] float over everything, on every Space and over full-screen
     * apps, stay when supermux is hidden, and stay out of screenshots and screen sharing. Asynchronous,
     * on AppKit's main thread; call once the window is showing.
     */
    fun floatEverywhere(title: String) = onMain {
        val w = windowTitled(title) ?: return@onMain
        call(w, "setCollectionBehavior:", CAN_JOIN_ALL_SPACES or STATIONARY or IGNORES_CYCLE or FULL_SCREEN_AUXILIARY)
        call(w, "setLevel:", STATUS_LEVEL)
        call(w, "setSharingType:", SHARING_NONE)
        call(w, "setCanHide:", false)
        call(w, "setHidesOnDeactivate:", false)
    }

    /** Screen Recording is allowed for supermux (its glasses). */
    fun canRecordScreen(): Boolean = runCatching { graphics.getFunction("CGPreflightScreenCaptureAccess").invokeInt(emptyArray()) != 0 }.getOrDefault(false)

    /** Asks for Screen Recording: macOS's prompt the first time, then its answer (true once allowed). */
    fun askToRecordScreen(): Boolean = runCatching { graphics.getFunction("CGRequestScreenCaptureAccess").invokeInt(emptyArray()) != 0 }.getOrDefault(false)

    /** Input Monitoring is allowed for supermux (the hold-to-talk key, from any app). */
    fun canListenToKeys(): Boolean = runCatching { graphics.getFunction("CGPreflightListenEventAccess").invokeInt(emptyArray()) != 0 }.getOrDefault(false)

    /** Asks for Input Monitoring: macOS's prompt the first time, then its answer. */
    fun askToListenToKeys(): Boolean = runCatching { graphics.getFunction("CGRequestListenEventAccess").invokeInt(emptyArray()) != 0 }.getOrDefault(false)

    /** Opens System Settings › Privacy & Security at [pane] ("ScreenCapture", "ListenEvent"). */
    fun openPrivacySettings(pane: String) {
        runCatching { ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_$pane").start() }
    }
}
