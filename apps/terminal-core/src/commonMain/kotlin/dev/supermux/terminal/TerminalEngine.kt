package dev.supermux.terminal

/**
 * One terminal emulator instance (a libghostty-vt terminal behind the st_* ABI).
 *
 * NOT thread-safe: callers serialize all calls (the future `TerminalSession` owns one engine on one
 * dispatcher). [close] releases native memory and is idempotent; any other call after it is an error.
 */
interface TerminalEngine : AutoCloseable {
    /** Parse pty output. [OutputOrigin.REPLAY] updates the screen but never queues effects. */
    fun feed(bytes: ByteArray, origin: OutputOrigin)
    /** Full reset (RIS); keeps size, colours and limits. */
    fun reset()
    fun resize(size: TerminalSize)
    /** Default colours + palette; remote OSC colour changes layer on top. */
    fun colors(colors: TerminalColors)
    /**
     * Current frame; dirty rows only unless [forceFull] (or nothing was acknowledged yet). While a
     * synchronized-output hold is active the captured frame is returned with
     * [TerminalViewport.held]; [breakHold] ends the hold first (the owner's timeout).
     */
    fun viewport(forceFull: Boolean = false, breakHold: Boolean = false): TerminalViewport
    /** The frame with [generation] was drawn; the next [viewport] is relative to it. */
    fun acknowledge(generation: Long)
    /** Scroll so absolute row [row] (0 = oldest history row) is at the top; clamps. */
    fun scrollTo(row: Long)
    fun key(key: TerminalKey)
    fun mouse(mouse: TerminalMouse)
    /**
     * Paste per the terminal's modes (bracketed paste, newline conversion). Returns false — and
     * sends nothing — when the text could inject commands (e.g. a newline without bracketed paste,
     * or the bracketed-paste end marker); the host may confirm with the user and call again with
     * [allowUnsafe] = true.
     */
    fun paste(text: String, allowUnsafe: Boolean = false): Boolean
    fun focus(focused: Boolean)
    /** Set (or clear with null) the active selection. */
    fun select(selection: TerminalSelection?)
    /** Plain text of the active selection ("" if none), unwrapped and trimmed like Ghostty's copy. */
    fun selectedText(): String
    /** Remove and return all queued effects, oldest first. */
    fun drainEffects(): List<TerminalEffect>
}

/**
 * Thrown by [createTerminalEngine] when this platform's native engine is not linked (the state of
 * every platform until the native bindings land), or the native library failed to load.
 */
class TerminalEngineUnavailableException(message: String = "native engine not linked yet", cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Create an engine. Throws [TerminalEngineUnavailableException] if no native engine is available. */
expect fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine
