package dev.supermux.editor.core

/**
 * What the text at a position is, as the language layer sees it: its [kind] (code, or inside a
 * string or a comment) and the [language] of the layer it belongs to (an injection: a fenced code
 * block in Markdown, a `<script>` in HTML; null: not known). Bracket matching counts only brackets
 * of the SAME context as the one at the cursor, so a `(` in a string never matches a `)` in code,
 * nor a `(` in Markdown prose one inside a fence (CM6 compares syntax-tree token types the same way).
 */
data class TokenContext(val kind: Kind, val language: String? = null) {
    enum class Kind { CODE, STRING, COMMENT }

    companion object {
        val CODE = TokenContext(Kind.CODE)
        val STRING = TokenContext(Kind.STRING)
        val COMMENT = TokenContext(Kind.COMMENT)
    }
}

/**
 * A language layer's answer for the character at [pos] (editor-syntax provides one from its spans
 * and layers): null when it does not know (no syntax there, or outside what it has parsed): callers
 * then treat that position as they would without a language layer (a plain scan).
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

    /**
     * True when this service's answer for the line starting at [lineFrom] is definitive (it has
     * parsed there): a null [foldable] then means "no fold", and no fallback (indentation) is asked.
     */
    fun knows(state: EditorState, lineFrom: Int): Boolean = false
}

/** Every [FoldService], highest precedence first; the first non-null answer wins. */
val foldServiceFacet: Facet<FoldService, List<FoldService>> = Facet.list("foldService")

/**
 * A plugin's effects to undo WITH a transaction (CM6's `invertedEffects`): given a transaction about
 * to be recorded by a history, the effects, in the document BEFORE it, that restore what it changed
 * of the plugin's state (the fold plugin: a fold a deletion removed). Any history reads it; a plugin
 * registering here needs no history dependency.
 */
val invertedEffectsFacet: Facet<(Transaction) -> List<StateEffect<*>>, List<(Transaction) -> List<StateEffect<*>>>> = Facet.list("invertedEffects")

/**
 * A language's comment syntax (CM6's `commentTokens` language data): its [line] comment token
 * (`//`, `#`) and its [block] comment's open and close (`/*`, `*/`); either may be absent.
 */
data class CommentTokens(val line: String? = null, val block: BlockComment? = null) {
    data class BlockComment(val open: String, val close: String)
}

/** The language layer's comment tokens at a position (an injected layer's own: a `<script>` in HTML), or null. */
fun interface CommentTokensProvider {
    fun tokensAt(state: EditorState, pos: Int): CommentTokens?
}

/** The language layer's [CommentTokensProvider], if any (the first wins). editor-syntax provides one. */
val commentTokensFacet: Facet<CommentTokensProvider, CommentTokensProvider?> = Facet.define("commentTokens") { it.firstOrNull() }

/**
 * Select the syntax node around each selection range (CM6's `selectParentSyntax`): a language layer
 * that knows the syntax tree answers it. The tree may live on another thread, so the service may
 * answer later (with its own `select` transaction); [selectParent] says whether it took the request.
 */
fun interface SelectParentService {
    fun selectParent(target: CommandTarget): Boolean
}

/** The language layer's [SelectParentService], if any (the first wins). */
val selectParentFacet: Facet<SelectParentService, SelectParentService?> = Facet.define("selectParent") { it.firstOrNull() }
