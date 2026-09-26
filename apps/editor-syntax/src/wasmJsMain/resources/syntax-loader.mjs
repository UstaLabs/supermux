// supermux editor-syntax: browser (and Node) loader for supermux-syntax.wasm, the SAME ses_* C
// binding as the native libraries (tree-sitter 0.25.10, the bridge, the tables loader, zlib and
// every grammar's code) compiled to one wasm32-wasi reactor module (native/README.md, "Web").
// Plain ES module, no dependencies; shipped as a wasmJs resource next to the wasm binary and
// imported by the Kotlin/Wasm binding (src/wasmJsMain/.../WasmSes.kt).
//
// - Compiled once per URL, instantiated once per process (initialize); loadRuntime makes an
//   independent instance (tests).
// - Imports: a minimal WASI shim (exactly what wasi-libc links in: see WASI below) and two host
//   callbacks, env.ses_host_read / env.ses_host_match, which call the functions Kotlin registers
//   ONCE with setHost(). Callbacks never enter the wasm function table: the module's own C
//   trampolines (native/src/syntax_wasm.c) call these imports with a host context id.
// - Every value crossing to Kotlin is a number or a JS string. Byte buffers cross as "latin1"
//   strings (one char per byte) and int arrays as strings of two UTF-16 units per int (low half
//   first), because Kotlin/Wasm converts a whole string in one bulk copy, while reading a typed
//   array element by element costs one JS call per element.
// - Views of `memory.buffer` are re-acquired after every call that can allocate: a grown memory
//   detaches the old ArrayBuffer.

export const ABI_VERSION = 3;

export const Reason = Object.freeze({
  MISSING_BINARY: 'MISSING_BINARY',
  CORRUPT_BINARY: 'CORRUPT_BINARY',
  ABI_MISMATCH: 'ABI_MISMATCH',
  INITIALIZATION_FAILED: 'INITIALIZATION_FAILED',
});

export class SyntaxLoadError extends Error {
  constructor(reason, message, cause) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'SyntaxLoadError';
    this.reason = reason;
  }
}

/** The package default: the wasm binary shipped next to this loader (bundlers rewrite this URL). */
export function defaultWasmUrl() {
  return new URL('./supermux-syntax.wasm', import.meta.url).href;
}

function baseUrl() {
  return typeof document !== 'undefined' && document.baseURI ? document.baseURI : import.meta.url;
}

function resolveUrl(url, what) {
  let resolved;
  try {
    resolved = new URL(String(url), baseUrl());
  } catch (e) {
    throw new SyntaxLoadError(Reason.INITIALIZATION_FAILED, `invalid ${what} URL: ${url}`, e);
  }
  if (!['https:', 'http:', 'file:'].includes(resolved.protocol)) {
    throw new SyntaxLoadError(Reason.INITIALIZATION_FAILED, `${what} URL must be http(s): ${resolved.protocol}`);
  }
  return resolved.href;
}

async function fetchBytes(url) {
  if (url.startsWith('file:')) { // Node (the build's tests): no fetch() for file URLs
    const { readFile } = await import(/* webpackIgnore: true */ 'node:fs/promises');
    return new Uint8Array(await readFile(new URL(url)));
  }
  const response = await fetch(url, { credentials: 'same-origin' });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return new Uint8Array(await response.arrayBuffer());
}

// ------------------------------------------------------------------ strings ----

const CHUNK = 8192;

function unitsToString(units) { // Uint16Array | Uint8Array -> string, one char per element
  let s = '';
  for (let i = 0; i < units.length; i += CHUNK) s += String.fromCharCode.apply(null, units.subarray(i, i + CHUNK));
  return s;
}

const utf8 = new TextDecoder('utf-8');
const utf8enc = new TextEncoder();

// ------------------------------------------------------------------ host callbacks ----

// Kotlin registers these once (WasmSes.kt): read(ctx, index) -> string | null (the text from
// index to the end of some chunk; null or '' = end), match(ctx, regexId, text) -> 1 | 0 | -1.
const host = { read: null, match: null };

