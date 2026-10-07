package dev.supermux.net

import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class TerminalStatus { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * What happened to a byte the UI handed over. Every one of these is a REFUSAL
 * the caller can see: the client never takes bytes it is not going to send and
 * then drops them quietly, which is what an unbounded `trySend` whose result
 * nobody looked at did.
 */
enum class TerminalSendResult {
    ACCEPTED,
    /** No live socket, or the terminal is over. Editing should be disabled. */
    DISCONNECTED,
    /** The 64 KiB outbound budget is full — a paste larger than the queue, or a
     * client that has fallen far behind. Nothing was enqueued. */
    QUEUE_FULL,
    /** The screen is still being restored. Input is refused rather than queued:
     * the epoch it would belong to is not settled yet. */
    RESTORING,
    /** Replies only. This viewer does not own the size, so the daemon would
     * DROP the answer — and a dropped reply that we reported as sent is the
     * same lie as a delivery receipt for a letter we burned. */
    NOT_OWNER,
}

/** Why a terminal is over for good. Neither of these is a reconnect. */
sealed interface TerminalEnd {
    /** The program ended. See [TerminalEvent.Exit] for what `known` means. */
    data class Exited(val code: Int?, val signal: Int?, val known: Boolean) : TerminalEnd
    /** A non-recoverable failure: no such terminal, a protocol we cannot speak. */
    data class Failed(val code: String, val message: String) : TerminalEnd
}

/** Bytes the UI has 64 KiB of room for, in one FIFO so nothing reorders. */
private sealed interface Outbound {
    val size: Int
    class Input(val bytes: ByteArray) : Outbound { override val size get() = bytes.size }
    /** [epochBound] marks a frame that only means something on the screen it was
     * produced for — a reply. A resize or a focus claim is about the
     * CONNECTION and survives a new epoch. */
    class Control(val text: String, val epochBound: Boolean = false) : Outbound {
        override val size get() = text.length
    }
}

/** The outbound budget. A paste bigger than this is refused whole. */
private const val MAX_QUEUED_BYTES = 64 * 1024

/**
 * Build the /ws/term URL. Pure + testable. Mirrors the broker handler
 * (channels/web/index.ts): either `?session=` (session-scoped, incl. kind=agent)
 * or `?workspace=` (a plain shell in the workspace workdir). Exactly one.
 * `kind=agent` forces the singular agent terminal and ignores terminalId; scratch
 * may name a terminal (broker defaults to "main").
 *
 * A WORKSPACE terminal asks for `terminalProtocol=2` — the ordered revision the
 * broker's lane speaks. A session/agent terminal does not, and gets the legacy
 * framing until Plan 4 Tasks 3-5 retire it.
 *
 * [create] is the difference between "new terminal" and "reconnect", and it is
 * only ever sent on a revision-2 socket. `create=0` on a reconnect is what stops
 * a terminal whose shell exited from coming back alive and empty — the
 * `tmux new-session -A` behaviour the backend split apart.
 */
internal fun termWsUrl(
    baseUrl: String,
    sessionId: String,
    kind: String,
    terminalId: String?,
    workspaceId: String? = null,
    create: Boolean? = null,
): String {
    // Darwin's (iOS) WebSocket requires a ws/wss scheme; the broker base may be an
    // http(s) pair URL. Normalize exactly like BrokerClient before opening the socket.
    val wsBase = wsBaseUrl(baseUrl)
    if (workspaceId != null) {
        val base = StringBuilder("$wsBase/ws/term?workspace=${urlParam(workspaceId)}")
        if (terminalId != null) base.append("&terminal=${urlParam(terminalId)}")
        base.append("&terminalProtocol=$TERMINAL_PROTOCOL_VERSION")
        if (create != null) base.append("&create=").append(if (create) "1" else "0")
        return base.toString()
    }
    val base = "$wsBase/ws/term?session=${urlParam(sessionId)}"
    return when {
        kind == "agent" -> "$base&kind=agent"
        terminalId != null -> "$base&terminal=${urlParam(terminalId)}"
        else -> base
    }
}

private const val URL_HEX = "0123456789ABCDEF"

/**
 * RFC 3986 percent-encoding over UTF-8, so the broker recovers the value with
 * `URLSearchParams`. Keeps the unreserved set `A-Z a-z 0-9 - _ . ~`.
 *
 * These ids were interpolated RAW. Session names are free-form — "fix: the
 * thing" is a real one — so a name containing `&` or `#` did not merely produce
 * a wrong URL: `?session=a&kind=agent` from a value of `a&kind=agent` is a
 * parameter the client never meant to send, and `#` truncated the query
 * entirely. A space or a non-ASCII character makes the handshake line
 * malformed. Nothing about the fix is specific to a hostile id; an ordinary
 * Turkish session title is enough.
 */
private fun urlParam(value: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    val out = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        if (c < 0x80 && c.toChar() in unreserved) out.append(c.toChar())
        else {
            out.append('%')
            out.append(URL_HEX[c shr 4])
            out.append(URL_HEX[c and 0x0F])
        }
    }
    return out.toString()
}

