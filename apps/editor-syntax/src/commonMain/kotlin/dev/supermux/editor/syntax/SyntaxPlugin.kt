package dev.supermux.editor.syntax

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.FoldRange
import dev.supermux.editor.core.FoldService
import dev.supermux.editor.core.foldServiceFacet
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TokenContext
import dev.supermux.editor.core.TokenContextProvider
import dev.supermux.editor.core.tokenContextFacet
import dev.supermux.editor.core.CommentTokensProvider
import dev.supermux.editor.core.commentTokensFacet
import dev.supermux.editor.core.SelectParentService
import dev.supermux.editor.core.selectParentFacet
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The worker's answer for one document version: token spans (and folds) for UTF-16 [start, end)
 * of version [version]'s text. [syntaxOff]: the document is plain text from now on (too big, or
 * the parse timed out); spans and folds are then empty and the UI shows "syntax off".
 */
class SyntaxSpansUpdate(
    val version: Long,
    val start: Int,
    val end: Int,
    val spans: RangeSet<Decoration>,
    val folds: IntArray,
    val syntaxOff: Boolean = false,
    /** The [SyntaxSnapshot.epoch] it was computed for; an update for another field instance is ignored (-1: any). */
    val epoch: Long = -1,
    /** The injected layers' ranges in [start, end): packed [from, to, depth, index into [layerLanguages]]*. */
    val layers: IntArray = IntArray(0),
    val layerLanguages: List<String> = emptyList(),
)

/** A range of the document in an injected layer of [language] (depth > 0; the host is everything else). */
internal class LayerRange(val from: Int, val to: Int, val depth: Int, val language: String)

/** One doc-changing transaction: [changes] turned version `version - 1` into [version]. */
class VersionedChanges(val version: Long, val changes: ChangeSet)

/** A select-parent request (CM6's `selectParentSyntax`) for the ranges [from, to]* of the state it was made in. */
class ParentRequest(val id: Long, val ranges: IntArray)

/** The worker's answer to request [id], computed at [version]: per range (anchor, head), or (-1, -1) where nothing encloses it. */
class ParentAnswer(val id: Long, val version: Long, val ranges: IntArray)

/** What the syntax worker needs from one state. Plain data: safe to hand to another thread. */
class SyntaxSnapshot(
    /**
     * Unique per syntax field instance (each [EditorState.create] makes a new one): a replaced
     * state starts at version 0 again, so a version alone does not identify a text.
     */
    val epoch: Long,
    /** Null: plain text, nothing to parse. */
    val language: String?,
    val doc: Rope,
    val version: Long,
    /** UTF-16 [first, last + 1) the surface shows; empty = not known yet. */
    val viewport: IntRange,
    /** The last [Syntax.LOG_SIZE] doc changes, oldest first, ending at [version]. */
    val log: List<VersionedChanges>,
    val syntaxOff: Boolean,
    /** A pending select-parent request (answered at this version by [SyntaxWorker]). */
    val parentRequest: ParentRequest? = null,
)

/** The syntax field's value: data only (no native handle ever sits in an EditorState). */
internal class SyntaxValue(
    val epoch: Long,
    val language: String?,
    val version: Long,
    val spans: RangeSet<Decoration>,
    val folds: IntArray,
    val viewport: IntRange,
    val log: List<VersionedChanges>,
    val syntaxOff: Boolean,
    /** The version of the last update applied: an older one arriving later is ignored (FIFO guard). */
    val lastUpdate: Long = -1,
    /** Where the spans, folds and [layers] are known: the updates' windows, within what is kept. */
    val known: IntRange = IntRange.EMPTY,
    val layers: List<LayerRange> = emptyList(),
) {
    fun copy(
        version: Long = this.version, spans: RangeSet<Decoration> = this.spans, folds: IntArray = this.folds,
        viewport: IntRange = this.viewport, log: List<VersionedChanges> = this.log, syntaxOff: Boolean = this.syntaxOff,
        lastUpdate: Long = this.lastUpdate, known: IntRange = this.known, layers: List<LayerRange> = this.layers,
    ) = SyntaxValue(epoch, language, version, spans, folds, viewport, log, syntaxOff, lastUpdate, known, layers)
}

