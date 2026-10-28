package dev.supermux.webview.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

class BridgeException(message: String) : Exception(message)

/**
 * The host side of the page bridge. The page side is `window.hostBridge`, installed by [BridgeShim].
 *
 * Calls and posts need a live engine: [call] fails with [BridgeException] when there is none, and [post] is then a
 * no-op. Messages from origins the view doesn't accept, malformed messages and replies nobody waits for are dropped
 * and counted in [droppedMessages]. Events whose name starts with `$` belong to the library (console, size, saved
 * state) and never reach [on] listeners. Main thread only.
 */
class WebBridge internal constructor(
    private val onReservedEvent: (name: String, data: JsonElement) -> Unit,
) {
    private var sink: ((String) -> Unit)? = null
    private var originAllowed: (String) -> Boolean = { false }
    private var scope: CoroutineScope? = null
    private var nextId = 1L
    private val pending = HashMap<Long, CompletableDeferred<JsonElement>>()
    private val methods = HashMap<String, suspend (JsonElement) -> JsonElement>()
    private val listeners = HashMap<String, MutableList<(JsonElement) -> Unit>>()

    var droppedMessages: Int = 0
        private set

    val isConnected: Boolean get() = sink != null

    /** Calls the page's `hostBridge.handle(method, …)` handler and returns its result. */
    suspend fun call(method: String, params: JsonElement = JsonNull, timeoutMillis: Long = 10_000): JsonElement {
        val send = sink ?: throw BridgeException("bridge is not connected (the view is not live)")
        val id = nextId++
        val reply = CompletableDeferred<JsonElement>()
        pending[id] = reply
        try {
            send(BridgeCodec.encode(BridgeMessage.Call(id, method, params)))
            return withTimeout(timeoutMillis) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw BridgeException("call '$method' timed out after $timeoutMillis ms")
        } finally {
            pending.remove(id)
        }
    }

    /** Sends event [name] to the page's `hostBridge.on(name, …)` listeners. */
    fun post(name: String, data: JsonElement = JsonNull) {
        sink?.invoke(BridgeCodec.encode(BridgeMessage.Event(name, data)))
    }

    /** Listens for the page's `hostBridge.post(name, …)`. Returns the unsubscribe function. */
    fun on(name: String, listener: (JsonElement) -> Unit): () -> Unit {
        listeners.getOrPut(name) { mutableListOf() } += listener
        return { listeners[name]?.remove(listener) }
    }

    /** Answers the page's `hostBridge.call(method, …)`. */
    fun handle(method: String, handler: suspend (JsonElement) -> JsonElement) {
        methods[method] = handler
    }

    internal fun connect(sink: (String) -> Unit, originAllowed: (String) -> Boolean, scope: CoroutineScope) {
        this.sink = sink
        this.originAllowed = originAllowed
        this.scope = scope
    }

    internal fun disconnect() {
        sink = null
        scope = null
        originAllowed = { false }
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(BridgeException("bridge disconnected")) }
    }

    internal fun receive(origin: String, json: String) {
        if (!originAllowed(origin)) {
            droppedMessages++
            return
        }
        when (val message = BridgeCodec.decode(json)) {
            null -> droppedMessages++
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
                if (message.name.startsWith("$")) {
                    onReservedEvent(message.name, message.data)
                } else {
                    listeners[message.name]?.toList()?.forEach { listener -> runCatching { listener(message.data) } }
                }
            is BridgeMessage.Call -> answer(message)
        }
    }

    private fun answer(call: BridgeMessage.Call) {
        val scope = scope ?: return
        scope.launch {
            val handler = methods[call.method]
            val reply = if (handler == null) {
                BridgeMessage.Reply(call.id, error = "no such method: ${call.method}")
            } else {
                try {
                    BridgeMessage.Reply(call.id, result = handler(call.params))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BridgeMessage.Reply(call.id, error = e.message ?: e.toString())
                }
            }
            sink?.invoke(BridgeCodec.encode(reply))
        }
    }
}
