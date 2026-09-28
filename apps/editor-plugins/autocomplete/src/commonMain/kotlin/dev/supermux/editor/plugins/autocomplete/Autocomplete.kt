package dev.supermux.editor.plugins.autocomplete

import dev.supermux.editor.compose.ViewPlugin
import dev.supermux.editor.compose.ViewPluginHost
import dev.supermux.editor.compose.ViewPluginInstance
import dev.supermux.editor.compose.viewPluginsFacet
import dev.supermux.editor.core.ChangeSet
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
import dev.supermux.editor.core.Prec
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.Tooltip
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapOf
import dev.supermux.editor.core.tooltipsFacet
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * The autocompletion's options (CM6's `autocompletion(config)` defaults unless noted).
 *
 * - [activateOnTyping] / [activateOnTypingDelay]: typing a word character (or a trigger character)
 *   asks the sources that many ms after the last keystroke (CM6: 100).
 * - [triggerCharacters]: characters that ask the sources even outside a word (`.`; a source can add
 *   its own through [completionTriggersFacet]: the LSP server's).
 * - [selectOnOpen]: the first option is selected when the list opens.
 * - [interactionDelay]: ms after the list opens during which Enter / Tab / the arrows are NOT the
 *   list's (a fast "foo⏎" typed just as it opened still makes a newline). CM6: 75.
 * - [acceptOnTab]: Tab accepts too (VS Code; CM6 binds only Enter by default).
 * - [aboveCursor]: the list above the line (CM6's `aboveCursor`); it flips when there is no room.
 */
data class AutocompleteConfig(
    val activateOnTyping: Boolean = true,
    val activateOnTypingDelay: Long = 100,
    val triggerCharacters: Set<Char> = emptySet(),
    val selectOnOpen: Boolean = true,
    val interactionDelay: Long = 75,
    val acceptOnTab: Boolean = true,
    val aboveCursor: Boolean = false,
    val defaultKeymap: Boolean = true,
)

/** A source's live answer: where its text starts ([from]), ends ([end], moving with typing), and whether it must be asked again ([stale]). */
class ActiveResult internal constructor(
    val source: CompletionSource,
    val result: CompletionResult,
    val from: Int,
    val end: Int,
    val explicit: Boolean,
    val stale: Boolean = false,
)

/** One shown option: its [completion], [score], the [matched] ranges of its label (`from, to` pairs), and the [result] it came from. */
class Option internal constructor(val completion: Completion, val score: Int, val matched: IntArray, val result: ActiveResult)

/**
 * The autocompletion's state: the sources' [results], the filtered and sorted [options], the
 * [selected] one (-1: none), when it opened (for [AutocompleteConfig.interactionDelay]), the start
 * requests the runner follows, and the side panel's resolved [info].
 */
class CompletionState internal constructor(
    val results: List<ActiveResult> = emptyList(),
    val options: List<Option> = emptyList(),
    val selected: Int = -1,
    internal val openedAt: TimeSource.Monotonic.ValueTimeMark? = null,
    internal val startRequests: Int = 0,
    internal val startExplicit: Boolean = false,
    val info: Pair<Completion, String?>? = null,
) {
    val open: Boolean get() = options.isNotEmpty()
    val selectedOption: Option? get() = options.getOrNull(selected)
    /** Where the list is anchored: the selected option's result's start, else the first result's. */
    val from: Int get() = (selectedOption?.result ?: options.firstOrNull()?.result)?.from ?: -1

    internal fun copy(
        results: List<ActiveResult> = this.results,
        options: List<Option> = this.options,
        selected: Int = this.selected,
        openedAt: TimeSource.Monotonic.ValueTimeMark? = this.openedAt,
        startRequests: Int = this.startRequests,
        startExplicit: Boolean = this.startExplicit,
        info: Pair<Completion, String?>? = this.info,
    ) = CompletionState(results, options, selected, openedAt, startRequests, startExplicit, info)
}

/** Every plugin's completion sources, highest precedence first (the LSP plugin adds its own). */
val completionSourcesFacet: Facet<CompletionSource, List<CompletionSource>> = Facet.list("completionSources")

/** Characters that open completion outside a word, from every plugin (the LSP server's trigger characters). */
val completionTriggersFacet: Facet<Set<Char>, Set<Char>> = Facet.define("completionTriggers") { l -> l.flatMapTo(HashSet()) { it } }

/**
 * Autocompletion (CM6's `@codemirror/autocomplete`): [autocompletion] installs it, [registerWidgets]
 * gives the popup (`tooltip:completion`).
 *
 * - **Keys** (CM6's `completionKeymap`, highest precedence, only while the list is open unless
 *   noted): `Ctrl-Space` (Apple also `Alt-\`` and `Alt-i`) opens it always; `ArrowDown` / `ArrowUp`
 *   move (wrapping), `PageDown` / `PageUp` by a page; `Enter` (and `Tab`, [AutocompleteConfig.acceptOnTab])
 *   accepts; `Escape` closes.
 * - **Accepting** inserts the option's text at every cursor where the text typed is the same (CM6's
 *   `insertCompletionText`), a snippet at the main cursor with its fields ([Snippets]), or an
 *   option's own edits (LSP `additionalTextEdits`), as ONE transaction with userEvent
 *   `input.complete` (one undo step; the M4a lsp contract).
 * - **Typing** filters the options with CM6's [FuzzyMatcher] and sorts them (score + boost, then
 *   `sortText` or the label); a source is asked again only when its result's `validFor` no longer
 *   matches, and a keystroke cancels a source call in flight.
 */
object Autocomplete {
    const val TOOLTIP = "tooltip:completion"
    val tooltipKey = WidgetKey(TOOLTIP, "list")

    /** The class of a snippet's fields (`snippet-field`). */
    const val SNIPPET_FIELD_CLASS = "snippet-field"

    internal val config: Facet<AutocompleteConfig, AutocompleteConfig> = Facet.first("autocomplete.config", AutocompleteConfig())
    internal val overrideSources: Facet<List<CompletionSource>, List<CompletionSource>?> = Facet.define("autocomplete.override") { it.firstOrNull() }

    val setResults: StateEffectType<List<ActiveResult>> = StateEffectType("autocomplete.results")
    val setSelected: StateEffectType<Int> = StateEffectType("autocomplete.select")
    val close: StateEffectType<Unit> = StateEffectType("autocomplete.close")
    val start: StateEffectType<Boolean> = StateEffectType("autocomplete.start")
    internal val setInfo: StateEffectType<Pair<Completion, String?>> = StateEffectType("autocomplete.info")

    /** Where the time comes from ([AutocompleteConfig.interactionDelay]); tests may replace it. */
    internal var timeSource: TimeSource.Monotonic = TimeSource.Monotonic

    val field: StateField<CompletionState> = StateField(
        "autocomplete",
        { CompletionState() },
        { v, tr -> update(v, tr) },
    )

    /** The state in [st] (empty when the plugin is not installed). */
    fun state(st: EditorState): CompletionState = st.fieldOrNull(field) ?: CompletionState()

    fun isOpen(st: EditorState): Boolean = state(st).open

    /** The sources in [st]: the override, else every [completionSourcesFacet] input. */
    fun sources(st: EditorState): List<CompletionSource> = st.facet(overrideSources) ?: st.facet(completionSourcesFacet)

    fun triggers(st: EditorState): Set<Char> = st.facet(config).triggerCharacters + st.facet(completionTriggersFacet)

    private fun update(v: CompletionState, tr: Transaction): CompletionState {
        var s = v
        val st = tr.state
        var refilter = false
        var newResults: List<ActiveResult>? = null
        for (e in tr.effects) {
            e.valueIf(setResults)?.let { newResults = it }
            if (e.isOf(close)) return CompletionState(startRequests = s.startRequests)
            e.valueIf(start)?.let { s = s.copy(startRequests = s.startRequests + 1, startExplicit = it) }
        }
        if (tr.isUserEvent("input.complete")) return CompletionState(startRequests = s.startRequests)
        if (newResults != null) {
            s = s.copy(results = newResults!!)
            refilter = true
        } else if (s.results.isNotEmpty()) {
            if (tr.docChanged) {
                s = s.copy(results = mapResults(s.results, tr))
                refilter = true
            } else if (tr.selectionSet) {
                val head = st.selection.main.head
                val kept = s.results.filter { head >= it.from && head <= it.end }
                if (kept.size != s.results.size) { s = s.copy(results = kept); refilter = true }
            }
        }
        if (refilter) {
            val prev = s.selectedOption?.completion
            val options = filterAndSort(s.results, st)
            val keep = if (prev != null && !tr.docChanged) options.indexOfFirst { it.completion == prev } else -1
            val cfg = st.facet(config)
            val selected = when {
                options.isEmpty() -> -1
                keep >= 0 -> keep
                cfg.selectOnOpen -> 0
                else -> -1
            }
            val openedAt = if (options.isEmpty()) null else s.openedAt ?: timeSource.markNow()
            s = s.copy(options = options, selected = selected, openedAt = openedAt)
        }
        for (e in tr.effects) {
            e.valueIf(setSelected)?.let { i -> if (s.open) s = s.copy(selected = i.coerceIn(-1, s.options.size - 1)) }
            e.valueIf(setInfo)?.let { s = s.copy(info = it) }
        }
        return s
    }

    /** Results through an edit: a result whose start the cursor went before, or left the line of, goes; one whose [CompletionResult.validFor] no longer matches is [ActiveResult.stale] (asked again). */
    private fun mapResults(results: List<ActiveResult>, tr: Transaction): List<ActiveResult> {
        val st = tr.state
        val head = st.selection.main.head
        return results.mapNotNull { r ->
            val from = tr.changes.mapPos(r.from, -1)
            val end = tr.changes.mapPos(r.end, 1)
            if (head < from || head > end || st.doc.lineIndexAt(from) != st.doc.lineIndexAt(head)) return@mapNotNull null
            val typed = st.doc.slice(from, minOf(end, st.doc.length))
            val valid = r.result.validFor?.matches(typed) == true
            ActiveResult(r.source, r.result, from, end, r.explicit, stale = r.stale || !valid)
        }
    }

    /**
     * CM6's `sortOptions`: every result's options matched against the text from its start to its
     * end (a stale result whose `validFor` stopped matching is left out), by score + boost, then
     * `sortText` (else the label); an option equal to the one before it (label, detail, type, apply,
     * boost) is dropped.
     */
    fun filterAndSort(results: List<ActiveResult>, st: EditorState): List<Option> {
        val options = ArrayList<Option>()
        for (a in results) {
            if (a.stale && a.result.validFor != null) continue
            if (!a.result.filter) {
                for (c in a.result.options) options += Option(c, 1_000_000_000 - options.size, IntArray(0), a)
                continue
            }
            val pattern = st.doc.slice(a.from, minOf(a.end, st.doc.length).coerceAtLeast(a.from))
            val m = FuzzyMatcher(pattern)
            for (c in a.result.options) {
                val match = m.match(c.label) ?: continue
                val matched = if (c.displayLabel == null) match.ranges else IntArray(0)
                options += Option(c, match.score + c.boost, matched, a)
            }
        }
        options.sortWith { x, y ->
            if (x.score != y.score) y.score.compareTo(x.score)
            else (x.completion.sortText ?: x.completion.label).compareTo(y.completion.sortText ?: y.completion.label)
        }
        val out = ArrayList<Option>(options.size)
        var prev: Completion? = null
        for (o in options) {
            val c = o.completion
            val p = prev
            if (p == null || p.label != c.label || p.detail != c.detail || (p.type != null && c.type != null && p.type != c.type) || p.apply != c.apply || p.boost != c.boost) out += o
            prev = c
        }
        return out
    }

    // ---------------------------------------------------------------------- commands --

    private fun interactive(st: EditorState): Boolean {
        val s = state(st)
        val at = s.openedAt ?: return false
        return at.elapsedNow().inWholeMilliseconds >= st.facet(config).interactionDelay
    }

    /** Open completion here, asking every source (CM6's `startCompletion`, `Ctrl-Space`). */
    val startCompletion: Command = Command { t ->
        if (t.state.fieldOrNull(field) == null) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(start.of(true))))
        true
    }

    /** Close the list; false (the key goes on) when it is closed. */
    val closeCompletion: Command = Command { t ->
        if (!isOpen(t.state)) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(close.of(Unit))))
        true
    }

    /** CM6's `moveCompletionSelection(forward, by)`: one option (wrapping) or a page (stopping at the ends). */
    fun moveSelection(forward: Boolean, page: Boolean = false): Command = Command { t ->
        val s = state(t.state)
        if (!s.open || !interactive(t.state)) return@Command false
        val step = if (page) PAGE else 1
        var i = s.selected + step * (if (forward) 1 else -1)
        val n = s.options.size
        if (i < 0) i = if (page) 0 else n - 1 else if (i >= n) i = if (page) n - 1 else 0
        t.dispatch(TransactionSpec(effects = listOf(setSelected.of(i))))
        true
    }

    /** Accept the selected option (CM6's `acceptCompletion`); false when there is none. */
    val acceptCompletion: Command = Command { t ->
        val s = state(t.state)
        val o = s.selectedOption ?: return@Command false
        if (!interactive(t.state)) return@Command false
        applyOption(t, o)
        true
    }

    /** Accept option [index] of the list (a tap on it): no interaction delay, a tap is deliberate. */
    fun accept(t: CommandTarget, index: Int): Boolean {
        val o = state(t.state).options.getOrNull(index) ?: return false
        applyOption(t, o)
        return true
    }

    private const val PAGE = 8

    private fun applyOption(t: CommandTarget, o: Option) {
        val c = o.completion
        val from = o.result.from
        val to = minOf(o.result.end, t.state.doc.length)
        when (val a = c.apply) {
            is CompletionApply.Custom -> a.apply(t, c, from, to)
            is CompletionApply.Template -> t.dispatch(snippetSpec(t.state, a.snippet, from, to, emptyList()))
            is CompletionApply.WithEdits -> t.dispatch(
                if (a.snippet) snippetSpec(t.state, Snippet.fromLsp(a.text), from, to, a.edits)
                else insertCompletionText(t.state, a.text, from, to, a.edits),
            )
            is CompletionApply.Text -> t.dispatch(insertCompletionText(t.state, a.text, from, to))
            null -> t.dispatch(insertCompletionText(t.state, c.label, from, to))
        }
    }

    /**
     * CM6's `insertCompletionText`: [text] over [from, to) at the main cursor, and at every other
     * cursor over the same offsets around it when the text there is the same (else that cursor is
     * left alone); each cursor after its inserted text. [extra] edits elsewhere (an import) join the
     * same transaction unless they overlap one of those ranges. userEvent `input.complete`.
     */
    fun insertCompletionText(st: EditorState, text: String, from: Int, to: Int, extra: List<ChangeSpec> = emptyList()): TransactionSpec {
        val sel = st.selection
        val main = sel.main
        val fromOff = from - main.from
        val toOff = to - main.from
        val replaced = st.doc.slice(from, to)
        val specs = ArrayList<ChangeSpec>()
        val ends = ArrayList<Int?>()
        for (r in sel.ranges) {
            val a = r.from + fromOff
            val b = if (to == main.from) r.to else r.from + toOff
            val ok = r == main || (a >= 0 && b <= st.doc.length && a <= b && (from == to || st.doc.slice(a, r.from + toOff) == replaced))
            if (!ok || a < 0 || b > st.doc.length || a > b) { ends += null; continue }
            specs += ChangeSpec(a, b, text)
            ends += b
        }
        val kept = extra.filter { e -> specs.none { s -> e.from < s.to && e.to > s.from || e.from == s.from && e.to == s.to } }
        val changes = ChangeSet.of(st.doc.length, merge(specs + kept))
        val ranges = sel.ranges.mapIndexed { i, r ->
            val end = ends[i]
            if (end == null) r.map(changes) else SelectionRange(changes.mapPos(end, 1))
        }
        return TransactionSpec(
            changeSet = changes,
            selection = EditorSelection.create(ranges, sel.mainIndex),
            scrollIntoView = true,
            userEvent = "input.complete",
        )
    }

    /** Drop specs that overlap an earlier one (two cursors' ranges merged into one). */
    private fun merge(specs: List<ChangeSpec>): List<ChangeSpec> {
        val sorted = specs.sortedWith(compareBy({ it.from }, { it.to }))
        val out = ArrayList<ChangeSpec>()
        for (s in sorted) { val l = out.lastOrNull(); if (l != null && s.from < l.to) continue; out += s }
        return out
    }

    /**
     * A snippet at the main cursor (CM6's `snippet()`): [from, to) replaced by its text, the first
     * field selected (every range of it), and the fields active ([Snippets]) when there is more than
     * one stop; [extra] edits join unless they overlap. userEvent `input.complete`.
     */
    fun snippetSpec(st: EditorState, snippet: Snippet, from: Int, to0: Int, extra: List<ChangeSpec>): TransactionSpec {
        val main = st.selection.main
        val to = if (to0 == main.from) main.to else to0
        val (text, ranges) = snippet.instantiate(st, from)
        val kept = extra.filter { e -> !(e.from < to && e.to > from) && !(e.from == from && e.to == to) }
        val changes = ChangeSet.of(st.doc.length, listOf(ChangeSpec(from, to, text)) + kept)
        val delta = changes.mapPos(from, -1) - from
        val mapped = ranges.map { FieldRange(it.field, it.from + delta, it.to + delta) }
        val first = mapped.filter { it.field == 0 }
        val selection = if (first.isEmpty()) EditorSelection.cursor(changes.mapPos(to, 1))
        else EditorSelection.create(first.map { SelectionRange(it.from, it.to) })
        val effects = if (mapped.any { it.field > 0 }) listOf(Snippets.setActive.of(ActiveSnippet(mapped, 0))) else emptyList()
        return TransactionSpec(changeSet = changes, selection = selection, effects = effects, scrollIntoView = true, userEvent = "input.complete")
    }

    /** CM6's `completionKeymap`. */
    fun keymap(cfg: AutocompleteConfig): List<KeyBinding> = buildList {
        add(KeyBinding("Ctrl-Space", startCompletion))
        add(KeyBinding("Alt-`", Command { t -> isApple && startCompletion.run(t) }))
        add(KeyBinding("Alt-i", Command { t -> isApple && startCompletion.run(t) }))
        add(KeyBinding("Escape", closeCompletion))
        add(KeyBinding("ArrowDown", moveSelection(true)))
        add(KeyBinding("ArrowUp", moveSelection(false)))
        add(KeyBinding("PageDown", moveSelection(true, page = true)))
        add(KeyBinding("PageUp", moveSelection(false, page = true)))
        add(KeyBinding("Enter", acceptCompletion))
        if (cfg.acceptOnTab) add(KeyBinding("Tab", acceptCompletion))
    }

    private val isApple: Boolean get() = dev.supermux.editor.compose.isApplePlatform

    internal fun runner(): ViewPlugin = ViewPlugin { host -> CompletionRunner(host) }

    internal val commands = listOf(
        NamedCommand("autocomplete.start", "Trigger completion", startCompletion),
        NamedCommand("autocomplete.close", "Close completion", closeCompletion),
        NamedCommand("autocomplete.accept", "Accept completion", acceptCompletion),
    )
}

