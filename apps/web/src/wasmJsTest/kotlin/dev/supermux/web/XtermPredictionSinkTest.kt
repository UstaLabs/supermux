// `JsAny`/`toJsString` are still behind the wasm-interop opt-in in Kotlin 2.3; wasmJsMain sets it
// in the build file, the test source set does not.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.supermux.web

import dev.supermux.net.DrawDim
import dev.supermux.net.HideCaret
import dev.supermux.net.MoveCaret
import dev.supermux.net.Passthrough
import dev.supermux.net.RestoreCell
import dev.supermux.net.ShowCaret
import dev.supermux.web.terminal.Terminal
import dev.supermux.web.terminal.XtermPredictionSink
import dev.supermux.web.terminal.xtermCursorCol
import dev.supermux.web.terminal.xtermCursorRow
import dev.supermux.web.terminal.xtermReadCell
import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLDivElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The op renderer against a REAL xterm.js terminal (Karma, headless Chrome) — the only place the
 * escape sequences and the 0-based↔1-based CUP conversion are actually proved. A unit test with a
 * fake terminal would only re-assert the strings this file writes.
 *
 * xterm measures a character cell on `open`, so the terminal must be attached to a laid-out
 * element: each test mounts its own div on `document.body` and removes it afterwards. Writes are
 * parsed ASYNCHRONOUSLY, so every assertion waits on the write callback.
 */
class XtermPredictionSinkTest {
    private fun mount(): Pair<Terminal, HTMLDivElement> {
        val div = document.createElement("div") as HTMLDivElement
        div.style.width = "600px"
        div.style.height = "300px"
        document.body!!.appendChild(div)
        val term = Terminal()
        term.open(div)
        return term to div
    }

    private fun unmount(term: Terminal, div: HTMLDivElement) {
        term.dispose()
        document.body!!.removeChild(div)
    }

    /** Suspend until xterm's parser has consumed everything written so far. */
    private suspend fun Terminal.flush() {
        val done = CompletableDeferred<Unit>()
        write("".toJsString()) { done.complete(Unit) }
        done.await()
    }

    @Test
    fun drawDimPaintsTheCellAndRestoreClearsIt() = runTest {
        val (term, div) = mount()
        try {
            term.write("abc".toJsString())
            term.flush()

            val sink = XtermPredictionSink(term)
            // Column 3 is the cell just past "abc" — row/col are 0-based, the CUP escape is 1-based.
            sink.render(listOf(DrawDim(id = 1, row = 0, col = 3, char = "x")))
            term.flush()
            assertEquals("x", xtermReadCell(term, 0, 3), "the dim prediction should occupy cell (0,3)")
            assertEquals("a", xtermReadCell(term, 0, 0), "the existing line must be untouched")

            // The snapshot taken on DrawDim was a blank cell, so the rollback rewrites a space.
            sink.render(listOf(RestoreCell(id = 1, row = 0, col = 3)))
            term.flush()
            assertEquals(" ", xtermReadCell(term, 0, 3), "RestoreCell should put the old cell back")
        } finally {
            unmount(term, div)
        }
    }

    @Test
    fun restoreRewritesTheCharacterThatWasThere() = runTest {
        val (term, div) = mount()
        try {
            term.write("abc".toJsString())
            term.flush()

            val sink = XtermPredictionSink(term)
            sink.render(listOf(DrawDim(id = 7, row = 0, col = 1, char = "Z")))
            term.flush()
            assertEquals("Z", xtermReadCell(term, 0, 1))

            sink.render(listOf(RestoreCell(id = 7, row = 0, col = 1)))
            term.flush()
            assertEquals("b", xtermReadCell(term, 0, 1), "the pre-prediction snapshot must come back")
        } finally {
            unmount(term, div)
        }
    }

    @Test
    fun moveCaretMovesTheRealCursor() = runTest {
        val (term, div) = mount()
        try {
            term.write("abc".toJsString())
            term.flush()
            assertEquals(3, xtermCursorCol(term), "cursor sits after the three written chars")

            XtermPredictionSink(term).render(listOf(MoveCaret(row = 0, col = 1)))
            term.flush()
            assertEquals(1, xtermCursorCol(term), "MoveCaret is an absolute, 0-based reposition")
            assertEquals(0, xtermCursorRow(term))
        } finally {
            unmount(term, div)
        }
    }

    @Test
    fun passthroughWritesServerBytesVerbatim() = runTest {
        val (term, div) = mount()
        try {
            // Includes a multi-byte glyph: Passthrough hands xterm the RAW bytes, so the emulator
            // does the UTF-8 decoding (the reason the op carries a ByteArray and not a String).
            XtermPredictionSink(term).render(listOf(Passthrough("héllo".encodeToByteArray())))
            term.flush()
            assertEquals("h", xtermReadCell(term, 0, 0))
            assertEquals("é", xtermReadCell(term, 0, 1))
            assertEquals("o", xtermReadCell(term, 0, 4))
        } finally {
            unmount(term, div)
        }
    }

    @Test
    fun caretBracketHidesAndShowsWithoutDisturbingTheScreen() = runTest {
        val (term, div) = mount()
        try {
            term.write("abc".toJsString())
            term.flush()
            val sink = XtermPredictionSink(term)
            // The bracket the engine wraps a reconcile batch in: DECTCEM off, paint, DECTCEM on.
            sink.render(listOf(HideCaret, DrawDim(id = 2, row = 0, col = 3, char = "q"), ShowCaret))
            term.flush()
            assertEquals("q", xtermReadCell(term, 0, 3), "the bracketed draw still lands")
            assertEquals("abc", (0..2).joinToString("") { xtermReadCell(term, 0, it) })
            assertEquals(4, xtermCursorCol(term), "the caret ends after the painted cell")
        } finally {
            unmount(term, div)
        }
    }

    @Test
    fun cursorIsZeroBasedOnBothAxes() = runTest {
        val (term, div) = mount()
        try {
            term.write("hi\r\nthere".toJsString())
            term.flush()
            val pos = XtermPredictionSink(term).cursor()
            assertEquals(1, pos.row, "second line = row 1")
            assertEquals(5, pos.col, "after \"there\" = col 5")
        } finally {
            unmount(term, div)
        }
    }
}
