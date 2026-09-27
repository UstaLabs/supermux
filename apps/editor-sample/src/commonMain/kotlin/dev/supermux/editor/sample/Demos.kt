package dev.supermux.editor.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.GutterClickHandler
import dev.supermux.editor.core.LineMapping
import dev.supermux.editor.compose.LinkedScroll
import dev.supermux.editor.compose.LinkedSide
import dev.supermux.editor.compose.WidgetClickHandler
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.compose.widgetClickFacet
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.Panel
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.core.panelsFacet

// ------------------------------------------------------------------ the M3c demo --
//
// What M4's plugins will do, faked just enough to try M3c's surface by hand: lint and diff markers
// (a click says which line), fold arrows that really fold ({ ... } ranges, "⋯" to unfold), a review
// thread under a line with a reply field (the 💬 marker opens and closes it), an inline type hint,
// and a find panel.

/** The demo's own memory: its folds (mapped through edits), its markers, the thread and the panel. */
private class DemoValue(
    val folds: RangeSet<Decoration>,
    val markers: RangeSet<GutterMarker>,
    val threadAt: Int,
    val threadOpen: Boolean,
    val hintAt: Int,
)

object M3cDemo {
    private val toggleFold = StateEffectType<Int>("demo.fold") // a line to fold / unfold
    private val unfoldAt = StateEffectType<Int>("demo.unfold") // a fold's start
    private val toggleThread = StateEffectType<Unit>("demo.thread")

    /** The find panel: in or out of the configuration. */
    private val panelSlot = dev.supermux.editor.core.Compartment("demo.panel")

    /** Show or hide the demo's find panel. */
    fun setPanel(view: dev.supermux.editor.core.CommandTarget, on: Boolean) =
        view.dispatch(TransactionSpec(effects = listOf(panelSlot.reconfigure(if (on) panelsFacet.of(Panel("find", top = true)) else extensionOf()))))

    /** The line the review thread hangs under (0-based), clamped to the document. */
    private const val THREAD_LINE = 12

    private val field: StateField<DemoValue> = StateField(
        "demo",
        { st -> initial(st) },
        { v, tr -> update(v, tr) },
        { f ->
            extensionOf(
                gutterMarkersFacet.compute(FacetDep.field(f)) { it.field(f).markers },
                gutterMarkersFacet.compute(FacetDep.field(f), FacetDep.Doc) { foldMarkers(it.doc, it.field(f).folds) },
                decorationsFacet.compute(FacetDep.field(f)) { st ->
                    val v = st.field(f)
                    val extra = ArrayList<Ranged<Decoration>>()
                    if (v.threadOpen) extra += Ranged(v.threadAt, v.threadAt, Decoration.BlockWidget(WidgetKey("thread", "t1"), estimatedHeightLines = 5f))
                    extra += Ranged(v.hintAt, v.hintAt, Decoration.InlineWidget(WidgetKey("hint", "h1"), side = 1))
                    v.folds.update(add = extra)
                },
            )
        },
    )

    private fun initial(st: EditorState): DemoValue {
        val doc = st.doc
        fun at(line: Int) = doc.lineStart(line.coerceIn(0, doc.lineCount - 1))
        val m = ArrayList<Ranged<GutterMarker>>()
        m += Ranged(at(4), at(4), GutterMarker("lint", "lint-error", "error: unresolved reference (demo)"))
        m += Ranged(at(9), at(9), GutterMarker("lint", "lint-warning", "warning: unused variable (demo)"))
        for (l in 20..24) m += Ranged(at(l), at(l), GutterMarker("diff", "diff-add", "added line (demo)"))
        m += Ranged(at(27), at(27), GutterMarker("diff", "diff-remove", "removed lines (demo)"))
        m += Ranged(at(30), at(30), GutterMarker("diff", "diff-change", "changed line (demo)"))
        val thread = (THREAD_LINE).coerceAtMost(doc.lineCount - 1)
        m += Ranged(at(thread), at(thread), GutterMarker("comment", "comment", "review thread: 1 comment"))
        // The hint after the first "val x" found (else the end of line 6).
        val text = doc.slice(0, minOf(doc.length, 20_000))
        val v = Regex("\\bval [A-Za-z_][A-Za-z0-9_]*").find(text)
        val hint = v?.range?.last?.plus(1) ?: (at(7) - 1).coerceAtLeast(0)
        return DemoValue(RangeSet.empty(), RangeSet.of(m), at(thread), threadOpen = true, hintAt = hint)
    }

