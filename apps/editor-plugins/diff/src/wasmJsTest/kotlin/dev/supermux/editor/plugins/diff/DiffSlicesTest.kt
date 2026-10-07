package dev.supermux.editor.plugins.diff

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A JS Promise, non-generic, which kotlin-test awaits (editor-syntax's MainThreadTest pattern). */
@JsName("Promise")
external class DiffTestPromise : JsAny

private fun jsError(message: String): JsAny = js("new Error(message)")

private fun runSuspendTest(block: suspend CoroutineScope.() -> Unit): DiffTestPromise = Promise<JsAny?> { resolve, reject ->
    CoroutineScope(Dispatchers.Default).launch {
        try { block(); resolve(null) } catch (t: Throwable) { reject(jsError(t.stackTraceToString())) }
    }
}.unsafeCast<DiffTestPromise>()

/** A MessageChannel ping-pong: the longest gap between two pings is the longest the thread was held. */
private fun startGapMonitor(): JsAny = js("""(() => {
  const ch = new MessageChannel();
  let last = performance.now(), max = 0, on = true;
  ch.port1.onmessage = () => { const now = performance.now(); max = Math.max(max, now - last); last = now; if (on) ch.port2.postMessage(0); };
  ch.port2.postMessage(0);
  return { stop() { on = false; ch.port1.close(); return Math.max(max, performance.now() - last); } };
})()""")

private fun stopGapMonitor(m: JsAny): Double = js("m.stop()")

/** In the browser a whole diff never holds the page: slices of a few ms, a real macrotask between them. */
class DiffSlicesTest {
    @Test fun aBigDiffRunsInShortSlices() = runSuspendTest {
        val rnd = Random(1)
        val a = randomLines(rnd, 10_000)
        val b = mutate(rnd, a, 1_000)
        val expected = LineDiff.diff(a, b).hunks
        val monitor = startGapMonitor()
        val r = DiffJobs.diff(a, b, sliceMs = 4)
        val held = stopGapMonitor(monitor)
        println("DIFF-SLICES wasm 10k lines / 1k changes: longest slice ${DiffJobs.longestSliceMs} ms, page held at most $held ms, ${r.hunks.size} hunks")
        assertEquals(expected, r.hunks)
        assertTrue(held < 16, "the page was held $held ms")
    }
}
