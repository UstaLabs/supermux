package dev.supermux.editor.syntax

import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.Ranged
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.TimeSource

/**
 * The edits made since a highlighter's layers were parsed, in order, shared by every document of
 * one [Highlighter]. A layer's tree is brought up to date lazily ([Layer.tree]): a Markdown file
 * has thousands of layers and one keystroke touches one of them, so the others are neither edited
 * (a JNI call each) nor copied.
 */
internal class EditLog {
    var base = 0L // absolute index of edits[0]
    val edits = ArrayList<TextEdit>()
    val end: Long get() = base + edits.size
}

/**
 * One parse of one document version, including injected sub-trees. Owned by the worker: it
 * holds native trees, so it never goes into an EditorState. [close] frees every tree it still owns.
 */
class ParsedDocument internal constructor(internal val layers: List<Layer>, val length: Int, internal val log: EditLog) : AutoCloseable {
    private var closed = false

    init {
        // a layer handed on from the previous parse belongs to this document now
        for (l in layers) l.owner = this
    }

    /** Text edited since this parse, in the current coordinates ([start, end]*): injections are re-found there. */
    internal var edited: IntArray = IntArray(0)
        private set

    val language: String get() = layers[0].language

    /** The injected layers as "depth:language@start", in paint order (host excluded). */
    val injections: List<String> get() = paintOrder().drop(1).map { "${it.depth}:${it.language}@${it.ranges.firstOrNull() ?: 0}" }

    /** Every layer (host first) with its ranges: equal for equal parses (the replay tests compare them). */
    val layerSignature: List<String>
        get() = paintOrder().map { "${it.depth}:${it.parentLanguage}>${it.language}#${it.pattern}${it.ranges.toList()}" }

    private var order: List<Layer>? = null

    /** Host first, then by (depth, first range start, language): paint order must not depend on how a layer was found. */
    internal fun paintOrder(): List<Layer> = order ?: run {
        // the parse's breadth-first order usually is that order already: check in O(n) before sorting
        val sorted = (1 until layers.size).all { layerOrder(layers[it - 1], layers[it]) <= 0 }
        (if (sorted) layers else layers.sortedWith(::layerOrder)).also { order = it }
    }

    /** Record one edit in every tree (host and injections), before the next [Highlighter.parse]. */
    fun edit(e: TextEdit) {
        check(!closed) { "document closed" }
        log.edits += e
        for (l in layers) {
            val r = l.ranges
            // a layer whose text the edit touches must be reparsed; the others stay valid as they are
            if (r.isEmpty() || Highlighter.touches(r, e.start, e.oldEnd)) l.dirty = true
            l.clean = false
            if (r.isNotEmpty() && r[r.size - 1] < e.start) continue // wholly before the edit: nothing moves
            l.ranges = mapRanges(r, e)
            for (site in l.sites) for (part in site.parts) {
                if (part.extentEnd < e.start) continue
                part.ranges = mapRanges(part.ranges, e)
                part.extentStart = mapPos(part.extentStart, e)
                part.extentEnd = mapPos(part.extentEnd, e)
            }
        }
        edited = mapRanges(edited, e) + intArrayOf(e.start, e.newEnd)
        order = null
    }

    override fun close() {
        if (closed) return
        closed = true
        for (l in layers) if (l.owner === this) l.close()
    }

    internal companion object {
        fun layerOrder(a: Layer, b: Layer): Int {
            if (a.depth != b.depth) return a.depth.compareTo(b.depth)
            val sa = a.ranges.firstOrNull() ?: 0
            val sb = b.ranges.firstOrNull() ?: 0
            if (sa != sb) return sa.compareTo(sb)
            val l = a.language.compareTo(b.language)
            return if (l != 0) l else a.pattern.compareTo(b.pattern)
        }

        fun mapPos(p: Int, e: TextEdit): Int = when { p < e.start -> p; p >= e.oldEnd -> p + e.newEnd - e.oldEnd; else -> e.start }
        fun mapRanges(r: IntArray, e: TextEdit): IntArray = if (r.isEmpty()) r else IntArray(r.size) { mapPos(r[it], e) }
    }
}

/**
 * A parse tree over part of the document: the host (depth 0, the whole document, [ranges] empty)
 * or an injection (its included UTF-16 [ranges], packed [start, end]*). [sites] are the
 * injections found in it, kept so the next parse only looks again where something changed.
 */
