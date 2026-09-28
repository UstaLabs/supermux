package dev.supermux.editor.plugins.search

/** A phone or tablet browser (touch, no fine pointer): the big targets. */
internal actual val touchFirst: Boolean by lazy { coarsePointer() }

private fun coarsePointer(): Boolean = js("(typeof matchMedia === 'function') && matchMedia('(pointer: coarse)').matches")

/**
 * A real macrotask: kotlinx-coroutines' JS dispatcher runs up to 16 queued tasks per event-loop
 * turn, so `yield()` alone kept the page busy for 16 slices (~70 ms measured). A MessageChannel
 * message (no setTimeout clamp) lets the browser paint and take input first.
 */
internal actual suspend fun giveBackThread() = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
    postMacrotask { if (c.isActive) c.resumeWith(Result.success(Unit)) }
}

private fun postMacrotask(f: () -> Unit) {
    js("{ const ch = new MessageChannel(); ch.port1.onmessage = () => { ch.port1.close(); f(); }; ch.port2.postMessage(0); }")
}