export function setHost(read, match) {
  host.read = read;
  host.match = match;
}

// ------------------------------------------------------------------ WASI ----
// Exactly the wasi_snapshot_preview1 functions the module imports (native/wasm/expected-imports.json;
// an unknown import fails instantiation with ABI_MISMATCH). wasi-libc links them in for stdio
// (fd_write / fd_seek / fd_close: tree-sitter's debug printing, never enabled here) and for
// clock_gettime (clock_time_get: the parse timeout).

function wasi(mem) {
  const ERRNO_SPIPE = 70;
  const lines = { 1: '', 2: '' };
  const view = () => new DataView(mem().buffer);
  return {
    fd_write(fd, iovs, iovsLen, nwritten) {
      const dv = view();
      let n = 0, text = '';
      for (let i = 0; i < iovsLen; i++) {
        const p = dv.getUint32(iovs + 8 * i, true), l = dv.getUint32(iovs + 8 * i + 4, true);
        text += utf8.decode(new Uint8Array(mem().buffer, p, l));
        n += l;
      }
      if (fd === 1 || fd === 2) {
        const parts = (lines[fd] + text).split('\n');
        lines[fd] = parts.pop();
        for (const line of parts) (fd === 1 ? console.log : console.error)(`supermux-syntax: ${line}`);
      }
      view().setUint32(nwritten, n, true);
      return 0;
    },
    fd_close() { return 0; },
    fd_seek() { return ERRNO_SPIPE; },
    clock_time_get(id, _precision, out) {
      // 0 = realtime; the monotonic (and cputime) clocks from performance.now()
      const ns = id === 0 ? BigInt(Date.now()) * 1000000n : BigInt(Math.round(performance.now() * 1e6));
      view().setBigUint64(out, ns, true);
      return 0;
    },
  };
}

// ------------------------------------------------------------------ compile ----

const compiled = new Map(); // resolved URL -> Promise<WebAssembly.Module>

async function fetchAndCompile(url) {
  if (typeof WebAssembly !== 'object') {
    throw new SyntaxLoadError(Reason.INITIALIZATION_FAILED, 'WebAssembly is not available in this runtime');
  }
  let bytes;
  try {
    bytes = await fetchBytes(url);
  } catch (e) {
    throw new SyntaxLoadError(Reason.MISSING_BINARY, `fetching ${url} failed: ${e && e.message}`, e);
  }
  try {
    return await WebAssembly.compile(bytes);
  } catch (e) {
    throw new SyntaxLoadError(Reason.CORRUPT_BINARY, `${url} is not a valid wasm module: ${e && e.message}`, e);
  }
}

export function compileModule(url) {
  const key = resolveUrl(url == null ? defaultWasmUrl() : url, 'wasm');
  let p = compiled.get(key);
  if (!p) {
    p = fetchAndCompile(key);
    compiled.set(key, p);
    p.catch(() => { if (compiled.get(key) === p) compiled.delete(key); });
  }
  return p;
}

// ------------------------------------------------------------------ runtime ----

const REQUIRED_EXPORTS = [
  'memory', '_initialize', 'ses_abi_version', 'ses_wasm_malloc', 'ses_wasm_free', 'ses_wasm_scratch',
  'ses_wasm_parser_parse', 'ses_wasm_query_captures', 'ses_wasm_query_matches', 'ses_free',
  'ses_language_count', 'ses_language_name', 'ses_language_has_tables', 'ses_language_provide_tables',
  'ses_language_load', 'ses_parser_new', 'ses_parser_free', 'ses_parser_set_language',
  'ses_parser_set_timeout_micros', 'ses_parser_set_included_ranges', 'ses_parser_reset', 'ses_parser_parse_utf16',
  'ses_tree_copy', 'ses_tree_free', 'ses_tree_edit', 'ses_tree_root_sexp', 'ses_tree_has_error',
  'ses_tree_changed_ranges', 'ses_query_new', 'ses_query_free', 'ses_query_capture_count', 'ses_query_capture_name',
  'ses_query_flags', 'ses_query_pattern_count', 'ses_query_regex_count', 'ses_query_regex',
  'ses_query_pattern_settings', 'ses_debug_live_trees',
];

