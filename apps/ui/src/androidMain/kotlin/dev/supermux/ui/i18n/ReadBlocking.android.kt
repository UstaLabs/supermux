package dev.supermux.ui.i18n

import kotlinx.coroutines.runBlocking

internal actual fun <T> readBlocking(block: suspend () -> T): T? = runBlocking { block() }

internal actual fun readFromClasspath(path: String): String? =
    Catalogs::class.java.classLoader?.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
