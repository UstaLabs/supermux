package dev.supermux.editor.spike

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

actual typealias SpikeResult = Promise<JsAny?>

@OptIn(DelicateCoroutinesApi::class)
actual fun runSpike(block: suspend () -> Unit): SpikeResult = GlobalScope.promise { block(); null }
