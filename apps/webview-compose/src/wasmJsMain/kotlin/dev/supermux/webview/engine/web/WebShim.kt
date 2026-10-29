package dev.supermux.webview.engine.web

import dev.supermux.webview.bridge.BridgeShim
import kotlinx.serialization.json.JsonPrimitive

/**
 * The web engine's page shim: the common [BridgeShim], identical on every engine, over a channel of the web's own
 * (contract **Delivery**: "On the web, delivery uses a per-document `MessagePort` … The port belongs to the web
 * engine's own shim variant, which creates it and transfers it with its `$ready`").
 * - On its first send, the document's `$ready`, it creates a `MessageChannel` and posts
 *   `{webviewBridge: PROTOCOL, secret, text}` with the other end to its parent, aimed at the app's origin. Everything
 *   after that, both ways, goes over the port. Only this document holds it, and it dies with the document.
 * - The secret proves that a document is the one the engine started. A sandboxed srcdoc speaks as the opaque origin
 *   `"null"`, and so does any page it navigates to, so the engine trusts the secret, not the origin. Page scripts can't
 *   read the secret, because the shim's own `<script>` removes itself before they run.
 * - It turns a trusted click on a link that leaves the document into `{webviewOpen, newWindow}` on the port, so a demo's
 *   links go through the app's `WebClient` instead of navigating the demo. Synthetic clicks pass through untouched:
 *   the port is private to the shim, so a request on it always comes from a real click.
 * - It sends what the user's wheel or drag couldn't scroll in the page as `{webviewScroll: [dx, dy]}`, and a chaining
 *   drag's release velocity as `{webviewFling: [vx, vy]}`, so the list the view is in scrolls instead ([SCROLL]).
 */
internal object WebShim {
    /** The handshake's protocol number. `listenForHandshakes` in Dom.kt accepts this one only. */
    const val PROTOCOL: Int = 1

    /**
     * The shim for one document.
     * @param secret the document's secret: random, used once.
     * @param parentOrigin the app's origin (`location.origin`), the only place the handshake goes. An app with an
     *   opaque origin (served from a file) sends it to `*`: an iframe's parent can't change while the iframe lives.
     * @param trustSyntheticInput for tests only, which can't make trusted input: scroll chaining takes the page's own
     *   synthetic wheels and drags too. Never set by the engine an app gets.
     */
    fun source(
        secret: String,
        parentOrigin: String,
        callTimeoutMillis: Long = 30_000,
        trustSyntheticInput: Boolean = false,
    ): String {
        val target = if (parentOrigin == "null" || parentOrigin.isEmpty()) "*" else parentOrigin
        val prelude = PRELUDE
            .replace("__PROTOCOL__", PROTOCOL.toString())
            .replace("__SECRET__", JsonPrimitive(secret).toString())
            .replace("__TARGET__", JsonPrimitive(target).toString())
        val shim = BridgeShim.source("link.send", mainFrameExpression = "window.parent !== window", callTimeoutMillis = callTimeoutMillis)
        val scroll = SCROLL.replace("__TRUST_SYNTHETIC__", trustSyntheticInput.toString())
        return prelude + "\n" + shim + "\n" + LINKS + "\n" + scroll + "\n" + EPILOGUE
    }

    private val PRELUDE = """
(function () {
  var self = document.currentScript;
  var link = (function () {
    var parentWindow = window.parent, Channel = MessageChannel, port = null;
    var SECRET = __SECRET__, TARGET = __TARGET__;
    return {
      send: function (text) {
        if (port !== null) { port.postMessage(text); return; }
        var channel = new Channel();
        parentWindow.postMessage({ webviewBridge: __PROTOCOL__, secret: SECRET, text: text }, TARGET, [channel.port2]);
        port = channel.port1;
        port.onmessage = function (e) {
          var receive = window.__hostBridgeReceive;
          if (typeof e.data === 'string' && typeof receive === 'function') receive(e.data);
        };
      },
      open: function (url, newWindow) {
        if (port !== null) port.postMessage({ webviewOpen: String(url), newWindow: newWindow === true });
      },
      scroll: function (dx, dy) {
        if (port !== null) port.postMessage({ webviewScroll: [dx, dy] });
      },
      fling: function (vx, vy) {
        if (port !== null) port.postMessage({ webviewFling: [vx, vy] });
      }
    };
  })();
""".trim()

    private val LINKS = """
  document.addEventListener('click', function (e) {
    if (!e.isTrusted || e.defaultPrevented || e.button !== 0) return;
    var node = e.target;
    while (node && node.nodeType === 1 && node.localName !== 'a' && node.localName !== 'area') node = node.parentNode;
    if (!node || node.nodeType !== 1 || !node.hasAttribute('href')) return;
    var href = String(node.href);
    if (href === '' || href.indexOf('javascript:') === 0) return;
    if (href.split('#')[0] === String(location.href).split('#')[0]) return;
    e.preventDefault();
    var target = node.getAttribute('target');
    link.open(href, (target !== null && target !== '' && target.toLowerCase() !== '_self') || e.ctrlKey || e.metaKey || e.shiftKey);
  }, true);
""".trim()

