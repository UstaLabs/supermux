package dev.supermux.webview.engine.linux

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Routes the glue's events, made on the WebKit thread, to their engines on the main thread, in the order WebKit made
 * them. The WebKit thread only queues: it never waits on the JVM. A scheme request for a view no engine listens to any
 * more is answered 404 at once, so WebKit doesn't wait on it.
 */
internal object WpeHost {
    private val listeners = ConcurrentHashMap<Long, (WpeEvent) -> Unit>()
    private val queue = Channel<Pair<Long, WpeEvent>>(Channel.UNLIMITED)

    @Volatile
    private var attached = false

    @Synchronized
    fun attach() {
        if (attached) return
        attached = true
        WpeNative.sink = WpeNative.Sink { view, kind, a, b, s1, s2 ->
            WpeEvent.decode(kind, a, b, s1, s2)?.let { queue.trySend(view to it) }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            for ((view, event) in queue) {
                val listener = listeners[view]
                when {
                    listener != null -> try {
                        listener(event)
                    } catch (e: Exception) {
                        System.err.println("webview-wpe: an engine's event failed: $e")
                    }
                    event is WpeEvent.Scheme -> WpeNative.finishScheme(event.request, 404, null, null)
                }
            }
        }
    }

    fun listen(view: Long, listener: (WpeEvent) -> Unit) {
        listeners[view] = listener
    }

    fun forget(view: Long) {
        listeners.remove(view)
    }
}
