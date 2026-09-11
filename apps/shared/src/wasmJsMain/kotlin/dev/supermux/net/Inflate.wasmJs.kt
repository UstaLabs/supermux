package dev.supermux.net

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toUByteArray
import org.khronos.webgl.toUint8Array

/**
 * pako-backed zlib inflater.
 *
 * pako documents an `onData(chunk)` hook for streaming output, but it cannot be used from
 * Kotlin/Wasm: a Kotlin lambda is not a `JsAny`, so it cannot be assigned to a property of an
 * external class. Output is therefore drained out of the inflater's own buffers after each push
 * (see [takeOutput]).
 *
 * The draining is one array per [feed], not one array per internal 64 KiB block: a single [feed]
 * can hand back more than 64 KB, where the JVM and Apple actuals cap each [inflate] at their output
 * buffer. That is still within the `expect` contract — [inflate] returns the newly produced bytes
 * and the caller loops until it gets an empty array — and it is what the RFB ZRLE decoder wants,
 * since it consumes a whole rect's worth of bytes at a time.
 */
@OptIn(ExperimentalUnsignedTypes::class)
actual class ZlibInflater actual constructor() {
    private val pending = ArrayDeque<ByteArray>()
    private var closed = false
    private val inflater = Pako.Inflate()

    actual fun feed(input: ByteArray) {
        if (closed || input.isEmpty()) return
        val ok = inflater.push(input.asUByteArray().toUint8Array(), Z_SYNC_FLUSH)
        // A ZRLE connection shares ONE zlib stream for its whole life, so a failed push means every
        // later byte is garbage too. Fail loudly, exactly as the JVM/Apple actuals do.
        // `push` also returns false forever once the stream has ENDED (err stays 0), which for a
        // never-ending RFB stream is just as fatal — so any false is an error.
        if (!ok) {
            throw IllegalStateException("pako inflate failed: ${inflater.err} ${inflater.msg ?: "(stream ended)"}")
        }
        val produced = takeOutput(inflater)
        if (produced != null && produced.length > 0) pending.addLast(produced.toUByteArray().asByteArray())
    }

    actual fun inflate(): ByteArray = if (closed) ByteArray(0) else pending.removeFirstOrNull() ?: ByteArray(0)

    actual fun close() {
        closed = true
        pending.clear()
    }
}

/**
 * Take everything the last push produced and leave the inflater ready for the next one.
 *
 * pako only moves output into `chunks` when its 64 KiB window fills (`avail_out === 0`) or the
 * stream ends — Z_SYNC_FLUSH does NOT change that for `Inflate` — so a typical ZRLE rect leaves its
 * bytes sitting in `strm.output[0 until next_out)` and `chunks` empty. This collects both: the
 * finished chunks, then the partial window. Setting `avail_out = 0` makes the next push allocate a
 * fresh output buffer, so the partial window is never handed out twice; the `avail_out !== 0` guard
 * covers the mirror case, a full window pako already flushed into `chunks`.
 *
 * Written as a JS snippet because it reads and writes fields the external declaration deliberately
 * does not expose.
 */
@Suppress("UNUSED_PARAMETER")
private fun takeOutput(inflater: JsAny): Uint8Array? = js(
    """(function () {
        var parts = inflater.chunks ? inflater.chunks.splice(0) : [];
        var s = inflater.strm;
        if (s && s.next_out && s.avail_out !== 0) {
            parts.push(s.output.slice(0, s.next_out));
            s.avail_out = 0;
        }
        // On Z_STREAM_END pako's onEnd has already flattened `chunks` into `result` and cleared
        // `chunks`, so the final block lives only there. Never reached by a live RFB stream.
        var r = inflater.result;
        if (r && r.length) parts.push(r);
        inflater.result = null;
        if (parts.length === 0) return null;
        if (parts.length === 1) return parts[0];
        var total = 0, i;
        for (i = 0; i < parts.length; i++) total += parts[i].length;
        var out = new Uint8Array(total), off = 0;
        for (i = 0; i < parts.length; i++) { out.set(parts[i], off); off += parts[i].length; }
        return out;
    })()""",
)
