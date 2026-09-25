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

    /**
     * Make [language] usable: its tables are bundled, or provided now from the app's resources.
     * Idempotent. Throws [SyntaxException] (NO_TABLES naming the missing resource, UNKNOWN_LANGUAGE).
     */
    fun ensureLanguage(language: String)

    fun newParser(language: String): ParserHandle
    fun newQuery(language: String, source: String): QueryHandle
}

interface ParserHandle : AutoCloseable {
    val language: String

    /** Restrict the next parses to these UTF-16 ranges ([start,end]* packed); empty = whole document. */
    fun setIncludedRanges(ranges: IntArray, text: TextSource)

    fun setTimeoutMicros(micros: Long)

    /** Parse; [old] must already carry every edit since it was produced. Throws SyntaxException(TIMEOUT). */
    fun parse(text: TextSource, old: TreeHandle?): TreeHandle
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
}
