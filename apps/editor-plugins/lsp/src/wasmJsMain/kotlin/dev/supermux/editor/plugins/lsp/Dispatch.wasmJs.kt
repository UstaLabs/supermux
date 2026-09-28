package dev.supermux.editor.plugins.lsp

// The web has one thread: parsing runs on it (Dispatchers.Default is the event loop there too).
internal actual val lspParseDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

internal actual val lspSliceBigMessages: Boolean = true

/**
 * A real macrotask (MessageChannel): kotlinx-coroutines' JS dispatcher runs up to 16 queued tasks per
 * event-loop turn, so a `yield()` alone does not let the browser paint (the search plugin's lesson).
 */
internal actual suspend fun lspGiveBack() = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
    postMacrotask { if (c.isActive) c.resumeWith(Result.success(Unit)) }
}

private fun postMacrotask(f: () -> Unit) {
    js("{ const ch = new MessageChannel(); ch.port1.onmessage = () => { ch.port1.close(); f(); }; ch.port2.postMessage(0); }")
}
