package dev.supermux.webview.engine.android

import dev.supermux.webview.WebError

/** From this WebView major (M139) the keyboard shrinks the visual viewport itself, given the IME inset. */
internal const val WEBVIEW_IME_VIEWPORT_MAJOR: Int = 139

/** The major version in a WebView package's versionName (`156.0.8078.47` → 156), or null. */
internal fun webViewMajor(versionName: String?): Int? = versionName?.substringBefore('.')?.toIntOrNull()

/**
 * How many px of a view a keyboard covers: the part of the view (bottom at [viewBottom], window px) below the
 * keyboard's top, which is [imeBottom] px above the bottom of a [windowHeight] px window. 0 when the view ends above it.
 */
internal fun imeOverlap(viewBottom: Int, windowHeight: Int, imeBottom: Int): Int =
    (viewBottom - (windowHeight - imeBottom)).coerceIn(0, maxOf(imeBottom, 0))

/** ComponentCallbacks2's TRIM_MEMORY_UI_HIDDEN: the app's UI went away. */
internal const val TRIM_UI_HIDDEN: Int = 20

/** ComponentCallbacks2's TRIM_MEMORY_BACKGROUND: the app's process is cached. */
internal const val TRIM_BACKGROUND: Int = 40

/**
 * Whether an `onTrimMemory` level means memory is short while the app runs (ComponentCallbacks2): RUNNING_LOW (10) and
 * RUNNING_CRITICAL (15). RUNNING_MODERATE (5) is too early to give anything up. The other levels say where the app's
 * process is, not that memory is short: UI_HIDDEN (20) that its UI went away, and BACKGROUND (40), MODERATE (60) and
 * COMPLETE (80) that it is cached ([isBackgroundTrim]). Android 14 sends little but UI_HIDDEN and BACKGROUND, at every
 * app switch: as pressure, each switch evicted views and dropped covers.
 */
internal fun isMemoryPressure(level: Int): Boolean = level == 10 || level == 15

/** Whether an `onTrimMemory` level says the app's process is cached (BACKGROUND, MODERATE, COMPLETE): a light trim. */
internal fun isBackgroundTrim(level: Int): Boolean = level >= TRIM_BACKGROUND

/**
 * What Android's memory signals ask of a runtime (spec §4): [pressure] when memory is short while the app runs (the
 * running levels, `onLowMemory`); a light [trim] once its process is cached, of what is quickest to make again, and
 * nothing of the views it shows; and [recovered] when the app comes back to the foreground after its UI went away, so a
 * pressure window doesn't greet the user with half the budget. Main thread only.
 */
internal class MemorySignals(
    private val pressure: () -> Unit,
    private val trim: () -> Unit,
    private val recovered: () -> Unit,
) {
    /** Whether the app's UI went away since it was last in the foreground. */
    private var hidden = false

    fun onTrimMemory(level: Int) {
        if (level >= TRIM_UI_HIDDEN) hidden = true
        when {
            isMemoryPressure(level) -> pressure()
            isBackgroundTrim(level) -> trim()
        }
    }

    fun onLowMemory() = pressure()

    /** One of the app's Activities started: the app is in the foreground again if its UI had gone away. */
    fun onActivityStarted() {
        if (!hidden) return
        hidden = false
        recovered()
    }
}

/**
 * The title to report for a page: WebView reports the URL, without its scheme, as the title of a page that has none,
 * and that is no title. Blank is none too.
 */
internal fun pageTitle(title: String?, url: String?): String? {
    if (title.isNullOrBlank()) return null
    if (url != null) {
        // platform-ok: url
        val bare = url.substringAfter("://").removeSuffix("/")
        // platform-ok: url
        if (title == url || title.removeSuffix("/") == bare) return null
    }
    return title
}

/** WebView's profile for a [dev.supermux.webview.WebProfile.Named] id; the prefix keeps it apart from the app's own. */
internal fun namedProfile(id: String): String = "wvc_named_$id"

/** The prefix of the profiles [dev.supermux.webview.WebProfile.Ephemeral] engines use, one each. */
internal const val EPHEMERAL_PROFILE_PREFIX: String = "wvc_ephemeral_"

internal fun ephemeralProfile(index: Int): String = "$EPHEMERAL_PROFILE_PREFIX$index"

/**
 * The [WebError] for a main-frame load that failed with WebViewClient's [errorCode] (its ERROR_* values): a failed TLS
 * handshake (-11, ERROR_FAILED_SSL_HANDSHAKE) is the certificate's, a Safe Browsing block (-16, ERROR_UNSAFE_RESOURCE)
 * the policy's, and the rest the network's.
 */
internal fun loadError(url: String?, errorCode: Int, description: String): WebError = when (errorCode) {
    -11 -> WebError.Certificate(url, description)
    -16 -> WebError.BlockedByPolicy(url)
    else -> WebError.Network(url, description)
}
