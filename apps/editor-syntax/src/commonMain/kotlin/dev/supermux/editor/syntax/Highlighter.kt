package dev.supermux.editor.syntax

import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.Ranged

/**
 * One parse of one document version, including injected sub-trees. Owned by the worker: it
 * holds native trees, so it never goes into an EditorState. [close] frees every tree.
 */
class ParsedDocument internal constructor(internal val layers: List<Layer>, val length: Int) : AutoCloseable {
    private var closed = false

    val language: String get() = layers[0].language

    /** The injected layers as "depth:language", in parse order (host excluded). */
    val injections: List<String> get() = layers.drop(1).map { "${it.depth}:${it.language}" }

    /** Record one edit in every tree (host and injections), before the next [Highlighter.parse]. */
    fun edit(e: TextEdit) {
        check(!closed) { "document closed" }
        val delta = e.newEnd - e.oldEnd
        for (l in layers) {
            l.tree.edit(e)
            if (l.ranges.isNotEmpty()) {
                l.ranges = IntArray(l.ranges.size) { i ->
                    val p = l.ranges[i]
                    when { p < e.start -> p; p >= e.oldEnd -> p + delta; else -> e.start }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        for (l in layers) l.tree.close()
    }
}

/**
 * A parse tree over part of the document: the host (depth 0, the whole document, [ranges] empty)
 * or an injection (its included UTF-16 [ranges], packed [start, end]*).
 */
internal class Layer(
    val language: String,
    val tree: TreeHandle,
    var ranges: IntArray,
    val depth: Int,
    val pattern: Int,
    val parentLanguage: String?,
) {
    fun key() = layerKey(depth, parentLanguage, language, pattern, ranges.firstOrNull() ?: 0)
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
            layers += Layer(language, host.parse(text, previous?.layers?.get(0)?.tree), IntArray(0), 0, -1, null)
            val old = previous?.layers?.drop(1)?.associateBy { it.key() } ?: emptyMap()
            var i = 0
            while (i < layers.size) { inject(layers[i], text, length, old, layers); i++ }
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
            // the last capture (latest pattern) of each node range
            val winner = HashMap<Long, Int>()
            for (i in 0 until c.size) {
                if (cls[c.capture(i)] == SKIP || c.end(i) <= c.start(i)) continue
                val k = c.start(i).toLong() shl 32 or c.end(i).toLong()
                val prev = winner[k]
                if (prev == null || c.pattern(i) >= c.pattern(prev)) winner[k] = i
            }
            // outer nodes first, so inner ones paint over them
            val order = winner.values.sortedByDescending { c.end(it) - c.start(it) }
            // `@none` wins like any capture and clears the colour under it
            for (i in order) paintRange(paint, s, maxOf(s, c.start(i)), minOf(e, c.end(i)), maxOf(-1, cls[c.capture(i)]), layer.ranges)
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
        val found = sortedSetOf<Long>()
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
        val out = IntArray(found.size * 2)
        found.forEachIndexed { i, v -> out[2 * i] = (v ushr 32).toInt(); out[2 * i + 1] = v.toInt() }
        return out
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

    private class Pending(val language: String, val pattern: Int, val ranges: ArrayList<Int> = ArrayList())

    /** Find [parent]'s injections, parse each (reusing [old]'s layer at the same place), append to [out]. */
    private fun inject(parent: Layer, text: TextSource, length: Int, old: Map<String, Layer>, out: MutableList<Layer>) {
        if (parent.depth >= maxDepth) return
        val q = query(parent.language, QueryKind.INJECTIONS) ?: return
        val content = q.captureNames.indexOf("injection.content")
        if (content < 0) return
        val langCapture = q.captureNames.indexOf("injection.language")
        val fileCapture = q.captureNames.indexOf("injection.filename")
        val from = parent.ranges.firstOrNull() ?: 0
        val to = parent.ranges.lastOrNull() ?: length
        val separate = ArrayList<Pending>()
        val combined = LinkedHashMap<String, Pending>()
        for (m in q.matches(parent.tree, from, to, text, content).toList()) {
            val st = settingsOf(parent.language, q, m.pattern)
            var lang: String? = st.language?.let { registry.aliasFor(it) }
            if (st.self) lang = parent.language
            if (st.parent) lang = parent.parentLanguage ?: parent.language
            m.captures.firstOrNull { it.index == langCapture }?.let { lang = registry.aliasFor(slice(text, it.start, it.end)) }
            m.captures.firstOrNull { it.index == fileCapture }?.let { lang = registry.forFile(slice(text, it.start, it.end)) }
            val l = lang ?: continue
            if (l !in backend.languages) continue
            if (registry.query(l, QueryKind.HIGHLIGHTS) == null && registry.query(l, QueryKind.INJECTIONS) == null) continue
            val target = if (st.combined) combined.getOrPut("${m.pattern}/$l") { Pending(l, m.pattern) } else Pending(l, m.pattern).also { separate += it }
            for (c in m.captures) if (c.index == content) contentRanges(c, st, target.ranges)
        }
        for (p in separate + combined.values) {
            val ranges = clip(normalize(p.ranges), parent.ranges)
            if (ranges.isEmpty()) continue
            backend.ensureLanguage(p.language)
            val parser = parser(p.language)
            parser.setIncludedRanges(ranges, text)
            val reuse = old[layerKey(parent.depth + 1, parent.language, p.language, p.pattern, ranges[0])]
            out += Layer(p.language, parser.parse(text, reuse?.tree), ranges, parent.depth + 1, p.pattern, parent.language)
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