/**
 * Autocompletion: the state, the popup's tooltip (`tooltip:completion`, content from
 * [Autocomplete.registerWidgets]), the keymap, snippets, and the runner that asks the sources.
 * [override]: only these sources (CM6's `override`); else every [completionSourcesFacet] input.
 */
fun autocompletion(config: AutocompleteConfig = AutocompleteConfig(), override: List<CompletionSource>? = null): Extension {
    val runner = Autocomplete.runner()
    return extensionOf(
        Autocomplete.config.of(config),
        if (override != null) Autocomplete.overrideSources.of(override) else extensionOf(),
        Autocomplete.field,
        tooltipsFacet.compute(FacetDep.field(Autocomplete.field), FacetDep.facet(Autocomplete.config)) { st ->
            val s = Autocomplete.state(st)
            if (!s.open) null else Tooltip(s.from.coerceIn(0, st.doc.length), Autocomplete.tooltipKey, above = st.facet(Autocomplete.config).aboveCursor)
        },
        if (config.defaultKeymap) Prec.highest(keymapOf(*Autocomplete.keymap(config).toTypedArray())) else extensionOf(),
        commandsFacet.of(Autocomplete.commands),
        viewPluginsFacet.of(runner),
        Snippets.extension,
    )
}

/**
 * Asks the sources (a [ViewPlugin]): after a typed word or trigger character (with the typing
 * delay), for a stale result, and at once on [Autocomplete.start]. A newer ask cancels the one in
 * flight; an answer for a document that changed meanwhile is mapped through the changes, and
 * dropped when the cursor went before its start. Also resolves the selected option's info.
 */
