package dev.supermux.net

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * pako-backed zlib inflater. pako's streaming `Inflate` buffers each produced block; every [feed]
 * drains those blocks into a queue and [inflate] hands them out one call at a time — exactly the
 * "call until empty" contract the RFB ZRLE decoder relies on.
 */
actual class ZlibInflater actual constructor() {
    private val pending = ArrayDeque<ByteArray>()
    private var closed = false
    private val inflater = Pako.Inflate()

    actual fun feed(input: ByteArray) {
        if (closed || input.isEmpty()) return
        val ok = inflater.push(input.toUint8Array(), Z_SYNC_FLUSH)
        if (!ok && inflater.err != 0) {
            println("[ZlibInflater] pako error ${inflater.err}: ${inflater.msg}")
            return
        }
        val produced = takeOutput(inflater)
        if (produced != null && produced.length > 0) pending.addLast(produced.toByteArray())
    }

    actual fun inflate(): ByteArray = if (closed) ByteArray(0) else pending.removeFirstOrNull() ?: ByteArray(0)

    actual fun close() {
        closed = true
        pending.clear()
    }

    private companion object {
        const val Z_SYNC_FLUSH = 2
    }
}

/**
 * Drain the output pako produced so far and reset its buffers, so each [ZlibInflater.feed] yields
 * only the bytes that push produced.
 *
 * pako's default `onData` appends every produced block to `inflater.chunks` and only flattens them
 * into `inflater.result` when the stream ENDS — a ZRLE stream never ends, so mid-stream output has
 * to be taken from `chunks`. Written as a JS snippet because it mutates fields the external
 * declaration deliberately does not expose.
 */
@Suppress("UNUSED_PARAMETER")
private fun takeOutput(inflater: JsAny): Uint8Array? = js(
    """(function () {
        var chunks = inflater.chunks;
        if (chunks && chunks.length) {
            var total = 0, i;
            for (i = 0; i < chunks.length; i++) total += chunks[i].length;
            var out = new Uint8Array(total), off = 0;
            for (i = 0; i < chunks.length; i++) { out.set(chunks[i], off); off += chunks[i].length; }
            inflater.chunks = [];
            inflater.result = null;
            return out;
        }
        var r = inflater.result;
        inflater.result = null;
        return (r && r.length) ? r : null;
    })()""",
)

internal fun ByteArray.toUint8Array(): Uint8Array {
    val out = Uint8Array(size)
    for (i in indices) out[i] = this[i]
    return out
}

internal fun Uint8Array.toByteArray(): ByteArray {
    val out = ByteArray(length)
    for (i in 0 until length) out[i] = this[i]
    return out
}
