// Loader test fixture for terminal-loader.mjs. Runs unchanged in Node and in a browser page
// (loader-test.mjs serves this directory over HTTP and drives both). `base` is the HTTP origin
// the driver serves, with these paths:
//   /supermux-terminal.wasm          the real module (application/wasm)
//   /octet/supermux-terminal.wasm    the same bytes as application/octet-stream (buffered fallback)
//   /missing.wasm                    404
//   /garbage.wasm                    not a wasm module (application/wasm)
//   /empty.wasm                      a valid wasm module with no exports
import {
  ABI_VERSION, Reason, TerminalLoadError, compileModule, loadRuntime, initialize, currentRuntime,
  lastFailure, isLoading,
} from './terminal-loader.mjs';

const enc = new TextEncoder();
const dec = new TextDecoder();

/** Row texts of a kind-1 envelope (width-0 continuation cells skipped, trailing blanks trimmed). */
export function viewportRows(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (v.getUint32(0, true) !== 0x53545654 || v.getUint16(6, true) !== 1) throw new Error('not a viewport envelope');
  let o = 12 + 8 + 16;
  const rowCount = v.getUint32(o, true); o += 4;
  const rows = new Map();
  for (let r = 0; r < rowCount; r++) {
    const index = v.getInt32(o, true);
    const cells = v.getUint32(o + 4, true);
    o += 8;
    let text = '';
    for (let c = 0; c < cells; c++) {
      const tl = v.getUint32(o, true);
      const t = dec.decode(bytes.subarray(o + 4, o + 4 + tl));
      o += 4 + tl;
      const width = v.getInt32(o, true);
      o += 4 + 8 + 8 + 4 + 4;
      if (width !== 0) text += t === '' ? ' ' : t;
    }
    rows.set(index, text.trimEnd());
  }
  return rows;
}

/** Responses (tag 1) of a kind-2 envelope, as strings. */
export function responses(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (v.getUint16(6, true) !== 2) throw new Error('not an effects envelope');
  const count = v.getUint32(12, true);
  let o = 16;
  const out = [];
  for (let i = 0; i < count; i++) {
    const tag = v.getUint8(o); o += 1;
    if (tag === 1 || tag === 2 || tag === 3) {
      const n = v.getUint32(o, true);
      const s = dec.decode(bytes.subarray(o + 4, o + 4 + n));
      o += 4 + n;
      if (tag === 1) out.push(s);
    } else if (tag === 5) {
      o += 1; const has = v.getUint8(o); o += 1;
      if (has) { o += 4 + v.getUint32(o, true); }
    }
  }
  return out;
}

