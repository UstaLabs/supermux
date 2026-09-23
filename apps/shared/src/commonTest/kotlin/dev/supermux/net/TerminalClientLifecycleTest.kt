package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The connection's LIFECYCLE: what survives a reconnect, what a full queue
 * does, who may answer a query, and whether closing leaves anything running.
 *
 * Driven through an injected [TerminalTransport] rather than a real socket —
 * Ktor's MockEngine cannot do WebSockets, and none of what is pinned here is
 * about bytes on a wire.
 */
private class FakeSocket : TerminalSocket {
    val inbound = Channel<TerminalWireFrame>(Channel.UNLIMITED)
    val binaries = mutableListOf<ByteArray>()
    val texts = mutableListOf<String>()
    /** When set, every send parks here — a socket that has stopped draining. */
    var sendGate: CompletableDeferred<Unit>? = null
    var closed = 0

    override suspend fun receive(): TerminalWireFrame? = inbound.receiveCatching().getOrNull()
    override suspend fun sendBinary(bytes: ByteArray) {
        sendGate?.await()
        binaries += bytes
    }
    override suspend fun sendText(text: String) {
        sendGate?.await()
        texts += text
    }

    fun push(text: String) { inbound.trySend(TerminalWireFrame.Text(text)) }
    fun pushBytes(bytes: ByteArray) { inbound.trySend(TerminalWireFrame.Binary(bytes)) }
    /** The peer went away. */
    fun drop() { inbound.close() }
}

private class FakeTransport : TerminalTransport {
    val sockets = mutableListOf<FakeSocket>()
    val urls = mutableListOf<String>()
    var released = 0

    override suspend fun open(url: String, token: String, session: suspend (TerminalSocket) -> Unit) {
        val socket = FakeSocket()
        sockets += socket
        urls += url
        try {
            session(socket)
        } finally {
            // Exactly what a real transport does when the session returns or is
            // cancelled: release the socket, once.
            socket.closed++
            released++
        }
    }
}

private const val READY = """{"type":"ready","version":2,"epoch":"c-1","replyOwner":false,"ownerGeneration":0}"""
private const val RESET = """{"type":"reset","epoch":"e-a"}"""
private const val REPLAY_START = """{"type":"replay-start","epoch":"e-a"}"""
private const val REPLAY_END = """{"type":"replay-end","epoch":"e-a"}"""
private const val OWNER_ON = """{"type":"owner","epoch":"e-a","enabled":true,"ownerGeneration":1}"""
private const val EXIT_3 = """{"type":"exit","known":true,"code":3,"signal":null}"""
private const val FAILURE_RECOVERABLE =
    """{"type":"failure","code":"backend-unavailable","recoverable":true,"message":"zmx helper exited before the attach completed"}"""
private const val FAILURE_FATAL = """{"type":"failure","code":"target-not-found","recoverable":false,"message":"no such terminal"}"""

private fun client(transport: FakeTransport) = TerminalClient(
    baseUrl = "ws://h:1",
    token = "t",
    http = HttpClient(MockEngine { respond("{}") }),
    sessionId = "",
    terminalId = "main",
    workspaceId = "w1",
    create = true,
    transport = transport,
)

/** Bring a connection all the way to "live": ready, a screen, replay closed. */
private fun FakeSocket.restore() {
    push(READY)
    push(RESET)
    push(REPLAY_START)
    push(REPLAY_END)
}

class TerminalClientLifecycleTest {

    @Test fun a_paste_larger_than_the_budget_is_refused_whole() = runTest {
        val transport = FakeTransport()
        val terminal = client(transport)
        val job = launch { terminal.run() }
        advanceUntilIdle()
        transport.sockets[0].restore()
        advanceUntilIdle()
        assertTrue(terminal.inputEnabled.value)

        assertEquals(TerminalSendResult.ACCEPTED, terminal.sendInput("ls\r".encodeToByteArray()))
        // Hold the socket so the queue can actually fill.
        transport.sockets[0].sendGate = CompletableDeferred()
        assertEquals(TerminalSendResult.ACCEPTED, terminal.sendInput(ByteArray(60 * 1024)))
        // Over the 64 KiB budget: refused, and NOT half-enqueued.
        val before = terminal.queuedByteCount()
        assertEquals(TerminalSendResult.QUEUE_FULL, terminal.sendInput(ByteArray(8 * 1024)))
        assertEquals(before, terminal.queuedByteCount())
        // A paste larger than the whole budget can never be accepted.
        assertEquals(TerminalSendResult.QUEUE_FULL, terminal.sendInput(ByteArray(64 * 1024 + 1)))

        terminal.stop()
        job.cancelAndJoin()
    }

