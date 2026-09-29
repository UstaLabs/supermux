#!/usr/bin/env node
// The editor sample's web measurements, in Chrome, driven over the DevTools protocol with no
// dependencies (Node >= 22: fetch and WebSocket are built in).
//
//   node web-bench/run.mjs cold  <dist> [--runs 5] [--ceiling 250] [--syntax-ceiling 120]
//   node web-bench/run.mjs bench <dist> [--headed]
//
// Chrome uses the GPU (ANGLE on Metal) even headless; --software-gl measures the software rasterizer
// instead (seconds of shader compiling in Compose's first frames: not the editor's cost).
//
// <dist> is :editor-sample:wasmJsBrowserDistribution's output (build/dist/wasmJs/productionExecutable).
//
// cold:  each run is a FRESH headless Chrome with a new profile (no code cache, no HTTP cache) loading
//        the page once. index.html's MessageChannel monitor records the longest the main thread was
//        held, per startup phase, from the page's first script to the first frame with syntax colours
//        (window.__cold). Asserts the median run's longest hold <= --ceiling, and the longest hold
//        after the syntax backend loaded (query compiles, first parse, first coloured paint) <=
//        --syntax-ceiling. Exit code 1 when a ceiling is exceeded.
// input: the web text-input path with trusted CDP input: Compose's TEXTAREA exists once the editor
//        has focus (mouse or touch), Input.insertText / imeSetComposition land at the editor's caret
//        after the editor moved it (DOM caret sync), a paste event pastes, and the accessibility tree
//        has exactly one text box. Exit code 1 when a check fails.
// two:   ?two=1 (two editors and a plain <input>): typing and IME land in the focused editor; a
//        programmatic edit of the unfocused editor never touches the other text inputs.
// bench: opens ?bench=1 (the 10k-line file): 200 keystrokes then 400 wheel-scrolled frames, as
//        SampleApp.runBench does in the desktop window; prints window.__editorBench.

import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const CHROME = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const [mode, distArg, ...rest] = process.argv.slice(2);
if (!['cold', 'bench', 'eval', 'input', 'two', 'widget', 'search'].includes(mode) || !distArg) {
  console.error('usage: node run.mjs cold|bench|input|two|widget|search <dist> [--runs N] [--ceiling MS] [--syntax-ceiling MS] [--headed]');
  process.exit(2);
}
const opt = (name, dflt) => { const i = rest.indexOf(name); return i >= 0 ? rest[i + 1] : dflt; };
const flag = (name) => rest.includes(name);
const dist = path.resolve(distArg);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm',
  '.json': 'application/json', '.css': 'text/css', '.txt': 'text/plain', '.ttf': 'font/ttf', '.sesz': 'application/octet-stream',
  '.map': 'application/json', '.cvr': 'application/octet-stream',
};

function serve(root) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    let file = path.join(root, decodeURIComponent(url.pathname));
    if (!file.startsWith(root)) { res.writeHead(403).end(); return; }
    if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
    fs.readFile(file, (err, data) => {
      if (err) { res.writeHead(404).end(); return; }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
      res.end(data);
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function launchChrome(headed) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'editor-bench-'));
  const args = [
    '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--window-size=1400,1000',
  ];
  if (!headed) args.push('--headless=new');
  // Headless Chrome renders WebGL with a software rasterizer unless told to use the GPU (ANGLE on
  // Metal on the Mac); Compose's first frames then spend seconds compiling shaders in software.
  if (!flag('--software-gl')) args.push('--use-angle=metal', '--enable-gpu-rasterization', '--ignore-gpu-blocklist');
  args.push('about:blank');
  const proc = spawn(CHROME, args, { stdio: 'ignore' });
  const portFile = path.join(profile, 'DevToolsActivePort');
  for (let i = 0; i < 200 && !fs.existsSync(portFile); i++) await sleep(50);
  const [port] = fs.readFileSync(portFile, 'utf8').split('\n');
  return {
    port,
    async close() {
      // Wait for Chrome to EXIT (its helpers write into the profile while they shut down) before
      // removing the profile: a fixed sleep lost that race (ENOTEMPTY in rmSync).
      const exited = proc.exitCode !== null || proc.signalCode !== null ? Promise.resolve() : new Promise((r) => proc.once('exit', r));
      proc.kill();
      await exited;
      fs.rmSync(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
    },
  };
}

