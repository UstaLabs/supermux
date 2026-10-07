package dev.supermux.terminal

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/** The JS `Uint8Array` (kotlinx-browser is deliberately not a dependency of this package). */
internal external class Uint8Array : JsAny {
    val length: Int
}

/**
 * A TerminalRuntime instance of terminal-loader.mjs: one wasm instance and its st_* handle table.
 * Handles cross as the u32 bit pattern in an Int; Kotlin [Long] crosses as a JS BigInt (u64/i64
 * parameters). Byte ranges are owned Uint8Array copies in both directions.
 */
internal external interface LoaderRuntime : JsAny {
    fun abiVersion(): Int
    fun create(
        columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int, historyLines: Int, historyBytes: Long,
    ): CreateResult
    fun destroy(handle: Int): Int
    fun feed(handle: Int, bytes: Uint8Array, origin: Int): Int
    fun reset(handle: Int): Int
    fun resize(handle: Int, columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Int
    fun colors(handle: Int, bytes: Uint8Array): Int
    fun scrollTo(handle: Int, row: Long): Int
    fun select(handle: Int, has: Int, startRow: Long, startColumn: Int, endRow: Long, endColumn: Int): Int
    fun acknowledge(handle: Int, generation: Long): Int
    fun key(handle: Int, physicalCode: Int, text: Uint8Array, modifiers: Int, action: Int): Int
    fun mouse(handle: Int, column: Int, row: Int, button: Int, modifiers: Int, action: Int): Int
    fun paste(handle: Int, text: Uint8Array, flags: Int): Int
    fun focus(handle: Int, focused: Boolean): Int
    fun readViewport(handle: Int, flags: Int): BufferResult
    fun selectedText(handle: Int): BufferResult
    fun drainEffects(handle: Int): BufferResult

    fun memoryBytes(): Double
    fun liveBuffers(): Int
    fun growMemory(bytes: Int): Boolean
}

internal external interface CreateResult : JsAny {
    val status: Int
    val handle: Int
}

internal external interface BufferResult : JsAny {
    val status: Int
    val bytes: Uint8Array?
}

// ---------------------------------------------------------------- byte copies ----
// Kotlin/Wasm arrays live in the GC heap, not in linear memory, so there is no zero-copy view;
// copying moves four bytes per boundary crossing.

private fun newUint8Array(length: Int): Uint8Array = js("new Uint8Array(length)")

private fun u8Get(a: Uint8Array, i: Int): Int = js("a[i]")

private fun u8Get32(a: Uint8Array, i: Int): Int = js("a[i] | (a[i + 1] << 8) | (a[i + 2] << 16) | (a[i + 3] << 24)")

private fun u8Set(a: Uint8Array, i: Int, v: Int) {
    js("a[i] = v")
}

private fun u8Set32(a: Uint8Array, i: Int, v: Int) {
    js("a[i] = v; a[i + 1] = v >>> 8; a[i + 2] = v >>> 16; a[i + 3] = v >>> 24")
}

internal fun ByteArray.toUint8Array(): Uint8Array {
    val out = newUint8Array(size)
    var i = 0
    val end4 = size and 3.inv()
    while (i < end4) {
        u8Set32(
            out, i,
            (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8) or
                ((this[i + 2].toInt() and 0xFF) shl 16) or (this[i + 3].toInt() shl 24),
        )
        i += 4
    }
    while (i < size) {
        u8Set(out, i, this[i].toInt())
        i++
    }
    return out
}

internal fun Uint8Array.toByteArray(): ByteArray {
    val n = length
    val out = ByteArray(n)
    var i = 0
    val end4 = n and 3.inv()
    while (i < end4) {
        val v = u8Get32(this, i)
        out[i] = v.toByte()
        out[i + 1] = (v ushr 8).toByte()
        out[i + 2] = (v ushr 16).toByte()
        out[i + 3] = (v ushr 24).toByte()
        i += 4
    }
    while (i < n) {
        out[i] = u8Get(this, i).toByte()
        i++
    }
    return out
}

// ---------------------------------------------------------------- errors / promises ----

private fun jsErrorReason(e: JsAny): String? = js("(e && typeof e.reason === 'string') ? e.reason : null")

private fun jsErrorMessage(e: JsAny): String = js("String((e && e.message) || e)")

/** A loader rejection → the typed startup error (unknown reasons become INITIALIZATION_FAILED). */
internal fun loadFailure(error: JsAny?): TerminalEngineUnavailableException {
    if (error == null) {
        return TerminalEngineUnavailableException(
            "terminal wasm runtime failed to load", reason = TerminalEngineUnavailableException.Reason.INITIALIZATION_FAILED,
        )
    }
    val reason = TerminalEngineUnavailableException.Reason.entries.firstOrNull { it.name == jsErrorReason(error) }
        ?: TerminalEngineUnavailableException.Reason.INITIALIZATION_FAILED
    return TerminalEngineUnavailableException("terminal wasm runtime: ${jsErrorMessage(error)}", reason = reason)
}

/**
 * Await a loader promise; a rejection becomes [loadFailure].
 *
 * Cancellable FROM THE CALLER'S SIDE: `withTimeout`/`cancel` around it resumes the caller with a
 * [kotlinx.coroutines.CancellationException] at once. A JS promise cannot be aborted, so the shared
 * load keeps running (and the next caller reuses its result) — the settle handlers simply find the
 * continuation no longer active and drop the value. Without this, a cancelled caller would hang
 * until the network settled, which is exactly what `TerminalRuntime.initialize`'s contract forbids.
 */
internal suspend fun <T : JsAny?> Promise<T>.awaitLoad(): T = suspendCancellableCoroutine { cont ->
    then(
        { value -> if (cont.isActive) cont.resume(value); null },
        { error -> if (cont.isActive) cont.resumeWithException(loadFailure(error)); null },
    )
}
