// WASM smoke fixture for pinned libghostty-vt (ghostty-vt.wasm).
//
// Runs unchanged in Node and in a browser (smoke.mjs inlines this file into a
// page for headless Chrome). It loads ONLY the VT engine: no ghostty-web
// renderer, no DOM terminal. All struct offsets and enum values come from the
// engine's own ABI manifest (ghostty_type_json), never hardcoded.
//
// Verifies: explicit export surface, host-owned allocation/free, memory growth
// with view re-acquisition, the ESC[31mredESC[0m three-red-cells fixture via
// the render-state API, and terminal response callback delivery (CSI 6n ->
// write_pty) through the exported, growable indirect function table. When the
// module carries the st_* wrapper (supermux-terminal.wasm) the same red-cell
// fixture, effects and replay suppression are also checked through st_* and
// the codec envelope.

export const REQUIRED_EXPORTS = [
  'memory', '__indirect_function_table',
  'ghostty_type_json',
  'ghostty_wasm_alloc', 'ghostty_wasm_free',
  'ghostty_wasm_alloc_opaque', 'ghostty_wasm_free_opaque', 'ghostty_wasm_take_opaque',
  'ghostty_free',
  'ghostty_terminal_new', 'ghostty_terminal_free', 'ghostty_terminal_set',
  'ghostty_terminal_get', 'ghostty_terminal_vt_write', 'ghostty_terminal_resize',
  'ghostty_terminal_reset',
  'ghostty_render_state_new', 'ghostty_render_state_free', 'ghostty_render_state_update',
  'ghostty_render_state_get',
  'ghostty_render_state_row_iterator_new', 'ghostty_render_state_row_iterator_free',
  'ghostty_render_state_row_iterator_next', 'ghostty_render_state_row_get',
  'ghostty_render_state_row_cells_new', 'ghostty_render_state_row_cells_free',
  'ghostty_render_state_row_cells_select', 'ghostty_render_state_row_cells_get',
  'ghostty_key_encoder_new', 'ghostty_key_encoder_encode',
  'ghostty_mouse_encoder_new', 'ghostty_mouse_encoder_encode',
  'ghostty_terminal_selection_format_alloc',
];

/** The st_* ABI (and codec envelope) version this fixture expects; see native/README.md. */
export const ST_CODEC_ABI = 2;

export const ST_EXPORTS = [
  'st_abi_version', 'st_create', 'st_destroy', 'st_feed', 'st_reset', 'st_resize', 'st_colors',
  'st_read_viewport', 'st_acknowledge', 'st_scroll_to', 'st_key', 'st_mouse', 'st_paste', 'st_focus',
  'st_select', 'st_selected_text', 'st_drain_effects', 'st_free_buffer',
];

// Minimal hand-assembled wasm module: imports env.f with the given i32-only
// signature and re-exports a wasm function that forwards to it. A wasm
// function (unlike a plain JS function) can be stored in another module's
// funcref table, which is how JS callbacks reach libghostty-vt's effects.
function trampolineBytes(paramCount) {
  const str = (s) => [s.length, ...new TextEncoder().encode(s)];
  const section = (id, body) => [id, body.length, ...body];
  const type = [0x01, 0x60, paramCount, ...Array(paramCount).fill(0x7f), 0x00];
  const imp = [0x01, ...str('env'), ...str('f'), 0x00, 0x00];
  const func = [0x01, 0x00];
  const exp = [0x01, ...str('f'), 0x00, 0x01];
  const body = [0x00];
  for (let i = 0; i < paramCount; i++) body.push(0x20, i);
  body.push(0x10, 0x00, 0x0b);
  const code = [0x01, body.length, ...body];
  return new Uint8Array([
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
    ...section(1, type), ...section(2, imp), ...section(3, func),
    ...section(7, exp), ...section(10, code),
  ]);
}

