package dev.supermux.terminal

/** Fixtures shared by the commonTest suites (engine contract, types, codec). */
object TestFixtures {
    /** 256-entry palette with a recognisable ANSI red (204,102,102) at index 1. */
    fun fixtureColors(): TerminalColors {
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
}