    private fun update(v: DemoValue, tr: Transaction): DemoValue {
        var folds = v.folds.map(tr.changes)
        val markers = v.markers.map(tr.changes)
        var threadAt = tr.changes.mapPos(v.threadAt, -1)
        var open = v.threadOpen
        val doc = tr.state.doc
        for (e in tr.effects) {
            e.valueIf(toggleFold)?.let { line ->
                val start = doc.lineStart(line)
                val existing = folds.firstOrNull { doc.lineIndexAt(it.from) == line }
                folds = if (existing != null) folds.update(filter = { it !== existing })
                else foldRange(doc, line)?.let { (a, b) -> folds.update(add = listOf(Ranged(a, b, Decoration.Replace(WidgetKey("fold", "f$start"), fold = true)))) } ?: folds
            }
            e.valueIf(unfoldAt)?.let { at -> folds = folds.update(filter = { it.from != at }) }
            e.valueIf(toggleThread)?.let { open = !open }
        }
        if (threadAt > doc.length) threadAt = doc.length
        return DemoValue(folds, markers, doc.lineStart(doc.lineIndexAt(threadAt)), open, tr.changes.mapPos(v.hintAt, 1))
    }

    /** A line ending in `{`: from after it to its matching `}` (which stays shown after the "⋯"). */
    private fun foldRange(doc: Rope, line: Int): Pair<Int, Int>? {
        val start = doc.lineStart(line)
        val end = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length
        if (end <= start || doc.charAt(end - 1) != '{') return null
        var depth = 0
        var i = end - 1
        val limit = minOf(doc.length, end + 200_000)
        while (i < limit) {
            when (doc.charAt(i)) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return if (doc.lineIndexAt(i) > line) end to i else null } }
            i++
        }
        return null
    }

    /** A fold arrow on every line ending in `{` (in the first 3,000 lines): open, or closed when folded. */
    private fun foldMarkers(doc: Rope, folds: RangeSet<Decoration>): RangeSet<GutterMarker> {
        val folded = folds.mapTo(HashSet()) { doc.lineIndexAt(it.from) }
        val out = ArrayList<Ranged<GutterMarker>>()
        for (l in 0 until minOf(doc.lineCount, 3000)) {
            val s = doc.lineStart(l)
            val e = if (l + 1 < doc.lineCount) doc.lineStart(l + 1) - 1 else doc.length
            if (e > s && doc.charAt(e - 1) == '{') {
                val closed = l in folded
                out += Ranged(s, s, GutterMarker("fold", if (closed) "fold-closed" else "fold-open", if (closed) "unfold" else "fold"))
            }
        }
        return RangeSet.of(out)
    }

    /** The demo: its state field, and its say over gutter clicks (fold arrows, the 💬) and the "⋯". */
    fun extension(onGutter: (String) -> Unit = {}): Extension = extensionOf(
        field,
        panelSlot.of(extensionOf()),
        gutterClickFacet.of(GutterClickHandler { t, column, line, marker ->
            when (column) {
                "fold" -> { if (marker != null) t.dispatch(TransactionSpec(effects = listOf(toggleFold.of(line)))); true }
                "comment" -> { if (marker != null) t.dispatch(TransactionSpec(effects = listOf(toggleThread.of(Unit)))); true }
                else -> { onGutter("gutter click: $column, line ${line + 1}${marker?.tooltip?.let { " — $it" } ?: ""}"); true }
            }
        }),
        widgetClickFacet.of(WidgetClickHandler { t, key, from, _ ->
            if (key.type == "fold") { t.dispatch(TransactionSpec(effects = listOf(unfoldAt.of(from)))); true } else false
        }),
        // Backspace into a fold, a search landing inside one: unfold (the editor's unfold-first default).
        dev.supermux.editor.compose.revealFacet.of(dev.supermux.editor.compose.RevealHandler { t, from, _ ->
            t.dispatch(TransactionSpec(effects = listOf(unfoldAt.of(from)))); true
        }),
    )

    fun panelShown(state: EditorState): Boolean = state.facet(panelsFacet).any { it.id == "find" }

    /** The demo's widget content: the review thread, the type hint, the find panel. */
    @Composable
    fun rememberWidgets(ink: Color, chrome: Color): WidgetRegistry = remember(ink, chrome) {
        WidgetRegistry().apply {
            register("thread") { _ ->
                val comments = remember { mutableStateListOf("Ahmet: does this still hold after a reload from disk?") }
                val draft = rememberTextFieldState()
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp))
                        .background(chrome).border(1.dp, ink.copy(alpha = 0.25f), RoundedCornerShape(6.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText("💬 review thread (a block widget)", style = TextStyle(color = ink.copy(alpha = 0.7f), fontSize = 11.sp))
                    for (c in comments) BasicText(c, style = TextStyle(color = ink, fontSize = 13.sp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicTextField(
                            draft,
                            Modifier.weight(1f).background(ink.copy(alpha = 0.08f)).padding(6.dp).testTag("thread-reply")
                                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { focusEditor(); true } else false },
                            textStyle = TextStyle(color = ink, fontSize = 13.sp),
                        )
                        BasicText(
                            "Reply",
                            Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF4BBAA7).copy(alpha = 0.35f)).clickable {
                                val t = draft.text.toString().trim()
                                if (t.isNotEmpty()) { comments += "you: $t"; draft.clearText() }
                            }.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = TextStyle(color = ink, fontSize = 13.sp),
                        )
                    }
                }
            }
            register("hint") {
                BasicText(
                    ": Hint",
                    Modifier.clip(RoundedCornerShape(3.dp)).background(ink.copy(alpha = 0.12f)).padding(horizontal = 3.dp),
                    style = TextStyle(color = ink.copy(alpha = 0.6f), fontSize = 11.sp),
                )
            }
            register("panel:find") {
                val query = rememberTextFieldState()
                Row(
                    Modifier.fillMaxWidth().background(chrome).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText("find:", style = TextStyle(color = ink, fontSize = 13.sp))
                    BasicTextField(query, Modifier.weight(1f).background(ink.copy(alpha = 0.08f)).padding(6.dp).testTag("find-field"), textStyle = TextStyle(color = ink, fontSize = 13.sp))
                    val n = if (query.text.isEmpty()) 0 else Regex(Regex.escape(query.text.toString())).findAll(editor.state.doc.slice(0, minOf(editor.state.doc.length, 500_000))).count()
                    BasicText("$n found · Esc: back to the editor", style = TextStyle(color = ink.copy(alpha = 0.7f), fontSize = 11.sp))
                    BasicText("✕", Modifier.clickable { setPanel(editor, false); focusEditor() }.padding(6.dp), style = TextStyle(color = ink, fontSize = 13.sp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ side by side --

/**
 * Line diff (Myers, O((N+M)·D)) of [a] against [b], as [LineMapping] hunks: what M4's diff plugin
 * will compute (this one is the sample's own, to feel linked scrolling on a real edit).
 */
fun lineDiff(a: List<String>, b: List<String>, maxD: Int = 2000): List<LineMapping.Hunk> {
    var p = 0
    while (p < a.size && p < b.size && a[p] == b[p]) p++
    var s = 0
    while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
    val n = a.size - p - s
    val m = b.size - p - s
    if (n == 0 && m == 0) return emptyList()
    if (n == 0 || m == 0) return listOf(LineMapping.Hunk(p, p + n, p, p + m))
    val max = n + m
    val off = max + 1
    val v = IntArray(2 * max + 3)
    val trace = ArrayList<IntArray>()
    var found = -1
    loop@ for (d in 0..minOf(max, maxD)) {
        trace += v.copyOf()
        var k = -d
        while (k <= d) {
            var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
            var y = x - k
            while (x < n && y < m && a[p + x] == b[p + y]) { x++; y++ }
            v[off + k] = x
            if (x >= n && y >= m) { found = d; break@loop }
            k += 2
        }
    }
    if (found < 0) return listOf(LineMapping.Hunk(p, p + n, p, p + m))
    // Walk back: every diagonal step is a matched pair of lines.
    val match = IntArray(n) { -1 }
    var x = n
    var y = m
    for (d in found downTo 1) {
        val vv = trace[d]
        val k = x - y
        val prevK = if (k == -d || (k != d && vv[off + k - 1] < vv[off + k + 1])) k + 1 else k - 1
        val prevX = vv[off + prevK]
        val prevY = prevX - prevK
        while (x > prevX && y > prevY) { x--; y--; match[x] = y }
        x = prevX
        y = prevY
    }
    while (x > 0 && y > 0) { x--; y--; match[x] = y }
    val hunks = ArrayList<LineMapping.Hunk>()
    var i = 0
    var j = 0
    while (i < n || j < m) {
        if (i < n && match[i] == j) { i++; j++; continue }
        var i2 = i
        while (i2 < n && match[i2] < 0) i2++
        val j2 = if (i2 < n) match[i2] else m
        hunks += LineMapping.Hunk(p + i, p + i2, p + j, p + j2)
        i = i2
        j = j2
    }
    return hunks
}

/** The side-by-side demo's per-side decorations (diff line tints, markers) and, in B, the line mapping. */
private class DiffSide(val decos: RangeSet<Decoration>, val markers: RangeSet<GutterMarker>, val mapping: dev.supermux.editor.core.LineMapping? = null)

private val setDiff = StateEffectType<DiffSide>("demo.diff")

private val diffField: StateField<DiffSide> = StateField(
    "demo.diffSide",
    { DiffSide(RangeSet.empty(), RangeSet.empty()) },
    { v, tr ->
        var out = DiffSide(v.decos.map(tr.changes), v.markers.map(tr.changes), v.mapping)
        for (e in tr.effects) e.valueIf(setDiff)?.let { out = it }
        out
    },
    { f ->
        extensionOf(
            decorationsFacet.compute(FacetDep.field(f)) { it.field(f).decos },
            gutterMarkersFacet.compute(FacetDep.field(f)) { it.field(f).markers },
            // What M4's diff plugin will do: the mapping as data; the surface aligns the rows itself.
            dev.supermux.editor.core.lineMappingFacet.compute(FacetDep.field(f)) { it.field(f).mapping ?: dev.supermux.editor.core.LineMapping.IDENTITY },
        )
    },
)

/** The extension each side of the side-by-side demo carries. */
val sideBySideExtension: Extension get() = diffField

/**
 * B: the sample file with a few fake edits (a deleted run, an inserted run, a changed run of
 * another length), so the demo opens with every kind of hunk on screen.
 */
fun fakeWorkingCopy(text: String): String {
    val lines = text.split('\n').toMutableList()
    if (lines.size < 200) return text + "\n// an added line\n"
    lines.subList(150, 152).let { it.clear(); it.addAll(listOf("    // changed: one run of two lines", "    // became four lines", "    // in the working copy", "    // (side-by-side demo)")) }
    lines.addAll(101, listOf("    // inserted in the working copy (1/3)", "    // inserted in the working copy (2/3)", "    // inserted in the working copy (3/3)"))
    lines.subList(40, 45).clear()
    return lines.joinToString("\n")
}

/**
 * Recompute the diff of [a] against [b] and put it in both: diff tints and markers, and the line
 * mapping (in B's state: `lineMappingFacet`). No gap widgets: the linked surfaces pad the rows
 * from their measured heights.
 */
fun applyDiff(a: dev.supermux.editor.compose.EditorView, b: dev.supermux.editor.compose.EditorView) {
    val da = a.state.doc
    val db = b.state.doc
    val hunks = lineDiff(da.toString().split('\n'), db.toString().split('\n'))
    fun side(doc: Rope, isA: Boolean): DiffSide {
        val decos = ArrayList<Ranged<Decoration>>()
        val markers = ArrayList<Ranged<GutterMarker>>()
        for (h in hunks) {
            val from = if (isA) h.aFrom else h.bFrom
            val to = if (isA) h.aTo else h.bTo
            val other = if (isA) h.bTo - h.bFrom else h.aTo - h.aFrom
            val kind = when { to == from -> null; other == 0 -> if (isA) "diff-remove" else "diff-add"; else -> "diff-change" }
            if (kind != null) for (l in from until to) {
                val s = doc.lineStart(l)
                decos += Ranged(s, s, Decoration.LineStyle(setOf(kind)))
                markers += Ranged(s, s, GutterMarker("diff", kind, kind.removePrefix("diff-")))
            }
        }
        return DiffSide(RangeSet.of(decos), RangeSet.of(markers), if (isA) null else dev.supermux.editor.core.LineMapping(hunks.map { dev.supermux.editor.core.LineMapping.Hunk(it.aFrom, it.aTo, it.bFrom, it.bTo) }))
    }
    a.dispatch(TransactionSpec(effects = listOf(setDiff.of(side(da, true)))))
    b.dispatch(TransactionSpec(effects = listOf(setDiff.of(side(db, false)))))
}

/** The side-by-side pane: A (the file, read-only) and B (its working copy) linked line by line. */
@Composable
fun SideBySidePane(a: SampleSession, b: SampleSession, theme: EditorTheme, stats: FrameStats?, onFontSize: (Float) -> Unit) {
    val link = remember(a, b) { LinkedScroll() }
    val tinted = remember(theme) {
        val dark = theme.background.red < 0.5f
        theme.copy(lineClassBackgrounds = theme.lineClassBackgrounds + mapOf(
            "diff-add" to (if (dark) Color(0x2E3FB950) else Color(0x333FB950)),
            "diff-remove" to (if (dark) Color(0x33F85149) else Color(0x33F85149)),
            "diff-change" to (if (dark) Color(0x2E58A6FF) else Color(0x2E2F6FD6)),
        ))
    }
    // B is the working copy: every edit recomputes the diff, a frame later (never from inside B's
    // own dispatch; M4's diff plugin will debounce and run off the UI thread).
    androidx.compose.runtime.LaunchedEffect(a, b, link) {
        androidx.compose.runtime.snapshotFlow { b.view.state.doc }.collect { applyDiff(a.view, b.view) }
    }
    Row(Modifier.fillMaxSize()) {
        Editor(a.view, Modifier.weight(1f).fillMaxHeight(), theme = tinted, readOnly = true, onViewport = a::onViewport,
            onFontSize = onFontSize, label = "Base (A)", linked = link, linkedSide = LinkedSide.A)
        Box(Modifier.width(1.dp).fillMaxHeight().background(theme.gutterForeground.copy(alpha = 0.5f)))
        Editor(b.view, Modifier.weight(1f).fillMaxHeight(), theme = tinted, onViewport = b::onViewport,
            onPaint = stats?.let { s -> { s.drawEnd() } }, onFontSize = onFontSize, label = "Working copy (B)", linked = link, linkedSide = LinkedSide.B)
    }
}
