package dev.supermux.webview

import kotlin.time.TimeSource

/** Milliseconds on a monotonic clock that starts now. */
internal fun monotonicClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