internal class CompletionRunner(private val host: ViewPluginHost) : ViewPluginInstance {
    private var job: Job? = null
    private var infoJob: Job? = null
    private var since: ChangeSet? = null
    private var lastRequests = Autocomplete.state(host.target.state).startRequests

    override fun update(tr: Transaction) {
        val st = tr.state
        val s = Autocomplete.state(st)
        val cfg = st.facet(Autocomplete.config)
        if (tr.docChanged) since = since?.compose(tr.changes)
        if (s.startRequests != lastRequests) {
            lastRequests = s.startRequests
            query(explicit = s.startExplicit, trigger = null, delayMs = 0)
        } else if (tr.docChanged && (tr.isUserEvent("input.complete") || tr.effects.any { it.isOf(Autocomplete.close) })) {
            cancel()
        } else if (tr.docChanged && (typing(tr) || tr.isUserEvent("delete"))) {
            val head = st.selection.main.head
            val ch = if (head > 0) st.doc.charAt(head - 1) else ' '
            val stale = s.results.filter { it.stale }
            when {
                typing(tr) && ch in Autocomplete.triggers(st) -> query(explicit = false, trigger = ch.toString(), delayMs = cfg.activateOnTypingDelay)
                stale.isNotEmpty() -> query(explicit = stale.any { it.explicit }, trigger = null, delayMs = cfg.activateOnTypingDelay)
                !s.open && typing(tr) && cfg.activateOnTyping && isWordChar(ch) -> query(explicit = false, trigger = null, delayMs = cfg.activateOnTypingDelay)
                !s.open -> cancel()
            }
        } else if (tr.docChanged && !s.open && (tr.isUserEvent("paste") || tr.isUserEvent("undo") || tr.isUserEvent("redo"))) {
            // A remote, agent or LSP edit leaves a pending ask alone (its answer is mapped).
            cancel()
        } else if (tr.effects.any { it.isOf(Autocomplete.close) }) {
            cancel()
        }
        resolveInfo(s)
    }

