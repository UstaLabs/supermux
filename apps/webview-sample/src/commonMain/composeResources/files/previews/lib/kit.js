/*
 * The chat demo's preview kit, shared by every preview page (a classic script: `window.Kit`).
 * - Kit.variant(fallback): the variant the app asked for. The app writes window.PREVIEW_VARIANT into the page it serves;
 *   opened by hand, `?v=<name>` picks one.
 * - Kit.loop(step): requestAnimationFrame capped at 60 fps (a 120 Hz screen draws every other frame), with dt in seconds.
 *   A hidden page draws nothing (its engine stops its frames), and a page shown again doesn't jump.
 * - Kit.fit(canvas): the canvas's backing store at its CSS size times the screen's density, at most 2.
 * - Kit.noise2 / Kit.noise3: simplex noise, about -1..1 (Stefan Gustavson's public-domain algorithm).
 * - Kit.post / Kit.call / Kit.on: the page's side of the app's bridge (window.hostBridge), harmless where there's none.
 */
(function () {
  'use strict';
  var K = {};

  K.variant = function (fallback) {
    var given = window.PREVIEW_VARIANT;
    if (!given) {
      try { given = new URLSearchParams(location.search).get('v'); } catch (e) { given = null; }
    }
    return given || fallback;
  };

  /** presets[variant], or presets[fallback] when the variant is unknown. */
  K.pick = function (presets, fallback) {
    var v = K.variant(fallback);
    return presets[v] || presets[fallback];
  };

  K.dpr = function (max) {
    return Math.min(window.devicePixelRatio || 1, max || 2);
  };

  K.dark = function () {
    return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  };

  /** Calls fn(dark) now and whenever the theme changes. */
  K.onTheme = function (fn) {
    fn(K.dark());
    if (!window.matchMedia) return;
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    var listener = function () { fn(mq.matches); };
    if (mq.addEventListener) mq.addEventListener('change', listener); else if (mq.addListener) mq.addListener(listener);
  };

  K.loop = function (step, opts) {
    var fps = (opts && opts.fps) || 60;
    var minGap = 1000 / fps * 0.75;
    var last = -1;
    var running = true;
    function frame(now) {
      if (!running) return;
      requestAnimationFrame(frame);
      if (last < 0) { last = now; return; }
      var gap = now - last;
      if (gap < minGap) return;
      last = now;
      step(Math.min(gap, 64) / 1000, now / 1000);
    }
    requestAnimationFrame(frame);
    document.addEventListener('visibilitychange', function () { last = -1; });
    return {
      stop: function () { running = false; },
      start: function () { if (!running) { running = true; last = -1; requestAnimationFrame(frame); } },
      get running() { return running; }
    };
  };

  /** Sizes canvas's backing store to its CSS box at the screen's density (at most maxDpr, 2 by default). */
  K.fit = function (canvas, maxDpr) {
    var dpr = K.dpr(maxDpr || 2);
    var cw = canvas.clientWidth || 1, ch = canvas.clientHeight || 1;
    var w = Math.max(1, Math.round(cw * dpr)), h = Math.max(1, Math.round(ch * dpr));
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    return { w: w, h: h, dpr: dpr, cw: cw, ch: ch };
  };

  /** Calls fn() now and whenever el's size changes. */
  K.watch = function (el, fn) {
    if (typeof ResizeObserver === 'function') new ResizeObserver(function () { fn(); }).observe(el);
    else window.addEventListener('resize', fn);
    fn();
  };

  /** A seeded random generator, 0..1 (mulberry32). */
  K.rng = function (seed) {
    var a = (seed >>> 0) || 1;
    return function () {
      a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  };

  K.clamp = function (v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; };
  K.lerp = function (a, b, t) { return a + (b - a) * t; };
  K.smooth = function (t) { t = K.clamp(t, 0, 1); return t * t * (3 - 2 * t); };

  /** '#rrggbb' as [r, g, b], 0..255. */
  K.rgb = function (hex) {
    var n = parseInt(hex.replace('#', ''), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  };

  /** The colour between hex colours a and b at t, as 'rgb(r,g,b)'. */
  K.mix = function (a, b, t) {
    var x = K.rgb(a), y = K.rgb(b);
    return 'rgb(' + Math.round(K.lerp(x[0], y[0], t)) + ',' + Math.round(K.lerp(x[1], y[1], t)) + ',' +
      Math.round(K.lerp(x[2], y[2], t)) + ')';
  };

  // Simplex noise, 2D and 3D.
  var grad3 = new Float32Array([1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1,
    0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1]);
  var perm = new Uint8Array(512), permMod12 = new Uint8Array(512);
  (function seed() {
    var rand = K.rng(1337), p = new Uint8Array(256), i;
    for (i = 0; i < 256; i++) p[i] = i;
    for (i = 255; i > 0; i--) { var r = Math.floor(rand() * (i + 1)); var t = p[i]; p[i] = p[r]; p[r] = t; }
    for (i = 0; i < 512; i++) { perm[i] = p[i & 255]; permMod12[i] = perm[i] % 12; }
  })();
  var F2 = 0.5 * (Math.sqrt(3) - 1), G2 = (3 - Math.sqrt(3)) / 6, F3 = 1 / 3, G3 = 1 / 6;

  K.noise2 = function (xin, yin) {
    var n0 = 0, n1 = 0, n2 = 0;
    var s = (xin + yin) * F2;
    var i = Math.floor(xin + s), j = Math.floor(yin + s);
    var t = (i + j) * G2;
    var x0 = xin - (i - t), y0 = yin - (j - t);
    var i1 = x0 > y0 ? 1 : 0, j1 = x0 > y0 ? 0 : 1;
    var x1 = x0 - i1 + G2, y1 = y0 - j1 + G2, x2 = x0 - 1 + 2 * G2, y2 = y0 - 1 + 2 * G2;
    var ii = i & 255, jj = j & 255, gi;
    var t0 = 0.5 - x0 * x0 - y0 * y0;
    if (t0 >= 0) { gi = permMod12[ii + perm[jj]] * 3; t0 *= t0; n0 = t0 * t0 * (grad3[gi] * x0 + grad3[gi + 1] * y0); }
    var t1 = 0.5 - x1 * x1 - y1 * y1;
    if (t1 >= 0) { gi = permMod12[ii + i1 + perm[jj + j1]] * 3; t1 *= t1; n1 = t1 * t1 * (grad3[gi] * x1 + grad3[gi + 1] * y1); }
    var t2 = 0.5 - x2 * x2 - y2 * y2;
    if (t2 >= 0) { gi = permMod12[ii + 1 + perm[jj + 1]] * 3; t2 *= t2; n2 = t2 * t2 * (grad3[gi] * x2 + grad3[gi + 1] * y2); }
    return 70 * (n0 + n1 + n2);
  };

  K.noise3 = function (xin, yin, zin) {
    var n0 = 0, n1 = 0, n2 = 0, n3 = 0;
    var s = (xin + yin + zin) * F3;
    var i = Math.floor(xin + s), j = Math.floor(yin + s), k = Math.floor(zin + s);
    var t = (i + j + k) * G3;
    var x0 = xin - (i - t), y0 = yin - (j - t), z0 = zin - (k - t);
    var i1, j1, k1, i2, j2, k2;
    if (x0 >= y0) {
      if (y0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
      else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; }
      else { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; }
    } else {
      if (y0 < z0) { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; }
      else if (x0 < z0) { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; }
      else { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
    }
    var x1 = x0 - i1 + G3, y1 = y0 - j1 + G3, z1 = z0 - k1 + G3;
    var x2 = x0 - i2 + 2 * G3, y2 = y0 - j2 + 2 * G3, z2 = z0 - k2 + 2 * G3;
    var x3 = x0 - 1 + 3 * G3, y3 = y0 - 1 + 3 * G3, z3 = z0 - 1 + 3 * G3;
    var ii = i & 255, jj = j & 255, kk = k & 255, gi;
    var t0 = 0.6 - x0 * x0 - y0 * y0 - z0 * z0;
    if (t0 >= 0) { gi = permMod12[ii + perm[jj + perm[kk]]] * 3; t0 *= t0; n0 = t0 * t0 * (grad3[gi] * x0 + grad3[gi + 1] * y0 + grad3[gi + 2] * z0); }
    var t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1;
    if (t1 >= 0) { gi = permMod12[ii + i1 + perm[jj + j1 + perm[kk + k1]]] * 3; t1 *= t1; n1 = t1 * t1 * (grad3[gi] * x1 + grad3[gi + 1] * y1 + grad3[gi + 2] * z1); }
    var t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2;
    if (t2 >= 0) { gi = permMod12[ii + i2 + perm[jj + j2 + perm[kk + k2]]] * 3; t2 *= t2; n2 = t2 * t2 * (grad3[gi] * x2 + grad3[gi + 1] * y2 + grad3[gi + 2] * z2); }
    var t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3;
    if (t3 >= 0) { gi = permMod12[ii + 1 + perm[jj + 1 + perm[kk + 1]]] * 3; t3 *= t3; n3 = t3 * t3 * (grad3[gi] * x3 + grad3[gi + 1] * y3 + grad3[gi + 2] * z3); }
    return 32 * (n0 + n1 + n2 + n3);
  };

  /** Fractal noise: octaves of noise2, about -1..1. */
  K.fbm2 = function (x, y, octaves) {
    var sum = 0, amp = 0.5, freq = 1, norm = 0;
    for (var o = 0; o < (octaves || 3); o++) {
      sum += amp * K.noise2(x * freq, y * freq);
      norm += amp;
      amp *= 0.5;
      freq *= 2.03;
    }
    return sum / norm;
  };

  // The app's bridge. A page opened by hand has none: posts go nowhere and calls answer null.
  K.post = function (name, data) {
    var b = window.hostBridge;
    if (!b) return false;
    try { return b.post(name, data === undefined ? null : data); } catch (e) { return false; }
  };
  K.call = function (name, params) {
    var b = window.hostBridge;
    if (!b) return Promise.resolve(null);
    return b.call(name, params === undefined ? null : params).catch(function () { return null; });
  };
  K.on = function (name, fn) {
    var b = window.hostBridge;
    return b ? b.on(name, fn) : function () {};
  };
  /** What the page keeps while its view is cold: save() is asked when its engine goes, restore(saved) gets it back. */
  K.keep = function (save, restore) {
    var b = window.hostBridge;
    if (!b) return;
    b.onSaveState(save);
    b.onRestoreState(function (saved) { if (saved) restore(saved); });
  };

  window.Kit = K;
})();
