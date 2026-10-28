package dev.supermux.webview.bridge

/**
 * The page side of the bridge: `window.hostBridge`, identical on every engine. Engines inject [source] at the start
 * of every top-level document, and deliver host messages by calling `window.__hostBridgeReceive(json)`.
 *
 * Page API:
 * - `call(method, params)` returns a Promise. It rejects if the call can't be sent or the host doesn't answer in time.
 * - `post(name, data)` returns whether it was sent.
 * - `on(name, fn)` and `handle(method, fn)` each return a function that undoes them.
 * - `onSaveState(fn)` registers what to keep while the view is cold. `onRestoreState(fn)` gets it back, even when
 *   registered late.
 * - Names starting with `$` are reserved.
 *
 * The shim also:
 * - announces each document (`$ready`);
 * - forwards console output and uncaught errors (`$console`), capped and rate-limited;
 * - reports the document's size and viewport height (`$size`), from the main document only.
 *
 * It captures built-ins at install, so pages that patch prototypes later can't break it. Nothing here names the
 * host app.
 */
object BridgeShim {
    /** The longest console message text kept; longer text is cut and ends with an ellipsis. */
    const val CONSOLE_TEXT_MAX: Int = 8192

    /**
     * The shim's JavaScript.
     * @param sendExpression a JS function expression that takes one string: the engine's channel to the host.
     * @param mainFrameExpression a JS expression that is true in the view's main document. Only that document
     *   reports its size.
     * @param maxMessageChars messages longer than this are refused on the page side too.
     * @param callTimeoutMillis how long a page-side `call` waits for the host.
     */
    fun source(
        sendExpression: String,
        mainFrameExpression: String = "window === window.top",
        maxMessageChars: Int = 256 * 1024,
        callTimeoutMillis: Long = 30_000,
    ): String = TEMPLATE
        .replace("__SEND__", sendExpression)
        .replace("__MAIN_FRAME__", mainFrameExpression)
        .replace("__MAX_CHARS__", maxMessageChars.toString())
        .replace("__CALL_TIMEOUT__", callTimeoutMillis.toString())
        .replace("__CONSOLE_MAX__", CONSOLE_TEXT_MAX.toString())

