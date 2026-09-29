// Base texts of the Changes pane's lazy files, by blob SHA (spec 2026-09-29 §4). A committed blob
// never changes, so an entry is valid forever; the budget only bounds memory. Failures (too large,
// binary, offline) are not cached: a "Load anyway" or a retry asks again.
package dev.supermux.ui.editor

import dev.supermux.editor.plugins.diff.LineDiff
import dev.supermux.net.BlobText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BaseTextCache(private val maxChars: Long = 16L * 1024 * 1024) {
    private val texts = LinkedHashMap<String, String>(16, 0.75f, true)
    private var chars = 0L
    private val lock = Mutex()

    suspend fun get(
        repo: String,
        sha: String,
        force: Boolean,
        fetch: suspend (repo: String, sha: String, force: Boolean) -> BlobText,
    ): BlobText {
        val key = "$repo\u0000$sha"
        lock.withLock { texts[key]?.let { return BlobText.Text(it) } }
        val r = fetch(repo, sha, force)
        if (r is BlobText.Text) lock.withLock {
            if (texts.put(key, r.text) == null) chars += r.text.length
            val it = texts.entries.iterator()
            while (chars > maxChars && texts.size > 1 && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue
                chars -= e.value.length
                it.remove()
            }
        }
        return r
    }
}

/**
 * The `@@ -a,b +c,d @@` header (3 context lines, like git) of the hunk holding new-side [line]
 * (1-based), else the nearest hunk above it, else "". What a lazy file's comments carry as
 * `diffHunkHeader`, computed from the two texts instead of a patch.
 */
fun hunkHeaderAt(base: String, working: String, line: Int): String {
    val a = LineDiff.lines(base)
    val b = LineDiff.lines(working)
    val hunks = LineDiff.diff(a, b).hunks
    val target = line - 1
    val h = hunks.lastOrNull { it.bFrom <= target || (it.bFrom == it.bTo && it.bFrom <= target + 1) } ?: return ""
    val ctx = 3
    val aStart = maxOf(0, h.aFrom - ctx)
    val bStart = maxOf(0, h.bFrom - ctx)
    val aEnd = minOf(a.size - (if (base.endsWith("\n")) 1 else 0), h.aTo + ctx)
    val bEnd = minOf(b.size - (if (working.endsWith("\n")) 1 else 0), h.bTo + ctx)
    return "@@ -${aStart + 1},${aEnd - aStart} +${bStart + 1},${bEnd - bStart} @@"
}
