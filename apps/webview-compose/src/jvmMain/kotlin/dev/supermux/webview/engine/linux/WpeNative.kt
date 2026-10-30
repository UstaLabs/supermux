package dev.supermux.webview.engine.linux

/**
 * The Linux engine's JNI glue (native/linux, built into the WPE runtime as lib/libwebview-wpe.so). `JNI_OnLoad`
 * registers these natives on this class.
 * - Posted to the WebKit thread: everything but the calls below.
 * - [createView], [saveState] and [restoreState] wait for the WebKit thread, and fail rather than wait when called on it.
 * - [pullFrame], [drainFrames], [frameMode], [frameSeq], [version] and [setenv] run on the caller's thread.
 * - [onEvent] is called on the WebKit thread: [WpeHost] only queues it.
 */
internal object WpeNative {
    // Events (wv.h WV_EV_*).
    const val FRAME = 1
    const val LOAD = 2
    const val LOAD_FAILED = 3
    const val TITLE = 4
    const val URI = 5
    const val PROGRESS = 6
    const val HISTORY = 7
    const val POLICY = 8
    const val RESPONSE = 9
    const val RELAY = 10
    const val EVAL = 11
    const val CRASHED = 12
    const val SCHEME = 13
    const val IME_FOCUS = 14
    const val IME_CURSOR = 15
    const val CURSOR = 16
    const val DATA_CLEARED = 17
    const val DIALOG = 18
    const val PERMISSION = 19
    const val FILE_CHOOSER = 20
    const val NEW_WINDOW = 21
    const val RESPONSIVE = 22
    const val CLIPBOARD = 23
    const val OPTION_MENU = 24
    const val OPTION_MENU_CLOSED = 25

    // WV_OPTION_*.
    const val OPTION_ENABLED = 1
    const val OPTION_SELECTED = 2
    const val OPTION_GROUP_LABEL = 4
    const val OPTION_GROUP_CHILD = 8

    // WebKitScriptDialogType.
    const val DIALOG_ALERT = 0
    const val DIALOG_CONFIRM = 1
    const val DIALOG_PROMPT = 2
    const val DIALOG_BEFORE_UNLOAD = 3

    // WV_PERM_*.
    const val PERM_CAMERA = 1L
    const val PERM_MICROPHONE = 2L
    const val PERM_GEOLOCATION = 4L
    const val PERM_NOTIFICATIONS = 8L
    const val PERM_OTHER = 16L

    const val INIT_CPU = 1
    const val INIT_NO_SANDBOX = 2
    const val INIT_GLOBAL_CONTEXT = 4

    const val FAIL_CANCELLED = 1L
    const val FAIL_TLS = 2L
    const val FAIL_POLICY = 4L
    const val FAIL_CANNOT_SHOW = 8L

    const val POLICY_NEW_WINDOW = 1L
    const val POLICY_GESTURE = 1L shl 8
    const val POLICY_REDIRECT = 1L shl 9
    const val NAV_LINK = 0
    const val NAV_FORM = 1
    const val NAV_BACK_FORWARD = 2
    const val NAV_RELOAD = 3
    const val NAV_FORM_RESUBMIT = 4
    const val NAV_OTHER = 5

    const val RESPONSE_MAIN = 1L
    const val RESPONSE_CAN_SHOW = 2L
    const val RESPONSE_ATTACHMENT = 4L

    const val LOAD_STARTED = 0L
    const val LOAD_REDIRECTED = 1L
    const val LOAD_COMMITTED = 2L
    const val LOAD_FINISHED = 3L

    const val POINTER_MOVE = 0
    const val POINTER_DOWN = 1
    const val POINTER_UP = 2
    const val POINTER_ENTER = 3
    const val POINTER_LEAVE = 4

    const val GO_RELOAD = 0
    const val GO_STOP = 1
    const val GO_BACK = 2
    const val GO_FORWARD = 3

    const val IGNORE = 0
    const val USE = 1
    const val DOWNLOAD = 2

    const val IME_PREEDIT = 1
    const val IME_COMMIT = 2
    const val IME_FINISH = 3

