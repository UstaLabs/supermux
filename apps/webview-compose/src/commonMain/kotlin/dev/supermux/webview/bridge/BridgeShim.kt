package dev.supermux.webview.bridge

/**
 * The page side of the bridge: `window.hostBridge`, identical on every engine. Engines inject [source] at the start
 * of the main frame of every document whose origin may talk to the host. Host messages are delivered by calling
 * `window.__hostBridgeReceive(json)`.
 *
 * Each host message names the document it is for: `to`, the nonce that document announced. The shim drops messages
 * for another document, so nothing meant for one document reaches the next, even on the same origin or after a
 * restore from the back-forward cache. The host's `$hello` is the only message without an address.
 *
 * A message for another document means the host has a stale nonce for this one: the document's `$ready` was lost,
 * say. The shim then announces its current nonce, at most once a second, so a quiet page is reached again as soon as
 * the host sends it anything. The host ignores a `$ready` it already has. Correctly addressed traffic costs nothing
 * more.
 *
 * Each page message names the document that sent it: `from`, its current nonce (a `$ready` also carries it in
 * `d.nonce`). The host drops messages from a document that isn't current, so a late message from a replaced document
 * can't pass for the current one's. A page of the current document's origin that speaks with another nonce lost its
 * `$ready`, and the host asks it to announce itself.
 *
 * Page API:
 * - `call(method, params)` returns a Promise. It rejects if the call can't be sent, the host doesn't answer in time,
 *   or the page is restored from the back-forward cache meanwhile.
 * - `post(name, data)` returns whether it was sent.
 * - `on(name, fn)` and `handle(method, fn)` each return a function that undoes them.
 * - `onSaveState(fn)` registers what to keep while the view is cold. `onRestoreState(fn)` gets it back, even when
 *   registered late.
 * - Names starting with `$` are reserved.
 *
 * The shim also:
 * - announces each document, and each restore from the back/forward cache (`$ready`);
 * - answers the host's `$hello` with its current nonce, then its save provider if it has one (`$saveStateProvider`), and
 *   reports its size again: a host that asks may be a new connection to a page that was already running (a parked page
 *   a new view adopted), whose earlier reports went to an earlier one, or to nobody (a provider registered while the
 *   page was parked);
 * - gives a page restored from the back-forward cache a new nonce, and rejects the calls that page was waiting on,
 *   whose answers the host addresses to the old one;
 * - forwards console output and uncaught errors (`$console`), capped and rate-limited;
 * - reports the document's size and viewport height (`$size`).
 *
 * It captures built-ins at install (`JSON`, `Promise`, `Error`, `TypeError`, timers…), so pages that replace them or
 * patch prototypes later can't break it: its rejections stay real errors. Nothing here names the host app.
 */
public object BridgeShim {
    /** The longest console message text kept; longer text is cut and ends with an ellipsis. */
    public const val CONSOLE_TEXT_MAX: Int = 8192

    /**
     * The shim's JavaScript.
     * @param sendExpression a JS function expression that takes one string: the engine's channel to the host.
     * @param mainFrameExpression a JS expression that is true in the view's main document. The shim installs only
     *   there.
     * @param callTimeoutMillis how long a page-side `call` waits for the host.
     */
    public fun source(
        sendExpression: String,
        mainFrameExpression: String = "window === window.top",
        callTimeoutMillis: Long = 30_000,
    ): String = TEMPLATE
        .replace("__SEND__", sendExpression)
        .replace("__MAIN_FRAME__", mainFrameExpression)
        .replace("__MAX_MESSAGE__", BridgeCodec.DEFAULT_MAX_MESSAGE_CHARS.toString())
        .replace("__MAX_REPLY__", BridgeCodec.DEFAULT_MAX_REPLY_CHARS.toString())
        .replace("__CALL_TIMEOUT__", callTimeoutMillis.toString())
        .replace("__CONSOLE_MAX__", CONSOLE_TEXT_MAX.toString())

