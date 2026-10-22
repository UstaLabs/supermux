package dev.supermux.media

/** One ranged request's answer: [total] is the whole file's size, as the server states it. */
class RangeResult(val total: Long)

/**
 * Fetch bytes `from..to` (inclusive) of one file, handing each chunk to `sink` as it arrives (the
 * buffer is reused after `sink` returns). Null on any failure — bytes already handed over stay valid.
 */
typealias RangeDownload = suspend (from: Long, to: Long, sink: suspend (ByteArray, Int) -> Unit) -> RangeResult?

/**
 * Download a whole file through [download], [rangeBytes] at a time, into [append]. Each request
 * stays short whatever the HTTP engine's per-request timeout is, and a dropped one resumes at the
 * first byte that did not arrive. Gives up after [maxAttempts] requests in a row that brought nothing.
 *
 * [sizeHint] (the attachment's advertised size) only clamps the first request; completion is
 * always judged against the server's own total.
 */
internal suspend fun downloadInRanges(
    download: RangeDownload,
    rangeBytes: Long,
    sizeHint: Long?,
    append: suspend (ByteArray, Int) -> Unit,
    maxAttempts: Int = 3,
): Boolean {
    var offset = 0L
    var total: Long? = null
    var failures = 0
    while (true) {
        total?.let { if (offset >= it) return offset == it }
        val from = offset
        val limit = total ?: sizeHint?.takeIf { it > 0 }
        val end = from + rangeBytes - 1
        val to = if (limit != null) maxOf(from, minOf(end, limit - 1)) else end
        val result = download(from, to) { buf, n ->
            append(buf, n)
            offset += n
        }
        if (result == null) {
            // Bytes arrived before the drop: a slow link making progress, not a dead one.
            if (offset > from) failures = 0 else if (++failures >= maxAttempts) return false
            continue
        }
        total = result.total
        if (offset == from && offset < result.total) {
            if (++failures >= maxAttempts) return false
        } else {
            failures = 0
        }
    }
}
