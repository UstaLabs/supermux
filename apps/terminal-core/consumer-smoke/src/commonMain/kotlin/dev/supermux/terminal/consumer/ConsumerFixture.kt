package dev.supermux.terminal.consumer

import dev.supermux.terminal.CellFlags
import dev.supermux.terminal.OutputOrigin
import dev.supermux.terminal.TerminalColor
import dev.supermux.terminal.TerminalColors
import dev.supermux.terminal.TerminalEffect
import dev.supermux.terminal.TerminalEngine
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.Underline
import dev.supermux.terminal.createTerminalEngine

/**
 * The semantic fixture every consumer check runs, written against the PUBLISHED public API only
 * (commonMain of `dev.supermux.terminal:terminal-core`). It is in commonMain on purpose: compiling
 * it proves the published metadata/klib is usable from common code, not just from one platform.
 *
 * It is deliberately the same red-cell fixture terminal-core's own EngineContractTest uses, so a
 * green run here means the packaged native library behaves like the one the package tests.
 */
object ConsumerFixture {
    val size = TerminalSize(80, 24, 8, 16)

    /** 256-entry palette with a recognisable ANSI red (204,102,102) at index 1. */
    fun colors(): TerminalColors {
        val palette = List(TerminalColor.PALETTE_SIZE) { i -> TerminalColor.rgba(i, 255 - i, (i * 7) and 0xFF) }
            .toMutableList()
        palette[1] = TerminalColor.rgba(204, 102, 102)
        return TerminalColors(
            foreground = TerminalColor.rgb(0xDDDDDD),
            background = TerminalColor.rgb(0x111111),
            cursor = TerminalColor.rgb(0xFFCC00),
            palette = palette,
        )
    }

    /**
     * Create → drive → free one engine. Throws [IllegalStateException] on any mismatch; returns a
     * short description of what it observed (printed by the callers so the evidence is in the log).
     */
    fun run(): String {
        val engine = createTerminalEngine(size, TerminalLimits())
        try {
            val colors = colors()
            engine.colors(colors)

            // 1. Styled text through the real VT parser: SGR 31 resolves to OUR palette entry.
            engine.feed("\u001b[31mred\u001b[0m".encodeToByteArray(), OutputOrigin.LIVE)
            val full = engine.viewport(forceFull = true)
            check(full.full) { "first forced frame is not full" }
            check(full.size == size) { "frame size ${full.size} != $size" }
            check(full.rows.size == size.rows) { "full frame has ${full.rows.size} rows, want ${size.rows}" }
            val row0 = full.rows.first { it.index == 0 }.cells
            check(row0.size == size.columns) { "row 0 has ${row0.size} cells, want ${size.columns}" }
            check(row0.take(3).map { it.text } == listOf("r", "e", "d")) {
                "row 0 starts with ${row0.take(3).map { it.text }}, want [r, e, d]"
            }
            for (cell in row0.take(3)) {
                check(cell.width == 1) { "red cell width ${cell.width}" }
                check(cell.style.foreground == colors.palette[1]) {
                    "red cell fg ${cell.style.foreground}, want ${colors.palette[1]}"
                }
                check(cell.style.background == TerminalColor.DEFAULT) { "red cell bg is not DEFAULT" }
                check(cell.style.flags == CellFlags.NONE) { "red cell flags ${cell.style.flags}" }
                check(cell.style.underline == Underline.NONE) { "red cell underline ${cell.style.underline}" }
            }
            check(row0[3].text == "") { "cell 3 is '${row0[3].text}', want empty" }
            check(full.cursor.column == 3 && full.cursor.row == 0) { "cursor at ${full.cursor}" }

            // 2. Unicode width handling comes from the engine's own tables (uucode), not from Kotlin.
            engine.feed("\r\n世!".encodeToByteArray(), OutputOrigin.LIVE)
            engine.acknowledge(full.generation)
            val second = engine.viewport(forceFull = true)
            val wide = second.rows.first { it.index == 1 }.cells
            check(wide[0].text == "世" && wide[0].width == 2) { "wide cell ${wide[0]}" }
            check(wide[1].width == 0) { "spacer cell ${wide[1]}" }
            check(wide[2].text == "!") { "cell after the wide one is ${wide[2]}" }

            // 3. Device status report: a real reply produced by the engine (cross-checked against
            //    the cursor the frame reports, which is a different code path), and REPLAY suppression.
            engine.feed("\u001b[6n".encodeToByteArray(), OutputOrigin.REPLAY)
            check(engine.drainEffects().none { it is TerminalEffect.Response }) {
                "a REPLAY query produced a Response effect"
            }
            engine.feed("\u001b[6n".encodeToByteArray(), OutputOrigin.LIVE)
            val responses = engine.drainEffects().filterIsInstance<TerminalEffect.Response>()
            check(responses.size == 1) { "${responses.size} responses to CSI 6n, want 1" }
            val reply = responses[0].bytes.decodeToString()
            val expected = "\u001b[${second.cursor.row + 1};${second.cursor.column + 1}R"
            check(reply == expected) {
                "CSI 6n reply ${reply.replace("\u001b", "ESC")}, want ${expected.replace("\u001b", "ESC")}"
            }

            // 4. Resize keeps content and reports the new geometry.
            val smaller = TerminalSize(40, 10, 9, 18)
            engine.resize(smaller)
            val resized = engine.viewport(forceFull = true)
            check(resized.size == smaller) { "size after resize ${resized.size}" }
            check(resized.rows.size == 10 && resized.rows.all { it.cells.size == 40 }) { "resized grid is wrong" }
            check(resized.rows.first { it.index == 0 }.cells.take(3).map { it.text } == listOf("r", "e", "d")) {
                "content lost across resize"
            }
            return "red/wide/DSR/resize OK (frame gen ${resized.generation}, ${resized.rows.size}x${resized.size.columns})"
        } finally {
            // 5. Freeing is the consumer's job and must not throw; close() is idempotent.
            engine.close()
            engine.close()
        }
    }
}