internal fun terminalResizeFrame(cols: Int, rows: Int): String =
    encodeTerminalCommand(TerminalCommand.Resize(cols, rows))

internal fun terminalFocusFrame(focused: Boolean, cols: Int = 0, rows: Int = 0): String =
    if (focused && cols > 0 && rows > 0) {
        encodeTerminalCommand(TerminalCommand.Focus(true, cols, rows))
    } else {
        encodeTerminalCommand(TerminalCommand.Focus(false))
    }

private val legacyJson = Json { ignoreUnknownKeys = true }

/**
 * A terminal connection, as a state machine over the ordered event stream.
 *
 * WHAT CHANGED FROM REVISION 1. The old client matched control frames by
 * substring, so any text containing `"type":"exit"` — including a failure
 * message that merely mentioned exiting — closed the tab; it ignored the
 * broker's reset; and its outbound queue was unbounded, with the result of
 * `trySend` discarded, so a paste that did not fit was lost in silence.
 *
 * Now:
 *  - [events] is THE stream, in wire order, decoded by [TerminalEventDecoder].
 *  - only a real [TerminalEvent.Exit] ends the terminal; a recoverable failure
 *    reconnects, and a disconnect leaves the tab standing and shows [status].
 *  - the outbound queue has a 64 KiB budget and every refusal is returned.
 *  - unsent bytes are dropped when the epoch is replaced. Keystrokes we are not
 *    certain reached the shell are never replayed into a new screen.
 *
 * [output] and [exit] remain for the renderers Plan 4 Tasks 3-5 delete; they
 * are fed from the same loop, so they cannot disagree with [events] about order.
 */
