package dev.supermux.terminal

/**
 * A native buffer violated the st_* codec (native/README.md, "Codec"): wrong magic/ABI/kind,
 * truncated, oversized, trailing bytes, an impossible count, an out-of-range value or invalid UTF-8.
 * Bindings treat it as an engine failure; it never means "empty".
 */
class TerminalCodecException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Strict decoder for the envelopes the st_* ABI returns (st_read_viewport, st_drain_effects,
 * st_selected_text). Shared by every binding so all platforms see identical types.
 *
 * ```
 * u32 magic = 0x53545654; u16 abi = 1; u16 kind; u32 payloadBytes; payload[payloadBytes]
 * ```
 * Little-endian; strings are u32 length + UTF-8; booleans u8 0/1; nullable = u8 presence + value;
 * Long = i64 except colours (u64 [TerminalColor]); Int = i32; collections = u32 count + elements.
 * Every rule is enforced: the buffer must be exactly `12 + payloadBytes` long, payloadBytes ≤ 8 MiB,
 * sizes 1..4096 with at most [MAX_CELLS] cells, counts possible for the remaining bytes, enums and
 * colours in range, cell text ≤ [MAX_CELL_TEXT_BYTES] and empty for width-0 cells, selection inside
 * the history + screen rows.
 */
object ViewportCodec {
    const val MAGIC: Int = 0x53545654
    const val ABI: Int = 1
    const val HEADER_BYTES: Int = 12
    const val MAX_PAYLOAD_BYTES: Int = 8 * 1024 * 1024
    const val MAX_DIMENSION: Int = TerminalSize.MAX_DIMENSION
    const val MAX_CELLS: Int = TerminalSize.MAX_CELLS
    const val MAX_CELL_TEXT_BYTES: Int = TerminalSize.MAX_CELL_TEXT_BYTES

    /** Upper bound for list pre-sizing; counts are validated against the bytes, not trusted. */
    private const val MAX_PRESIZE = 4096

    const val KIND_VIEWPORT: Int = 1
    const val KIND_EFFECTS: Int = 2
    const val KIND_SELECTED_TEXT: Int = 3

    const val EFFECT_RESPONSE: Int = 1
    const val EFFECT_INPUT: Int = 2
    const val EFFECT_TITLE: Int = 3
    const val EFFECT_BELL: Int = 4
    const val EFFECT_CLIPBOARD: Int = 5

    // Smallest possible encodings, used to reject impossible counts before allocating.
    private const val MIN_ROW_BYTES = 8 // i32 index + u32 cell count
    private const val MIN_CELL_BYTES = 32 // u32 text length + i32 width + 2 x u64 + 2 x i32
    private const val MIN_LINK_BYTES = 16 // 3 x i32 + u32 uri length
    private const val MIN_EFFECT_BYTES = 1 // tag (Bell)

    fun decodeViewport(bytes: ByteArray): TerminalViewport {
        val r = open(bytes, KIND_VIEWPORT)
        val generation = r.i64()
        val columns = r.i32()
        val rows = r.i32()
        val cellWidth = r.i32()
        val cellHeight = r.i32()
        if (columns !in 1..MAX_DIMENSION || rows !in 1..MAX_DIMENSION || columns.toLong() * rows > MAX_CELLS) {
            fail("viewport size ${columns}x$rows out of range")
        }
        if (cellWidth < 1 || cellHeight < 1) fail("cell size ${cellWidth}x$cellHeight out of range")
        val size = TerminalSize(columns, rows, cellWidth, cellHeight)

        val rowCount = r.count(MIN_ROW_BYTES)
        if (rowCount > rows) fail("$rowCount rows in a $rows-row viewport")
        var previous = -1
        val rowList = ArrayList<TerminalRow>(rowCount)
        repeat(rowCount) {
            val index = r.i32()
            if (index <= previous || index >= rows) fail("row index $index out of order or range")
            previous = index
            val cellCount = r.count(MIN_CELL_BYTES)
            if (cellCount != columns) fail("row $index has $cellCount cells, expected $columns")
            val cells = ArrayList<TerminalCell>(cellCount)
            repeat(cellCount) { cells.add(r.cell()) }
            rowList.add(TerminalRow(index, cells))
        }

        val cursorColumn = r.i32()
        val cursorRow = r.i32()
        val shape = r.i32()
        if (shape !in CursorShape.BLOCK..CursorShape.BLOCK_HOLLOW) fail("cursor shape $shape")
        val cursor = TerminalCursor(cursorColumn, cursorRow, shape, r.bool())
        val modes = TerminalModes(alternateScreen = r.bool(), mouseTracking = r.bool(), bracketedPaste = r.bool())
        val historyRows = r.i64()
        val viewportTop = r.i64()
        if (historyRows < 0 || viewportTop < 0 || viewportTop > historyRows) {
            fail("scroll position top=$viewportTop history=$historyRows")
        }
        val full = r.bool()
        if (full && rowCount != rows) fail("full frame with $rowCount of $rows rows")

        val linkCount = r.count(MIN_LINK_BYTES)
        val links = ArrayList<TerminalLink>(minOf(linkCount, MAX_PRESIZE))
        repeat(linkCount) {
            val row = r.i32()
            val first = r.i32()
            val last = r.i32()
            val uri = r.string()
            if (row !in 0 until rows || first < 0 || last < first || last >= columns) {
                fail("link row=$row columns=$first..$last out of range")
            }
            links.add(TerminalLink(row, first, last, uri))
        }
        val selection = if (r.bool()) {
            val lastRow = historyRows + rows - 1
            val start = r.point(columns, lastRow)
            val end = r.point(columns, lastRow)
            TerminalSelection(start, end)
        } else {
            null
        }
        val held = r.bool()
        r.end()
        return TerminalViewport(
            generation = generation, size = size, rows = rowList, cursor = cursor, modes = modes,
            historyRows = historyRows, viewportTop = viewportTop, full = full, links = links, selection = selection,
            held = held,
        )
    }

