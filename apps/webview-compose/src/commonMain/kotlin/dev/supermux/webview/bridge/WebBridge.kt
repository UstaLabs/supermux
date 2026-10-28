package dev.supermux.webview.bridge

import dev.supermux.webview.monotonicClock
import dev.supermux.webview.originOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/** Limits that keep an untrusted page from overloading the host through the bridge (spec §9). */
internal data class BridgeLimits(
    val maxMessageChars: Int = BridgeCodec.DEFAULT_MAX_MESSAGE_CHARS,
    val maxReplyChars: Int = BridgeCodec.DEFAULT_MAX_REPLY_CHARS,
    val maxDepth: Int = BridgeCodec.DEFAULT_MAX_DEPTH,
    val messagesPerSecond: Int = 200,
    val messageBurst: Int = 400,
    val charsPerSecond: Int = 512 * 1024,
    val charBurst: Int = 1024 * 1024,
    val readiesPerSecond: Int = 5,
    val readyBurst: Int = 20,
    val hellosPerSecond: Int = 2,
    val helloBurst: Int = 2,
    val maxPageCallsInFlight: Int = 16,
    val handlerTimeoutMillis: Long = 10_000,
    val maxQueuedPosts: Int = 64,
)

open class BridgeException(message: String) : Exception(message)

class BridgeTimeoutException(method: String, timeoutMillis: Long) :
    BridgeException("call '$method' timed out after $timeoutMillis ms")

/**
 * The host side of the page bridge. The page side is `window.hostBridge`, installed by [BridgeShim]. Main thread
 * only; [call] hops to the connection's dispatcher itself.
 *
 * The page is untrusted:
 * - **Inbound checks.** Every message is charged per message and per character, size-capped (calls and events
 *   smaller than replies), depth-checked and strictly validated. It must come from the current document's allowed
 *   origin.
 * - **Documents.** Main-frame commits ([documentCommitted]) close the gate when the origin changes. Each document
 *   then announces itself with a `$ready` nonce, which has its own small budget so floods can't starve it.
 *   - A new document fails the old one's pending calls and never receives its stale replies.
 *   - Until a trusted document has announced itself, posts are queued (bounded), and calls wait within their timeout,
 *     pinned to the connection they were issued on.
 *   - If no document is current and an allowed page speaks, or the host has something to send, the host sends a
 *     data-free `$hello` (rate-limited). The shim answers with its current nonce.
 * - **Delivery.** Every message goes out with the origin it's for. The sink drops it if the main frame's document has
 *   another origin when it arrives.
 * - **Reserved events.** Events named `$…` belong to the library and never reach [on] listeners; unknown ones are
 *   dropped.
 * - **Handler errors.** The page gets only "handler failed", unless the handler throws a [BridgeException] meant for
 *   the page. [onError] sees the real exception.
 *
 * Engines report main-frame commits as [dev.supermux.webview.engine.EngineEvent.UrlChanged], inject the shim into
 * the main frame of documents whose origin may talk to the host, and report each message's true origin. For a
 * sandboxed srcdoc frame, that is the content's local origin, after checking the message's source frame.
 *
 * [connect] parents a never-completing child job to the given scope until [disconnect]. A scope that joins its
 * children (`coroutineScope`, `runTest`) must disconnect first.
 */