export async function runSmoke(wasmBytes, log = console.log) {
  let checks = 0;
  let failures = 0;
  const check = (cond, msg) => {
    checks++;
    if (!cond) failures++;
    log(`${cond ? 'ok  ' : 'FAIL'} - ${msg}`);
    return cond;
  };
  const require = (cond, msg) => {
    if (!check(cond, msg)) throw new Error(`required check failed: ${msg}`);
  };

  const { module, instance } = await WebAssembly.instantiate(wasmBytes, {});
  const imports = WebAssembly.Module.imports(module);
  check(imports.length === 0, `module has no imports (${imports.map((i) => i.module + '.' + i.name).join(',') || 'none'})`);
  const x = instance.exports;

  // ---- explicit export surface ------------------------------------------
  const missing = REQUIRED_EXPORTS.filter((n) => !(n in x));
  require(missing.length === 0, `required exports present (missing: ${missing.join(',') || 'none'})`);
  const fnExports = Object.keys(x).filter((k) => typeof x[k] === 'function');
  const nonGhostty = fnExports.filter((k) => !k.startsWith('ghostty_') && !k.startsWith('st_'));
  check(nonGhostty.length === 0, `every function export is ghostty_* or st_* (${fnExports.length} exports; others: ${nonGhostty.join(',') || 'none'})`);
  const hasSt = 'st_abi_version' in x;
  if (hasSt) {
    const stMissing = ST_EXPORTS.filter((n) => !(n in x));
    const stExtra = fnExports.filter((k) => k.startsWith('st_') && !ST_EXPORTS.includes(k));
    require(stMissing.length === 0 && stExtra.length === 0,
      `st_* ABI exports exactly the 18 functions (missing: ${stMissing.join(',') || 'none'}; extra: ${stExtra.join(',') || 'none'})`);
  }

  const memory = x.memory;
  const u8 = () => new Uint8Array(memory.buffer); // re-acquire on every access
  const dv = () => new DataView(memory.buffer);

  // ---- ABI manifest -----------------------------------------------------
  const jsonPtr = x.ghostty_type_json();
  const bytes = u8();
  let end = jsonPtr;
  while (bytes[end] !== 0) end++;
  const abi = JSON.parse(new TextDecoder().decode(bytes.subarray(jsonPtr, end)));
  require(abi.schema === 1 && abi.abi.pointer_size === 4, `ABI manifest schema ${abi.schema}, wasm32 pointers`);
  const T = abi.types;
  const E = (type, name) => {
    const v = T[type]?.values?.[name];
    if (v === undefined) throw new Error(`enum ${type}.${name} missing from manifest`);
    return v;
  };
  const off = (type, field) => {
    const f = T[type]?.fields?.[field];
    if (!f) throw new Error(`field ${type}.${field} missing from manifest`);
    return f.offset;
  };
  const SUCCESS = E('GhosttyResult', 'SUCCESS');

  // ---- allocation / free --------------------------------------------------
  const alloc = (n) => {
    const p = x.ghostty_wasm_alloc(n);
    if (!p) throw new Error('ghostty_wasm_alloc failed');
    return p;
  };
  const ptrs = [];
  for (let i = 0; i < 256; i++) ptrs.push([alloc(1024 + i), 1024 + i]);
  const distinct = new Set(ptrs.map(([p]) => p)).size === ptrs.length;
  const aligned = ptrs.every(([p]) => p % abi.abi.max_alignment === 0);
  for (const [p, n] of ptrs) x.ghostty_wasm_free(p, n);
  check(distinct && aligned, `256 host allocations distinct + ${abi.abi.max_alignment}-byte aligned, freed`);
  check(x.ghostty_wasm_alloc(0) === 0, 'zero-length alloc returns NULL');

  const slot = x.ghostty_wasm_alloc_opaque();
  const take = () => x.ghostty_wasm_take_opaque(slot);
  const write = (t, s) => {
    const data = typeof s === 'string' ? new TextEncoder().encode(s) : s;
    const p = alloc(data.length);
    u8().set(data, p);
    x.ghostty_terminal_vt_write(t, p, data.length);
    x.ghostty_wasm_free(p, data.length);
  };

  // ---- callbacks through the indirect function table ----------------------
  const table = x.__indirect_function_table;
  const addCallback = async (paramCount, fn) => {
    const { instance: tr } = await WebAssembly.instantiate(trampolineBytes(paramCount), { env: { f: fn } });
    const idx = table.grow(1);
    table.set(idx, tr.exports.f);
    return idx;
  };
  const responses = [];
  const writePtyIdx = await addCallback(4, (_term, _ud, data, len) => {
    responses.push(new TextDecoder().decode(u8().slice(data, data + len)));
  });
  let bells = 0;
  const bellIdx = await addCallback(2, () => { bells++; });
  check(writePtyIdx > 0 && table.length > writePtyIdx, `table grew for callbacks (write_pty idx ${writePtyIdx}, bell idx ${bellIdx})`);

  // ---- red-cell fixture ---------------------------------------------------
  require(x.ghostty_terminal_new(0, slot, 80, 24) === SUCCESS, 'ghostty_terminal_new(80x24)');
  const term = take();
  check(x.ghostty_terminal_set(term, E('GhosttyTerminalOption', 'WRITE_PTY'), writePtyIdx) === SUCCESS, 'set WRITE_PTY callback');
  check(x.ghostty_terminal_set(term, E('GhosttyTerminalOption', 'BELL'), bellIdx) === SUCCESS, 'set BELL callback');

  write(term, '\x1b[31mred\x1b[0m');

  require(x.ghostty_render_state_new(0, slot) === SUCCESS, 'render state new');
  const rs = take();
  require(x.ghostty_render_state_update(rs, term) === SUCCESS, 'render state update');
  require(x.ghostty_render_state_row_iterator_new(0, slot) === SUCCESS, 'row iterator new');
  const it = take();
  require(x.ghostty_render_state_row_cells_new(0, slot) === SUCCESS, 'row cells new');
  const cells = take();

  const handleSlot = x.ghostty_wasm_alloc_opaque();
  dv().setUint32(handleSlot, it, true);
  require(x.ghostty_render_state_get(rs, E('GhosttyRenderStateData', 'ROW_ITERATOR'), handleSlot) === SUCCESS, 'populate row iterator');
  require(x.ghostty_render_state_row_iterator_next(it) === 1, 'row 0 present');
  dv().setUint32(handleSlot, cells, true);
  require(x.ghostty_render_state_row_get(it, E('GhosttyRenderStateRowData', 'CELLS'), handleSlot) === SUCCESS, 'row 0 cells');

  const CD = (n) => E('GhosttyRenderStateRowCellsData', n);
  const bufSize = T.GhosttyBuffer.size;
  const styleSize = T.GhosttyStyle.size;
  const bufPtr = alloc(bufSize);
  const textPtr = alloc(16);
  const stylePtr = alloc(styleSize);
  const rgbPtr = alloc(4);
  const palettePtr = alloc(T.GhosttyRenderStateColors.size);
  u8().fill(0, palettePtr, palettePtr + T.GhosttyRenderStateColors.size);
  dv().setUint32(palettePtr + off('GhosttyRenderStateColors', 'size'), T.GhosttyRenderStateColors.size, true);
  x.ghostty_render_state_get(rs, E('GhosttyRenderStateData', 'COLORS'), palettePtr);
  const pal1 = palettePtr + off('GhosttyRenderStateColors', 'palette') + 3;
  const red = [u8()[pal1], u8()[pal1 + 1], u8()[pal1 + 2]];

  let redCells = 0;
  for (let col = 0; col < 3; col++) {
    x.ghostty_render_state_row_cells_select(cells, col);
    dv().setUint32(bufPtr + off('GhosttyBuffer', 'ptr'), textPtr, true);
    dv().setUint32(bufPtr + off('GhosttyBuffer', 'cap'), 16, true);
    dv().setUint32(bufPtr + off('GhosttyBuffer', 'len'), 0, true);
    const tr = x.ghostty_render_state_row_cells_get(cells, CD('GRAPHEMES_UTF8'), bufPtr);
    const len = dv().getUint32(bufPtr + off('GhosttyBuffer', 'len'), true);
    const text = new TextDecoder().decode(u8().slice(textPtr, textPtr + len));

    u8().fill(0, stylePtr, stylePtr + styleSize);
    dv().setUint32(stylePtr + off('GhosttyStyle', 'size'), styleSize, true);
    x.ghostty_render_state_row_cells_get(cells, CD('STYLE'), stylePtr);
    const fgBase = stylePtr + off('GhosttyStyle', 'fg_color');
    const tag = dv().getInt32(fgBase + off('GhosttyStyleColor', 'tag'), true);
    const palIdx = u8()[fgBase + off('GhosttyStyleColor', 'value')];

    const fr = x.ghostty_render_state_row_cells_get(cells, CD('FG_COLOR'), rgbPtr);
    const rgb = [u8()[rgbPtr], u8()[rgbPtr + 1], u8()[rgbPtr + 2]];
    const ok = tr === SUCCESS && text === 'red'[col] &&
      tag === E('GhosttyStyleColorTag', 'PALETTE') && palIdx === 1 &&
      fr === SUCCESS && rgb.join() === red.join();
    if (ok) redCells++;
    check(ok, `cell ${col} = '${text}' fg=palette[${palIdx}] rgb(${rgb.join(',')})`);
  }
  check(redCells === 3, `exactly three red cells (${redCells})`);

  // ---- the same fixture through the st_* ABI + codec -----------------------
  if (hasSt) {
    check(x.st_abi_version() === ST_CODEC_ABI, `st_abi_version() === ${ST_CODEC_ABI}`);
    const u32Slot = alloc(8);
    const lenSlot = alloc(4);
    require(x.st_create(ST_CODEC_ABI, 80, 24, 8, 16, 100, 1n << 20n, 0, u32Slot) === 0,
      `st_create(ABI ${ST_CODEC_ABI}, 80x24)`);
    const h = dv().getUint32(u32Slot, true);
    const stFeed = (str, origin = 0) => {
      const data = new TextEncoder().encode(str);
      const p = alloc(data.length);
      u8().set(data, p);
      const r = x.st_feed(h, p, data.length, origin);
      x.ghostty_wasm_free(p, data.length);
      return r;
    };
    const take = (status, what) => {
      require(status === 0, `${what} -> ST_OK`);
      const p = dv().getUint32(u32Slot, true);
      const n = dv().getUint32(lenSlot, true);
      const bytes = u8().slice(p, p + n); // owned copy before the next call can grow memory
      check(x.st_free_buffer(p) === 0, `${what}: st_free_buffer`);
      return new DataView(bytes.buffer);
    };
    const envelope = (v, kind) =>
      v.getUint32(0, true) === 0x53545654 && v.getUint16(4, true) === ST_CODEC_ABI && v.getUint16(6, true) === kind &&
      v.getUint32(8, true) === v.byteLength - 12;
    check(stFeed('\x1b[31mred\x1b[0m') === 0, 'st_feed red fixture');
    const vp = take(x.st_read_viewport(h, 1, u32Slot, lenSlot, 0), 'st_read_viewport(FORCE_FULL)');
    check(envelope(vp, 1), `viewport envelope (${vp.byteLength} bytes)`);
    const redRgba = BigInt(((red[0] << 24) | (red[1] << 16) | (red[2] << 8) | 0xff) >>> 0);
    let off = 12 + 8 + 16;
    const rowCount = vp.getUint32(off, true);
    off += 4;
    const row0 = vp.getInt32(off, true);
    const cellCount = vp.getUint32(off + 4, true);
    off += 8;
    let stRed = 0;
    for (let i = 0; i < 4; i++) {
      const tl = vp.getUint32(off, true);
      const text = new TextDecoder().decode(new Uint8Array(vp.buffer, off + 4, tl));
      off += 4 + tl;
      const width = vp.getInt32(off, true);
      const fg = vp.getBigUint64(off + 4, true);
      const bg = vp.getBigUint64(off + 12, true);
      off += 4 + 8 + 8 + 4 + 4;
      if (i < 3 && text === 'red'[i] && width === 1 && fg === redRgba && bg === 1n << 32n) stRed++;
      if (i === 3) check(text === '' && fg === 1n << 32n, 'st: cell 3 empty with DEFAULT fg');
    }
    check(rowCount === 24 && row0 === 0 && cellCount === 80 && stRed === 3,
      `st: full frame 24 rows x 80 cells, three red cells fg=0x${redRgba.toString(16)}`);
    check(stFeed('\x1b[6n', 1) === 0, 'st_feed REPLAY CSI 6n');
    const none = take(x.st_drain_effects(h, u32Slot, lenSlot), 'st_drain_effects (replay)');
    check(envelope(none, 2) && none.getUint32(12, true) === 0, 'st: REPLAY query queues no effects');
    stFeed('\x1b[6n');
    const fx = take(x.st_drain_effects(h, u32Slot, lenSlot), 'st_drain_effects (live)');
    const fxLen = fx.getUint32(17, true);
    const reply = new TextDecoder().decode(new Uint8Array(fx.buffer, 21, fxLen));
    check(envelope(fx, 2) && fx.getUint32(12, true) === 1 && fx.getUint8(16) === 1 && reply === '\x1b[1;4R',
      `st: LIVE CSI 6n -> one Response ${JSON.stringify(reply)}`);
    check(x.st_destroy(h) === 0 && x.st_destroy(h) === -1 && x.st_feed(h, 0, 0, 0) === -1,
      'st_destroy; second destroy and later calls -> ST_ERR_INVALID_HANDLE');
    x.ghostty_wasm_free(u32Slot, 8);
    x.ghostty_wasm_free(lenSlot, 4);
  }

  // ---- terminal responses + bell -----------------------------------------
  write(term, '\x1b[6n\x07');
  check(responses.join('') === '\x1b[1;4R', `CSI 6n delivered to write_pty callback (${JSON.stringify(responses.join(''))})`);
  check(bells === 1, 'BEL delivered to bell callback');

  // ---- resize --------------------------------------------------------------
  check(x.ghostty_terminal_resize(term, 40, 10, 8, 16) === SUCCESS, 'resize to 40x10');
  const u16Ptr = alloc(2);
  x.ghostty_terminal_get(term, E('GhosttyTerminalData', 'COLS'), u16Ptr);
  const cols = dv().getUint16(u16Ptr, true);
  x.ghostty_terminal_get(term, E('GhosttyTerminalData', 'ROWS'), u16Ptr);
  const rows = dv().getUint16(u16Ptr, true);
  check(cols === 40 && rows === 10, `terminal reports ${cols}x${rows}`);

  // ---- memory growth --------------------------------------------------------
  const before = memory.buffer.byteLength;
  const bigLen = 32 * 1024 * 1024;
  const big = alloc(bigLen);
  const after = memory.buffer.byteLength;
  u8()[big + bigLen - 1] = 0x5a; // fresh view after growth
  check(after > before && u8()[big + bigLen - 1] === 0x5a, `memory grew ${before} -> ${after} bytes; re-acquired view writes`);
  x.ghostty_wasm_free(big, bigLen);
  // Old handles stay valid across growth.
  x.ghostty_render_state_update(rs, term);
  write(term, '\x1b[6n');
  check(responses.length === 2, 'terminal + callbacks still work after memory growth');

  // ---- malformed input ------------------------------------------------------
  write(term, new Uint8Array([0x1b, 0x5b, 0x33, 0x31, 0x3b, 0xe2, 0x82, 0xff, 0x1b, 0x5d, 0x38, 0x3b]));
  write(term, '\x1b\\\x1b[0mok');
  check(true, 'malformed/incomplete sequences did not trap');

  // ---- 1000 create/free cycles ----------------------------------------------
  let cycles = 0;
  const memBeforeCycles = memory.buffer.byteLength;
  for (let i = 0; i < 1000; i++) {
    if (x.ghostty_terminal_new(0, slot, 80, 24) !== SUCCESS) break;
    const t = take();
    write(t, '\x1b[31mred\x1b[0m');
    x.ghostty_terminal_free(t);
    cycles++;
  }
  const memAfterCycles = memory.buffer.byteLength;
  check(cycles === 1000, `1000 terminals created/fed/freed`);
  check(memAfterCycles - memBeforeCycles <= 4 * 1024 * 1024,
    `no leak-driven memory growth over 1000 cycles (${memBeforeCycles} -> ${memAfterCycles} bytes)`);

  // ---- cleanup ----------------------------------------------------------------
  for (const [p, n] of [[bufPtr, bufSize], [textPtr, 16], [stylePtr, styleSize], [rgbPtr, 4], [palettePtr, T.GhosttyRenderStateColors.size], [u16Ptr, 2]]) {
    x.ghostty_wasm_free(p, n);
  }
  x.ghostty_wasm_free_opaque(handleSlot);
  x.ghostty_render_state_row_cells_free(cells);
  x.ghostty_render_state_row_iterator_free(it);
  x.ghostty_render_state_free(rs);
  x.ghostty_terminal_free(term);
  x.ghostty_wasm_free_opaque(slot);

  log(`# ${checks} checks, ${failures} failures`);
  return { checks, failures, passed: failures === 0 };
}
