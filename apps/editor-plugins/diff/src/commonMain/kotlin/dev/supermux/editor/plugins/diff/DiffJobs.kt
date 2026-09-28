package dev.supermux.editor.plugins.diff

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.time.TimeSource

/** A worker thread's dispatcher (JVM, Android, iOS), or null where there is none (the browser). */
internal expect val diffWorker: CoroutineDispatcher?

/** Give the UI thread back for a moment (the web: a real event-loop turn, see the wasm actual). */
internal expect suspend fun giveBackThread()

/**
 * A whole diff that must not hold the UI thread: on a worker thread where the platform has one
 * (checking for cancellation as it goes), else (the browser) in slices of at most [sliceMs] of work
 * with a real macrotask between them (a MessageChannel message: kotlinx-coroutines' JS dispatcher
 * runs up to 16 queued tasks per event-loop turn, so `yield()` alone would not let the page paint;
 * search's SearchRunner, M4b).
 */
object DiffJobs {
    /** The longest slice of the last sliced diff, in ms (tests). */
    var longestSliceMs: Double = 0.0
        internal set

    suspend fun diff(a: List<String>, b: List<String>, options: DiffOptions = DiffOptions(), sliceMs: Long = 4, offThread: Boolean = true): DiffResult {
        val worker = diffWorker
        if (offThread && worker != null) return withContext(worker) { LineDiff.diffSliced(a, b, options) { yield() } }
        return sliced(a, b, options, sliceMs) { if (worker == null) giveBackThread() else yield() }
    }

    internal suspend fun sliced(a: List<String>, b: List<String>, options: DiffOptions, sliceMs: Long, giveBack: suspend () -> Unit): DiffResult {
        var mark = TimeSource.Monotonic.markNow()
        var longest = 0.0
        val r = LineDiff.diffSliced(a, b, options) {
            val e = mark.elapsedNow()
            if (e.inWholeMilliseconds >= sliceMs) {
                longest = maxOf(longest, e.inWholeMicroseconds / 1000.0)
                giveBack()
                mark = TimeSource.Monotonic.markNow()
            }
        }
        longestSliceMs = maxOf(longest, mark.elapsedNow().inWholeMicroseconds / 1000.0)
        return r
    }
}
