package dev.supermux.editor.syntax

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

    override fun ensureLanguage(language: String) {
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

    companion object {
        /** Where a code-only grammar's tables blob lives among the app's resources. */
        fun tablesPath(language: String) = "editor-syntax/tables/$language.sesz"
    }
}

private class NativeParser(private val parser: SyntaxParser) : ParserHandle {
    override val language: String get() = parser.language
    override fun setIncludedRanges(ranges: IntArray, text: TextSource) = parser.setIncludedRanges(ranges, text)
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

private class NativeQuery(private val query: SyntaxQuery) : QueryHandle {
    override val captureNames: List<String> get() = query.captureNames
    override val patternCount: Int get() = query.patternCount
    override fun settings(pattern: Int): List<PatternSetting> = query.patternSettings(pattern)
    override fun captures(tree: TreeHandle, start: Int, end: Int, text: TextSource): Captures =
        query.captures((tree as NativeTree).tree, start, end, text)
    override fun close() = query.close()
}