    fun decodeEffects(bytes: ByteArray): List<TerminalEffect> {
        val r = open(bytes, KIND_EFFECTS)
        val count = r.count(MIN_EFFECT_BYTES)
        val effects = ArrayList<TerminalEffect>(minOf(count, MAX_PRESIZE))
        repeat(count) {
            effects.add(
                when (val tag = r.u8()) {
                    EFFECT_RESPONSE -> TerminalEffect.Response(r.byteArray())
                    EFFECT_INPUT -> TerminalEffect.Input(r.byteArray())
                    EFFECT_TITLE -> TerminalEffect.Title(r.string())
                    EFFECT_BELL -> TerminalEffect.Bell
                    EFFECT_CLIPBOARD -> {
                        val write = r.bool()
                        val text = if (r.bool()) r.string() else null
                        if (!write && text != null) fail("clipboard read request carries text")
                        TerminalEffect.ClipboardRequest(write, text)
                    }
                    else -> fail("unknown effect tag $tag")
                },
            )
        }
        r.end()
        return effects
    }

    fun decodeSelectedText(bytes: ByteArray): String {
        val r = open(bytes, KIND_SELECTED_TEXT)
        val text = r.string()
        r.end()
        return text
    }

    private fun open(bytes: ByteArray, kind: Int): Reader {
        if (bytes.size < HEADER_BYTES) fail("buffer of ${bytes.size} bytes has no envelope header")
        val r = Reader(bytes, 0, bytes.size)
        val magic = r.u32()
        if (magic != MAGIC.toLong()) fail("bad magic 0x${magic.toString(16)}")
        val abi = r.u16()
        if (abi != ABI) fail("unsupported codec ABI $abi")
        val actualKind = r.u16()
        if (actualKind != kind) fail("expected kind $kind, got $actualKind")
        val payload = r.u32()
        if (payload > MAX_PAYLOAD_BYTES) fail("payload of $payload bytes exceeds ${MAX_PAYLOAD_BYTES}")
        if (payload != (bytes.size - HEADER_BYTES).toLong()) {
            fail("payload length $payload but ${bytes.size - HEADER_BYTES} bytes follow the header")
        }
        return r
    }

    private fun fail(message: String): Nothing = throw TerminalCodecException(message)

    private class Reader(private val b: ByteArray, private var pos: Int, private val end: Int) {
        private fun need(n: Int) {
            if (n < 0 || end - pos < n) fail("truncated: need $n bytes at offset $pos, ${end - pos} left")
        }

        fun u8(): Int {
            need(1)
            return b[pos++].toInt() and 0xFF
        }

        fun u16(): Int {
            need(2)
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v
        }

        fun i32(): Int {
            need(4)
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun u32(): Long = i32().toLong() and 0xFFFF_FFFFL

        fun i64(): Long {
            val lo = u32()
            val hi = u32()
            return lo or (hi shl 32)
        }

        fun bool(): Boolean = when (val v = u8()) {
            0 -> false
            1 -> true
            else -> fail("boolean byte $v at offset ${pos - 1}")
        }

        /** Collection count; rejects counts that cannot fit in the remaining bytes. */
        fun count(minElementBytes: Int): Int {
            val n = u32()
            if (n * minElementBytes > end - pos) fail("impossible count $n at offset ${pos - 4}")
            return n.toInt()
        }

        private fun length(): Int {
            val n = u32()
            if (n > end - pos) fail("length $n exceeds the ${end - pos} remaining bytes")
            return n.toInt()
        }

        fun byteArray(): ByteArray {
            val n = length()
            val out = b.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun string(maxBytes: Int = Int.MAX_VALUE): String {
            val n = length()
            if (n > maxBytes) fail("string of $n bytes exceeds $maxBytes")
            val s = try {
                b.decodeToString(pos, pos + n, throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                throw TerminalCodecException("invalid UTF-8 at offset $pos", e)
            }
            pos += n
            return s
        }

        fun color(): Long {
            val c = i64()
            if (!TerminalColor.isValid(c)) fail("invalid colour 0x${c.toULong().toString(16)}")
            return c
        }

        fun cell(): TerminalCell {
            val text = string(MAX_CELL_TEXT_BYTES)
            val width = i32()
            if (width !in 0..2) fail("cell width $width")
            if (width == 0 && text.isNotEmpty()) fail("text in a width-0 cell")
            val fg = color()
            val bg = color()
            val flags = i32()
            if (flags and 0xFF.inv() != 0) fail("cell flags 0x${flags.toString(16)}")
            val underline = i32()
            if (underline !in Underline.NONE..Underline.DASHED) fail("underline $underline")
            return TerminalCell(text, width, CellStyle(fg, bg, flags, underline))
        }

        fun point(columns: Int, lastRow: Long): TerminalPoint {
            val row = i64()
            val column = i32()
            if (row !in 0..lastRow || column !in 0 until columns) fail("selection point ($row, $column) out of range")
            return TerminalPoint(row, column)
        }

        fun end() {
            if (pos != end) fail("${end - pos} trailing bytes")
        }
    }
}
