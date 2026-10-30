package dev.supermux.webview.engine.mac

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor

/**
 * The live pages, by token (D5), and the hand-over of every upcall to the main thread.
 * - [post] runs on whichever thread the glue called from (AppKit's, or a background one for snapshots): it only hands
 *   the event to [executor], `Dispatchers.Main` (the EDT), which runs them in the order posted.
 * - Everything else runs on the main thread.
 */
internal object MacPages {
    /** Where upcalls run: the EDT. Tests run them by hand. */
    @Volatile var executor: Executor = Dispatchers.Main.asExecutor()

    /** What macOS memory pressure calls (`MacMemoryHook`). */
    @Volatile var onMemoryPressure: () -> Unit = {}

    private val handlers = HashMap<Long, (GlueEvent) -> Unit>()
    private val creating = HashMap<Long, CompletableDeferred<Long>>()
    private val abandoned = HashMap<Long, (Long) -> Unit>()
    private var nextToken = 1L

    fun post(page: Long, event: GlueEvent) {
        executor.execute { dispatch(page, event) }
    }

    private fun dispatch(page: Long, event: GlueEvent) {
        when (event) {
            is GlueEvent.Created -> {
                creating.remove(page)?.complete(event.handle)
                val release = abandoned.remove(page)
                if (release != null && event.handle != 0L) release(event.handle)
            }
            GlueEvent.Memory -> onMemoryPressure()
            else -> handlers[page]?.invoke(event)
        }
    }

    /** A token for a page about to be made, and the wait for its handle (`CREATED`). */
    fun newToken(): Pair<Long, CompletableDeferred<Long>> {
        val token = nextToken++
        val created = CompletableDeferred<Long>()
        creating[token] = created
        return token to created
    }

    /**
     * Gives up waiting for [token]'s creation (it took too long, or the wait was cancelled): a page the glue makes
     * after all is freed with [release] when its `CREATED` comes, so nothing leaks.
     */
    fun abandon(token: Long, release: (Long) -> Unit) {
        if (creating.remove(token) != null) abandoned[token] = release
    }

    fun register(token: Long, handler: (GlueEvent) -> Unit) {
        handlers[token] = handler
    }

    fun unregister(token: Long) {
        handlers.remove(token)
    }
}
