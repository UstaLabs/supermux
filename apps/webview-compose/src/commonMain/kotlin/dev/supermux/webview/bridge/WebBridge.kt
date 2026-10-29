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
 * The page's answer to [WebBridge.callForState].
 * @property result what the page's handler returned.
 * @property origin the origin of the document that answered, which is the document the call went to.
 */
internal data class PageAnswer(val result: JsonElement, val origin: String)

/**
 * The host side of the page bridge. The page side is `window.hostBridge`, installed by [BridgeShim]. Main thread
 * only; [call] hops to the connection's dispatcher itself.
 *
 * The page is untrusted:
 * - **Inbound checks.** Every message is charged per message and per character, size-capped (calls and events
 *   smaller than replies), depth-checked and strictly validated. It must come from the current document: from its
 *   allowed origin, and signed with its nonce (`from`).
 * - **Documents.** Main-frame commits ([documentCommitted]) close the gate when the origin changes, or when the
 *   engine can't read the URL (an opaque commit). Each document then announces itself with a `$ready` nonce, which
 *   has its own small budget so floods can't starve it.
 *   - A new document fails the old one's pending calls and never receives its stale replies.
 *   - Until a trusted document has announced itself, posts are queued (bounded), and calls wait within their timeout,
 *     pinned to the connection they were issued on.
 *   - A message signed by another document is dropped: a stale one from a document since replaced, or one from a
 *     document whose `$ready` was lost.
 *   - The host asks a page to announce itself with a data-free `$hello` (rate-limited), and the shim answers with its
 *     current nonce:
 *     - when an allowed page speaks while no document is current, or signs as another document of the current
 *       document's origin, the host asks that page. That recovers a `$ready` lost after a same-origin navigation,
 *       which leaves the gate open. If the message was only stale, the current document answers with the nonce it
 *       has, which changes nothing;
 *     - when no document is current and the host has something to send, it asks the origin of the last commit and the
 *       origin that last announced itself, each only if allowed.
 *   - The `$ready` budget refills when a commit's origin differs from the previous commit's, or is opaque (two opaque
 *     documents never share an origin), so a flood on one page can't silence the next. Same-document URL changes
 *     (pushState, fragments) keep the origin, so they never refill.
 * - **Delivery.** Every call, reply and event names the document it is for (`to`, its nonce) and goes out with that
 *   document's origin. The sink drops it if the main frame's document has another origin when it arrives; the shim
 *   drops it if it is for another document, even one of the same origin. A reply goes to the document that made the
 *   call.
 * - **Page state.** State belongs to the origin whose document saved it. [callForState] returns the page's answer
 *   with the origin of the document that gave it. [postPinned] sends an event (`$restoreState`) that reaches only a
 *   document of a given origin; otherwise it is dropped and counted.
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

    /** An event waiting for a document. It is addressed when it leaves, and only to a document of [onlyTo] if set. */
    private class QueuedPost(val event: BridgeMessage.Event, val onlyTo: String?)

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
    private val outbox = ArrayDeque<QueuedPost>()
    private val methods = HashMap<String, suspend (JsonElement) -> JsonElement>()
    private val listeners = HashMap<String, MutableList<(JsonElement) -> Unit>>()
    private val messageBudget = TokenBucket(limits.messagesPerSecond, limits.messageBurst, clock)
    private val charBudget = TokenBucket(limits.charsPerSecond, limits.charBurst, clock)
    private val readyBudget = TokenBucket(limits.readiesPerSecond, limits.readyBurst, clock)
    private val helloBudget = TokenBucket(limits.hellosPerSecond, limits.helloBurst, clock)

    /** The origin of the last document that announced itself, for [askToAnnounce]. */
    private var lastOrigin: String? = null

    /**
     * The origin of the last main-frame commit ([OPAQUE] when the engine couldn't read its URL), for the `$ready`
     * refill and [askToAnnounce]. Null when none was reported since [connect].
     */
    private var lastCommittedOrigin: String? = null

    /**
     * Inbound messages dropped (budget, size, shape, origin, sender, unknown reply or reserved name), plus posts
     * dropped: the oldest queued ones beyond the limit, and pinned ones that met a document of another origin.
     */
    var droppedMessages: Long = 0
        private set

    /** Sees exceptions from page-call handlers and [on] listeners, which the page itself never sees. */
    var onError: ((Throwable) -> Unit)? = null

    val isConnected: Boolean get() = connection != null

    /** Whether a trusted document has announced itself on the other end. */
    val isReady: Boolean get() = document != null

    /**
     * The origin of the current document (the trusted one that announced itself and hasn't navigated away), or null.
     * Not for page state: read after a call, it may already name another document, because events processed before
     * the caller resumed can switch documents. [callForState] returns the origin that answered.
     */
    internal val documentOrigin: String? get() = document?.origin

    /** Calls the page's `hostBridge.handle(method, …)` handler and returns its result. */
    suspend fun call(method: String, params: JsonElement = JsonNull, timeoutMillis: Long = 10_000): JsonElement =
        request(method, params, timeoutMillis).result

    /**
     * [call] for page state (`$saveState`): returns the page's answer with the origin of the document that gave it,
     * so the state can be pinned to that origin ([postPinned]). That is the document the call went to, since a new
     * document fails every call pending on the old one.
     */
    internal suspend fun callForState(method: String, params: JsonElement = JsonNull, timeoutMillis: Long = 10_000): PageAnswer =
        request(method, params, timeoutMillis)

    private suspend fun request(method: String, params: JsonElement, timeoutMillis: Long): PageAnswer {
        // Before waiting for a document: params that can't be sent would wait out the timeout, asking for one meanwhile.
        if (!params.isStrictJson()) throw BridgeException("params for '$method' are not strict JSON")
        val started = connection ?: throw BridgeException("bridge is not connected (the view is not live)")
        val dispatcher = started.scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
        return withContext(dispatcher) {
            withTimeoutOrNull(timeoutMillis) {
                val target = awaitDocument(started)
                val id = nextId++
                val text = BridgeCodec.encode(BridgeMessage.Call(id, method, params), to = target.nonce)
                val reply = CompletableDeferred<JsonElement>()
                pending[id] = reply
                try {
                    if (!send(started, text, target.origin)) throw BridgeException("the page could not be reached")
                    // A reply resolves this only while target is current: switching documents fails pending calls.
                    PageAnswer(reply.await(), target.origin)
                } finally {
                    pending.remove(id)
                }
            } ?: throw BridgeTimeoutException(method, timeoutMillis)
        }
    }

    /**
     * Waits until a trusted document is current on [conn], asking a quiet one to announce itself, and returns it. Fails
     * at once if [conn] is no longer current, which happens when it dropped while the call hopped dispatchers.
     */
    private suspend fun awaitDocument(conn: Connection): Document {
        if (connection !== conn) throw BridgeException("bridge disconnected")
        if (document == null) askToAnnounce(conn)
        while (true) {
            ready.await()
            if (connection !== conn) throw BridgeException("bridge disconnected")
            document?.let { return it }
            yield()
        }
    }

    /**
     * Sends event [name] to the page's `hostBridge.on(name, …)` listeners.
     * - Before a trusted document is ready, the event is queued; beyond [BridgeLimits.maxQueuedPosts] the oldest is
     *   dropped.
     * - Returns false when it can't be delivered: not connected, or [data] isn't strict JSON.
     */
    fun post(name: String, data: JsonElement = JsonNull): Boolean = postEvent(BridgeMessage.Event(name, data), onlyTo = null)

    /**
     * [post], pinned to [origin]: the event reaches only a document of that origin, as state saved there must
     * (`$restoreState`). A caller that doesn't know the state's origin doesn't send it.
     * - Queued, it is dropped and counted if a document of another origin announces itself first.
     * - Sent at once, it is dropped and counted, and returns false, when the current document has another origin.
     */
    internal fun postPinned(name: String, data: JsonElement, origin: String): Boolean =
        postEvent(BridgeMessage.Event(name, data), onlyTo = origin)

    /** [post], reaching only a document of origin [onlyTo] when that is set. */
    private fun postEvent(event: BridgeMessage.Event, onlyTo: String?): Boolean {
        val conn = connection ?: return false
        val current = document
        // Encoding now validates the data, so the caller learns at once. A queued post is encoded again, addressed, when
        // it leaves.
        val text = encodeOrNull(event, to = current?.nonce) ?: return false
        if (current == null) {
            if (outbox.size >= limits.maxQueuedPosts) {
                outbox.removeFirst()
                droppedMessages++
            }
            outbox.addLast(QueuedPost(event, onlyTo))
            askToAnnounce(conn)
            return true
        }
        if (onlyTo != null && onlyTo != current.origin) {
            droppedMessages++
            return false
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
        lastCommittedOrigin = null
        readyBudget.refill()
        helloBudget.refill()
    }

    internal fun disconnect() {
        val conn = connection ?: return
        conn.scope.cancel()
        connection = null
        lastOrigin = null
        lastCommittedOrigin = null
        switchDocument(null, BridgeException("bridge disconnected"))
        ready.completeExceptionally(BridgeException("bridge disconnected"))
        ready = CompletableDeferred()
        outbox.clear()
    }

    /**
     * The engine committed a main-frame navigation to [url], reported in the same terms as message origins (a
     * sandboxed srcdoc document commits as its content's local origin URL). Null is a commit whose URL the engine
     * can't read: it is opaque.
     * - A commit that leaves the current document's origin, or is opaque, closes the gate: nothing more goes out until
     *   the new document announces itself, so error pages and pages without the shim can't receive traffic.
     * - A commit whose origin differs from the previous commit's, or is opaque, refills the `$ready` budget, whatever
     *   the gate's state, so a flood on one page can't silence the next. Same-document URL changes (pushState,
     *   fragments) keep the origin, so they never refill it.
     */
    internal fun documentCommitted(url: String?) {
        val origin = url?.let(::originOf) ?: OPAQUE
        val current = document
        // Two opaque origins are never the same origin, so an opaque commit is always a new origin, for the refill as
        // for the gate. With no commit reported since connect, the current document's origin is the last one known to
        // have committed.
        if (origin == OPAQUE || origin != (lastCommittedOrigin ?: current?.origin)) readyBudget.refill()
        lastCommittedOrigin = origin
        if (current != null && (origin == OPAQUE || origin != current.origin)) {
            switchDocument(null, BridgeException("the page navigated away"))
        }
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
        val envelope = BridgeCodec.decodeEnvelope(json, limits.maxReplyChars, limits.maxDepth)
        if (envelope == null) {
            droppedMessages++
            return
        }
        val message = envelope.message
        if (message is BridgeMessage.Event && message.name == READY ||
            message !is BridgeMessage.Reply && json.length > limits.maxMessageChars
        ) {
            droppedMessages++
            return
        }
        val allowed = conn.originAllowed(origin)
        val current = document
        if (!allowed || current == null || current.origin != origin || envelope.from != current.nonce) {
            droppedMessages++
            // An allowed page that speaks while no document is current, or signs as another document of the current
            // one's origin (a same-origin navigation leaves the gate open), may have lost its $ready: ask it to announce
            // itself. If the message was only stale, the current document answers with the nonce it has, which changes
            // nothing.
            if (allowed && (current == null || current.origin == origin)) askToAnnounce(conn, origin)
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
            is BridgeMessage.Call -> answer(conn, message, current)
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
        while (outbox.isNotEmpty()) {
            val post = outbox.removeFirst()
            val text = if (post.onlyTo == null || post.onlyTo == origin) encodeOrNull(post.event, to = nonce) else null
            if (text == null) droppedMessages++ else send(conn, text, origin)
        }
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
     * Asks the page of origin [target] to announce itself again, with a constant `$hello` that carries no data and no
     * address: it may still be there with a lost or stale `$ready`. Rate-limited.
     */
    private fun askToAnnounce(conn: Connection, target: String) {
        if (helloBudget.take(1)) send(conn, HELLO, target)
    }

    /**
     * No document is current and the host has something to send. Ask the origin of the last commit and the origin
     * that last announced itself: each only if allowed, once, and each for a `$hello` token.
     */
    private fun askToAnnounce(conn: Connection) {
        for (target in listOfNotNull(lastCommittedOrigin, lastOrigin).distinct()) {
            if (conn.originAllowed(target)) askToAnnounce(conn, target)
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

    /**
     * Answers [caller]'s [call]. Every reply, "busy" included, is addressed to [caller]. One computed after another
     * document became current is never sent.
     */
    private fun answer(conn: Connection, call: BridgeMessage.Call, caller: Document) {
        if (conn.callsInFlight >= limits.maxPageCallsInFlight) {
            send(conn, BridgeCodec.encode(BridgeMessage.Reply(call.id, error = "busy"), to = caller.nonce), caller.origin)
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
                val text = encodeOrNull(reply, to = caller.nonce)
                    ?: BridgeCodec.encode(BridgeMessage.Reply(call.id, error = "result is not strict JSON"), to = caller.nonce)
                send(conn, text, caller.origin)
            }
        }
    }

    /** [message] encoded and addressed [to] a document, or null when its payload isn't strict JSON. */
    private fun encodeOrNull(message: BridgeMessage, to: String?): String? = try {
        BridgeCodec.encode(message, to)
    } catch (e: IllegalArgumentException) {
        null
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

        /**
         * Asks the page to announce itself again. Constant, carrying no data and no address, so any document may
         * receive it.
         */
        val HELLO: String = BridgeCodec.encode(BridgeMessage.Event("\$hello"))

        /** The origin of an opaque document, as [originOf] serializes it. */
        const val OPAQUE: String = "null"

        val RESERVED_EVENTS: Set<String> = setOf("\$console", "\$size", "\$saveStateProvider")
    }
}
