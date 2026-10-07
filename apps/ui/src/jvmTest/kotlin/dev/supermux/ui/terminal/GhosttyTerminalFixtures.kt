package dev.supermux.ui.terminal

import dev.supermux.terminal.CellFlags
import dev.supermux.terminal.CellStyle
import dev.supermux.terminal.Modifiers
import dev.supermux.terminal.OutputOrigin
import dev.supermux.terminal.TerminalCell
import dev.supermux.terminal.TerminalColors
import dev.supermux.terminal.TerminalCursor
import dev.supermux.terminal.TerminalEffect
import dev.supermux.terminal.TerminalEngine
import dev.supermux.terminal.TerminalKey
import dev.supermux.terminal.TerminalKeys
import dev.supermux.terminal.TerminalModes
import dev.supermux.terminal.TerminalMouse
import dev.supermux.terminal.TerminalRow
import dev.supermux.terminal.TerminalSelection
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.TerminalViewport
import dev.supermux.net.TerminalSocket
import dev.supermux.net.TerminalTransport
import dev.supermux.net.TerminalWireFrame
import kotlinx.coroutines.channels.Channel

/**
 * A terminal engine small enough to reason about and real enough to hold the properties the
 * surface is supposed to preserve.
 *
 * It is NOT a spy: it keeps a screen, and the tests read that screen. "Hide and show retains
 * history" is only worth asserting if there is a history to lose, and "the echo confirms without
 * doubling" is only worth asserting if the confirmed glyph is genuinely on the authoritative
 * screen rather than in a list of calls.
 *
 * It also ENCODES, because that is the whole point of the change under test: the accessory bar no
 * longer builds bytes, so the only bytes in the system come out of here, and a test can count
 * them.
 */
class FakeTerminalEngine(var size: TerminalSize) : TerminalEngine {
    private val screen = StringBuilder()
    private val effects = mutableListOf<TerminalEffect>()
    private var generation = 0L
    private var sequence = 0L
    private var closed = false

    /** Every feed, with the origin it carried. The adapter's ONE feed lane, recorded. */
    val feeds = mutableListOf<Pair<String, OutputOrigin>>()
    var resets = 0
        private set
    var closes = 0
        private set
    var alternateScreen = false
    var selection: TerminalSelection? = null
        private set

    val text: String get() = screen.toString()

    override fun feed(bytes: ByteArray, origin: OutputOrigin) {
        check(!closed) { "fed a closed engine" }
        val text = bytes.decodeToString()
        feeds += text to origin
        for (ch in text) {
            when {
                ch == '\b' || ch.code == 0x7f -> if (screen.isNotEmpty()) screen.deleteAt(screen.length - 1)
                ch.code < 0x20 -> Unit
                else -> screen.append(ch)
            }
        }
        generation++
        sequence++
    }

    override fun reset() {
        resets++
        screen.clear()
        generation++
        sequence++
    }

    override fun resize(size: TerminalSize) {
        this.size = size
        generation++
        sequence++
    }

    override fun colors(colors: TerminalColors) = Unit

    override fun viewport(forceFull: Boolean, breakHold: Boolean): TerminalViewport {
        val style = CellStyle(0L, 0L, CellFlags.NONE, 0)
        val line = screen.toString().takeLast(size.columns).padEnd(size.columns, ' ')
        return TerminalViewport(
            generation = generation,
            size = size,
            rows = (0 until size.rows).map { index ->
                val content = if (index == 0) line else " ".repeat(size.columns)
                TerminalRow(index, (0 until size.columns).map { TerminalCell(content[it].toString(), 1, style) })
            },
            cursor = TerminalCursor(
                column = minOf(screen.length, size.columns - 1),
                row = 0,
                shape = 0,
                visible = true,
            ),
            modes = TerminalModes(alternateScreen, false, false, false),
            historyRows = 0L,
            viewportTop = 0L,
            full = true,
            links = emptyList(),
            selection = selection,
            held = false,
            sequence = sequence,
        )
    }

    override fun acknowledge(generation: Long) = Unit

    override fun scrollTo(row: Long) = Unit

