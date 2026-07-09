package dev.supermux.desktop.terminal

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.CharBuffer
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Headless smoke test proving `jediterm-core`/`jediterm-ui` 3.73 resolve from
 * `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies` (they did, first try — see
 * Task 1 report) AND that the MODEL layer (`TerminalTextBuffer` + `JediTerminal`) is constructible
 * and drivable with **zero `java.awt`/Swing imports anywhere in this file** — the key finding
 * Task 5 (predictive echo, needs public buffer/cursor reads) and Task 2/3 depend on.
 *
 * Constructor shapes / APIs that worked, headlessly:
 *  - `StyleState()` — no-arg constructor.
 *  - `TerminalTextBuffer(width: Int, height: Int, styleState: StyleState, maxHistoryLinesCount: Int)`
 *    — the 4-arg constructor (a Kotlin default-arg overload of the 5-arg ctor that also takes a
 *    nullable `TextProcessing` for hyperlink matching; we don't need that for the terminal panel).
 *  - `JediTerminal(display: TerminalDisplay, buffer: TerminalTextBuffer, styleState: StyleState)`
 *    — `TerminalDisplay` is a **pure interface** (`setCursor`, `scrollArea`, `beep`,
 *    `setCursorVisible`, `useAlternateScreenBuffer`, `getWindowTitle`/`setWindowTitle`,
 *    `getSelection`, `terminalMouseModeSet`, `setMouseFormat`, `ambiguousCharsAreDoubleWidth`,
 *    plus default `onResize`/`setBracketedPasteMode`/`getWindowForeground`/`getWindowBackground`)
 *    with **no AWT/Swing types anywhere in its signature**, so a trivial no-op stub
 *    (`NoOpTerminalDisplay` below) satisfies it without touching `jediterm-ui` or AWT at all.
 *    Confirms: JediTerm's core model has no hard AWT dependency — only `jediterm-ui`'s
 *    `JediTermWidget` (Swing) does. The pure-model path works; no `JediTermWidget` /
 *    `HeadlessException` fallback was needed.
 *  - Feeding bytes through the REAL emulator (this is what a PTY byte-stream needs — NOT
 *    `JediTerminal.writeCharacters`, which bypasses escape-sequence processing):
 *    `ArrayTerminalDataStream(charArray)` + `JediEmulator(dataStream, terminal)` (terminal
 *    implements `com.jediterm.terminal.Terminal`), then drain with
 *    `while (emulator.hasNext()) emulator.next()`.
 *
 * ### Coordinate conventions (load-bearing for Task 5's `PredictionAdapter` — verified empirically
 * here, byte-code-audited against 3.73's actual implementation, NOT assumed):
 *  - `JediTerminal.getCursorX()` / `getCursorY()` are **1-based** (VT100/ANSI cursor-addressing
 *    convention). A fresh terminal reports `cursorX == 1, cursorY == 1`.
 *  - `TerminalTextBuffer.getCharAt(x, y)` is **0-based in BOTH axes** (`y` indexes screen lines
 *    directly via `getLine(y)`).
 *  - `TerminalTextBuffer.writeString(x, y, CharBuffer)` — the prediction-adapter's programmatic
 *    write path — is **mixed**: `x` (column) is 0-based (passed straight through to
 *    `TerminalLine.writeString`), but `y` (row) is **1-based**: the implementation does
 *    `screenLinesStorage.get(y - 1)` internally. So to draw a synthetic char at the buffer cell
 *    the emulator would report via `getCharAt(x, y)`, call `writeString(x, y + 1, ...)`.
 *  - Net effect for the adapter: to write AT the current cursor cell, call
 *    `writeString(cursorX - 1, cursorY, buf)`; to read that same cell back for a snapshot/restore,
 *    call `getCharAt(cursorX - 1, cursorY - 1)`. `PredictionAdapterTest` (Task 5) must assert this
 *    exact mapping — do not re-derive it from scratch there.
 */
class JediTermSmokeTest {

    /** No-op [TerminalDisplay]: every method in this interface is AWT-free, so this stub proves
     *  the core model needs no Swing/AWT classes on the classpath at all. */
    private class NoOpTerminalDisplay : TerminalDisplay {
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

    private data class Model(val terminal: JediTerminal, val buffer: TerminalTextBuffer, val styleState: StyleState)

    private fun newTerminal(cols: Int = 80, rows: Int = 24): Model {
        val styleState = StyleState()
        val buffer = TerminalTextBuffer(cols, rows, styleState, 1000)
        val terminal = JediTerminal(NoOpTerminalDisplay(), buffer, styleState)
        return Model(terminal, buffer, styleState)
    }

    /** Feeds [text] through a real [JediEmulator] (like a PTY byte-stream would), so CR/LF and
     *  escape sequences are interpreted rather than written verbatim. */
    private fun feed(terminal: JediTerminal, text: String) {
        val dataStream = ArrayTerminalDataStream(text.toCharArray())
        val emulator = JediEmulator(dataStream, terminal)
        while (emulator.hasNext()) emulator.next()
    }

    @Test
    fun `emulator write path renders hello world across two lines`() {
        val model = newTerminal()
        feed(model.terminal, "hello\r\nworld")

        // getCharAt is 0-based in both axes: row 0 is "hello", row 1 is "world".
        val line0 = (0 until 5).map { model.buffer.getCharAt(it, 0) }.joinToString("")
        val line1 = (0 until 5).map { model.buffer.getCharAt(it, 1) }.joinToString("")
        assertEquals("hello", line0)
        assertEquals("world", line1)
    }

    @Test
    fun `cursor advances with writes and CRLF, 1-based VT100 convention`() {
        val model = newTerminal()
        feed(model.terminal, "hello\r\nworld")

        // cursorX/cursorY are 1-based: starts at (1,1); "hello" -> (6,1); CRLF -> (1,2);
        // "world" (5 chars) -> (6,2).
        assertEquals(6, model.terminal.cursorX)
        assertEquals(2, model.terminal.cursorY)
    }

    @Test
    fun `programmatic styled write via TerminalTextBuffer writeString works`() {
        val model = newTerminal()
        // This is the prediction-adapter dependency: draw synthetic chars directly into the
        // buffer, independent of the emulator's own write path. writeString's y is 1-based
        // (internally y-1 indexes the screen line), x is 0-based — see class KDoc.
        model.buffer.writeString(2, 3, CharBuffer("hi"))

        assertEquals('h', model.buffer.getCharAt(2, 2))
        assertEquals('i', model.buffer.getCharAt(3, 2))
        // The 1-based row itself (index 3, i.e. row y=3 not y-1=2) stays untouched.
        assertEquals(' ', model.buffer.getCharAt(2, 3))
    }

    @Test
    fun `writing at the current cursor cell and reading it back round-trips`() {
        val model = newTerminal()
        feed(model.terminal, "ab") // cursor now at column 3 (1-based), row 1

        // Draw a synthetic char exactly at the cursor cell using the mapping documented above.
        val cursorX = model.terminal.cursorX
        val cursorY = model.terminal.cursorY
        model.buffer.writeString(cursorX - 1, cursorY, CharBuffer("Z"))

        assertEquals('Z', model.buffer.getCharAt(cursorX - 1, cursorY - 1))
    }

    @Test
    fun `getCharAt and cursor getters are readable on a fresh buffer`() {
        val model = newTerminal()

        // Fresh cells read back as blank (space) rather than throwing.
        assertEquals(' ', model.buffer.getCharAt(0, 0))
        assertEquals(1, model.terminal.cursorX)
        assertEquals(1, model.terminal.cursorY)
    }
}
