package dev.supermux.webview.wpetest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * The iOS engine's WebKit scripts (`engine/webkit/WebKitScripts.kt` in :webview-compose, at b35cd67b0), with
 * RELAY, SENDER, RECEIVER and the delivery body copied byte for byte (Task 9 diffs them against the library's file), so the
 * lab runs exactly what a WebKit engine installs:
 * - [relay] in the script world, holding the native message handler [handlerName];
 * - [labShim] in the page world: the lab's stand-in for the library's BridgeShim, sending through the same SENDER and
 *   receiving through the same RECEIVER (`window.__hostBridgeReceive`);
 * - [deliveryBody], run by `call_async_javascript_function` in the script world with `json`, `target` and `name`.
 */
internal class WebKitRelay(val handlerName: String, val outEvent: String, val inEvent: String) {
    fun relay(pageOrigins: Set<String>, mainFrame: String = MAIN_FRAME): String = RELAY
        .replace("__MAIN_FRAME__", mainFrame)
        .replace("__HANDLER__", jsString(handlerName))
        .replace("__OUT__", jsString(outEvent))
        .replace("__ASK__", jsString(askEvent))
        .replace("__UP__", jsString(upEvent))
        .replace("__ORIGINS__", jsArray(pageOrigins))
        .replace("__MAX__", MAX_REPLY_CHARS.toString())

    /** The lab's page-world shim: SENDER as `__lab.send`, `__hostBridgeReceive` feeding `__lab.on` handlers, then RECEIVER. */
    fun labShim(): String =
        LAB_SHIM.replace("__SENDER__", sender()) + "\n" + RECEIVER.replace("__IN__", jsString(inEvent))

    private fun sender(): String =
        SENDER.replace("__OUT__", jsString(outEvent)).replace("__ASK__", jsString(askEvent)).replace("__UP__", jsString(upEvent))

    private val askEvent: String get() = "$outEvent-ask"
    private val upEvent: String get() = "$outEvent-up"

    val deliveryBody: String =
        "if (self.origin !== target) return false; document.dispatchEvent(new CustomEvent(name, { detail: json })); return true;"

    companion object {
        private const val MAIN_FRAME = "window === window.top"

        /** BridgeCodec.DEFAULT_MAX_REPLY_CHARS in the library. */
        private const val MAX_REPLY_CHARS = 256 * 1024

        /** Scripts with event names of their own, from [random]; the handler is "webview", as on iOS. */
        fun random(random: Random = Random): WebKitRelay {
            val token = (1..16).joinToString("") { "0123456789abcdef"[random.nextInt(16)].toString() }
            return WebKitRelay(handlerName = "webview", outEvent = "webview-out-$token", inEvent = "webview-in-$token")
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
  // The document has something to show once it has painted content, or has loaded: not at its parsing, when the screen
  // may still show nothing but its background. A page with nothing contentful (a blank one) only loads.
  var shown = false;
  function content() { if (shown) return; shown = true; painted(); }
  send('start');
  try {
    new PerformanceObserver(function (list, observer) {
      if (!list.getEntriesByName('first-contentful-paint').length) return;
      observer.disconnect();
      content();
    }).observe({ type: 'paint', buffered: true });
  } catch (e) {}
  if (document.readyState === 'complete') content(); else window.addEventListener('load', content);
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

        /** Copied byte for byte: the shim's channel out, which queues until the relay listens. */
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

        /** The lab's stand-in for BridgeShim: announces the document ($ready) through SENDER, as the real shim does. */
        private val LAB_SHIM = """
(function () {
  if (window.__lab) return;
  var send = __SENDER__;
  var handlers = [];
  window.__lab = {
    send: function (text) { send(String(text)); },
    on: function (f) { handlers.push(f); }
  };
  window.__hostBridgeReceive = function (text) {
    for (var i = 0; i < handlers.length; i++) { try { handlers[i](text); } catch (e) {} }
  };
  window.__lab.send('{"t":"event","n":"${'$'}ready","d":{"lab":1}}');
})();
""".trim()
    }
}
