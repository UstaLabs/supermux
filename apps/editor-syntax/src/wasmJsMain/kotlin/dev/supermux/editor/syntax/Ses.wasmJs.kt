package dev.supermux.editor.syntax

/**
 * A SyntaxRuntime of syntax-loader.mjs: one instance of supermux-syntax.wasm, the ses_* binding
 * compiled to wasm. Pointers are wasm32 addresses in an Int (0 = NULL). Byte buffers cross as latin1
 * strings and int arrays as packed strings (two UTF-16 units per int, low half first): Kotlin/Wasm
 * copies a whole string at once, where a typed array would cost one JS call per element.
 */
internal external interface SyntaxRuntime : JsAny {
    fun status(): Int
    fun exceeded(): Int
    fun errOffset(): Int
    fun errType(): Int

    fun abiVersion(): Int
    fun languageCount(): Int
    fun languageName(index: Int): String?
    fun languageHasTables(name: String): Int
    fun languageLoad(name: String): Int
    fun provideTables(name: String, latin1: String): Int

    fun parserNew(): Int
    fun parserFree(parser: Int)
    fun parserSetLanguage(parser: Int, name: String): Int
    fun parserSetTimeoutMicros(parser: Int, micros: Long)
    fun parserSetIncludedRanges(parser: Int, packed: String?): Int
    fun parserReset(parser: Int)
    fun parse(parser: Int, old: Int, readCtx: Int): Int
    fun parseString(parser: Int, old: Int, text: String): Int

    fun treeCopy(tree: Int): Int
    fun treeFree(tree: Int)
    fun treeEdit(tree: Int, start: Int, oldEnd: Int, newEnd: Int, sr: Int, sc: Int, oer: Int, oec: Int, ner: Int, nec: Int)
    fun treeSexp(tree: Int): String
    fun treeHasError(tree: Int): Boolean
    fun treeChangedRanges(old: Int, new: Int): String?

    fun queryNew(language: String, source: String): Int
    fun queryFree(query: Int)
    fun queryCaptureCount(query: Int): Int
    fun queryCaptureName(query: Int, index: Int): String
    fun queryFlags(query: Int): Int
    fun queryPatternCount(query: Int): Int
    fun queryRegexCount(query: Int): Int
    fun queryRegex(query: Int, id: Int): String
    fun queryPatternSettings(query: Int, pattern: Int): String
    fun queryCaptures(query: Int, tree: Int, start: Int, end: Int, readCtx: Int, matchCtx: Int): String?
    fun queryMatches(query: Int, tree: Int, start: Int, end: Int, readCtx: Int, matchCtx: Int, childrenOf: Int): String?

    fun debugLiveTrees(): Long
    fun memoryBytes(): Double
}

/** The pull reader behind one host context: a throwing source ends the text, and the call rethrows. */
private class Reader(val source: TextSource) {
    var failure: Throwable? = null

    fun read(index: Int): String? {
        if (failure != null) return null
        return try {
            val c = source.chunkAt(index)
            if (c.isEmpty()) null else c.toString()
        } catch (t: Throwable) {
            failure = t
            null
        }
    }
}

/** The #match? callback behind one host context; a throwing matcher aborts the query (SES_ERR_CALLBACK). */
private class Matcher(val matcher: RegexMatcher) {
    var failure: Throwable? = null

    fun match(id: Int, text: String): Int {
        if (failure != null) return -1
        return try { if (matcher.matches(id, text)) 1 else 0 } catch (t: Throwable) { failure = t; -1 }
    }
}

/**
 * Host context ids -> the Kotlin objects the module's trampolines call back into (through
 * env.ses_host_read / env.ses_host_match). Single-threaded, like everything on the web.
 */
private object Hosts {
    val readers = HashMap<Int, Reader>()
    val matchers = HashMap<Int, Matcher>()
    private var next = 0

    fun id(): Int { next = if (next == Int.MAX_VALUE) 1 else next + 1; return next }

