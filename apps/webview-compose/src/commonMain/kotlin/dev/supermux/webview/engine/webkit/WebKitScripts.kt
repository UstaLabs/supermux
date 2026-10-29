package dev.supermux.webview.engine.webkit

import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeShim
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * The scripts a WebKit engine installs, and the one it delivers with (spec §7, §9; the engine contract's **Delivery**).
 *
 * - [shim] is the common [BridgeShim], installed in the **page** world: it is the page's own `window.hostBridge`.
 *   Its channel out is a `CustomEvent` named [outEvent] on `document`; host messages come back as [inEvent] events,
 *   which a receiver installed with it hands to `window.__hostBridgeReceive`. It installs only in the main frame of a
 *   document whose origin (`self.origin`, read at document start, before any page script) is in the list.
 * - [relay] runs in the engine's **isolated** world, in every main-frame document. Only that world holds the native
 *   message handler, [handlerName], so a page script can't talk to the engine except through the shim. It names each
 *   document with a token of its own, reports `start`, `paint` (two animation frames after the document has content),
 *   focus, and title and same-document URL changes, and forwards the shim's [outEvent]s if the document's origin is in
 *   the list ([RelayMessage]).
 * - [deliveryBody] is run by `callAsyncJavaScript` in the isolated world, with the arguments `json`, `target` and
 *   `name`: it compares `self.origin`, which the page can't redefine in that world, with the target origin when the
 *   message arrives, and only then dispatches it to the shim.
 *
 * The event names are random per engine, so other scripts in the page can't listen to the shim's channel or forge
 * the host's messages without knowing them. Origins are given as the page itself sees them ([WebKitUrls.pageOrigins]).
 */
internal class WebKitScripts(val handlerName: String, val outEvent: String, val inEvent: String) {
    /**
     * The relay, for documents whose origins are [pageOrigins]. [mainFrame] is a JavaScript expression that is true in
     * the main frame: the relay runs only there (a test runs it in an iframe).
     */
    fun relay(pageOrigins: Set<String>, mainFrame: String = MAIN_FRAME): String = RELAY
        .replace("__MAIN_FRAME__", mainFrame)
        .replace("__HANDLER__", jsString(handlerName))
        .replace("__OUT__", jsString(outEvent))
        .replace("__ASK__", jsString(askEvent))
        .replace("__UP__", jsString(upEvent))
        .replace("__ORIGINS__", jsArray(pageOrigins))
        .replace("__MAX__", BridgeCodec.DEFAULT_MAX_REPLY_CHARS.toString())

    /** The shim and its receiver, for documents whose origins are [pageOrigins], in the frame [mainFrame] names. */
    fun shim(pageOrigins: Set<String>, mainFrame: String = MAIN_FRAME): String = BridgeShim.source(
        sendExpression = SENDER.replace("__OUT__", jsString(outEvent)).replace("__ASK__", jsString(askEvent)).replace("__UP__", jsString(upEvent)),
        mainFrameExpression = "($mainFrame) && ${jsArray(pageOrigins)}.indexOf(self.origin) >= 0",
    ) + "\n" + RECEIVER.replace("__IN__", jsString(inEvent))

    /** The shim asks whether the relay listens (the relay cancels it); and the relay says it does, when it starts. */
    private val askEvent: String get() = "$outEvent-ask"
    private val upEvent: String get() = "$outEvent-up"

    /** The delivery script's body; `callAsyncJavaScript` passes `json`, `target` and `name` as its arguments. */
    val deliveryBody: String =
        "if (self.origin !== target) return false; document.dispatchEvent(new CustomEvent(name, { detail: json })); return true;"

    companion object {
        private const val MAIN_FRAME = "window === window.top"

        /** Scripts with event names of their own, from [random]. */
        fun random(random: Random = Random): WebKitScripts {
            val token = (1..16).joinToString("") { "0123456789abcdef"[random.nextInt(16)].toString() }
            return WebKitScripts(handlerName = "webview", outEvent = "webview-out-$token", inEvent = "webview-in-$token")
        }

        private fun jsString(text: String): String = JsonPrimitive(text).toString()

        private fun jsArray(items: Set<String>): String = JsonArray(items.sorted().map(::JsonPrimitive)).toString()

        private val RELAY = """
(function () {
  if (!(__MAIN_FRAME__)) return;
  var handlers = window.webkit && window.webkit.messageHandlers;
  var handler = handlers && handlers[__HANDLER__];
  if (!handler) return;
  var post = handler.postMessage.bind(handler);
  var origins = __ORIGINS__, MAX = __MAX__, hex = '0123456789abcdef';
  function token() {
    var a = new Uint8Array(8), s = '';
    crypto.getRandomValues(a);
    for (var i = 0; i < 8; i++) s += hex.charAt(a[i] >> 4) + hex.charAt(a[i] & 15);
    return s;
  }
  var doc = token();
  function send(kind) { try { post(doc + ' ' + kind); } catch (e) {} }
  function painted() { requestAnimationFrame(function () { requestAnimationFrame(function () { send('paint'); }); }); }
  send('start');
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', painted); else painted();
  window.addEventListener('focus', function () { send('focus'); });
  window.addEventListener('blur', function () { send('blur'); });
  // Title and same-document URL changes, which Kotlin/Native can't key-value observe: a hint, and WebKit's own value is
  // read. pushState fires no event, so a timer looks too, slowed down by WebKit while the page is hidden.
  var lastHref = location.href, lastTitle = document.title;
  function look() {
    if (location.href !== lastHref) { lastHref = location.href; send('url'); }
    if (document.title !== lastTitle) { lastTitle = document.title; send('title'); }
  }
  window.addEventListener('popstate', look);
  window.addEventListener('hashchange', look);
  window.addEventListener('load', look);
  document.addEventListener('DOMContentLoaded', look);
  setInterval(look, 250);
  window.addEventListener('pageshow', function (e) {
    if (!e.persisted) return;
    doc = token();
    send('start');
    painted();
  });
  if (origins.indexOf(self.origin) < 0) return;
  document.addEventListener(__ASK__, function (e) { e.preventDefault(); });
  document.addEventListener(__OUT__, function (e) {
    var text = e.detail;
    if (typeof text === 'string' && text.length <= MAX) send('msg ' + text);
  });
  // WebKit may run the page world's scripts first: the shim then keeps what it said (its announcement) until now.
  document.dispatchEvent(new CustomEvent(__UP__));
})();
""".trim()

        /**
         * The shim's channel out. WebKit doesn't promise which world's document-start scripts run first (seen both ways
         * in the simulator), so until the relay is known to listen (it cancels the ask event, or announces itself with
         * the up event) the sender keeps the shim's first messages, at most 64, and hands them over in order.
         */
        private val SENDER = """
(function () {
  var d = document, fire = d.dispatchEvent.bind(d), listen = d.addEventListener.bind(d), E = CustomEvent;
  var up = false, waiting = [];
  listen(__UP__, function () {
    up = true;
    var w = waiting;
    waiting = [];
    for (var i = 0; i < w.length; i++) fire(new E(__OUT__, { detail: w[i] }));
  });
  return function (s) {
    if (!up) up = !fire(new E(__ASK__, { cancelable: true }));
    if (up) fire(new E(__OUT__, { detail: s })); else if (waiting.length < 64) waiting.push(s);
  };
})()
""".trim()

        private val RECEIVER = """
(function () {
  var receive = window.__hostBridgeReceive;
  if (typeof receive !== 'function') return;
  document.addEventListener(__IN__, function (e) {
    var text = e.detail;
    if (typeof text === 'string') receive(text);
  });
})();
""".trim()
    }
}