    private val TEMPLATE = """
(function () {
  if (window.hostBridge) return;
  try { if (!(__MAIN_FRAME__)) return; } catch (e) { return; }
  var send = __SEND__;
  var MAX_MESSAGE = __MAX_MESSAGE__, MAX_REPLY = __MAX_REPLY__, CALL_TIMEOUT = __CALL_TIMEOUT__, CONSOLE_MAX = __CONSOLE_MAX__;
  var stringify = JSON.stringify, parse = JSON.parse, P = Promise, Err = Error, TypeErr = TypeError, setTimer = setTimeout, clearTimer = clearTimeout;
  var defineProperty = Object.defineProperty, create = Object.create, freeze = Object.freeze, now = Date.now, isArray = Array.isArray;
  var raf = typeof requestAnimationFrame === 'function' ? requestAnimationFrame : function (f) { return setTimer(f, 16); };
  var nextId = 1, pending = create(null), listeners = create(null), methods = create(null);
  var saveProvider = null, restoreListener = null, restored = null, hasRestored = false;
  function nonce() {
    try { var a = new Uint32Array(4); crypto.getRandomValues(a); return a[0].toString(36) + a[1].toString(36) + a[2].toString(36) + a[3].toString(36); }
    catch (e) { return String(Math.random()).slice(2) + String(now()); }
  }
  var documentNonce = nonce(), lastSpokeUp = -1e9;
  function announce() { out({ t: 'event', n: '${'$'}ready', d: { nonce: documentNonce }, from: documentNonce }); }
  function tellSaveProvider() { out({ t: 'event', n: '${'$'}saveStateProvider', d: true, from: documentNonce }); }
  // The host wrote to another document, so the nonce it has for this one is stale (its announcement was lost, say).
  // Announce again, at most once a second; the host ignores an announcement it already has.
  function speakUp() {
    var t = now(), since = t - lastSpokeUp;
    if (since >= 0 && since < 1000) return;
    lastSpokeUp = t;
    announce();
  }
  function out(msg, limit) {
    var text;
    try { text = stringify(msg); } catch (e) { return false; }
    if (typeof text !== 'string' || text.length > (limit || MAX_MESSAGE)) return false;
    try { send(text); return true; } catch (e) { return false; }
  }
  function reserved(name) { return typeof name !== 'string' || name.charAt(0) === '$'; }
  function later(fn) { setTimer(fn, 0); }
  function rethrowLater(error) { later(function () { throw error; }); }
  function deliver(name, data) {
    var ls = listeners[name];
    if (!ls) return;
    var copy = [];
    for (var i = 0; i < ls.length; i++) copy[i] = ls[i];
    for (var j = 0; j < copy.length; j++) { try { copy[j](data); } catch (e) { rethrowLater(e); } }
  }
  function answer(msg) {
    var fn = msg.m === '${'$'}saveState' ? function () { return saveProvider ? saveProvider() : null; } : (reserved(msg.m) ? null : methods[msg.m]);
    P.resolve().then(function () {
      if (typeof fn !== 'function') throw new Err('no such method: ' + msg.m);
      return fn(msg.p);
    }).then(function (r) {
      if (!out({ t: 'reply', id: msg.id, r: r === undefined ? null : r, from: documentNonce }, MAX_REPLY)) out({ t: 'reply', id: msg.id, e: 'the reply could not be sent (too large or not JSON)', from: documentNonce });
    }, function (e) {
      out({ t: 'reply', id: msg.id, e: String(e && e.message || e), from: documentNonce });
    });
  }
  function rejectPending(reason) {
    var ids = [], id;
    for (id in pending) ids[ids.length] = id;
    for (var i = 0; i < ids.length; i++) {
      var p = pending[ids[i]];
      delete pending[ids[i]];
      clearTimer(p.timer);
      p.reject(new Err(reason));
    }
  }
  function receive(json) {
    if (typeof json !== 'string') return;
    var msg;
    try { msg = parse(json); } catch (e) { return; }
    if (!msg || typeof msg !== 'object') return;
    if (msg.t === 'event' && msg.n === '${'$'}hello') {
      // A host that asks may be a new connection: the page tells it again what earlier ones heard, or what nobody heard
      // while the page was parked (its save provider, its size).
      announce();
      if (saveProvider) tellSaveProvider();
      lastW = lastH = lastVh = -1;
      scheduleSize();
      return;
    }
    if (msg.to !== documentNonce) {
      if (typeof msg.to === 'string') speakUp();
      return;
    }
    if (msg.t === 'reply') {
      var p = pending[msg.id];
      if (!p) return;
      delete pending[msg.id];
      clearTimer(p.timer);
      if (typeof msg.e === 'string') p.reject(new Err(msg.e)); else p.resolve(msg.r);
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
  function checkName(api, name, fn, needsFunction) {
    if (reserved(name)) throw new TypeErr('hostBridge.' + api + ': names starting with $ are reserved');
    if (needsFunction && typeof fn !== 'function') throw new TypeErr('hostBridge.' + api + ': the second argument must be a function');
  }
  var api = freeze({
    call: function (method, params) {
      if (reserved(method)) return P.reject(new TypeErr('hostBridge.call: names starting with $ are reserved'));
      var id = nextId++;
      return new P(function (resolve, reject) {
        var timer = setTimer(function () {
          if (pending[id]) { delete pending[id]; reject(new Err('hostBridge: the host did not answer in time')); }
        }, CALL_TIMEOUT);
        pending[id] = { resolve: resolve, reject: reject, timer: timer };
        if (!out({ t: 'call', id: id, m: method, p: params === undefined ? null : params, from: documentNonce })) {
          delete pending[id];
          clearTimer(timer);
          reject(new Err('hostBridge: the call could not be sent (too large, not JSON, or no channel)'));
        }
      });
    },
    post: function (name, data) {
      checkName('post', name);
      return out({ t: 'event', n: name, d: data === undefined ? null : data, from: documentNonce });
    },
    on: function (name, fn) {
      checkName('on', name, fn, true);
      var ls = listeners[name] || (listeners[name] = []);
      ls[ls.length] = fn;
      return function () {
        var cur = listeners[name];
        if (!cur) return;
        var kept = [], removed = false;
        for (var i = 0; i < cur.length; i++) {
          if (!removed && cur[i] === fn) { removed = true; continue; }
          kept[kept.length] = cur[i];
        }
        listeners[name] = kept;
      };
    },
    handle: function (method, fn) {
      checkName('handle', method, fn, true);
      methods[method] = fn;
      return function () { if (methods[method] === fn) delete methods[method]; };
    },
    onSaveState: function (fn) {
      saveProvider = typeof fn === 'function' ? fn : null;
      if (saveProvider) tellSaveProvider();
    },
    onRestoreState: function (fn) {
      restoreListener = typeof fn === 'function' ? fn : null;
      if (restoreListener && hasRestored) { var f = restoreListener, d = restored; later(function () { f(d); }); }
    }
  });
  defineProperty(window, 'hostBridge', { value: api, writable: false, configurable: false, enumerable: false });
  announce();
  window.addEventListener('pageshow', function (e) {
    if (!e.persisted) return;
    // A new life with a new nonce. The host addresses its answers to the calls pending here to the old nonce, which
    // receive() now drops: reject them instead of letting them time out.
    documentNonce = nonce();
    rejectPending('hostBridge: the page was restored from the back-forward cache');
    announce();
  });
  function describe(a) {
    try {
      if (typeof a === 'string') return a;
      if (a instanceof Err) return a.stack ? String(a.stack) : String(a.name) + ': ' + String(a.message);
      if (a === undefined || a === null || typeof a !== 'object') return String(a);
      var ancestors = [], nodes = 0;
      var text = stringify(a, function (key, value) {
        if (typeof value === 'bigint' || typeof value === 'function' || typeof value === 'symbol') return String(value);
        if (value && typeof value === 'object') {
          while (ancestors.length && ancestors[ancestors.length - 1] !== this) ancestors.length--;
          for (var i = 0; i < ancestors.length; i++) if (ancestors[i] === value) return '[Circular]';
          if (++nodes > 2000) return '[…]';
          if (isArray(value) && value.length > 1000) {
            var head = [];
            for (var k = 0; k < 1000; k++) head[k] = value[k];
            head[1000] = '… ' + (value.length - 1000) + ' more';
            value = head;
          }
          ancestors[ancestors.length] = value;
        }
        return value;
      });
      return typeof text === 'string' ? text : String(a);
    } catch (e) {
      return '[unprintable]';
    }
  }
  var consoleWindowStart = 0, consoleCount = 0, consoleDropped = 0, inConsole = false;
  function flushDropped() {
    if (consoleDropped === 0) return;
    var n = consoleDropped;
    consoleDropped = 0;
    out({ t: 'event', n: '${'$'}console', d: { level: 'warn', text: n + ' console messages were dropped (more than 100 a second)' }, from: documentNonce });
  }
  function isResizeObserverNoise(text) {
    return text.indexOf('ResizeObserver loop completed with undelivered notifications') === 0 || text.indexOf('ResizeObserver loop limit exceeded') === 0;
  }
  function emitConsole(d) {
    if (isResizeObserverNoise(d.text)) return;
    var t = now();
    if (t - consoleWindowStart >= 1000) {
      flushDropped();
      consoleWindowStart = t;
      consoleCount = 0;
    }
    if (consoleCount >= 100) {
      if (consoleDropped++ === 0) setTimer(flushDropped, 1000);
      return;
    }
    consoleCount++;
    if (d.text.length > CONSOLE_MAX) d.text = d.text.slice(0, CONSOLE_MAX) + '…';
    out({ t: 'event', n: '${'$'}console', d: d, from: documentNonce });
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
  var lastW = -1, lastH = -1, lastVh = -1, sizeScheduled = false, SCAN_LIMIT = 2000, SCAN_EVERY = 250;
  var lastScan = -1e9, scanBottom = 0, trailingScan = false;
  function scan(top) {
    var all = document.body.getElementsByTagName('*');
    var n = all.length < SCAN_LIMIT ? all.length : SCAN_LIMIT, bottom = 0;
    for (var i = 0; i < n; i++) { var b = all[i].getBoundingClientRect().bottom - top; if (b > bottom) bottom = b; }
    return bottom;
  }
  function measure() {
    var de = document.documentElement;
    var vw = window.innerWidth, vh = window.innerHeight;
    if (!de || !vw || !vh || document.hidden) return null;
    var box = de.getBoundingClientRect();
    var h = box.height;
    if (de.scrollHeight > h + 1 && document.body) {
      var t = now();
      if (t - lastScan >= SCAN_EVERY) {
        scanBottom = scan(box.top);
        lastScan = t;
      } else if (!trailingScan) {
        trailingScan = true;
        setTimer(function () { trailingScan = false; lastScan = -1e9; scheduleSize(); }, SCAN_EVERY);
      }
      if (scanBottom > h) h = scanBottom;
    }
    return { w: Math.ceil(box.width), h: Math.ceil(h), vh: vh };
  }
  function reportSize() {
    sizeScheduled = false;
    var m = measure();
    if (!m || (m.w === lastW && m.h === lastH && m.vh === lastVh)) return;
    lastW = m.w;
    lastH = m.h;
    lastVh = m.vh;
    out({ t: 'event', n: '${'$'}size', d: m, from: documentNonce });
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