    private fun typing(tr: Transaction): Boolean =
        tr.isUserEvent("input") && !tr.isUserEvent("input.complete") && !tr.isUserEvent("input.paste") &&
            !tr.isUserEvent("input.replace") && !tr.isUserEvent("input.indent") && !tr.isUserEvent("input.drop")

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun cancel() { job?.cancel(); job = null }

    private fun query(explicit: Boolean, trigger: String?, delayMs: Long) {
        job?.cancel()
        job = host.scope.launch {
            if (delayMs > 0) delay(delayMs)
            val st0 = host.target.state
            val sources = Autocomplete.sources(st0)
            if (sources.isEmpty()) return@launch
            val pos = st0.selection.main.head
            val ctx = CompletionContext(st0, pos, explicit, trigger)
            since = ChangeSet.empty(st0.doc.length)
            val answers = coroutineScope {
                sources.map { src ->
                    async {
                        val r = try {
                            src.complete(ctx)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            println("editor-plugins/autocomplete: a completion source failed: $e")
                            null
                        }
                        r?.takeIf { it.options.isNotEmpty() }?.let { src to it }
                    }
                }.awaitAll().filterNotNull()
            }
            val changes = since ?: ChangeSet.empty(st0.doc.length)
            since = null
            val st = host.target.state
            val head = st.selection.main.head
            val results = answers.mapNotNull { (src, r) ->
                val from = changes.mapPos(r.from.coerceIn(0, changes.lengthBefore), -1)
                val end = changes.mapPos((r.to ?: pos).coerceIn(0, changes.lengthBefore), 1)
                if (head < from || head > end) null else ActiveResult(src, r, from, end, explicit)
            }
            if (results.isEmpty() && Autocomplete.state(st).results.isEmpty()) return@launch
            host.target.dispatch(TransactionSpec(effects = listOf(Autocomplete.setResults.of(results))))
        }
    }