    const val CONF_PAGE_SCRIPTS = 1
    const val CONF_INSPECTABLE = 2

    const val PROFILE_DEFAULT = 0
    const val PROFILE_NAMED = 1
    const val PROFILE_EPHEMERAL = 2
    const val LOAD_URI = 0
    const val LOAD_HTML = 1

    fun interface Sink {
        fun event(view: Long, kind: Int, a: Long, b: Long, s1: String?, s2: String?)
    }

    @Volatile
    var sink: Sink? = null

    @JvmStatic
    fun onEvent(view: Long, kind: Int, a: Long, b: Long, s1: String?, s2: String?) {
        try {
            sink?.event(view, kind, a, b, s1, s2)
        } catch (t: Throwable) {
            System.err.println("webview-wpe: event $kind for view $view failed: $t")
        }
    }

    /** [runtimeDir]: the runtime's folder, which WebKit mounts in its page sandbox; null mounts nothing. */
    @JvmStatic external fun init(flags: Int, dataDir: String, cacheDir: String, runtimeDir: String?): Int
    @JvmStatic external fun version(): String
    @JvmStatic external fun createView(profile: Int, profileDir: String?, mediaGesture: Boolean, width: Int, height: Int, scale: Double): Long
    @JvmStatic external fun destroyView(view: Long)
    @JvmStatic external fun registerScheme(scheme: String, secure: Boolean)
    @JvmStatic external fun configure(view: Long, relay: String?, shim: String?, world: String?, handler: String?, flags: Int, background: Int, userAgent: String?)
    @JvmStatic external fun setDark(dark: Boolean)
    @JvmStatic external fun load(view: Long, kind: Int, text: String, base: String?, headers: Array<String>?)
    @JvmStatic external fun navigate(view: Long, what: Int)
    @JvmStatic external fun evaluate(view: Long, request: Int, script: String, world: String?)
    @JvmStatic external fun callAsync(view: Long, request: Int, body: String, world: String?, args: Array<String>?)
    @JvmStatic external fun decide(view: Long, decision: Long, verdict: Int)
    @JvmStatic external fun saveState(view: Long): ByteArray?
    @JvmStatic external fun restoreState(view: Long, state: ByteArray): Boolean
    @JvmStatic external fun setSize(view: Long, width: Int, height: Int, scale: Double)
    @JvmStatic external fun setVisible(view: Long, visible: Boolean)
    @JvmStatic external fun setFocus(view: Long, focused: Boolean)
    @JvmStatic external fun pointer(view: Long, type: Int, x: Double, y: Double, button: Int, modifiers: Int, count: Int, timeMs: Long)
    @JvmStatic external fun scroll(view: Long, dx: Double, dy: Double, x: Double, y: Double, precise: Boolean, modifiers: Int, timeMs: Long)
    @JvmStatic external fun key(view: Long, down: Boolean, keyval: Int, keycode: Int, modifiers: Int, timeMs: Long)
    @JvmStatic external fun ime(view: Long, kind: Int, text: String?, cursor: Int)
    @JvmStatic external fun pullFrame(view: Long, dst: Long, rowBytes: Int, width: Int, height: Int, full: Boolean, out: LongArray): Int
    @JvmStatic external fun drainFrames(view: Long, out: LongArray): Int
    @JvmStatic external fun frameMode(view: Long, autoAck: Boolean, verify: Boolean, damageScale: Double)
    @JvmStatic external fun finishScheme(request: Long, status: Int, mime: String?, data: ByteArray?)
    @JvmStatic external fun terminate(view: Long)
    @JvmStatic external fun setenv(name: String, value: String?)
    @JvmStatic external fun frameSeq(view: Long): Long
    @JvmStatic external fun clearData(view: Long, request: Long)
    @JvmStatic external fun answer(view: Long, request: Long, allow: Boolean, text: String?, files: Array<String>?)

    /** What the system's clipboard holds, for the pages' pastes ([WpeClipboard]): its text, or null for none. */
    @JvmStatic external fun clipboard(text: String?)
}
