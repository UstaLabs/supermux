package dev.supermux.editor.spike

import kotlinx.coroutines.runBlocking

actual typealias SpikeResult = Unit

actual fun runSpike(block: suspend () -> Unit): SpikeResult = runBlocking { block() }