    private val TEMPLATE = """
(function () {
  if (window.hostBridge) return;
  var send = __SEND__;
  var MAX_CHARS = __MAX_CHARS__, CALL_TIMEOUT = __CALL_TIMEOUT__, CONSOLE_MAX = __CONSOLE_MAX__;
  var stringify = JSON.stringify, parse = JSON.parse, P = Promise, setTimer = setTimeout, clearTimer = clearTimeout;
  var defineProperty = Object.defineProperty, create = Object.create, freeze = Object.freeze, now = Date.now;
  var raf = typeof requestAnimationFrame === 'function' ? requestAnimationFrame : function (f) { return setTimer(f, 16); };
  var nextId = 1, pending = create(null), listeners = create(null), methods = create(null);
  var saveProvider = null, restoreListener = null, restored = null, hasRestored = false;
  function isMainFrame() { try { return !!(__MAIN_FRAME__); } catch (e) { return false; } }
  function nonce() {
    try { var a = new Uint32Array(4); crypto.getRandomValues(a); return a[0].toString(36) + a[1].toString(36) + a[2].toString(36) + a[3].toString(36); }
    catch (e) { return String(Math.random()).slice(2) + String(now()); }
  }
  function out(msg) {
    var text;
    try { text = stringify(msg); } catch (e) { return false; }
    if (typeof text !== 'string' || text.length > MAX_CHARS) return false;
    try { send(text); return true; } catch (e) { return false; }
  }
  function reserved(name) { return typeof name !== 'string' || name.charAt(0) === '$'; }
  function later(fn) { setTimer(fn, 0); }
  function deliver(name, data) {
    var ls = listeners[name];
    if (!ls) return;
    var copy = [];
    for (var i = 0; i < ls.length; i++) copy[i] = ls[i];
    for (var j = 0; j < copy.length; j++) { try { copy[j](data); } catch (e) { var error = e; later(function () { throw error; }); } }
  }
  function answer(msg) {
    var fn = msg.m === '${'$'}saveState' ? function () { return saveProvider ? saveProvider() : null; } : (reserved(msg.m) ? null : methods[msg.m]);
    P.resolve().then(function () {
      if (typeof fn !== 'function') throw new Error('no such method: ' + msg.m);
      return fn(msg.p);
    }).then(function (r) {
      if (!out({ t: 'reply', id: msg.id, r: r === undefined ? null : r })) out({ t: 'reply', id: msg.id, e: 'the reply is too large or not JSON' });
    }, function (e) {
      out({ t: 'reply', id: msg.id, e: String(e && e.message || e) });
    });
  }
  function receive(json) {
    if (typeof json !== 'string') return;
    var msg;
    try { msg = parse(json); } catch (e) { return; }
    if (!msg || typeof msg !== 'object') return;
    if (msg.t === 'reply') {
      var p = pending[msg.id];
      if (!p) return;
      delete pending[msg.id];
      clearTimer(p.timer);
      if (typeof msg.e === 'string') p.reject(new Error(msg.e)); else p.resolve(msg.r);
    } else if (msg.t === 'event' && typeof msg.n === 'string') {
      if (msg.n === '${'$'}restoreState') {
        restored = msg.d;
        hasRestored = true;
        if (restoreListener) { var f = restoreListener, d = msg.d; later(function () { f(d); }); }
      } else if (msg.n.charAt(0) !== '$') {
        deliver(msg.n, msg.d);
      }
    } else if (msg.t === 'call' && typeof msg.m === 'string') {
      answer(msg);
    }
  }
  defineProperty(window, '__hostBridgeReceive', { value: receive, writable: false, configurable: false, enumerable: false });
  var api = freeze({
    call: function (method, params) {
      if (reserved(method)) return P.reject(new TypeError('hostBridge: names starting with $ are reserved'));
      var id = nextId++;
      return new P(function (resolve, reject) {
        var timer = setTimer(function () {
          if (pending[id]) { delete pending[id]; reject(new Error('hostBridge: the host did not answer in time')); }
        }, CALL_TIMEOUT);
        pending[id] = { resolve: resolve, reject: reject, timer: timer };
        if (!out({ t: 'call', id: id, m: method, p: params === undefined ? null : params })) {
          delete pending[id];
          clearTimer(timer);
          reject(new Error('hostBridge: the call is too large or not JSON'));
        }
      });
    },
    post: function (name, data) {
      if (reserved(name)) throw new TypeError('hostBridge: names starting with $ are reserved');
      return out({ t: 'event', n: name, d: data === undefined ? null : data });
    },
    on: function (name, fn) {
      if (reserved(name) || typeof fn !== 'function') throw new TypeError('hostBridge.on(name, fn): names starting with $ are reserved');
      var ls = listeners[name] || (listeners[name] = []);
      ls[ls.length] = fn;
      return function () {
        var cur = listeners[name];
        if (!cur) return;
        var kept = [];
        for (var i = 0; i < cur.length; i++) if (cur[i] !== fn) kept[kept.length] = cur[i];
        listeners[name] = kept;
      };
    },
    handle: function (method, fn) {
      if (reserved(method) || typeof fn !== 'function') throw new TypeError('hostBridge.handle(method, fn): names starting with $ are reserved');
      methods[method] = fn;
      return function () { if (methods[method] === fn) delete methods[method]; };
    },
    onSaveState: function (fn) {
      saveProvider = typeof fn === 'function' ? fn : null;
      if (saveProvider) out({ t: 'event', n: '${'$'}saveStateProvider', d: true });
    },
    onRestoreState: function (fn) {
      restoreListener = typeof fn === 'function' ? fn : null;
      if (restoreListener && hasRestored) { var f = restoreListener, d = restored; later(function () { f(d); }); }
    }
  });
  defineProperty(window, 'hostBridge', { value: api, writable: false, configurable: false, enumerable: false });
  out({ t: 'event', n: '${'$'}ready', d: { nonce: nonce() } });
  function describe(a) {
    try {
      if (typeof a === 'string') return a;
      if (a instanceof Error) return a.stack ? String(a.stack) : String(a.name) + ': ' + String(a.message);
      if (a === undefined || a === null || typeof a !== 'object') return String(a);
      var seen = [];
      var text = stringify(a, function (key, value) {
        if (typeof value === 'bigint' || typeof value === 'function' || typeof value === 'symbol') return String(value);
        if (value && typeof value === 'object') {
          for (var i = 0; i < seen.length; i++) if (seen[i] === value) return '[Circular]';
          if (seen.length >= 1000) return '[…]';
          seen[seen.length] = value;
        }
        return value;
      });
      return typeof text === 'string' ? text : String(a);
    } catch (e) {
      return '[unprintable]';
    }
  }
  var consoleWindowStart = 0, consoleCount = 0, consoleDropped = 0, inConsole = false;
  function emitConsole(d) {
    if (d.text.indexOf('ResizeObserver loop') !== -1) return;
    var t = now();
    if (t - consoleWindowStart >= 1000) {
      if (consoleDropped > 0) out({ t: 'event', n: '${'$'}console', d: { level: 'warn', text: consoleDropped + ' console messages were dropped (more than 100 a second)' } });
      consoleWindowStart = t;
      consoleCount = 0;
      consoleDropped = 0;
    }
    if (consoleCount >= 100) { consoleDropped++; return; }
    consoleCount++;
    if (d.text.length > CONSOLE_MAX) d.text = d.text.slice(0, CONSOLE_MAX) + '…';
    out({ t: 'event', n: '${'$'}console', d: d });
  }
  var levels = ['debug', 'log', 'info', 'warn', 'error'];
  for (var li = 0; li < levels.length; li++) (function (level) {
    var original = console[level];
    console[level] = function () {
      if (!inConsole) {
        inConsole = true;
        try {
          var text = '';
          for (var i = 0; i < arguments.length; i++) text += (i ? ' ' : '') + describe(arguments[i]);
          emitConsole({ level: level, text: text });
        } catch (e) {
        } finally {
          inConsole = false;
        }
      }
      if (typeof original === 'function') return original.apply(console, arguments);
    };
  })(levels[li]);
  window.addEventListener('error', function (e) {
    var target = e.target;
    if (target && target !== window && target.tagName) {
      emitConsole({ level: 'error', text: 'Failed to load ' + String(target.tagName).toLowerCase() + ' ' + String(target.currentSrc || target.src || target.href || ''), uncaught: true });
      return;
    }
    var message = String(e.message || '');
    var text = e.error ? describe(e.error) : (message === 'Script error.' ? 'Script error. (a cross-origin script failed; serve it with CORS and add crossorigin to see the details)' : message);
    emitConsole({ level: 'error', text: text, source: e.filename || null, line: e.lineno || null, uncaught: true });
  }, true);
  window.addEventListener('unhandledrejection', function (e) {
    emitConsole({ level: 'error', text: 'Unhandled rejection: ' + describe(e.reason), uncaught: true });
  });
  var lastW = -1, lastH = -1, lastVh = -1, sizeScheduled = false, SCAN_LIMIT = 2000;
  function measure() {
    var de = document.documentElement;
    var vw = window.innerWidth, vh = window.innerHeight;
    if (!de || !vw || !vh || document.hidden) return null;
    var box = de.getBoundingClientRect();
    var h = box.height;
    if (de.scrollHeight > h + 1 && document.body) {
      var all = document.body.getElementsByTagName('*');
      var n = all.length < SCAN_LIMIT ? all.length : SCAN_LIMIT, top = box.top;
      for (var i = 0; i < n; i++) { var bottom = all[i].getBoundingClientRect().bottom - top; if (bottom > h) h = bottom; }
    }
    return { w: Math.ceil(box.width), h: Math.ceil(h), vh: vh };
  }
  function reportSize() {
    sizeScheduled = false;
    if (!isMainFrame()) return;
    var m = measure();
    if (!m || (m.w === lastW && m.h === lastH && m.vh === lastVh)) return;
    lastW = m.w;
    lastH = m.h;
    lastVh = m.vh;
    out({ t: 'event', n: '${'$'}size', d: m });
  }
  function scheduleSize() { if (!sizeScheduled) { sizeScheduled = true; raf(reportSize); } }
  if (typeof ResizeObserver === 'function') {
    var observer = new ResizeObserver(scheduleSize);
    var startObserving = function () { observer.observe(document.documentElement); if (document.body) observer.observe(document.body); scheduleSize(); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startObserving); else startObserving();
  }
  window.addEventListener('resize', scheduleSize);
  document.addEventListener('visibilitychange', scheduleSize);
})();
""".trim()
}