class TerminalClient(
    private val baseUrl: String,   // e.g. ws://host:9898  (ws/wss base, same as BrokerClient)
    private val token: String,
    http: HttpClient,
    private val sessionId: String,
    private val kind: String = "scratch",      // "scratch" | "agent"
    private val terminalId: String? = null,    // scratch only; broker defaults to "main"
    /** When set, opens /ws/term?workspace=… instead of ?session=… (spec §7.3). */
    private val workspaceId: String? = null,
    /** True for a UI "new terminal": the FIRST attempt may create the target.
     * Every reconnect says `create=0` regardless — see [termWsUrl]. */
    private val create: Boolean? = null,
    private val transport: TerminalTransport = KtorTerminalTransport(http),
) {
    /** Revision 2 is the workspace wire; a session/agent socket is still legacy. */
    private val revision: Int = if (workspaceId != null) TERMINAL_PROTOCOL_VERSION else 1

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 256)
    /** The ordered stream. A slow collector becomes backpressure on the socket,
     * which is what keeps a control frame from overtaking the bytes in front of
     * it; with no collector at all, events are dropped rather than stalling. */
    val events: SharedFlow<TerminalEvent> = _events

    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val output: SharedFlow<ByteArray> = _output
    private val _status = MutableStateFlow(TerminalStatus.DISCONNECTED)
    val status: StateFlow<TerminalStatus> = _status
    private val _exit = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** Legacy: "this tab is over", for renderers that cannot tell why. */
    val exit: SharedFlow<Int> = _exit

    private val _ended = MutableStateFlow<TerminalEnd?>(null)
    /** Non-null once the terminal is over for good. Nothing reconnects after it. */
    val ended: StateFlow<TerminalEnd?> = _ended
    private val _restoring = MutableStateFlow(false)
    /** Between Ready/Reset and ReplayEnd: the screen on display is HISTORY. */
    val restoring: StateFlow<Boolean> = _restoring
    private val _epoch = MutableStateFlow<String?>(null)
    /** Changes whenever the screen is replaced — the signal to drop predictive
     * echo and any in-flight IME composition, which belong to a screen that is
     * gone. */
    val epoch: StateFlow<String?> = _epoch
    private val _replyOwner = MutableStateFlow(false)
    /** True while this viewer owns the size, and therefore while its emulator's
     * query answers are the ones the pty will accept. */
    val replyOwner: StateFlow<Boolean> = _replyOwner
    private val _inputEnabled = MutableStateFlow(false)
    /** What the UI binds "can I type / can I paste" to. */
    val inputEnabled: StateFlow<Boolean> = _inputEnabled

    @Volatile private var stopped = false
    @Volatile private var ownerGeneration = 0L
    @Volatile private var attemptedOnce = false
    /** The socket this connection is parked on, so [stop] can unblock it. */
    @Volatile private var liveSocket: TerminalSocket? = null

    // Pty input and control frames, drained FIFO by a single per-connection
    // sender so nothing reorders and a reply cannot overtake the typing in
    // front of it. Bounded by BYTES, not by item count: what matters is how
    // much unsent input we are holding, not how many keystrokes it took.
    private val outbound = Channel<Outbound>(Channel.UNLIMITED)
    private val budgetLock = SynchronizedObject()
    private var queuedBytes = 0

    // Last requested size, re-sent on (re)connect: the view often reports its size
    // before the socket is open, so that first resize would otherwise be dropped.
    @Volatile private var lastCols = 0
    @Volatile private var lastRows = 0
    @Volatile private var focused = false

    suspend fun run() {
        var attempt = 0
        while (!stopped) {
            try {
                _status.value = TerminalStatus.CONNECTING
                updateInputEnabled()
                transport.open(url(), token) { socket ->
                    attempt = 0
                    attemptedOnce = true
                    runConnection(socket)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Connect failed or the socket died mid-stream. NOT an exit:
                // "I cannot reach your program" is a reconnect.
            }
            endConnectionState()
            if (stopped) break
            val delayMs = listOf(500L, 1000L, 2000L, 4000L, 8000L)[minOf(attempt, 4)]
            attempt++
            delay(delayMs)
        }
        endConnectionState()
    }

    private fun url(): String = termWsUrl(
        baseUrl, sessionId, kind, terminalId, workspaceId,
        // Only the first attempt may create. A reconnect that creates is how a
        // dead shell came back alive and empty with its exit never reported.
        create = if (revision < 2) null else if (attemptedOnce) false else create,
    )

    /** One connection, start to finish. Returns when the socket is done. */
    private suspend fun runConnection(socket: TerminalSocket): Unit = coroutineScope {
        liveSocket = socket
        // A stop() that landed between `transport.open` and here read a null
        // socket and closed nothing, so close it ourselves. Both sides write
        // their own field before reading the other's, which is what makes the
        // window empty rather than merely small.
        if (stopped) {
            liveSocket = null
            socket.close()
            return@coroutineScope
        }
        // Whatever the previous connection did not manage to send belongs to a
        // screen that no longer exists.
        clearQueue(dropControl = true)
        _status.value = TerminalStatus.CONNECTED
        _restoring.value = revision >= 2
        updateInputEnabled()
        val sender = launch { senderLoop(socket) }
        try {
            // The geometry the view reported before the socket opened.
            if (lastCols > 0 && lastRows > 0) {
                enqueue(Outbound.Control(
                    if (focused) terminalFocusFrame(true, lastCols, lastRows)
                    else terminalResizeFrame(lastCols, lastRows),
                ))
            }
            receiveLoop(socket)
        } finally {
            liveSocket = null
            // Unblocks a sender parked on the queue or mid-write; coroutineScope
            // then joins it, so no connection leaves a coroutine behind.
            sender.cancel()
        }
    }

    private suspend fun receiveLoop(socket: TerminalSocket) {
        val decoder = TerminalEventDecoder()
        if (revision < 2) {
            // Legacy has no `ready`, but the stream shape is still the contract
            // above the socket, so it is synthesised here — with a version that
            // says which wire it came off.
            if (!apply(TerminalEvent.Ready(1, "legacy-${legacyEpoch++}", replyOwner = false, ownerGeneration = 0))) return
        }
        while (true) {
            val frame = socket.receive() ?: return
            val decoded = if (revision >= 2) {
                when (frame) {
                    is TerminalWireFrame.Binary -> decoder.onBinary(frame.bytes)
                    is TerminalWireFrame.Text -> decoder.onText(frame.text)
                }
            } else {
                when (frame) {
                    is TerminalWireFrame.Binary -> TerminalDecode.Deliver(TerminalEvent.Output(frame.bytes))
                    is TerminalWireFrame.Text -> decodeLegacy(frame.text)
                }
            }
            when (decoded) {
                // A frame we cannot place is dropped; a live shell is not ended
                // over one bad control object.
                is TerminalDecode.Ignore -> continue
                is TerminalDecode.Fatal -> {
                    finish(TerminalEnd.Failed(decoded.code, decoded.message))
                    apply(TerminalEvent.Failure(decoded.code, recoverable = false, message = decoded.message))
                    return
                }
                is TerminalDecode.Deliver -> {
                    if (!apply(decoded.event)) return
                    // Legacy's reset carries no replay boundary, and pretending
                    // it does is the honest minimum: an empty replay says "what
                    // follows is live", which is all revision 1 ever meant.
                    val event = decoded.event
                    if (revision < 2 && event is TerminalEvent.Reset) {
                        if (!apply(TerminalEvent.ReplayStart(event.epoch))) return
                        if (!apply(TerminalEvent.ReplayEnd(event.epoch))) return
                    }
                }
            }
        }
    }

    private var legacyEpoch = 0

    /** Revision 1's frames: `{"type":"reset"}`, `{"type":"exit","code":N}`,
     * `{"type":"error","reason":"…"}`. Parsed as JSON, never by substring. */
    private fun decodeLegacy(text: String): TerminalDecode {
        val obj = runCatching { legacyJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return TerminalDecode.Ignore("undecodable control frame")
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "reset" -> TerminalDecode.Deliver(TerminalEvent.Reset("legacy-${legacyEpoch++}"))
            "exit" -> {
                val code = obj["code"]?.jsonPrimitive?.intOrNull
                TerminalDecode.Deliver(TerminalEvent.Exit(code, signal = null, known = code != null))
            }
            "error" -> TerminalDecode.Deliver(TerminalEvent.Failure(
                "attach-failed",
                recoverable = false,
                message = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "terminal error",
            ))
            else -> TerminalDecode.Ignore("unknown legacy frame")
        }
    }

    /**
     * Apply one event and publish it. Returns false when this connection is
     * over — which is NOT the same as the terminal being over; see [ended].
     */
    private suspend fun apply(event: TerminalEvent): Boolean {
        var keepGoing = true
        when (event) {
            is TerminalEvent.Ready -> {
                _epoch.value = event.epoch
                _replyOwner.value = event.replyOwner
                ownerGeneration = event.ownerGeneration
            }
            is TerminalEvent.Reset -> {
                // A new screen. Anything still queued was typed against the old
                // one, and replaying it here would put it somewhere else.
                if (_epoch.value != event.epoch) clearQueue(dropControl = false)
                _epoch.value = event.epoch
                _restoring.value = true
            }
            is TerminalEvent.ReplayStart -> _restoring.value = true
            is TerminalEvent.ReplayEnd -> _restoring.value = false
            is TerminalEvent.Owner -> {
                _replyOwner.value = event.enabled
                ownerGeneration = event.ownerGeneration
            }
            is TerminalEvent.Output -> _output.emit(event.bytes)
            is TerminalEvent.Exit -> {
                finish(TerminalEnd.Exited(event.code, event.signal, event.known))
                keepGoing = false
            }
            is TerminalEvent.Failure -> {
                // Recoverable: drop the socket and reconnect, tab intact.
                if (!event.recoverable) finish(TerminalEnd.Failed(event.code, event.message))
                keepGoing = false
            }
        }
        updateInputEnabled()
        _events.emit(event)
        if (event is TerminalEvent.Exit) {
            _exit.emit(event.code ?: event.signal?.let { 128 + it } ?: 0)
        } else if (event is TerminalEvent.Failure && !event.recoverable) {
            // The legacy flow means "the tab is over", and it is.
            _exit.emit(0)
        }
        return keepGoing
    }

    private fun finish(end: TerminalEnd) {
        if (_ended.value == null) _ended.value = end
        stopped = true
        updateInputEnabled()
    }

    private fun endConnectionState() {
        _status.value = TerminalStatus.DISCONNECTED
        _restoring.value = false
        _replyOwner.value = false
        updateInputEnabled()
    }

    private fun updateInputEnabled() {
        _inputEnabled.value = _ended.value == null &&
            !stopped &&
            _status.value == TerminalStatus.CONNECTED &&
            !_restoring.value
    }

    // ---- outbound -----------------------------------------------------------

    private suspend fun senderLoop(socket: TerminalSocket) {
        while (true) {
            val item = outbound.receiveCatching().getOrNull() ?: return
            try {
                when (item) {
                    is Outbound.Input -> socket.sendBinary(item.bytes)
                    is Outbound.Control -> socket.sendText(item.text)
                }
            } catch (e: CancellationException) {
                release(item.size)
                throw e
            } catch (_: Throwable) {
                // The socket died under us. These bytes are NOT re-queued: we
                // do not know whether the shell saw them, and a keystroke we
                // are unsure about must not be replayed into the next screen.
                release(item.size)
                return
            }
            release(item.size)
        }
    }

    private fun enqueue(item: Outbound): TerminalSendResult {
        val size = item.size
        val reserved = synchronized(budgetLock) {
            if (queuedBytes + size > MAX_QUEUED_BYTES) {
                false
            } else {
                queuedBytes += size
                true
            }
        }
        if (!reserved) return TerminalSendResult.QUEUE_FULL
        if (!outbound.trySend(item).isSuccess) {
            // The client is stopped. Give the budget back and SAY so — this is
            // the failed trySend revision 1 discarded without a word.
            release(size)
            return TerminalSendResult.DISCONNECTED
        }
        return TerminalSendResult.ACCEPTED
    }

    private fun release(size: Int) {
        synchronized(budgetLock) { queuedBytes = maxOf(0, queuedBytes - size) }
    }

    /**
     * Drop what is queued.
     *
     * [dropControl] is the difference between a NEW CONNECTION — where the
     * geometry is re-sent from scratch a moment later, so keeping the old
     * frames would only duplicate them — and a NEW EPOCH inside a live
     * connection, where the pending resize still means what it said. Input and
     * replies go either way: typing we are unsure of is never replayed into a
     * screen it was not aimed at, and a reply is refused by the broker on the
     * epoch it carries.
     */
    private fun clearQueue(dropControl: Boolean) {
        val kept = mutableListOf<Outbound>()
        while (true) {
            val item = outbound.tryReceive().getOrNull() ?: break
            if (!dropControl && item is Outbound.Control && !item.epochBound) kept += item
            else release(item.size)
        }
        for (item in kept) {
            if (!outbound.trySend(item).isSuccess) release(item.size)
        }
    }

    /** Bytes still waiting to go out. Exposed for tests and diagnostics. */
    internal fun queuedByteCount(): Int = synchronized(budgetLock) { queuedBytes }

    /**
     * Enqueue user input (FIFO, non-suspending) so callers cannot reorder
     * keystrokes. Refused — with a reason — while disconnected, while the
     * screen is being restored, or when the 64 KiB budget is full.
     */
    fun sendInput(bytes: ByteArray): TerminalSendResult {
        if (bytes.isEmpty()) return TerminalSendResult.ACCEPTED
        if (stopped || _ended.value != null) return TerminalSendResult.DISCONNECTED
        if (_status.value != TerminalStatus.CONNECTED) return TerminalSendResult.DISCONNECTED
        if (_restoring.value) return TerminalSendResult.RESTORING
        return enqueue(Outbound.Input(bytes))
    }

    /**
     * A terminal REPLY this viewer's emulator produced (DA/DSR/kitty answers).
     *
     * A separate path from typing on purpose. Every viewer of one terminal
     * renders the same query and every one of them answers it, so anything
     * past the FIRST answer is read by the shell as typed input — which is why
     * the daemon takes an answer only from the size owner, and only once the
     * replay boundary has closed. Both rules are checked here so the caller is
     * told the truth instead of a delivery that never happened.
     */
    fun sendReply(bytes: ByteArray): TerminalSendResult {
        if (bytes.isEmpty()) return TerminalSendResult.ACCEPTED
        if (stopped || _ended.value != null) return TerminalSendResult.DISCONNECTED
        if (_status.value != TerminalStatus.CONNECTED) return TerminalSendResult.DISCONNECTED
        // An answer produced while history is being redrawn is an answer to a
        // question that was asked, and answered, minutes ago.
        if (_restoring.value) return TerminalSendResult.RESTORING
        // Revision 1 has no owner concept and no reply frame, so `replyOwner`
        // is never true there and every answer is refused — which is the right
        // answer for a wire on which two viewers would both have replied.
        if (!_replyOwner.value) return TerminalSendResult.NOT_OWNER
        val epoch = _epoch.value ?: return TerminalSendResult.DISCONNECTED
        return enqueue(Outbound.Control(
            encodeTerminalCommand(TerminalCommand.Reply(epoch, ownerGeneration, encodeReplyPayload(bytes))),
            epochBound = true,
        ))
    }

    suspend fun resize(cols: Int, rows: Int) {
        lastCols = cols
        lastRows = rows
        enqueue(Outbound.Control(
            if (focused) terminalFocusFrame(true, cols, rows) else terminalResizeFrame(cols, rows),
        ))
    }

    suspend fun focus(isFocused: Boolean) {
        focused = isFocused
        if (!isFocused || (lastCols > 0 && lastRows > 0)) {
            enqueue(Outbound.Control(terminalFocusFrame(isFocused, lastCols, lastRows)))
        }
    }

    /** Ask the broker to destroy the backing target, not just this viewer. */
    fun requestClose(): TerminalSendResult =
        enqueue(Outbound.Control(encodeTerminalCommand(TerminalCommand.Close)))

    /**
     * Stop for good, from any thread. Idempotent.
     *
     * This CLOSES THE SOCKET, and it has to: the client spends almost all of
     * its life suspended in `socket.receive()`, and a stop that only flipped a
     * flag left `run()` parked there until the peer happened to say something
     * — on a quiet shell, indefinitely. The doc here used to claim the loop
     * ended; callers who believed it leaked a coroutine per closed tab, and
     * the ones who did not believe it had to cancel the job as well, which
     * turns an ordinary close into a cancellation the client cannot tell from
     * its caller going away.
     *
     * After this returns, a suspended [receive] ends in null, the connection
     * unwinds normally, and [run] returns without throwing.
     */
    fun stop() {
        stopped = true
        updateInputEnabled()
        outbound.close()
        clearQueue(dropControl = true)
        liveSocket?.close()
    }
}