    /**
     * Scroll chaining: what a user's wheel or one-finger drag can't scroll in the page goes to the app over the port, so
     * the list around the view scrolls instead. Only input the browser made counts (`isTrusted`), unless the engine was
     * built for a test. The page's own scrolling runs first: along the target's scroll containers, up to the root,
     * whatever room each has in that direction takes its share, and `overscroll-behavior: contain` or `none` keeps the
     * rest in the page. Deltas are in CSS px, positive down and right (the DOM's sign); a drag that ends while chaining
     * also sends its release velocity, in CSS px per second.
     */
    private val SCROLL = """
  (function () {
    var TRUST_SYNTHETIC = __TRUST_SYNTHETIC__, LINE = 16;
    function trusted(e) { return e.isTrusted === true || TRUST_SYNTHETIC; }
    function rootScroller() { return document.scrollingElement || document.documentElement; }
    function scrollsOn(el, axis) {
      if (el !== rootScroller()) {
        var s = getComputedStyle(el), o = axis === 'y' ? s.overflowY : s.overflowX;
        if (o !== 'auto' && o !== 'scroll' && o !== 'overlay') return false;
      }
      return axis === 'y' ? el.scrollHeight > el.clientHeight : el.scrollWidth > el.clientWidth;
    }
    function roomOn(el, axis, sign) {
      var r = axis === 'y'
        ? (sign > 0 ? el.scrollHeight - el.clientHeight - el.scrollTop : el.scrollTop)
        : (sign > 0 ? el.scrollWidth - el.clientWidth - Math.abs(el.scrollLeft) : Math.abs(el.scrollLeft));
      return r > 0 ? r : 0;
    }
    function keepsOverscroll(el, axis) {
      var s = getComputedStyle(el), b = axis === 'y' ? s.overscrollBehaviorY : s.overscrollBehaviorX;
      return b === 'contain' || b === 'none';
    }
    function leftOver(node, axis, delta) {
      if (!(delta > 0 || delta < 0)) return 0;
      var sign = delta > 0 ? 1 : -1, left = sign * delta, root = rootScroller();
      var el = node && node.nodeType === 1 ? node : root;
      while (el) {
        if (scrollsOn(el, axis)) {
          left -= roomOn(el, axis, sign);
          if (left <= 0 || keepsOverscroll(el, axis)) return 0;
        }
        if (el === root) break;
        var up = el.parentElement;
        if (!up && el.parentNode && el.parentNode.host) up = el.parentNode.host;
        el = up || root;
      }
      return keepsOverscroll(root, axis) ? 0 : sign * left;
    }
    addEventListener('wheel', function (e) {
      if (!trusted(e) || e.defaultPrevented || e.ctrlKey) return;
      var kx = e.deltaMode === 1 ? LINE : e.deltaMode === 2 ? innerWidth : 1;
      var ky = e.deltaMode === 1 ? LINE : e.deltaMode === 2 ? innerHeight : 1;
      var dx = leftOver(e.target, 'x', e.deltaX * kx), dy = leftOver(e.target, 'y', e.deltaY * ky);
      if (dx !== 0 || dy !== 0) link.scroll(dx, dy);
    }, { passive: true });
    var drag = null;
    function single(e) { return e.touches && e.touches.length === 1 ? e.touches[0] : null; }
    addEventListener('touchstart', function (e) {
      var t = trusted(e) ? single(e) : null;
      drag = t ? { id: t.identifier, x: t.clientX, y: t.clientY, target: e.target, chained: false, moves: [] } : null;
    }, { passive: true });
    addEventListener('touchmove', function (e) {
      var t = single(e);
      if (!drag || !trusted(e) || !t || t.identifier !== drag.id || e.defaultPrevented) { drag = null; return; }
      var dx = drag.x - t.clientX, dy = drag.y - t.clientY, now = e.timeStamp;
      drag.x = t.clientX;
      drag.y = t.clientY;
      var lx = leftOver(drag.target, 'x', dx), ly = leftOver(drag.target, 'y', dy);
      drag.chained = lx !== 0 || ly !== 0;
      drag.moves.push([now, lx, ly]);
      while (drag.moves.length > 1 && now - drag.moves[0][0] > 100) drag.moves.shift();
      if (drag.chained) link.scroll(lx, ly);
    }, { passive: true });
    addEventListener('touchend', function (e) {
      var d = drag;
      drag = null;
      if (!d || !d.chained || !trusted(e) || d.moves.length < 2) return;
      var m = d.moves, span = (m[m.length - 1][0] - m[0][0]) / 1000, sx = 0, sy = 0;
      if (!(span > 0)) return;
      for (var i = 1; i < m.length; i++) { sx += m[i][1]; sy += m[i][2]; }
      link.fling(sx / span, sy / span);
    }, { passive: true });
    addEventListener('touchcancel', function () { drag = null; }, { passive: true });
  })();
""".trim()

    private val EPILOGUE = """
  if (self && self.parentNode) self.parentNode.removeChild(self);
})();
""".trim()
}