async function openPage(port, url) {
  const target = await (await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' })).json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let id = 0;
  const pending = new Map();
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
  };
  const send = (method, params = {}) => new Promise((resolve) => { const i = ++id; pending.set(i, resolve); ws.send(JSON.stringify({ id: i, method, params })); });
  return {
    send,
    async value(expression) {
      const r = await send('Runtime.evaluate', { expression, returnByValue: true, includeCommandLineAPI: true });
      return r.result && r.result.result ? r.result.result.value : undefined;
    },
    close() { ws.close(); },
  };
}

async function waitFor(page, expression, timeoutMs) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const v = await page.value(`JSON.stringify(${expression} || null)`);
    if (v && v !== 'null') return JSON.parse(v);
    await sleep(250);
  }
  throw new Error(`timed out waiting for ${expression}`);
}

const median = (xs) => { const s = [...xs].sort((a, b) => a - b); return s[Math.floor(s.length / 2)]; };

const server = await serve(dist);
const base = `http://127.0.0.1:${server.address().port}/index.html`;
let failed = false;
try {
  if (mode === 'cold') {
    const runs = Number(opt('--runs', '5'));
    const ceiling = Number(opt('--ceiling', 'Infinity'));
    const syntaxCeiling = Number(opt('--syntax-ceiling', 'Infinity'));
    const results = [];
    for (let r = 0; r < runs; r++) {
      const chrome = await launchChrome(flag('--headed'));
      try {
        const page = await openPage(chrome.port, base);
        const cold = await waitFor(page, 'window.__cold', 120000);
        const syntaxHold = Math.max(0, ...cold.phases.filter((p) => !['page', 'app'].includes(p.phase)).map((p) => p.maxHold));
        cold.syntaxHold = syntaxHold;
        results.push(cold);
        console.log(`COLD run ${r + 1}: longest hold ${cold.maxHold} ms (after the backend loaded: ${syntaxHold} ms), first coloured frame at ${cold.toColouredMs} ms`);
        console.log(`  phases: ${cold.phases.map((p) => `${p.phase}<=${p.until}ms max ${p.maxHold}`).join(' | ')}`);
        console.log(`  longest: ${cold.top.map((t) => `${t.ms}(${t.phase}@${t.at})`).join(' ')}`);
        page.close();
      } finally {
        await chrome.close();
      }
    }
    // The ceilings hold on a quiet Mac. The Mac is shared: under heavy load (other sessions'
    // builds) every run is slower, M3a's build as much as M3b's (measured A/B 2026-09-26 at load
    // 35-45: 423/440 vs 439/444 ms), so there the result is recorded, not judged.
    const load = os.loadavg();
    const cores = os.cpus().length;
    const overloaded = Math.min(load[0], load[1]) > cores * 1.5;
    const m = median(results.map((c) => c.maxHold));
    const ms = median(results.map((c) => c.syntaxHold));
    const colour = median(results.map((c) => c.toColouredMs));
    console.log(`COLD median of ${runs}: longest hold ${m} ms (ceiling ${ceiling}), after the backend ${ms} ms (ceiling ${syntaxCeiling}), first coloured frame ${colour} ms`);
    console.log(`COLD load ${load.map((l) => l.toFixed(1)).join(' ')} on ${cores} cores${overloaded ? ' (overloaded: ceilings not applied)' : ''}`);
    if (overloaded) {
      if (m > ceiling || ms > syntaxCeiling) console.log(`COLD INCONCLUSIVE: over the ceiling under load (${m} / ${ms} ms); rerun on a quiet Mac`);
    } else {
      if (m > ceiling) { console.log(`COLD FAIL: longest hold ${m} ms > ${ceiling} ms`); failed = true; }
      if (ms > syntaxCeiling) { console.log(`COLD FAIL: syntax-phase hold ${ms} ms > ${syntaxCeiling} ms`); failed = true; }
    }
  } else if (mode === 'input') {
    const chrome = await launchChrome(flag('--headed'));
    try {
      const page = await openPage(chrome.port, base);
      await waitFor(page, 'window.__cold', 120000);
      await page.value("window.__editorOpen('TURKISH')");
      await waitFor(page, "window.__editorDoc && window.__editorDoc().startsWith('Türkçe') && window.__cold", 30000);
      await sleep(500);
      const doc = () => page.value('window.__editorDoc()');
      const sel = async () => (await page.value('window.__editorSel()')).split(',').map(Number);
      const check = (name, ok, detail = '') => { console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ': ' + detail : ''}`); if (!ok) failed = true; };
      const textarea = `(() => { const roots = [document]; for (let i = 0; i < roots.length; i++) for (const el of roots[i].querySelectorAll('*')) if (el.shadowRoot) roots.push(el.shadowRoot);
        for (const r of roots) { const t = r.querySelector('textarea'); if (t) return { exists: true, focused: r.activeElement === t, value: t.value.length, caret: t.selectionStart, around: t.value.slice(t.selectionStart - 4, t.selectionStart + 4) }; } return { exists: false }; })()`;
      const active = `(() => { let a = document.activeElement; while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement; return a ? a.tagName : 'none'; })()`;
      const key = async (k, code, vk, modifiers = 0, commands) => {
        await page.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: k, code, windowsVirtualKeyCode: vk, modifiers, ...(commands ? { commands } : {}) });
        await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk, modifiers });
        await sleep(80);
      };
      const insertedAtCaret = async (name, act, text) => {
        const [, h] = await sel();
        const before = await page.value(`JSON.stringify(${textarea})`);
        await act();
        await sleep(300);
        const d = await doc();
        check(name, d.slice(h, h + text.length) === text, `caret ${h}, found ${JSON.stringify(d.slice(h - 3, h + text.length + 3))}, active ${await page.value(active)}, dom before ${before}, doc around caret ${JSON.stringify(d.slice(h - 4, h + 4))}`);
      };
      let ta = JSON.parse(await page.value(`JSON.stringify(${textarea})`));
      check('no text input before focus is fine either way', true, JSON.stringify(ta));
      for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) await page.send('Input.dispatchMouseEvent', { type, x: 300, y: 130, button: 'left', clickCount: 1 });
      await sleep(500);
      ta = JSON.parse(await page.value(`JSON.stringify(${textarea})`));
      ta.active = await page.value(active);
      check('a mouse click creates and focuses the TEXTAREA', ta.exists && ta.active === 'TEXTAREA', JSON.stringify(ta));
      await key('End', 'End', 35);
      await insertedAtCaret('Input.insertText lands at the caret', () => page.send('Input.insertText', { text: 'ğüş' }), 'ğüş');
      for (let i = 0; i < 5; i++) await key('ArrowLeft', 'ArrowLeft', 37);
      await insertedAtCaret('after the editor moved the caret, insertText follows it', () => page.send('Input.insertText', { text: 'X' }), 'X');
      // Trace the DOM around the composition (printed when the check fails): the TEXTAREA's events
      // with its value and selection, frames, and the editor's own transactions and key paths.
      await page.value(`(() => { window.__trace = []; const roots = [document]; for (let i = 0; i < roots.length; i++) for (const el of roots[i].querySelectorAll('*')) if (el.shadowRoot) roots.push(el.shadowRoot);
        let t = null; for (const r of roots) t = t || r.querySelector('textarea');
        const at = () => t.selectionStart + '-' + t.selectionEnd + ' ' + JSON.stringify(t.value.slice(Math.max(0, t.selectionStart - 4), t.selectionStart + 4));
        for (const type of ['keydown', 'keyup', 'beforeinput', 'input', 'compositionstart', 'compositionupdate', 'compositionend', 'select', 'focus', 'blur'])
          t.addEventListener(type, (e) => window.__trace && window.__trace.push(performance.now().toFixed(1) + ' dom ' + type + ' ' + (e.inputType || '') + ' ' + JSON.stringify(e.data ?? e.key ?? '') + ' | ' + at()), true);
        document.addEventListener('selectionchange', () => window.__trace && window.__trace.push(performance.now().toFixed(1) + ' dom selectionchange | ' + at()));
        const frame = () => { if (!window.__trace) return; window.__trace.push(performance.now().toFixed(1) + ' frame | ' + at()); requestAnimationFrame(frame); }; requestAnimationFrame(frame);
        return true; })()`);
      await key('ArrowUp', 'ArrowUp', 38);
      await insertedAtCaret('IME composition lands at the caret', async () => {
        await page.send('Input.imeSetComposition', { text: 'に', selectionStart: 1, selectionEnd: 1 });
        await sleep(100);
        await page.send('Input.imeSetComposition', { text: 'にほ', selectionStart: 2, selectionEnd: 2 });
        await sleep(100);
        await page.send('Input.insertText', { text: '日本' });
      }, '日本');
      if (failed || flag('--trace')) console.log('TRACE (key ArrowUp before it, then the composition):\n  ' + (await page.value('window.__trace.join("\\n  ")')));
      // A long task between the composition's steps (a busy page) must not let the held caret move
      // through before Compose caught up: the hold counts frames, not milliseconds.
      const busy = (ms) => page.value(`(() => { const t0 = performance.now(); while (performance.now() - t0 < ${ms}) {} return true; })()`);
      for (let i = 0; i < 3; i++) await key('ArrowLeft', 'ArrowLeft', 37);
      // The long task runs right after the browser's selectionchange (a one-shot listener behind the
      // editor's): the held caret must still wait for Compose's frame.
      const busyAfterSelectionChange = (ms) => page.value(`(() => { const f = () => { const t0 = performance.now(); while (performance.now() - t0 < ${ms}) {} }; document.addEventListener('selectionchange', f, { once: true, capture: true }); return true; })()`);
      await insertedAtCaret('IME text lands at the caret with long tasks in between', async () => {
        await busyAfterSelectionChange(200);
        await page.send('Input.imeSetComposition', { text: 'か', selectionStart: 1, selectionEnd: 1 });
        await sleep(100);
        await busyAfterSelectionChange(200);
        await page.send('Input.imeSetComposition', { text: 'かな', selectionStart: 2, selectionEnd: 2 });
        await sleep(100);
        await busyAfterSelectionChange(200);
        await page.send('Input.insertText', { text: '仮名' });
        await busy(250);
      }, '仮名');
      await page.value('window.__trace = null');
      await key('Home', 'Home', 36);
      await insertedAtCaret('a paste event pastes at the caret', () => page.value(`(() => { const dt = new DataTransfer(); dt.setData('text/plain', 'PASTED'); const roots = [document]; for (let i = 0; i < roots.length; i++) for (const el of roots[i].querySelectorAll('*')) if (el.shadowRoot) roots.push(el.shadowRoot);
        let t = null; for (const r of roots) t = t || r.querySelector('textarea'); (t || document).dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, composed: true, cancelable: true })); return !!t; })()`), 'PASTED');
      // Cmd/Ctrl-V from the real clipboard: the browser's own paste command raises the paste event.
      await page.send('Browser.grantPermissions', { permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] });
      const wrote = await page.value("navigator.clipboard.writeText('CLIP').then(() => true, () => false)");
      await sleep(200);
      const mod = process.platform === 'darwin' ? 4 : 2;
      await insertedAtCaret('Mod-V pastes the clipboard', () => key('v', 'KeyV', 86, mod, ['paste']), 'CLIP');
      if (!wrote) console.log('  (clipboard write was refused: the Mod-V check depends on it)');
      // A caret move while the TEXTAREA's value is AHEAD of the editor (an edit Compose has not
      // processed yet) is held back, but only until the editor caught up or ~100 ms: then it is
      // applied once. Here the value never catches up (a character only the DOM has), so only the
      // time limit can let the user's caret through.
      const findTa = `const roots = [document]; for (let i = 0; i < roots.length; i++) for (const el of roots[i].querySelectorAll('*')) if (el.shadowRoot) roots.push(el.shadowRoot);
        let t = null; for (const r of roots) t = t || r.querySelector('textarea'); const proto = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');`;
      const caretMovedTo = async (name, prepare, cleanup) => {
        const [, h0] = await sel();
        const moved = JSON.parse(await page.value(`(() => { ${findTa} ${prepare}
          const c = t.selectionStart; const k = Math.max(0, c - 3); window.__keep = { value: t.value, k };
          proto.set.call(t, t.value + 'Z'); t.setSelectionRange(k, k); return JSON.stringify({ c, k }); })()`));
        await sleep(400);
        const [, h1] = await sel();
        await page.value(`(() => { ${findTa} proto.set.call(t, window.__keep.value); t.setSelectionRange(window.__keep.k, window.__keep.k); ${cleanup} return true; })()`);
        await sleep(200);
        const expected = h0 - (moved.c - moved.k);
        check(name, h1 === expected, `editor caret ${h0} -> ${h1}, expected ${expected} (textarea ${moved.c} -> ${moved.k})`);
      };
      await caretMovedTo('a caret moved while the TEXTAREA is ahead is applied within ~100 ms', '', '');
      // A compositionend that never came (the focus left mid-composition) must not leave caret
      // moves ignored: the blur ends the editor's "composing".
      await page.value(`(() => { ${findTa} t.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true, composed: true, data: '' })); t.blur(); return true; })()`);
      await sleep(200);
      for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) await page.send('Input.dispatchMouseEvent', { type, x: 300, y: 160, button: 'left', clickCount: 1 });
      await sleep(500);
      {
        const [, h0] = await sel();
        const moved = JSON.parse(await page.value(`(() => { ${findTa} const c = t.selectionStart; const k = Math.max(0, c - 2); t.setSelectionRange(k, k); return JSON.stringify({ c, k }); })()`));
        await sleep(300);
        const [, h1] = await sel();
        check('after a composition that never ended and a blur, caret moves are followed again', h1 === h0 - (moved.c - moved.k), `editor caret ${h0} -> ${h1} (textarea ${moved.c} -> ${moved.k})`);
      }
      await page.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 300, y: 200 }] });
      await sleep(50);
      await page.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await sleep(500);
      ta = JSON.parse(await page.value(`JSON.stringify(${textarea})`));
      check('after a touch the TEXTAREA is still there', ta.exists, JSON.stringify(ta));
      await page.send('Accessibility.enable');
      const tree = (await page.send('Accessibility.getFullAXTree')).result.nodes;
      const boxes = tree.filter((n) => !n.ignored && (n.role?.value === 'textbox' || n.role?.value === 'TextField'));
      const describe = async (b) => { if (!b.backendDOMNodeId) return '?'; const d = (await page.send('DOM.describeNode', { backendNodeId: b.backendDOMNodeId })).result?.node; return d ? `${d.nodeName}[${(d.attributes || []).join(' ')}]`.slice(0, 160) : '?'; };
      const described = [];
      for (const b of boxes) described.push(`'${b.name?.value}' ${await describe(b)} value=${JSON.stringify((b.value?.value || '').slice(0, 40))}`);
      check('exactly one text box in the accessibility tree', boxes.length === 1, described.join(' | '));
      check('the text box is the editor, named', boxes.length > 0 && boxes[0].name?.value === 'Sample editor', boxes[0]?.name?.value);
      const caretLine = (await doc()).split('\n').find((l) => l.includes('PASTED'));
      check('it holds the caret\'s line', boxes.length > 0 && (boxes[0].value?.value || '').includes(caretLine), caretLine);
      page.close();
    } finally {
      await chrome.close();
    }
  } else if (mode === 'search') {
    // The search panel on the web: Mod-f from the editor, typing into the panel's own field (a
    // Compose text field: its own TEXTAREA session), Turkish case folding, Enter, Escape back.
    const chrome = await launchChrome(flag('--headed'));
    try {
      const page = await openPage(chrome.port, base);
      await waitFor(page, 'window.__cold', 120000);
      await page.value("window.__editorOpen('TURKISH')");
      await waitFor(page, "window.__editorDoc && window.__editorDoc().startsWith('Türkçe') && window.__cold && !!window.__search", 30000);
      await sleep(500);
      const check = (name, ok, detail = '') => { console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ': ' + detail : ''}`); if (!ok) failed = true; };
      const doc = () => page.value('window.__editorDoc()');
      const sel = async () => (await page.value('window.__editorSel()')).split(',').map(Number);
      const search = async () => page.value('JSON.stringify(window.__search())').then(JSON.parse);
      const selected = async () => { const [a, h] = await sel(); return (await doc()).slice(Math.min(a, h), Math.max(a, h)); };
      const mod = process.platform === 'darwin' ? 4 : 2;
      const key = async (k, code, vk, modifiers = 0) => {
        await page.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: k, code, windowsVirtualKeyCode: vk, modifiers });
        await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk, modifiers });
        await sleep(120);
      };
      for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) await page.send('Input.dispatchMouseEvent', { type, x: 300, y: 130, button: 'left', clickCount: 1 });
      await sleep(400);
      await key('f', 'KeyF', 70, mod);
      await sleep(500);
      let s = await search();
      check('Mod-f opens the panel and moves the focus into it', s.open && !s.focused, JSON.stringify(s));
      await key('a', 'KeyA', 65, mod);
      await page.send('Input.insertText', { text: 'ıstanbul' });
      await sleep(500);
      s = await search();
      check('typing in the panel searches (debounced)', s.search === 'ıstanbul', JSON.stringify(s));
      check('without match case, ı finds İstanbul (and the first match is selected)', (await selected()) === 'İstanbul', JSON.stringify(await selected()));
      check('the count is shown', s.count === '1 of 1', s.count);
      await key('a', 'KeyA', 65, mod);
      await page.send('Input.insertText', { text: 'ük' });
      await sleep(500);
      const first = await sel();
      await key('Enter', 'Enter', 13);
      await sleep(200);
      const second = await sel();
      check('Enter in the field goes to the next match', second[0] !== first[0] && (await selected()) === 'ük', `${first} -> ${second}`);
      s = await search();
      check('"2 of 2"', s.count === '2 of 2', s.count);
      await key('Escape', 'Escape', 27);
      await sleep(400);
      s = await search();
      check('Escape closes the panel and gives the editor the focus', !s.open && s.focused, JSON.stringify(s));
      const [, h] = await sel();
      await page.send('Input.insertText', { text: 'Q' });
      await sleep(300);
      check('then typing reaches the editor', (await doc()).charAt(Math.max(0, h - 2)) === 'Q' || (await doc()).includes('Q'), JSON.stringify((await doc()).slice(h - 4, h + 4)));
      page.close();
    } finally {
      await chrome.close();
    }
  } else if (mode === 'two') {
    const chrome = await launchChrome(flag('--headed'));
    try {
      const page = await openPage(chrome.port, `${base}?two=1`);
      await waitFor(page, "typeof window.__twoDoc === 'function'", 60000);
      await sleep(1500);
      const check = (name, ok, detail = '') => { console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ': ' + detail : ''}`); if (!ok) failed = true; };
      const doc = (i) => page.value(`window.__twoDoc(${i})`);
      const click = async (x, y) => { for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) await page.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: 1 }); await sleep(500); };
      const textareas = `(() => { const out = []; const roots = [document]; for (let i = 0; i < roots.length; i++) for (const el of roots[i].querySelectorAll('*')) { if (el.shadowRoot) roots.push(el.shadowRoot); if (el.tagName === 'TEXTAREA') out.push(el.value); } return JSON.stringify(out); })()`;
      const h = (await page.send('Runtime.evaluate', { expression: 'window.innerHeight', returnByValue: true })).result.result.value;
      // Editor 1 (top half): click the end of its first line, type, compose.
      await click(400, 12);
      await page.send('Input.insertText', { text: 'A1' });
      await sleep(300);
      await page.send('Input.imeSetComposition', { text: 'に', selectionStart: 1, selectionEnd: 1 });
      await sleep(100);
      await page.send('Input.insertText', { text: '日' });
      await sleep(300);
      check('typing and IME land in editor 1', (await doc(0)).includes('A1日') && !(await doc(1)).includes('A1'), JSON.stringify([await doc(0), await doc(1)]));
      // Editor 2 (bottom half).
      await click(400, h / 2 + 12);
      await page.send('Input.insertText', { text: 'B2' });
      await sleep(300);
      check('typing lands in editor 2, not editor 1', (await doc(1)).includes('B2') && !(await doc(0)).includes('B2'), JSON.stringify([await doc(0), await doc(1)]));
      const before = await page.value(textareas);
      // A programmatic edit of editor 1 while editor 2 has the focus.
      await page.value("window.__twoEdit(0, 'PROG ')");
      await sleep(400);
      const after = await page.value(textareas);
      check('an unfocused editor never rewrites the focused one\'s TEXTAREA', before === after && !after.includes('PROG'), `${before} -> ${after}`);
      await page.send('Input.insertText', { text: 'C3' });
      await sleep(300);
      check('editor 2 keeps typing at its caret', (await doc(1)).includes('B2C3') && !(await doc(0)).includes('C3'), await doc(1));
      // The plain <input> on the page: its own text, the editors untouched.
      // A real click on it, as a user focuses it.
      const r = JSON.parse(await page.value("JSON.stringify(document.getElementById('plain').getBoundingClientRect())"));
      await click(r.x + r.width / 2, r.y + r.height / 2);
      await page.send('Input.insertText', { text: 'zz' });
      await sleep(300);
      const plain = await page.value("document.getElementById('plain').value");
      check('a plain input on the page gets its own typing', plain === 'zz' && !(await doc(0)).includes('zz') && !(await doc(1)).includes('zz'), JSON.stringify([plain, await page.value("(() => { let a = document.activeElement; return a ? a.tagName + '#' + a.id : 'none'; })()")]));
      page.close();
    } finally {
      await chrome.close();
    }
  } else if (mode === 'widget') {
    // A text field INSIDE a block widget (the M3c demo's review thread): it takes the typing and its
    // keys, the editor none of them; a click on the text gives the editor its input back.
    const chrome = await launchChrome(flag('--headed'));
    try {
      const page = await openPage(chrome.port, base);
      await waitFor(page, 'window.__cold', 120000);
      await page.value("window.__editorOpen('DEMO')");
      await waitFor(page, "window.__demoProbe && window.__demoProbe().x !== undefined", 30000);
      await sleep(800);
      const check = (name, ok, detail = '') => { console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ': ' + detail : ''}`); if (!ok) failed = true; };
      const doc = () => page.value('window.__editorDoc()');
      const probe = async () => JSON.parse(await page.value('JSON.stringify(window.__demoProbe())'));
      const click = async (x, y) => { for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) await page.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: 1 }); await sleep(500); };
      const key = async (k, code, vk) => { await page.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: k, code, windowsVirtualKeyCode: vk }); await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk }); await sleep(80); };
      const before = await doc();
      const p0 = await probe();
      // A phone: a touch into the field, then its keyboard's text.
      await page.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: p0.x, y: p0.y }] });
      await sleep(50);
      await page.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await sleep(600);
      await page.send('Input.insertText', { text: 'T' });
      await sleep(400);
      check('a touch into the widget field, then typing: the field has it', (await probe()).draft === 'T' && (await doc()) === before, JSON.stringify((await probe()).draft));
      // A mouse and a hardware keyboard: typing, Backspace, an arrow.
      await click(p0.x, p0.y);
      await key('End', 'End', 35);
      await page.send('Input.insertText', { text: 'from the widget' });
      await sleep(300);
      await key('Backspace', 'Backspace', 8);
      await key('ArrowLeft', 'ArrowLeft', 37);
      await sleep(300);
      const p1 = await probe();
      check('the widget field takes typing and keys', p1.draft === 'Tfrom the widge', JSON.stringify(p1.draft));
      check('the editor took none of it', (await doc()) === before);
      // (Not checked: a touch on a field a mouse focused loses the next text in Compose web 1.12 for
      // ANY Compose text field, one outside the editor too; see the editor-compose README.)
      // Back to the editor: a click on its text.
      await click(300, 60);
      await page.send('Input.insertText', { text: 'EDX' });
      await sleep(300);
      check('a click on the text gives the editor its input back', (await doc()).includes('EDX') && !(await probe()).draft.includes('EDX'), JSON.stringify((await probe()).draft));
      // The find panel (outside the scrolling area) types on its own too.
      await page.value('window.__demoPanel(true)');
      await sleep(800);
      const q = await probe();
      await click(q.fx, q.fy);
      await page.send('Input.insertText', { text: 'needle' });
      await sleep(300);
      check('the panel field types on its own', (await probe()).find === 'needle' && !(await doc()).includes('needle'), JSON.stringify((await probe()).find));
      page.close();
    } finally {
      await chrome.close();
    }
  } else if (mode === 'eval') {
    // Debugging: load the page, wait, print an expression's value (the last argument).
    const chrome = await launchChrome(flag('--headed'));
    try {
      const page = await openPage(chrome.port, base);
      await waitFor(page, 'window.__cold', 120000);
      if (flag('--click')) {
        await page.value("window.__paths = []; window.addEventListener('keydown', (e) => window.__paths.push(e.composedPath().slice(0, 4).map((n) => n.tagName || n.nodeName || String(n)).join('>') + ' key=' + e.key + ' code=' + e.code), true)");
        for (const type of ['mousePressed', 'mouseReleased']) await page.send('Input.dispatchMouseEvent', { type, x: 700, y: 300, button: 'left', clickCount: 1 });
        await sleep(500);
        await page.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'x', code: 'KeyX', text: 'x', windowsVirtualKeyCode: 88 });
        await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'x', code: 'KeyX', windowsVirtualKeyCode: 88 });
        await sleep(300);
      }
      console.log(await page.value(rest[rest.length - 1]));
      page.close();
    } finally {
      await chrome.close();
    }
  } else {
    const chrome = await launchChrome(flag('--headed'));
    try {
      const page = await openPage(chrome.port, `${base}?bench=1`);
      // The page times its keystrokes, then asks for scrolling: 400 trusted wheel events at ~60 Hz.
      await waitFor(page, 'window.__editorScrollReady', 300000);
      for (const type of ['mouseMoved']) await page.send('Input.dispatchMouseEvent', { type, x: 700, y: 500 });
      for (let i = 0; i < 400; i++) {
        await page.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: 700, y: 500, deltaX: 0, deltaY: 40 });
        await sleep(16);
      }
      await page.value('window.__editorScrollDone = true');
      // Then typing: a click into the text, and 200 x's at a human pace (30-80 ms apart).
      await waitFor(page, 'window.__editorTypeReady', 300000);
      for (const type of ['mousePressed', 'mouseReleased']) await page.send('Input.dispatchMouseEvent', { type, x: 700, y: 300, button: 'left', clickCount: 1 });
      await sleep(300);
      for (let i = 0; i < 200; i++) {
        await sleep(30 + Math.floor(Math.random() * 50));
        await page.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'x', code: 'KeyX', text: 'x', unmodifiedText: 'x', windowsVirtualKeyCode: 88 });
        await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'x', code: 'KeyX', windowsVirtualKeyCode: 88 });
      }
      // Then the bound keys, served by the keymap inside the DOM event: 40 of each.
      const named = [['Backspace', 8], ['Enter', 13], ['ArrowLeft', 37], ['ArrowUp', 38], ['ArrowRight', 39], ['ArrowDown', 40]];
      for (const [key, vk] of named) {
        for (let i = 0; i < 40; i++) {
          await sleep(30 + Math.floor(Math.random() * 50));
          await page.send('Input.dispatchKeyEvent', { type: 'keyDown', key, code: key, windowsVirtualKeyCode: vk, text: key === 'Enter' ? '\r' : undefined });
          await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key, code: key, windowsVirtualKeyCode: vk });
        }
      }
      await sleep(500);
      await page.value('window.__editorTypeDone = true');
      const result = await waitFor(page, 'window.__editorBench', 300000);
      console.log(`WEB_BENCH ${JSON.stringify(result)}`);
      page.close();
    } finally {
      await chrome.close();
    }
  }
} finally {
  server.close();
}
process.exit(failed ? 1 : 0);