    /** Touch the object: its init registers the callbacks. */
    fun ensure() {}

    init {
        loaderSetHost(
            { ctx, index -> readers[ctx]?.read(index) },
            { ctx, id, text -> matchers[ctx]?.match(id, text) ?: -1 },
        )
    }
}

internal fun IntArray.packInts(): String {
    val c = CharArray(size * 2)
    for (i in indices) { val v = this[i]; c[2 * i] = (v and 0xFFFF).toChar(); c[2 * i + 1] = (v ushr 16).toChar() }
    return c.concatToString()
}

internal fun String.unpackInts(): IntArray = IntArray(length / 2) { this[2 * it].code or (this[2 * it + 1].code shl 16) }

internal fun String.latin1Bytes(): ByteArray = ByteArray(length) { this[it].code.toByte() }

internal fun ByteArray.toLatin1(): String {
    val c = CharArray(size)
    for (i in indices) c[i] = (this[i].toInt() and 0xFF).toChar()
    return c.concatToString()
}

/** The wasm module's runtime, loaded by [WasmBackend.load] (or, in tests, before any test runs). */
internal fun syntaxRuntime(): SyntaxRuntime = loaderCurrentRuntime()
    ?: throw SyntaxException("the syntax wasm runtime is not loaded: await WasmBackend.load() first", -2)