internal class Layer(
    val language: String,
    private val handle: TreeHandle,
    var ranges: IntArray,
    val depth: Int,
    val pattern: Int,
    val parentLanguage: String?,
    private val log: EditLog,
    /** How many edits of [log] (absolute) [handle] carries. */
    private var applied: Long = log.end,
) {
    var sites: List<Site> = emptyList()
    /** An edit touched this layer's text since it was parsed (set by [ParsedDocument.edit]). */
    var dirty = false
    /** The document that frees this tree: a layer handed on unchanged moves to the next parse's document. */
    var owner: ParsedDocument? = null
    /** This layer is the previous parse's, handed on unchanged (same object, same tree). */
    var clean = false

    /** The tree, with every logged edit applied. */
    val tree: TreeHandle get() {
        while (applied < log.end) { handle.edit(log.edits[(applied - log.base).toInt()]); applied++ }
        return handle
    }

    fun key() = LayerKey(depth, parentLanguage, language, pattern, ranges.firstOrNull() ?: 0)
    fun close() = handle.close()
}

/** One match of an injection pattern: the content ranges it contributes and the extent of its captures. */
internal class Part(var extentStart: Int, var extentEnd: Int, var ranges: IntArray) {
    fun sameAs(o: Part) = extentStart == o.extentStart && extentEnd == o.extentEnd && ranges.contentEquals(o.ranges)
}

/** An injection found in a layer: one part, or every part of an `injection.combined` pattern. */
internal class Site(val pattern: Int, val language: String, val combined: Boolean, val parts: MutableList<Part>) {
    /** The layer last parsed (or handed on) for this site: a kept site finds its old tree here. */
    var layer: Layer? = null
    fun ranges(): IntArray = if (parts.size == 1) parts[0].ranges else Highlighter.normalize(parts.flatMap { it.ranges.asList() })
    /** Where the site starts: cached by [merge] (a combined site has hundreds of parts, and sorting compares it often). */
    var start: Int = 0
    fun computeStart(): Int = (if (parts.size == 1) parts[0].ranges.firstOrNull() ?: Int.MAX_VALUE
        else parts.minOfOrNull { it.ranges.firstOrNull() ?: Int.MAX_VALUE } ?: 0).also { start = it }
}

/** Where a layer is (depth, parent, language, pattern, first range start), not what it holds: its reuse key across parses. */
internal data class LayerKey(val depth: Int, val parent: String?, val language: String, val pattern: Int, val start: Int)

internal class LazyLayers(private val previous: ParsedDocument?) {
    private val map: Map<LayerKey, Layer> by lazy {
        HashMap<LayerKey, Layer>().also { m -> previous?.layers?.forEach { if (it.depth > 0) m[it.key()] = it } }
    }
    operator fun get(k: LayerKey): Layer? = if (previous == null) null else map[k]
}

/** [Highlighter.parse] was abandoned because a newer snapshot is waiting. */
class ParseCancelled : Exception("parse cancelled: a newer snapshot is waiting")

/**
 * Parses a document (with its injections) and answers spans and folds for a range. Owns a parser
 * per language; its queries are the backend's shared ones. Not thread-safe (the syntax worker's
 * thread only).
 *
 * Priority, flattened into non-overlapping marks:
 * - an injected layer's span wins over the host span it overlaps; layers paint in the order
 *   (depth, first range start, language), so the result never depends on how a layer was found;
 * - within a layer, a node captured by several patterns takes the LATEST pattern's capture, as
 *   tree-sitter-highlight 0.25 does (and Helix and nvim-treesitter), and an inner node wins over
 *   the outer one it sits in; `@none` clears.
 *
 * A reparse re-finds injections only where the tree changed or the text was edited, reuses a
 * layer's tree incrementally when its ranges are unchanged, and does not reparse a layer at all
 * when no edit touched it.
 */
