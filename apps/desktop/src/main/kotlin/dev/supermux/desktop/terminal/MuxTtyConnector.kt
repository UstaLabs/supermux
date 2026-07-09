package dev.supermux.desktop.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

/**
 * Bridges the shared `TerminalClient` byte-stream to JediTerm's char-based [TtyConnector].
 *
 * Server output ([offerServerBytes]) and synthetic predictive-echo escapes ([injectDisplayBytes])
 * flow through the SAME FIFO queue, so the emulator renders them in the exact order they were
 * enqueued (no interleaving race). [read] blocks on that queue and streams the bytes through a
 * single stateful UTF-8 decoder, carrying any undecoded multi-byte tail across reads so a codepoint
 * split across two WebSocket frames still decodes to one char. User input flows the other way:
 * [write] taps [onUserInput] (the prediction pipeline's pre-send hook, Task 5) BEFORE handing the
 * bytes to [sendInput].
 */
class MuxTtyConnector(
    private val sendInput: (ByteArray) -> Unit,
    private val requestResize: (cols: Int, rows: Int) -> Unit,
    private val isConnected: () -> Boolean,
    private val name: String = "supermux",
) : TtyConnector {

    /** Pre-send tap for the predictive-echo pipeline (installed in Task 5); null = no prediction.
     *  @Volatile: installed from a coroutine after start, read in [write] on the UI/EDT thread. */
    @Volatile
    var onUserInput: ((ByteArray) -> Unit)? = null

    // FIFO of raw byte chunks (server output + injected prediction escapes, ordered). A unique
    // sentinel instance signals end-of-stream; identity comparison distinguishes it from a
    // legitimately-empty offered chunk.
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val poison = ByteArray(0)

    // Streaming UTF-8 decode: one stateful decoder + a carry of undecoded trailing bytes (an
    // incomplete multi-byte sequence at a chunk boundary) prepended to the next chunk.
    // ⚠️ SINGLE-READER CONTRACT: decoder/carry/pending are touched ONLY inside read()/decode(),
    // which JediTerm's TerminalStarter drives from ONE dedicated emulator thread. read() is NOT
    // reentrant or thread-safe — producers must use offerServerBytes/injectDisplayBytes only.
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry: ByteArray = ByteArray(0)
    // Decoded chars not yet handed to the caller (a decode produced more than the last read buffer
    // could hold); drained first on the next read.
    private var pending: CharBuffer? = null

    private val exitLatch = CountDownLatch(1)

    /** Enqueue raw server output. Ordered with [injectDisplayBytes]. */
    fun offerServerBytes(bytes: ByteArray) {
        queue.put(bytes)
    }

    /** Enqueue synthetic predictive-echo bytes into the same queue as server output (ordered). */
    fun injectDisplayBytes(bytes: ByteArray) {
        queue.put(bytes)
    }

    /** End the stream: unblocks [read] with -1 (sticky) and releases [waitFor]. */
    fun closeStream() {
        queue.put(poison)
        exitLatch.countDown()
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        // Serve any leftover decoded chars from a previous partial read first.
        pending?.let { p ->
            val n = minOf(length, p.remaining())
            p.get(buf, offset, n)
            if (!p.hasRemaining()) pending = null
            return n
        }
        // Block until a chunk yields at least one decoded char (or the stream closes).
        while (true) {
            val chunk = queue.take() // blocks; never busy-spins
            if (chunk === poison) {
                queue.put(poison) // keep EOF sticky for subsequent reads
                return -1
            }
            val decoded = decode(chunk)
            if (decoded.hasRemaining()) {
                val n = minOf(length, decoded.remaining())
                decoded.get(buf, offset, n)
                if (decoded.hasRemaining()) pending = decoded
                return n
            }
            // Chunk was only a partial multi-byte sequence → loop and block for the rest.
        }
    }

    /** Decode carry + [chunk] as far as possible; retain the incomplete tail as the new carry. */
    private fun decode(chunk: ByteArray): CharBuffer {
        val input = ByteBuffer.allocate(carry.size + chunk.size)
        input.put(carry).put(chunk).flip()
        val out = CharBuffer.allocate(input.remaining().coerceAtLeast(1)) // chars <= bytes
        decoder.decode(input, out, false)
        carry = ByteArray(input.remaining())
        input.get(carry)
        out.flip()
        return out
    }

    override fun write(bytes: ByteArray) {
        onUserInput?.invoke(bytes)
        sendInput(bytes)
    }

    override fun write(string: String) {
        write(string.toByteArray(StandardCharsets.UTF_8))
    }

    override fun resize(termSize: TermSize) {
        requestResize(termSize.columns, termSize.rows)
    }

    override fun isConnected(): Boolean = isConnected.invoke()

    override fun ready(): Boolean = pending?.hasRemaining() == true || queue.isNotEmpty()

    override fun waitFor(): Int {
        exitLatch.await()
        return 0
    }

    override fun getName(): String = name

    override fun close() {
        closeStream()
    }
}