/** The ses_* ABI over supermux-syntax.wasm: the same C as the native actuals, the same Kotlin above it. */
internal actual object Ses {
    private val rt: SyntaxRuntime get() = syntaxRuntime().also { Hosts.ensure() }

    private inline fun <R> withReader(source: TextSource?, cleanup: (R) -> Unit, block: (Int) -> R): R {
        if (source == null) return block(0)
        val r = Reader(source)
        val id = Hosts.id()
        Hosts.readers[id] = r
        try {
            val out = block(id)
            r.failure?.let { cleanup(out); throw it }
            return out
        } finally {
            Hosts.readers.remove(id)
        }
    }

    private inline fun <R> withMatcher(match: RegexMatcher?, block: (Int, Matcher?) -> R): R {
        if (match == null) return block(0, null)
        val m = Matcher(match)
        val id = Hosts.id()
        Hosts.matchers[id] = m
        try { return block(id, m) } finally { Hosts.matchers.remove(id) }
    }

    actual fun abiVersion(): Int = rt.abiVersion()
    actual fun languageCount(): Int = rt.languageCount()
    actual fun languageName(index: Int): String = rt.languageName(index) ?: ""
    actual fun languageHasTables(name: String): Int = rt.languageHasTables(name)
    actual fun provideTables(name: String, bytes: ByteArray): Int =
        if (bytes.isEmpty()) SyntaxStatus.BAD_TABLES else rt.provideTables(name, bytes.toLatin1())
    actual fun languageLoad(name: String): Int = rt.languageLoad(name)

    actual fun parserNew(): Long = rt.parserNew().toLong()
    actual fun parserFree(parser: Long) = rt.parserFree(parser.toInt())
    actual fun parserSetLanguage(parser: Long, name: String): Int = rt.parserSetLanguage(parser.toInt(), name)
    actual fun parserSetTimeoutMicros(parser: Long, micros: Long) = rt.parserSetTimeoutMicros(parser.toInt(), micros)
    actual fun parserSetIncludedRanges(parser: Long, ranges: IntArray): Int =
        rt.parserSetIncludedRanges(parser.toInt(), if (ranges.isEmpty()) null else ranges.packInts())
    actual fun parserReset(parser: Long) = rt.parserReset(parser.toInt())

    actual fun parse(parser: Long, old: Long, source: TextSource, status: IntArray): Long {
        val r = rt
        val t = withReader(source, cleanup = { if (it != 0) r.treeFree(it) }) { ctx ->
            r.parse(parser.toInt(), old.toInt(), ctx).also { status[0] = r.status() }
        }
        return t.toLong()
    }

    actual fun parseString(parser: Long, old: Long, text: String, status: IntArray): Long {
        val r = rt
        val t = r.parseString(parser.toInt(), old.toInt(), text)
        status[0] = r.status()
        return t.toLong()
    }

    actual fun treeCopy(tree: Long): Long = rt.treeCopy(tree.toInt()).toLong()
    actual fun treeFree(tree: Long) = rt.treeFree(tree.toInt())
    actual fun treeEdit(tree: Long, start: Int, oldEnd: Int, newEnd: Int, sr: Int, sc: Int, oer: Int, oec: Int, ner: Int, nec: Int) =
        rt.treeEdit(tree.toInt(), start, oldEnd, newEnd, sr, sc, oer, oec, ner, nec)
    actual fun treeSexp(tree: Long): String = rt.treeSexp(tree.toInt())
    actual fun treeHasError(tree: Long): Boolean = rt.treeHasError(tree.toInt())
    actual fun treeChangedRanges(old: Long, new: Long): IntArray {
        val r = rt
        val packed = r.treeChangedRanges(old.toInt(), new.toInt())
        check(r.status(), "changedRanges")
        return packed!!.unpackInts()
    }

    actual fun queryNew(language: String, utf8: ByteArray, err: IntArray): Long {
        val r = rt
        // the loader encodes the text as UTF-8 again: the same bytes (it was a Kotlin String)
        val q = r.queryNew(language, utf8.decodeToString())
        err[0] = r.status(); err[1] = r.errOffset(); err[2] = r.errType()
        return q.toLong()
    }

    actual fun queryFree(query: Long) = rt.queryFree(query.toInt())
    actual fun queryCaptureCount(query: Long): Int = rt.queryCaptureCount(query.toInt())
    actual fun queryCaptureName(query: Long, index: Int): ByteArray = rt.queryCaptureName(query.toInt(), index).latin1Bytes()
    actual fun queryFlags(query: Long): Int = rt.queryFlags(query.toInt())
    actual fun queryPatternCount(query: Long): Int = rt.queryPatternCount(query.toInt())
    actual fun queryRegexCount(query: Long): Int = rt.queryRegexCount(query.toInt())
    actual fun queryRegex(query: Long, id: Int): ByteArray = rt.queryRegex(query.toInt(), id).latin1Bytes()
    actual fun queryPatternSettings(query: Long, pattern: Int): ByteArray = rt.queryPatternSettings(query.toInt(), pattern).latin1Bytes()

    actual fun queryMatches(
        query: Long, tree: Long, start: Int, end: Int, source: TextSource?, match: RegexMatcher?, childrenOf: Int, flags: IntArray,
    ): IntArray {
        val r = rt
        return withMatcher(match) { mid, matcher ->
            // the loader already freed the C buffer, whatever happened
            val packed = withReader(source, cleanup = {}) { rid -> r.queryMatches(query.toInt(), tree.toInt(), start, end, rid, mid, childrenOf) }
            matcher?.failure?.let { throw it }
            check(r.status(), "queryMatches")
            flags[0] = r.exceeded()
            packed!!.unpackInts()
        }
    }

    actual fun queryCaptures(
        query: Long, tree: Long, start: Int, end: Int, source: TextSource?, match: RegexMatcher?, flags: IntArray,
    ): IntArray {
        val r = rt
        return withMatcher(match) { mid, matcher ->
            val packed = withReader(source, cleanup = {}) { rid -> r.queryCaptures(query.toInt(), tree.toInt(), start, end, rid, mid) }
            matcher?.failure?.let { throw it } // SES_ERR_CALLBACK: the C side freed its buffer already
            check(r.status(), "queryCaptures")
            flags[0] = r.exceeded()
            packed!!.unpackInts()
        }
    }

    actual fun debugLiveTrees(): Long = rt.debugLiveTrees()
}
