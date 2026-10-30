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
 *   links go through the app's `WebClient` instead of navigating the demo. Synthetic clicks pass through untouched.
 * - The port is the shim's alone. It is created and handed over at install, before any page script runs, and every
 *   built-in the shim calls on it later (`MessagePort.prototype.postMessage`, the `MessageEvent` `data` getter,
 *   `Reflect.apply`) is captured then: a page that patches them never sees the port, so it can't send on it. The input
 *   getters scroll chaining reads are captured too, so a page can't inflate the user's deltas. The engine still takes a
 *   link request only while the app's window has transient activation ([IframeWebEngine]).
 * - It sends what the user's wheel or drag couldn't scroll in the page as `{webviewScroll: [dx, dy]}`, and a chaining
 *   drag's release velocity as `{webviewFling: [vx, vy]}`, so the list the view is in scrolls instead ([SCROLL]).
 * - It tells of the user's presses in the page (`{webviewPress: 1}`), so the engine can tell a focus the user gave the
 *   page from one the page took ([PRESSES]).
 */
internal object WebShim {
    /** The handshake's protocol number. `listenForHandshakes` in Dom.kt accepts this one only. */
    const val PROTOCOL: Int = 1

    /**
     * The shim for one document.
     * @param secret the document's secret: random, used once.
     * @param parentOrigin the app's origin (`location.origin`), the only place the handshake goes. An app with an
     *   opaque origin (served from a file) sends it to `*`: an iframe's parent can't change while the iframe lives.
     * @param trustSyntheticInput for tests only, which can't make trusted input: scroll chaining and link interception
     *   take the page's own synthetic wheels, drags and clicks too. Never set by the engine an app gets.
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
            .replace("__TRUST_SYNTHETIC__", trustSyntheticInput.toString())
        val shim = BridgeShim.source("link.send", mainFrameExpression = "window.parent !== window", callTimeoutMillis = callTimeoutMillis)
        return prelude + "\n" + shim + "\n" + LINKS + "\n" + PRESSES + "\n" + SCROLL + "\n" + EPILOGUE
    }

    /**
     * The port, and the built-ins the shim uses after page scripts may have run, captured at install, before any can:
     * a page that patched `postMessage` or the `data` getter would be handed the port the next time the shim used it.
     * `read(getter, object)` reads a property through its captured getter.
     */
    private val PRELUDE = """
(function () {
  var self = document.currentScript;
  var apply = Reflect.apply, getOwn = Object.getOwnPropertyDescriptor, Str = String;
  function getterOf(type, name) { var d = type ? getOwn(type.prototype, name) : null; return d ? d.get : null; }
  function read(getter, object) { return apply(getter, object, []); }
  var postOnPort = MessagePort.prototype.postMessage, dataOf = getterOf(MessageEvent, 'data');
  var port1Of = getterOf(MessageChannel, 'port1'), port2Of = getterOf(MessageChannel, 'port2');
  var setOnMessage = getOwn(MessagePort.prototype, 'onmessage').set;
  var TRUST_SYNTHETIC = __TRUST_SYNTHETIC__;
  function trusted(e) { return e.isTrusted === true || TRUST_SYNTHETIC; }
  var link = (function () {
    var parentWindow = window.parent, Channel = MessageChannel, port = null;
    var SECRET = __SECRET__, TARGET = __TARGET__;
    function post(message) { if (port !== null) apply(postOnPort, port, [message]); }
    return {
      send: function (text) {
        if (port !== null) { post(text); return; }
        var channel = new Channel(), theirs = read(port2Of, channel);
        parentWindow.postMessage({ webviewBridge: __PROTOCOL__, secret: SECRET, text: text }, TARGET, [theirs]);
        port = read(port1Of, channel);
        apply(setOnMessage, port, [function (e) {
          var data = read(dataOf, e), receive = window.__hostBridgeReceive;
          if (typeof data === 'string' && typeof receive === 'function') receive(data);
        }]);
      },
      open: function (url, newWindow) { post({ webviewOpen: Str(url), newWindow: newWindow === true }); },
      scroll: function (dx, dy) { post({ webviewScroll: [dx, dy] }); },
      fling: function (vx, vy) { post({ webviewFling: [vx, vy] }); },
      press: function () { post({ webviewPress: 1 }); }
    };
  })();
""".trim()

    /**
     * The user's presses in the page, told to the engine (`{webviewPress: 1}`, at most one each 50 ms): a page that takes
     * the keyboard right after one was given it by the user; one that takes it with none, took it. Capture listeners on
     * the window, added before any page script, so a page can't hide a press from them, and can't fake one.
     */
    private val PRESSES = """
  (function () {
    var clock = Date.now, last = -1e9;
    function pressed(e) {
      if (!trusted(e)) return;
      var t = clock();
      if (t - last >= 0 && t - last < 50) return;
      last = t;
      link.press();
    }
    addEventListener('pointerdown', pressed, true);
    addEventListener('mousedown', pressed, true);
    addEventListener('touchstart', pressed, { capture: true, passive: true });
  })();
""".trim()

