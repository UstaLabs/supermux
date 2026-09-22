// supermux terminal-core: browser loader for supermux-terminal.wasm (libghostty-vt + the st_* ABI v1,
// see native/README.md). Plain ES module, no dependencies, no Node APIs; shipped as a resource of the
// wasmJs artifact next to the wasm binary and imported by the Kotlin/Wasm binding
// (src/wasmJsMain/.../WasmTerminalLoader.kt).
//
// - The module is compiled ONCE per URL (the compilation promise is cached) and instantiated ONCE per
//   runtime; every terminal handle lives in that instance's handle table (st_create/st_destroy).
// - The module has no imports: effects are queued natively and drained with st_drain_effects, so
//   there are no callbacks and no function-table patching here.
// - Every output is an OWNED copy (Uint8Array.slice) taken before the next call into the module;
//   no view of `memory.buffer` survives a call that can grow memory (a grown memory detaches the old
//   ArrayBuffer). Views are re-acquired right before each read/write.
// - The wasm URL is either the package default — `supermux-terminal.wasm` resolved relative to THIS
//   file (bundlers rewrite `new URL(..., import.meta.url)` to the emitted, content-hashed asset) —
//   or a URL the host app configures once in initialize(). It is never derived from user input.
//   Only http(s) URLs (and same-document relative ones) are accepted.

export const ABI_VERSION = 1;

/** Startup failure reasons; names match TerminalEngineUnavailableException.Reason. */
export const Reason = Object.freeze({
  MISSING_BINARY: 'MISSING_BINARY',
  CORRUPT_BINARY: 'CORRUPT_BINARY',
  ABI_MISMATCH: 'ABI_MISMATCH',
  INITIALIZATION_FAILED: 'INITIALIZATION_FAILED',
});

export class TerminalLoadError extends Error {
  constructor(reason, message, cause) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'TerminalLoadError';
    this.reason = reason;
  }
}

// The st_* functions this binding calls; a module missing any of them is an ABI mismatch.
const ST_EXPORTS = [
  'st_abi_version', 'st_create', 'st_destroy', 'st_feed', 'st_reset', 'st_resize', 'st_colors',
  'st_read_viewport', 'st_acknowledge', 'st_scroll_to', 'st_key', 'st_mouse', 'st_paste', 'st_focus',
  'st_select', 'st_selected_text', 'st_drain_effects', 'st_free_buffer',
];
const HOST_EXPORTS = ['memory', 'ghostty_wasm_alloc', 'ghostty_wasm_free'];

const ST_OK = 0;
const ST_ERR_OUT_OF_MEMORY = -4;

/** The package default: the wasm binary shipped next to this loader. */
export function defaultWasmUrl() {
  return new URL('./supermux-terminal.wasm', import.meta.url).href;
}

function resolveWasmUrl(url) {
  if (url == null) return defaultWasmUrl();
  if (typeof url !== 'string' && !(url instanceof URL)) {
    throw new TerminalLoadError(Reason.INITIALIZATION_FAILED, 'wasm URL must be a string or URL');
  }
  const base = typeof document !== 'undefined' && document.baseURI ? document.baseURI : import.meta.url;
  let resolved;
  try {
    resolved = new URL(String(url), base);
  } catch (e) {
    throw new TerminalLoadError(Reason.INITIALIZATION_FAILED, `invalid wasm URL: ${url}`, e);
  }
  if (resolved.protocol !== 'https:' && resolved.protocol !== 'http:') {
    throw new TerminalLoadError(Reason.INITIALIZATION_FAILED, `wasm URL must be http(s): ${resolved.protocol}`);
  }
  return resolved.href;
}

// ------------------------------------------------------------------ compile ----

const compiled = new Map(); // resolved URL -> Promise<WebAssembly.Module>

async function fetchAndCompile(url) {
  if (typeof WebAssembly !== 'object' || typeof WebAssembly.compile !== 'function') {
    throw new TerminalLoadError(Reason.INITIALIZATION_FAILED, 'WebAssembly is not available in this runtime');
  }
  let response;
  try {
    response = await fetch(url, { credentials: 'same-origin' });
  } catch (e) {
    throw new TerminalLoadError(Reason.MISSING_BINARY, `fetching ${url} failed: ${e && e.message}`, e);
  }
  if (!response.ok) {
    throw new TerminalLoadError(Reason.MISSING_BINARY, `fetching ${url} failed: HTTP ${response.status}`);
  }
  const type = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
  try {
    // compileStreaming requires `application/wasm`; any other MIME falls back to a buffered compile.
    if (type === 'application/wasm' && typeof WebAssembly.compileStreaming === 'function') {
      return await WebAssembly.compileStreaming(response);
    }
    return await WebAssembly.compile(await response.arrayBuffer());
  } catch (e) {
    if (e instanceof WebAssembly.CompileError) {
      throw new TerminalLoadError(Reason.CORRUPT_BINARY, `${url} is not a valid wasm module: ${e.message}`, e);
    }
    throw new TerminalLoadError(Reason.MISSING_BINARY, `reading ${url} failed: ${e && e.message}`, e);
  }
}

