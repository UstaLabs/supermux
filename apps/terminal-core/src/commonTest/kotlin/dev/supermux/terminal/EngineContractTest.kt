package dev.supermux.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The behavioural contract every binding (JNI, cinterop, wasm) must pass against the real
 * libghostty-vt engine. Until the native bindings land, `createTerminalEngine` throws
 * [TerminalEngineUnavailableException] on every platform, so this suite is EXPECTED to fail (TDD
 * red). Do not make it pass with a Kotlin fake.
 */
class EngineContractTest {
    private val size = TerminalSize(80, 24, 8, 16)

    private fun <T> withEngine(size: TerminalSize = this.size, block: (TerminalEngine) -> T): T {
        val engine = createTerminalEngine(size, TerminalLimits())
        try {
            return block(engine)
        } finally {
            engine.close()
        }
    }

    private fun TerminalViewport.rowText(row: Int): String =
        rows.first { it.index == row }.cells.filter { it.width != 0 }.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    @Test fun replayQueriesNeverBecomeRemoteInput() {
        val engine = createTerminalEngine(TerminalSize(80, 24, 8, 16), TerminalLimits())
        try {
            engine.feed("\u001b[6n".encodeToByteArray(), OutputOrigin.REPLAY)
            assertTrue(engine.drainEffects().none { it is TerminalEffect.Response })
            engine.feed("\u001b[6n".encodeToByteArray(), OutputOrigin.LIVE)
            assertEquals(1, engine.drainEffects().count { it is TerminalEffect.Response })
        } finally { engine.close() }
    }

    @Test fun liveCursorPositionReportMatchesCursor() = withEngine { engine ->
        engine.feed("ab\u001b[6n".encodeToByteArray(), OutputOrigin.LIVE)
        val responses = engine.drainEffects().filterIsInstance<TerminalEffect.Response>()
        assertEquals(listOf(TerminalEffect.Response("\u001b[1;3R".encodeToByteArray())), responses)
    }

    @Test fun redCellUsesConfiguredPalette() = withEngine { engine ->
        val colors = TestFixtures.fixtureColors()
        engine.colors(colors)
        engine.feed("\u001b[31mred\u001b[0m".encodeToByteArray(), OutputOrigin.LIVE)
        val viewport = engine.viewport(forceFull = true)
        assertTrue(viewport.full)
        val cells = viewport.rows.first { it.index == 0 }.cells
        assertEquals(size.columns, cells.size)
        assertEquals(listOf("r", "e", "d"), cells.take(3).map { it.text })
        for (cell in cells.take(3)) {
            assertEquals(1, cell.width)
            assertEquals(colors.palette[1], cell.style.foreground)
            assertEquals(TerminalColor.DEFAULT, cell.style.background)
            assertEquals(CellFlags.NONE, cell.style.flags)
            assertEquals(Underline.NONE, cell.style.underline)
        }
        assertEquals("", cells[3].text)
        assertEquals(TerminalColor.DEFAULT, cells[3].style.foreground)
        assertEquals(3, viewport.cursor.column)
        assertEquals(0, viewport.cursor.row)
    }

    @Test fun wideCellHasContinuation() = withEngine { engine ->
        engine.feed("世!".encodeToByteArray(), OutputOrigin.LIVE)
        val cells = engine.viewport(forceFull = true).rows.first { it.index == 0 }.cells
        assertEquals("世", cells[0].text)
        assertEquals(2, cells[0].width)
        assertEquals(0, cells[1].width)
        assertEquals("!", cells[2].text)
        assertEquals(1, cells[2].width)
    }

    @Test fun resizeChangesViewportSize() = withEngine { engine ->
        engine.feed("hello".encodeToByteArray(), OutputOrigin.LIVE)
        val smaller = TerminalSize(40, 10, 9, 18)
        engine.resize(smaller)
        val viewport = engine.viewport(forceFull = true)
        assertEquals(smaller, viewport.size)
        assertEquals(10, viewport.rows.size)
        assertTrue(viewport.rows.all { it.cells.size == 40 })
        assertEquals("hello", viewport.rowText(0))
    }