    @Test fun input_is_refused_while_disconnected_and_while_the_screen_is_restoring() = runTest {
        val transport = FakeTransport()
        val terminal = client(transport)
        // Nothing is open yet.
        assertEquals(TerminalSendResult.DISCONNECTED, terminal.sendInput("a".encodeToByteArray()))
        val job = launch { terminal.run() }
        advanceUntilIdle()
        val socket = transport.sockets[0]
        socket.push(READY)
        socket.push(RESET)
        socket.push(REPLAY_START)
        advanceUntilIdle()
        // The screen on display is HISTORY; a keystroke aimed at it has no home.
        assertTrue(terminal.restoring.value)
        assertTrue(!terminal.inputEnabled.value)
        assertEquals(TerminalSendResult.RESTORING, terminal.sendInput("a".encodeToByteArray()))
        socket.push(REPLAY_END)
        advanceUntilIdle()
        assertEquals(TerminalSendResult.ACCEPTED, terminal.sendInput("a".encodeToByteArray()))

        terminal.stop()
        job.cancelAndJoin()
    }

    @Test fun input_that_did_not_reach_a_lost_socket_is_not_replayed_on_the_next_one() = runTest {
        val transport = FakeTransport()
        val terminal = client(transport)
        val job = launch { terminal.run() }
        advanceUntilIdle()
        val first = transport.sockets[0]
        first.restore()
        advanceUntilIdle()

        assertEquals(TerminalSendResult.ACCEPTED, terminal.sendInput("landed\r".encodeToByteArray()))
        advanceUntilIdle()
        assertEquals(listOf("landed\r"), first.binaries.map { it.decodeToString() })

        // From here the socket stops draining, and then dies with input still
        // in flight and more still queued.
        first.sendGate = CompletableDeferred()
        assertEquals(TerminalSendResult.ACCEPTED, terminal.sendInput("in-flight\r".encodeToByteArray()))
        advanceUntilIdle()
        assertEquals(TerminalSendResult.ACCEPTED, terminal.sendInput("still-queued\r".encodeToByteArray()))
        first.drop()
        advanceUntilIdle()

        val second = transport.sockets[1]
        second.restore()
        advanceUntilIdle()
        // Neither keystroke is re-sent: we do not know whether the shell saw
        // the first, and the second belongs to a screen that no longer exists.
        assertEquals(emptyList(), second.binaries.map { it.decodeToString() })
        assertEquals(0, terminal.queuedByteCount())
        // ...and the reconnect refuses to create, so a shell that exited while
        // we were away is reported, not silently replaced by a fresh one.
        assertTrue(transport.urls[0].endsWith("create=1"), transport.urls[0])
        assertTrue(transport.urls[1].endsWith("create=0"), transport.urls[1])

        terminal.stop()
        job.cancelAndJoin()
    }

    @Test fun a_query_is_answered_only_by_the_viewer_that_owns_the_size() = runTest {
        val ownerTransport = FakeTransport()
        val backgroundTransport = FakeTransport()
        val owner = client(ownerTransport)
        val background = client(backgroundTransport)
        val jobs = listOf(launch { owner.run() }, launch { background.run() })
        advanceUntilIdle()
        ownerTransport.sockets[0].restore()
        backgroundTransport.sockets[0].restore()
        advanceUntilIdle()

        // Both emulators are shown the same DSR query and both produce an
        // answer; only one of them owns the size.
        ownerTransport.sockets[0].push(OWNER_ON)
        advanceUntilIdle()
        val answer = "\u001b[24;1R".encodeToByteArray()
        assertEquals(TerminalSendResult.ACCEPTED, owner.sendReply(answer))
        // The background viewer is told the truth: the daemon would drop it.
        assertEquals(TerminalSendResult.NOT_OWNER, background.sendReply(answer))
        advanceUntilIdle()

        assertEquals(
            listOf("""{"type":"reply","epoch":"e-a","ownerGeneration":1,"data":"G1syNDsxUg=="}"""),
            ownerTransport.sockets[0].texts,
        )
        assertEquals(emptyList(), backgroundTransport.sockets[0].texts)
        // An answer is never typing: nothing went out as input.
        assertEquals(emptyList(), ownerTransport.sockets[0].binaries.map { it.decodeToString() })

        owner.stop(); background.stop()
        jobs.forEach { it.cancelAndJoin() }
    }

