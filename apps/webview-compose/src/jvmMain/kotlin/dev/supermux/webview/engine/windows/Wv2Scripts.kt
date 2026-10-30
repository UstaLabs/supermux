package dev.supermux.webview.engine.windows

import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeShim
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.webkit.WebKitScripts
import java.security.SecureRandom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The scripts of a Windows engine (D5: the WebEngine contract's **Delivery** for WebView2, which has no isolated world).
 * - [document] is an epoch's document script, installed with `AddScriptToExecuteOnDocumentCreated`: it runs in every frame
 *   of every document before any page script, and returns at once outside the main frame. In strict mode, with every
 *   built-in it uses captured first, it:
 *   - posts `start`, then `paint` (two animation frames after the first contentful paint or the load: the relay's rule),
 *     through the captured `chrome.webview.postMessage`, each prefixed with the epoch's secret and its document token;
 *   - where the document's origin (`self.origin`, read now) is a bridge origin and scripts are on, hands the common
 *     [BridgeShim] a channel that signs and caps what it sends (the relay's burst and rate), through a property of the
 *     window that exists only until the shim has taken it, before any page script runs;
 *   - defines the receiver [receiverName], neither writable nor configurable, which hands a host message to the shim's
 *     captured `__hostBridgeReceive` only if the origin it captured at document creation is the target.
 * - [deliveryCall] is the `ExecuteScript` text that delivers a host message to that receiver.
 *
 * The secret is an argument of the outer function only, never a literal inside a function a page could reach, and those
 * functions are strict, so neither `caller`, `arguments` nor a stack trace's call sites lead to it. Without the shim the
 * script names neither the channel nor the receiver.
 */
internal class Wv2Scripts(val receiverName: String, val channelName: String) {
    fun document(secret: String, pageOrigins: Set<String>, shim: Boolean): String {
        val channel = js(channelName)
        val signals = SIGNALS
            .replace("__READY__", js(WebBridge.READY_PREFIX))
            .replace("__READY_MAX__", WebBridge.READY_MAX_CHARS.toString())
            .replace("__BURST__", WebKitScripts.PAGE_MESSAGE_BURST.toString())
            .replace("__RATE__", WebKitScripts.PAGE_MESSAGES_PER_SECOND.toString())
            .replace("__MAX__", BridgeCodec.DEFAULT_MAX_REPLY_CHARS.toString())
            .replace("__ARGS__", "${js(secret)}, ${jsArray(pageOrigins)}, $shim, ${if (shim) channel else "null"}")
        if (!shim) return signals
        val bridge = BridgeShim.source(
            sendExpression = "(function () { var o = window[$channel]; delete window[$channel]; return o; })()",
            mainFrameExpression = "window === window.top && typeof window[$channel] === 'function'",
        )
        return signals + "\n" + bridge + "\n" + RECEIVER.replace("__ARGS__", "${js(receiverName)}, $channel")
    }

    fun deliveryCall(json: String, targetOrigin: String): String = "window[${js(receiverName)}](${js(json)}, ${js(targetOrigin)})"

    companion object {
        private val random = SecureRandom()

        fun newSecret(): String = hex(16)

        fun random(): Wv2Scripts = Wv2Scripts(receiverName = "__wv_r" + hex(8), channelName = "__wv_c" + hex(8))

        private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        private fun js(text: String): String = JsonPrimitive(text).toString()

        private fun jsArray(items: Set<String>): String = JsonArray(items.sorted().map(::JsonPrimitive)).toString()

        private val SIGNALS = """
(function (K, ORIGINS, SHIM, CHANNEL) {
  'use strict';
  if (window !== window.top) return;
  var webview = window.chrome && window.chrome.webview;
  if (!webview || typeof webview.postMessage !== 'function') return;
  var post = webview.postMessage, apply = Reflect.apply, define = Object.defineProperty, min = Math.min;
  var indexOf = String.prototype.indexOf, arrayIndexOf = Array.prototype.indexOf, clock = performance.now.bind(performance);
  var frame = window.requestAnimationFrame.bind(window), listen = EventTarget.prototype.addEventListener;
  var fill = crypto.getRandomValues.bind(crypto), Observer = window.PerformanceObserver, origin = self.origin;
  var hex = '0123456789abcdef';
  function token() {
    var a = new Uint8Array(8), s = '';
    fill(a);
    for (var i = 0; i < 8; i++) s += hex.charAt(a[i] >> 4) + hex.charAt(a[i] & 15);
    return s;
  }
  var doc = token();
  function send(text) { try { apply(post, webview, [K + doc + ' ' + text]); } catch (e) {} }
  send('start');
  // The document has something to show once it painted content, or loaded (a blank page only loads); then two frames.
  var shown = false;
  function painted() { frame(function () { frame(function () { send('paint'); }); }); }
  function content() { if (shown) return; shown = true; painted(); }
  try {
    new Observer(function (list, observer) {
      if (!list.getEntriesByName('first-contentful-paint').length) return;
      observer.disconnect();
      content();
    }).observe({ type: 'paint', buffered: true });
  } catch (e) {}
  if (document.readyState === 'complete') content(); else apply(listen, window, ['load', content]);
  // Back from the back-forward cache, the page is a document anew (off in this engine, V4; kept in case it isn't).
  apply(listen, window, ['pageshow', function (e) {
    if (!e.persisted || !e.isTrusted) return;
    doc = token();
    send('start');
    painted();
  }, true]);
  if (!SHIM || apply(arrayIndexOf, ORIGINS, [origin]) < 0) return;
  var READY = __READY__, READY_MAX = __READY_MAX__, BURST = __BURST__, RATE = __RATE__, MAX = __MAX__;
  var budget = BURST, refilled = clock();
  function allowed(text) {
    if (text.length <= READY_MAX && apply(indexOf, text, [READY]) === 0) return true;
    var t = clock();
    budget = min(BURST, budget + (t - refilled) * RATE / 1000);
    refilled = t;
    if (budget < 1) return false;
    budget -= 1;
    return true;
  }
  // The shim (next, in the page's own mode, as on every engine) takes this and deletes it: no page script ever sees it.
  define(window, CHANNEL, { value: function (text) {
    if (typeof text === 'string' && text.length <= MAX && allowed(text)) send('msg ' + text);
  }, configurable: true });
})(__ARGS__);
""".trim()

        private val RECEIVER = """
(function (RECEIVER, CHANNEL) {
  'use strict';
  if (window !== window.top) return;
  try { delete window[CHANNEL]; } catch (e) {}
  var receive = window.__hostBridgeReceive, origin = self.origin, apply = Reflect.apply;
  if (typeof receive !== 'function') return;
  Object.defineProperty(window, RECEIVER, {
    value: function (json, target) {
      if (typeof json !== 'string' || target !== origin) return false;
      apply(receive, window, [json]);
      return true;
    },
    writable: false, configurable: false, enumerable: false,
  });
})(__ARGS__);
""".trim()
    }
}
