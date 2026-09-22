package dev.supermux.terminal

/**
 * JNI declarations for native/src/terminal_jni.c (lib`supermux_terminal_jni`), shared by Android
 * and the desktop JVM. A 1:1 view of the st_* ABI: handles are the u32 st_handle as [Int], byte
 * ranges are [ByteArray] (UTF-8 for text), every mutating call returns the st_status, and the
 * buffer readers return the envelope copy (native buffer already freed) with the status in
 * `status[0]`. Loading the library is the platform's job ([createTerminalEngine]).
 *
 * NOT thread-safe per handle: [NativeTerminalEngine] serializes every call on one handle.
 */
internal object NativeTerminal {
    @JvmStatic external fun abiVersion(): Int

    @JvmStatic external fun create(
        abiVersion: Int, columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int,
        historyLines: Int, historyBytes: Long, outHandle: IntArray,
    ): Int

    @JvmStatic external fun destroy(handle: Int): Int
    @JvmStatic external fun feed(handle: Int, data: ByteArray, origin: Int): Int
    @JvmStatic external fun reset(handle: Int): Int
    @JvmStatic external fun resize(handle: Int, columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Int
    @JvmStatic external fun colors(handle: Int, colors: LongArray): Int
    @JvmStatic external fun scrollTo(handle: Int, row: Long): Int

    @JvmStatic external fun select(
        handle: Int, hasSelection: Boolean, startRow: Long, startColumn: Int, endRow: Long, endColumn: Int,
    ): Int

    @JvmStatic external fun acknowledge(handle: Int, generation: Long): Int
    @JvmStatic external fun key(handle: Int, physicalCode: Int, text: ByteArray, modifiers: Int, action: Int): Int
    @JvmStatic external fun mouse(handle: Int, column: Int, row: Int, button: Int, modifiers: Int, action: Int): Int
    @JvmStatic external fun paste(handle: Int, text: ByteArray, flags: Int): Int
    @JvmStatic external fun focus(handle: Int, focused: Boolean): Int
    @JvmStatic external fun readViewport(handle: Int, flags: Int, status: IntArray): ByteArray?
    @JvmStatic external fun selectedText(handle: Int, status: IntArray): ByteArray?
    @JvmStatic external fun drainEffects(handle: Int, status: IntArray): ByteArray?

    /**
     * After the library is loaded: its st_* ABI must be [expected] and every JNI entry point this
     * class declares must resolve (a missing one surfaces as [UnsatisfiedLinkError] on first call).
     */
    fun verifyAbi(expected: Int, library: String) {
        val actual = try {
            abiVersion()
        } catch (e: UnsatisfiedLinkError) {
            throw TerminalEngineUnavailableException(
                "$library lacks the supermux JNI entry points", e, TerminalEngineUnavailableException.Reason.ABI_MISMATCH,
            )
        }
        if (actual != expected) {
            throw TerminalEngineUnavailableException(
                "$library implements st_* ABI $actual, this binding needs $expected",
                reason = TerminalEngineUnavailableException.Reason.ABI_MISMATCH,
            )
        }
    }
}
