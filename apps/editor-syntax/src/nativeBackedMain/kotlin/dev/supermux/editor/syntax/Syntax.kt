package dev.supermux.editor.syntax

/**
 * The native tree-sitter binding (ses_* C ABI) as plain Kotlin objects. Every offset, length, row
 * and column here is in UTF-16 code units, exactly like String/Rope indexes and web-tree-sitter's
 * startIndex/endIndex: there is no byte encoding anywhere on the Kotlin side.
 *
 * Not thread-safe per object (a parser, tree or query cursor is used by one thread at a time); a
 * [SyntaxQuery] may be shared once built. Grammar loading is thread-safe inside the C runtime.
 */

object SyntaxLanguages {
    const val ABI_VERSION = 3

    /** Every compiled-in grammar (code); [hasTables] says whether it is usable yet. */
    fun names(): List<String> = List(Ses.languageCount()) { Ses.languageName(it) }
    fun hasTables(name: String): Boolean = Ses.languageHasTables(name) == 1
    /** Tables for a grammar whose blob is not bundled (e.g. downloaded). Checked against the code's hash. */
    fun provideTables(name: String, sesz: ByteArray) = check(Ses.provideTables(name, sesz), "provideTables($name)")
    fun load(name: String) = check(Ses.languageLoad(name), "load($name)")

    init {
        val abi = Ses.abiVersion()
        if (abi != ABI_VERSION) throw SyntaxException("native ses ABI $abi, binding needs $ABI_VERSION", -2)
    }
}

internal fun check(status: Int, what: String) {
    if (status != SyntaxStatus.OK) throw SyntaxException("$what failed", status)
}

class SyntaxParser(val language: String) : AutoCloseable {
    private var ptr: Long = Ses.parserNew().also { if (it == 0L) throw SyntaxException("parserNew", -4) }

    init {
        SyntaxLanguages // ABI check
        val st = Ses.parserSetLanguage(ptr, language)
        if (st != SyntaxStatus.OK) { close(); throw SyntaxException("setLanguage($language)", st) }
    }

    fun setTimeoutMicros(micros: Long) = Ses.parserSetTimeoutMicros(live(), micros)

    /**
     * Restrict the next parses to these UTF-16 ranges (packed [start, end]*, sorted, not
     * overlapping); empty = the whole document. [text] is the document: tree-sitter needs each
     * boundary's row and column, read from it.
     */
    fun setIncludedRanges(ranges: IntArray, text: TextSource) =
        check(Ses.parserSetIncludedRanges(live(), ranges, text), "setIncludedRanges")

    /** Parse, reusing [old] (which must already carry every [SyntaxTree.edit] since it was made). */
    fun parse(source: TextSource, old: SyntaxTree? = null): SyntaxTree {
        val status = IntArray(1)
        val t = Ses.parse(live(), old?.live() ?: 0L, source, status)
        if (t == 0L) throw SyntaxException("parse", status[0])
        return SyntaxTree(t)
    }

    fun parse(text: String, old: SyntaxTree? = null): SyntaxTree {
        val status = IntArray(1)
        val t = Ses.parseString(live(), old?.live() ?: 0L, text, status)
        if (t == 0L) throw SyntaxException("parse", status[0])
        return SyntaxTree(t)
    }

    private fun live(): Long { check(ptr != 0L) { "parser closed" }; return ptr }

    override fun close() { if (ptr != 0L) { Ses.parserFree(ptr); ptr = 0L } }
}

class SyntaxTree internal constructor(private var ptr: Long) : AutoCloseable {
    internal fun live(): Long { check(ptr != 0L) { "tree closed" }; return ptr }

    fun copy(): SyntaxTree = SyntaxTree(Ses.treeCopy(live()))

    fun edit(e: TextEdit) = Ses.treeEdit(
        live(), e.start, e.oldEnd, e.newEnd, e.startRow, e.startColumn, e.oldEndRow, e.oldEndColumn,
        e.newEndRow, e.newEndColumn,
    )

    fun sexp(): String = Ses.treeSexp(live())
    val hasError: Boolean get() = Ses.treeHasError(live())

    /** Packed [start, end]* (UTF-16) of what changed from this (edited) tree to its reparse [new]. */
    fun changedRanges(new: SyntaxTree): IntArray = Ses.treeChangedRanges(live(), new.live())

    override fun close() { if (ptr != 0L) { Ses.treeFree(ptr); ptr = 0L } }
}

class SyntaxQuery(val language: String, source: String) : AutoCloseable {
    private var ptr: Long = run {
        SyntaxLanguages
        val err = IntArray(3)
        val p = Ses.queryNew(language, source.encodeToByteArray(), err)
        if (p == 0L) throw SyntaxException("query: error type ${err[2]} at byte ${err[1]}", err[0])
        p
    }
    // Everything below runs after queryNew: a failure must free the native query (guarded).
    val captureNames: List<String> = guarded { List(Ses.queryCaptureCount(ptr)) { Ses.queryCaptureName(ptr, it).decodeToString() } }
    /** Bit 0: uses #lua-match?, which is not evaluated (those predicates pass). */
    val flags: Int = guarded { Ses.queryFlags(ptr) }
    val patternCount: Int = guarded { Ses.queryPatternCount(ptr) }

