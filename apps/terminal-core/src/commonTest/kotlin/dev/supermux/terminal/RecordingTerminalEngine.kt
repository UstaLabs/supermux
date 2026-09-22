package dev.supermux.terminal

/**
 * A recording [TerminalEngine] for the [TerminalSession] suites: it records every call in order and
 * models just enough of the real engine's contract to make the session's cadence observable —
 *
 * - a text screen (so "feed A, reset, feed B" can be asserted to show only B),
 * - generations plus dirty-row accumulation with the native acknowledgement rule (an ack cleans
 *   ONLY the most recently serialized frame; dirt keeps accumulating until then),
 * - synchronized output (mode 2026): [SYNC_BEGIN] captures the frame and holds it until the program
 *   ends the hold or the reader passes `breakHold`,
 * - effects (BEL → [TerminalEffect.Bell], local input → [TerminalEffect.Input]).
 *
 * It is NOT a terminal emulator; EngineContractTest covers the real one.
 */
class RecordingTerminalEngine(private var size: TerminalSize) : TerminalEngine {
    /** Every call except drainEffects, in order, e.g. `feed(LIVE,"hi")`, `reset`, `ack(3)`. */
    val calls = mutableListOf<String>()

    var closeCount = 0
        private set
    val isClosed: Boolean get() = closeCount > 0

    var viewportReads = 0
        private set
    var breakHoldReads = 0
        private set

    private val lines = MutableList(size.rows) { StringBuilder() }
    private var cursorRow = 0
    private var cursorColumn = 0

    private var generation = 0L
    private val dirtyRows = mutableSetOf<Int>()
    private var serializedRows: Set<Int> = emptySet()
    private var serializedGeneration = -1L
    private var serializedFull = false
    private var fullAcknowledged = false

    private var holdActive = false
    private var heldGeneration = -1L
    private var heldSnapshot: List<String> = emptyList()

    private val effects = ArrayDeque<TerminalEffect>()
    private var selection: TerminalSelection? = null

    /** The whole screen, trailing blanks trimmed. */
    fun screenText(): String = lines.joinToString("\n") { it.toString().trimEnd() }.trimEnd('\n')

    /** Only the calls that mutate the terminal (feed/reset/resize/…), for ordering assertions. */
    fun mutations(): List<String> = calls.filter { call ->
        MUTATING.any { call == it || call.startsWith("$it(") }
    }

    override fun feed(bytes: ByteArray, origin: OutputOrigin) {
        var text = bytes.decodeToString()
        calls += "feed(${origin.name},${text.readable()})"
        if (text.contains(SYNC_BEGIN)) {
            text = text.replace(SYNC_BEGIN, "")
            beginHold()
        }
        var endsHold = false
        if (text.contains(SYNC_END)) {
            text = text.replace(SYNC_END, "")
            endsHold = true
        }
        for (ch in text) {
            when (ch) {
                '\n' -> {
                    cursorRow = minOf(cursorRow + 1, size.rows - 1)
                    cursorColumn = 0
                    dirtyRows += cursorRow
                }
                '\u0007' -> if (origin == OutputOrigin.LIVE) effects.addLast(TerminalEffect.Bell)
                else -> {
                    val line = lines[cursorRow]
                    while (line.length < cursorColumn) line.append(' ')
                    if (cursorColumn < line.length) line[cursorColumn] = ch else line.append(ch)
                    cursorColumn++
                    dirtyRows += cursorRow
                }
            }
        }
        if (endsHold) holdActive = false
        generation++
    }

    private fun beginHold() {
        holdActive = true
        generation++
        heldGeneration = generation
        heldSnapshot = lines.map { it.toString() }
    }

    override fun reset() {
        calls += "reset"
        lines.forEach { it.clear() }
        cursorRow = 0
        cursorColumn = 0
        dirtyRows += lines.indices
        holdActive = false
        fullAcknowledged = false
        generation++
    }

    override fun resize(size: TerminalSize) {
        calls += "resize(${size.columns}x${size.rows})"
        while (lines.size < size.rows) lines.add(StringBuilder())
        while (lines.size > size.rows) lines.removeLast()
        this.size = size
        cursorRow = minOf(cursorRow, size.rows - 1)
        dirtyRows += lines.indices
        holdActive = false
        fullAcknowledged = false
        generation++
    }

    override fun colors(colors: TerminalColors) {
        calls += "colors"
        fullAcknowledged = false
        generation++
    }

