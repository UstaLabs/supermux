// Reference renderer for the supermux avatar format v2 (see FORMAT.md).
// Mirrors apps/ui/.../AgentAvatarRig.kt — keep the two in step. Used by the broker (avatar tools)
// and by tools/preview.ts.
(function (global) {
  const XFADE = 300;
  const STATES = ["idle", "working", "waiting", "asleep", "listening", "speaking"];
  const SHAPES = ["path", "circle", "ellipse", "rect", "group"];
  const PROPS = ["x", "y", "rotate", "scale", "scaleX", "scaleY", "opacity", "fill", "stroke", "strokeWidth"];
  const BEHAVIORS = ["blink", "lookAt", "breathe", "float"];
  const EASES = ["linear", "in", "out", "inOut", "step"];

  function hash(s) { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) | 0; return Math.abs(h); }
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function ease(name, p) {
    switch (name) {
      case "linear": return p;
      case "in": return p * p;
      case "out": return 1 - (1 - p) * (1 - p);
      case "step": return p < 1 ? 0 : 1;
      default: return p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
    }
  }
  function parseColor(c) {
    if (typeof c !== "string" || c[0] !== "#") return null;
    const h = c.slice(1);
    if (h.length !== 6 && h.length !== 8) return null;
    const n = [0, 2, 4, 6].map(i => i < h.length ? parseInt(h.slice(i, i + 2), 16) : 255);
    return n.some(isNaN) ? null : n;
  }
  function lerpColor(a, b, k) {
    const ca = parseColor(a), cb = parseColor(b);
    if (!ca || !cb) return k < 0.5 ? a : b;
    const m = ca.map((v, i) => Math.round(v + (cb[i] - v) * k));
    return "#" + m.map(v => v.toString(16).padStart(2, "0")).join("");
  }
  function firstColor(fill) {
    if (typeof fill === "string") return fill === "none" ? null : fill;
    const g = fill && (fill.linear || fill.radial);
    const s = g && g.stops && g.stops[0];
    return s ? (Array.isArray(s) ? s[1] : s) : null;
  }

  function stateOf(spec, name) {
    const s = spec.states || {};
    return s[name] || s.idle || {};
  }
  // Hover is an overlay; "hover:<state>" overrides it for one state.
  function hoverOf(spec, name) {
    const s = spec.states || {};
    return s["hover:" + name] || s.hover || {};
  }

  // keys: [t, value] or [t, value, ease] — the third entry eases the segment that STARTS there.
  function trackValue(tr, ctx, enteredAt) {
    const keys = tr.keys || [];
    if (!keys.length) return null;
    const dur = Math.max(1, tr.duration || 1000);
    const delay = tr.delay || 0;
    let p;
    if (tr.driver === "level") p = clamp(ctx.level || 0, 0, 1);
    else if (tr.loop === false) p = clamp((ctx.t - enteredAt - delay) / dur, 0, 1);
    else p = ((((ctx.t + ctx.phase) - delay) % dur) + dur) % dur / dur;
    if (p <= keys[0][0]) return keys[0][1];
    for (let i = 0; i < keys.length - 1; i++) {
      const [t0, v0, e0] = keys[i], [t1, v1] = keys[i + 1];
      if (p >= t0 && p <= t1) {
        const k = ease(e0 || tr.ease || (tr.driver === "level" ? "linear" : "inOut"), t1 > t0 ? (p - t0) / (t1 - t0) : 1);
        return typeof v0 === "number" ? v0 + (v1 - v0) * k : lerpColor(v0, v1, k);
      }
    }
    return keys[keys.length - 1][1];
  }

  function baseValues(part) {
    return {
      x: part.x || 0, y: part.y || 0, rotate: part.rotate || 0,
      scale: part.scale ?? 1, scaleX: part.scaleX ?? 1, scaleY: part.scaleY ?? 1,
      opacity: part.opacity ?? 1, fill: null, stroke: null, strokeWidth: 1, vis: 1,
    };
  }

  function applyTracks(v, layer, part, ctx, enteredAt) {
    for (const tr of layer.tracks || []) {
      if (tr.part !== part.id) continue;
      let val = trackValue(tr, ctx, enteredAt);
      if (val == null) continue;
      // "gain": "level" — the louder, the bigger the motion (silent = no motion).
      if (tr.gain === "level" && typeof val === "number") {
        const g = clamp(ctx.level || 0, 0, 1);
        val = ["x", "y", "rotate"].includes(tr.prop) ? val * g : 1 + (val - 1) * g;
      }
      switch (tr.prop) {
        case "x": case "y": case "rotate": v[tr.prop] += val; break;
        case "scale": case "scaleX": case "scaleY": case "opacity": case "strokeWidth": v[tr.prop] *= val; break;
        case "fill": v.fill = val; break;
        case "stroke": v.stroke = val; break;
      }
    }
  }

  function applyBehaviors(v, part, disabled, ctx) {
    const t = ctx.t + ctx.phase, h = hash(part.id || "");
    for (const b of part.behaviors || []) {
      if (disabled.has(b.type)) continue;
      if (b.type === "blink") {
        const every = (b.every || 3500) + (h % 1400) - 700;
        const ph = ctx.forceBlink ? 70 : (t + h * 137) % every;
        if (ph < 140) v.scaleY *= 1 - 0.9 * Math.sin(Math.PI * ph / 140);
      } else if (b.type === "breathe") {
        v.scale *= 1 + (b.amount ?? 0.03) * Math.sin(2 * Math.PI * t / (b.period || 3200));
      } else if (b.type === "float") {
        v.y += (b.amount ?? 2) * Math.sin(2 * Math.PI * t / (b.period || 2400));
      } else if (b.type === "lookAt") {
        const r = b.range ?? 3;
        const px = ctx.pointer ? ctx.pointer[0] : Math.sin(t / 1700) * 0.6;
        const py = ctx.pointer ? ctx.pointer[1] : Math.sin(t / 2300) * 0.3;
        v.x += clamp(px, -1, 1) * r; v.y += clamp(py, -1, 1) * r;
      }
    }
  }

  function visible(part, stateName, hovered) {
    const has = (k, s) => (part[k] || []).includes(s);
    const inSet = k => has(k, stateName) || (hovered && has(k, "hover"));
    if (part.showIn && !inSet("showIn")) return 0;
    if (part.hideIn && inSet("hideIn")) return 0;
    return 1;
  }

  // The animated values of one part in one state, with the hover overlay at strength hoverAmt.
  function evalPart(spec, part, stateName, ctx, enteredAt) {
    const st = stateOf(spec, stateName), hov = hoverOf(spec, stateName);
    const plain = baseValues(part);
    applyTracks(plain, st, part, ctx, enteredAt);
    applyBehaviors(plain, part, new Set(st.disable || []), ctx);
    plain.vis = visible(part, stateName, false);
    const amt = ctx.hoverAmt ?? (ctx.hovered ? 1 : 0);
    if (amt <= 0) return plain;
    const over = baseValues(part);
    applyTracks(over, st, part, ctx, enteredAt);
    applyTracks(over, hov, part, ctx, ctx.hoverAt || 0);
    applyBehaviors(over, part, new Set([...(st.disable || []), ...(hov.disable || [])]), ctx);
    over.vis = visible(part, stateName, true);
    return amt >= 1 ? over : mix(plain, over, amt);
  }

  function mix(a, b, k) {
    const o = {};
    for (const key of Object.keys(b)) {
      if (key === "fill" || key === "stroke") o[key] = a[key] && b[key] ? lerpColor(a[key], b[key], k) : (k < 0.5 ? a[key] : b[key]);
      else if (key === "rotate") {
        const d = ((((b.rotate - a.rotate) % 360) + 540) % 360) - 180; // the short way round
        o.rotate = a.rotate + d * k;
      } else o[key] = a[key] + (b[key] - a[key]) * k;
    }
    return o;
  }

  let gid = 0;
  function paint(fill, defs) {
    if (fill == null) return "none";
    if (typeof fill === "string") return fill;
    const id = "g" + (gid++);
    const stops = (g) => (g.stops || []).map((s, i, arr) => {
      const [off, col] = Array.isArray(s) ? s : [arr.length > 1 ? i / (arr.length - 1) : 0, s];
      return `<stop offset="${off}" stop-color="${col.slice(0, 7)}" stop-opacity="${col.length === 9 ? parseInt(col.slice(7), 16) / 255 : 1}"/>`;
    }).join("");
    if (fill.linear) {
      const g = fill.linear, [x1, y1] = g.from || [0, 0], [x2, y2] = g.to || [0, 100];
      defs.push(`<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}">${stops(g)}</linearGradient>`);
    } else if (fill.radial) {
      const g = fill.radial, [cx, cy] = g.center || [50, 50];
      defs.push(`<radialGradient id="${id}" gradientUnits="userSpaceOnUse" cx="${cx}" cy="${cy}" r="${g.radius || 50}">${stops(g)}</radialGradient>`);
    } else return "none";
    return `url(#${id})`;
  }

  // Centre of a path's bounds, from its points and control points (no DOM needed — the same
  // control-point bounds Compose's Path.getBounds() reports).
  function pathCenter(d) {
    const toks = d.match(/[a-zA-Z]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?/g) || [];
    const arity = { M: 2, L: 2, T: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, A: 7, Z: 0 };
    let i = 0, cmd = "M", x = 0, y = 0, sx = 0, sy = 0;
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    const add = (px, py) => { minX = Math.min(minX, px); maxX = Math.max(maxX, px); minY = Math.min(minY, py); maxY = Math.max(maxY, py); };
    while (i < toks.length) {
      if (/[a-zA-Z]/.test(toks[i])) cmd = toks[i++];
      const up = cmd.toUpperCase(), rel = cmd !== up, n = arity[up];
      if (n === undefined) break;
      if (up === "Z") { x = sx; y = sy; continue; }
      const a = toks.slice(i, i + n).map(Number); i += n;
      if (a.length < n || a.some(isNaN)) break;
      const ox = rel ? x : 0, oy = rel ? y : 0;
      if (up === "H") { x = a[0] + (rel ? x : 0); add(x, y); }
      else if (up === "V") { y = a[0] + (rel ? y : 0); add(x, y); }
      else if (up === "A") { x = a[5] + ox; y = a[6] + oy; add(x, y); }
      else {
        for (let k = 0; k < n; k += 2) add(a[k] + ox, a[k + 1] + oy);
        x = a[n - 2] + ox; y = a[n - 1] + oy;
      }
      if (up === "M") { sx = x; sy = y; cmd = rel ? "l" : "L"; }
    }
    return isFinite(minX) ? [(minX + maxX) / 2, (minY + maxY) / 2] : null;
  }

  // The part's own centre, for "pivot": "center" (paths are measured once, in the browser).
  const bboxCache = new Map();
  function ownCenter(p) {
    switch (p.shape || (p.d ? "path" : "group")) {
      case "circle": case "ellipse": return [p.cx ?? 50, p.cy ?? 50];
      case "rect": return [(p.x0 ?? 0) + (p.w ?? 10) / 2, (p.y0 ?? 0) + (p.h ?? 10) / 2];
      case "path": {
        if (!bboxCache.has(p.d)) bboxCache.set(p.d, pathCenter(p.d));
        return bboxCache.get(p.d);
      }
      default: { // group: the centre of its children's centres
        const cs = (p.children || []).map(ownCenter).filter(Boolean);
        return cs.length ? [cs.reduce((a, c) => a + c[0], 0) / cs.length, cs.reduce((a, c) => a + c[1], 0) / cs.length] : null;
      }
    }
  }

  function shapeSvg(p, fillAttr, stroke, strokeWidth, filt) {
    const st = stroke ? ` stroke="${stroke}" stroke-width="${strokeWidth}" stroke-linecap="${p.lineCap || "butt"}" stroke-linejoin="round"` : "";
    const common = ` fill="${fillAttr}"${st}${filt}`;
    switch (p.shape || (p.d ? "path" : "group")) {
      case "path": return `<path d="${p.d}"${common}/>`;
      case "circle": return `<circle cx="${p.cx ?? 50}" cy="${p.cy ?? 50}" r="${p.r ?? 10}"${common}/>`;
      case "ellipse": return `<ellipse cx="${p.cx ?? 50}" cy="${p.cy ?? 50}" rx="${p.rx ?? 10}" ry="${p.ry ?? 10}"${common}/>`;
      case "rect": return `<rect x="${p.x0 ?? 0}" y="${p.y0 ?? 0}" width="${p.w ?? 10}" height="${p.h ?? 10}" rx="${p.corner ?? 0}"${common}/>`;
      default: return "";
    }
  }

  /**
   * ctx: { state, prevState, switchedAt, t, hovered | hoverAmt, hoverAt, pointer: [-1..1, -1..1] | null,
   *        level: 0..1, seed: string (per Agent, de-syncs identical avatars), forceBlink }
   */
  function render(spec, ctx, px, opts) {
    opts = opts || {};
    ctx = Object.assign({}, ctx, { phase: ctx.seed ? hash(ctx.seed) % 10007 : 0 });
    const size = spec.size || 100;
    const defs = [];
    const k = ctx.prevState && ctx.prevState !== ctx.state ? clamp((ctx.t - (ctx.switchedAt || 0)) / XFADE, 0, 1) : 1;
    const st = stateOf(spec, ctx.state), pst = stateOf(spec, ctx.prevState || ctx.state);
    const sat = (pst.saturation ?? 1) + ((st.saturation ?? 1) - (pst.saturation ?? 1)) * k;
    const fid = "sat" + (gid++);
    function drawPart(part) {
      let v = evalPart(spec, part, ctx.state, ctx, ctx.switchedAt || 0);
      if (k < 1) v = mix(evalPart(spec, part, ctx.prevState, ctx, 0), v, ease("inOut", k));
      let pv = part.pivot;
      if (pv === "center") pv = ownCenter(part);
      const [pvx, pvy] = Array.isArray(pv) ? pv : [size / 2, size / 2];
      const sx = v.scale * v.scaleX, sy = v.scale * v.scaleY;
      const tf = `translate(${v.x} ${v.y}) rotate(${v.rotate} ${pvx} ${pvy}) translate(${pvx} ${pvy}) scale(${sx} ${sy}) translate(${-pvx} ${-pvy})`;
      const op = clamp(v.opacity * v.vis, 0, 1);
      const fill = v.fill ?? (part.fill === undefined ? "#000000" : part.fill);
      const stroke = v.stroke ?? part.stroke;
      const filt = part.keepColor || sat >= 0.999 ? "" : ` filter="url(#${fid})"`;
      const inner = shapeSvg(part, fill === "none" ? "none" : paint(fill, defs), stroke, (part.strokeWidth ?? 1) * v.strokeWidth, filt) +
        (part.children || []).map(drawPart).join("");
      return `<g transform="${tf}" opacity="${op}">${inner}</g>`;
    }
    const op = (pst.opacity ?? 1) + ((st.opacity ?? 1) - (pst.opacity ?? 1)) * k;
    const body = (spec.parts || []).map(drawPart).join("");
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${px}" height="${px}" viewBox="0 0 ${size} ${size}" overflow="${opts.clip ? "hidden" : "visible"}">` +
      `<defs>${defs.join("")}<filter id="${fid}" color-interpolation-filters="sRGB"><feColorMatrix type="saturate" values="${sat}"/></filter></defs>` +
      `<g opacity="${op}">${body}</g></svg>`;
  }

  function validate(spec) {
    const errs = [];
    const okStates = [...STATES, "hover"];
    if (spec.format !== "supermux-avatar") errs.push(`"format" should be "supermux-avatar"`);
    const ids = new Set();
    (function walk(parts) {
      for (const p of parts || []) {
        const where = `part "${p.id}"`;
        if (!p.id) errs.push("a part has no id");
        else if (ids.has(p.id)) errs.push(`duplicate part id "${p.id}"`);
        ids.add(p.id);
        const shape = p.shape || (p.d ? "path" : "group");
        if (!SHAPES.includes(shape)) errs.push(`${where}: unknown shape "${shape}" (${SHAPES.join(", ")})`);
        if (shape === "path" && !p.d) errs.push(`${where}: shape "path" needs "d"`);
        if (p.d && !/^[\sMmLlHhVvCcSsQqTtAaZz0-9eE.,+-]+$/.test(p.d)) errs.push(`${where}: bad characters in "d"`);
        if (p.d && !/^\s*[Mm]/.test(p.d)) errs.push(`${where}: "d" must start with M`);
        if (p.stroke && p.fill === undefined && shape !== "group") errs.push(`warning: ${where}: has a stroke but no "fill" — the default fill is black; add "fill": "none" for a line`);
        if (p.pivot !== undefined && p.pivot !== "center" && !(Array.isArray(p.pivot) && p.pivot.length === 2)) errs.push(`${where}: "pivot" must be [x, y] or "center"`);
        for (const k of ["showIn", "hideIn"]) for (const s of p[k] || []) if (!okStates.includes(s)) errs.push(`${where}: unknown state "${s}" in ${k} (${okStates.join(", ")})`);
        for (const b of p.behaviors || []) if (!BEHAVIORS.includes(b.type)) errs.push(`${where}: unknown behavior "${b.type}" (${BEHAVIORS.join(", ")})`);
        walk(p.children);
      }
    })(spec.parts);
    for (const [name, st] of Object.entries(spec.states || {})) {
      const base = name.startsWith("hover:") ? name.slice(6) : null;
      if (!okStates.includes(name) && !(base && STATES.includes(base))) errs.push(`unknown state "${name}" (${okStates.join(", ")}, or hover:<state>)`);
      for (const d of st.disable || []) if (!BEHAVIORS.includes(d)) errs.push(`state ${name}: unknown behavior "${d}" in disable`);
      for (const tr of st.tracks || []) {
        const w = `state ${name}, track on "${tr.part}"`;
        if (!ids.has(tr.part)) errs.push(`${w}: unknown part`);
        if (!PROPS.includes(tr.prop)) errs.push(`${w}: unknown prop "${tr.prop}" (${PROPS.join(", ")})`);
        if (tr.ease && !EASES.includes(tr.ease)) errs.push(`${w}: unknown ease "${tr.ease}"`);
        if (tr.gain && tr.gain !== "level") errs.push(`${w}: unknown gain "${tr.gain}"`);
        if (tr.driver && !["time", "level"].includes(tr.driver)) errs.push(`${w}: unknown driver "${tr.driver}"`);
        if (!Array.isArray(tr.keys) || tr.keys.length < 1) errs.push(`${w}: no keys`);
        for (const key of tr.keys || []) {
          if (!Array.isArray(key) || key.length < 2) { errs.push(`${w}: a key must be [t, value] or [t, value, ease]`); continue; }
          if (key[2] !== undefined && !EASES.includes(key[2])) errs.push(`${w}: unknown ease "${key[2]}" in a key`);
          const colour = tr.prop === "fill" || tr.prop === "stroke";
          if (colour ? !parseColor(key[1]) : typeof key[1] !== "number") errs.push(`${w}: value ${JSON.stringify(key[1])} should be ${colour ? "a #RRGGBB colour" : "a number"}`);
        }
      }
    }
    return errs;
  }

  global.SupermuxAvatar = { render, validate, STATES, pathCenter };
})(typeof window !== "undefined" ? window : globalThis);
