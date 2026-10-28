package dev.supermux.webview.bridge

import dev.supermux.webview.monotonicClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Limits that keep an untrusted page from overloading the host through the bridge (spec §9). */
data class BridgeLimits(
    val maxMessageChars: Int = 256 * 1024,
    val maxDepth: Int = 32,
    val messagesPerSecond: Int = 200,
    val burst: Int = 400,
    val maxPageCallsInFlight: Int = 16,
    val handlerTimeoutMillis: Long = 10_000,
    val maxQueuedPosts: Int = 64,
)

open class BridgeException(message: String) : Exception(message)

class BridgeTimeoutException(method: String, timeoutMillis: Long) :
    BridgeException("call '$method' timed out after $timeoutMillis ms")

/**
 * The host side of the page bridge. The page side is `window.hostBridge`, installed by [BridgeShim]. Main thread
 * only.
 *
 * The page is untrusted:
 * - Every inbound message is rate-, size- and depth-limited, strictly validated, and must come from the current
 *   document's allowed origin.
 * - Each document announces itself with a `$ready` nonce. A new one fails the old document's pending calls, and
 *   replies computed for an old document are never delivered.
 * - Before a document is ready, posts are queued (bounded) and calls wait within their timeout. A `$ready` from a
 *   disallowed origin stops all outbound traffic.
 * - Events named `$…` belong to the library and never reach [on] listeners. Unknown ones are dropped.
 * - Handler exceptions reach the page only as "handler failed", unless the handler throws a [BridgeException],
 *   whose message is meant for the page.
 *
 * Engines inject the shim into every top-level document and report each message's true origin. For a sandboxed
 * srcdoc frame, that means the content's local origin, after checking the message's source frame.
 */
