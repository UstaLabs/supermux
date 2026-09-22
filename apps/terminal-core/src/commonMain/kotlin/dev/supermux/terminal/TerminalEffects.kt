package dev.supermux.terminal

/**
 * Side effects the engine queued since the last [TerminalEngine.drainEffects], in order.
 * - [Response]: bytes the terminal answers a LIVE query with (DSR, DA, DECRQM, …) — send to the pty.
 *   Never produced for [OutputOrigin.REPLAY] input.
 * - [Input]: bytes produced by local input ([TerminalEngine.key], [TerminalEngine.mouse],
 *   [TerminalEngine.paste], [TerminalEngine.focus]) — send to the pty.
 * - [Title]: OSC 0/2 window title. [Bell]: BEL (LIVE only).
 * - [ClipboardRequest]: OSC 52 (LIVE only); `write=true` carries the text to copy (null = clear),
 *   `write=false` asks to read the clipboard (the embedder decides whether to answer).
 */
sealed interface TerminalEffect {
    data class Response(val bytes: ByteArray) : TerminalEffect {
        override fun equals(other: Any?): Boolean = other is Response && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "Response(${bytes.contentToString()})"
    }

    data class Input(val bytes: ByteArray) : TerminalEffect {
        override fun equals(other: Any?): Boolean = other is Input && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "Input(${bytes.contentToString()})"
    }

    data class Title(val value: String) : TerminalEffect
    data object Bell : TerminalEffect
    data class ClipboardRequest(val write: Boolean, val text: String?) : TerminalEffect
}