    override fun viewport(forceFull: Boolean, breakHold: Boolean): TerminalViewport {
        calls += "viewport(full=$forceFull,break=$breakHold)"
        viewportReads++
        if (breakHold) {
            breakHoldReads++
            if (holdActive) {
                holdActive = false
                generation++
            }
        }
        if (holdActive) {
            serializedGeneration = heldGeneration
            serializedFull = true
            serializedRows = lines.indices.toSet()
            return frame(heldSnapshot, serializedRows, heldGeneration, full = true, held = true)
        }
        val pending = serializedRows + dirtyRows
        dirtyRows.clear()
        val full = forceFull || !fullAcknowledged
        serializedFull = full
        serializedRows = if (full) lines.indices.toSet() else pending
        serializedGeneration = generation
        return frame(lines.map { it.toString() }, serializedRows, generation, full, held = false)
    }

    override fun acknowledge(generation: Long) {
        calls += "ack($generation)"
        // Mirrors ST_ERR_INVALID_ARGUMENT for a generation that was never serialized.
        require(generation == serializedGeneration) {
            "ack($generation) but the last serialized frame is $serializedGeneration"
        }
        serializedRows = emptySet()
        if (serializedFull) fullAcknowledged = true
    }

    override fun scrollTo(row: Long) {
        calls += "scrollTo($row)"
        generation++
    }

    override fun key(key: TerminalKey) {
        calls += "key(${key.physicalCode},${key.text.readable()})"
        if (key.text.isNotEmpty()) effects.addLast(TerminalEffect.Input(key.text.encodeToByteArray()))
        generation++
    }

    override fun mouse(mouse: TerminalMouse) {
        calls += "mouse(${mouse.column},${mouse.row},${mouse.action})"
        generation++
    }

    override fun paste(text: String, allowUnsafe: Boolean): Boolean {
        calls += "paste(${text.readable()},$allowUnsafe)"
        // Mirrors the engine's unsafe-paste rule closely enough to test the reply plumbing.
        if (text.contains('\n') && !allowUnsafe) return false
        effects.addLast(TerminalEffect.Input(text.encodeToByteArray()))
        generation++
        return true
    }

    override fun focus(focused: Boolean) {
        calls += "focus($focused)"
        generation++
    }

    override fun select(selection: TerminalSelection?) {
        calls += "select(${selection != null})"
        this.selection = selection
        generation++
    }

    override fun selectedText(): String {
        calls += "selectedText"
        return selection?.let { "${it.start.row}:${it.start.column}-${it.end.row}:${it.end.column}" } ?: ""
    }

    override fun drainEffects(): List<TerminalEffect> {
        if (effects.isEmpty()) return emptyList()
        val drained = effects.toList()
        effects.clear()
        return drained
    }

    override fun close() {
        calls += "close"
        closeCount++
    }

    private fun frame(
        source: List<String>,
        rows: Set<Int>,
        generation: Long,
        full: Boolean,
        held: Boolean,
    ) = TerminalViewport(
        generation = generation,
        size = size,
        rows = rows.sorted().map { index -> TerminalRow(index, cellsOf(source.getOrElse(index) { "" })) },
        cursor = TerminalCursor(cursorColumn, cursorRow, CursorShape.BLOCK, visible = true),
        modes = TerminalModes(alternateScreen = false, mouseTracking = false, bracketedPaste = false),
        historyRows = 0,
        viewportTop = 0,
        full = full,
        links = emptyList(),
        selection = selection,
        held = held,
    )

    private fun cellsOf(line: String): List<TerminalCell> = List(size.columns) { column ->
        val text = if (column < line.length) line[column].toString() else ""
        TerminalCell(text, 1, CellStyle(TerminalColor.DEFAULT, TerminalColor.DEFAULT, CellFlags.NONE, Underline.NONE))
    }

    companion object {
        /** DECSET 2026: begin synchronized output. */
        const val SYNC_BEGIN: String = "\u001b[?2026h"

        /** DECRST 2026: end synchronized output. */
        const val SYNC_END: String = "\u001b[?2026l"

        private val MUTATING = listOf("feed", "reset", "resize", "colors", "scrollTo", "key", "mouse", "paste", "focus", "select")

        private fun String.readable(): String =
            replace("\u001b", "\\e").replace("\n", "\\n").replace("\u0007", "\\a")
    }
}

/** Text of the row with [index] in a published frame, or null when the frame does not carry it. */
fun TerminalViewport.rowTextOrNull(index: Int): String? =
    rows.firstOrNull { it.index == index }?.cells?.joinToString("") { it.text }?.trimEnd()
