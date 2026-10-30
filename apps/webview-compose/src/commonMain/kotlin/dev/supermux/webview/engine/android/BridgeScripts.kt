package dev.supermux.webview.engine.android

import dev.supermux.webview.bridge.BridgeShim
import dev.supermux.webview.originOf
import dev.supermux.webview.schemeOf

// The Android engine's side of the page bridge, as pure functions (spec §7 "Bridge and hooks"). The engine injects
// one object, CHANNEL_NAME, with WebViewCompat.addWebMessageListener, and one document-start script, bridgeScript(),
// with WebViewCompat.addDocumentStartJavaScript, both limited to the lease's bridge origins. Never @JavascriptInterface.

/** The object WebView injects into the frames of bridge origins. Neutral: it names no app. */
internal const val CHANNEL_NAME: String = "__hostBridgeChannel"

/** The name of a lease's [index]th channel ([BridgeChannels]): [CHANNEL_NAME], then the same with 1, 2, … after it. */
internal fun channelName(index: Int): String = if (index == 0) CHANNEL_NAME else "$CHANNEL_NAME$index"

/**
 * The bridge's channels on one WebView: which listener to add when the bridge's origins change, and which shims to
 * inject. WebView can't change a listener's origins, and taking one away drops what every document that has its object
 * sends, the current one too, even when its origin stays allowed. So a listener stays for the WebView's life: origins a
 * change adds get a channel of their own, under the next name ([channelName]); origins a change takes away keep theirs,
 * and the engine drops what they send ([allowed]). The shims, which documents start with, follow the origins allowed
 * now. Main thread only.
 */
internal class BridgeChannels {
    /** A listener WebView injects as [name] into the frames of [rules]' origins. */
    class Channel(val name: String, val rules: Set<String>)

    private val channels = ArrayList<Channel>()

    /** The origin rules allowed now: none without JavaScript. */
    var allowed: Set<String> = emptySet()
        private set

    /** The rules allowed are [rules] from now on. The channel to add for those none covers yet, or null. */
    fun update(rules: Set<String>): Channel? {
        allowed = rules
        val covered = channels.flatMapTo(HashSet()) { it.rules }
        val missing = rules.filterTo(LinkedHashSet()) { it !in covered }
        if (missing.isEmpty()) return null
        return Channel(channelName(channels.size), missing).also { channels += it }
    }

    /** Each channel's shim now: the allowed origins it covers. A channel that covers none gets no shim. */
    fun shims(): List<Pair<Channel, Set<String>>> = channels.mapNotNull { channel ->
        val rules = channel.rules.filterTo(LinkedHashSet()) { it in allowed }
        if (rules.isEmpty()) null else channel to rules
    }

    /** A new WebView, with nothing injected yet. */
    fun clear() {
        channels.clear()
        allowed = emptySet()
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
 * - the bridge shim ([BridgeShim]), sending through [channel] with [token] in front of every message, so the engine can
 *   tell a document that began loading after the epoch started from one that didn't ([DocumentGate]);
 * - then a hook that hands the host's messages, which WebView delivers as `message` events on the channel object, to
 *   the shim's `__hostBridgeReceive`.
 * Both capture what they use before any page script runs, so a page can't redirect them later; only the closure that
 * sends holds the token.
 */
internal fun bridgeScript(token: String, channel: String = CHANNEL_NAME): String {
    require(TOKEN.matches(token)) { "a token is $TOKEN_LENGTH letters and digits" }
    val send = "(function (o, apply) { var pm = o && o.postMessage; " +
        "return function (s) { apply(pm, o, ['$token' + s]); }; })(window.$channel, Reflect.apply)"
    val hook = "(function (o) { var receive = window.__hostBridgeReceive; var add = o && o.addEventListener; " +
        "if (typeof receive !== 'function' || typeof add !== 'function') return; " +
        "add.call(o, 'message', function (e) { receive(e.data); }); })(window.$channel);"
    return BridgeShim.source(sendExpression = send) + "\n" + hook
}

/** A page message split into its token and its JSON, or null when it is too short to carry both. */
internal fun splitSigned(data: String): Pair<String, String>? =
    if (data.length <= TOKEN_LENGTH) null else data.substring(0, TOKEN_LENGTH) to data.substring(TOKEN_LENGTH)
