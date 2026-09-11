package dev.supermux.net

import org.khronos.webgl.Uint8Array

/** The subset of pako 2.x this module uses: a streaming zlib inflater. */
@JsModule("pako")
external object Pako {
    class Inflate : JsAny {
        /** Feed a chunk; `flush` 2 = Z_SYNC_FLUSH so output is produced without waiting for stream end. */
        fun push(data: Uint8Array, flush: Int): Boolean
        val result: Uint8Array?
        val err: Int
        val msg: String?
    }
}
