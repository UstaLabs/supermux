package dev.supermux.webview.engine.windows

/**
 * The Windows engine's glue (`native/windows`, `webview_windows.dll`): every call into it, and its three calls back.
 * - `page` is the engine's token: what the glue names the page by, from [createPage] until [release].
 * - Every call returns at once and runs later on the glue's UI thread, in the order made, except: [start] (waits up to
 *   15 s: never on the EDT), [runtimeVersion], [compareVersions], [hwndOf] and [rootOf] (the caller's thread), and
 *   [pullFrame] and [frameSeq] (the EDT's own copy, under the page's lock).
 * - [onEvent] is called on the glue's UI thread and its memory watcher; [decideNavigation] and [offerKey] on the UI
 *   thread, which waits for their answers. None may block: [offerKey] is bounded by `KeyRouter`.
 * - Lengths are pixels of the surface's canvas.
 */
internal object WinGlue {
    /** The ABI this library speaks: `WV_ABI_VERSION` in `native/windows/wv2.h`. Change both together. 2: [close]. */
    const val ABI: Int = 2

    // Events (wv2.h Ev).
    const val CREATED = 1
    const val ENVIRONMENT = 2
    const val NAV_STARTING = 3
    const val COMMITTED = 4
    const val SOURCE_CHANGED = 5
    const val HISTORY = 6
    const val TITLE = 7
    const val DOM_LOADED = 8
    const val COMPLETED = 9
    const val MESSAGE = 10
    const val RESULT = 11
    const val FRAME = 12
    const val FOCUS = 13
    const val MOVE_FOCUS = 14
    const val CURSOR = 15
    const val RESOURCE = 16
    const val DIALOG = 17
    const val PERMISSION = 18
    const val DOWNLOAD = 19
    const val NEW_WINDOW = 20
    const val PROCESS_FAILED = 21
    const val DEVTOOLS_EVENT = 22
    const val MEMORY = 23
    const val BROWSER_EXITED = 24
    const val SCRIPT_READY = 25
    const val LOG = 26

    // configure's flags.
    const val CONF_SCRIPTS = 1
    const val CONF_INSPECTABLE = 2
    const val CONF_DARK = 4
    const val CONF_AUTOPLAY = 8

    // go's what.
    const val GO_RELOAD = 0
    const val GO_STOP = 1
    const val GO_BACK = 2
    const val GO_FORWARD = 3

    // Navigation flags and verdicts (logic.h).
    const val NAV_MAIN_FRAME = 1
    const val NAV_REDIRECT = 2
    const val NAV_USER = 4
    const val ALLOW = 0
    const val REFUSE = 1
    const val OWN = 2
    const val REFUSE_OWN = 3

    // COREWEBVIEW2_NAVIGATION_KIND (W16); -1: not known.
    const val KIND_RELOAD = 0
    const val KIND_BACK_OR_FORWARD = 1
    const val KIND_NEW_DOCUMENT = 2

    // start's answers.
    const val START_OK = 0
    const val START_NO_CAPTURE = 1
    const val START_NO_DEVICE = 2
    const val START_NO_THREAD = 3
    const val START_NO_UPCALL = 4

    // COREWEBVIEW2_MOUSE_EVENT_KIND (the WM_* ids) and the MK_* keys.
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
    const val HORIZONTAL_WHEEL = 0x020E
    const val MOUSE_LEAVE = 0x02A3
    const val MK_LBUTTON = 0x1
    const val MK_RBUTTON = 0x2
    const val MK_SHIFT = 0x4
    const val MK_CONTROL = 0x8
    const val MK_MBUTTON = 0x10

    // COREWEBVIEW2_MOVE_FOCUS_REASON.
    const val FOCUS_PROGRAMMATIC = 0
    const val FOCUS_NEXT = 1
    const val FOCUS_PREVIOUS = 2

    // COREWEBVIEW2_PROCESS_FAILED_KIND (W16).
    const val FAILED_BROWSER = 0
    const val FAILED_RENDERER = 1
    const val FAILED_UNRESPONSIVE = 2
    const val FAILED_FRAME_RENDERER = 3
    const val FAILED_GPU = 6

    /** The glue lost its Direct3D device and couldn't make another (`kFailedNoDevice`): the page can't be drawn. */
    const val FAILED_NO_DEVICE = 100

    // COREWEBVIEW2_WEB_ERROR_STATUS: 1–5 certificate errors, 14 a cancelled navigation (W16).
    const val ERROR_CERTIFICATE_FIRST = 1
    const val ERROR_CERTIFICATE_LAST = 5
    const val ERROR_CANCELED = 14

    fun interface Sink {
        fun event(page: Long, type: Int, epoch: Int, id: Long, flags: Long, a: String?, b: String?, c: String?, data: ByteArray?)
    }

