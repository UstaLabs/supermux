// Test-only: load the syntax runtime and every test resource BEFORE any test runs. The Kotlin test
// module imports this file (TestResources.wasmJs.kt), and ES module evaluation waits for this
// top-level await, so the shared nativeBacked tests run synchronously, as on the JVM, reading
// their files from the loader's resource map. A failure is exported, not thrown, and every test
// that needs the runtime reports it.
import { initialize, putResource } from './syntax-loader.mjs';

const BASE = '/base/kotlin/'; // karma.config.d/syntax-wasm.js serves the staged resources here

async function setup() {
  await initialize(null, BASE + 'editor-syntax/tables/');
  const list = await (await fetch(BASE + 'syntax-test-resources.json')).json();
  await Promise.all(list.map(async (p) => {
    const r = await fetch(BASE + p);
    if (!r.ok) throw new Error(`${p}: HTTP ${r.status}`);
    putResource(p, new Uint8Array(await r.arrayBuffer()));
  }));
  return null;
}

export const setupFailure = await setup().catch((e) => `${e && e.reason}: ${e && e.message}`);

/**
 * Tests: measure how long the thread is held. A MessageChannel ping-pong runs whenever the event
 * loop is free; the longest interval between two pings is the longest run of work in between.
 */
export function startGapMonitor() {
  const ch = new MessageChannel();
  let last = performance.now(), max = 0, ticks = 0, on = true;
  const started = last, top = [];
  ch.port1.onmessage = () => {
    const now = performance.now();
    max = Math.max(max, now - last);
    top.push(now - last); top.sort((a, b) => b - a); top.length = Math.min(top.length, 5);
    last = now;
    ticks++;
    if (on) ch.port2.postMessage(0);
  };
  ch.port2.postMessage(0);
  return {
    stop() {
      on = false;
      ch.port1.close();
      const now = performance.now();
      return { max: Math.max(max, now - last), ticks, total: now - started, top: top.map((x) => x.toFixed(1)).join(' ') };
    },
  };
}
