#!/usr/bin/env node
// Runs smoke-core.mjs against a built ghostty-vt.wasm.
//
//   node smoke.mjs <ghostty-vt.wasm> [--browser <chrome-binary>] [--work <dir>]
//
// Always runs the fixture in Node first. With --browser it also runs the SAME
// fixture in headless Chrome: the wasm bytes and smoke-core.mjs are inlined
// into a standalone page (no server, no ghostty-web, no DOM terminal), and the
// result is read back over the Chrome DevTools protocol.
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runSmoke } from './smoke-core.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const wasmPath = args[0];
if (!wasmPath) {
  console.error('usage: node smoke.mjs <ghostty-vt.wasm> [--browser <chrome>] [--work <dir>]');
  process.exit(2);
}
const flag = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const chrome = flag('--browser');
const workDir = resolve(flag('--work') ?? join(here, '..', 'build', 'wasm-smoke'));

const wasmBytes = readFileSync(wasmPath);

console.log(`# node ${process.version}: running fixture`);
const nodeResult = await runSmoke(wasmBytes, (l) => console.log(`[node] ${l}`));
let ok = nodeResult.passed;

if (chrome) {
  const browserResult = await runInChrome(chrome);
  ok = ok && browserResult.passed;
}

console.log(ok ? 'WASM SMOKE PASSED' : 'WASM SMOKE FAILED');
process.exit(ok ? 0 : 1);

async function runInChrome(chromeBin) {
  mkdirSync(workDir, { recursive: true });
  const core = readFileSync(join(here, 'smoke-core.mjs'), 'utf8');
  const page = `<!doctype html><meta charset="utf-8"><title>ghostty-vt wasm smoke</title>
<pre id="log"></pre>
<script type="module">
${core}
const b64 = ${JSON.stringify(Buffer.from(wasmBytes).toString('base64'))};
const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
const lines = [];
window.__smoke = runSmoke(bin, (l) => { lines.push(l); document.getElementById('log').textContent += l + '\\n'; })
  .then((r) => ({ ...r, lines, ua: navigator.userAgent }))
  .catch((e) => ({ passed: false, checks: 0, failures: 1, lines: [...lines, 'EXCEPTION ' + e.stack], ua: navigator.userAgent }));
</script>`;
  const pagePath = join(workDir, 'smoke.html');
  writeFileSync(pagePath, page);
  const profile = join(workDir, 'chrome-profile');
  rmSync(profile, { recursive: true, force: true });

  const proc = spawn(chromeBin, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--disable-extensions', '--remote-debugging-port=0', `--user-data-dir=${profile}`,
    pathToFileURL(pagePath).href,
  ], { stdio: ['ignore', 'ignore', 'pipe'] });

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
      target = list.find((t) => t.type === 'page' && t.url.endsWith('smoke.html'));
      if (!target) await new Promise((r) => setTimeout(r, 200));
    }
    if (!target) throw new Error('smoke page target not found');

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
    for (let i = 0; i < 100; i++) {
      const r = await send('Runtime.evaluate', {
        expression: 'window.__smoke ? window.__smoke : null',
        awaitPromise: true, returnByValue: true,
      });
      value = r.result?.result?.value;
      if (value) break;
      await new Promise((res) => setTimeout(res, 200));
    }
    ws.close();
    if (!value) throw new Error('smoke result never appeared in page');
    console.log(`# headless chrome: ${value.ua}`);
    for (const l of value.lines) console.log(`[chrome] ${l}`);
    return value;
  } finally {
    proc.kill('SIGKILL');
  }
}