/** Compile the module at `url` once; a failed compilation is not cached (the next call retries). */
export function compileModule(url) {
  const key = resolveWasmUrl(url);
  let p = compiled.get(key);
  if (!p) {
    p = fetchAndCompile(key);
    compiled.set(key, p);
    p.catch(() => { if (compiled.get(key) === p) compiled.delete(key); });
  }
  return p;
}

// ------------------------------------------------------------------ runtime ----

/**
 * One instance of the module: its own linear memory and st_* handle table. All methods are
 * synchronous and return plain numbers / { status, handle } / { status, bytes } objects; `bytes`
 * is an owned Uint8Array (never a view of wasm memory).
 */
export class TerminalRuntime {
  #x;
  #scratch; // 16 bytes: [0] out_buf / out_handle, [4] out_len, [8] out_frame_flags
  #liveBuffers = 0;

  constructor(instance) {
    const x = instance.exports;
    const missing = [...HOST_EXPORTS, ...ST_EXPORTS].filter((n) => !(n in x));
    if (missing.length) {
      throw new TerminalLoadError(Reason.ABI_MISMATCH, `wasm module lacks exports: ${missing.join(', ')}`);
    }
    const abi = x.st_abi_version() >>> 0;
    if (abi !== ABI_VERSION) {
      throw new TerminalLoadError(
        Reason.ABI_MISMATCH, `wasm module implements st_* ABI ${abi}, this loader needs ${ABI_VERSION}`);
    }
    this.#x = x;
    this.#scratch = x.ghostty_wasm_alloc(16);
    if (!this.#scratch) throw new TerminalLoadError(Reason.INITIALIZATION_FAILED, 'wasm scratch allocation failed');
  }

  // Fresh views on every access: memory.grow detaches the previous buffer.
  #u8() { return new Uint8Array(this.#x.memory.buffer); }
  #dv() { return new DataView(this.#x.memory.buffer); }

  /** Run `call(ptr, len)` with `bytes` copied into wasm memory for that call only. */
  #withBytes(bytes, call) {
    const n = bytes ? bytes.length : 0;
    if (n === 0) return call(0, 0);
    const x = this.#x;
    const p = x.ghostty_wasm_alloc(n);
    if (!p) return ST_ERR_OUT_OF_MEMORY;
    try {
      this.#u8().set(bytes, p); // view acquired after the allocation (which may have grown memory)
      return call(p, n);
    } finally {
      x.ghostty_wasm_free(p, n);
    }
  }

  /** Call an st_* buffer reader; copy the envelope out and free the native buffer on every path. */
  #readBuffer(call) {
    const s = this.#scratch;
    const status = call(s, s + 4);
    if (status !== ST_OK) return { status, bytes: null };
    const dv = this.#dv(); // acquired AFTER the call: it may have grown memory
    const p = dv.getUint32(s, true);
    const n = dv.getUint32(s + 4, true);
    this.#liveBuffers++;
    try {
      return { status, bytes: this.#u8().slice(p, p + n) };
    } finally {
      this.#x.st_free_buffer(p);
      this.#liveBuffers--;
    }
  }

