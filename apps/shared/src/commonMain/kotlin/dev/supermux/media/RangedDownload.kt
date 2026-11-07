package dev.supermux.media

/** One ranged request's answer: [total] is the whole file's size, as the server states it. */
class RangeResult(val total: Long)

/**
 * Fetch bytes `from..to` (inclusive) of one file, handing each chunk to `sink` as it arrives (the
 * buffer is reused after `sink` returns). Null on any failure — bytes already handed over stay valid.
 */
typealias RangeDownload = suspend (from: Long, to: Long, sink: suspend (ByteArray, Int) -> Unit) -> RangeResult?

/**
 * How [downloadInRanges] ended. [Failed] leaves a true prefix of the file behind, so trying again can
 * continue it. [Overlong] means the bytes already held run past the file's end: they cannot be its
 * prefix, so continuing is pointless and only throwing them away and starting over can work.
 */
internal enum class RangeOutcome { Complete, Failed, Overlong }

/**
 * Download a whole file through [download], [rangeBytes] at a time, into [append]. Each request
 * stays short whatever the HTTP engine's per-request timeout is, and a dropped one resumes at the
 * first byte that did not arrive. Gives up ([RangeOutcome.Failed]) after [maxAttempts] requests in a
 * row that brought nothing.
 *
 * [sizeHint] (the attachment's advertised size) only clamps the first request; completion is
 * always judged against the server's own total.
 *
 * [startAt] is how much of the file is already stored — a partial an earlier attempt kept — so the
 * first request asks for the rest only. [onProgress] hears the bytes held so far after every chunk,
 * and once more as soon as the first response states the file's size: its own chunks arrive before
 * the size does, and a partial that is already whole brings none at all.
 */
internal suspend fun downloadInRanges(
    download: RangeDownload,
    rangeBytes: Long,
    sizeHint: Long?,
    append: suspend (ByteArray, Int) -> Unit,
    maxAttempts: Int = 3,
    startAt: Long = 0L,
    onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
): RangeOutcome {
    var offset = startAt
    var total: Long? = null
    var failures = 0
    while (true) {
        total?.let {
            if (offset == it) return RangeOutcome.Complete
            // Not a drop to retry: what is held cannot be this file's prefix.
            if (offset > it) return RangeOutcome.Overlong
        }
        val from = offset
        val limit = total ?: sizeHint?.takeIf { it > 0 }
        val end = from + rangeBytes - 1
        val to = if (limit != null) maxOf(from, minOf(end, limit - 1)) else end
        val result = download(from, to) { buf, n ->
            append(buf, n)
            offset += n
            onProgress(offset, total)
        }
        if (result == null) {
            // Bytes arrived before the drop: a slow link making progress, not a dead one.
            if (offset > from) failures = 0 else if (++failures >= maxAttempts) return RangeOutcome.Failed
            continue
        }
        if (total == null) onProgress(offset, result.total)
        total = result.total
        if (offset == from && offset < result.total) {
            if (++failures >= maxAttempts) return RangeOutcome.Failed
        } else {
            failures = 0
        }
    }
}