export async function runLoaderTests(base, log = console.log) {
  let checks = 0;
  let failures = 0;
  const check = (cond, msg) => {
    checks++;
    if (!cond) failures++;
    log(`${cond ? 'ok  ' : 'FAIL'} - ${msg}`);
    return cond;
  };
  const url = (p) => new URL(p, base).href;
  const rejectsWith = async (promise, reason, what) => {
    try {
      await promise;
      return check(false, `${what}: expected ${reason}, but it loaded`);
    } catch (e) {
      return check(e instanceof TerminalLoadError && e.reason === reason, `${what} -> ${e.reason ?? e}: ${e.message}`);
    }
  };

  // ---- loader failure -------------------------------------------------------------
  check(currentRuntime() === null && lastFailure() === null && !isLoading(), 'fresh loader: no runtime, no failure');
  await rejectsWith(loadRuntime(url('/missing.wasm')), Reason.MISSING_BINARY, 'HTTP 404');
  await rejectsWith(loadRuntime(url('/garbage.wasm')), Reason.CORRUPT_BINARY, 'non-wasm bytes');
  await rejectsWith(loadRuntime(url('/empty.wasm')), Reason.ABI_MISMATCH, 'module without st_* exports');
  await rejectsWith(loadRuntime('file:///etc/passwd'), Reason.INITIALIZATION_FAILED, 'file: URL rejected');
  await rejectsWith(loadRuntime('javascript:alert(1)'), Reason.INITIALIZATION_FAILED, 'javascript: URL rejected');
  await rejectsWith(initialize(url('/missing.wasm')), Reason.MISSING_BINARY, 'initialize() with a 404');
  check(currentRuntime() === null && lastFailure()?.reason === Reason.MISSING_BINARY && !isLoading(),
    'failed initialize(): no runtime, failure recorded, not loading');
  const f1 = compileModule(url('/missing.wasm'));
  await f1.catch(() => {});
  const f2 = compileModule(url('/missing.wasm'));
  await f2.catch(() => {});
  check(f1 !== f2, 'failed compilations are not cached (the next call retries)');

  // ---- compile cache + initialize singleton -----------------------------------------
  const wasm = url('/supermux-terminal.wasm');
  check(compileModule(wasm) === compileModule(wasm), 'compilation promise cached per URL');
  const p1 = initialize(wasm);
  const p2 = initialize();
  check(isLoading(), 'initialize() in flight');
  const rt = await p1;
  check(rt === await p2 && currentRuntime() === rt && lastFailure() === null, 'initialize(): one runtime, failure cleared');
  check(rt.abiVersion() === ABI_VERSION, `runtime ABI ${rt.abiVersion()}`);
  check(await initialize(wasm) === rt, 'initialize(same url) again -> same runtime');
  await rejectsWith(initialize(url('/octet/supermux-terminal.wasm')), Reason.INITIALIZATION_FAILED,
    'initialize(different url) after init');
  check(currentRuntime() === rt, 'rejected re-initialize keeps the runtime');

  // ---- buffered fallback (wrong MIME) ------------------------------------------------
  const rtOctet = await loadRuntime(url('/octet/supermux-terminal.wasm'));
  check(rtOctet.abiVersion() === ABI_VERSION, 'application/octet-stream served module loads (buffered compile)');

  // ---- two handles in one runtime, memory growth -------------------------------------
  const make = (r, cols = 80, rows = 24) => {
    const { status, handle } = r.create(cols, rows, 8, 16, 1000, 1n << 24n);
    if (status !== 0) throw new Error(`st_create -> ${status}`);
    return handle;
  };
  const feed = (r, h, s, origin = 0) => r.feed(h, typeof s === 'string' ? enc.encode(s) : s, origin);
  const read = (r, h) => {
    const { status, bytes } = r.readViewport(h, 1);
    if (status !== 0) throw new Error(`st_read_viewport -> ${status}`);
    return bytes;
  };
  const drain = (r, h) => {
    const { status, bytes } = r.drainEffects(h);
    if (status !== 0) throw new Error(`st_drain_effects -> ${status}`);
    return responses(bytes);
  };

  const a = make(rt);
  const b = make(rt, 40, 10);
  check(a !== b, `two handles in one runtime (${a >>> 0}, ${b >>> 0})`);
  check(feed(rt, a, 'alpha terminal') === 0 && feed(rt, b, 'bravo\r\nsecond line') === 0, 'fed different content');
  const aBefore = read(rt, a);
  const bBefore = read(rt, b);
  check(viewportRows(aBefore).get(0) === 'alpha terminal', 'handle A row 0');
  check(viewportRows(bBefore).get(0) === 'bravo' && viewportRows(bBefore).get(1) === 'second line', 'handle B rows 0-1');
  check(feed(rt, a, '\x1b[6n') === 0, 'CSI 6n queued on A only');

  // Large fixture: 6 MiB of output into A (allocated inside wasm memory for the call) plus a
  // 48 MiB allocation — linear memory must grow, detaching every earlier ArrayBuffer view.
  const memBefore = rt.memoryBytes();
  const line = 'x'.repeat(78) + '\r\n';
  const big = enc.encode(line.repeat(Math.ceil((6 << 20) / line.length)) + 'last line of A');
  check(feed(rt, a, big) === 0, `fed ${big.length} byte fixture to A`);
  check(rt.growMemory(48 << 20), 'grew memory by a 48 MiB allocation');
  const memAfter = rt.memoryBytes();
  check(memAfter > memBefore, `linear memory grew ${memBefore} -> ${memAfter} bytes`);
  check(viewportRows(aBefore).get(0) === 'alpha terminal' && aBefore.byteLength > 0 &&
    viewportRows(bBefore).get(1) === 'second line',
  'envelopes read before growth are owned copies (still intact, not detached views)');

  const aAfter = viewportRows(read(rt, a));
  const bAfter = viewportRows(read(rt, b));
  check(aAfter.get(23) === 'last line of A' && aAfter.get(22) === 'x'.repeat(78), 'A shows the fixture tail after growth');
  check(bAfter.get(0) === 'bravo' && bAfter.get(1) === 'second line' && bAfter.size === 10,
    'B unchanged after growth (no stale memory, no bleed from A)');
  check(feed(rt, b, '\x1b[2;4H\x1b[6n') === 0, 'CSI 6n queued on B');
  const aFx = drain(rt, a);
  const bFx = drain(rt, b);
  check(aFx.length === 1 && aFx[0] === '\x1b[1;15R', `A drains only its own response ${JSON.stringify(aFx)}`);
  check(bFx.length === 1 && bFx[0] === '\x1b[2;4R', `B drains only its own response ${JSON.stringify(bFx)}`);
  check(drain(rt, a).length === 0 && drain(rt, b).length === 0, 'queues empty after drain');
  check(rt.liveBuffers() === 0, 'every returned buffer freed');

  // ---- two runtimes (instances) from one compiled module ------------------------------
  const rt2 = await loadRuntime(wasm);
  check(rt2 !== rt, 'second runtime is a separate instance');
  const c = make(rt2);
  feed(rt2, c, 'charlie');
  check(viewportRows(read(rt2, c)).get(0) === 'charlie' && viewportRows(read(rt, b)).get(0) === 'bravo',
    'instances do not share screens');
  check(rt.destroy(a) === 0 && rt.destroy(a) === -1 && rt.feed(a, enc.encode('x'), 0) === -1,
    'destroy A; A then invalid');
  check(viewportRows(read(rt, b)).get(0) === 'bravo', 'B still valid after A was destroyed');
  check(rt.destroy(b) === 0 && rt2.destroy(c) === 0, 'destroyed B and C');

  log(`# ${checks} checks, ${failures} failures`);
  return { checks, failures, passed: failures === 0 };
}
