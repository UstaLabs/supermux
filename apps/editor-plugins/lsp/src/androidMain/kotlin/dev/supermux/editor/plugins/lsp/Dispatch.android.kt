package dev.supermux.editor.plugins.lsp

internal actual val lspParseDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

internal actual val lspSliceBigMessages: Boolean = false

internal actual suspend fun lspGiveBack() = kotlinx.coroutines.yield()
