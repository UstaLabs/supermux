package dev.supermux.editor.syntax

import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.js.Promise

/** A JS Promise, non-generic so it can be the actual of `expect class TestResult` (kotlin-test awaits it). */
@JsName("Promise")
external class TestPromise : JsAny

actual typealias TestResult = TestPromise

private fun jsError(message: String): JsAny = js("new Error(message)")

actual fun runSuspendTest(block: suspend CoroutineScope.() -> Unit): TestResult = Promise<JsAny?> { resolve, reject ->
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
            resolve(null)
        } catch (t: Throwable) {
            reject(jsError(t.stackTraceToString()))
        }
    }
}.unsafeCast<TestPromise>()

internal actual fun dispatchNow(host: Host, spec: TransactionSpec) = host.applyNow(spec)
