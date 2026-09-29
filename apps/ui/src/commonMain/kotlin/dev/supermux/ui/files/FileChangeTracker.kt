// "Changed on disk" detection for open documents, from the parent folders' live snapshots. The
// host file-system service already pushes a fresh snapshot of every subscribed folder whenever
// anything in it changes, so the stale banner needs no watcher of its own: remember each open
// file's (mtime, size) from the first snapshot seen after it was opened and report when a later
// snapshot disagrees — or the entry is gone.
package dev.supermux.ui.files

import dev.supermux.fs.DirSnapshot
import kotlin.time.TimeSource

/**
 * Pure bookkeeping behind the workspace stale banner. Paths are ABSOLUTE host paths. Not
 * thread-safe: drive it from one thread (the UI thread).
 *
 * Our OWN saves change mtime too, and the legacy workspace write route answers without the new
 * mtime. So a save is bracketed with [beginWrite]/[endWrite]: while it is in flight, and for
 * [writeGraceMs] after it ends (the folder event can land just after the HTTP answer), a change to
 * that file only moves the baseline, it is never reported.
 */
class FileChangeTracker(
    private val nowMs: () -> Long = defaultClock(),
    private val writeGraceMs: Long = 2_000,
) {
    private data class Seen(val mtime: Long?, val size: Long)
    private object Missing

    /** abs path → baseline: null (no sighting yet), [Seen], or [Missing] (reported deleted). */
    private val tracked = LinkedHashMap<String, Any?>()
    private val writing = HashMap<String, Int>()
    private val quietUntil = HashMap<String, Long>()

    val trackedPaths: Set<String> get() = tracked.keys.toSet()

    /** Start tracking [absPath]; its baseline comes from the next snapshot that lists it. */
    fun track(absPath: String) {
        if (absPath !in tracked) tracked[absPath] = null
    }

    fun untrack(absPath: String) {
        tracked.remove(absPath)
        writing.remove(absPath)
        quietUntil.remove(absPath)
    }

    /** The folders to subscribe to: one per distinct parent of a tracked file. */
    fun folders(): Set<String> = tracked.keys.mapNotNull(::parentOf).toSet()

    /** A save of [absPath] is about to go out. */
    fun beginWrite(absPath: String) {
        writing[absPath] = (writing[absPath] ?: 0) + 1
    }

    /** The save of [absPath] finished (either way); its folder event may still be on its way. */
    fun endWrite(absPath: String) {
        val n = (writing[absPath] ?: 0) - 1
        if (n > 0) writing[absPath] = n else writing.remove(absPath)
        quietUntil[absPath] = nowMs() + writeGraceMs
    }

    private fun quiet(absPath: String): Boolean {
        if ((writing[absPath] ?: 0) > 0) return true
        val until = quietUntil[absPath] ?: return false
        if (nowMs() <= until) return true
        quietUntil.remove(absPath)
        return false
    }

    /**
     * A fresh snapshot of [dir] (the path it was SUBSCRIBED under). Returns the tracked files in it
     * that changed or disappeared since the last snapshot — each change reported once.
     */
    fun onSnapshot(dir: String, snap: DirSnapshot): List<String> {
        val folder = if (dir.length > 1) dir.trimEnd('/') else dir
        val byName = snap.entries.associateBy { it.name }
        val out = ArrayList<String>()
        for (abs in tracked.keys.toList()) {
            if (parentOf(abs) != folder) continue
            val entry = byName[displayName(abs)]
            val now: Any = if (entry == null || entry.type == "dir") Missing else Seen(entry.mtime, entry.size)
            val base = tracked[abs]
            if (base == null) {
                // First sighting. A file not listed yet is most likely a snapshot older than the
                // file itself (it was just created) — wait for one that lists it.
                if (now is Seen) tracked[abs] = now
                continue
            }
            if (now == base) continue
            tracked[abs] = now
            if (!quiet(abs)) out += abs
        }
        return out
    }
}

private fun defaultClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

/** The absolute path of the workdir-relative [rel] (leading slash optional). */
fun absoluteInWorkdir(workdir: String, rel: String): String =
    rel.split('/').filter { it.isNotEmpty() && it != "." }.fold(if (workdir.length > 1) workdir.trimEnd('/') else workdir) { acc, seg -> childOf(acc, seg) }
