package dev.supermux.webview.bridge

/**
 * The page side of the bridge: `window.hostBridge`, identical on every engine. Engines inject [source] at document
 * start and deliver host messages by calling `window.__hostBridgeReceive(json)`.
 *
 * Page API:
 * - `hostBridge.call(method, params)` returns a Promise.
 * - `hostBridge.post(name, data)`.
 * - `hostBridge.on(name, fn)` returns an unsubscribe function.
 * - `hostBridge.handle(method, fn)`.
 * - `hostBridge.onSaveState(fn)` registers the page state to keep while the view is cold. It comes back as the
 *   `$restoreState` event.
 *
 * It also forwards console output and uncaught errors (`$console`) and the document's size (`$size`, for
 * WrapContentHeight). Nothing here names the host app.
 */
object BridgeShim {
    /** The shim's JavaScript. [sendExpression] is a JS function expression that takes one string. */
    fun source(sendExpression: String): String = """
(function () {
  if (window.hostBridge) return;
  var send = $sendExpression;
  var nextId = 1, pending = {}, listeners = {}, methods = {}, saveProvider = null;
  function out(msg) { try { send(JSON.stringify(msg)); } catch (e) {} }
  function fmt(a) { if (typeof a === 'string') return a; try { return JSON.stringify(a); } catch (e) { return String(a); } }
  function deliver(name, data) {
    var ls = listeners[name];
    if (!ls) return;
    ls.slice().forEach(function (fn) { try { fn(data); } catch (e) {} });
  }
  window.__hostBridgeReceive = function (json) {
    var msg;
    try { msg = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { return; }
    if (!msg || typeof msg !== 'object') return;
    if (msg.t === 'reply') {
      var p = pending[msg.id];
      if (!p) return;
      delete pending[msg.id];
      if (msg.e != null) p.reject(new Error(msg.e)); else p.resolve(msg.r);
    } else if (msg.t === 'event') {
      deliver(msg.n, msg.d);
    } else if (msg.t === 'call') {
      var fn = msg.m === '${'$'}saveState' ? function () { return saveProvider ? saveProvider() : null; } : methods[msg.m];
      Promise.resolve().then(function () {
        if (!fn) throw new Error('no such method: ' + msg.m);
        return fn(msg.p);
      }).then(function (r) {
        out({ t: 'reply', id: msg.id, r: r === undefined ? null : r });
      }, function (e) {
        out({ t: 'reply', id: msg.id, e: String(e && e.message || e) });
      });
    }
  };
  var api = Object.freeze({
    call: function (method, params) {
      var id = nextId++;
      return new Promise(function (resolve, reject) {
        pending[id] = { resolve: resolve, reject: reject };
        out({ t: 'call', id: id, m: method, p: params === undefined ? null : params });
      });
    },
    post: function (name, data) { out({ t: 'event', n: name, d: data === undefined ? null : data }); },
    on: function (name, fn) {
      (listeners[name] = listeners[name] || []).push(fn);
      return function () { var ls = listeners[name] || []; var i = ls.indexOf(fn); if (i >= 0) ls.splice(i, 1); };
    },
    handle: function (method, fn) { methods[method] = fn; },
    onSaveState: function (fn) { saveProvider = fn; out({ t: 'event', n: '${'$'}saveStateProvider', d: true }); }
  });
  Object.defineProperty(window, 'hostBridge', { value: api, writable: false, configurable: false });
  ['debug', 'log', 'info', 'warn', 'error'].forEach(function (level) {
    var original = console[level];
    console[level] = function () {
      out({ t: 'event', n: '${'$'}console', d: { level: level, text: Array.prototype.map.call(arguments, fmt).join(' ') } });
      if (original) return original.apply(console, arguments);
    };
  });
  window.addEventListener('error', function (e) {
    out({ t: 'event', n: '${'$'}console', d: { level: 'error', text: String(e.message), source: e.filename || null, line: e.lineno || null, uncaught: true } });
  });
  window.addEventListener('unhandledrejection', function (e) {
    out({ t: 'event', n: '${'$'}console', d: { level: 'error', text: 'Unhandled rejection: ' + fmt(e.reason), uncaught: true } });
  });
  var lastW = -1, lastH = -1, scheduled = false;
  function report() {
    scheduled = false;
    var de = document.documentElement;
    if (!de) return;
    var r = de.getBoundingClientRect();
    var w = Math.ceil(r.width), h = Math.ceil(r.height);
    if (w !== lastW || h !== lastH) { lastW = w; lastH = h; out({ t: 'event', n: '${'$'}size', d: { w: w, h: h } }); }
  }
  function schedule() { if (!scheduled) { scheduled = true; requestAnimationFrame(report); } }
  if (window.ResizeObserver) {
    var observer = new ResizeObserver(schedule);
    var start = function () { observer.observe(document.documentElement); if (document.body) observer.observe(document.body); schedule(); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
  }
})();
""".trimIndent()
}
