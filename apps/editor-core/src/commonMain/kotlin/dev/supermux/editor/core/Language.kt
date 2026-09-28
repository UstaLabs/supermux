package dev.supermux.editor.core

/**
 * What the text at a position is, as the language layer sees it: code, or inside a string or a
 * comment. Bracket matching counts only brackets of the same kind as the one at the cursor, so a
 * `(` in a string never matches a `)` in code (CM6 compares syntax-tree token types the same way).
 */
enum class TokenContext { CODE, STRING, COMMENT }

/**
 * A language layer's answer for the character at [pos] (editor-syntax provides one from its spans):
 * null when it does not know (no syntax there yet), which callers treat as [TokenContext.CODE].
 */
fun interface TokenContextProvider {
    fun contextAt(state: EditorState, pos: Int): TokenContext?
}

/** The language layer's [TokenContextProvider], if any (the first provider wins). */
val tokenContextFacet: Facet<TokenContextProvider, TokenContextProvider?> = Facet.define("tokenContext") { it.firstOrNull() }

/** A fold's range: [from] is the end of its first line, [to] where the hidden text ends. */
data class FoldRange(val from: Int, val to: Int) {
    init { require(from in 0..to) { "invalid fold range $from..$to" } }
}

/**
 * A language layer's folds (CM6's `foldService`): the range that folds at the line spanning
 * [lineFrom, lineTo], or null. editor-syntax provides one from tree-sitter's `folds.scm`; the fold
 * plugin falls back to indentation when none answers.
 */
fun interface FoldService {
    fun foldable(state: EditorState, lineFrom: Int, lineTo: Int): FoldRange?
}

/** Every [FoldService], highest precedence first; the first non-null answer wins. */
val foldServiceFacet: Facet<FoldService, List<FoldService>> = Facet.list("foldService")