    /** The #match?-family regexes, compiled once per query (Kotlin Regex, found anywhere in the node text). */
    private val regexes: List<Regex> = guarded {
        List(Ses.queryRegexCount(ptr)) { id ->
            val pattern = Ses.queryRegex(ptr, id).decodeToString()
            try {
                Regex(pattern)
            } catch (e: IllegalArgumentException) {
                throw SyntaxException("query: #match? regex /$pattern/ does not compile: ${e.message}", SyntaxStatus.QUERY)
            }
        }
    }
    private val matcher: RegexMatcher? =
        if (regexes.isEmpty()) null else RegexMatcher { id, text -> regexCalls?.invoke(); regexes[id].containsMatchIn(text) }

    /** Tests: called on every regex evaluation. */
    internal var regexCalls: (() -> Unit)? = null

    private inline fun <T> guarded(block: () -> T): T =
        try { block() } catch (t: Throwable) { close(); throw t }

    /**
     * Captures of nodes intersecting UTF-16 [start, end): packed [start, end, captureIndex,
     * patternIndex]* in UTF-16 units, in tree-sitter's capture order. [text] feeds the text
     * predicates (#eq?, #any-of?, and the #match? family, evaluated with Kotlin Regex); without it
     * they pass. A match that fails a predicate is dropped whole, so with several patterns
     * capturing one node the first pattern that survives its predicates comes first.
     */
    fun captures(tree: SyntaxTree, start: Int, end: Int, text: TextSource? = null): Captures {
        check(ptr != 0L) { "query closed" }
        val flags = IntArray(1)
        val ints = Ses.queryCaptures(ptr, tree.live(), start, end, text, matcher, flags)
        return Captures(ints, flags[0] != 0)
    }

    /** Pattern [pattern]'s directives (#set!, #is?, #is-not?) in source order, each with its capture. */
    fun patternSettings(pattern: Int): List<PatternSetting> {
        check(ptr != 0L) { "query closed" }
        require(pattern in 0 until patternCount) { "pattern $pattern of $patternCount" }
        return decodeSettings(Ses.queryPatternSettings(ptr, pattern))
    }

    /** The capture-less `#set! key [value]` directives of [pattern] as key -> value (a later one wins). */
    fun settingsMap(pattern: Int): Map<String, String?> =
        patternSettings(pattern).filter { it.kind == PatternSetting.Kind.SET && it.captureId == null }
            .associate { it.key to it.value }

    override fun close() { if (ptr != 0L) { Ses.queryFree(ptr); ptr = 0L } }
}

/** ses_query_pattern_settings' packed records (see supermux_syntax.h). */
internal fun decodeSettings(b: ByteArray): List<PatternSetting> {
    var i = 0
    fun u32(): Int {
        val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
        i += 4
        return v
    }
    fun str(len: Int): String = b.decodeToString(i, i + len).also { i += len }
    val out = ArrayList<PatternSetting>()
    while (i < b.size) {
        val kind = when (b[i++].toInt()) { 2 -> PatternSetting.Kind.IS; 3 -> PatternSetting.Kind.IS_NOT; else -> PatternSetting.Kind.SET }
        val capture = u32().takeIf { it >= 0 }
        val key = str(u32())
        val vl = u32()
        val value = if (vl == -1) null else str(vl)
        out += PatternSetting(kind, capture, key, value)
    }
    return out
}

/** Does regex [id] of the query match somewhere in [text]? Called from the native cursor loop. */
internal fun interface RegexMatcher {
    fun matches(id: Int, text: String): Boolean
}

/** The raw ses_* ABI, one actual per binding (JNI / cinterop). Pointers are Longs, 0 = null. */
internal expect object Ses {
    fun abiVersion(): Int
    fun languageCount(): Int
    fun languageName(index: Int): String
    fun languageHasTables(name: String): Int
    fun provideTables(name: String, bytes: ByteArray): Int
    fun languageLoad(name: String): Int
    fun parserNew(): Long
    fun parserFree(parser: Long)
    fun parserSetLanguage(parser: Long, name: String): Int
    fun parserSetTimeoutMicros(parser: Long, micros: Long)
    fun parserSetIncludedRanges(parser: Long, ranges: IntArray, source: TextSource): Int
    fun parse(parser: Long, old: Long, source: TextSource, status: IntArray): Long
    fun parseString(parser: Long, old: Long, text: String, status: IntArray): Long
    fun treeCopy(tree: Long): Long
    fun treeFree(tree: Long)
    fun treeEdit(tree: Long, start: Int, oldEnd: Int, newEnd: Int, sr: Int, sc: Int, oer: Int, oec: Int, ner: Int, nec: Int)
    fun treeSexp(tree: Long): String
    fun treeHasError(tree: Long): Boolean
    fun treeChangedRanges(old: Long, new: Long): IntArray
    fun queryNew(language: String, utf8: ByteArray, err: IntArray): Long
    fun queryFree(query: Long)
    fun queryCaptureCount(query: Long): Int
    fun queryCaptureName(query: Long, index: Int): ByteArray
    fun queryFlags(query: Long): Int
    fun queryPatternCount(query: Long): Int
    fun queryRegexCount(query: Long): Int
    fun queryRegex(query: Long, id: Int): ByteArray
    fun queryPatternSettings(query: Long, pattern: Int): ByteArray
    /** flags[0] = 1 when the cursor exceeded its match limit. */
    fun queryCaptures(query: Long, tree: Long, start: Int, end: Int, source: TextSource?, match: RegexMatcher?, flags: IntArray): IntArray
    /** Native trees alive right now (leak tests). */
    fun debugLiveTrees(): Long
}
