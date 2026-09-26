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
// bench: opens ?bench=1 (the 10k-line file): 200 keystrokes then 400 wheel-scrolled frames, as
//        SampleApp.runBench does in the desktop window; prints window.__editorBench.

import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const CHROME = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const [mode, distArg, ...rest] = process.argv.slice(2);
if (!['cold', 'bench', 'eval'].includes(mode) || !distArg) {
  console.error('usage: node run.mjs cold|bench <dist> [--runs N] [--ceiling MS] [--syntax-ceiling MS] [--headed]');
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
      proc.kill();
      await sleep(300);
      fs.rmSync(profile, { recursive: true, force: true });
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
    const m = median(results.map((c) => c.maxHold));
    const ms = median(results.map((c) => c.syntaxHold));
    const colour = median(results.map((c) => c.toColouredMs));
    console.log(`COLD median of ${runs}: longest hold ${m} ms (ceiling ${ceiling}), after the backend ${ms} ms (ceiling ${syntaxCeiling}), first coloured frame ${colour} ms`);
    if (m > ceiling) { console.log(`COLD FAIL: longest hold ${m} ms > ${ceiling} ms`); failed = true; }
    if (ms > syntaxCeiling) { console.log(`COLD FAIL: syntax-phase hold ${ms} ms > ${syntaxCeiling} ms`); failed = true; }
  } else if (mode === 'eval') {
    // Debugging: load the page, wait, print an expression's value (the last argument).
    const chrome = await launchChrome(flag('--headed'));
    try {
      const page = await openPage(chrome.port, base);
      await waitFor(page, 'window.__cold', 120000);
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