    private var infoFor: Completion? = null

    private fun resolveInfo(s: CompletionState) {
        val c = s.selectedOption?.completion
        if (c === infoFor) return
        infoFor = c
        infoJob?.cancel()
        if (c == null || c.info != null || c.resolveInfo == null || s.info?.first == c) return
        val resolve = c.resolveInfo
        infoJob = host.scope.launch {
            delay(50)
            val text = try { resolve() } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Throwable) { null }
            if (Autocomplete.state(host.target.state).selectedOption?.completion == c) {
                host.target.dispatch(TransactionSpec(effects = listOf(Autocomplete.setInfo.of(c to text))))
            }
        }
    }

    override fun destroy() { cancel(); infoJob?.cancel() }
}

/** An active snippet's fields and which one the cursor is in. */
data class ActiveSnippet(val ranges: List<FieldRange>, val active: Int) {
    fun map(changes: ChangeSet): ActiveSnippet? {
        val out = ArrayList<FieldRange>(ranges.size)
        for (r in ranges) out += r.map(changes) ?: return null
        return ActiveSnippet(out, active)
    }

    fun selectionInsideField(sel: EditorSelection): Boolean =
        sel.ranges.all { range -> ranges.any { it.field == active && it.from <= range.from && it.to >= range.to } }
}

