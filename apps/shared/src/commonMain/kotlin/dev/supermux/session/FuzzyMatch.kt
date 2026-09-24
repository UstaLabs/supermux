package dev.supermux.session

/** A fuzzy hit: higher [score] ranks first; [indices] are the matched characters of the text. */
data class FuzzyHit(val score: Int, val indices: List<Int>)

private const val WORD_SEPARATORS = " -_/."

/**
 * Case-insensitive subsequence match, scored so the obvious hit wins: a contiguous substring beats
 * scattered letters, a prefix or word start beats a mid-word hit, and every skipped character
 * costs. "smx" → s̲uperm̲ux̲, "gm" → g̲reenm̲ate. Null when [query] is not a subsequence of [text];
 * an empty query matches everything with score 0.
 */
fun fuzzyMatch(query: String, text: String): FuzzyHit? {
    if (query.isEmpty()) return FuzzyHit(0, emptyList())
    val q = query.lowercase()
    val t = text.lowercase()
    val sub = t.indexOf(q)
    if (sub >= 0) {
        val bonus = when {
            sub == 0 -> 40
            t[sub - 1] in WORD_SEPARATORS -> 20
            else -> 0
        }
        return FuzzyHit(100 + q.length * 4 + bonus - sub, (sub until sub + q.length).toList())
    }
    var from = 0
    var prev = -2
    var score = 0
    val indices = ArrayList<Int>(q.length)
    for (c in q) {
        // Prefer an occurrence that continues the run or starts a word; else take the first one.
        var first = -1
        var preferred = -1
        for (j in from until t.length) {
            if (t[j] != c) continue
            if (first < 0) first = j
            if (j == 0 || t[j - 1] in WORD_SEPARATORS || j == prev + 1) {
                preferred = j
                break
            }
        }
        val at = if (preferred >= 0) preferred else first
        if (at < 0) return null
        score += when {
            at == prev + 1 -> 6
            at == 0 || t[at - 1] in WORD_SEPARATORS -> 8
            else -> 1
        }
        score -= minOf(at - from, 5)
        indices += at
        prev = at
        from = at + 1
    }
    return FuzzyHit(score, indices)
}