class WebBridge internal constructor(
    private val onReservedEvent: (name: String, data: JsonElement) -> Unit,
    private val limits: BridgeLimits = BridgeLimits(),
    private val clock: () -> Long = monotonicClock(),
) {
    private class Document(val nonce: String, val origin: String)

    private class Connection(val scope: CoroutineScope, val sink: (String) -> Unit, val originAllowed: (String) -> Boolean) {
        var callsInFlight = 0
    }

    private var connection: Connection? = null
    private var document: Document? = null
    private var ready = CompletableDeferred<Unit>()
    private var generation = 0L
    private var nextId = 1L
    private val pending = HashMap<Long, CompletableDeferred<JsonElement>>()
    private val outbox = ArrayDeque<String>()
    private val methods = HashMap<String, suspend (JsonElement) -> JsonElement>()
    private val listeners = HashMap<String, MutableList<(JsonElement) -> Unit>>()
    private var tokens = limits.burst.toDouble()
    private var lastRefill = clock()

    /** Inbound messages dropped (rate, size, shape, origin, unknown reply or reserved name), plus queued posts dropped. */
    var droppedMessages: Long = 0
        private set

    val isConnected: Boolean get() = connection != null

    /** Whether a trusted document has announced itself on the other end. */
    val isReady: Boolean get() = document != null

    /** Calls the page's `hostBridge.handle(method, …)` handler and returns its result. */
    suspend fun call(method: String, params: JsonElement = JsonNull, timeoutMillis: Long = 10_000): JsonElement {
        if (connection == null) throw BridgeException("bridge is not connected (the view is not live)")
        return withTimeoutOrNull(timeoutMillis) {
            ready.await()
            val conn = connection ?: throw BridgeException("bridge disconnected")
            val id = nextId++
            val text = try {
                BridgeCodec.encode(BridgeMessage.Call(id, method, params))
            } catch (e: IllegalArgumentException) {
                throw BridgeException("params for '$method' are not strict JSON")
            }
            val reply = CompletableDeferred<JsonElement>()
            pending[id] = reply
            try {
                if (!send(conn, text)) throw BridgeException("the page could not be reached")
                reply.await()
            } finally {
                pending.remove(id)
            }
        } ?: throw BridgeTimeoutException(method, timeoutMillis)
    }

    /**
     * Sends event [name] to the page's `hostBridge.on(name, …)` listeners.
     * - Before the document is ready, the event is queued; beyond [BridgeLimits.maxQueuedPosts] the oldest is dropped.
     * - Returns false when it can't be delivered: not connected, or [data] isn't strict JSON.
     */
    fun post(name: String, data: JsonElement = JsonNull): Boolean {
        val conn = connection ?: return false
        val text = try {
            BridgeCodec.encode(BridgeMessage.Event(name, data))
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (document == null) {
            if (outbox.size >= limits.maxQueuedPosts) {
                outbox.removeFirst()
                droppedMessages++
            }
            outbox.addLast(text)
            return true
        }
        return send(conn, text)
    }

    /** Listens for the page's `hostBridge.post(name, …)`. Returns the unsubscribe function. */
    fun on(name: String, listener: (JsonElement) -> Unit): () -> Unit {
        listeners.getOrPut(name) { mutableListOf() } += listener
        return { listeners[name]?.remove(listener) }
    }

    /** Answers the page's `hostBridge.call(method, …)`. Returns the function that removes the handler. */
    fun handle(method: String, handler: suspend (JsonElement) -> JsonElement): () -> Unit {
        methods[method] = handler
        return { if (methods[method] === handler) methods.remove(method) }
    }

    internal fun connect(sink: (String) -> Unit, originAllowed: (String) -> Boolean, scope: CoroutineScope) {
        if (connection != null) disconnect()
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        connection = Connection(child, sink, originAllowed)
    }

    internal fun disconnect() {
        val conn = connection ?: return
        conn.scope.cancel()
        connection = null
        switchDocument(null, BridgeException("bridge disconnected"))
        ready.completeExceptionally(BridgeException("bridge disconnected"))
        ready = CompletableDeferred()
        outbox.clear()
    }

    internal fun receive(origin: String, json: String) {
        val conn = connection
        if (conn == null || !takeToken()) {
            droppedMessages++
            return
        }
        val message = BridgeCodec.decode(json, limits.maxMessageChars, limits.maxDepth)
        if (message == null) {
            droppedMessages++
            return
        }
        if (message is BridgeMessage.Event && message.name == READY) {
            onReady(conn, origin, message.data)
            return
        }
        if (!conn.originAllowed(origin) || document?.origin != origin) {
            droppedMessages++
            return
        }
        when (message) {
            is BridgeMessage.Reply -> {
                val waiting = pending.remove(message.id)
                if (waiting == null) {
                    droppedMessages++
                    return
                }
                val error = message.error
                if (error != null) waiting.completeExceptionally(BridgeException(error)) else waiting.complete(message.result ?: JsonNull)
            }
            is BridgeMessage.Event ->
                if (message.name.startsWith("$")) reserved(message.name, message.data) else dispatch(message.name, message.data)
            is BridgeMessage.Call -> answer(conn, message)
        }
    }

    private fun onReady(conn: Connection, origin: String, data: JsonElement) {
        val nonce = ((data as? JsonObject)?.get("nonce") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (nonce == null) {
            droppedMessages++
            return
        }
        if (!conn.originAllowed(origin)) {
            droppedMessages++
            if (document != null) switchDocument(null, BridgeException("the page navigated to an untrusted origin"))
            return
        }
        val current = document
        if (current != null && current.nonce == nonce && current.origin == origin) return
        switchDocument(Document(nonce, origin), BridgeException("the page navigated away"))
        while (outbox.isNotEmpty()) send(conn, outbox.removeFirst())
    }

    /** Another document, or none, is on the other end now: whatever the old one owed us fails. */
    private fun switchDocument(next: Document?, reason: BridgeException) {
        generation++
        document = next
        val owed = pending.values.toList()
        pending.clear()
        owed.forEach { it.completeExceptionally(reason) }
        if (next != null) {
            if (!ready.isCompleted) ready.complete(Unit)
        } else if (ready.isCompleted) {
            ready = CompletableDeferred()
        }
    }

    private fun reserved(name: String, data: JsonElement) {
        if (name !in RESERVED_EVENTS) {
            droppedMessages++
            return
        }
        try {
            onReservedEvent(name, data)
        } catch (e: Exception) {
            droppedMessages++
        }
    }

    private fun dispatch(name: String, data: JsonElement) {
        listeners[name]?.toList()?.forEach { listener ->
            try {
                listener(data)
            } catch (e: Exception) {
                // One failing app listener must not stop the others.
            }
        }
    }

    private fun answer(conn: Connection, call: BridgeMessage.Call) {
        if (conn.callsInFlight >= limits.maxPageCallsInFlight) {
            send(conn, BridgeCodec.encode(BridgeMessage.Reply(call.id, error = "busy")))
            return
        }
        conn.callsInFlight++
        val owner = generation
        conn.scope.launch {
            val reply = try {
                val handler = methods[call.method]
                if (handler == null || call.method.startsWith("$")) {
                    BridgeMessage.Reply(call.id, error = "no such method: ${call.method}")
                } else {
                    withTimeoutOrNull(limits.handlerTimeoutMillis) { handler(call.params) }
                        ?.let { BridgeMessage.Reply(call.id, result = it) }
                        ?: BridgeMessage.Reply(call.id, error = "timeout")
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                BridgeMessage.Reply(call.id, error = "cancelled")
            } catch (e: BridgeException) {
                BridgeMessage.Reply(call.id, error = e.message ?: "error")
            } catch (e: Exception) {
                BridgeMessage.Reply(call.id, error = "handler failed")
            } finally {
                conn.callsInFlight--
            }
            if (owner == generation && connection === conn) {
                val text = try {
                    BridgeCodec.encode(reply)
                } catch (e: IllegalArgumentException) {
                    BridgeCodec.encode(BridgeMessage.Reply(call.id, error = "result is not strict JSON"))
                }
                send(conn, text)
            }
        }
    }

    private fun send(conn: Connection, text: String): Boolean = try {
        conn.sink(text)
        true
    } catch (e: Exception) {
        false
    }

    private fun takeToken(): Boolean {
        val now = clock()
        tokens = minOf(limits.burst.toDouble(), tokens + (now - lastRefill) * limits.messagesPerSecond / 1000.0)
        lastRefill = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    internal companion object {
        const val READY: String = "\$ready"
        val RESERVED_EVENTS: Set<String> = setOf("\$console", "\$size", "\$saveStateProvider")
    }
}
