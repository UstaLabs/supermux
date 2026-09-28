package dev.supermux.editor.plugins.search

import dev.supermux.editor.core.Rope

/**
 * What to look for (CM6's `SearchQuery`). Plain data: two queries with the same fields are equal.
 *
 * - [search]: a literal text, where `\n`, `\r`, `\t` and `\\` stand for a line break, a carriage
 *   return, a tab and a backslash (CM6's escapes; any other backslash is itself), or with [regexp]
 *   a Kotlin [Regex] pattern (java.util.regex on the JVM and Android, Kotlin/Native's and
 *   Kotlin/Wasm's own engines elsewhere; `SearchEngineTest`'s golden holds on all of them). `^` and
 *   `$` are line anchors. A pattern that can match a line break (`\n`, `\r`, `\s`, `\W`, `\D`, `[^`,
 *   a literal line break: CM6's test) searches across lines, in a bounded window; any other runs line
 *   by line.
 * - [caseSensitive] false (the default) folds case locale-free, the same for literal and regex: a
 *   character's uppercase, then lowercase ([fold]). So Turkish `i`, `I`, `İ` and `ı` are ONE letter
 *   then; match case tells them apart.
 * - [wholeWord]: CM6's rule: a match's first character or the one before it is not a word
 *   character, and its last or the one after it neither. Word characters are the editor's (letters,
 *   digits, `_`; a surrogate pair, an emoji, is not one).
 * - [replace]: unquoted like a literal; with [regexp] `$&` is the match, `$$` a `$`, `$1`…`$99` a
 *   group (the longest group number that exists, CM6's rule; a group that did not take part is
 *   empty) and anything else is kept as typed. A literal query's replacement is no template.
 *
 * An empty search or a pattern that does not compile is not [valid] (its [error] says why) and
 * finds nothing: nothing here throws.
 */