  abiVersion() { return this.#x.st_abi_version() >>> 0; }

  /** historyBytes is a BigInt (u64). The handle is the u32 bit pattern as a signed i32. */
  create(columns, rows, cellWidthPx, cellHeightPx, historyLines, historyBytes) {
    const s = this.#scratch;
    const status = this.#x.st_create(
      ABI_VERSION, columns, rows, cellWidthPx, cellHeightPx, historyLines, BigInt.asUintN(64, BigInt(historyBytes)), 0, s);
    return { status, handle: status === ST_OK ? this.#dv().getInt32(s, true) : 0 };
  }

  destroy(h) { return this.#x.st_destroy(h); }
  feed(h, bytes, origin) { return this.#withBytes(bytes, (p, n) => this.#x.st_feed(h, p, n, origin)); }
  reset(h) { return this.#x.st_reset(h); }
  resize(h, columns, rows, cw, ch) { return this.#x.st_resize(h, columns, rows, cw, ch); }
  /** `bytes`: count * 8 little-endian u64 colour values. */
  colors(h, bytes) { return this.#withBytes(bytes, (p, n) => this.#x.st_colors(h, p, n >>> 3)); }
  scrollTo(h, row) { return this.#x.st_scroll_to(h, BigInt(row)); }
  select(h, has, startRow, startColumn, endRow, endColumn) {
    return this.#x.st_select(h, has, BigInt(startRow), startColumn, BigInt(endRow), endColumn);
  }
  acknowledge(h, generation) { return this.#x.st_acknowledge(h, BigInt(generation)); }
  key(h, physicalCode, text, modifiers, action) {
    return this.#withBytes(text, (p, n) => this.#x.st_key(h, physicalCode, p, n, modifiers, action));
  }
  mouse(h, column, row, button, modifiers, action) { return this.#x.st_mouse(h, column, row, button, modifiers, action); }
  paste(h, text, flags) { return this.#withBytes(text, (p, n) => this.#x.st_paste(h, p, n, flags)); }
  focus(h, focused) { return this.#x.st_focus(h, focused ? 1 : 0); }
  readViewport(h, flags) { return this.#readBuffer((b, l) => this.#x.st_read_viewport(h, flags, b, l, 0)); }
  selectedText(h) { return this.#readBuffer((b, l) => this.#x.st_selected_text(h, b, l)); }
  drainEffects(h) { return this.#readBuffer((b, l) => this.#x.st_drain_effects(h, b, l)); }

  /** Test/diagnostics: current linear memory size in bytes, and envelopes not yet freed (always 0). */
  memoryBytes() { return this.#x.memory.buffer.byteLength; }
  liveBuffers() { return this.#liveBuffers; }
  /** Test hook: grow linear memory by allocating (and freeing) `bytes` inside the module. */
  growMemory(bytes) {
    const p = this.#x.ghostty_wasm_alloc(bytes);
    if (!p) return false;
    this.#u8()[p + bytes - 1] = 0x5a;
    this.#x.ghostty_wasm_free(p, bytes);
    return true;
  }
}

/** Compile (cached) + instantiate a NEW runtime for `url`. Independent of initialize()'s singleton. */
export async function loadRuntime(url) {
  const module = await compileModule(url);
  let instance;
  try {
    instance = await WebAssembly.instantiate(module, {});
  } catch (e) {
    const reason = e instanceof WebAssembly.LinkError ? Reason.ABI_MISMATCH : Reason.INITIALIZATION_FAILED;
    throw new TerminalLoadError(reason, `instantiating the terminal wasm module failed: ${e && e.message}`, e);
  }
  return new TerminalRuntime(instance);
}

// ------------------------------------------------------- process singleton ----

let current = null;     // TerminalRuntime once initialized
let pending = null;     // Promise<TerminalRuntime> while loading
let configuredUrl = null;
let lastError = null;   // the last failed initialize(), until one succeeds

/**
 * Load the process-wide runtime once. `url` (optional) is configured by the host app; a second
 * call with a DIFFERENT url is rejected, the same (or no) url returns the same runtime. A failed
 * load is not sticky: the next initialize() retries (the failure stays readable via lastFailure()).
 */
export function initialize(url) {
  let resolved;
  try {
    resolved = url == null ? null : resolveWasmUrl(url);
  } catch (e) {
    lastError = e;
    return Promise.reject(e);
  }
  if (current || pending) {
    if (resolved !== null && resolved !== configuredUrl) {
      return Promise.reject(new TerminalLoadError(Reason.INITIALIZATION_FAILED,
        `terminal runtime already initialized from ${configuredUrl}; cannot switch to ${resolved}`));
    }
    return current ? Promise.resolve(current) : pending;
  }
  configuredUrl = resolved ?? defaultWasmUrl();
  const p = loadRuntime(configuredUrl).then(
    (rt) => { current = rt; pending = null; lastError = null; return rt; },
    (e) => { pending = null; configuredUrl = null; lastError = e; throw e; },
  );
  pending = p;
  return p;
}

/** The initialized runtime, or null (not initialized yet, still loading, or the load failed). */
export function currentRuntime() { return current; }

/** The error of the last failed initialize() (cleared by a successful one), else null. */
export function lastFailure() { return lastError; }

/** True while initialize() is in flight. */
export function isLoading() { return pending !== null; }