/**
 * Snippet fields (CM6's snippet state): after a snippet is inserted, `Tab` / `Shift-Tab` move between
 * its fields (every range of a field selected at once), `Escape` leaves them; moving to the last
 * field, or the cursor out of the current one, ends it. The fields are marked [Autocomplete.SNIPPET_FIELD_CLASS]
 * and move with edits.
 */
object Snippets {
    val setActive: StateEffectType<ActiveSnippet?> = StateEffectType("snippet.active") { v, c -> v?.map(c) }
    val moveToField: StateEffectType<Int> = StateEffectType("snippet.moveTo")

    val field: StateField<ActiveSnippet?> = StateField(
        "snippet",
        { null },
        { v, tr ->
            var value = v
            var effected = false
            for (e in tr.effects) {
                if (e.isOf(setActive)) { value = e.valueIf(setActive); effected = true }
                e.valueIf(moveToField)?.let { f -> value = value?.copy(active = f); effected = true }
            }
            if (!effected) {
                if (value != null && tr.docChanged) value = value.map(tr.changes)
                if (value != null && tr.selectionSet && !value.selectionInsideField(tr.state.selection)) value = null
            }
            value
        },
        { f ->
            decorationsFacet.compute(FacetDep.field(f)) { st ->
                val a = st.field(f) ?: return@compute RangeSet.empty()
                RangeSet.of(a.ranges.filter { it.from < it.to }.map { Ranged(it.from, it.to, Decoration.Mark(setOf(Autocomplete.SNIPPET_FIELD_CLASS), inclusiveStart = true, inclusiveEnd = true) as Decoration) })
            }
        },
    )