class WebBridge internal constructor(
    private val onReservedEvent: (name: String, data: JsonElement) -> Unit,
    private val limits: BridgeLimits = BridgeLimits(),
    clock: () -> Long = monotonicClock(),
) {
    private class Document(val nonce: String, val origin: String)

    private class Connection(
        val scope: CoroutineScope,
        val sink: (text: String, targetOrigin: String) -> Unit,
        val originAllowed: (String) -> Boolean,
    ) {
        var callsInFlight = 0
    }

    private class TokenBucket(private val perSecond: Int, private val burst: Int, private val clock: () -> Long) {
        private var tokens = burst.toDouble()
        private var last = clock()

        fun take(cost: Int): Boolean {
            val now = clock()
            tokens = minOf(burst.toDouble(), tokens + (now - last) * perSecond / 1000.0)
            last = now
            if (tokens < cost) return false
            tokens -= cost
            return true
        }

        fun refill() {
            tokens = burst.toDouble()
        }
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
    private val messageBudget = TokenBucket(limits.messagesPerSecond, limits.messageBurst, clock)
    private val charBudget = TokenBucket(limits.charsPerSecond, limits.charBurst, clock)
    private val readyBudget = TokenBucket(limits.readiesPerSecond, limits.readyBurst, clock)
    private val helloBudget = TokenBucket(limits.hellosPerSecond, limits.helloBurst, clock)

    /** The origin of the last document that announced itself, for [askToAnnounce]. */
    private var lastOrigin: String? = null

    /** Inbound messages dropped (budget, size, shape, origin, unknown reply or reserved name), plus queued posts dropped. */
    var droppedMessages: Long = 0
        private set

    /** Sees exceptions from page-call handlers and [on] listeners, which the page itself never sees. */
    var onError: ((Throwable) -> Unit)? = null

    val isConnected: Boolean get() = connection != null

    /** Whether a trusted document has announced itself on the other end. */
    val isReady: Boolean get() = document != null

    /** Calls the page's `hostBridge.handle(method, …)` handler and returns its result. */
    suspend fun call(method: String, params: JsonElement = JsonNull, timeoutMillis: Long = 10_000): JsonElement {
        val started = connection ?: throw BridgeException("bridge is not connected (the view is not live)")
        val dispatcher = started.scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
        return withContext(dispatcher) {
            withTimeoutOrNull(timeoutMillis) {
                val target = awaitDocument(started)
                val id = nextId++
                val text = try {
                    BridgeCodec.encode(BridgeMessage.Call(id, method, params))
                } catch (e: IllegalArgumentException) {
                    throw BridgeException("params for '$method' are not strict JSON")
                }
                val reply = CompletableDeferred<JsonElement>()
                pending[id] = reply
                try {
                    if (!send(started, text, target)) throw BridgeException("the page could not be reached")
                    reply.await()
                } finally {
                    pending.remove(id)
                }
            } ?: throw BridgeTimeoutException(method, timeoutMillis)
        }
    }

    /** Waits until a trusted document is current on [conn], asking a quiet one to announce itself, and returns its origin. */
    private suspend fun awaitDocument(conn: Connection): String {
        if (document == null) askToAnnounce(conn)
        while (true) {
            ready.await()
            if (connection !== conn) throw BridgeException("bridge disconnected")
            document?.let { return it.origin }
            yield()
        }
    }

    /**
     * Sends event [name] to the page's `hostBridge.on(name, …)` listeners.
     * - Before a trusted document is ready, the event is queued; beyond [BridgeLimits.maxQueuedPosts] the oldest is
     *   dropped.
     * - Returns false when it can't be delivered: not connected, or [data] isn't strict JSON.
     */
    fun post(name: String, data: JsonElement = JsonNull): Boolean {
        val conn = connection ?: return false
        val text = try {
            BridgeCodec.encode(BridgeMessage.Event(name, data))
        } catch (e: IllegalArgumentException) {
            return false
        }
        val current = document
        if (current == null) {
            if (outbox.size >= limits.maxQueuedPosts) {
                outbox.removeFirst()
                droppedMessages++
            }
            outbox.addLast(text)
            askToAnnounce(conn)
            return true
        }
        return send(conn, text, current.origin)
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

    /**
     * Connects to a live engine's page. [sink] delivers to the page only if the main frame's document has origin
     * `targetOrigin` when the message arrives (see [dev.supermux.webview.engine.WebEngine.postMessage]).
     */
    internal fun connect(
        sink: (text: String, targetOrigin: String) -> Unit,
        originAllowed: (String) -> Boolean,
        scope: CoroutineScope,
    ) {
        if (connection != null) disconnect()
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        connection = Connection(child, sink, originAllowed)
        lastOrigin = null
        readyBudget.refill()
        helloBudget.refill()
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

    /**
     * The engine committed a main-frame navigation to [url], reported in the same terms as message origins (a
     * sandboxed srcdoc document commits as its content's local origin URL).
     * - If it leaves the current document's origin, nothing more goes out until the new document announces itself,
     *   so error pages and pages without the shim can't receive traffic.
     * - A commit that leaves the origin, or comes with no document current, refills the `$ready` budget, so a flood on
     *   one page can't silence the next.
     */
    internal fun documentCommitted(url: String) {
        val origin = originOf(url)
        val current = document
        if (current == null || current.origin != origin) readyBudget.refill()
        if (current != null && current.origin != origin) switchDocument(null, BridgeException("the page navigated away"))
    }

    internal fun receive(origin: String, json: String) {
        val conn = connection
        if (conn == null || json.length > limits.maxReplyChars) {
            droppedMessages++
            return
        }
        if (json.length <= READY_MAX_CHARS && json.startsWith(READY_PREFIX)) {
            if (!readyBudget.take(1)) {
                droppedMessages++
                return
            }
            val message = BridgeCodec.decode(json, limits.maxReplyChars, limits.maxDepth)
            if (message is BridgeMessage.Event && message.name == READY) onReady(conn, origin, message.data) else droppedMessages++
            return
        }
        if (!charBudget.take(json.length) || !messageBudget.take(1)) {
            droppedMessages++
            return
        }
        val message = BridgeCodec.decode(json, limits.maxReplyChars, limits.maxDepth)
        if (message == null || message is BridgeMessage.Event && message.name == READY ||
            message !is BridgeMessage.Reply && json.length > limits.maxMessageChars
        ) {
            droppedMessages++
            return
        }
        val allowed = conn.originAllowed(origin)
        if (!allowed || document?.origin != origin) {
            droppedMessages++
            // A trusted page speaking while no document is current lost its $ready (or it came stale): ask again.
            if (allowed && document == null) askToAnnounce(conn, origin)
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
            is BridgeMessage.Call -> answer(conn, message, origin)
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
        switchDocument(Document(nonce, origin), BridgeException("the page navigated away"), completeReady = false)
        lastOrigin = origin
        while (outbox.isNotEmpty()) send(conn, outbox.removeFirst(), origin)
        if (!ready.isCompleted) ready.complete(Unit)
    }

    /** Another document, or none, is on the other end now: whatever the old one owed us fails. */
    private fun switchDocument(next: Document?, reason: BridgeException, completeReady: Boolean = true) {
        generation++
        document = next
        val owed = pending.values.toList()
        pending.clear()
        owed.forEach { it.completeExceptionally(reason) }
        if (next != null) {
            if (completeReady && !ready.isCompleted) ready.complete(Unit)
        } else if (ready.isCompleted) {
            ready = CompletableDeferred()
        }
    }

    /**
     * No document is current, but [target] (the page that spoke, or the last one that announced) may still be there
     * with a lost `$ready`. Ask it to announce itself again with a constant `$hello`, which carries no data.
     */
    private fun askToAnnounce(conn: Connection, target: String? = lastOrigin) {
        if (target != null && helloBudget.take(1)) send(conn, HELLO, target)
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
            report(e)
        }
    }

    private fun dispatch(name: String, data: JsonElement) {
        listeners[name]?.toList()?.forEach { listener ->
            try {
                listener(data)
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    private fun answer(conn: Connection, call: BridgeMessage.Call, origin: String) {
        if (conn.callsInFlight >= limits.maxPageCallsInFlight) {
            send(conn, BridgeCodec.encode(BridgeMessage.Reply(call.id, error = "busy")), origin)
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
            } catch (e: Throwable) {
                report(e)
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
                send(conn, text, origin)
            }
        }
    }

    private fun send(conn: Connection, text: String, targetOrigin: String): Boolean = try {
        conn.sink(text, targetOrigin)
        true
    } catch (e: Exception) {
        report(e)
        false
    }

    private fun report(e: Throwable) {
        try {
            onError?.invoke(e)
        } catch (ignored: Exception) {
            // An error hook that fails has nowhere left to report to.
        }
    }

    internal companion object {
        const val READY: String = "\$ready"

        /** How the shim serializes a `$ready` (key order is insertion order), so it can skip the general budget. */
        const val READY_PREFIX: String = "{\"t\":\"event\",\"n\":\"\$ready\""

        const val READY_MAX_CHARS: Int = 256

        /** Asks the page to announce itself again. Constant, carrying no data, so any document may receive it. */
        val HELLO: String = BridgeCodec.encode(BridgeMessage.Event("\$hello"))

        val RESERVED_EVENTS: Set<String> = setOf("\$console", "\$size", "\$saveStateProvider")
    }
}
