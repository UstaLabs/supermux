package dev.supermux.editor.syntax

import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

actual typealias TestResult = Unit

actual fun runSuspendTest(block: suspend CoroutineScope.() -> Unit): TestResult = runBlocking(block = block)

internal actual fun dispatchNow(host: Host, spec: TransactionSpec) = runBlocking { host.dispatch(spec) }
