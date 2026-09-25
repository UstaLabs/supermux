@file:OptIn(ExperimentalForeignApi::class)

package dev.supermux.editor.syntax

import dev.supermux.editor.syntax.cinterop.ses_abi_version
import dev.supermux.editor.syntax.cinterop.ses_debug_live_trees
import dev.supermux.editor.syntax.cinterop.ses_free
import dev.supermux.editor.syntax.cinterop.ses_language_count
import dev.supermux.editor.syntax.cinterop.ses_language_has_tables
import dev.supermux.editor.syntax.cinterop.ses_language_load
import dev.supermux.editor.syntax.cinterop.ses_language_name
import dev.supermux.editor.syntax.cinterop.ses_language_provide_tables
import dev.supermux.editor.syntax.cinterop.ses_parser_free
import dev.supermux.editor.syntax.cinterop.ses_parser_new
import dev.supermux.editor.syntax.cinterop.ses_parser_parse
import dev.supermux.editor.syntax.cinterop.ses_parser_parse_utf16
import dev.supermux.editor.syntax.cinterop.ses_parser_set_included_ranges
import dev.supermux.editor.syntax.cinterop.ses_parser_set_language
import dev.supermux.editor.syntax.cinterop.ses_parser_set_timeout_micros
import dev.supermux.editor.syntax.cinterop.ses_query_capture_count
import dev.supermux.editor.syntax.cinterop.ses_query_capture_name
import dev.supermux.editor.syntax.cinterop.ses_query_captures
import dev.supermux.editor.syntax.cinterop.ses_query_flags
import dev.supermux.editor.syntax.cinterop.ses_query_free
import dev.supermux.editor.syntax.cinterop.ses_query_new
import dev.supermux.editor.syntax.cinterop.ses_query_pattern_count
import dev.supermux.editor.syntax.cinterop.ses_query_pattern_settings
import dev.supermux.editor.syntax.cinterop.ses_query_regex
import dev.supermux.editor.syntax.cinterop.ses_query_regex_count
import dev.supermux.editor.syntax.cinterop.ses_tree_changed_ranges
import dev.supermux.editor.syntax.cinterop.ses_tree_copy
import dev.supermux.editor.syntax.cinterop.ses_tree_edit
import dev.supermux.editor.syntax.cinterop.ses_tree_free
import dev.supermux.editor.syntax.cinterop.ses_tree_has_error
import dev.supermux.editor.syntax.cinterop.ses_tree_root_sexp
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * cinterop actual over the static libsupermux_syntax.a (ses_* + tree-sitter + grammars). Kotlin
 * Strings are UTF-16; each chunk is copied into a pinned CharArray that stays pinned until the C
 * reader asks for the next one, so tree-sitter reads the code units in place.
 */
private class Reader(val source: TextSource) {
    private var pinned: Pinned<CharArray>? = null
    var failure: Throwable? = null

    fun next(index: Int, outLen: CPointer<UIntVar>): CPointer<UShortVar>? {
        release()
        outLen.pointed.value = 0u
        if (failure != null) return null
        val chunk = try { source.chunkAt(index) } catch (t: Throwable) { failure = t; return null }
        if (chunk.isEmpty()) return null
        val chars = chunk.toString().toCharArray()
        val p = chars.pin()
        pinned = p
        outLen.pointed.value = chars.size.toUInt()
        return p.addressOf(0).reinterpret()
    }

    fun release() { pinned?.unpin(); pinned = null }
}

// A C function pointer: no captures, so the Reader travels through ctx as a StableRef.
private val readChunk = staticCFunction { ctx: COpaquePointer?, index: UInt, outLen: CPointer<UIntVar>? ->
    ctx!!.asStableRef<Reader>().get().next(index.toInt(), outLen!!)
}

/**
 * Runs [block] with a reader for [source]. When the source threw, the C side saw the end of the
 * document and finished normally: [cleanup] then frees what [block] returned before the source's
 * exception is rethrown.
 */
private inline fun <R> withReader(source: TextSource?, cleanup: (R) -> Unit, block: (COpaquePointer?) -> R): R {
    if (source == null) return block(null)
    val reader = Reader(source)
    val ref = StableRef.create(reader)
    try {
        val r = block(ref.asCPointer())
        reader.failure?.let { cleanup(r); throw it }
        return r
    } finally {
        reader.release()
        ref.dispose()
    }
}

