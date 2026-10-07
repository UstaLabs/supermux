package dev.supermux.terminal

/**
 * st_* status codes (native/include/supermux_terminal.h) and their mapping to Kotlin exceptions,
 * shared by every native binding (JNI, cinterop) so all platforms fail identically.
 */
internal object NativeStatus {
    const val ABI_VERSION: Int = 2

    const val OK: Int = 0
    const val INVALID_HANDLE: Int = -1
    const val ABI_MISMATCH: Int = -2
    const val INVALID_ARGUMENT: Int = -3
    const val OUT_OF_MEMORY: Int = -4
    const val LIMIT: Int = -5
    const val REJECTED: Int = -6
    const val INTERNAL: Int = -7

    const val READ_FORCE_FULL: Int = 1
    const val READ_BREAK_HOLD: Int = 2
    const val PASTE_ALLOW_UNSAFE: Int = 1

    fun name(status: Int): String = when (status) {
        OK -> "ST_OK"
        INVALID_HANDLE -> "ST_ERR_INVALID_HANDLE"
        ABI_MISMATCH -> "ST_ERR_ABI_MISMATCH"
        INVALID_ARGUMENT -> "ST_ERR_INVALID_ARGUMENT"
        OUT_OF_MEMORY -> "ST_ERR_OUT_OF_MEMORY"
        LIMIT -> "ST_ERR_LIMIT"
        REJECTED -> "ST_ERR_REJECTED"
        INTERNAL -> "ST_ERR_INTERNAL"
        else -> "status $status"
    }

    /** Throw for any status but [OK]. */
    fun check(status: Int, op: String) {
        if (status == OK) return
        val message = "$op failed: ${name(status)}"
        throw when (status) {
            INVALID_ARGUMENT -> IllegalArgumentException(message)
            // The binding never passes a stale handle (closed flag under the engine lock).
            INVALID_HANDLE -> IllegalStateException(message)
            else -> TerminalNativeException(status, message)
        }
    }

    /** st_create failure → typed startup error (nothing was created). */
    fun createFailure(status: Int): Throwable = when (status) {
        INVALID_ARGUMENT -> IllegalArgumentException("st_create failed: ${name(status)}")
        ABI_MISMATCH -> TerminalEngineUnavailableException(
            "native library rejected st_* ABI $ABI_VERSION", reason = TerminalEngineUnavailableException.Reason.ABI_MISMATCH,
        )
        else -> TerminalEngineUnavailableException(
            "st_create failed: ${name(status)}", reason = TerminalEngineUnavailableException.Reason.INITIALIZATION_FAILED,
        )
    }

    fun readFlags(forceFull: Boolean, breakHold: Boolean): Int =
        (if (forceFull) READ_FORCE_FULL else 0) or (if (breakHold) READ_BREAK_HOLD else 0)

    /** TerminalColors → the 259 u64 values st_colors takes: fg, bg, cursor, palette[256]. */
    fun colorArray(colors: TerminalColors): LongArray {
        val out = LongArray(3 + TerminalColor.PALETTE_SIZE)
        out[0] = colors.foreground
        out[1] = colors.background
        out[2] = colors.cursor
        colors.palette.forEachIndexed { i, c -> out[3 + i] = c }
        return out
    }
}
