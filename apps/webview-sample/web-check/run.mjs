#!/usr/bin/env node
// The web engine's checks in a real Chrome, over the DevTools protocol, with no dependencies (Node >= 22).
//
//   node web-check/run.mjs memory  web-check/probe   # feasibility G4: Chrome's memory per live demo iframe, and a leak check
//   node web-check/run.mjs input   web-check/probe   # feasibility G2: trusted clicks through clip-path; wheel over an iframe
//   node web-check/run.mjs lab     <sample dist>     # the perf lab's runs on the iframe engine, with Chrome's memory (Task 16)
//   node web-check/run.mjs overlay <sample dist>     # a Compose menu and dialog over a live page, with trusted clicks (Task 16)
//   node web-check/run.mjs wheel   <sample dist>     # a trusted wheel over a live demo in the lab's list scrolls the list
//
// <sample dist> is :webview-sample:wasmJsBrowserDistribution's output (build/dist/wasmJs/productionExecutable).
// It serves <dir> on 127.0.0.1 (a random port) and starts Chrome headless with --remote-debugging-port=0, which binds to
// localhost: nothing is exposed. Chrome draws with the GPU (ANGLE on Metal) unless --software-gl. Options: --headed,
// --out <dir> (screenshots, default /tmp/webview-check). Lines starting PROBE or CHECK are results; exit code 1 = a
// check failed. Modeled on apps/editor-sample/web-bench/run.mjs.
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';

const CHROME = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MODES = ['memory', 'input', 'lab', 'overlay', 'wheel'];
const [mode, dirArg, ...rest] = process.argv.slice(2);
if (!MODES.includes(mode) || !dirArg) {
  console.error(`usage: node run.mjs ${MODES.join('|')} <dir> [--software-gl] [--headed] [--out <dir>]`);
  process.exit(2);
}
const flag = (name) => rest.includes(name);
const opt = (name, dflt) => { const i = rest.indexOf(name); return i >= 0 ? rest[i + 1] : dflt; };
const root = path.resolve(dirArg);
const out = path.resolve(opt('--out', path.join(os.tmpdir(), 'webview-check')));

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm',
  '.json': 'application/json', '.css': 'text/css', '.png': 'image/png', '.map': 'application/json', '.ttf': 'font/ttf',
};

function serve(dir) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    let file = path.join(dir, decodeURIComponent(url.pathname));
    if (!file.startsWith(dir)) { res.writeHead(403).end(); return; }
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
const median = (xs) => { const s = [...xs].sort((a, b) => a - b); return s[Math.floor(s.length / 2)]; };

async function launchChrome() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'webview-check-'));
  const args = [
    '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--window-size=1400,1000',
  ];
  if (!flag('--headed')) args.push('--headless=new');
  if (!flag('--software-gl')) args.push('--use-angle=metal', '--enable-gpu-rasterization', '--ignore-gpu-blocklist');
  args.push('about:blank');
  const proc = spawn(CHROME, args, { stdio: 'ignore' });
  const portFile = path.join(profile, 'DevToolsActivePort');
  for (let i = 0; i < 200 && !fs.existsSync(portFile); i++) await sleep(50);
  const [port] = fs.readFileSync(portFile, 'utf8').split('\n');
  return {
    port,
    pid: proc.pid,
    async close() {
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
      const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      return r.result && r.result.result ? r.result.result.value : undefined;
    },
    close() { ws.close(); },
  };
}

async function waitFor(page, expression, timeoutMs) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const v = await page.value(`JSON.stringify((${expression}) || null)`);
    if (v && v !== 'null') return JSON.parse(v);
    await sleep(200);
  }
  throw new Error(`timed out waiting for ${expression}`);
}

/** The resident memory of Chrome and every process under it, in MB (macOS/Linux ps). */
function treeRssMb(rootPid) {
  const rows = execFileSync('ps', ['-axo', 'pid=,ppid=,rss='], { encoding: 'utf8' })
    .trim().split('\n').map((l) => l.trim().split(/\s+/).map(Number));
  const children = new Map();
  for (const [pid, ppid] of rows) { if (!children.has(ppid)) children.set(ppid, []); children.get(ppid).push(pid); }
  const rss = new Map(rows.map(([pid, , kb]) => [pid, kb]));
  let kb = 0;
  const stack = [rootPid];
  while (stack.length) { const p = stack.pop(); kb += rss.get(p) || 0; for (const c of children.get(p) || []) stack.push(c); }
  return kb / 1024;
}