class Highlighter(
    private val backend: SyntaxBackend,
    val language: String,
    private val registry: LanguageRegistry = LanguageRegistry.default,
    /** Injected layers nest at most this deep (Markdown -> HTML -> JavaScript is 2). */
    private val maxDepth: Int = 3,
) : AutoCloseable {
    private val parsers = HashMap<String, ParserHandle>()
    private val editLog = EditLog()
    private var previousDoc: ParsedDocument? = null
    private val injectionSettings = HashMap<String, InjectionSettings>()
    private val classIndex = HashMap<String, IntArray>()

    /** Each parse runs in slices this long (0: one go), checking [parse]'s `cancel` between them. */
    var sliceMicros: Long = 0
    /** A whole [parse] (every layer) may take this long; beyond it SyntaxException(TIMEOUT). 0: no limit. */
    var budgetMicros: Long = 0
    /** Called between two parse slices (tests). */
    internal var onSlice: (() -> Unit)? = null
    /**
     * Where the syntax worker shares the UI thread (the web: [platformSliceYield]), [parseSuspending]
     * calls this between slices and before a layer's parse once a slice's time is used up, so no
     * run of parsing holds the thread for more than about [sliceMicros]. null: never (the native
     * worker has a thread of its own). [parse] never yields.
     */
    var yieldBetweenSlices: (suspend () -> Unit)? = null
    /** Diagnostics (once per document for each kind). */
    var log: (String) -> Unit = { println("editor-syntax: $it") }
    private var loggedMatchLimit = false

    /** Nanoseconds per phase of the last [parse] (diagnostics, benchmarks). */
    val phases: MutableMap<String, Long> = LinkedHashMap()
    private inline fun <T> phase(name: String, block: () -> T): T {
        val t = TimeSource.Monotonic.markNow()
        try { return block() } finally { phases[name] = (phases[name] ?: 0L) + t.elapsedNow().inWholeNanoseconds }
    }

    /** Injected languages met while not [SyntaxBackend.isReady]: the worker loads them and parses again. */
    val pendingLanguages: MutableSet<String> = HashSet()
    /** Injected languages that failed to load: skipped for good in this document. */
    val failedLanguages: MutableSet<String> = HashSet()

    init {
        check(backend.isReady(language)) { "$language is not loaded: await backend.ensureLanguage first" }
    }

    /**
     * Parse [text] ([length] units; [points] gives rows and columns), reusing [previous] if given,
     * which must already carry the edits. [cancel] is asked between parse slices: true throws
     * [ParseCancelled]. Over [budgetMicros] throws SyntaxException(TIMEOUT).
     */
    fun parse(
        text: TextSource, length: Int, previous: ParsedDocument?,
        points: PointSource = LineTable(text, length), cancel: () -> Boolean = { false },
    ): ParsedDocument = runNow { parse(text, length, previous, points, cancel, null) }

    /** [parse], giving the thread away between slices through [yieldBetweenSlices] (the worker's). */
    suspend fun parseSuspending(
        text: TextSource, length: Int, previous: ParsedDocument?,
        points: PointSource = LineTable(text, length), cancel: () -> Boolean = { false },
    ): ParsedDocument = parse(text, length, previous, points, cancel, yieldBetweenSlices)

    private suspend fun parse(
        text: TextSource, length: Int, previous: ParsedDocument?, points: PointSource, cancel: () -> Boolean,
        yielder: (suspend () -> Unit)?,
    ): ParsedDocument {
        val started = TimeSource.Monotonic.markNow()
        phases.clear()
        previousDoc = previous
        val slicer = Slicer(started, cancel, yielder)
        val host = parser(language)
        host.setIncludedRanges(IntArray(0), points)
        val layers = ArrayList<Layer>()
        try {
            check(previous == null || previous.log === editLog) { "previous is another highlighter's document" }
            val oldHost = previous?.layers?.get(0)
            layers += Layer(language, phase("host") { slicer.parse(host, text, oldHost?.tree) }, IntArray(0), 0, -1, null, editLog)
            // old layers by where they are, for re-found sites (a kept site knows its layer); built on first use
            val old = LazyLayers(previous)
            // new layer -> the old layer it came from (its sites say where the injections were)
            val from = HashMap<Layer, Layer>()
            if (oldHost != null) from[layers[0]] = oldHost
            val edited = previous?.edited ?: IntArray(0)
            var i = 0
            while (i < layers.size) {
                val l = layers[i]
                // a layer handed on unchanged with no injections of its own has nothing to do
                if (!(l.clean && l.sites.isEmpty())) inject(l, if (l.clean) l else from[l], edited, text, points, length, old, from, layers, slicer)
                i++
            }
        } catch (t: Throwable) {
            for (l in layers) if (!l.clean) l.close() // a handed-on layer still belongs to [previous]
            throw t
        }
        // Trees handed on keep their place in the log; once it is long, bring every tree up to date and start over.
        if (editLog.edits.size > 512) {
            layers.forEach { it.tree }
            editLog.base = editLog.end
            editLog.edits.clear()
        }
        return ParsedDocument(layers, length, editLog)
    }

    /**
     * Time slices over one [parse] call: resumes a timed-out parse until done, cancelled or over
     * budget. With a [yielder], a slice is measured from the last yield, across layers: a layer's
     * parse gets only what is left of the slice, and the thread is given away when it is used up.
     */
    private inner class Slicer(
        private val started: TimeSource.Monotonic.ValueTimeMark,
        private val cancel: () -> Boolean,
        private val yielder: (suspend () -> Unit)?,
    ) {
        private var lastYield = TimeSource.Monotonic.markNow()
        /** Time spent given away: the budget counts only the time spent working. */
        private var yielded = kotlin.time.Duration.ZERO

        private fun left(): Long = sliceMicros - lastYield.elapsedNow().inWholeMicroseconds

        /** Microseconds of work since the parse started (not the time other tasks ran during yields). */
        private fun worked(): Long = (started.elapsedNow() - yielded).inWholeMicroseconds

        private suspend fun giveAway(y: suspend () -> Unit) {
            val t = TimeSource.Monotonic.markNow()
            y()
            yielded += t.elapsedNow()
            lastYield = TimeSource.Monotonic.markNow()
            // a newer text may have arrived while the thread was away: stop a stale parse at once
            if (cancel()) throw ParseCancelled()
        }

        /**
         * Between two steps that are not parses (injection query windows, merging): the next step
         * cannot be interrupted, so yield once half of the slice is used.
         */
        suspend fun checkpoint() {
            val y = if (sliceMicros > 0) yielder else null
            if (y != null && left() < sliceMicros / 2) giveAway(y)
        }

        suspend fun parse(p: ParserHandle, text: TextSource, old: TreeHandle?): TreeHandle {
            val slice = if (sliceMicros > 0) sliceMicros else budgetMicros
            val y = if (sliceMicros > 0) yielder else null
            try {
                if (y != null && left() < sliceMicros / 4) giveAway(y)
                p.setTimeoutMicros(if (y != null) maxOf(1L, left()) else slice)
                while (true) {
                    try {
                        return p.parse(text, old)
                    } catch (e: SyntaxException) {
                        if (e.status != SyntaxStatus.TIMEOUT) throw e
                        if (cancel()) throw ParseCancelled()
                        if (budgetMicros > 0 && worked() > budgetMicros) {
                            throw SyntaxException("parse over its ${budgetMicros / 1000} ms budget", SyntaxStatus.TIMEOUT)
                        }
                        onSlice?.invoke()
                        if (y != null) {
                            giveAway(y)
                            p.setTimeoutMicros(slice)
                        }
                    }
                }
            } catch (t: Throwable) {
                // Whatever ends the loop (cancelled, over budget, a throwing TextSource or callback,
                // out of memory), a suspended parse must not be resumed by the next parse of other text.
                p.reset()
                throw t
            }
        }
    }

    /** Spans in [start, end): sorted, non-overlapping after priority resolution, each a token class. */
    fun spans(doc: ParsedDocument, start: Int, end: Int, text: TextSource): List<Ranged<Decoration>> {
        val s = maxOf(0, start)
        val e = minOf(doc.length, end)
        if (e <= s) return emptyList()
        val paint = IntArray(e - s) { -1 }
        for (layer in doc.paintOrder()) { // a later paint wins
            if (layer.ranges.isNotEmpty() && !intersects(layer.ranges, s, e)) continue
            val q = query(layer.language, QueryKind.HIGHLIGHTS) ?: continue
            val cls = classes(layer.language, q)
            val c = captures(q, layer.tree, s, e, text)
            // Sort the drawable captures by (start, end descending, pattern): outer nodes first, and
            // for one node range its captures in pattern order, the last of which wins.
            val idx = (0 until c.size).filter { cls[c.capture(it)] != SKIP && c.end(it) > c.start(it) }
                .sortedWith(compareBy<Int>({ c.start(it) }, { -c.end(it) }, { c.pattern(it) }, { it }))
            // One sweep with a stack of the open (enclosing) nodes: every unit is painted once, by
            // the innermost node covering it (nodes of one tree nest). `@none` paints "no colour".
            val stackEnd = IntArray(idx.size)
            val stackCls = IntArray(idx.size)
            var depth = 0
            var pos = s
            fun emit(to: Int) {
                val upTo = minOf(to, e)
                if (depth > 0 && upTo > pos) paintRange(paint, s, pos, upTo, maxOf(-1, stackCls[depth - 1]), layer.ranges)
                if (upTo > pos) pos = upTo
            }
            var k = 0
            while (k < idx.size) {
                val i = idx[k]
                var j = k // the same node range again: only its last (latest pattern) capture counts
                while (j + 1 < idx.size && c.start(idx[j + 1]) == c.start(i) && c.end(idx[j + 1]) == c.end(i)) j++
                val w = idx[j]
                val st = maxOf(s, c.start(w))
                while (depth > 0 && stackEnd[depth - 1] <= st) { emit(stackEnd[depth - 1]); depth-- }
                emit(st)
                pos = maxOf(pos, st)
                stackEnd[depth] = if (depth > 0) minOf(c.end(w), stackEnd[depth - 1]) else c.end(w)
                stackCls[depth] = cls[c.capture(w)]
                depth++
                k = j + 1
            }
            while (depth > 0) { emit(stackEnd[depth - 1]); depth-- }
        }
        val out = ArrayList<Ranged<Decoration>>()
        var i = 0
        while (i < paint.size) {
            val k = paint[i]
            var j = i + 1
            while (j < paint.size && paint[j] == k) j++
            if (k >= 0) out += Ranged(s + i, s + j, MARKS[k])
            i = j
        }
        return out
    }

    /** @fold ranges intersecting [start, end), as UTF-16 [start, end) pairs, sorted, host and injections. */
    fun folds(doc: ParsedDocument, start: Int, end: Int, text: TextSource): IntArray {
        val s = maxOf(0, start)
        val e = minOf(doc.length, end)
        val found = HashSet<Long>()
        for (layer in doc.layers) {
            if (layer.ranges.isNotEmpty() && !intersects(layer.ranges, s, e)) continue
            val q = query(layer.language, QueryKind.FOLDS) ?: continue
            val fold = q.captureNames.indexOf("fold")
            if (fold < 0) continue
            val c = captures(q, layer.tree, s, maxOf(s, e), text)
            for (i in 0 until c.size) {
                if (c.capture(i) == fold && c.end(i) > c.start(i)) found += c.start(i).toLong() shl 32 or c.end(i).toLong()
            }
        }
        val sorted = found.sorted()
        return IntArray(sorted.size * 2) { i -> if (i % 2 == 0) (sorted[i / 2] ushr 32).toInt() else sorted[i / 2].toInt() }
    }

    override fun close() {
        parsers.values.forEach { it.close() }
        parsers.clear()
    }

    /**
     * Captures of [s, e); when the cursor dropped matches (its match limit), again in 4096-unit
     * windows, where far fewer matches are in progress at once.
     */
    private fun captures(q: QueryHandle, tree: TreeHandle, s: Int, e: Int, text: TextSource): Captures {
        val c = q.captures(tree, s, e, text)
        if (!c.exceededMatchLimit || e - s <= WINDOW) return c
        if (!loggedMatchLimit) { loggedMatchLimit = true; log("query match limit exceeded in $language; querying in ${WINDOW}-unit windows") }
        val parts = ArrayList<IntArray>()
        var a = s
        while (a < e) { val b = minOf(e, a + WINDOW); parts += q.captures(tree, a, b, text).ints; a = b }
        val all = IntArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(all, o); o += p.size }
        return Captures(all, false)
    }

    // ------------------------------------------------------------------------------ injections --

    private class InjectionSettings(
        val language: String?, val combined: Boolean, val includeChildren: Boolean,
        val includeUnnamedChildren: Boolean, val self: Boolean, val parent: Boolean,
    )

    private fun settingsOf(lang: String, q: QueryHandle, pattern: Int): InjectionSettings = injectionSettings.getOrPut("$lang/$pattern") {
        val set = q.settings(pattern).filter { it.kind == PatternSetting.Kind.SET }.associate { it.key to it.value }
        InjectionSettings(
            language = set["injection.language"],
            combined = "injection.combined" in set,
            includeChildren = "injection.include-children" in set,
            includeUnnamedChildren = "injection.include-unnamed-children" in set,
            self = "injection.self" in set,
            parent = "injection.parent" in set,
        )
    }

    /**
     * Find [parent]'s injections and parse each, appending the layers to [out]. With [old] (the
     * layer [parent] came from): a clean parent keeps every site; otherwise the parts whose
     * extent touches the changed ranges (tree changes + edited text) are dropped, and the query
     * runs again over those ranges together with the dropped parts' extents, so nothing next to
     * an edit is lost. A layer's tree is copied when nothing it covers changed, reparsed
     * incrementally when its ranges are the same, and parsed afresh otherwise.
     */
    private suspend fun inject(
        parent: Layer, old: Layer?, edited: IntArray, text: TextSource, points: PointSource, length: Int,
        oldLayers: LazyLayers, from: MutableMap<Layer, Layer>, out: MutableList<Layer>, slicer: Slicer,
    ) {
        if (parent.depth >= maxDepth) return
        val q = query(parent.language, QueryKind.INJECTIONS) ?: return
        val start = parent.ranges.firstOrNull() ?: 0
        val end = parent.ranges.lastOrNull() ?: length
        if (!parent.clean) slicer.checkpoint() // the parse before, then changed ranges + a query: both uninterruptible
        val sites: List<Site> = phase("sites") { when {
            old == null -> {
                val found = phase("s.find") { findParts(parent, q, intArrayOf(start, end), text, slicer) }
                slicer.checkpoint()
                phase("s.merge") { merge(emptyList(), found) }
            }
            parent.clean -> old.sites
            else -> {
                val around = IntArray(edited.size) { i -> if (i % 2 == 0) maxOf(0, edited[i] - 1) else edited[i] + 1 }
                val changed = phase("s.changed") { normalize((old.tree.changedRanges(parent.tree) + around).toList()) }
                val dropped = ArrayList<Int>()
                val kept = phase("s.kept") { old.sites.map { site ->
                    if (site.parts.none { touches(changed, it.extentStart, it.extentEnd) }) return@map site // untouched: as it is
                    val (stay, go) = site.parts.partition { !touches(changed, it.extentStart, it.extentEnd) }
                    go.forEach { dropped += it.extentStart; dropped += it.extentEnd }
                    Site(site.pattern, site.language, site.combined, stay.toMutableList())
                } }
                val region = clip(normalize((changed.toList() + dropped)), intArrayOf(start, end))
                val found = phase("s.find") { findParts(parent, q, region, text, slicer) }
                slicer.checkpoint()
                phase("s.merge") { merge(kept, found) }
            }
        } }
        parent.sites = sites
        slicer.checkpoint()
        phase("layers") { for (site in sites) {
            if (site.language in failedLanguages) continue
            if (!isReady(site.language)) { pendingLanguages += site.language; continue }
            val ranges = clip(site.ranges(), parent.ranges)
            if (ranges.isEmpty()) continue
            // the site's own last layer if it still fits, else whatever old layer sits at that place
            val fits = { l: Layer -> l.ranges.contentEquals(ranges) && l.owner === previousDoc && l.depth == parent.depth + 1 }
            val prev = site.layer?.takeIf(fits)
                ?: oldLayers[LayerKey(parent.depth + 1, parent.language, site.language, site.pattern, ranges[0])]?.takeIf(fits)
            val layer = if (prev != null && !prev.dirty) {
                prev.also { it.clean = true } // handed on as it is
            } else {
                val parser = parser(site.language)
                parser.setIncludedRanges(ranges, points)
                phase("layerParse") { Layer(site.language, slicer.parse(parser, text, prev?.tree), ranges, parent.depth + 1, site.pattern, parent.language, editLog) }
            }
            if (prev != null && !layer.clean) from[layer] = prev
            site.layer = layer
            out += layer
        } }
    }

    private class Found(val pattern: Int, val language: String, val combined: Boolean, val part: Part)

    /** What [Part.sameAs] compares, hashable. */
    private data class PartKey(val extentStart: Int, val extentEnd: Int, val ranges: List<Int>) {
        constructor(p: Part) : this(p.extentStart, p.extentEnd, p.ranges.asList())
    }

    /**
     * Kept sites plus newly found parts: a combined part joins its (pattern, language) site; equal
     * parts are one. The kept sites are in order already; the few new ones are merged in. Lookups
     * are hashed: a first parse of a Markdown file finds thousands of parts (a pairwise search
     * took 90 ms for 3.5k in the browser).
     */
    private fun merge(kept: List<Site>, found: List<Found>): List<Site> {
        val sites = kept.filter { it.parts.isNotEmpty() || it.combined }.toMutableList()
        val fresh = ArrayList<Site>()
        var reorder = false
        // the first combined site per (pattern, language), kept ones first; a pattern is combined or not
        val combined = HashMap<Pair<Int, String>, Site>()
        for (x in sites) if (x.combined) combined.getOrPut(x.pattern to x.language) { x }
        val partsOf = HashMap<Site, HashSet<PartKey>>()
        // the (pattern, language, first part) of every single site, kept or new
        val singles = HashSet<Triple<Int, String, PartKey>>()
        for (x in sites) if (!x.combined) x.parts.firstOrNull()?.let { singles += Triple(x.pattern, x.language, PartKey(it)) }
        for (f in found) {
            val key = PartKey(f.part)
            if (f.combined) {
                val site = combined.getOrPut(f.pattern to f.language) { Site(f.pattern, f.language, true, ArrayList()).also { fresh += it } }
                if (partsOf.getOrPut(site) { site.parts.mapTo(HashSet()) { PartKey(it) } }.add(key)) { site.parts += f.part; reorder = true }
            } else if (singles.add(Triple(f.pattern, f.language, key))) {
                fresh += Site(f.pattern, f.language, false, mutableListOf(f.part))
            }
        }
        for (s in sites) if (s.parts.size > 1) s.parts.sortBy { it.extentStart }
        for (s in fresh) if (s.parts.size > 1) s.parts.sortBy { it.extentStart }
        val live = sites.filter { s -> s.parts.any { it.ranges.isNotEmpty() } }
        val add = fresh.filter { s -> s.parts.any { it.ranges.isNotEmpty() } }.toMutableList()
        for (x in live) x.computeStart()
        for (x in add) x.computeStart()
        add.sortWith(::siteOrder)
        // merge two sorted lists; a combined site that gained a part may have moved: then sort all
        val out = ArrayList<Site>(live.size + add.size)
        var i = 0
        var j = 0
        while (i < live.size || j < add.size) {
            out += if (j >= add.size || (i < live.size && siteOrder(live[i], add[j]) <= 0)) live[i++] else add[j++]
        }
        if (reorder || (1 until out.size).any { siteOrder(out[it - 1], out[it]) > 0 }) out.sortWith(::siteOrder)
        return out
    }

    private fun siteOrder(a: Site, b: Site): Int {
        val sa = a.start
        val sb = b.start
        if (sa != sb) return sa.compareTo(sb)
        val l = a.language.compareTo(b.language)
        return if (l != 0) l else a.pattern.compareTo(b.pattern)
    }

    /** The injection matches of [parent] intersecting [where] ([start, end]*), one part each. */
    /** A match by its pattern and its captures' (start, end, index): found again from another range or window. */
    private data class MatchKey(val pattern: Int, val captures: List<Int>)

    /**
     * The injection matches of [parent] intersecting [where] ([start, end]*), one part each. Each
     * range is queried in windows of [INJECTION_WINDOW] units, with a [Slicer.checkpoint] between
     * them: one query over a whole 10k-line Markdown file held the web's UI thread for 46 ms. A
     * match crossing a window edge is found from both windows and kept once.
     */
    private suspend fun findParts(parent: Layer, q: QueryHandle, where: IntArray, text: TextSource, slicer: Slicer): List<Found> {
        val content = q.captureNames.indexOf("injection.content")
        if (content < 0 || where.isEmpty()) return emptyList()
        val langCapture = q.captureNames.indexOf("injection.language")
        val fileCapture = q.captureNames.indexOf("injection.filename")
        val out = ArrayList<Found>()
        val seen = HashSet<MatchKey>()
        for (w in where.indices step 2) {
            var from = where[w]
            while (true) {
                val to = minOf(where[w + 1], from + INJECTION_WINDOW)
                val m0 = q.matches(parent.tree, from, to, text, content)
                if (m0.exceededMatchLimit && !loggedMatchLimit) { loggedMatchLimit = true; log("injection query match limit exceeded in ${parent.language}") }
                for (m in m0.toList()) {
                    // a match found again from a second range or window
                    val key = IntArray(3 * m.captures.size)
                    m.captures.forEachIndexed { i, c -> key[3 * i] = c.start; key[3 * i + 1] = c.end; key[3 * i + 2] = c.index }
                    if (!seen.add(MatchKey(m.pattern, key.asList()))) continue
                    val st = settingsOf(parent.language, q, m.pattern)
                    var lang: String? = st.language?.let { registry.aliasFor(it) }
                    if (st.self) lang = parent.language
                    if (st.parent) lang = parent.parentLanguage ?: parent.language
                    m.captures.firstOrNull { it.index == langCapture }?.let { lang = registry.aliasFor(slice(text, it.start, it.end)) }
                    m.captures.firstOrNull { it.index == fileCapture }?.let { lang = registry.forFile(slice(text, it.start, it.end)) }
                    val l = lang ?: continue
                    if (l !in backend.languages) continue
                    if (registry.query(l, QueryKind.HIGHLIGHTS) == null && registry.query(l, QueryKind.INJECTIONS) == null) continue
                    val ranges = ArrayList<Int>()
                    var es = Int.MAX_VALUE
                    var ee = 0
                    for (c in m.captures) {
                        es = minOf(es, c.start); ee = maxOf(ee, c.end)
                        if (c.index == content) contentRanges(c, st, ranges)
                    }
                    out += Found(m.pattern, l, st.combined, Part(es, ee, normalize(ranges)))
                }
                if (to >= where[w + 1]) break
                from = to
                slicer.checkpoint()
            }
        }
        return out
    }

    /** A content node's ranges: whole, or minus its children (all, or the named ones only). */
    private fun contentRanges(c: Matches.Capture, st: InjectionSettings, out: MutableList<Int>) {
        if (st.includeChildren || c.childCount == 0) { out += c.start; out += c.end; return }
        var at = c.start
        for (i in 0 until c.childCount) {
            if (st.includeUnnamedChildren && !c.childIsNamed(i)) continue
            if (c.childStart(i) > at) { out += at; out += c.childStart(i) }
            at = maxOf(at, c.childEnd(i))
        }
        if (c.end > at) { out += at; out += c.end }
    }

    // ------------------------------------------------------------------------------ helpers --

    private fun parser(lang: String): ParserHandle = parsers.getOrPut(lang) { backend.newParser(lang) }

    private val queries = QueryKind.entries.associateWith { HashMap<String, QueryHandle?>() }

    /** The backend's shared query, looked up once per (language, kind) here (the backend keys by the whole text). */
    private fun query(lang: String, kind: QueryKind): QueryHandle? {
        val m = queries.getValue(kind)
        if (m.containsKey(lang)) return m[lang]
        return registry.query(lang, kind)?.let { backend.sharedQuery(lang, it) }.also { m[lang] = it }
    }

    private val ready = HashSet<String>()

    /** [SyntaxBackend.isReady], remembered once true (native asks the library each time). */
    private fun isReady(lang: String): Boolean = lang in ready || backend.isReady(lang).also { if (it) ready += lang }

    /** Token class index (into [TokenClasses.ALL]) per capture of [q]; [NONE] = `@none`, [SKIP] = not drawn. */
    private fun classes(lang: String, q: QueryHandle): IntArray = classIndex.getOrPut(lang) {
        IntArray(q.captureNames.size) { i ->
            val name = q.captureNames[i]
            if (name == "none") NONE else tokenClassFor(name)?.let { TokenClasses.ALL.indexOf(it) } ?: SKIP
        }
    }

    internal companion object {
        /** Run [block] to completion on this thread; it must not suspend (a [parse] never yields). */
        fun <T> runNow(block: suspend () -> T): T {
            var result: Result<T>? = null
            block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
            return (result ?: error("a synchronous parse suspended")).getOrThrow()
        }

        /** `@none`: an explicit "no colour" that wins over an outer node's colour. */
        const val NONE = -1
        /** Not drawn and not competing (`@spell`, `_helper`, unknown names). */
        const val SKIP = -2
        const val WINDOW = 4096
        /** Units per injection query ([findParts]): about 2k lines of Markdown. */
        const val INJECTION_WINDOW = 32_768

        val MARKS: List<Decoration.Mark> = TokenClasses.ALL.map { Decoration.Mark(setOf(it), inclusiveStart = false, inclusiveEnd = false) }

        fun slice(text: TextSource, start: Int, end: Int): String {
            val sb = StringBuilder(end - start)
            while (start + sb.length < end) {
                val chunk = text.chunkAt(start + sb.length)
                if (chunk.isEmpty()) break
                sb.append(chunk, 0, minOf(chunk.length, end - start - sb.length))
            }
            return sb.toString()
        }

        /** Does [s, e] touch (overlap or abut) any of [ranges]? */
        fun touches(ranges: IntArray, s: Int, e: Int): Boolean {
            for (i in ranges.indices step 2) if (ranges[i] <= e && ranges[i + 1] >= s) return true
            return false
        }

        fun intersects(ranges: IntArray, s: Int, e: Int): Boolean {
            for (i in ranges.indices step 2) if (ranges[i] < e && ranges[i + 1] > s) return true
            return false
        }

        /** Sorted, merged [start, end]* ranges, empty ones dropped. */
        fun normalize(r: List<Int>): IntArray {
            val pairs = r.chunked(2).filter { it[1] > it[0] }.sortedBy { it[0] }
            val out = ArrayList<Int>()
            for ((a, b) in pairs) {
                if (out.isNotEmpty() && a <= out[out.size - 1]) out[out.size - 1] = maxOf(out[out.size - 1], b)
                else { out += a; out += b }
            }
            return out.toIntArray()
        }

        /** [r] intersected with [parent] (empty parent: the whole document). */
        fun clip(r: IntArray, parent: IntArray): IntArray {
            if (parent.isEmpty()) return r
            val out = ArrayList<Int>()
            var j = 0
            for (i in r.indices step 2) {
                while (j < parent.size && parent[j + 1] <= r[i]) j += 2
                var k = j
                while (k < parent.size && parent[k] < r[i + 1]) {
                    val a = maxOf(r[i], parent[k])
                    val b = minOf(r[i + 1], parent[k + 1])
                    if (b > a) { out += a; out += b }
                    k += 2
                }
            }
            return out.toIntArray()
        }

        /** Paint class [k] over [from, to) (absolute), only inside [ranges] (sorted) when there are any. */
        fun paintRange(paint: IntArray, base: Int, from: Int, to: Int, k: Int, ranges: IntArray) {
            if (ranges.isEmpty()) { for (p in from until to) paint[p - base] = k; return }
            // the first range ending after [from]: binary search over the sorted pairs
            var lo = 0
            var hi = ranges.size / 2
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (ranges[2 * mid + 1] <= from) lo = mid + 1 else hi = mid }
            var i = 2 * lo
            while (i < ranges.size && ranges[i] < to) {
                val a = maxOf(from, ranges[i])
                val b = minOf(to, ranges[i + 1])
                for (p in a until b) paint[p - base] = k
                i += 2
            }
        }
    }
}
