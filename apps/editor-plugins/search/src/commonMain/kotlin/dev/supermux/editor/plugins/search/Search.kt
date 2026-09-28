package dev.supermux.editor.plugins.search

import dev.supermux.editor.compose.EditorViewport
import dev.supermux.editor.compose.revealFacet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.NamedCommand
import dev.supermux.editor.core.Panel
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapFacet
import dev.supermux.editor.core.panelsFacet

/**
 * The search plugin's options: the panel at the [top] (CM6's default is the bottom; supermux's
 * search bar has always been at the top) and the flags a new query starts with.
 */
data class SearchConfig(
    val top: Boolean = true,
    val caseSensitive: Boolean = false,
    val regexp: Boolean = false,
    val wholeWord: Boolean = false,
)

/**
 * The plugin's state: the [query], whether the search panel is [open] (and its replace row
 * [replaceOpen]), whether the go-to-line panel is [gotoLineOpen], and the focus requests the panels
 * follow (a counter: each open asks the field to take the focus again).
 */
data class SearchState(
    val query: SearchQuery,
    val open: Boolean = false,
    val replaceOpen: Boolean = false,
    val focusRequest: Int = 0,
    val gotoLineOpen: Boolean = false,
    val gotoLineFocusRequest: Int = 0,
)

/** "3 of 17": the [current] match (1-based; 0: the selection is none), of [total]; [error]: a bad pattern. */
data class MatchInfo(val current: Int, val total: MatchCount, val error: String? = null) {
    val label: String get() = when {
        error != null -> "Invalid regex: $error"
        total.count == 0 -> "No results"
        current > 0 -> "$current of ${total.label}"
        total.count == 1 && !total.capped -> "1 match"
        else -> "${total.label} matches"
    }
}

/**
 * Search and replace, CM6's `@codemirror/search` in Kotlin, on the engine of [SearchQuery]:
 *
 * - **State**: [field] (a [SearchState]), changed by [setQueryEffect], [togglePanel],
 *   [toggleReplace], [focusPanel] and [toggleGotoLine]. The panels come from it (editor-core's
 *   `panelsFacet`, `panel:search` / `panel:goto-line` content: [registerWidgets]).
 * - **Marks**: while the panel is open, every match in editor-compose's [EditorViewport] gets a
 *   [MATCH_CLASS] mark, and a match that IS a selection range [SELECTED_CLASS] too.
 * - **Commands** (CM6's names and keys, [keymap]): [openSearchPanel] `Mod-f`, [closeSearchPanel]
 *   `Escape`, [findNext] `Mod-g` / `F3`, [findPrevious] `Mod-Shift-g` / `Shift-F3`, [selectMatches]
 *   `Alt-Enter` (Apple `Mod-Alt-Enter`), [selectSelectionMatches] `Mod-Shift-l`, [replaceNext],
 *   [replaceAll], [selectNextOccurrence] `Mod-d`, [gotoLine] `Mod-Alt-g`.
 * - **userEvents**: `select.search` (moving between matches), `select.search.matches` (every match
 *   selected), `input.replace` (one), `input.replace.all` (one transaction: one undo step).
 * - **Folds**: a match is selected with `scrollIntoView`, so a fold holding it opens (the surface
 *   asks editor-compose's `revealFacet`); replace all asks the same for every match first.
 */
object Search {
    const val PANEL = "search"
    const val GOTO_PANEL = "goto-line"
    const val MATCH_CLASS = "search-match"
    const val SELECTED_CLASS = "search-match-selected"

    /** Matches [selectMatches] makes into selection ranges at most (CM6's 1,000). */
    const val SELECT_LIMIT = 1000

    val setQueryEffect: StateEffectType<SearchQuery> = StateEffectType("search.setQuery")
    val togglePanel: StateEffectType<Boolean> = StateEffectType("search.togglePanel")
    val toggleReplace: StateEffectType<Boolean> = StateEffectType("search.toggleReplace")
    val focusPanel: StateEffectType<Unit> = StateEffectType("search.focusPanel")
    val toggleGotoLine: StateEffectType<Boolean> = StateEffectType("search.toggleGotoLine")

    internal val config: Facet<SearchConfig, SearchConfig> = Facet.first("search.config", SearchConfig())

