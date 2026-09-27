package dev.supermux.editor.plugins.basics

import dev.supermux.editor.compose.InputHandler
import dev.supermux.editor.compose.inputHandlerFacet
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.Prec
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapOf

/**
 * What [CloseBrackets] pairs: the opening characters it closes ([brackets]; `(`→`)`, `[`→`]`,
 * `{`→`}`, a quote closes with itself) and the characters before which an opening BRACKET is still
 * closed ([before], besides whitespace and the line's end). A language plugin provides its own
 * through [closeBracketsConfig] (Markdown might add `*`, Rust drop `'`).
 */
data class CloseBracketsConfig(
    val brackets: List<Char> = listOf('(', '[', '{', '\'', '"', '`'),
    // CM6's default. (`,` is not in it: before a comma a bracket is typed alone, as in CM6.)
    val before: String = ")]}:;>",
)

/** The [CloseBracketsConfig] in effect: the highest-precedence one, else the default. */
val closeBracketsConfig: Facet<CloseBracketsConfig, CloseBracketsConfig> = Facet.first("closeBrackets", CloseBracketsConfig())

/**
 * Closing brackets, CM6's `closeBrackets` (without its syntax awareness: pairing is not yet
 * suppressed inside strings or comments; that comes with the syntax tree, see the README):
 *
 * - typing an opening bracket inserts the pair, the cursor between, when the next character is
 *   whitespace, the line's end or one of [CloseBracketsConfig.before]; otherwise just the bracket;
 * - a quote pairs only when neither the character before nor the one after is a word character
 *   (so `don't` and `a"b` type plainly), and steps over a quote right after the cursor;
 * - typing a closing bracket (or quote) steps over the one right after the cursor only when this
 *   plugin inserted it (tracked in [insertedClosers], moved through every change, as CM6 does);
 * - with a selection, an opening character wraps it (the selection stays on the wrapped text);
 * - Backspace between an empty pair deletes both;
 * - at every cursor, all or nothing (CM6): when any cursor would type the character plainly, it is
 *   typed plainly at every cursor.
 *
 * Typed text reaches it from every input path (the hidden field of a soft or hardware keyboard,
 * the web's key path, [dev.supermux.editor.compose.EditorView.typeText]) through
 * [inputHandlerFacet]; Backspace is a key binding for hardware keys, and a soft keyboard's
 * Backspace arrives as a deletion through the same input handler.
 */
object CloseBrackets {
    /** The input handler: typed text and a soft keyboard's one-character deletion. */
    val inputHandler: InputHandler = InputHandler { t, from, to, text -> handleInput(t, from, to, text) }

    /** Backspace between an empty pair deletes both (false elsewhere: the default Backspace runs). */
    val deleteBracketPair: Command = Command { t -> deletePairs(t) }

    /** Closers this plugin inserted, at their positions in the new document. */
    private val insertedAt = StateEffectType<List<Int>>("closeBrackets.inserted")

    /** A tracked closer was stepped over (it is the user's now). */
    private val steppedOver = StateEffectType<List<Int>>("closeBrackets.steppedOver")

    /**
     * The positions of the closing characters this plugin inserted and the user has not stepped over
     * yet, moved through every change (one deleted or replaced is forgotten).
     */
    val insertedClosers: StateField<List<Int>> = StateField("closeBrackets.closers", { emptyList() }, { value: List<Int>, tr: dev.supermux.editor.core.Transaction ->
        var v = value
        if (tr.docChanged && v.isNotEmpty()) v = v.mapNotNull { p ->
            val a = tr.changes.mapPos(p, 1)
            val b = tr.changes.mapPos(p + 1, -1)
            if (b - a == 1) a else null
        }
        for (e in tr.effects) {
            e.valueIf(steppedOver)?.let { gone -> v = v - gone.toSet() }
            e.valueIf(insertedAt)?.let { added -> v = (v + added).distinct().sorted() }
        }
        v
    })

    /** The plugin: the input handler, the Backspace binding (above the defaults) and, optionally, a config. */
    fun extension(config: CloseBracketsConfig? = null): Extension = extensionOf(
        insertedClosers,
        inputHandlerFacet.of(inputHandler),
        Prec.high(keymapOf(KeyBinding("Backspace", deleteBracketPair))),
        if (config != null) closeBracketsConfig.of(config) else extensionOf(),
    )

    /** The closing character for [open]. */
    fun closing(open: Char): Char = when (open) { '(' -> ')'; '[' -> ']'; '{' -> '}'; '<' -> '>'; else -> open }

    private fun isQuote(c: Char, config: CloseBracketsConfig) = c in config.brackets && closing(c) == c

    private fun handleInput(t: CommandTarget, from: Int, to: Int, text: String): Boolean {
        val st = t.state
        val main = st.selection.main
        val config = st.facet(closeBracketsConfig)
        if (text.isEmpty()) {
            // A soft keyboard's Backspace: one unit before an empty main range.
            return main.empty && from == main.head - 1 && to == main.head && deletePairs(t)
        }
        // Only plain typing over the main range (not an IME's replacement of a word around it).
        if (text.length != 1 || from != main.from || to != main.to) return false
        val ch = text[0]
        val opening = ch in config.brackets
        val closer = !opening && config.brackets.any { closing(it) == ch }
        if (!opening && !closer) return false
        val doc = st.doc
        val tracked = st.fieldOrNull(insertedClosers).orEmpty()
        val specs = ArrayList<ChangeSpec>()
        val kinds = ArrayList<Kind>()
        for (r in st.selection.ranges) {
            val next = if (r.to < doc.length) doc.charAt(r.to) else null
            val kind = when {
                opening && !r.empty -> Kind.WRAP
                r.empty && next == ch && (closer || isQuote(ch, config)) && r.head in tracked -> Kind.STEP_OVER
                opening && r.empty && shouldPair(doc, r.head, ch, config) -> Kind.PAIR
                else -> Kind.PLAIN
            }
            // All or nothing (CM6): one cursor that would type it plainly makes it plain everywhere.
            if (kind == Kind.PLAIN) return false
            when (kind) {
                Kind.WRAP -> { specs += ChangeSpec(r.from, r.from, ch.toString()); specs += ChangeSpec(r.to, r.to, closing(ch).toString()) }
                Kind.STEP_OVER -> Unit
                Kind.PAIR -> specs += ChangeSpec(r.head, r.head, "$ch${closing(ch)}")
                Kind.PLAIN -> specs += ChangeSpec(r.from, r.to, ch.toString())
            }
            kinds += kind
        }
        val changes = ChangeSet.of(doc.length, specs.sortedWith(compareBy({ it.from }, { it.to })))
        val ranges = st.selection.ranges.mapIndexed { i, r ->
            when (kinds[i]) {
                // The selection stays on the wrapped text, inside the new pair.
                Kind.WRAP -> SelectionRange(
                    changes.mapPos(r.anchor, if (r.anchor == r.from) 1 else -1),
                    changes.mapPos(r.head, if (r.head == r.from) 1 else -1),
                )
                Kind.STEP_OVER -> SelectionRange(changes.mapPos(r.head, 1) + 1)
                Kind.PAIR -> SelectionRange(changes.mapPos(r.head, -1) + 1)
                Kind.PLAIN -> SelectionRange(changes.mapPos(r.to, 1))
            }
        }
        // Where the closers this inserts end up, and which tracked ones were stepped over.
        val added = st.selection.ranges.mapIndexedNotNull { i, r ->
            when (kinds[i]) {
                Kind.PAIR -> changes.mapPos(r.head, -1) + 1
                Kind.WRAP -> changes.mapPos(r.to, -1)
                else -> null
            }
        }
        val stepped = st.selection.ranges.filterIndexed { i, _ -> kinds[i] == Kind.STEP_OVER }.map { it.head }
        val effects = buildList {
            if (stepped.isNotEmpty()) add(steppedOver.of(stepped))
            if (added.isNotEmpty()) add(insertedAt.of(added))
        }
        t.dispatch(TransactionSpec(changeSet = changes, selection = EditorSelection.create(ranges, st.selection.mainIndex), effects = effects, scrollIntoView = true, userEvent = "input.type"))
        return true
    }

    private enum class Kind { WRAP, STEP_OVER, PAIR, PLAIN }

    /** An opening [ch] at [pos] gets its closer: CM6's rules (see [CloseBrackets]). */
    private fun shouldPair(doc: Rope, pos: Int, ch: Char, config: CloseBracketsConfig): Boolean {
        val next = if (pos < doc.length) doc.charAt(pos) else null
        if (isQuote(ch, config)) return !isWordAt(doc, pos) && !isWordBefore(doc, pos)
        return next == null || next == '\n' || next.isWhitespace() || next in config.before
    }

    private fun deletePairs(t: CommandTarget): Boolean {
        val st = t.state
        val config = st.facet(closeBracketsConfig)
        val doc = st.doc
        val ranges = st.selection.ranges
        if (ranges.any { !it.empty }) return false
        fun betweenPair(pos: Int): Boolean {
            if (pos <= 0 || pos >= doc.length) return false
            val before = doc.charAt(pos - 1)
            return before in config.brackets && doc.charAt(pos) == closing(before)
        }
        if (!betweenPair(st.selection.main.head)) return false
        // Every cursor: its pair when it is between one, else one character (a plain Backspace).
        val specs = ranges.mapNotNull { r ->
            when {
                betweenPair(r.head) -> ChangeSpec(r.head - 1, r.head + 1)
                r.head > 0 -> ChangeSpec(prevUnit(doc, r.head), r.head)
                else -> null
            }
        }.distinctBy { it.from }
        val changes = ChangeSet.of(doc.length, specs.sortedBy { it.from })
        val next = ranges.map { SelectionRange(changes.mapPos(it.head, -1)) }
        t.dispatch(TransactionSpec(changeSet = changes, selection = EditorSelection.create(next, st.selection.mainIndex), scrollIntoView = true, userEvent = "delete.backward"))
        return true
    }
}

/** One code point back (a surrogate pair together). */
internal fun prevUnit(doc: Rope, pos: Int): Int =
    if (pos >= 2 && doc.charAt(pos - 1).isLowSurrogate() && doc.charAt(pos - 2).isHighSurrogate()) pos - 2 else pos - 1

/** A word character: a letter, a digit or `_` (a surrogate pair is never one here: emoji, symbols). */
internal fun isWordChar(c: Char): Boolean = c == '_' || (!c.isSurrogate() && c.isLetterOrDigit())

internal fun isWordAt(doc: Rope, pos: Int): Boolean = pos < doc.length && isWordChar(doc.charAt(pos))

internal fun isWordBefore(doc: Rope, pos: Int): Boolean = pos > 0 && isWordChar(doc.charAt(pos - 1))