data class SearchQuery(
    val search: String,
    val caseSensitive: Boolean = false,
    val regexp: Boolean = false,
    val wholeWord: Boolean = false,
    val replace: String = "",
) {
    /** The literal text [search] stands for (escapes resolved). */
    internal val unquoted: String = unquote(search)

    private val compiled: Any? = if (regexp && search.isNotEmpty()) compile(search, caseSensitive) else null

    /** The pattern's compile error, or null. */
    val error: String? = (compiled as? String)

    /** True when this query can find something: a non-empty search, and a pattern that compiles. */
    val valid: Boolean = search.isNotEmpty() && error == null

    internal val regex: Regex? = compiled as? Regex

    /** A regex that can match a line break searches across lines (CM6's test). */
    internal val multiline: Boolean = regexp && MULTILINE_HINT.containsMatchIn(search)

    /** The folded needle for a literal search. */
    internal val needle: String = if (caseSensitive) unquoted else fold(unquoted)

    /** Every match in [from, to), in document order, not overlapping. */
    fun cursor(doc: Rope, from: Int = 0, to: Int = doc.length): Iterator<SearchMatch> {
        val a = from.coerceIn(0, doc.length)
        val b = to.coerceIn(a, doc.length)
        return when {
            !valid -> emptyList<SearchMatch>().iterator()
            regex != null -> RegexCursor(doc, regex, multiline, wholeWord, a, b)
            else -> LiteralCursor(doc, needle, !caseSensitive, wholeWord, a, b)
        }
    }

    /**
     * The first match after [curTo] (the current selection is [curFrom, curTo)), wrapping to the
     * document's start; never the current selection itself. Null when there is no other (CM6's
     * `nextMatch`).
     */
    fun nextMatch(doc: Rope, curFrom: Int, curTo: Int): SearchMatch? {
        if (!valid) return null
        val c = cursor(doc, curTo, doc.length)
        while (c.hasNext()) { val m = c.next(); if (!m.isAt(curFrom, curTo)) return m }
        val w = cursor(doc, 0, doc.length)
        while (w.hasNext()) {
            val m = w.next()
            if (m.from >= curTo) break // the scan from curTo found nothing else there
            if (!m.isAt(curFrom, curTo)) return m
        }
        return null
    }

    /**
     * The last match ending at or before [curFrom], wrapping to the document's end; never the
     * current selection [curFrom, curTo) itself (CM6's `prevMatch`: forward scans of chunks going
     * back from the position).
     */
    fun prevMatch(doc: Rope, curFrom: Int, curTo: Int): SearchMatch? {
        if (!valid) return null
        return lastIn(doc, 0, curFrom, curFrom, curTo) ?: lastIn(doc, minOf(curFrom, curTo), doc.length, curFrom, curTo)
    }

    /** Every match, or null when there are more than [limit] (CM6's `matchAll`). */
    fun matchAll(doc: Rope, limit: Int = MATCH_LIMIT): List<SearchMatch>? {
        val out = ArrayList<SearchMatch>()
        val c = cursor(doc)
        while (c.hasNext()) {
            val m = c.next()
            if (out.size >= limit) return null
            out += m
        }
        return out
    }

    /** How many matches, counting at most [limit] ("10,000+" past it). */
    fun count(doc: Rope, limit: Int = MATCH_LIMIT): MatchCount {
        var n = 0
        val c = cursor(doc)
        while (c.hasNext()) {
            c.next()
            if (n == limit) return MatchCount(limit, true)
            n++
        }
        return MatchCount(n, false)
    }

    /** What replaces [match] (see the class comment for templates). */
    fun replacement(match: SearchMatch): String {
        val text = unquote(replace)
        if (!regexp) return text
        val g = match.groups
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '$' || i + 1 >= text.length) { out.append(c); i++; continue }
            val n = text[i + 1]
            when {
                n == '$' -> { out.append('$'); i += 2 }
                n == '&' -> { out.append(g.getOrElse(0) { "" }); i += 2 }
                n.isAsciiDigit() -> {
                    var e = i + 1
                    while (e < text.length && text[e].isAsciiDigit()) e++
                    val digits = text.substring(i + 1, e)
                    // The longest prefix of the digits naming a group there is (CM6).
                    var used = false
                    for (l in digits.length downTo 1) {
                        val k = digits.substring(0, l).toIntOrNull() ?: continue
                        if (k in 1 until g.size) {
                            out.append(g[k]).append(digits, l, digits.length)
                            used = true
                            break
                        }
                    }
                    if (!used) out.append('$').append(digits)
                    i = e
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** The last match in [from, to) that is not [exFrom, exTo), scanning chunks backwards. */
    private fun lastIn(doc: Rope, from: Int, to: Int, exFrom: Int, exTo: Int): SearchMatch? {
        if (to < from) return null
        var size = PREV_CHUNK
        var pos = to
        while (true) {
            val overlap = if (regex == null) needle.length else 0
            val start = maxOf(from, pos - size - overlap)
            var last: SearchMatch? = null
            val c = cursor(doc, start, pos)
            while (c.hasNext()) { val m = c.next(); if (!m.isAt(exFrom, exTo)) last = m }
            // A regex scan starting mid-way may find a match the full scan would not (inside an
            // earlier, longer one): CM6 trusts one well past the chunk's start.
            if (last != null && (regex == null || start == from || last.from > start + 10)) return last
            if (start == from) return null
            if (regex == null) pos = start + overlap else size *= 2
        }
    }

    companion object {
        /** Matches counted (and marked, selected, replaced one by one) at most: "10,000+" past it. */
        const val MATCH_LIMIT = 10_000

        private const val PREV_CHUNK = 10_000

        private val MULTILINE_HINT = Regex("""\\[sWDnr]|\n|\r|\[\^""")

        /** CM6's escapes: `\n`, `\r`, `\t`, `\\`; any other backslash is itself. */
        internal fun unquote(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> { out.append('\n'); i += 2; continue }
                        'r' -> { out.append('\r'); i += 2; continue }
                        't' -> { out.append('\t'); i += 2; continue }
                        '\\' -> { out.append('\\'); i += 2; continue }
                    }
                }
                out.append(c); i++
            }
            return out.toString()
        }

        private fun compile(pattern: String, caseSensitive: Boolean): Any = try {
            val options = if (caseSensitive) setOf(RegexOption.MULTILINE) else setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
            Regex(unicodeWordClasses(pattern), options)
        } catch (e: Throwable) {
            // PatternSyntaxException on the JVM, IllegalArgumentException elsewhere: an error state.
            e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "invalid regular expression"
        }

        /**
         * `\w` and `\W` as the editor's word characters (Unicode letters, decimal digits, `_`).
         * Kotlin's engines disagree on the plain ones: `\w` is ASCII everywhere, but the
         * Kotlin/Native and Kotlin/Wasm engine folds case INTO the class (with ignore case `İ` and
         * `ı` are `\w` there, `ğ` is not; on the JVM neither is). Written out, they agree, and `\w+`
         * finds `ağaç` and `İstanbul` whole:
         * - `\w` is `[\p{L}\p{Nd}_]`; `\W` is `(?:(?![\p{L}\p{Nd}_])[\s\S])`;
         * - inside a class `\w` is `\p{L}\p{Nd}_`, and in a NEGATED class `\p{L}\p{Nd}\p{Pc}`: that
         *   engine mishandles a literal next to a `\p{…}` in a negated class (`[^\p{L}_]` matches `_`
         *   there), `\p{Pc}` (connector punctuation, `_` among it) it does not;
         * - `\W` inside a class and quoted text (`\Q…\E`) are left as they are.
         */
        internal fun unicodeWordClasses(p: String): String {
            if (p.indexOf('\\') < 0) return p
            val out = StringBuilder(p.length + 16)
            var i = 0
            var inClass = false
            var negated = false
            while (i < p.length) {
                val c = p[i]
                if (c == '\\' && i + 1 < p.length) {
                    val n = p[i + 1]
                    when {
                        n == 'Q' -> {
                            val e = p.indexOf("\\E", i + 2)
                            val end = if (e < 0) p.length else e + 2
                            out.append(p, i, end); i = end; continue
                        }
                        n == 'w' -> out.append(if (!inClass) "[\\p{L}\\p{Nd}_]" else if (negated) "\\p{L}\\p{Nd}\\p{Pc}" else "\\p{L}\\p{Nd}_")
                        n == 'W' && !inClass -> out.append("(?:(?![\\p{L}\\p{Nd}_])[\\s\\S])")
                        else -> out.append(c).append(n)
                    }
                    i += 2
                    continue
                }
                if (!inClass && c == '[') {
                    inClass = true
                    negated = false
                    out.append(c); i++
                    // A `]` (or `^]`) right after the opening bracket is a literal.
                    if (i < p.length && p[i] == '^') { out.append('^'); i++; negated = true }
                    if (i < p.length && p[i] == ']') { out.append(']'); i++ }
                    continue
                }
                if (inClass && c == ']') inClass = false
                out.append(c); i++
            }
            return out.toString()
        }

        private val FOLD: CharArray by lazy { CharArray(65536) { it.toChar().uppercaseChar().lowercaseChar() } }

        /** Locale-free case folding, one UTF-16 unit for one (offsets stay the document's). */
        fun fold(c: Char): Char = FOLD[c.code]

        internal fun fold(s: String): String {
            val t = FOLD
            val a = CharArray(s.length)
            for (i in s.indices) a[i] = t[s[i].code]
            return a.concatToString()
        }

        internal fun foldRange(s: CharSequence): String {
            val t = FOLD
            val a = CharArray(s.length)
            for (i in 0 until s.length) a[i] = t[s[i].code]
            return a.concatToString()
        }
    }
}

/** One match: [from, to) in the document; [groups] a regex's (0 is the whole match), else empty. */
data class SearchMatch(val from: Int, val to: Int, val groups: List<String> = emptyList()) {
    internal fun isAt(a: Int, b: Int) = from == a && to == b
}

/** A match count, [capped] when there were more than [count] ("10,000+"). */
data class MatchCount(val count: Int, val capped: Boolean) {
    val label: String get() = thousands(count) + if (capped) "+" else ""

    private fun thousands(n: Int): String {
        val s = n.toString()
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return out.toString()
    }
}

private fun Char.isAsciiDigit() = this in '0'..'9'

/** The editor's word characters (letters, digits, `_`; a surrogate, an emoji's half, is none). */
internal fun isWordChar(c: Char): Boolean = c == '_' || (!c.isSurrogate() && c.isLetterOrDigit())