    val field: StateField<SearchState> = StateField(
        "search",
        { st -> st.facet(config).let { c -> SearchState(SearchQuery("", c.caseSensitive, c.regexp, c.wholeWord)) } },
        { v, tr ->
            var s = v
            for (e in tr.effects) {
                e.valueIf(setQueryEffect)?.let { s = s.copy(query = it) }
                e.valueIf(togglePanel)?.let { s = s.copy(open = it) }
                e.valueIf(toggleReplace)?.let { s = s.copy(replaceOpen = it) }
                e.valueIf(focusPanel)?.let { s = s.copy(focusRequest = s.focusRequest + 1) }
                e.valueIf(toggleGotoLine)?.let { s = s.copy(gotoLineOpen = it, gotoLineFocusRequest = if (it) s.gotoLineFocusRequest + 1 else s.gotoLineFocusRequest) }
            }
            s
        },
    )

    /** The plugin's state in [st] (a default one when the plugin is not installed). */
    fun state(st: EditorState): SearchState = st.fieldOrNull(field) ?: SearchState(SearchQuery(""))

    fun query(st: EditorState): SearchQuery = state(st).query

    fun isOpen(st: EditorState): Boolean = state(st).open

    /** Set the query (the panel's fields do this as the user types). */
    fun setQuery(target: CommandTarget, query: SearchQuery) {
        if (query != query(target.state)) target.dispatch(TransactionSpec(effects = listOf(setQueryEffect.of(query))))
    }

    // ------------------------------------------------------------------------ commands --

    /**
     * Open the panel (or, open, give its field the focus again), its query filled from the main
     * selection when that is one line of at most 100 characters, else from the word at an empty
     * cursor, else the last query (CM6 fills from the selection only).
     */
    val openSearchPanel: Command = Command { t ->
        val st = t.state
        val s = state(st)
        val q = defaultQuery(st, s.query)
        t.dispatch(TransactionSpec(effects = listOfNotNull(
            if (!s.open) togglePanel.of(true) else null,
            if (s.gotoLineOpen) toggleGotoLine.of(false) else null,
            if (q != s.query) setQueryEffect.of(q) else null,
            focusPanel.of(Unit),
        )))
        true
    }

    /** Close the panel; false (the key goes on) when it is closed. The panel hands the focus back. */
    val closeSearchPanel: Command = Command { t ->
        val s = state(t.state)
        when {
            s.open -> { t.dispatch(TransactionSpec(effects = listOf(togglePanel.of(false)))); true }
            s.gotoLineOpen -> { t.dispatch(TransactionSpec(effects = listOf(toggleGotoLine.of(false)))); true }
            else -> false
        }
    }

    /** CM6's searchCommand: with a valid query run [f], else open the panel. */
    private fun searchCommand(f: (CommandTarget, SearchQuery) -> Boolean): Command = Command { t ->
        val q = query(t.state)
        if (q.valid) f(t, q) else openSearchPanel.run(t)
    }

    private fun select(t: CommandTarget, m: SearchMatch): Boolean {
        t.dispatch(TransactionSpec(selection = EditorSelection.single(m.from, m.to), scrollIntoView = true, userEvent = "select.search"))
        return true
    }

    /** Select the next match after the main selection, wrapping (the panel opens without a query). */
    val findNext: Command = searchCommand { t, q ->
        val main = t.state.selection.main
        q.nextMatch(t.state.doc, main.from, main.to)?.let { select(t, it) } ?: false
    }

    /** Select the match before the main selection, wrapping to the end. */
    val findPrevious: Command = searchCommand { t, q ->
        val main = t.state.selection.main
        q.prevMatch(t.state.doc, main.from, main.to)?.let { select(t, it) } ?: false
    }

    /**
     * Every match a selection range (multi-cursor), the one at or after the main cursor the main
     * range; nothing past [SELECT_LIMIT] matches (CM6).
     */
    val selectMatches: Command = searchCommand { t, q ->
        val st = t.state
        val ms = q.matchAll(st.doc, SELECT_LIMIT)
        if (ms.isNullOrEmpty()) false else {
            val head = st.selection.main.from
            val main = ms.indexOfFirst { it.to >= head }.let { if (it < 0) ms.size - 1 else it }
            t.dispatch(TransactionSpec(selection = EditorSelection.create(ms.map { SelectionRange(it.from, it.to) }, main), userEvent = "select.search.matches"))
            true
        }
    }

    /** Every occurrence of the (one, non-empty) selected text selected, CM6's `selectSelectionMatches`. */
    val selectSelectionMatches: Command = Command { t ->
        val st = t.state
        val sel = st.selection
        if (sel.ranges.size > 1 || sel.main.empty) return@Command false
        val main = sel.main
        val text = st.doc.slice(main.from, main.to)
        val ranges = ArrayList<SelectionRange>()
        var mainIndex = 0
        val c = LiteralCursor(st.doc, text, fold = false, wholeWord = false, from = 0, to = st.doc.length)
        while (c.hasNext()) {
            val m = c.next()
            if (ranges.size > SELECT_LIMIT) return@Command false
            if (m.from == main.from) mainIndex = ranges.size
            ranges += SelectionRange(m.from, m.to)
        }
        t.dispatch(TransactionSpec(selection = EditorSelection.create(ranges, mainIndex), userEvent = "select.search.matches"))
        true
    }

    /**
     * CM6's `replaceNext`: when the main selection is a match, replace it and select the next one;
     * else select the next match (the first press selects, each next one replaces).
     */
    val replaceNext: Command = searchCommand { t, q ->
        val st = t.state
        val main = st.selection.main
        val match = q.nextMatch(st.doc, main.from, main.from) ?: return@searchCommand false
        if (match.from == main.from && match.to == main.to) {
            val insert = q.replacement(match)
            val change = dev.supermux.editor.core.ChangeSet.of(st.doc.length, listOf(ChangeSpec(match.from, match.to, insert)))
            val next = q.nextMatch(st.doc, match.from, match.to)
            val selection = next?.let { EditorSelection.single(change.mapPos(it.from, 1), change.mapPos(it.to, 1)) }
                ?: EditorSelection.cursor(match.from + insert.length)
            t.dispatch(TransactionSpec(changeSet = change, selection = selection, scrollIntoView = true, userEvent = "input.replace"))
        } else {
            select(t, match)
        }
        true
    }

    /** Replace every match, in ONE transaction (one undo step). Folds holding a match open first. */
    val replaceAll: Command = searchCommand { t, q ->
        val ms = q.matchAll(t.state.doc, Int.MAX_VALUE).orEmpty()
        if (ms.isEmpty()) return@searchCommand false
        reveal(t, ms)
        val st = t.state
        t.dispatch(TransactionSpec(changes = ms.map { ChangeSpec(it.from, it.to, q.replacement(it)) }, userEvent = "input.replace.all"))
        st !== t.state
    }

    /**
     * Ask the reveal handlers (the fold plugin) to show every match: local input never edits inside
     * a fold (the surface refuses it), so the folds holding one open first, as a search into them does.
     */
    private fun reveal(t: CommandTarget, ms: List<SearchMatch>) {
        val handlers = t.state.facet(revealFacet)
        if (handlers.isEmpty()) return
        for (m in ms) for (h in handlers) if (h.reveal(t, m.from, maxOf(m.to, m.from))) break
    }

    /**
     * CM6's `selectNextOccurrence` (`Mod-d`): with an empty cursor, select the word at each cursor;
     * else add the next occurrence of the selected text after the last range (wrapping), whole-word
     * only when the selection is a whole word. The main range stays. False when every range's text
     * is not the same, or when there is no other occurrence.
     */
    val selectNextOccurrence: Command = Command { t ->
        val st = t.state
        val sel = st.selection
        if (sel.ranges.any { it.empty }) {
            val next = EditorSelection.create(sel.ranges.map { r -> wordAt(st.doc, r.head)?.let { (a, b) -> SelectionRange(a, b) } ?: r }, sel.mainIndex)
            if (next == sel) return@Command false
            t.dispatch(TransactionSpec(selection = next, userEvent = "select"))
            return@Command true
        }
        val first = sel.ranges[0]
        val text = st.doc.slice(first.from, first.to)
        if (sel.ranges.any { st.doc.slice(it.from, it.to) != text }) return@Command false
        val m = findNextOccurrence(st, text) ?: return@Command false
        t.dispatch(TransactionSpec(selection = sel.addRange(SelectionRange(m.from, m.to), makeMain = false), scrollIntoView = false, userEvent = "select"))
        true
    }

    private fun findNextOccurrence(st: EditorState, text: String): SearchMatch? {
        val doc = st.doc
        val ranges = st.selection.ranges
        val main = st.selection.main
        val word = wordAt(doc, main.head)
        val fullWord = word != null && word.first == main.from && word.second == main.to
        fun ok(m: SearchMatch): Boolean {
            if (!fullWord) return true
            val w = wordAt(doc, m.from)
            return w != null && w.first == m.from && w.second == m.to
        }
        val after = LiteralCursor(doc, text, fold = false, wholeWord = false, from = ranges.last().to, to = doc.length)
        while (after.hasNext()) { val m = after.next(); if (ok(m)) return m }
        val wrap = LiteralCursor(doc, text, fold = false, wholeWord = false, from = 0, to = doc.length)
        while (wrap.hasNext()) {
            val m = wrap.next()
            if (m.from >= ranges.last().from) break
            if (ranges.any { it.from == m.from }) continue
            if (ok(m)) return m
        }
        return null
    }