/**
 * The editor-core side of syntax highlighting: a state field holding the current spans, mapped
 * through every edit at once (so nothing flickers while the worker reparses), and replaced inside
 * the updated range when the worker's [spans] effect lands, even a stale one (mapped through the
 * edits since its version). The field feeds [decorationsFacet].
 */
object Syntax {
    /** Doc changes kept for mapping stale updates; an update older than that is dropped. */
    const val LOG_SIZE = 64

    /** UTF-16 [first, last + 1) the surface shows (plus its own margin); dispatch as `start until end`. */
    val setViewport: StateEffectType<IntRange> = StateEffectType("syntax.viewport")

    /** The worker's spans for a version; see [SyntaxSpansUpdate]. */
    val spans: StateEffectType<SyntaxSpansUpdate> = StateEffectType("syntax.spans")

    /**
     * The extension for one document. [language] null = plain text (no parsing). It also answers
     * editor-core's language hooks: `tokenContextFacet` (strings and comments, for bracket matching;
     * a new provider with every new syntax value, so what depends on it follows the spans) and
     * `foldServiceFacet` (folds from `folds.scm`, see [foldAt]).
     */
    fun extension(language: String?): Extension = extensionOf(
        languageFacet.of(language),
        field,
        parentField,
        // Mod-/ : the comment tokens of the language AT the position (an injected layer's own).
        commentTokensFacet.compute(FacetDep.field(field)) { st ->
            st.field(field).let { v -> CommentTokensProvider { _, pos -> languageAt(v, pos)?.let { LanguageRegistry.COMMENT_TOKENS[it] } } }
        },
        // Mod-i : the tree is the worker's; the request rides on the state, the answer comes back as an
        // effect the host turns into a selection (highlight's SyntaxHost).
        selectParentFacet.compute(FacetDep.field(field)) { st ->
            st.field(field).let { v ->
                SelectParentService { t ->
                    if (v.language == null || v.syntaxOff) return@SelectParentService false
                    val ranges = t.state.selection.ranges.flatMap { listOf(it.from, it.to) }.toIntArray()
                    t.dispatch(dev.supermux.editor.core.TransactionSpec(effects = listOf(requestParent.of(ParentRequest(nextRequest(), ranges)))))
                    true
                }
            }
        },
        tokenContextFacet.compute(FacetDep.field(field)) { st -> st.field(field).let { v -> TokenContextProvider { _, pos -> contextIn(v, pos) } } },
        foldServiceFacet.compute(FacetDep.field(field)) { st ->
            st.field(field).let { v ->
                object : FoldService {
                    override fun foldable(state: EditorState, lineFrom: Int, lineTo: Int) = foldAt(v.folds, state.doc, lineFrom, lineTo)
                    // Where the worker has parsed, its folds are the answer (no indentation fallback there).
                    override fun knows(state: EditorState, lineFrom: Int) = v.language != null && !v.syntaxOff && lineFrom in v.known
                }
            }
        },
    )

