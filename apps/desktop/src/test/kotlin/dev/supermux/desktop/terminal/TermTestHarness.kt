package dev.supermux.desktop.terminal

import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer

/**
 * Headless JediTerm MODEL + a real [MuxTtyConnector] wired exactly the way production is, so
 * Task-5 prediction code can be exercised end-to-end WITHOUT AWT/Swing (mirrors
 * [JediTermSmokeTest]'s pure-model construction, but adds the connector→emulator lane so injected
 * escapes actually parse):
 *
 *   connector.injectDisplayBytes(...) → FIFO queue → TtyBasedArrayDataStream(connector).read()
 *     → JediEmulator drains on a daemon thread → JediTerminal mutates TerminalTextBuffer
 *
 * A background drain thread is the honest reproduction of production (JediTerm's own emulator
 * thread drives [MuxTtyConnector.read]); [awaitChar] polls the buffer under its lock until the
 * asynchronously-parsed escape lands or the timeout trips. Call [close] to end the drain thread
 * (EOF unblocks the reader).
 */
internal class TermTestHarness(cols: Int = 80, rows: Int = 24) {
    private class NoOpDisplay : TerminalDisplay {
        override fun setCursor(x: Int, y: Int) {}
        override fun setCursorShape(shape: CursorShape?) {}
        override fun beep() {}
        override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) {}
        override fun setCursorVisible(visible: Boolean) {}
        override fun useAlternateScreenBuffer(useAlternate: Boolean) {}
        override fun getWindowTitle(): String = ""
        override fun setWindowTitle(name: String) {}
        override fun getSelection(): TerminalSelection? = null
        override fun terminalMouseModeSet(mouseMode: MouseMode) {}
        override fun setMouseFormat(mouseFormat: MouseFormat) {}
        override fun ambiguousCharsAreDoubleWidth(): Boolean = false
    }

    val styleState = StyleState()
    val buffer = TerminalTextBuffer(cols, rows, styleState, 1000)
    val terminal = JediTerminal(NoOpDisplay(), buffer, styleState)

    /** User-input bytes the connector forwarded (the `sendInput` sink), for pre-send-tap assertions.
     *  Plain (unsynchronized) list — TEST-THREAD-ONLY BY INVARIANT: only connector.write() appends,
     *  and tests invoke write()/handleInput on the test thread (production's JediTerm write-executor
     *  thread doesn't exist in this harness). The pipeline stress test hammers handleInput from its
     *  own thread but never routes user input through connector.write(), so the invariant holds. */
    val sent = mutableListOf<ByteArray>()

    val connector = MuxTtyConnector(
        sendInput = { sent.add(it) },
        requestResize = { _, _ -> },
        isConnected = { true },
    )

    private val emulator = JediEmulator(TtyBasedArrayDataStream(connector), terminal)
    private val drainThread = Thread({
        // next() swallows the EOF (see DataStreamIteratingEmulator), so the loop ends cleanly on
        // closeStream(); the catch is a belt-and-braces guard for any other emulator hiccup.
        runCatching { while (emulator.hasNext()) emulator.next() }
    }, "term-test-drain").apply { isDaemon = true }

    init {
        drainThread.start()
    }

    /** Read one buffer cell (0-based both axes, per the smoke test) under the buffer lock. */
    fun charAt(x: Int, y: Int): Char {
        buffer.lock()
        try {
            return buffer.getCharAt(x, y)
        } finally {
            buffer.unlock()
        }
    }

    /** Read [len] chars of row [y] starting at column [x0], 0-based. */
    fun textAt(x0: Int, y: Int, len: Int): String =
        (x0 until x0 + len).map { charAt(it, y) }.joinToString("")

    /** Poll until cell (x,y) equals [expected] or [timeoutMs] elapses; returns the final cell. */
    fun awaitChar(x: Int, y: Int, expected: Char, timeoutMs: Long = 2000): Char {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (charAt(x, y) == expected) return expected
            Thread.sleep(5)
        }
        return charAt(x, y)
    }

    fun close() {
        connector.closeStream()
    }
}