async function settledRss(page, pid) {
  await page.send('HeapProfiler.collectGarbage');
  await sleep(1000);
  const xs = [];
  for (let i = 0; i < 5; i++) { xs.push(treeRssMb(pid)); await sleep(500); }
  return median(xs);
}

/** Chrome and every process under it (ps), each with whether it is the GPU process. */
function treeProcesses(rootPid) {
  const rows = execFileSync('ps', ['-axo', 'pid=,ppid=,command='], { encoding: 'utf8' })
    .trim().split('\n').map((l) => { const m = l.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/); return [Number(m[1]), Number(m[2]), m[3]]; });
  const children = new Map();
  for (const [pid, ppid] of rows) { if (!children.has(ppid)) children.set(ppid, []); children.get(ppid).push(pid); }
  const command = new Map(rows.map(([pid, , cmd]) => [pid, cmd]));
  const out = [];
  const stack = [rootPid];
  while (stack.length) {
    const p = stack.pop();
    out.push({ pid: p, gpu: (command.get(p) || '').includes('--type=gpu-process') });
    for (const c of children.get(p) || []) stack.push(c);
  }
  return out;
}

/**
 * The physical footprint of Chrome and every process under it, in MB (macOS `footprint`): dirty and compressed memory,
 * what Activity Monitor shows. Summed RSS also counts, in every process, the clean pages of the framework they all map,
 * which the system evicts and reloads under memory pressure: on a Mac that swaps, it doesn't follow the iframes. The
 * GPU process's footprint swings by tens of MB on its own (its caches), so the total without it comes too. A process
 * that exits meanwhile makes `footprint` fail: it is asked again.
 */
function treeFootprintMb(rootPid) {
  for (let attempt = 0; ; attempt++) {
    try {
      const procs = treeProcesses(rootPid);
      const report = execFileSync('footprint', ['--noCategories', '-f', 'bytes', ...procs.map((p) => String(p.pid))], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
      const gpu = new Set(procs.filter((p) => p.gpu).map((p) => p.pid));
      let all = 0, withoutGpu = 0;
      for (const m of report.matchAll(/\[(\d+)\]: .*?Footprint: (\d+) B/g)) {
        const mb = Number(m[2]) / (1024 * 1024);
        all += mb;
        if (!gpu.has(Number(m[1]))) withoutGpu += mb;
      }
      return { all, withoutGpu };
    } catch (e) {
      if (attempt >= 2) throw e;
    }
  }
}

/** The footprint after [settledRss] (which collected garbage): the median of three samples, with and without the GPU. */
async function settledFootprint(pid) {
  const xs = [];
  for (let i = 0; i < 3; i++) { xs.push(treeFootprintMb(pid)); await sleep(500); }
  return { all: median(xs.map((x) => x.all)), withoutGpu: median(xs.map((x) => x.withoutGpu)) };
}

function slope(points) {
  const n = points.length, mx = points.reduce((s, p) => s + p[0], 0) / n, my = points.reduce((s, p) => s + p[1], 0) / n;
  const num = points.reduce((s, p) => s + (p[0] - mx) * (p[1] - my), 0), den = points.reduce((s, p) => s + (p[0] - mx) ** 2, 0);
  return num / den;
}

/** Every open shadow root's elements, searched from the document: Compose's a11y tree lives in one. */
const FIND = `(function find(test) {
  var roots = [document];
  for (var i = 0; i < roots.length; i++) {
    var all = roots[i].querySelectorAll('*');
    for (var j = 0; j < all.length; j++) { if (test(all[j])) return all[j]; if (all[j].shadowRoot) roots.push(all[j].shadowRoot); }
  }
  return null;
})`;
const centerOf = (test) => `(function () { var el = ${FIND}(${test}); if (!el) return null; var r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; })()`;
const byId = (id) => `function (el) { return el.id === ${JSON.stringify(id)}; }`;
const byText = (text) => `function (el) { return el.children.length === 0 && el.textContent === ${JSON.stringify(text)}; }`;
const rectOf = (test) => `(function () { var el = ${FIND}(${test}); if (!el) return null; var r = el.getBoundingClientRect(); return { x: r.left, y: r.top, width: r.width, height: r.height }; })()`;
const HIT = (x, y) => `(function () { var el = document.elementFromPoint(${x}, ${y}); if (!el) return 'none'; if (el.shadowRoot && el.shadowRoot.querySelector('canvas')) return 'compose'; return el.localName; })()`;

async function click(page, x, y) {
  for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) {
    await page.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: 1 });
  }
}

async function screenshot(page, name) {
  fs.mkdirSync(out, { recursive: true });
  const shot = await page.send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(out, name), Buffer.from(shot.result.data, 'base64'));
}

