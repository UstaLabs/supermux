package dev.supermux.ui.i18n

/** The browser cannot block: Main.kt loads the catalogs before the first frame instead. */
internal actual fun <T> readBlocking(block: suspend () -> T): T? = null
