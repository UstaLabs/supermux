package dev.supermux.editor.plugins.diff

internal actual val diffWorker: kotlinx.coroutines.CoroutineDispatcher? = kotlinx.coroutines.Dispatchers.Default

internal actual suspend fun giveBackThread() = kotlinx.coroutines.yield()