const MODE_RUNS = {
  // Feasibility G4.
  async memory(page, chrome) {
    await waitFor(page, 'window.probe && window.probe.ready', 30000);
    const points = [];
    // The same measurement in physical footprint (macOS only), beside RSS: see treeFootprintMb.
    const footprints = [];
    const lean = [];
    const withFootprint = process.platform === 'darwin';
    for (const n of [0, 6, 12, 24, 48]) {
      await page.value(`window.probe.set(${n})`);
      await waitFor(page, `window.probe.loaded() >= ${n}`, 60000);
      await sleep(2000);
      const mb = await settledRss(page, chrome.pid);
      points.push([n, mb]);
      console.log(`PROBE G4 memory live-iframes=${n} chrome-rss-mb=${mb.toFixed(1)}`);
      if (withFootprint) {
        const fp = await settledFootprint(chrome.pid);
        footprints.push([n, fp.all]);
        lean.push([n, fp.withoutGpu]);
        console.log(`PROBE G4 footprint live-iframes=${n} chrome-footprint-mb=${fp.all.toFixed(1)} without-gpu-process-mb=${fp.withoutGpu.toFixed(1)}`);
      }
    }
    const perFrame = slope(points);
    console.log(`PROBE G4 memory slope-mb-per-iframe=${perFrame.toFixed(2)}`);
    if (withFootprint) {
      console.log(`PROBE G4 footprint slope-mb-per-iframe=${slope(footprints).toFixed(2)} without-gpu-process=${slope(lean).toFixed(2)}`);
    }
    for (let i = 0; i < 200; i++) {
      await page.value('window.probe.set(12)');
      await waitFor(page, 'window.probe.loaded() >= 12', 60000);
      await page.value('window.probe.set(0)');
    }
    await sleep(5000);
    const after = await settledRss(page, chrome.pid);
    const growth = after - points[0][1];
    console.log(`PROBE G4 memory after-200-cycles-mb=${after.toFixed(1)} baseline-mb=${points[0][1].toFixed(1)} growth-mb=${growth.toFixed(1)}`);
    const pass = perFrame <= 20 && growth <= 30;
    console.log(`PROBE G4 ${pass ? 'PASS' : 'FAIL'} (slope <= 20 MB, growth <= 30 MB)`);
    if (withFootprint) {
      const fpAfter = await settledFootprint(chrome.pid);
      for (const [label, series, value] of [['', footprints, fpAfter.all], [' without-gpu-process', lean, fpAfter.withoutGpu]]) {
        const fpSlope = slope(series);
        const fpGrowth = value - series[0][1];
        console.log(`PROBE G4 footprint${label} after-200-cycles-mb=${value.toFixed(1)} baseline-mb=${series[0][1].toFixed(1)} growth-mb=${fpGrowth.toFixed(1)}`);
        console.log(`PROBE G4 footprint${label} ${fpSlope <= 20 && fpGrowth <= 30 ? 'PASS' : 'FAIL'} (slope <= 20 MB, growth <= 30 MB)`);
      }
    }
    return pass;
  },

  // Feasibility G2 with trusted input, and the wheel over a live iframe.
  async input(page) {
    await waitFor(page, 'window.probe && window.probe.ready', 30000);
    await sleep(300);
    const clickAt = async (x, y) => { await click(page, x, y); await sleep(250); return page.value('window.probe.take()'); };
    const points = [[320, 230, 'compose'], [203, 153, 'iframe'], [202, 230, 'compose'], [100, 100, 'iframe'], [20, 20, 'compose'], [700, 500, 'compose']];
    let pass = true;
    for (const [x, y, want] of points) {
      const got = await clickAt(x, y);
      pass = pass && got === want;
      console.log(`PROBE G2 trusted x=${x} y=${y} expected=${want} got=${got || 'nothing'} ${got === want ? 'PASS' : 'FAIL'}`);
    }
    await page.value('window.probe.inert(true)');
    const inert = await clickAt(100, 100);
    pass = pass && inert === 'compose';
    console.log(`PROBE G2 trusted inert-page x=100 y=100 expected=compose got=${inert || 'nothing'} ${inert === 'compose' ? 'PASS' : 'FAIL'}`);
    await page.value('window.probe.inert(false)');
    await page.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: 100, y: 100, deltaX: 0, deltaY: 120 });
    await sleep(300);
    console.log(`PROBE info wheel-over-live-iframe got=${(await page.value('window.probe.take()')) || 'nothing'}`);
    await page.value('window.probe.inert(true)');
    await page.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: 100, y: 100, deltaX: 0, deltaY: 120 });
    await sleep(300);
    console.log(`PROBE info wheel-over-inert-iframe got=${(await page.value('window.probe.take()')) || 'nothing'}`);
    return pass;
  },

  // The perf lab on the real engine (Task 16): the Reading test, then the Scroll test, each from a fresh page. Beside
  // the frame intervals, which sit at the display's interval while the work fits in it, Chrome's own counters for the
  // run (Performance.getMetrics): how many layouts and style recalculations the app's page did, and their time.
  async lab(page, chrome, base) {
    let pass = true;
    await page.send('Performance.enable', { timeDomain: 'threadTicks' });
    const metrics = async () => Object.fromEntries(((await page.send('Performance.getMetrics')).result?.metrics || []).map((m) => [m.name, m.value]));
    for (const run of ['reading', 'scroll']) {
      await page.send('Page.navigate', { url: `${base}?screen=lab&run=${run}` });
      await waitFor(page, 'window.__webviewSample && window.__webviewSample.ready', 60000);
      const start = await metrics();
      let shot = 0;
      const deadline = Date.now() + 10 * 60 * 1000;
      while (!(await page.value('window.__webviewSample.report'))) {
        if (Date.now() > deadline) throw new Error(`the ${run} run did not finish`);
        if (shot < 12) await screenshot(page, `lab-${run}-${String(shot++).padStart(2, '0')}.png`);
        await sleep(400);
      }
      const end = await metrics();
      const report = await page.value('window.__webviewSample.report');
      const stats = await page.value('window.__webviewSample.stats()');
      const mb = await settledRss(page, chrome.pid);
      const delta = (name) => (end[name] || 0) - (start[name] || 0);
      const ms = (name) => (delta(name) * 1000).toFixed(0);
      console.log(`CHECK lab run=${run} ${report}`);
      console.log(`CHECK lab run=${run} chrome layouts=${delta('LayoutCount')} layout-ms=${ms('LayoutDuration')} style-recalcs=${delta('RecalcStyleCount')} style-ms=${ms('RecalcStyleDuration')} script-ms=${ms('ScriptDuration')} task-ms=${ms('TaskDuration')}`);
      console.log(`CHECK lab run=${run} at-rest ${stats} chrome-rss-mb=${mb.toFixed(1)}`);
      const errors = await page.value('window.__webviewSample.errors()');
      if (errors) { console.log(`CHECK lab run=${run} FAIL runtime errors: ${errors}`); pass = false; }
    }
    return pass;
  },

  // A Compose menu and dialog over a live page take trusted clicks; the page takes them elsewhere (Task 16).
  async overlay(page, chrome, base) {
    await page.send('Page.navigate', { url: `${base}?screen=overlays` });
    await waitFor(page, 'window.__webviewSample && window.__webviewSample.ready && window.__webviewSample.overlayLive()', 60000);
    await sleep(800);
    const check = (name, ok, detail) => { console.log(`CHECK overlay ${name} ${ok ? 'PASS' : 'FAIL'}${detail ? ' ' + detail : ''}`); return ok; };
    let pass = true;
    // The controls are found in Compose's accessibility tree, which CMP 1.12.2 keeps in the DOM with a closed popup's
    // nodes in it, its main content's gone, until something else opens. So where everything is is read before any popup
    // opens, and a popup's closing is seen in the page's hit testing: its hole, and its scrim, go with it.
    const pageCenter = await waitFor(page, centerOf(byId('overlay-page')), 10000);
    const box = await waitFor(page, rectOf(byId('overlay-page')), 10000);
    const menuButton = await waitFor(page, centerOf(byId('open-menu')), 10000);
    const dialogButton = await waitFor(page, centerOf(byId('open-dialog')), 10000);
    pass = check('page-takes-clicks-with-no-overlay', (await page.value(HIT(pageCenter.x, pageCenter.y))) === 'iframe', JSON.stringify(pageCenter)) && pass;
    await click(page, menuButton.x, menuButton.y);
    const item = await waitFor(page, centerOf(byText('Open in browser')), 10000);
    await sleep(400);
    await screenshot(page, 'overlay-menu.png');
    pass = check('menu-item-is-hit-not-the-page', (await page.value(HIT(item.x, item.y))) === 'compose', JSON.stringify(item)) && pass;
    await click(page, item.x, item.y);
    await sleep(600);
    pass = check('menu-item-took-the-trusted-click', (await page.value(HIT(item.x, item.y))) === 'iframe', 'the menu, and its hole, are gone') && pass;
    await click(page, dialogButton.x, dialogButton.y);
    const close = await waitFor(page, centerOf(byId('close-dialog')), 10000);
    await sleep(600);
    await screenshot(page, 'overlay-dialog.png');
    pass = check('page-under-the-scrim-is-inert', (await page.value(HIT(pageCenter.x - 250, pageCenter.y))) === 'compose') && pass;
    await click(page, close.x, close.y);
    await sleep(600);
    pass = check('dialog-closed-by-a-trusted-click', (await page.value(HIT(close.x, close.y))) === 'iframe', 'the dialog, its hole and its scrim are gone') && pass;
    pass = check('page-takes-clicks-again', (await page.value(HIT(pageCenter.x, pageCenter.y))) === 'iframe') && pass;
    // The page's link, at its top, clicked for real, opens outside the app in a new tab.
    await click(page, box.x + box.width / 2, box.y + 24);
    await sleep(1500);
    const targets = await (await fetch(`http://127.0.0.1:${chrome.port}/json/list`)).json();
    pass = check('a-link-the-user-clicks-opens-outside', targets.some((t) => String(t.url).startsWith('https://example.com')), `${targets.length} targets`) && pass;
    return pass;
  },

  // Scroll chaining (feasibility amendment 3) with input the browser makes: Karma can only test it with synthetic
  // input. A wheel over a live demo in the perf lab's list, which the demo can't scroll, scrolls the list.
  async wheel(page, chrome, base) {
    await page.send('Page.navigate', { url: `${base}?screen=lab` });
    await waitFor(page, 'window.__webviewSample && window.__webviewSample.ready && window.__webviewSample.overlayLive()', 60000);
    await sleep(800);
    const LIVE = `(function () {
      var fs = document.querySelectorAll('[data-webview-frame]');
      for (var i = 0; i < fs.length; i++) {
        var f = fs[i], r = f.getBoundingClientRect();
        if (f.style.display === 'block' && f.style.visibility === 'visible' && r.top > 0 && r.height > 60) {
          return { x: r.left + r.width / 2, y: r.top + 30, top: r.top, index: i };
        }
      }
      return null;
    })()`;
    // Where the live pages are. A frame belongs to an engine, which the pool may give to another message as the list
    // moves, so the list's motion is the displacement most frames agree on, not one frame's.
    const TOPS = `(function () {
      var out = [], fs = document.querySelectorAll('[data-webview-frame]');
      for (var i = 0; i < fs.length; i++) if (fs[i].style.display === 'block' && fs[i].style.visibility === 'visible') out.push(fs[i].getBoundingClientRect().top);
      return out;
    })()`;
    const before = await waitFor(page, LIVE, 10000);
    const topsBefore = await page.value(TOPS);
    await screenshot(page, 'wheel-before.png');
    await page.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: before.x, y: before.y });
    for (let i = 0; i < 3; i++) {
      await page.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: before.x, y: before.y, deltaX: 0, deltaY: 100 });
      await sleep(60);
    }
    await sleep(800);
    await screenshot(page, 'wheel-after.png');
    const topsAfter = await page.value(TOPS);
    const votes = new Map();
    for (const b of topsBefore) for (const a of topsAfter) votes.set(Math.round(b - a), (votes.get(Math.round(b - a)) || 0) + 1);
    const [moved, agreeing] = [...votes.entries()].sort((x, y) => y[1] - x[1])[0] || [NaN, 0];
    const ok = moved >= 100 && agreeing >= 2;
    console.log(`CHECK wheel a-wheel-over-a-live-demo-scrolls-the-list ${ok ? 'PASS' : 'FAIL'} moved=${moved}px by ${agreeing} of ${topsBefore.length} pages, wheeled=300px over a demo`);
    return ok;
  },
};

const server = await serve(root);
const base = `http://127.0.0.1:${server.address().port}/` + (mode === 'memory' ? 'memory.html' : mode === 'input' ? 'input.html' : 'index.html');
const chrome = await launchChrome();
let pass = false;
try {
  const page = await openPage(chrome.port, mode === 'memory' || mode === 'input' ? base : 'about:blank');
  await page.send('Page.enable');
  pass = await MODE_RUNS[mode](page, chrome, base);
  page.close();
} catch (e) {
  console.log(`CHECK ${mode} FAIL ${e && e.stack || e}`);
} finally {
  await chrome.close();
  server.close();
  const load = os.loadavg();
  console.log(`${mode === 'memory' || mode === 'input' ? 'PROBE' : 'CHECK'} machine load ${load.map((l) => l.toFixed(1)).join(' ')} on ${os.cpus().length} cores`);
}
process.exit(pass ? 0 : 1);
