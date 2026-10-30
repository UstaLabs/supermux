package dev.supermux.webview.engine.linux

import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.webkit.RelayMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    /** Bounds the page messages in [queue]: everything else there is something WebKit or the engine waits on, or rare. */
    private val gate = RelayGate()

    @Volatile
    private var attached = false

    /** What the process's own events (view 0: a page's copy) go to, on the main thread. */
    @Volatile
    var processListener: ((WpeEvent) -> Unit)? = null

    @Synchronized
    fun attach() {
        if (attached) return
        attached = true
        WpeNative.sink = WpeNative.Sink { view, kind, a, b, s1, s2 ->
            WpeEvent.decode(kind, a, b, s1, s2)?.let { if (gate.admit(it)) queue.trySend(view to it) }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            for ((view, event) in queue) {
                gate.done(event)
                val listener = listeners[view]
                when {
                    listener != null -> try {
                        listener(event)
                    } catch (e: Exception) {
                        System.err.println("webview-wpe: an engine's event failed: $e")
                    }
                    event is WpeEvent.Scheme -> WpeNative.finishScheme(event.request, 404, null, null)
                    view == 0L -> processListener?.invoke(event)
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

/**
 * Bounds a page's messages between WebKit's thread and the main thread: past [limit] waiting, a page message is dropped,
 * as the engine's queue would under a flood. Never a `$ready` (the contract keeps them), nor the relay's own signals,
 * nor any other event: WebKit waits on its policy questions, scheme requests and dialogs, and the rest are rare.
 */
internal class RelayGate(private val limit: Int = 1024) {
    private val pending = AtomicInteger()

    /** Whether [event] goes to the main thread; any thread. */
    fun admit(event: WpeEvent): Boolean {
        if (!floodable(event)) return true
        if (pending.incrementAndGet() <= limit) return true
        pending.decrementAndGet()
        return false
    }

    /** The main thread took [event]. */
    fun done(event: WpeEvent) {
        if (floodable(event)) pending.decrementAndGet()
    }

    private fun floodable(event: WpeEvent): Boolean {
        val body = (event as? WpeEvent.Relay)?.body ?: return false
        val json = (RelayMessage.parse(body) as? RelayMessage.Page)?.json ?: return false
        return !(json.length <= WebBridge.READY_MAX_CHARS && json.startsWith(WebBridge.READY_PREFIX))
    }
}
