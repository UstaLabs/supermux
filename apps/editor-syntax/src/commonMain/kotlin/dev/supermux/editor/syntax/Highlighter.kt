package dev.supermux.editor.syntax

import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.Ranged

/**
 * One parse of one document version, including injected sub-trees. Owned by the worker: it
 * holds native trees, so it never goes into an EditorState. [close] frees every tree.
 */
class ParsedDocument internal constructor(internal val layers: List<Layer>, val length: Int) : AutoCloseable {
    private var closed = false

    /** Text edited since this parse, in the current coordinates ([start, end]*): injections are re-found there. */
    internal var edited: IntArray = IntArray(0)
        private set

    val language: String get() = layers[0].language

    /** The injected layers as "depth:language", in parse order (host excluded). */
    val injections: List<String> get() = layers.drop(1).map { "${it.depth}:${it.language}" }

    /** Record one edit in every tree (host and injections), before the next [Highlighter.parse]. */
    fun edit(e: TextEdit) {
        check(!closed) { "document closed" }
        for (l in layers) {
            l.tree.edit(e)
            l.ranges = mapRanges(l.ranges, e)
            for (site in l.sites) {
                site.ranges = mapRanges(site.ranges, e)
                site.extentStart = mapPos(site.extentStart, e)
                site.extentEnd = mapPos(site.extentEnd, e)
            }
        }
        edited = mapRanges(edited, e) + intArrayOf(e.start, e.newEnd)
    }

    override fun close() {
        if (closed) return
        closed = true
        for (l in layers) l.tree.close()
    }

    internal companion object {
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
    val tree: TreeHandle,
    var ranges: IntArray,
    val depth: Int,
    val pattern: Int,
    val parentLanguage: String?,
) {
    var sites: List<Site> = emptyList()

    fun key() = layerKey(depth, parentLanguage, language, pattern, ranges.firstOrNull() ?: 0)
}

/** One injection found in a layer: its language, content ranges, and the extent of its match. */
internal class Site(val pattern: Int, val language: String, var ranges: IntArray, var extentStart: Int, var extentEnd: Int) {
    fun sameAs(o: Site) = pattern == o.pattern && language == o.language && ranges.contentEquals(o.ranges)
}

/** Reuse key of a layer across parses: where it is (depth, parent, pattern, first range start), not what it holds. */
internal fun layerKey(depth: Int, parent: String?, language: String, pattern: Int, start: Int) = "$depth:$parent>$language#$pattern@$start"

/**
 * Parses a document (with its injections) and answers spans and folds for a range. Owns a parser
 * per language and compiled queries; not thread-safe (the syntax worker's thread only).
 *
 * Priority, flattened into non-overlapping marks:
 * - an injected layer's span wins over the host span it overlaps (deeper layers paint last);
 * - within a layer, a node captured by several patterns takes the LATEST pattern's capture, as
 *   tree-sitter-highlight 0.25 does (and Helix and nvim-treesitter; tools/fetch-queries.py
 *   reverses the few query files written the other way round), and an inner node wins over the
 *   outer one it sits in.
 */
class Highlighter(
    private val backend: SyntaxBackend,
    val language: String,
    private val registry: LanguageRegistry = LanguageRegistry.default,
    /** Injected layers nest at most this deep (Markdown -> HTML -> JavaScript is 2). */
    private val maxDepth: Int = 3,
) : AutoCloseable {
    private val parsers = HashMap<String, ParserHandle>()
    private val queries = HashMap<String, QueryHandle?>()
    private val injectionSettings = HashMap<String, InjectionSettings>()
    private val classIndex = HashMap<String, IntArray>()

    /** Per parse of each layer; 0 = none. A parse over it throws SyntaxException(TIMEOUT). */
    var timeoutMicros: Long = 0
        set(value) { field = value; parsers.values.forEach { it.setTimeoutMicros(value) } }

    init {
        backend.ensureLanguage(language)
    }

    /** Parse [text] ([length] units), reusing [previous] if given, which must already carry the edits. */
    fun parse(text: TextSource, length: Int, previous: ParsedDocument?): ParsedDocument {
        val host = parser(language)
        host.setIncludedRanges(IntArray(0), text)
        val layers = ArrayList<Layer>()
        try {
            val oldHost = previous?.layers?.get(0)
            layers += Layer(language, host.parse(text, oldHost?.tree), IntArray(0), 0, -1, null)
            val old = previous?.layers?.drop(1)?.associateBy { it.key() } ?: emptyMap()
            // new layer -> the old layer it was parsed from (its sites say where the injections were)
            val from = HashMap<Layer, Layer>()
            if (oldHost != null) from[layers[0]] = oldHost
            val edited = previous?.edited ?: IntArray(0)
            var i = 0
            while (i < layers.size) { inject(layers[i], from[layers[i]], edited, text, length, old, from, layers); i++ }
        } catch (t: Throwable) {
            layers.forEach { it.tree.close() }
            throw t
        }
        return ParsedDocument(layers, length)
    }

    /** Spans in [start, end): sorted, non-overlapping after priority resolution, each a token class. */
    fun spans(doc: ParsedDocument, start: Int, end: Int, text: TextSource): List<Ranged<Decoration>> {
        val s = maxOf(0, start)
        val e = minOf(doc.length, end)
        if (e <= s) return emptyList()
        val paint = IntArray(e - s) { -1 }
        // host first, then deeper layers: a later paint wins
        for (layer in doc.layers.sortedBy { it.depth }) {
            if (layer.ranges.isNotEmpty() && !intersects(layer.ranges, s, e)) continue
            val q = query(layer.language, QueryKind.HIGHLIGHTS) ?: continue
            val cls = classes(layer.language, q)
            val c = q.captures(layer.tree, s, e, text)
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
                // the same node range again: only its last (latest pattern) capture counts
                var j = k
                while (j + 1 < idx.size && c.start(idx[j + 1]) == c.start(i) && c.end(idx[j + 1]) == c.end(i)) j++
                val w = idx[j]
                val start = maxOf(s, c.start(w))
                while (depth > 0 && stackEnd[depth - 1] <= start) { emit(stackEnd[depth - 1]); depth-- }
                emit(start)
                pos = maxOf(pos, start)
                val end = if (depth > 0) minOf(c.end(w), stackEnd[depth - 1]) else c.end(w)
                stackEnd[depth] = end
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
            val c = q.captures(layer.tree, s, maxOf(s, e), text)
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
        queries.values.forEach { it?.close() }
        queries.clear()
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

    private val hasCombined = HashMap<String, Boolean>()

    /** Does [q] (the injections of [lang]) combine matches? Those need the whole document every time. */
    private fun combines(lang: String, q: QueryHandle): Boolean = hasCombined.getOrPut(lang) {
        (0 until q.patternCount).any { settingsOf(lang, q, it).combined }
    }

    /**
     * Find [parent]'s injections and parse each, appending the layers to [out]. With [old] (the
     * layer [parent] was reparsed from) only the ranges that changed are searched again: where the
     * new tree differs from the old one, and the text [edited] since; the other injection sites
     * are kept. A layer is reused from [oldLayers] by where it is ([Layer.key]).
     */
    private fun inject(
        parent: Layer, old: Layer?, edited: IntArray, text: TextSource, length: Int,
        oldLayers: Map<String, Layer>, from: MutableMap<Layer, Layer>, out: MutableList<Layer>,
    ) {
        if (parent.depth >= maxDepth) return
        val q = query(parent.language, QueryKind.INJECTIONS) ?: return
        val start = parent.ranges.firstOrNull() ?: 0
        val end = parent.ranges.lastOrNull() ?: length
        val sites: List<Site> = if (old == null || combines(parent.language, q)) {
            findSites(parent, q, intArrayOf(start, end), text)
        } else {
            // the edited text widened by one unit each side: a deletion is empty, and an edit right
            // at a node's edge can change the match of the node next to it
            val around = IntArray(edited.size) { i -> if (i % 2 == 0) maxOf(0, edited[i] - 1) else edited[i] + 1 }
            val changed = normalize((old.tree.changedRanges(parent.tree) + around).toList())
            val kept = old.sites.filter { s -> !touches(changed, s.extentStart, s.extentEnd) }
            val found = findSites(parent, q, clip(changed, intArrayOf(start, end)), text)
            (kept + found.filter { f -> kept.none { it.sameAs(f) } }).sortedBy { it.ranges[0] }
        }
        parent.sites = sites
        for (site in sites) {
            val ranges = clip(site.ranges, parent.ranges)
            if (ranges.isEmpty()) continue
            backend.ensureLanguage(site.language)
            val parser = parser(site.language)
            parser.setIncludedRanges(ranges, text)
            val reuse = oldLayers[layerKey(parent.depth + 1, parent.language, site.language, site.pattern, ranges[0])]
            val layer = Layer(site.language, parser.parse(text, reuse?.tree), ranges, parent.depth + 1, site.pattern, parent.language)
            if (reuse != null) from[layer] = reuse
            out += layer
        }
    }

    private class Pending(val language: String, val pattern: Int, val ranges: ArrayList<Int> = ArrayList()) {
        var extentStart = Int.MAX_VALUE
        var extentEnd = 0
    }

    /** The injection sites of [parent] whose matches intersect [where] ([start, end]*). */
    private fun findSites(parent: Layer, q: QueryHandle, where: IntArray, text: TextSource): List<Site> {
        val content = q.captureNames.indexOf("injection.content")
        if (content < 0 || where.isEmpty()) return emptyList()
        val langCapture = q.captureNames.indexOf("injection.language")
        val fileCapture = q.captureNames.indexOf("injection.filename")
        val separate = ArrayList<Pending>()
        val combined = LinkedHashMap<String, Pending>()
        val seen = HashSet<String>()
        for (w in where.indices step 2) {
            for (m in q.matches(parent.tree, where[w], where[w + 1], text, content).toList()) {
                val st = settingsOf(parent.language, q, m.pattern)
                var lang: String? = st.language?.let { registry.aliasFor(it) }
                if (st.self) lang = parent.language
                if (st.parent) lang = parent.parentLanguage ?: parent.language
                m.captures.firstOrNull { it.index == langCapture }?.let { lang = registry.aliasFor(slice(text, it.start, it.end)) }
                m.captures.firstOrNull { it.index == fileCapture }?.let { lang = registry.forFile(slice(text, it.start, it.end)) }
                val l = lang ?: continue
                if (l !in backend.languages) continue
                if (registry.query(l, QueryKind.HIGHLIGHTS) == null && registry.query(l, QueryKind.INJECTIONS) == null) continue
                // a match found again from a second changed range
                if (!st.combined && !seen.add("${m.pattern}/" + m.captures.joinToString(",") { "${it.start}-${it.end}" })) continue
                val target = if (st.combined) combined.getOrPut("${m.pattern}/$l") { Pending(l, m.pattern) } else Pending(l, m.pattern).also { separate += it }
                for (c in m.captures) {
                    target.extentStart = minOf(target.extentStart, c.start)
                    target.extentEnd = maxOf(target.extentEnd, c.end)
                    if (c.index == content) contentRanges(c, st, target.ranges)
                }
            }
        }
        return (separate + combined.values).mapNotNull { p ->
            val ranges = normalize(p.ranges)
            if (ranges.isEmpty()) null else Site(p.pattern, p.language, ranges, p.extentStart, p.extentEnd)
        }
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

    private fun parser(lang: String): ParserHandle = parsers.getOrPut(lang) {
        backend.ensureLanguage(lang)
        backend.newParser(lang).also { if (timeoutMicros > 0) it.setTimeoutMicros(timeoutMicros) }
    }

    private fun query(lang: String, kind: QueryKind): QueryHandle? {
        val key = "$lang/${kind.file}"
        if (key in queries) return queries[key]
        val text = registry.query(lang, kind)
        val q = text?.let { backend.ensureLanguage(lang); backend.newQuery(lang, it) }
        queries[key] = q
        return q
    }

    /** Token class index (into [TokenClasses.ALL]) per capture of [q]; [NONE] = `@none`, [SKIP] = not drawn. */
    private fun classes(lang: String, q: QueryHandle): IntArray = classIndex.getOrPut(lang) {
        IntArray(q.captureNames.size) { i ->
            val name = q.captureNames[i]
            if (name == "none") NONE else tokenClassFor(name)?.let { TokenClasses.ALL.indexOf(it) } ?: SKIP
        }
    }

    private companion object {
        /** `@none`: an explicit "no colour" that wins over an outer node's colour. */
        const val NONE = -1
        /** Not drawn and not competing (`@spell`, `_helper`, unknown names). */
        const val SKIP = -2

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
            for (i in r.indices step 2) for (j in parent.indices step 2) {
                val a = maxOf(r[i], parent[j])
                val b = minOf(r[i + 1], parent[j + 1])
                if (b > a) { out += a; out += b }
            }
            return out.toIntArray()
        }

        /** Paint class [k] over [from, to) (absolute), only inside [ranges] when there are any. */
        fun paintRange(paint: IntArray, base: Int, from: Int, to: Int, k: Int, ranges: IntArray) {
            if (ranges.isEmpty()) { for (p in from until to) paint[p - base] = k; return }
            for (i in ranges.indices step 2) {
                val a = maxOf(from, ranges[i])
                val b = minOf(to, ranges[i + 1])
                for (p in a until b) paint[p - base] = k
            }
        }
    }
}
