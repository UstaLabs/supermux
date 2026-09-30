// Base texts of the Changes pane's lazy files, by blob SHA (spec 2026-09-29 §4). A committed blob
// never changes, so an entry is valid forever; the budget only bounds memory. Failures (too large,
// binary, offline) are not cached: a "Load anyway" or a retry asks again.
package dev.supermux.ui.editor

import dev.supermux.editor.plugins.diff.LineDiff
import dev.supermux.net.BlobText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BaseTextCache(private val maxChars: Long = 16L * 1024 * 1024) {
    private val texts = LinkedHashMap<String, String>()
    private var chars = 0L
    private val lock = Mutex()

    suspend fun get(
        repo: String,
        sha: String,
        force: Boolean,
        fetch: suspend (repo: String, sha: String, force: Boolean) -> BlobText,
    ): BlobText {
        val key = "$repo\u0000$sha"
        lock.withLock { texts.remove(key)?.also { texts[key] = it }?.let { return BlobText.Text(it) } }
        val r = fetch(repo, sha, force)
        if (r is BlobText.Text) lock.withLock {
            texts.put(key, r.text)?.let { chars -= it.length }
            chars += r.text.length
            val iter = texts.entries.iterator()
            while (chars > maxChars && texts.size > 1 && iter.hasNext()) {
                val e = iter.next()
                if (e.key == key) continue
                chars -= e.value.length
                iter.remove()
            }
        }
        return r
    }
}

/**
 * The `@@ -a,b +c,d @@` header (3 context lines, like git) of the hunk holding new-side [line]
 * (1-based), else the nearest hunk above it, else "". What a lazy file's comments carry as
 * `diffHunkHeader`, computed from the two texts instead of a patch. An empty side counts as 0 lines
 * and prints start 0, like git. Unlike git, nearby changes are not merged into one hunk (the header
 * is informational).
 */
fun hunkHeaderAt(base: String, working: String, line: Int): String {
    val a = LineDiff.lines(base)
    val b = LineDiff.lines(working)
    val hunks = LineDiff.diff(a, b).hunks
    val target = line - 1
    val h = hunks.lastOrNull { it.bFrom <= target || (it.bFrom == it.bTo && it.bFrom <= target + 1) } ?: return ""
    val ctx = 3
    fun count(text: String, lines: List<String>) =
        if (text.isEmpty()) 0 else lines.size - (if (text.endsWith("\n")) 1 else 0)
    val aCount = count(base, a)
    val bCount = count(working, b)
    val aStart = minOf(maxOf(0, h.aFrom - ctx), aCount)
    val bStart = minOf(maxOf(0, h.bFrom - ctx), bCount)
    val aEnd = minOf(aCount, h.aTo + ctx)
    val bEnd = minOf(bCount, h.bTo + ctx)
    val aLen = aEnd - aStart
    val bLen = bEnd - bStart
    return "@@ -${if (aLen == 0) 0 else aStart + 1},$aLen +${if (bLen == 0) 0 else bStart + 1},$bLen @@"
}
