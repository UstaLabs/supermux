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
 */
class BlobChunkSource(private val blob: Blob) : ChunkSource {
    override val size: Long get() = blob.size.toDouble().toLong()

    override fun read(offset: Long, len: Int): ByteArray {
        val total = size
        if (offset >= total || len <= 0) return ByteArray(0)
        val end = minOf(offset + len, total)
        val slice = blob.slice(offset.toInt(), end.toInt())
        val url = URL.createObjectURL(slice)
        try {
            val xhr = XMLHttpRequest()
            xhr.open("GET", url, async = false)
            xhr.overrideMimeType("text/plain; charset=x-user-defined")
            xhr.send()
            val text = xhr.responseText
            val out = ByteArray(text.length)
            for (i in text.indices) out[i] = (text[i].code and 0xFF).toByte()
            return out
        } finally {
            URL.revokeObjectURL(url)
        }
    }
}