    @Volatile var sink: Sink? = null
    @Volatile var decider: ((page: Long, epoch: Int, flags: Int, url: String?) -> Int)? = null
    @Volatile var keyOffer: ((page: Long, vk: Int, scan: Int, flags: Int) -> Boolean)? = null

    /** The glue's events. Hands over, nothing more; nothing it throws may reach the glue's thread. */
    @JvmStatic
    fun onEvent(page: Long, type: Int, epoch: Int, id: Long, flags: Long, a: String?, b: String?, c: String?, data: ByteArray?) {
        try {
            sink?.event(page, type, epoch, id, flags, a, b, c, data)
        } catch (t: Throwable) {
            // The event is lost; the glue goes on.
        }
    }

    /** NavigationStarting's answer, on the glue's UI thread (D9). Anything that fails refuses. */
    @JvmStatic
    fun decideNavigation(page: Long, epoch: Int, flags: Int, url: String?): Int = try {
        decider?.invoke(page, epoch, flags, url) ?: REFUSE
    } catch (t: Throwable) {
        REFUSE
    }

    /** Whether the app took an accelerator key, on the glue's UI thread (D15). Anything that fails leaves it to the page. */
    @JvmStatic
    fun offerKey(page: Long, vk: Int, scan: Int, flags: Int): Boolean = try {
        keyOffer?.invoke(page, vk, scan, flags) ?: false
    } catch (t: Throwable) {
        false
    }

    // --- The process ---
    @JvmStatic external fun abiVersion(): Int
    @JvmStatic external fun start(): Int
    @JvmStatic external fun deviceName(): String
    @JvmStatic external fun runtimeVersion(): String?
    @JvmStatic external fun compareVersions(a: String, b: String): Int
    @JvmStatic external fun openEnvironment(request: Long, userDataFolder: String, browserArguments: String)
    @JvmStatic external fun hwndOf(component: java.awt.Component): Long
    @JvmStatic external fun rootOf(hwnd: Long): Long
    @JvmStatic external fun startMemoryWatch()

    // --- Pages ---
    @JvmStatic external fun createPage(page: Long, profile: String, inPrivate: Boolean, background: Int)
    /**
     * The engine crashed: the page's controller closes now, and the browser ends its renderer with it (a hung one too,
     * whatever WebView2's crash reporter does with it). The page stays known, its last frame pullable, until [release].
     */
    @JvmStatic external fun close(page: Long)
    @JvmStatic external fun release(page: Long)
    /** [userAgentSuffix] is appended to WebView2's own agent, which only the glue reads; null for none. */
    @JvmStatic external fun configure(page: Long, flags: Int, background: Int, userAgentSuffix: String?)
    @JvmStatic external fun setScript(page: Long, epoch: Int, secret: String, script: String)
    @JvmStatic external fun navigate(page: Long, epoch: Int, url: String, headers: String?)
    @JvmStatic external fun go(page: Long, epoch: Int, what: Int)
    @JvmStatic external fun execute(page: Long, request: Long, script: String)
    @JvmStatic external fun devtools(page: Long, request: Long, method: String, params: String)
    @JvmStatic external fun subscribe(page: Long, event: String)
    @JvmStatic external fun openDevTools(page: Long)
    @JvmStatic external fun clearData(page: Long, request: Long)
    @JvmStatic external fun serve(page: Long, host: String?, html: ByteArray?, floor: String?)
    @JvmStatic external fun respond(page: Long, request: Long, status: Int, headers: String?, body: ByteArray?)
    @JvmStatic external fun place(page: Long, frame: Long, canvas: Long, x: Int, y: Int, width: Int, height: Int, scale: Double)
    @JvmStatic external fun unplace(page: Long)
    @JvmStatic external fun parentMoved(page: Long)
    @JvmStatic external fun setVisible(page: Long, visible: Boolean)
    @JvmStatic external fun setMemoryTarget(page: Long, low: Boolean)
    @JvmStatic external fun pullFrame(page: Long, dst: Long, rowBytes: Int, width: Int, height: Int, full: Boolean, out: LongArray): Int
    @JvmStatic external fun frameSeq(page: Long): Long
    /**
     * The perf lab's frame times (Task 31), in nanoseconds, every page's, moved into [out]; their count. [kind] 0: the
     * capture thread's copy of each frame (arrival → published); 1: the EDT's pull of each. The first call turns the
     * timing on (before it, nothing is kept); nothing in the engine calls this.
     */
    @JvmStatic external fun frameTimes(kind: Int, out: LongArray): Int
    @JvmStatic external fun mouse(page: Long, kind: Int, keys: Int, data: Int, x: Int, y: Int)
    @JvmStatic external fun moveFocus(page: Long, reason: Int)
    @JvmStatic external fun answer(page: Long, question: Long, verdict: Int, text: String?)
}
