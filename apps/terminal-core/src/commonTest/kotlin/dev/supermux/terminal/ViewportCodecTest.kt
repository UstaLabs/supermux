package dev.supermux.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the golden buffers the REAL native encoder produced (fixtures/codec/<name>.bin, embedded in
 * CodecGolden.kt; the native bridge test asserts it still emits these exact bytes), then checks
 * the strict envelope rules against corrupted copies.
 */
class ViewportCodecTest {
    private val colors = TestFixtures.fixtureColors()
    private val red = colors.palette[1]
    private val blueBg = colors.palette[4]

    private fun TerminalViewport.row(index: Int) = rows.first { it.index == index }
    private fun TerminalRow.text() = cells.filter { it.width != 0 }.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()
    private fun style(fg: Long = TerminalColor.DEFAULT, bg: Long = TerminalColor.DEFAULT, flags: Int = 0, underline: Int = 0) =
        CellStyle(fg, bg, flags, underline)

    @Test fun decodesFullViewport() {
        val v = ViewportCodec.decodeViewport(CodecGolden.VIEWPORT_FULL)
        assertEquals(TerminalSize(12, 3, 8, 16), v.size)
        assertTrue(v.full)
        assertTrue(v.generation > 0)
        assertEquals(listOf(0, 1, 2), v.rows.map { it.index })
        assertTrue(v.rows.all { it.cells.size == 12 })

        val r0 = v.row(0).cells
        assertEquals(listOf("r", "e", "d"), r0.take(3).map { it.text })
        r0.take(3).forEach { assertEquals(TerminalCell(it.text, 1, style(fg = red)), it) }
        assertEquals(TerminalCell(" ", 1, style()), r0[3])
        assertEquals(TerminalCell("B", 1, style(flags = CellFlags.BOLD, underline = Underline.SINGLE)), r0[4])
        assertEquals(TerminalCell("世", 2, style()), r0[5])
        assertEquals(TerminalCell("", 0, style()), r0[6])
        assertEquals(TerminalCell("", 1, style()), r0[7])

        val r1 = v.row(1)
        assertEquals("ln e\u0301", r1.text())
        assertEquals("e\u0301", r1.cells[3].text)
        assertEquals(TerminalCell(" ", 1, style(bg = blueBg)), r1.cells[4])
        assertEquals("", v.row(2).text())

        assertEquals(TerminalCursor(5, 1, CursorShape.BLOCK, true), v.cursor)
        assertEquals(TerminalModes(alternateScreen = false, mouseTracking = false, bracketedPaste = false), v.modes)
        assertEquals(0L, v.historyRows)
        assertEquals(0L, v.viewportTop)
        assertEquals(listOf(TerminalLink(1, 0, 1, "https://x.y/z")), v.links)
        assertEquals(TerminalSelection(TerminalPoint(0, 0), TerminalPoint(0, 5)), v.selection)
    }

    @Test fun decodesPartialViewport() {
        val full = ViewportCodec.decodeViewport(CodecGolden.VIEWPORT_FULL)
        val v = ViewportCodec.decodeViewport(CodecGolden.VIEWPORT_PARTIAL)
        assertFalse(v.full)
        assertTrue(v.generation > full.generation)
        assertEquals(listOf(1, 2), v.rows.map { it.index })
        assertEquals("rev", v.row(2).text())
        v.row(2).cells.take(3).forEach { assertEquals(CellFlags.INVERSE, it.style.flags) }
        assertEquals(TerminalCursor(3, 2, CursorShape.BLOCK, true), v.cursor)
        assertEquals(TerminalModes(alternateScreen = false, mouseTracking = true, bracketedPaste = true), v.modes)
        assertEquals(full.links, v.links)
        assertEquals(full.selection, v.selection)
    }

