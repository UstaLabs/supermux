package dev.supermux.editor.plugins.search

import dev.supermux.editor.core.Rope

/**
 * What to look for (CM6's `SearchQuery`). Plain data: two queries with the same fields are equal.
 *
 * - [search]: a literal text, where `\n`, `\r`, `\t` and `\\` stand for a line break, a carriage
 *   return, a tab and a backslash (CM6's escapes; any other backslash is itself), or with [regexp]
 *   a Kotlin [Regex] pattern (java.util.regex on the JVM and Android, Kotlin/Native's and
 *   Kotlin/Wasm's own engine elsewhere; `SearchEngineTest`'s golden holds on all of them). `^` and
 *   `$` are line anchors; the input anchors `\A`, `\z`, `\Z` and `\G` are refused (the document is
 *   searched in windows, where they would answer at a window's edge). A pattern that can match a
 *   line break (`\n`, `\r`, `\s`, `\W`, `\D`, `\v`, `\R`, `\X`, any `\x`, `\u`, `\0`, `\c` escape,
 *   `\P{…}`, a control or space `\p{…}`, `[^`, the `s` flag, a literal line break) searches across
 *   lines; any other runs line by line.
 * - [caseSensitive] false (the default) ignores case, locale-free:
 *   - a LITERAL search folds each UTF-16 unit to its uppercase's lowercase ([fold]), so Turkish `i`,
 *     `I`, `İ` and `ı` are one letter; a letter outside the Basic Multilingual Plane (Deseret,
 *     Adlam: two units) is not folded;
 *   - a REGEX runs with the engine's IGNORE_CASE: every engine matches `i`, `I`, `İ`, `ı` as one
 *     letter too (they compare uppercase and lowercase, like [fold]); outside the BMP the JVM's
 *     engine folds (`𐐨` finds `𐐀`), the Kotlin/Native and Kotlin/Wasm engine does not
 *     (`CaseFoldingTest`); `\p{Lu}` / `\p{Ll}` with ignore case also follow each engine.
 *   Match case tells every one of them apart, the same everywhere.
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

    private val facts: PatternFacts? = if (regexp && search.isNotEmpty()) patternFacts(search) else null

    private val compiled: Any? = when {
        !regexp || search.isEmpty() -> null
        facts!!.inputAnchor -> "\\A, \\z, \\Z and \\G are not supported: use ^ and $ (line anchors)"
        else -> compile(search, caseSensitive)
    }

    /** The pattern's compile error, or null. */
    val error: String? = (compiled as? String)

    /** True when this query can find something: a non-empty search, and a pattern that compiles. */
    val valid: Boolean = search.isNotEmpty() && error == null

    internal val regex: Regex? = compiled as? Regex

    /**
     * The pattern without MULTILINE, for an empty last line: java.util.regex's multi-line `^` never
     * matches at the input's end (nor on an empty input); as input anchors on "" they are that
     * line's start and end.
     */
    internal val emptyLineRegex: Regex? by lazy {
        if (regex == null) null else try { Regex(unicodeWordClasses(search), if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)) } catch (e: Throwable) { null }
    }

    /** A regex that can match a line break searches across lines. */
    internal val multiline: Boolean = facts?.multiline == true

    /** The folded needle for a literal search. */
    internal val needle: String = if (caseSensitive) unquoted else fold(unquoted)

    /** Every match in [from, to), in document order, not overlapping. */
    fun cursor(doc: Rope, from: Int = 0, to: Int = doc.length): Iterator<SearchMatch> = scanner(doc, from, to)

    internal fun scanner(doc: Rope, from: Int = 0, to: Int = doc.length): MatchScanner {
        val a = from.coerceIn(0, doc.length)
        val b = to.coerceIn(a, doc.length)
        return when {
            !valid -> LiteralCursor(doc, "", false, false, a, b) // an empty needle finds nothing
            regex != null -> RegexCursor(doc, regex, { emptyLineRegex }, multiline, wholeWord, a, b)
            else -> LiteralCursor(doc, needle, !caseSensitive, wholeWord, a, b)
        }
    }

    /**
     * The first match after [curTo] (the current selection is [curFrom, curTo)), wrapping to the
     * document's start and scanning only up to where it began; never the current selection itself.
     * Null when there is no other (CM6's `nextMatch`).
     */
    fun nextMatch(doc: Rope, curFrom: Int, curTo: Int): SearchMatch? = nextImpl(doc, curFrom, curTo, exclude = true) {}

    /** The first match starting at or after [from], wrapping; an empty match AT [from] included (replace). */
    internal fun matchFrom(doc: Rope, from: Int): SearchMatch? = nextImpl(doc, from, from, exclude = false) {}

    /** [nextMatch], giving the thread back ([pause]) every [STEP_UNITS] units read. */
    internal suspend fun nextMatchSliced(doc: Rope, curFrom: Int, curTo: Int, pause: suspend () -> Unit): SearchMatch? =
        nextImpl(doc, curFrom, curTo, exclude = true) { pause() }

    internal inline fun nextImpl(doc: Rope, curFrom: Int, curTo: Int, exclude: Boolean, pause: () -> Unit): SearchMatch? {
        if (!valid) return null
        val c = scanner(doc, curTo, doc.length)
        while (true) {
            val m = c.step(STEP_UNITS)
            if (m === MatchScanner.PAUSED) { pause(); continue }
            if (m == null) break
            if (!exclude || !m.isAt(curFrom, curTo)) return m
        }
        // Wrapped: from the start up to where the first scan began (a literal a needle further, so a
        // match across that point is found; a regex's match may end at most there).
        val bound = if (regex == null) minOf(doc.length, curTo + needle.length - 1) else curTo
        val w = scanner(doc, 0, bound)
        while (true) {
            val m = w.step(STEP_UNITS)
            if (m === MatchScanner.PAUSED) { pause(); continue }
            if (m == null) return null
            if (m.from >= curTo) return null // the first scan saw it
            if (!exclude || !m.isAt(curFrom, curTo)) return m
        }
    }

    /**
     * The last match ending at or before [curFrom], wrapping to the document's end; never the
     * current selection [curFrom, curTo) itself (CM6's `prevMatch`: forward scans of chunks going
     * back from the position).
     */
    fun prevMatch(doc: Rope, curFrom: Int, curTo: Int): SearchMatch? = prevImpl(doc, curFrom, curTo) {}

    internal suspend fun prevMatchSliced(doc: Rope, curFrom: Int, curTo: Int, pause: suspend () -> Unit): SearchMatch? =
        prevImpl(doc, curFrom, curTo) { pause() }

    internal inline fun prevImpl(doc: Rope, curFrom: Int, curTo: Int, pause: () -> Unit): SearchMatch? {
        if (!valid) return null
        return lastIn(doc, 0, curFrom, curFrom, curTo, pause) ?: lastIn(doc, minOf(curFrom, curTo), doc.length, curFrom, curTo, pause)
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
    fun count(doc: Rope, limit: Int = MATCH_LIMIT): MatchCount = infoImpl(doc, -1, -1, limit) {}.total

    /** Which match [selFrom, selTo) is (1-based, 0: none) and how many there are, counting at most [limit]. */
    internal fun info(doc: Rope, selFrom: Int, selTo: Int, limit: Int = MATCH_LIMIT): MatchInfo = infoImpl(doc, selFrom, selTo, limit) {}

    internal suspend fun infoSliced(doc: Rope, selFrom: Int, selTo: Int, limit: Int = MATCH_LIMIT, pause: suspend () -> Unit): MatchInfo =
        infoImpl(doc, selFrom, selTo, limit) { pause() }

    internal inline fun infoImpl(doc: Rope, selFrom: Int, selTo: Int, limit: Int, pause: () -> Unit): MatchInfo {
        if (error != null) return MatchInfo(0, MatchCount(0, false), error)
        var n = 0
        var current = 0
        val c = scanner(doc)
        while (true) {
            val m = c.step(STEP_UNITS)
            if (m === MatchScanner.PAUSED) { pause(); continue }
            if (m == null) return MatchInfo(current, MatchCount(n, false))
            if (n == limit) return MatchInfo(current, MatchCount(limit, true))
            n++
            if (m.from == selFrom && m.to == selTo) current = n
        }
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
    internal inline fun lastIn(doc: Rope, from: Int, to: Int, exFrom: Int, exTo: Int, pause: () -> Unit): SearchMatch? {
        if (to < from) return null
        var size = PREV_CHUNK
        var pos = to
        while (true) {
            val overlap = if (regex == null) needle.length else 0
            val start = maxOf(from, pos - size - overlap)
            var last: SearchMatch? = null
            val c = scanner(doc, start, pos)
            while (true) {
                val m = c.step(STEP_UNITS)
                if (m === MatchScanner.PAUSED) { pause(); continue }
                if (m == null) break
                if (!m.isAt(exFrom, exTo)) last = m
            }
            // A regex scan starting mid-way may find a match the full scan would not (inside an
            // earlier, longer one): CM6 trusts one well past the chunk's start.
            if (last != null && (regex == null || start == from || last.from > start + 10)) return last
            if (start == from) return null
            pause()
            if (regex == null) pos = start + overlap else size *= 2
        }
    }

    companion object {
        /** Matches counted (and marked, selected, replaced one by one) at most: "10,000+" past it. */
        const val MATCH_LIMIT = 10_000

        internal const val PREV_CHUNK = 10_000

        /** A sliced scan's step: it may give the thread back after reading this much. */
        internal const val STEP_UNITS = 8192

        /** What a pattern needs from the windows: whether it can match a line break, whether it anchors to the input. */
        internal class PatternFacts(val multiline: Boolean, val inputAnchor: Boolean)

        private val LINE_BREAK_PROPERTY = Regex("^(C|Cc|Z|Zl|Zp|Space|Cntrl|javaWhitespace|javaISOControl|IsWhite_?Space|IsControl|All|Any|javaSpaceChar)$", RegexOption.IGNORE_CASE)

        /** A small scanner over the pattern: escapes, classes, `\Q…\E` and inline flags. */
        internal fun patternFacts(p: String): PatternFacts {
            var multiline = false
            var anchor = false
            var inClass = false
            var i = 0
            while (i < p.length) {
                val c = p[i]
                if (c == '\n' || c == '\r') multiline = true
                if (c == '\\' && i + 1 < p.length) {
                    val n = p[i + 1]
                    when (n) {
                        'Q' -> {
                            val e = p.indexOf("\\E", i + 2)
                            val quoted = if (e < 0) p.substring(i + 2) else p.substring(i + 2, e)
                            if (quoted.indexOf('\n') >= 0 || quoted.indexOf('\r') >= 0) multiline = true
                            i = if (e < 0) p.length else e + 2
                            continue
                        }
                        'A', 'z', 'Z', 'G' -> if (!inClass) anchor = true
                        's', 'W', 'D', 'n', 'r', 'v', 'R', 'X', 'x', 'u', '0', 'c', 'P' -> multiline = true
                        'p' -> {
                            val name = if (i + 2 < p.length && p[i + 2] == '{') p.substring(i + 3, p.indexOf('}', i + 3).let { if (it < 0) p.length else it })
                            else if (i + 2 < p.length) p[i + 2].toString() else ""
                            if (LINE_BREAK_PROPERTY.matches(name)) multiline = true
                        }
                    }
                    i += 2
                    continue
                }
                if (!inClass && c == '[') {
                    inClass = true
                    i++
                    if (i < p.length && p[i] == '^') { multiline = true; i++ }
                    if (i < p.length && p[i] == ']') i++
                    continue
                }
                if (inClass && c == ']') inClass = false
                if (!inClass && c == '(' && i + 1 < p.length && p[i + 1] == '?') {
                    // Inline flags: (?s), (?is), (?s:...), not after a '-' (turned off).
                    var j = i + 2
                    var on = true
                    while (j < p.length && (p[j].isLetter() || p[j] == '-')) {
                        if (p[j] == '-') on = false else if (p[j] == 's' && on) multiline = true
                        j++
                    }
                }
                i++
            }
            return PatternFacts(multiline, anchor)
        }

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