    /** Open the go-to-line panel (CM6's `gotoLine` dialog). */
    val gotoLine: Command = Command { t ->
        val s = state(t.state)
        t.dispatch(TransactionSpec(effects = listOfNotNull(if (s.open) togglePanel.of(false) else null, toggleGotoLine.of(true))))
        true
    }

    /**
     * Where [input] goes, CM6's syntax: a line number, `+n` / `-n` lines from the cursor's, `n%` of
     * the document, and `:column` after any of them; clamped to the document. Null: not that syntax.
     */
    fun gotoLineSelection(st: EditorState, input: String): EditorSelection? {
        val m = GOTO.matchEntire(input.trim()) ?: return null
        val (sign, ln, cl, percent) = m.destructured
        val doc = st.doc
        val start = doc.lineAt(st.selection.main.head)
        val col = if (cl.isNotEmpty()) cl.drop(1).toIntOrNull() ?: 0 else 0
        var line = ln.toIntOrNull() ?: start.number
        if (ln.isNotEmpty() && percent.isNotEmpty()) {
            var pc = line / 100.0
            if (sign.isNotEmpty()) pc = pc * (if (sign == "-") -1 else 1) + start.number.toDouble() / doc.lineCount
            line = kotlin.math.round(doc.lineCount * pc).toInt()
        } else if (ln.isNotEmpty() && sign.isNotEmpty()) {
            line = line * (if (sign == "-") -1 else 1) + start.number
        }
        val target = doc.line(line.coerceIn(1, doc.lineCount))
        return EditorSelection.cursor(target.from + col.coerceIn(0, target.length))
    }

    private val GOTO = Regex("""^([+-])?(\d+)?(:\d+)?(%)?$""")

    /** Go to [input] (see [gotoLineSelection]), scrolled into view, and close the go-to-line panel. */
    fun goToLine(target: CommandTarget, input: String): Boolean {
        val sel = gotoLineSelection(target.state, input)
        target.dispatch(TransactionSpec(selection = sel, scrollIntoView = sel != null, effects = listOf(toggleGotoLine.of(false)), userEvent = if (sel != null) "select" else null))
        return sel != null
    }

    // --------------------------------------------------------------------- match info --

    /** Which match the main selection is, of how many (counting stops at 10,000). */
    fun matchInfo(st: EditorState): MatchInfo {
        val q = query(st)
        if (q.error != null) return MatchInfo(0, MatchCount(0, false), q.error)
        if (!q.valid) return MatchInfo(0, MatchCount(0, false))
        val main = st.selection.main
        var n = 0
        var current = 0
        val c = q.cursor(st.doc)
        while (c.hasNext()) {
            val m = c.next()
            if (n == SearchQuery.MATCH_LIMIT) return MatchInfo(current, MatchCount(n, true))
            n++
            if (m.from == main.from && m.to == main.to) current = n
        }
        return MatchInfo(current, MatchCount(n, false))
    }

    // ---------------------------------------------------------------------- the query --

    private fun defaultQuery(st: EditorState, fallback: SearchQuery): SearchQuery {
        val main = st.selection.main
        val doc = st.doc
        val text = if (!main.empty) {
            if (main.to - main.from > 100) null else doc.slice(main.from, main.to).takeIf { it.indexOf('\n') < 0 }
        } else wordAt(doc, main.head)?.let { (a, b) -> doc.slice(a, b) }
        if (text.isNullOrEmpty()) return fallback
        return fallback.copy(search = if (fallback.regexp) escapeRegex(text) else text.replace("\\", "\\\\"))
    }

    private fun escapeRegex(s: String): String = buildString {
        for (c in s) { if (c in "\\^$.|?*+()[]{}") append('\\'); append(c) }
    }

    /** The word (letters, digits, `_`) touching [pos], at most 100 characters, or null. */
    internal fun wordAt(doc: Rope, pos: Int): Pair<Int, Int>? {
        val line = doc.lineIndexAt(pos)
        val lineStart = doc.lineStart(line)
        val lineEnd = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length
        var a = pos
        var b = pos
        while (a > lineStart && pos - a <= 100 && isWordChar(doc.charAt(a - 1))) a--
        while (b < lineEnd && b - pos <= 100 && isWordChar(doc.charAt(b))) b++
        return if (b > a && b - a <= 100) a to b else null
    }

