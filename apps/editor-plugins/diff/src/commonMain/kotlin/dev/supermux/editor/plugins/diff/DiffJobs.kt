package dev.supermux.editor.plugins.diff

import dev.supermux.editor.core.Rope
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

    /** [offThread] false: on the caller's dispatcher with plain `yield()`s (tests on a virtual clock), never a macrotask. */
    suspend fun diff(a: List<String>, b: List<String>, options: DiffOptions = DiffOptions(), sliceMs: Long = 4, offThread: Boolean = true): DiffResult {
        val worker = diffWorker
        if (offThread && worker != null) return withContext(worker) { LineDiff.diffSliced(a, b, options) { yield() } }
        return sliced(a, b, options, sliceMs) { if (worker == null && offThread) giveBackThread() else yield() }
    }

    /**
     * The whole diff of [doc] against the base ([baseLines], or [base] split here when null), line
     * splitting included, off the UI thread the same way as [diff]: a 10 MB file never splits on it.
     */
    suspend fun diffDoc(baseLines: List<String>?, base: String, doc: Rope, options: DiffOptions = DiffOptions(), sliceMs: Long = 4, offThread: Boolean = true): Pair<List<String>, DiffResult> {
        val worker = diffWorker
        if (offThread && worker != null) return withContext(worker) {
            val bl = baseLines ?: LineDiff.lines(base)
            bl to LineDiff.diffSliced(bl, LineDiff.lines(doc), options) { yield() }
        }
        var mark = TimeSource.Monotonic.markNow()
        val pause: suspend () -> Unit = {
            if (mark.elapsedNow().inWholeMilliseconds >= sliceMs) { if (worker == null && offThread) giveBackThread() else yield(); mark = TimeSource.Monotonic.markNow() }
        }
        val bl = baseLines ?: splitSliced(base, pause)
        val wl = ropeLines(doc, pause)
        return bl to sliced(bl, wl, options, sliceMs) { if (worker == null && offThread) giveBackThread() else yield() }
    }

    /** [text]'s lines (as [LineDiff.lines]), [pause] between chunks of work. */
    internal suspend fun splitSliced(text: String, pause: suspend () -> Unit): List<String> {
        val out = ArrayList<String>()
        var at = 0
        var n = 0
        while (true) {
            val nl = text.indexOf('\n', at)
            if (nl < 0) { out += text.substring(at); return out }
            out += text.substring(at, nl)
            at = nl + 1
            if (++n and 1023 == 0) pause()
        }
    }

    /** [doc]'s lines, chunk by chunk (never the whole text as one string), [pause] between chunks. */
    internal suspend fun ropeLines(doc: Rope, pause: suspend () -> Unit): List<String> {
        val out = ArrayList<String>(doc.lineCount)
        val line = StringBuilder()
        var pos = 0
        var n = 0
        while (pos < doc.length) {
            val chunk = doc.chunkAt(pos)
            var i = 0
            while (i < chunk.length) {
                val nl = chunk.indexOf('\n', i)
                if (nl < 0) { line.append(chunk, i, chunk.length); break }
                line.append(chunk, i, nl); out += line.toString(); line.setLength(0)
                i = nl + 1
            }
            pos += chunk.length
            if (++n and 15 == 0) pause()
        }
        out += line.toString()
        return out
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
