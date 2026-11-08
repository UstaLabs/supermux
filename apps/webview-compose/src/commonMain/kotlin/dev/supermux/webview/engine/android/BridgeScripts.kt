package dev.supermux.webview.engine.android

import dev.supermux.webview.bridge.BridgeShim
import dev.supermux.webview.originOf
import dev.supermux.webview.schemeOf

// The Android engine's side of the page bridge, as pure functions (spec §7 "Bridge and hooks"). The engine injects
// one object, CHANNEL_NAME, with WebViewCompat.addWebMessageListener, and one document-start script, bridgeScript(),
// with WebViewCompat.addDocumentStartJavaScript, both limited to the lease's bridge origins. Never @JavascriptInterface.

/** The object WebView injects into the frames of bridge origins. Neutral: it names no app. */
internal const val CHANNEL_NAME: String = "__hostBridgeChannel"

/**
 * The bridge's one channel on a WebView: when to (re)add its listener, and which origins its shim is injected into.
 * - WebView can't change a listener's origins, and taking one away drops what every document that has its object sends,
 *   the current one too, even when its origin stays allowed. A second listener added to a WebView that had a document
 *   crashed WebView 156 natively (on the user's phone, 2026-10-30): there is only ever one.
 * - A change of origins or of JavaScript that the listener covers keeps it: only the shim, which documents start with,
 *   follows the origins allowed now ([shimRules], within the listener's), and the engine drops what origins taken off
 *   send ([allowed]).
 * - One it doesn't cover replaces it ([update]'s or [boundary]'s answer) only when no document can be using it: at once
 *   on a WebView that has never had a document, otherwise at the next boundary, as the document goes. Until then, the
 *   origins added get no shim.
 * Main thread only.
 */
internal class BridgeChannels {
    /** The origin rules the listener was added with; null while there is none. */
    var listenerRules: Set<String>? = null
        private set

    /** The origin rules allowed now: none without JavaScript. */
    var allowed: Set<String> = emptySet()
        private set

    /** Whether the listener doesn't cover what is allowed: it is replaced at the next boundary. */
    private var stale = false

    /**
     * The rules allowed are [rules] from now on. The rules to (re)add the listener with now, or null: only when it
     * doesn't cover them and no document can be using it ([idle]: the WebView has never had one).
     */
    fun update(rules: Set<String>, idle: Boolean): Set<String>? {
        allowed = rules
        stale = !(listenerRules?.containsAll(rules) ?: rules.isEmpty())
        return if (stale && idle) replaced() else null
    }

    /** A boundary: the document goes. The rules to (re)add the listener with now, if it doesn't cover what is allowed. */
    fun boundary(): Set<String>? = if (stale) replaced() else null

    private fun replaced(): Set<String> {
        stale = false
        listenerRules = allowed
        return allowed
    }

    /** The origins the shim is injected into now: those allowed that the listener covers. */
    fun shimRules(): Set<String> = listenerRules?.filterTo(LinkedHashSet()) { it in allowed } ?: emptySet()

    /** A new WebView, with nothing injected yet. */
    fun clear() {
        listenerRules = null
        allowed = emptySet()
        stale = false
    }
}

/** How many characters in front of every page message are the epoch's token ([bridgeScript]). */
internal const val TOKEN_LENGTH: Int = 24

private val TOKEN = Regex("^[A-Za-z0-9]{$TOKEN_LENGTH}$")

/**
 * WebView's allowed-origin rules for [bridgeOrigins]. Those are origins as browsers write them (WebSettings refuses
 * anything else), which is exactly the rules' `SCHEME://HOST[:PORT]` form; only http and https origins have one.
 */
internal fun allowedOriginRules(bridgeOrigins: Set<String>): Set<String> =
    bridgeOrigins.filterTo(LinkedHashSet()) { schemeOf(it) == "http" || schemeOf(it) == "https" }

/** The origin a page message came from, as the bridge compares origins: WebView's `sourceOrigin`, serialized again. */
internal fun messageOrigin(sourceOrigin: String): String = originOf(sourceOrigin)

/**
 * The document-start script of one epoch, which runs in the main frame of every document of a bridge origin:
 * - the bridge shim ([BridgeShim]), sending through [CHANNEL_NAME] with [token] in front of every message, so the
 *   engine can tell a document that began loading after the epoch started from one that didn't ([DocumentGate]);
 * - then a hook that hands the host's messages, which WebView delivers as `message` events on the channel object, to
 *   the shim's `__hostBridgeReceive`.
 * Both capture what they use before any page script runs, so a page can't redirect them later; only the closure that
 * sends holds the token.
 */
internal fun bridgeScript(token: String): String {
    require(TOKEN.matches(token)) { "a token is $TOKEN_LENGTH letters and digits" }
    val send = "(function (o, apply) { var pm = o && o.postMessage; " +
        "return function (s) { apply(pm, o, ['$token' + s]); }; })(window.$CHANNEL_NAME, Reflect.apply)"
    val hook = "(function (o) { var receive = window.__hostBridgeReceive; var add = o && o.addEventListener; " +
        "if (typeof receive !== 'function' || typeof add !== 'function') return; " +
        "add.call(o, 'message', function (e) { receive(e.data); }); })(window.$CHANNEL_NAME);"
    return BridgeShim.source(sendExpression = send) + "\n" + hook
}

/** A page message split into its token and its JSON, or null when it is too short to carry both. */
internal fun splitSigned(data: String): Pair<String, String>? =
    if (data.length <= TOKEN_LENGTH) null else data.substring(0, TOKEN_LENGTH) to data.substring(TOKEN_LENGTH)
