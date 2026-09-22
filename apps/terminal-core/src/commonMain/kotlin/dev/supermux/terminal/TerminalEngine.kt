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
 * Thrown by [createTerminalEngine] when no engine can be started; [reason] says why. Nothing is
 * left half-created when it is thrown. A load failure is sticky for the process (the native
 * library is loaded once), so retrying does not help.
 */
class TerminalEngineUnavailableException(
    message: String = "native engine not linked yet",
    cause: Throwable? = null,
    val reason: Reason = Reason.NOT_LINKED,
) : IllegalStateException(message, cause) {
    enum class Reason {
        /** This platform has no native binding yet (browser until the wasm loader lands). */
        NOT_LINKED,
        /** The OS/CPU combination has no packaged native library (e.g. JVM on linux-riscv64). */
        UNSUPPORTED_PLATFORM,
        /** The native library for this platform is not packaged (jar resource / jniLibs missing). */
        MISSING_BINARY,
        /** The packaged library does not match its manifest (sha256/size), or could not be extracted. */
        CORRUPT_BINARY,
        /** The library implements a different st_* ABI version (or lacks the expected JNI entry points). */
        ABI_MISMATCH,
        /** The library was found but failed to load or to create a terminal (dlopen error, table full, OOM). */
        INITIALIZATION_FAILED,
    }
}

/**
 * A native engine call failed in a way the caller cannot prevent: an effect had to be dropped
 * because the queue or an envelope hit its limit ([status] `ST_ERR_LIMIT`), an allocation failed
 * (`ST_ERR_OUT_OF_MEMORY`) or Ghostty reported an internal error (`ST_ERR_INTERNAL`). The engine
 * stays usable; for feed() the screen was updated regardless.
 */
class TerminalNativeException(val status: Int, message: String) : RuntimeException(message)

/** Create an engine. Throws [TerminalEngineUnavailableException] if no native engine is available. */
expect fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine
