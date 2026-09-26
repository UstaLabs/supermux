package dev.supermux.editor.syntax

import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.CoroutineScope

/** What a suspending test returns: Unit natively (runBlocking), a Promise on the web (kotlin-test awaits it). */
expect class TestResult

/**
 * Run a suspending test body: runBlocking natively; on the web (one thread, no runBlocking) a
 * coroutine on the event loop whose Promise the test returns. (kotlinx-coroutines-test does not
 * link on wasmJs with this Kotlin.)
 */
expect fun runSuspendTest(block: suspend CoroutineScope.() -> Unit): TestResult

/**
 * Dispatch [spec] from inside a worker callback so that it is applied before the callback returns:
 * natively a blocking hop to the UI dispatcher; on the web, where the worker IS on the UI thread,
 * directly.
 */
internal expect fun dispatchNow(host: Host, spec: TransactionSpec)
