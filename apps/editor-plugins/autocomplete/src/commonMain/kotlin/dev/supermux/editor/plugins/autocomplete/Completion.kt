package dev.supermux.editor.plugins.autocomplete

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorState

/**
 * One completion option (CM6's `Completion`), as data.
 *
 * - [label] is what the typed text is matched against (and inserted, without [apply]);
 *   [displayLabel] is shown instead when given (an LSP item's label, its filterText the [label]).
 * - [detail] shows after the label, [info] in the side panel; [resolveInfo] fetches the info
 *   lazily when the option is selected (LSP `completionItem/resolve`).
 * - [type] picks the icon: `class`, `constant`, `enum`, `function`, `interface`, `keyword`, `method`,
 *   `namespace`, `property`, `text`, `type`, `variable`, `snippet`, `module`, `field` (CM6's).
 * - [boost] (-99..99) moves it up or down among equally good matches; [sortText] sorts equals.
 */
data class Completion(
    val label: String,
    val displayLabel: String? = null,
    val detail: String? = null,
    val info: String? = null,
    val type: String? = null,
    val boost: Int = 0,
    val apply: CompletionApply? = null,
    val sortText: String? = null,
    val resolveInfo: (suspend () -> String?)? = null,
)

/** What accepting an option does, besides inserting its label. */
sealed interface CompletionApply {
    /** Insert this text instead of the label (at every cursor where the typed text is the same). */
    data class Text(val text: String) : CompletionApply

    /** Insert a [Snippet] template (tab stops; CM6's syntax, or LSP's through [Snippet.fromLsp]). */
    data class Template(val snippet: Snippet) : CompletionApply

    /**
     * Insert [text] (a snippet when [snippet]) and apply [edits] elsewhere in the same transaction (an
     * LSP item's `additionalTextEdits`: an import); edits overlapping the inserted range are dropped.
     */
    data class WithEdits(val text: String, val edits: List<ChangeSpec>, val snippet: Boolean = false) : CompletionApply

    /** Anything else: dispatch it yourself, with userEvent `input.complete`. */
    fun interface Custom : CompletionApply {
        fun apply(target: CommandTarget, completion: Completion, from: Int, to: Int)
    }
}

/**
 * Where completion is asked (CM6's `CompletionContext`): the [state], the cursor [pos], whether the
 * user asked ([explicit]: `Ctrl-Space`) or it came from typing, and the [triggerCharacter] typed
 * (one of the sources' trigger characters), if that is why.
 */
class CompletionContext(
    val state: EditorState,
    val pos: Int,
    val explicit: Boolean,
    val triggerCharacter: String? = null,
) {
    /**
     * The text before [pos] on its line matching [regex] up to [pos] (CM6's `matchBefore`): its
     * start and text, or null. Looks at most 250 units back.
     */
    fun matchBefore(regex: Regex): Pair<Int, String>? {
        val line = state.doc.lineAt(pos)
        val start = maxOf(line.from, pos - 250)
        val text = state.doc.slice(start, pos)
        val m = Regex("(?:${regex.pattern})$", regex.options).find(text) ?: return null
        return (start + m.range.first) to m.value
    }

    /** The identifier characters before [pos] (letters, digits, `_`, `$`): where a word completion starts. */
    fun wordStart(): Int {
        val doc = state.doc
        val line = doc.lineAt(pos)
        var a = pos
        while (a > line.from) {
            val c = doc.charAt(a - 1)
            if (c.isLetterOrDigit() || c == '_' || c == '$') a-- else break
        }
        return a
    }
}

/**
 * A source's answer (CM6's `CompletionResult`): the [options] for the text from [from] (to the
 * cursor, or to [to]). While the user keeps typing and the text from [from] still matches [validFor],
 * the options are filtered again without asking the source; with [validFor] null every keystroke
 * asks again. [filter] false: the source filtered and sorted them itself (shown as they are).
 */
data class CompletionResult(
    val from: Int,
    val options: List<Completion>,
    val to: Int? = null,
    val validFor: Regex? = null,
    val filter: Boolean = true,
)

/**
 * Where options come from (CM6's `CompletionSource`): suspend, and cancellable (a newer keystroke
 * cancels the call); null or an empty result is no completion. Runs on the UI thread's scope: a
 * source with heavy work moves it off (the LSP source parses on a worker on native).
 */
fun interface CompletionSource {
    suspend fun complete(context: CompletionContext): CompletionResult?
}