    private val LINKS = """
  document.addEventListener('click', function (e) {
    if (!trusted(e) || e.defaultPrevented || e.button !== 0) return;
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
     *
     * The event properties it reads go through getters captured at install, and a drag's recent moves are kept in object
     * literals, not in arrays a patched `Array.prototype` could reach: the user's input can't be inflated. What it reads
     * of the page's layout can be faked, but that only decides how much of the user's own delta reaches the app.
     */
    private val SCROLL = """
  (function () {
    var LINE = 16, abs = Math.abs;
    var deltaXOf = getterOf(WheelEvent, 'deltaX'), deltaYOf = getterOf(WheelEvent, 'deltaY'), deltaModeOf = getterOf(WheelEvent, 'deltaMode');
    var ctrlOf = getterOf(MouseEvent, 'ctrlKey'), preventedOf = getterOf(Event, 'defaultPrevented');
    var targetOf = getterOf(Event, 'target'), timeOf = getterOf(Event, 'timeStamp');
    var touchesOf = getterOf(typeof TouchEvent === 'function' ? TouchEvent : null, 'touches');
    var countOf = getterOf(typeof TouchList === 'function' ? TouchList : null, 'length');
    var Tch = typeof Touch === 'function' ? Touch : null;
    var idOf = getterOf(Tch, 'identifier'), xOf = getterOf(Tch, 'clientX'), yOf = getterOf(Tch, 'clientY');
    var widthOf = (getOwn(window, 'innerWidth') || {}).get, heightOf = (getOwn(window, 'innerHeight') || {}).get;
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
        : (sign > 0 ? el.scrollWidth - el.clientWidth - abs(el.scrollLeft) : abs(el.scrollLeft));
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
      if (!trusted(e) || read(preventedOf, e) || read(ctrlOf, e)) return;
      var mode = read(deltaModeOf, e);
      var kx = mode === 1 ? LINE : mode === 2 ? (widthOf ? read(widthOf, window) : innerWidth) : 1;
      var ky = mode === 1 ? LINE : mode === 2 ? (heightOf ? read(heightOf, window) : innerHeight) : 1;
      var target = read(targetOf, e);
      var dx = leftOver(target, 'x', read(deltaXOf, e) * kx), dy = leftOver(target, 'y', read(deltaYOf, e) * ky);
      if (dx !== 0 || dy !== 0) link.scroll(dx, dy);
    }, { passive: true });
    if (!touchesOf || !countOf || !idOf || !xOf || !yOf) return;
    // A drag's recent moves, newest first, as { t, x, y, older }: those within WINDOW ms of the newest.
    var drag = null, WINDOW = 100;
    function single(e) { var list = read(touchesOf, e); return list && read(countOf, list) === 1 ? list[0] : null; }
    addEventListener('touchstart', function (e) {
      var t = trusted(e) ? single(e) : null;
      drag = t ? { id: read(idOf, t), x: read(xOf, t), y: read(yOf, t), target: read(targetOf, e), chained: false, moves: null } : null;
    }, { passive: true });
    addEventListener('touchmove', function (e) {
      var t = single(e);
      if (!drag || !trusted(e) || !t || read(idOf, t) !== drag.id || read(preventedOf, e)) { drag = null; return; }
      var cx = read(xOf, t), cy = read(yOf, t), dx = drag.x - cx, dy = drag.y - cy, now = read(timeOf, e);
      drag.x = cx;
      drag.y = cy;
      var lx = leftOver(drag.target, 'x', dx), ly = leftOver(drag.target, 'y', dy);
      drag.chained = lx !== 0 || ly !== 0;
      var newest = { t: now, x: lx, y: ly, older: drag.moves }, oldest = newest;
      while (oldest.older !== null && now - oldest.older.t <= WINDOW) oldest = oldest.older;
      oldest.older = null;
      drag.moves = newest;
      if (drag.chained) link.scroll(lx, ly);
    }, { passive: true });
    addEventListener('touchend', function (e) {
      var d = drag;
      drag = null;
      if (!d || !d.chained || !trusted(e) || d.moves === null) return;
      // The moves' sum over their span, which starts at the oldest one: its own delta came before the span.
      var last = d.moves, m = last, sx = 0, sy = 0, n = 0;
      while (m.older !== null) { sx += m.x; sy += m.y; n++; m = m.older; }
      var span = (last.t - m.t) / 1000;
      if (n === 0 || !(span > 0)) return;
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
