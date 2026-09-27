#!/usr/bin/env node
// Drives loader-test-core.mjs against terminal-loader.mjs over real HTTP, in Node and (with
// --browser) in headless Chrome loading the loader as an ES module from the same server.
//
//   node loader-test.mjs <supermux-terminal.wasm> [--browser <chrome-binary>] [--work <dir>]
//
// The server binds 127.0.0.1 on an ephemeral port and serves only this directory's loader/test
// modules plus the fixture paths documented in loader-test-core.mjs.
import { readFileSync, mkdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const wasmPath = args[0];
if (!wasmPath) {
  console.error('usage: node loader-test.mjs <supermux-terminal.wasm> [--browser <chrome>] [--work <dir>]');
  process.exit(2);
}
const flag = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const chrome = flag('--browser');
const workDir = resolve(flag('--work') ?? join(here, '..', 'build', 'wasm-loader-test'));

const wasmBytes = readFileSync(wasmPath);
// A valid, empty wasm module (magic + version only).
const emptyModule = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]);
const page = `<!doctype html><meta charset="utf-8"><title>terminal-loader test</title>
<script type="module">
import { runLoaderTests } from './loader-test-core.mjs';
const lines = [];
window.__result = runLoaderTests(location.origin, (l) => lines.push(l))
  .then((r) => ({ ...r, lines, ua: navigator.userAgent }))
  .catch((e) => ({ passed: false, checks: 0, failures: 1, lines: [...lines, 'EXCEPTION ' + e.stack], ua: navigator.userAgent }));
</script>`;

const routes = {
  '/index.html': ['text/html', page],
  '/terminal-loader.mjs': ['text/javascript', readFileSync(join(here, 'terminal-loader.mjs'))],
  '/loader-test-core.mjs': ['text/javascript', readFileSync(join(here, 'loader-test-core.mjs'))],
  '/supermux-terminal.wasm': ['application/wasm', wasmBytes],
  '/octet/supermux-terminal.wasm': ['application/octet-stream', wasmBytes],
  '/garbage.wasm': ['application/wasm', readFileSync(join(here, 'terminal-loader.mjs'))],
  '/empty.wasm': ['application/wasm', emptyModule],
};
const server = createServer((req, res) => {
  const route = routes[new URL(req.url, 'http://x').pathname];
  if (!route) { res.writeHead(404, { 'content-type': 'text/plain' }); res.end('not found'); return; }
  res.writeHead(200, { 'content-type': route[0], 'cache-control': 'no-store' });
  res.end(route[1]);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const origin = `http://127.0.0.1:${server.address().port}`;

let ok = false;
try {
  console.log(`# node ${process.version}: loader tests against ${origin}`);
  const { runLoaderTests } = await import('./loader-test-core.mjs');
  const nodeResult = await runLoaderTests(origin, (l) => console.log(`[node] ${l}`));
  ok = nodeResult.passed;
  if (chrome) {
    const r = await runInChrome(chrome, `${origin}/index.html`);
    console.log(`# headless chrome: ${r.ua}`);
    for (const l of r.lines) console.log(`[chrome] ${l}`);
    ok = ok && r.passed;
  }
} finally {
  server.close();
}
console.log(ok ? 'LOADER TEST PASSED' : 'LOADER TEST FAILED');
process.exit(ok ? 0 : 1);

async function runInChrome(chromeBin, pageUrl) {
  mkdirSync(workDir, { recursive: true });
  const profile = join(workDir, 'chrome-profile');
  rmSync(profile, { recursive: true, force: true });
  const proc = spawn(chromeBin, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--disable-extensions', '--remote-debugging-port=0', `--user-data-dir=${profile}`, pageUrl,
  ], {
    stdio: ['ignore', 'ignore', 'pipe'],
    // With a session bus Chrome can stall every http(s) navigation on this kind of headless host
    // (file: URLs still load); no bus = no stall.
    env: { ...process.env, DBUS_SESSION_BUS_ADDRESS: 'disabled:' },
  });
  try {
    const wsUrl = await new Promise((res, rej) => {
      let err = '';
      const timer = setTimeout(() => rej(new Error(`chrome did not start: ${err}`)), 30000);
      proc.stderr.on('data', (d) => {
        err += d;
        const m = /DevTools listening on (ws:\/\/\S+)/.exec(err);
        if (m) { clearTimeout(timer); res(m[1]); }
      });
      proc.on('exit', (c) => rej(new Error(`chrome exited ${c}: ${err}`)));
    });
    const port = new URL(wsUrl).port;
    let target;
    for (let i = 0; i < 50 && !target; i++) {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      target = list.find((t) => t.type === 'page' && t.url === pageUrl);
      if (!target) await new Promise((r) => setTimeout(r, 200));
    }
    if (!target) throw new Error('loader test page target not found');
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    let id = 0;
    const pending = new Map();
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
    };
    const send = (method, params) => new Promise((res) => {
      const mid = ++id;
      pending.set(mid, res);
      ws.send(JSON.stringify({ id: mid, method, params }));
    });
    let value;
    for (let i = 0; i < 150 && !value; i++) {
      const r = await send('Runtime.evaluate', {
        expression: 'window.__result ? window.__result : null', awaitPromise: true, returnByValue: true,
      });
      value = r.result?.result?.value;
      if (!value) await new Promise((res) => setTimeout(res, 200));
    }
    ws.close();
    if (!value) throw new Error('loader test result never appeared in page');
    return value;
  } finally {
    proc.kill('SIGKILL');
  }
}
