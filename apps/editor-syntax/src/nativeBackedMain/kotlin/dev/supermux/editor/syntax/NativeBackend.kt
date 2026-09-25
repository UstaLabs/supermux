package dev.supermux.editor.syntax

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [SyntaxBackend] over the native ses_* binding ([SyntaxParser], [SyntaxTree], [SyntaxQuery]): a
 * thin adapter, no logic of its own beyond table loading.
 *
 * [tables] reads a code-only grammar's `.sesz` blob ([tablesPath]) from the app's resources; null
 * when the resource is missing.
 */
class NativeBackend(
    private val tables: (language: String) -> ByteArray? = { SyntaxResources.read(tablesPath(it)) },
) : SyntaxBackend {
    override val languages: Set<String> by lazy { SyntaxLanguages.names().toSet() }

    override fun isReady(language: String): Boolean = language in languages && SyntaxLanguages.hasTables(language)

    override suspend fun ensureLanguage(language: String) = ensureLanguageNow(language)

    /** [ensureLanguage] without suspending: native loading is synchronous (a resource read, an inflate). */
    fun ensureLanguageNow(language: String) {
        if (language !in languages) throw SyntaxException("unknown language $language", SyntaxStatus.UNKNOWN_LANGUAGE)
        if (!SyntaxLanguages.hasTables(language)) {
            val blob = tables(language)
                ?: throw SyntaxException("no tables for $language: resource ${tablesPath(language)} is missing", SyntaxStatus.NO_TABLES)
            SyntaxLanguages.provideTables(language, blob)
        }
        SyntaxLanguages.load(language)
    }

    override fun newParser(language: String): ParserHandle = NativeParser(SyntaxParser(language))

    override fun newQuery(language: String, source: String): QueryHandle = NativeQuery(SyntaxQuery(language, source))

    // Copy-on-write, CAS-updated: any worker thread may ask; a query compiled twice in a race is
    // freed by the loser. A ses_query is immutable and its Kotlin regexes thread-safe, so sharing is fine.
    @OptIn(ExperimentalAtomicApi::class)
    private val shared = AtomicReference<Map<String, QueryHandle>>(emptyMap())

    @OptIn(ExperimentalAtomicApi::class)
    override fun sharedQuery(language: String, source: String): QueryHandle {
        val key = language + "\u0000" + source
        shared.load()[key]?.let { return it }
        val q = SharedQuery(SyntaxQuery(language, source))
        while (true) {
            val cur = shared.load()
            cur[key]?.let { q.query.close(); return it }
            if (shared.compareAndSet(cur, cur + (key to q))) return q
        }
    }

    companion object {
        /** Where a code-only grammar's tables blob lives among the app's resources. */
        fun tablesPath(language: String) = LanguageRegistry.tablesResource(language)
    }
}

private class NativeParser(private val parser: SyntaxParser) : ParserHandle {
    override val language: String get() = parser.language
    override fun setIncludedRanges(ranges: IntArray, points: PointSource) {
        if (ranges.isEmpty()) return parser.setIncludedRanges(IntArray(0))
        val out = IntArray(ranges.size * 3)
        for (i in ranges.indices step 2) {
            val a = points.pointAt(ranges[i])
            val b = points.pointAt(ranges[i + 1])
            val o = i * 3
            out[o] = ranges[i]; out[o + 1] = ranges[i + 1]
            out[o + 2] = PointSource.row(a); out[o + 3] = PointSource.column(a)
            out[o + 4] = PointSource.row(b); out[o + 5] = PointSource.column(b)
        }
        parser.setIncludedRanges(out)
    }
    override fun reset() = parser.reset()
    override fun setTimeoutMicros(micros: Long) = parser.setTimeoutMicros(micros)
    override fun parse(text: TextSource, old: TreeHandle?): TreeHandle = NativeTree(parser.parse(text, (old as NativeTree?)?.tree))
    override fun close() = parser.close()
}

internal class NativeTree(val tree: SyntaxTree) : TreeHandle {
    override fun edit(e: TextEdit) = tree.edit(e)
    override fun copy(): TreeHandle = NativeTree(tree.copy())
    override fun changedRanges(new: TreeHandle): IntArray = tree.changedRanges((new as NativeTree).tree)
    override val hasError: Boolean get() = tree.hasError
    override fun close() = tree.close()
}

/** A backend-owned query: callers must not free it, so [close] does nothing. */
private class SharedQuery(query: SyntaxQuery) : NativeQuery(query) {
    override fun close() {}
}

private open class NativeQuery(val query: SyntaxQuery) : QueryHandle {
    override val captureNames: List<String> get() = query.captureNames
    override val patternCount: Int get() = query.patternCount
    override fun settings(pattern: Int): List<PatternSetting> = query.patternSettings(pattern)
    override fun captures(tree: TreeHandle, start: Int, end: Int, text: TextSource): Captures =
        query.captures((tree as NativeTree).tree, start, end, text)
    override fun matches(tree: TreeHandle, start: Int, end: Int, text: TextSource, childrenOf: Int): Matches =
        query.matches((tree as NativeTree).tree, start, end, text, childrenOf)
    override fun close() { query.close() }
}
