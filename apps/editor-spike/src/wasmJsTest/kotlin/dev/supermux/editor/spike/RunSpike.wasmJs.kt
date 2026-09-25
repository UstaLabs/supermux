package dev.supermux.editor.spike

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/**
 * The JS Promise as a plain external CLASS (what kotlinx-coroutines-test does for its TestResult):
 * `kotlin.js.Promise<out T>` cannot be the target of an actual typealias because of its variance.
 * kotlin-test's wasm adapter only needs the returned value to be a Promise at runtime.
 */
@JsName("Promise")
external class SpikePromise : JsAny

actual typealias SpikeResult = SpikePromise

@OptIn(DelicateCoroutinesApi::class)
actual fun runSpike(block: suspend () -> Unit): SpikeResult =
    GlobalScope.promise { block(); null }.unsafeCast<SpikePromise>()
