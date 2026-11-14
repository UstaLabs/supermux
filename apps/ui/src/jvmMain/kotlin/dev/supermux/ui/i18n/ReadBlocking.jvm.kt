package dev.supermux.ui.i18n

import kotlinx.coroutines.runBlocking

internal actual fun <T> readBlocking(block: suspend () -> T): T? = runBlocking { block() }
