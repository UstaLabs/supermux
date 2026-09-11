package dev.supermux.net

import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.xhr.XMLHttpRequest

/**
 * A [ChunkSource] over a browser [Blob]/`File` that reads on demand, so a 500 MB video never sits
 * in the wasm heap.
 *
 * `ChunkSource.read` is synchronous by contract (the resumable uploader calls it per 5 MB chunk).
 * The only synchronous byte read the main thread has is a sync `XMLHttpRequest` against an object
 * URL, so each read slices the blob, mints a URL for the slice, fetches it as
 * `x-user-defined` text (one char per byte) and revokes the URL. Bounded RAM: one slice at a time.
 *
 * Every failure throws: a short or empty read that returned quietly would be uploaded as a
 * truncated file that the broker then reports as a successful upload.
 */
class BlobChunkSource(private val blob: Blob) : ChunkSource {
    init {
        // `Blob.slice` takes Int offsets, so a >2 GiB file cannot be addressed at all — say so here
        // rather than silently reading the wrong bytes from a wrapped offset.
        require(blob.size.toDouble() <= Int.MAX_VALUE) {
            "BlobChunkSource: files over 2 GiB are not supported in the browser"
        }
    }

    override val size: Long get() = blob.size.toDouble().toLong()

    override fun read(offset: Long, len: Int): ByteArray {
        val total = size
        if (offset >= total || len <= 0) return ByteArray(0)
        val end = minOf(offset + len, total)
        val expected = (end - offset).toInt()
        val slice = blob.slice(offset.toInt(), end.toInt())
        val url = URL.createObjectURL(slice)
        try {
            val xhr = XMLHttpRequest()
            xhr.open("GET", url, async = false)
            xhr.overrideMimeType("text/plain; charset=x-user-defined")
            xhr.send()
            // A blob: URL always answers 200; anything else means the slice never arrived.
            if (xhr.status.toInt() != 200) {
                error("BlobChunkSource: XHR ${xhr.status} reading $offset..$end")
            }
            val text = xhr.responseText
            if (text.length != expected) {
                error("BlobChunkSource: short read at $offset..$end — got ${text.length} of $expected bytes")
            }
            val out = ByteArray(expected)
            // x-user-defined maps bytes ≥ 0x80 to U+F780–U+F7FF, so the low byte is the real one.
            for (i in text.indices) out[i] = (text[i].code and 0xFF).toByte()
            return out
        } finally {
            URL.revokeObjectURL(url)
        }
    }
}