    @Test fun decodesEffects() {
        assertEquals(
            listOf(
                TerminalEffect.Response("\u001b[3;4R".encodeToByteArray()),
                TerminalEffect.Bell,
                TerminalEffect.Title("title é"),
                TerminalEffect.ClipboardRequest(write = true, text = "hi"),
                TerminalEffect.ClipboardRequest(write = false, text = null),
                TerminalEffect.Response("\u001b]52;c;\u0007".encodeToByteArray()),
                TerminalEffect.ClipboardRequest(write = true, text = null),
                TerminalEffect.Input(byteArrayOf(0x03)),
            ),
            ViewportCodec.decodeEffects(CodecGolden.EFFECTS),
        )
        assertEquals(emptyList(), ViewportCodec.decodeEffects(CodecGolden.EFFECTS_EMPTY))
    }

    @Test fun decodesSelectedText() {
        assertEquals("red B世", ViewportCodec.decodeSelectedText(CodecGolden.SELECTED_TEXT))
    }

    @Test fun rejectsEveryTruncation() {
        for (golden in listOf(CodecGolden.VIEWPORT_FULL, CodecGolden.VIEWPORT_PARTIAL)) {
            for (cut in 0 until golden.size) {
                val truncated = golden.copyOf(cut)
                assertFailsWith<TerminalCodecException>("cut at $cut") { ViewportCodec.decodeViewport(truncated) }
                if (cut >= ViewportCodec.HEADER_BYTES) {
                    // Also when the header is patched to claim exactly the bytes that remain.
                    val consistent = truncated.copyOf().also { it.putU32(8, (cut - ViewportCodec.HEADER_BYTES).toLong()) }
                    assertFailsWith<TerminalCodecException>("consistent cut at $cut") { ViewportCodec.decodeViewport(consistent) }
                }
            }
        }
        for (cut in 0 until CodecGolden.EFFECTS.size) {
            assertFailsWith<TerminalCodecException> { ViewportCodec.decodeEffects(CodecGolden.EFFECTS.copyOf(cut)) }
        }
        for (cut in 0 until CodecGolden.SELECTED_TEXT.size) {
            assertFailsWith<TerminalCodecException> { ViewportCodec.decodeSelectedText(CodecGolden.SELECTED_TEXT.copyOf(cut)) }
        }
    }

    @Test fun rejectsBadEnvelopes() {
        val g = CodecGolden.VIEWPORT_FULL
        assertFailsWith<TerminalCodecException>("trailing byte") { ViewportCodec.decodeViewport(g + byteArrayOf(0)) }
        assertFailsWith<TerminalCodecException>("magic") { ViewportCodec.decodeViewport(g.copyOf().also { it[0] = 0 }) }
        assertFailsWith<TerminalCodecException>("abi") { ViewportCodec.decodeViewport(g.copyOf().also { it[4] = 2 }) }
        assertFailsWith<TerminalCodecException>("kind") { ViewportCodec.decodeEffects(g) }
        assertFailsWith<TerminalCodecException>("kind") { ViewportCodec.decodeViewport(CodecGolden.EFFECTS) }
        assertFailsWith<TerminalCodecException>("payload length") {
            ViewportCodec.decodeViewport(g.copyOf().also { it.putU32(8, (g.size - 11).toLong()) })
        }
    }

    @Test fun rejectsOversizedPayload() {
        val payload = ViewportCodec.MAX_PAYLOAD_BYTES + 1
        val big = ByteArray(ViewportCodec.HEADER_BYTES + payload)
        CodecGolden.EFFECTS_EMPTY.copyInto(big, 0, 0, 8)
        big.putU32(8, payload.toLong())
        val e = assertFailsWith<TerminalCodecException> { ViewportCodec.decodeEffects(big) }
        assertTrue("exceeds" in e.message!!, e.message)
    }

