package dev.supermux.editor.spike

/**
 * Stand-in for kotlinx-coroutines-test's runTest, whose 1.9.0 wasm-js klib does not link against the
 * Kotlin 2.4.10 stdlib ("Key kotlin.text/substring… is missing in the map"; see :terminal-core).
 * Unit on jvm/android/ios (runBlocking), a Promise on wasmJs (the Karma/Mocha adapter awaits it).
 */
expect class SpikeResult

expect fun runSpike(block: suspend () -> Unit): SpikeResult