    /**
     * The ONE encoder. A modifier reaches the pty because it is applied HERE, to a key — not
     * because somebody upstream built a control byte and handed it over as text.
     */
    override fun key(key: TerminalKey) {
        if (key.action != 0) return // PRESS only; a release produces no bytes on this wire
        val ctrl = key.modifiers and Modifiers.CTRL != 0
        val alt = key.modifiers and Modifiers.ALT != 0
        val base: String = when {
            key.physicalCode == TerminalKeys.ESCAPE -> "\u001b"
            key.physicalCode == TerminalKeys.TAB -> "\t"
            key.physicalCode == TerminalKeys.ARROW_LEFT -> "\u001b[D"
            key.physicalCode == TerminalKeys.ARROW_RIGHT -> "\u001b[C"
            key.text.isNotEmpty() -> key.text
            else -> return
        }
        val encoded = when {
            ctrl && base.length == 1 && base[0].uppercaseChar() in 'A'..'Z' ->
                ((base[0].uppercaseChar() - 'A') + 1).toChar().toString()
            else -> base
        }
        effects += TerminalEffect.Input((if (alt) "\u001b$encoded" else encoded).encodeToByteArray())
    }

    override fun mouse(mouse: TerminalMouse) = Unit

    /** One operation, never a sequence of key presses — which is exactly what is under test. */
    override fun paste(text: String, allowUnsafe: Boolean): Boolean {
        if (!allowUnsafe && text.contains('\n')) return false
        effects += TerminalEffect.Input("\u001b[200~$text\u001b[201~".encodeToByteArray())
        return true
    }

    override fun focus(focused: Boolean) = Unit

    override fun select(selection: TerminalSelection?) {
        this.selection = selection
    }

    override fun selectedText(): String = ""

    override fun drainEffects(): List<TerminalEffect> {
        val drained = effects.toList()
        effects.clear()
        return drained
    }

    override fun close() {
        closes++
        closed = true
    }
}

/** One socket the test drives directly: push what the broker would send, read what the client sent. */
class FakeTerminalSocket : TerminalSocket {
    private val inbound = Channel<TerminalWireFrame>(Channel.UNLIMITED)

    /** Every binary frame the client sent — i.e. every byte that reached the "pty". */
    val sent = mutableListOf<ByteArray>()

    /** Every control frame (resize, focus, reply, close). */
    val controls = mutableListOf<String>()

    /** How many times the CLIENT closed this socket. See TerminalClient.stop(). */
    var closedByClient = 0
        private set

    val sentText: String get() = sent.joinToString("") { it.decodeToString() }

    override suspend fun receive(): TerminalWireFrame? = inbound.receiveCatching().getOrNull()
    override suspend fun sendBinary(bytes: ByteArray) { sent += bytes }
    override suspend fun sendText(text: String) { controls += text }
    override fun close() {
        closedByClient++
        inbound.cancel()
    }

    fun push(text: String) { inbound.trySend(TerminalWireFrame.Text(text)) }
    fun pushBytes(bytes: ByteArray) { inbound.trySend(TerminalWireFrame.Binary(bytes)) }

    /** The full revision-2 opening a workspace socket always performs. */
    fun openEpoch(epoch: String = "e-1", owner: Boolean = true) {
        push("""{"type":"ready","version":2,"epoch":"c-1","replyOwner":false,"ownerGeneration":0}""")
        push("""{"type":"reset","epoch":"$epoch"}""")
        push("""{"type":"replay-start","epoch":"$epoch"}""")
        push("""{"type":"replay-end","epoch":"$epoch"}""")
        if (owner) push("""{"type":"owner","epoch":"$epoch","enabled":true,"ownerGeneration":1}""")
    }
}

/** Hands out [FakeTerminalSocket]s and remembers them. */
class FakeTerminalTransport : TerminalTransport {
    val sockets = mutableListOf<FakeTerminalSocket>()
    var released = 0
        private set

    override suspend fun open(url: String, token: String, session: suspend (TerminalSocket) -> Unit) {
        val socket = FakeTerminalSocket()
        sockets += socket
        try {
            session(socket)
        } finally {
            released++
        }
    }
}
