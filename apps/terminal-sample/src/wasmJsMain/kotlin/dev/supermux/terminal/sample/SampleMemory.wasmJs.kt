package dev.supermux.terminal.sample

/**
 * Browser memory.
 *
 * `performance.memory` is a **Chromium-only, non-standard** property and it reports the JS heap,
 * not the wasm memory where the engine's scrollback actually lives. It is reported because it is
 * better than nothing for spotting a runaway, and [SampleMemory.source] names it so nobody mistakes
 * it for RSS. Everywhere else (Safari, Firefox) this returns zeros and says "unavailable".
 *
 * The number that WOULD matter in the browser is `WebAssembly.Memory.buffer.byteLength` of the
 * engine module, which the package does not expose (and should not: it is an implementation
 * detail of the loader). Browser memory therefore has to be read from the devtools memory panel.
 */
actual fun readSampleMemory(): SampleMemory {
    val used = jsHeapUsedBytes()
    val total = jsHeapTotalBytes()
    return if (used > 0) {
        SampleMemory(
            heapUsedBytes = used.toLong(),
            heapTotalBytes = total.toLong(),
            rssBytes = 0L,
            source = "performance.memory (JS heap only, Chromium)",
        )
    } else {
        SampleMemory(source = "unavailable in this browser")
    }
}

private fun jsHeapUsedBytes(): Double =
    js("(typeof performance !== 'undefined' && performance.memory) ? performance.memory.usedJSHeapSize : 0")

private fun jsHeapTotalBytes(): Double =
    js("(typeof performance !== 'undefined' && performance.memory) ? performance.memory.totalJSHeapSize : 0")