/**
 * One instance of the module. Pointers are wasm32 addresses (i32 numbers, 0 = NULL). Out-values
 * of the last call (status, error offset/type, match-limit flag) are read with the getters below.
 */
export class SyntaxRuntime {
  #x;
  #out;      // 32 bytes: [0] status, [4] out ptr, [8] out count, [12] exceeded, [16] err offset, [20] err type
  #status = 0;
  #exceeded = 0;
  #errOffset = 0;
  #errType = 0;

  constructor(instance) {
    const x = instance.exports;
    const missing = REQUIRED_EXPORTS.filter((n) => !(n in x));
    if (missing.length) throw new SyntaxLoadError(Reason.ABI_MISMATCH, `wasm module lacks exports: ${missing.join(', ')}`);
    x._initialize();
    const abi = x.ses_abi_version() >>> 0;
    if (abi !== ABI_VERSION) {
      throw new SyntaxLoadError(Reason.ABI_MISMATCH, `wasm module implements ses_* ABI ${abi}, this loader needs ${ABI_VERSION}`);
    }
    this.#x = x;
    this.#out = x.ses_wasm_malloc(32);
    if (!this.#out) throw new SyntaxLoadError(Reason.INITIALIZATION_FAILED, 'wasm allocation failed');
  }

  get exports() { return this.#x; }
  #dv() { return new DataView(this.#x.memory.buffer); }

  /** Run call(ptr, len) with [bytes] (Uint8Array) copied into wasm memory for the call only. */
  #withBytes(bytes, call) {
    const x = this.#x;
    const p = x.ses_wasm_malloc(bytes.length);
    if (!p) return -4; // SES_ERR_OUT_OF_MEMORY
    try {
      new Uint8Array(x.memory.buffer, p, bytes.length).set(bytes);
      return call(p, bytes.length);
    } finally {
      x.ses_wasm_free(p);
    }
  }

  #withCString(s, call) {
    const b = utf8enc.encode(s);
    const z = new Uint8Array(b.length + 1);
    z.set(b);
    return this.#withBytes(z, (p) => call(p));
  }

  #cstring(p) {
    if (!p) return null;
    const m = new Uint8Array(this.#x.memory.buffer);
    let e = p;
    while (m[e] !== 0) e++;
    return utf8.decode(m.subarray(p, e));
  }