    @Test fun rejectsImpossibleCounts() {
        val rowCountOffset = ViewportCodec.HEADER_BYTES + 8 + 16
        for (count in listOf(0xFFFF_FFFFL, 4L, 1_000_000L)) {
            val bad = CodecGolden.VIEWPORT_FULL.copyOf().also { it.putU32(rowCountOffset, count) }
            assertFailsWith<TerminalCodecException>("row count $count") { ViewportCodec.decodeViewport(bad) }
        }
        val effectCountOffset = ViewportCodec.HEADER_BYTES
        val bad = CodecGolden.EFFECTS.copyOf().also { it.putU32(effectCountOffset, 0x7FFF_FFFFL) }
        val e = assertFailsWith<TerminalCodecException> { ViewportCodec.decodeEffects(bad) }
        assertTrue("impossible count" in e.message!!, e.message)
        val badString = CodecGolden.SELECTED_TEXT.copyOf().also { it.putU32(ViewportCodec.HEADER_BYTES, 0xFFFF_FFF0L) }
        assertFailsWith<TerminalCodecException> { ViewportCodec.decodeSelectedText(badString) }
    }

    @Test fun rejectsOutOfRangeValues() {
        // First cell of row 0 starts after: header, generation, size, row count, row index, cell count.
        val cellOffset = ViewportCodec.HEADER_BYTES + 8 + 16 + 4 + 4 + 4
        val textLen = 1 // "r"
        val widthOffset = cellOffset + 4 + textLen
        val fgOffset = widthOffset + 4
        val g = CodecGolden.VIEWPORT_FULL
        assertFailsWith<TerminalCodecException>("width 3") { ViewportCodec.decodeViewport(g.copyOf().also { it.putU32(widthOffset, 3) }) }
        assertFailsWith<TerminalCodecException>("colour bit 33") {
            ViewportCodec.decodeViewport(g.copyOf().also { it[fgOffset + 4] = 2 })
        }
        assertFailsWith<TerminalCodecException>("invalid utf-8") {
            ViewportCodec.decodeViewport(g.copyOf().also { it[cellOffset + 4] = 0xFF.toByte() })
        }
        assertFailsWith<TerminalCodecException>("columns 0") {
            ViewportCodec.decodeViewport(g.copyOf().also { it.putU32(ViewportCodec.HEADER_BYTES + 8, 0) })
        }
        assertFailsWith<TerminalCodecException>("columns 4097") {
            ViewportCodec.decodeViewport(g.copyOf().also { it.putU32(ViewportCodec.HEADER_BYTES + 8, 4097) })
        }
        // Effects: bool byte 2, unknown tag.
        val fx = CodecGolden.EFFECTS
        assertFailsWith<TerminalCodecException>("tag") {
            ViewportCodec.decodeEffects(fx.copyOf().also { it[ViewportCodec.HEADER_BYTES + 4] = 9 })
        }
        val bellFree = CodecGolden.EFFECTS_EMPTY.copyOf(ViewportCodec.HEADER_BYTES + 4) + byteArrayOf(5, 2, 0)
        bellFree.putU32(ViewportCodec.HEADER_BYTES, 1)
        bellFree.putU32(8, 7)
        assertFailsWith<TerminalCodecException>("bool 2") { ViewportCodec.decodeEffects(bellFree) }
        val ok = bellFree.copyOf().also { it[ViewportCodec.HEADER_BYTES + 5] = 1 }
        assertEquals(listOf(TerminalEffect.ClipboardRequest(write = true, text = null)), ViewportCodec.decodeEffects(ok))
    }

    @Test fun decodedLinesUseContractTypes() {
        val v = ViewportCodec.decodeViewport(CodecGolden.VIEWPORT_FULL)
        assertNull(ViewportCodec.decodeViewport(CodecGolden.VIEWPORT_FULL.clearSelection()).selection)
        assertEquals(v.copy(selection = null), ViewportCodec.decodeViewport(CodecGolden.VIEWPORT_FULL.clearSelection()))
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        for (i in 0 until 4) this[offset + i] = (value ushr (8 * i)).toByte()
    }

    /** The selection is the last field: presence byte + i64 + i32 + i64 + i32. */
    private fun ByteArray.clearSelection(): ByteArray {
        val tail = 1 + 8 + 4 + 8 + 4
        val out = copyOf(size - tail + 1)
        out[out.size - 1] = 0
        out.putU32(8, (out.size - ViewportCodec.HEADER_BYTES).toLong())
        return out
    }
}