    /**
     * The fold for the line [lineFrom, lineTo] from tree-sitter's `folds.scm` ranges [folds]
     * (packed, sorted): the OUTERMOST node starting on the line and ending on a later one (CM6's
     * syntaxFolding picks the outermost too), made line-based: it hides from the line's end to the
     * start of the node's last line when that line starts with its closing token (`}` `)` `]` or
     * `end`: `fun f() {⋯}`), else to the node's end (`def f():⋯`). Null when none is known here.
     */
    fun foldAt(folds: IntArray, doc: Rope, lineFrom: Int, lineTo: Int): FoldRange? {
        if (folds.isEmpty()) return null
        // The first node starting at or after the line's start (starts are sorted).
        var lo = 0; var hi = folds.size / 2
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (folds[mid * 2] < lineFrom) lo = mid + 1 else hi = mid }
        var end = -1
        var i = lo
        while (i < folds.size / 2 && folds[i * 2] <= lineTo) {
            val e = folds[i * 2 + 1]
            if (e > lineTo && e > end) end = e
            i++
        }
        if (end < 0 || end > doc.length) return null
        val lastLine = doc.lineIndexAt(end - 1)
        if (lastLine <= doc.lineIndexAt(lineFrom)) return null
        val lastStart = doc.lineStart(lastLine)
        var p = lastStart
        while (p < end && (doc.charAt(p) == ' ' || doc.charAt(p) == '\t')) p++
        val closing = p < end && (doc.charAt(p) in "})]" || doc.slice(p, end) == "end")
        val to = if (closing) p else end
        return if (to > lineTo) FoldRange(lineTo, to) else null
    }

    /** What the character at [pos] is in [state] (see [contextIn]); null without syntax. */
    fun tokenContext(state: EditorState, pos: Int): TokenContext? = state.fieldOrNull(field)?.let { contextIn(it, pos) }

    /**
     * From the spans and layers: kind STRING under a string, regexp or escape token, COMMENT under a
     * comment, else CODE; the language of the deepest injected layer there (else the host's). Null
     * where nothing is known: plain text, syntax off, or outside the window the worker has parsed
     * (spans are kept within [KEEP_SCREENS] screens of the viewport).
     */
    private fun contextIn(v: SyntaxValue, pos: Int): TokenContext? {
        if (v.language == null || v.syntaxOff || pos !in v.known) return null
        // The spans do not overlap: the one containing pos is the last starting at or before it.
        val r = v.spans.lastStartingAtOrBefore(pos)
        val classes = if (r != null && r.to > pos) (r.value as? Decoration.Mark)?.classes.orEmpty() else emptySet()
        val kind = when {
            TokenClasses.COMMENT in classes -> TokenContext.Kind.COMMENT
            classes.any { it in STRING_CLASSES } -> TokenContext.Kind.STRING
            else -> TokenContext.Kind.CODE
        }
        // The deepest injected layer holding pos, else the host.
        var language = v.language
        var depth = 0
        for (l in v.layers) if (l.from <= pos && pos < l.to && l.depth > depth) { depth = l.depth; language = l.language }
        return TokenContext(kind, language)
    }

    /** The language of the deepest known layer at [pos], else the document's. */
    private fun languageAt(v: SyntaxValue, pos: Int): String? {
        var language = v.language ?: return null
        var depth = 0
        for (l in v.layers) if (l.from <= pos && pos < l.to && l.depth > depth) { depth = l.depth; language = l.language }
        return language
    }

    /** Ask the worker for each range's enclosing syntax node (see [ParentRequest]). */
    val requestParent: StateEffectType<ParentRequest> = StateEffectType("syntax.requestParent")

    /** The worker's answer (see [ParentAnswer]); the host turns it into a selection when the text did not change since. */
    val parentAnswer: StateEffectType<ParentAnswer> = StateEffectType("syntax.parentAnswer")

    /** The pending request (cleared by its answer, and by an edit: its ranges would be stale). */
    internal val parentField: StateField<ParentRequest?> = StateField(
        name = "syntax.parent",
        create = { null },
        update = { v, tr ->
            var out = v
            for (e in tr.effects) {
                e.valueIf(requestParent)?.let { out = it }
                e.valueIf(parentAnswer)?.let { a -> if (out?.id == a.id) out = null }
            }
            if (tr.docChanged) out = null
            out
        },
    )

    @OptIn(ExperimentalAtomicApi::class)
    private val requests = AtomicLong(0)

    @OptIn(ExperimentalAtomicApi::class)
    private fun nextRequest(): Long = requests.addAndFetch(1)

    private val STRING_CLASSES = setOf(TokenClasses.STRING, TokenClasses.STRING_SPECIAL, TokenClasses.REGEXP, TokenClasses.ESCAPE)

    /** What the worker needs from a state; null when the state has no syntax extension. */
    fun snapshot(state: EditorState): SyntaxSnapshot? = state.fieldOrNull(field)?.let { v ->
        SyntaxSnapshot(v.epoch, v.language, state.doc, v.version, v.viewport, v.log, v.syntaxOff, state.fieldOrNull(parentField))
    }

    /** Doc changes' inserted text kept in the log at most (then the oldest entries go). */
    const val LOG_MAX_INSERTED = 1 shl 20

    /** Spans are kept within this many screens (the viewport's length) around the viewport. */
    const val KEEP_SCREENS = 2

    @OptIn(ExperimentalAtomicApi::class)
    private val epochs = AtomicLong(0)

    /** The fold ranges known for [state], packed UTF-16 [start, end)*. */
    fun folds(state: EditorState): IntArray = state.fieldOrNull(field)?.folds ?: IntArray(0)

    /** True when the document is plain text for good (too big, or its parse timed out). */
    fun isOff(state: EditorState): Boolean = state.fieldOrNull(field)?.syntaxOff ?: false

    private val languageFacet: Facet<String?, String?> = Facet.define("syntax.language") { it.firstOrNull() }

    private val EMPTY: RangeSet<Decoration> = RangeSet.empty()

    internal val field: StateField<SyntaxValue> = StateField(
        name = "syntax",
        create = { st -> SyntaxValue(nextEpoch(), st.facet(languageFacet), 0, EMPTY, IntArray(0), IntRange.EMPTY, emptyList(), false) },
        update = ::update,
        provide = { f -> decorationsFacet.compute(FacetDep.field(f)) { st -> st.field(f).spans } },
    )

    private fun update(value: SyntaxValue, tr: Transaction): SyntaxValue {
        var v = value
        // A new language (a compartment reconfigure): start over, and bump the version so the worker notices.
        val lang = tr.state.facet(languageFacet)
        if (lang != v.language) v = SyntaxValue(v.epoch, lang, v.version + 1, EMPTY, IntArray(0), v.viewport, emptyList(), false)
        // Worker updates are in the coordinates of their own version, at most the start state's.
        for (e in tr.effects) e.valueIf(spans)?.let { v = applyUpdate(v, it) }
        if (tr.docChanged) {
            val c = tr.changes
            val version = v.version + 1
            val log = capLog((v.log + VersionedChanges(version, c)).takeLast(LOG_SIZE))
            v = v.copy(
                version = version, spans = v.spans.map(c), folds = mapFolds(v.folds, c),
                viewport = mapViewport(v.viewport, c), log = log,
                known = mapViewport(v.known, c), layers = mapLayers(v.layers, c),
            )
        }
        for (e in tr.effects) e.valueIf(setViewport)?.let { if (it != v.viewport) v = v.copy(viewport = it) }
        return v
    }

    private fun applyUpdate(v: SyntaxValue, u: SyntaxSpansUpdate): SyntaxValue {
        if (u.epoch >= 0 && u.epoch != v.epoch) return v // computed for another state's text
        if (u.version > v.version) return v // from a version this state never had
        if (u.version < v.lastUpdate) return v // overtaken: a newer update is already in
        if (u.syntaxOff) return v.copy(spans = EMPTY, folds = IntArray(0), syntaxOff = true, lastUpdate = u.version, known = IntRange.EMPTY, layers = emptyList())
        var spans = u.spans
        var folds = u.folds
        var layers = List(u.layers.size / 4) { i -> LayerRange(u.layers[i * 4], u.layers[i * 4 + 1], u.layers[i * 4 + 2], u.layerLanguages[u.layers[i * 4 + 3]]) }
        var start = u.start
        var end = u.end
        if (u.version < v.version) {
            // map through every edit since u.version, if the log still has them all
            val later = v.log.filter { it.version > u.version }
            if (later.size.toLong() != v.version - u.version) return v
            val c = later.drop(1).fold(later[0].changes) { acc, x -> acc.compose(x.changes) }
            spans = spans.map(c)
            folds = mapFolds(folds, c)
            layers = mapLayers(layers, c)
            start = c.mapPos(start, 1)
            end = maxOf(start, c.mapPos(end, -1))
        }
        // Keep only a window around the viewport (or the update, before the viewport is known), so
        // mapping the spans through every keystroke stays cheap on the UI thread.
        val (ks, ke) = if (v.viewport.isEmpty()) start to end else {
            val len = v.viewport.last + 1 - v.viewport.first
            v.viewport.first - KEEP_SCREENS * len to v.viewport.last + 1 + KEEP_SCREENS * len
        }
        // What is known now: this update's window, joined to the old one when they touch, within what is kept.
        val old = v.known
        val joined = if (!old.isEmpty() && old.first <= end && old.last + 1 >= start) minOf(old.first, start) until maxOf(old.last + 1, end) else start until end
        val known = maxOf(joined.first, ks) until minOf(joined.last + 1, ke)
        val kept = v.layers.filter { it.to <= start || it.from >= end } + layers
        return v.copy(
            spans = replaceIn(v.spans, spans, start, end, ks, ke), folds = replaceFolds(v.folds, folds, start, end), lastUpdate = u.version,
            known = known, layers = kept.filter { it.to > known.first && it.from < known.last + 1 },
        )
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun nextEpoch(): Long = epochs.addAndFetch(1)

    /** Drop the oldest log entries while their inserted text exceeds [LOG_MAX_INSERTED] (a big paste). */
    private fun capLog(log: List<VersionedChanges>): List<VersionedChanges> {
        var total = 0L
        var keepFrom = log.size
        for (i in log.indices.reversed()) {
            total += log[i].changes.iterChanges().sumOf { it.inserted.length.toLong() }
            if (total > LOG_MAX_INSERTED && keepFrom < log.size) break
            keepFrom = i
        }
        return if (keepFrom == 0) log else log.subList(keepFrom, log.size).toList()
    }

    /**
     * [old] outside [start, end) (a span crossing an edge keeps its outside part) plus [new] inside,
     * all within [keepFrom, keepTo).
     */
    private fun replaceIn(
        old: RangeSet<Decoration>, new: RangeSet<Decoration>, start: Int, end: Int, keepFrom: Int, keepTo: Int,
    ): RangeSet<Decoration> {
        val out = ArrayList<Ranged<Decoration>>(old.size + new.size)
        for (r in old) {
            if (r.to <= keepFrom || r.from >= keepTo) continue
            if (r.to <= start || r.from >= end) { out += r; continue }
            if (r.from < start) out += Ranged(r.from, start, r.value)
            if (r.to > end) out += Ranged(end, r.to, r.value)
        }
        for (r in new) {
            val a = maxOf(r.from, start)
            val b = minOf(r.to, end)
            if (b > a) out += if (a == r.from && b == r.to) r else Ranged(a, b, r.value)
        }
        return RangeSet.of(out)
    }

    /** Folds intersecting [start, end) come from [new]; the others stay. */
    private fun replaceFolds(old: IntArray, new: IntArray, start: Int, end: Int): IntArray {
        val out = ArrayList<Long>()
        for (i in old.indices step 2) if (old[i + 1] <= start || old[i] >= end) out += old[i].toLong() shl 32 or old[i + 1].toLong()
        for (i in new.indices step 2) out += new[i].toLong() shl 32 or new[i + 1].toLong()
        val sorted = out.distinct().sorted()
        return IntArray(sorted.size * 2) { i -> if (i % 2 == 0) (sorted[i / 2] ushr 32).toInt() else sorted[i / 2].toInt() }
    }

    private fun mapFolds(f: IntArray, c: ChangeSet): IntArray {
        if (f.isEmpty()) return f
        val out = ArrayList<Int>(f.size)
        for (i in f.indices step 2) {
            val a = c.mapPos(f[i], 1)
            val b = c.mapPos(f[i + 1], -1)
            if (b > a) { out += a; out += b }
        }
        return out.toIntArray()
    }

    private fun mapLayers(l: List<LayerRange>, c: ChangeSet): List<LayerRange> = if (l.isEmpty()) l else l.mapNotNull {
        val a = c.mapPos(it.from, 1)
        val b = c.mapPos(it.to, -1)
        if (b > a) LayerRange(a, b, it.depth, it.language) else null
    }

    private fun mapViewport(r: IntRange, c: ChangeSet): IntRange {
        if (r.isEmpty()) return r
        val a = c.mapPos(minOf(r.first, c.lengthBefore), -1)
        return a until maxOf(a, c.mapPos(minOf(r.last + 1, c.lengthBefore), 1))
    }
}
