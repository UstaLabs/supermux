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
 * Thrown by [createTerminalEngine] (and, in the browser, [TerminalRuntime.initialize]) when no
 * engine can be started; [reason] says why. Nothing is left half-created when it is thrown. On
 * Android, the JVM and iOS a load failure is sticky for the process (the native library is loaded
 * once), so retrying does not help; in the browser a failed [TerminalRuntime.initialize] may be
 * retried (e.g. after a network error).
 */
class TerminalEngineUnavailableException(
    message: String = "native engine not linked yet",
    cause: Throwable? = null,
    val reason: Reason = Reason.NOT_LINKED,
) : IllegalStateException(message, cause) {
    enum class Reason {
        /** This platform has no native binding. */
        NOT_LINKED,
        /**
         * Browser only: [createTerminalEngine] was called before [TerminalRuntime.initialize]
         * completed (the wasm module loads asynchronously).
         */
        NOT_INITIALIZED,
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

/**
 * Create an engine. Throws [TerminalEngineUnavailableException] if no native engine is available
 * (in the browser: reason [TerminalEngineUnavailableException.Reason.NOT_INITIALIZED] until
 * [TerminalRuntime.initialize] has completed).
 */
expect fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine

/**
 * Process-wide engine runtime.
 *
 * In the browser the engine is a WebAssembly module that must be fetched and compiled
 * asynchronously, so a host app awaits [initialize] once (e.g. during app start) before the first
 * [createTerminalEngine]. On Android, the desktop JVM and iOS the native library is loaded lazily
 * by the first [createTerminalEngine] and [initialize] is a no-op, so shared code may call it
 * unconditionally.
 */
expect object TerminalRuntime {
    /**
     * Browser: fetch, compile and instantiate the engine module once. [wasmUrl] is the URL of
     * `supermux-terminal.wasm` as configured by the HOST APP (never user input); null uses the
     * package default, the file next to `terminal-loader.mjs` (bundlers rewrite it to the emitted,
     * content-hashed asset). Idempotent: later calls with no URL or the same URL return at once;
     * a different URL after a successful load fails. Throws [TerminalEngineUnavailableException]
     * with reason MISSING_BINARY (fetch failed / HTTP error), CORRUPT_BINARY (not a wasm module),
     * ABI_MISMATCH (wrong st_* ABI or exports) or INITIALIZATION_FAILED (bad URL, instantiation
     * failed, already initialized from another URL). A failed load is not cached: calling again
     * retries. Cancelling the caller does not abort the shared load (it completes and is reused by
     * the next call); no terminal handle is created by [initialize], so nothing can leak.
     *
     * Android / JVM / iOS: no-op ([wasmUrl] is ignored).
     */
    suspend fun initialize(wasmUrl: String? = null)
}
