package dev.supermux.editor.syntax

/**
 * One syntax implementation: native (ses_*) on jvm/android/ios, web-tree-sitter on wasmJs (M2c).
 * Everything above this interface (highlighter, worker, editor plugin) is platform-free.
 *
 * Every offset is in UTF-16 code units. Handles are not thread-safe: a parser, a tree and a query
 * run on one thread at a time (the syntax worker's). A query may be shared once built.
 */
interface SyntaxBackend {
    /** Language ids this backend can parse right now or after [ensureLanguage]. */
    val languages: Set<String>

    /** Can [language] be parsed right now, without [ensureLanguage]? Never blocks. */
    fun isReady(language: String): Boolean

    /**
     * Make [language] usable: native provides a code-only grammar's tables from the app's
     * resources; the web (M2c) fetches a .wasm. Idempotent; may suspend for I/O. Throws
     * [SyntaxException] (NO_TABLES naming the missing resource, UNKNOWN_LANGUAGE).
     */
    suspend fun ensureLanguage(language: String)

    /** A parser for a ready language (see [isReady]). */
    fun newParser(language: String): ParserHandle

    /** A query the caller owns (and closes). */
    fun newQuery(language: String, source: String): QueryHandle

    /**
     * The compiled query for [source], shared by every document and thread of this backend
     * (compiling kotlin's highlights costs ~35 ms). Owned by the backend: never close it.
     */
    fun sharedQuery(language: String, source: String): QueryHandle
}

interface ParserHandle : AutoCloseable {
    val language: String

    /**
     * Restrict the next parses to these UTF-16 ranges ([start,end]* packed); empty = whole document.
     * [points] gives each boundary's row and column (the host's line index; nothing walks the text).
     */
    fun setIncludedRanges(ranges: IntArray, points: PointSource)

    fun setTimeoutMicros(micros: Long)

    /**
     * Parse; [old] must already carry every edit since it was produced. Throws
     * SyntaxException(TIMEOUT) when the timeout hits: the next call with the same text and old
     * tree then RESUMES the parse (time slices); call [reset] before parsing anything else.
     */
    fun parse(text: TextSource, old: TreeHandle?): TreeHandle

    /** Discard a timed-out parse (see [parse]). */
    fun reset()
}

interface TreeHandle : AutoCloseable {
    fun edit(e: TextEdit)
    fun copy(): TreeHandle

    /** [start,end]* UTF-16 ranges whose syntax differs between this (edited) tree and [new]. */
    fun changedRanges(new: TreeHandle): IntArray

    val hasError: Boolean
}

interface QueryHandle : AutoCloseable {
    val captureNames: List<String>
    val patternCount: Int

    /** Pattern [pattern]'s #set! / #is? / #is-not? directives, in source order. */
    fun settings(pattern: Int): List<PatternSetting>

    /** [start, end, captureIndex, patternIndex]* over nodes intersecting [start, end); predicates applied. */
    fun captures(tree: TreeHandle, start: Int, end: Int, text: TextSource): Captures

    /**
     * The same captures grouped per match (injections need a match's captures together); the
     * nodes of capture [childrenOf] (-1: none) also report their children.
     */
    fun matches(tree: TreeHandle, start: Int, end: Int, text: TextSource, childrenOf: Int = -1): Matches
}