    @Test fun a_query_inside_the_replay_is_answered_by_nobody() = runTest {
        val transport = FakeTransport()
        val terminal = client(transport)
        val job = launch { terminal.run() }
        advanceUntilIdle()
        val socket = transport.sockets[0]
        socket.push(READY)
        socket.push(RESET)
        socket.push(REPLAY_START)
        socket.push(OWNER_ON)
        // The replay re-draws a query the program asked minutes ago, and this
        // viewer's emulator dutifully answers it. That answer is not an
        // answer — it is keystrokes.
        socket.pushBytes("\u001b[6n".encodeToByteArray())
        advanceUntilIdle()
        assertTrue(terminal.replyOwner.value)
        assertEquals(TerminalSendResult.RESTORING, terminal.sendReply("\u001b[24;1R".encodeToByteArray()))
        advanceUntilIdle()
        assertEquals(emptyList(), socket.texts)

        terminal.stop()
        job.cancelAndJoin()
    }

    @Test fun only_an_exit_ends_the_terminal_a_recoverable_failure_reconnects() = runTest {
        val transport = FakeTransport()
        val terminal = client(transport)
        val job = launch { terminal.run() }
        advanceUntilIdle()
        transport.sockets[0].restore()
        advanceUntilIdle()
        // "zmx helper exited …" — the word revision 1 matched on.
        transport.sockets[0].push(FAILURE_RECOVERABLE)
        advanceUntilIdle()
        assertEquals(null, terminal.ended.value)
        assertEquals(2, transport.sockets.size, "a recoverable failure reconnects")

        transport.sockets[1].restore()
        transport.sockets[1].push(EXIT_3)
        advanceUntilIdle()
        assertEquals(TerminalEnd.Exited(code = 3, signal = null, known = true), terminal.ended.value)
        assertEquals(2, transport.sockets.size, "an exit is never retried")
        assertEquals(TerminalStatus.DISCONNECTED, terminal.status.value)
        assertTrue(!terminal.inputEnabled.value)
        job.cancelAndJoin()
    }

    @Test fun a_non_recoverable_failure_stops_without_pretending_the_program_exited() = runTest {
        val transport = FakeTransport()
        val terminal = client(transport)
        val job = launch { terminal.run() }
        advanceUntilIdle()
        transport.sockets[0].push(FAILURE_FATAL)
        advanceUntilIdle()
        assertEquals(TerminalEnd.Failed("target-not-found", "no such terminal"), terminal.ended.value)
        assertEquals(1, transport.sockets.size)
        job.cancelAndJoin()
    }

    @Test fun close_unblocks_every_loop_and_releases_the_socket_exactly_once() = runTest {
        val transport = FakeTransport()
        val terminal = client(transport)
        val job = launch { terminal.run() }
        advanceUntilIdle()
        transport.sockets[0].restore()
        advanceUntilIdle()
        // A sender parked on a socket that stopped draining, with more behind it.
        transport.sockets[0].sendGate = CompletableDeferred()
        terminal.sendInput("a".encodeToByteArray())
        terminal.sendInput("b".encodeToByteArray())
        advanceUntilIdle()

        terminal.stop()
        transport.sockets[0].drop()
        advanceUntilIdle()
        assertTrue(job.isCompleted, "run() returns once the client is stopped")
        assertEquals(1, transport.released)
        assertEquals(1, transport.sockets[0].closed)
        // Idempotent: stopping twice does not release twice.
        terminal.stop()
        advanceUntilIdle()
        assertEquals(1, transport.released)
        assertEquals(TerminalSendResult.DISCONNECTED, terminal.sendInput("c".encodeToByteArray()))
    }
}