/** The #match? callback's Kotlin side; a throwing matcher aborts the query (SES_ERR_CALLBACK). */
private class Matcher(val matcher: RegexMatcher) {
    var failure: Throwable? = null

    fun match(id: Int, text: CPointer<UShortVar>?, len: Int): Int {
        if (failure != null) return -1
        val s = if (text == null || len == 0) "" else CharArray(len) { text[it].toInt().toChar() }.concatToString()
        return try { if (matcher.matches(id, s)) 1 else 0 } catch (t: Throwable) { failure = t; -1 }
    }
}

private val matchRegex = staticCFunction { ctx: COpaquePointer?, id: UInt, text: CPointer<UShortVar>?, len: UInt ->
    ctx!!.asStableRef<Matcher>().get().match(id.toInt(), text, len.toInt())
}

internal actual object Ses {
    actual fun abiVersion(): Int = ses_abi_version().toInt()
    actual fun languageCount(): Int = ses_language_count().toInt()
    actual fun languageName(index: Int): String = ses_language_name(index.toUInt())!!.toKString()
    actual fun languageHasTables(name: String): Int = ses_language_has_tables(name)
    actual fun provideTables(name: String, bytes: ByteArray): Int =
        if (bytes.isEmpty()) SyntaxStatus.BAD_TABLES
        else bytes.usePinned { ses_language_provide_tables(name, it.addressOf(0).reinterpret(), bytes.size.convert()) }
    actual fun languageLoad(name: String): Int = ses_language_load(name)

    actual fun parserNew(): Long = ses_parser_new().toLong()
    actual fun parserFree(parser: Long) = ses_parser_free(parser.toCPointer())
    actual fun parserSetLanguage(parser: Long, name: String): Int = ses_parser_set_language(parser.toCPointer(), name)
    actual fun parserSetTimeoutMicros(parser: Long, micros: Long) =
        ses_parser_set_timeout_micros(parser.toCPointer(), micros.toULong())

    actual fun parserSetIncludedRanges(parser: Long, ranges: IntArray, source: TextSource): Int {
        if (ranges.isEmpty()) return ses_parser_set_included_ranges(parser.toCPointer(), null, 0u, null, null)
        return ranges.usePinned { pinned ->
            withReader(source, cleanup = {}) { ctx ->
                ses_parser_set_included_ranges(parser.toCPointer(), pinned.addressOf(0), ranges.size.toUInt(), readChunk, ctx)
            }
        }
    }

    actual fun parse(parser: Long, old: Long, source: TextSource, status: IntArray): Long = memScoped {
        val st = alloc<IntVar>()
        val t = withReader(source, cleanup = { ses_tree_free(it) }) { ctx ->
            ses_parser_parse(parser.toCPointer(), old.toCPointer(), readChunk, ctx, st.ptr)
        }
        status[0] = st.value
        t.toLong()
    }

    actual fun parseString(parser: Long, old: Long, text: String, status: IntArray): Long = memScoped {
        val st = alloc<IntVar>()
        val chars = text.toCharArray()
        val t = if (chars.isEmpty()) ses_parser_parse_utf16(parser.toCPointer(), old.toCPointer(), null, 0u, st.ptr)
        else chars.usePinned {
            ses_parser_parse_utf16(parser.toCPointer(), old.toCPointer(), it.addressOf(0).reinterpret(), chars.size.toUInt(), st.ptr)
        }
        status[0] = st.value
        t.toLong()
    }

    actual fun treeCopy(tree: Long): Long = ses_tree_copy(tree.toCPointer()).toLong()
    actual fun treeFree(tree: Long) = ses_tree_free(tree.toCPointer())
    actual fun treeEdit(tree: Long, start: Int, oldEnd: Int, newEnd: Int, sr: Int, sc: Int, oer: Int, oec: Int, ner: Int, nec: Int) =
        ses_tree_edit(
            tree.toCPointer(), start.toUInt(), oldEnd.toUInt(), newEnd.toUInt(), sr.toUInt(), sc.toUInt(),
            oer.toUInt(), oec.toUInt(), ner.toUInt(), nec.toUInt(),
        )

    actual fun treeSexp(tree: Long): String {
        val s = ses_tree_root_sexp(tree.toCPointer()) ?: return ""
        try { return s.toKString() } finally { ses_free(s) }
    }

    actual fun treeHasError(tree: Long): Boolean = ses_tree_has_error(tree.toCPointer()) != 0

    actual fun treeChangedRanges(old: Long, new: Long): IntArray = memScoped {
        val out = alloc<CPointerVar<IntVar>>()
        val n = alloc<UIntVar>()
        check(ses_tree_changed_ranges(old.toCPointer(), new.toCPointer(), out.ptr, n.ptr), "changedRanges")
        takeInts(out.value, n.value.toInt())
    }

    actual fun queryNew(language: String, utf8: ByteArray, err: IntArray): Long = memScoped {
        val off = alloc<UIntVar>()
        val type = alloc<IntVar>()
        val st = alloc<IntVar>()
        // cinterop maps `const char *` to String (it re-encodes as UTF-8: the same bytes, NUL-terminated)
        val q = ses_query_new(language, utf8.decodeToString(), utf8.size.toUInt(), off.ptr, type.ptr, st.ptr)
        err[0] = st.value; err[1] = off.value.toInt(); err[2] = type.value
        q.toLong()
    }

    actual fun queryFree(query: Long) = ses_query_free(query.toCPointer())
    actual fun queryCaptureCount(query: Long): Int = ses_query_capture_count(query.toCPointer()).toInt()
    actual fun queryCaptureName(query: Long, index: Int): ByteArray = memScoped {
        val len = alloc<UIntVar>()
        val s = ses_query_capture_name(query.toCPointer(), index.toUInt(), len.ptr) ?: return ByteArray(0)
        s.readBytes(len.value.toInt())
    }
    actual fun queryFlags(query: Long): Int = ses_query_flags(query.toCPointer()).toInt()
    actual fun queryPatternCount(query: Long): Int = ses_query_pattern_count(query.toCPointer()).toInt()
    actual fun queryRegexCount(query: Long): Int = ses_query_regex_count(query.toCPointer()).toInt()
    actual fun queryRegex(query: Long, id: Int): ByteArray = memScoped {
        val len = alloc<UIntVar>()
        val s = ses_query_regex(query.toCPointer(), id.toUInt(), len.ptr) ?: return ByteArray(0)
        s.readBytes(len.value.toInt())
    }
    actual fun queryPatternSettings(query: Long, pattern: Int): ByteArray = memScoped {
        val len = alloc<UIntVar>()
        val s = ses_query_pattern_settings(query.toCPointer(), pattern.toUInt(), len.ptr) ?: return ByteArray(0)
        s.reinterpret<ByteVar>().readBytes(len.value.toInt())
    }

    actual fun queryCaptures(
        query: Long, tree: Long, start: Int, end: Int, source: TextSource?, match: RegexMatcher?, flags: IntArray,
    ): IntArray = memScoped {
        val out = alloc<CPointerVar<IntVar>>()
        out.value = null
        val n = alloc<UIntVar>()
        val exceeded = alloc<IntVar>()
        val matcher = match?.let { Matcher(it) }
        val mref = matcher?.let { StableRef.create(it) }
        try {
            // A throwing source: the C side finished (it saw the end of the document), so free its buffer.
            val st = withReader(source, cleanup = { ses_free(out.value) }) { ctx ->
                ses_query_captures(
                    query.toCPointer(), tree.toCPointer(), start.toUInt(), end.toUInt(),
                    if (ctx == null) null else readChunk, ctx,
                    if (mref == null) null else matchRegex, mref?.asCPointer(), out.ptr, n.ptr, exceeded.ptr,
                )
            }
            matcher?.failure?.let { throw it } // SES_ERR_CALLBACK: the C side freed its buffer already
            check(st, "queryCaptures")
            flags[0] = exceeded.value
            takeInts(out.value, n.value.toInt())
        } finally {
            mref?.dispose()
        }
    }

    actual fun debugLiveTrees(): Long = ses_debug_live_trees()

    /** Copy a ses_*-returned int buffer into an IntArray and free it. */
    private fun takeInts(p: CPointer<IntVar>?, n: Int): IntArray {
        try { return IntArray(n) { p!![it] } } finally { ses_free(p) }
    }
}
