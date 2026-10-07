package dev.supermux.net

import org.khronos.webgl.Uint8Array

/** zlib's Z_SYNC_FLUSH — flush what the current input produced instead of waiting for stream end. */
internal const val Z_SYNC_FLUSH = 2

/** The subset of pako 2.x this module uses: a streaming zlib inflater. */
@JsModule("pako")
external object Pako {
    // `: JsAny` is NOT redundant: without it the class is not a JsAny subtype and cannot be passed
    // to [takeOutput]'s `js(...)` body.
    class Inflate : JsAny {
        /** Feed a chunk; `flush` is a zlib flush mode, see [Z_SYNC_FLUSH]. */
        fun push(data: Uint8Array, flush: Int): Boolean
        val result: Uint8Array?
        val err: Int
        val msg: String?
    }
}