    @Test fun resetClearsContent() = withEngine { engine ->
        // Visible text on the PRIMARY screen, then mouse tracking (1000), bracketed paste (2004) and
        // the alternate screen (1049) on top, so every piece of state reset must clear is really set.
        engine.feed("primary text".encodeToByteArray(), OutputOrigin.LIVE)
        assertEquals("primary text", engine.viewport(forceFull = true).rowText(0))
        engine.feed("\u001b[?1000h\u001b[?2004h\u001b[?1049h\u001b[Halt text".encodeToByteArray(), OutputOrigin.LIVE)
        val before = engine.viewport(forceFull = true)
        assertEquals(TerminalModes(alternateScreen = true, mouseTracking = true, bracketedPaste = true), before.modes)
        assertEquals("alt text", before.rowText(0))

        engine.reset()
        val viewport = engine.viewport(forceFull = true)
        assertEquals(size, viewport.size)
        assertEquals(size.rows, viewport.rows.size)
        assertTrue(viewport.rows.all { row -> row.cells.all { it.text.isEmpty() } })
        assertEquals(0, viewport.cursor.column)
        assertEquals(0, viewport.cursor.row)
        assertEquals(TerminalModes(alternateScreen = false, mouseTracking = false, bracketedPaste = false), viewport.modes)
        // Leaving the alternate screen must not bring the primary text back either.
        engine.feed("\u001b[?1049l".encodeToByteArray(), OutputOrigin.LIVE)
        assertTrue(engine.viewport(forceFull = true).rows.all { row -> row.cells.all { it.text.isEmpty() } })
    }

    @Test fun bellAndClipboardAreLiveOnly() = withEngine { engine ->
        // BEL + an OSC 52 clipboard write ("aGk=" = "hi").
        val bytes = "\u0007\u001b]52;c;aGk=\u0007".encodeToByteArray()
        engine.feed(bytes, OutputOrigin.REPLAY)
        assertTrue(engine.drainEffects().none { it is TerminalEffect.Bell || it is TerminalEffect.ClipboardRequest })
        engine.feed(bytes, OutputOrigin.LIVE)
        assertEquals(
            listOf(TerminalEffect.Bell, TerminalEffect.ClipboardRequest(write = true, text = "hi")),
            engine.drainEffects().filter { it is TerminalEffect.Bell || it is TerminalEffect.ClipboardRequest },
        )
    }

    @Test fun liveTitleIsReported() = withEngine { engine ->
        engine.feed("\u001b]2;hi\u0007".encodeToByteArray(), OutputOrigin.LIVE)
        assertEquals(listOf<TerminalEffect>(TerminalEffect.Title("hi")), engine.drainEffects())
    }

    @Test fun drainEmptiesQueue() = withEngine { engine ->
        engine.feed("\u0007".encodeToByteArray(), OutputOrigin.LIVE)
        assertEquals(listOf<TerminalEffect>(TerminalEffect.Bell), engine.drainEffects())
        assertEquals(emptyList(), engine.drainEffects())
    }

    @Test fun unsafePasteIsRejectedUntilAllowed() = withEngine { engine ->
        engine.feed("\u001b[?2004h".encodeToByteArray(), OutputOrigin.LIVE)
        engine.drainEffects()
        // The bracketed-paste end marker inside pasted text could inject commands.
        val unsafe = "a\u001b[201~b"
        assertEquals(false, engine.paste(unsafe))
        assertTrue(engine.drainEffects().none { it is TerminalEffect.Input })
        assertEquals(true, engine.paste(unsafe, allowUnsafe = true))
        assertEquals(1, engine.drainEffects().count { it is TerminalEffect.Input })
        assertEquals(true, engine.paste("safe"))
        assertEquals(
            listOf<TerminalEffect>(TerminalEffect.Input("\u001b[200~safe\u001b[201~".encodeToByteArray())),
            engine.drainEffects(),
        )
    }

    @Test fun heldFrameUntilBreakHold() = withEngine { engine ->
        engine.feed("A\u001b[?2026hB".encodeToByteArray(), OutputOrigin.LIVE)
        val held = engine.viewport(forceFull = true)
        assertTrue(held.held)
        assertEquals("A", held.rowText(0))
        val live = engine.viewport(forceFull = true, breakHold = true)
        assertFalse(live.held)
        assertEquals("AB", live.rowText(0))
    }

    @Test fun closeIsIdempotent() {
        val engine = createTerminalEngine(size, TerminalLimits())
        engine.close()
        engine.close()
    }
}