    // --------------------------------------------------------------------- decorations --

    private val matchMark: Decoration = Decoration.Mark(setOf(MATCH_CLASS))
    private val selectedMark: Decoration = Decoration.Mark(setOf(MATCH_CLASS, SELECTED_CLASS))
    private val NONE: RangeSet<Decoration> = RangeSet.empty()

    /** Characters searched for marks at most (a viewport on a minified file's one line can be megabytes). */
    private const val MAX_SCAN = 200_000

    private fun marks(st: EditorState): RangeSet<Decoration> {
        val s = state(st)
        if (!s.open || !s.query.valid) return NONE
        val doc = st.doc
        val range = EditorViewport.rangeOf(st)
        var from = maxOf(0, range.first)
        var to = minOf(doc.length, range.last + 1)
        if (to - from > MAX_SCAN) {
            from = (st.selection.main.head - MAX_SCAN / 2).coerceIn(from, to)
            to = minOf(to, from + MAX_SCAN)
        }
        // A match may start before the window and end in it (CM6 looks one query length back).
        val margin = if (s.query.regex == null) s.query.needle.length else 0
        val out = ArrayList<Ranged<Decoration>>()
        val sel = st.selection.ranges
        val c = s.query.cursor(doc, maxOf(0, from - margin), minOf(doc.length, to + margin))
        while (c.hasNext()) {
            val m = c.next()
            if (m.from == m.to) continue // an empty match has nothing to mark
            val selected = sel.any { it.from == m.from && it.to == m.to }
            out += Ranged(m.from, m.to, if (selected) selectedMark else matchMark)
            if (out.size >= SearchQuery.MATCH_LIMIT) break
        }
        return RangeSet.of(out)
    }

    // ------------------------------------------------------------------------ the keys --

    /** CM6's `searchKeymap`, and select all matches (`Alt-Enter`, Apple `Mod-Alt-Enter`). */
    val keymap: List<KeyBinding> = listOf(
        KeyBinding("Mod-f", openSearchPanel),
        KeyBinding("F3", findNext),
        KeyBinding("Shift-F3", findPrevious),
        KeyBinding("Mod-g", findNext),
        KeyBinding("Mod-Shift-g", findPrevious),
        KeyBinding("Escape", closeSearchPanel),
        KeyBinding("Mod-Shift-l", selectSelectionMatches),
        KeyBinding("Alt-Enter", selectMatches, mac = "Mod-Alt-Enter"),
        KeyBinding("Mod-Alt-g", gotoLine),
        KeyBinding("Mod-d", selectNextOccurrence),
    )

    val commands: List<NamedCommand> = listOf(
        NamedCommand("search.open", "Find", openSearchPanel),
        NamedCommand("search.close", "Close find", closeSearchPanel),
        NamedCommand("search.next", "Find next", findNext),
        NamedCommand("search.previous", "Find previous", findPrevious),
        NamedCommand("search.selectMatches", "Select all matches", selectMatches),
        NamedCommand("search.selectSelectionMatches", "Select all occurrences of the selection", selectSelectionMatches),
        NamedCommand("search.replaceNext", "Replace", replaceNext),
        NamedCommand("search.replaceAll", "Replace all", replaceAll),
        NamedCommand("search.selectNextOccurrence", "Add next occurrence", selectNextOccurrence),
        NamedCommand("search.gotoLine", "Go to line", gotoLine),
    )

    fun extension(config: SearchConfig = SearchConfig()): Extension = extensionOf(
        this.config.of(config),
        field,
        EditorViewport.extension,
        decorationsFacet.compute(FacetDep.Doc, FacetDep.Selection, FacetDep.field(field), FacetDep.field(EditorViewport.field)) { st -> marks(st) },
        panelsFacet.compute(FacetDep.field(field)) { st -> if (state(st).open) Panel(PANEL, top = st.facet(this.config).top) else null },
        panelsFacet.compute(FacetDep.field(field)) { st -> if (state(st).gotoLineOpen) Panel(GOTO_PANEL, top = st.facet(this.config).top) else null },
        keymapFacet.of(keymap),
        commandsFacet.of(commands),
    )
}

/** [Search.extension]: search and replace, its keys and its marks. Register the panels with [Search.registerWidgets]. */
fun search(config: SearchConfig = SearchConfig()): Extension = Search.extension(config)
