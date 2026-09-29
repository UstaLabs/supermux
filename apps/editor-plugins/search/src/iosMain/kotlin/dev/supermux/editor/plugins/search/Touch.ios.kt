package dev.supermux.editor.plugins.search

internal actual val touchFirst: Boolean = true

internal actual suspend fun giveBackThread() = kotlinx.coroutines.yield()