    fun active(st: EditorState): ActiveSnippet? = st.fieldOrNull(field)

    private fun moveField(dir: Int) = Command { t ->
        val a = active(t.state) ?: return@Command false
        if (dir < 0 && a.active == 0) return@Command false
        val next = a.active + dir
        val last = dir > 0 && a.ranges.none { it.field == next + dir }
        val sel = a.ranges.filter { it.field == next }
        if (sel.isEmpty()) return@Command false
        t.dispatch(TransactionSpec(
            selection = EditorSelection.create(sel.map { SelectionRange(it.from, it.to) }),
            effects = listOf(setActive.of(if (last) null else ActiveSnippet(a.ranges, next))),
            scrollIntoView = true,
            userEvent = "select",
        ))
        true
    }

    val nextField: Command = moveField(1)
    val prevField: Command = moveField(-1)
    val clear: Command = Command { t ->
        if (active(t.state) == null) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(setActive.of(null))))
        true
    }

    fun hasNextField(st: EditorState): Boolean = active(st)?.let { a -> a.ranges.any { it.field == a.active + 1 } } == true

    val extension: Extension = extensionOf(
        field,
        Prec.highest(keymapOf(KeyBinding("Tab", nextField), KeyBinding("Shift-Tab", prevField), KeyBinding("Escape", clear))),
    )
}
