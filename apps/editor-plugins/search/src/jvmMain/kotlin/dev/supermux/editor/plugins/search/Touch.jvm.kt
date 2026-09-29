package dev.supermux.editor.plugins.search

internal actual val touchFirst: Boolean = false

internal actual suspend fun giveBackThread() = kotlinx.coroutines.yield()