  /** The int32 array a ses_* call stored at out[4] / out[8], as a packed string; freed. */
  #takeInts() {
    const dv = this.#dv();
    const p = dv.getUint32(this.#out + 4, true), n = dv.getUint32(this.#out + 8, true);
    try {
      return unitsToString(new Uint16Array(this.#x.memory.buffer, p, 2 * n));
    } finally {
      this.#x.ses_free(p);
    }
  }

  #bytesAt(p, n) { return p ? unitsToString(new Uint8Array(this.#x.memory.buffer, p, n)) : ''; }

  status() { return this.#status; }
  exceeded() { return this.#exceeded; }
  errOffset() { return this.#errOffset; }
  errType() { return this.#errType; }

  abiVersion() { return this.#x.ses_abi_version() >>> 0; }
  languageCount() { return this.#x.ses_language_count() >>> 0; }
  languageName(i) { return this.#cstring(this.#x.ses_language_name(i)); }
  languageHasTables(name) { return this.#withCString(name, (p) => this.#x.ses_language_has_tables(p)); }
  languageLoad(name) { return this.#withCString(name, (p) => this.#x.ses_language_load(p)); }
  /** [bytes]: a Uint8Array, or a latin1 string (one char per byte). */
  provideTables(name, bytes) {
    if (typeof bytes === 'string') bytes = latin1ToBytes(bytes);
    return this.#withCString(name, (np) => this.#withBytes(bytes, (p, n) => this.#x.ses_language_provide_tables(np, p, n)));
  }

  parserNew() { return this.#x.ses_parser_new(); }
  parserFree(p) { this.#x.ses_parser_free(p); }
  parserSetLanguage(p, name) { return this.#withCString(name, (np) => this.#x.ses_parser_set_language(p, np)); }
  /** micros: a BigInt (Kotlin Long). */
  parserSetTimeoutMicros(p, micros) { this.#x.ses_parser_set_timeout_micros(p, BigInt.asUintN(64, BigInt(micros))); }
  /** ranges: packed ints (see #takeInts), 6 per range. */
  parserSetIncludedRanges(p, packed) {
    if (!packed) return this.#x.ses_parser_set_included_ranges(p, 0, 0);
    return this.#withBytes(unitsBytes(packed), (b, n) => this.#x.ses_parser_set_included_ranges(p, b, n >>> 2));
  }
  parserReset(p) { this.#x.ses_parser_reset(p); }

  /** Parse through the host reader [ctx]; the tree (0 on failure, see status()). */
  parse(p, old, ctx) {
    const t = this.#x.ses_wasm_parser_parse(p, old, ctx, this.#out);
    this.#status = this.#dv().getInt32(this.#out, true);
    return t;
  }

  parseString(p, old, text) {
    const x = this.#x, n = text.length;
    const b = x.ses_wasm_malloc(2 * n);
    if (!b) { this.#status = -4; return 0; }
    try {
      const u = new Uint16Array(x.memory.buffer, b, n);
      for (let i = 0; i < n; i++) u[i] = text.charCodeAt(i);
      const t = x.ses_parser_parse_utf16(p, old, b, n, this.#out);
      this.#status = this.#dv().getInt32(this.#out, true);
      return t;
    } finally {
      x.ses_wasm_free(b);
    }
  }

  treeCopy(t) { return this.#x.ses_tree_copy(t); }
  treeFree(t) { this.#x.ses_tree_free(t); }
  treeEdit(t, a, b, c, d, e, f, g, h, i) { this.#x.ses_tree_edit(t, a, b, c, d, e, f, g, h, i); }
  treeSexp(t) {
    const p = this.#x.ses_tree_root_sexp(t);
    try { return this.#cstring(p) ?? ''; } finally { this.#x.ses_free(p); }
  }
  treeHasError(t) { return this.#x.ses_tree_has_error(t) !== 0; }
  /** Packed ints, or null with status() set. */
  treeChangedRanges(a, b) {
    const o = this.#out;
    this.#status = this.#x.ses_tree_changed_ranges(a, b, o + 4, o + 8);
    return this.#status === 0 ? this.#takeInts() : null;
  }

  queryNew(language, source) {
    const o = this.#out;
    let q = 0;
    this.#withCString(language, (lp) => {
      const src = utf8enc.encode(source);
      this.#withBytes(src.length ? src : new Uint8Array(1), (sp) => {
        q = this.#x.ses_query_new(lp, sp, src.length, o + 16, o + 20, o);
      });
    });
    const dv = this.#dv();
    this.#status = q ? 0 : dv.getInt32(o, true);
    this.#errOffset = dv.getUint32(o + 16, true);
    this.#errType = dv.getInt32(o + 20, true);
    return q;
  }
  queryFree(q) { this.#x.ses_query_free(q); }
  queryCaptureCount(q) { return this.#x.ses_query_capture_count(q) >>> 0; }
  queryFlags(q) { return this.#x.ses_query_flags(q) >>> 0; }
  queryPatternCount(q) { return this.#x.ses_query_pattern_count(q) >>> 0; }
  queryRegexCount(q) { return this.#x.ses_query_regex_count(q) >>> 0; }
  /** The capture's name / the regex / the settings records, as latin1 strings of their bytes. */
  queryCaptureName(q, i) { const p = this.#x.ses_query_capture_name(q, i, this.#out + 8); return this.#bytesAt(p, this.#dv().getUint32(this.#out + 8, true)); }
  queryRegex(q, i) { const p = this.#x.ses_query_regex(q, i, this.#out + 8); return this.#bytesAt(p, this.#dv().getUint32(this.#out + 8, true)); }
  queryPatternSettings(q, i) { const p = this.#x.ses_query_pattern_settings(q, i, this.#out + 8); return this.#bytesAt(p, this.#dv().getUint32(this.#out + 8, true)); }

  /** Packed [start, end, capture, pattern]* ints, or null with status() set; exceeded() the match-limit flag. */
  queryCaptures(q, t, start, end, readCtx, matchCtx) {
    const o = this.#out;
    this.#status = this.#x.ses_wasm_query_captures(q, t, start, end, readCtx, matchCtx, o + 4, o + 8, o + 12);
    this.#exceeded = this.#dv().getInt32(o + 12, true);
    return this.#status === 0 ? this.#takeInts() : null;
  }

  queryMatches(q, t, start, end, readCtx, matchCtx, childrenOf) {
    const o = this.#out;
    this.#status = this.#x.ses_wasm_query_matches(q, t, start, end, readCtx, matchCtx, childrenOf, o + 4, o + 8, o + 12);
    this.#exceeded = this.#dv().getInt32(o + 12, true);
    return this.#status === 0 ? this.#takeInts() : null;
  }

  /** A BigInt (Kotlin Long). */
  debugLiveTrees() { return this.#x.ses_debug_live_trees(); }
  memoryBytes() { return this.#x.memory.buffer.byteLength; }
}

function latin1ToBytes(s) {
  const b = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i);
  return b;
}

/** A packed-ints string's bytes (little-endian int32s). */
function unitsBytes(s) {
  const u = new Uint16Array(s.length);
  for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i);
  return new Uint8Array(u.buffer);
}

function imports(module, holder) {
  const mem = () => holder.x.memory;
  const shim = wasi(mem);
  const needed = WebAssembly.Module.imports(module);
  const env = {
    ses_host_read(ctx, index) {
      const s = host.read ? host.read(ctx, index) : null;
      const n = s == null ? 0 : s.length;
      if (n === 0) return 0;
      const p = holder.x.ses_wasm_scratch(n); // may grow memory: views after it
      if (!p) return 0;
      const u = new Uint16Array(holder.x.memory.buffer, p, n);
      for (let i = 0; i < n; i++) u[i] = s.charCodeAt(i);
      return n;
    },
    ses_host_match(ctx, id, p, n) {
      if (!host.match) return -1;
      const text = n ? unitsToString(new Uint16Array(holder.x.memory.buffer, p, n)) : '';
      return host.match(ctx, id, text);
    },
  };
  const out = { env: {}, wasi_snapshot_preview1: {} };
  const missing = [];
  for (const imp of needed) {
    const table = imp.module === 'env' ? env : imp.module === 'wasi_snapshot_preview1' ? shim : null;
    if (imp.kind !== 'function' || !table || !(imp.name in table)) { missing.push(`${imp.module}.${imp.name}`); continue; }
    out[imp.module][imp.name] = table[imp.name];
  }
  if (missing.length) throw new SyntaxLoadError(Reason.ABI_MISMATCH, `wasm module needs unknown imports: ${missing.join(', ')}`);
  return out;
}

/** Compile (cached) + instantiate a NEW runtime for [url] (null: the package default). */
export async function loadRuntime(url) {
  const module = await compileModule(url);
  const holder = { x: null };
  let instance;
  try {
    instance = await WebAssembly.instantiate(module, imports(module, holder));
  } catch (e) {
    if (e instanceof SyntaxLoadError) throw e;
    const reason = e instanceof WebAssembly.LinkError ? Reason.ABI_MISMATCH : Reason.INITIALIZATION_FAILED;
    throw new SyntaxLoadError(reason, `instantiating the syntax wasm module failed: ${e && e.message}`, e);
  }
  holder.x = instance.exports;
  return new SyntaxRuntime(instance);
}

// ------------------------------------------------------------------ singleton ----

let current = null;
let pending = null;
let configuredUrl = null;
let tablesBase = null;

/**
 * Load the process-wide runtime once. [url]: the wasm module (null: next to this loader);
 * [tablesUrl]: the directory holding the code-only grammars' <lang>.sesz (null: editor-syntax/tables/
 * next to the wasm module). A second call with a different module URL is rejected.
 */
export function initialize(url, tablesUrl) {
  let resolved;
  try {
    resolved = resolveUrl(url == null ? defaultWasmUrl() : url, 'wasm');
    if (tablesUrl != null) tablesBase = resolveUrl(tablesUrl, 'tables');
  } catch (e) {
    return Promise.reject(e);
  }
  if (configuredUrl !== null && resolved !== configuredUrl) {
    return Promise.reject(new SyntaxLoadError(Reason.INITIALIZATION_FAILED,
      `the syntax runtime is already loaded from ${configuredUrl}; refusing ${resolved}`));
  }
  if (current) return Promise.resolve(current);
  if (pending) return pending;
  configuredUrl = resolved;
  pending = loadRuntime(resolved).then(
    (rt) => { current = rt; pending = null; return rt; },
    (e) => { pending = null; configuredUrl = null; throw e; },
  );
  return pending;
}

export function currentRuntime() { return current; }

/** Where <lang>.sesz is fetched from. */
export function tablesUrl(lang) {
  const base = tablesBase ?? new URL('editor-syntax/tables/', configuredUrl ?? defaultWasmUrl()).href;
  return new URL(`${lang}.sesz`, base.endsWith('/') ? base : base + '/').href;
}

// ------------------------------------------------------------------ resources ----
// Tables blobs (and, in tests, test resources) by resource path, fetched once.

const resources = new Map(); // path -> Uint8Array

export function hasResource(path) { return resources.has(path); }
export function dropResource(path) { resources.delete(path); }
export function putResource(path, bytes) { resources.set(path, bytes); }
/** The resource's bytes as a latin1 string, or null. */
export function resourceLatin1(path) {
  const b = resources.get(path);
  return b === undefined ? null : unitsToString(b);
}
/** ses_language_provide_tables straight from a fetched resource (no copy through Kotlin). */
export function provideTablesFromResource(name, path) {
  const b = resources.get(path);
  if (b === undefined) return -9; // SES_ERR_NO_TABLES
  return current.provideTables(name, b);
}

const inflight = new Map(); // path -> Promise<null | message>

/** Fetch [url] into resource [path] (once at a time per path): null, or an error message. */
export function fetchResource(path, url) {
  let p = inflight.get(path);
  if (!p) {
    p = (async () => {
      try {
        resources.set(path, await fetchBytes(resolveUrl(url, 'resource')));
        return null;
      } catch (e) {
        return `${url}: ${(e && e.message) || e}`;
      } finally {
        inflight.delete(path);
      }
    })();
    inflight.set(path, p);
  }
  return p;
}

// ------------------------------------------------------------------ scheduling ----

// One MessageChannel for every yield: a posted message is a new task, so whatever is already
// queued (input, a frame, timers, other tasks) runs before the syntax worker's next slice.
// (Not scheduler.yield(): its continuation is prioritised ahead of other queued tasks.)
const waiting = [];
let channel = null;

/** A promise resolved in a new task of the event loop. */
export function nextTask() {
  return new Promise((resolve) => {
    if (typeof MessageChannel === 'undefined') { setTimeout(resolve, 0); return; }
    if (!channel) {
      channel = new MessageChannel();
      channel.port1.onmessage = () => { const r = waiting.shift(); if (r) r(); };
    }
    waiting.push(resolve);
    channel.port2.postMessage(0);
  });
}
